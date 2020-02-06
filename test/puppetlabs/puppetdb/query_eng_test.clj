(ns puppetlabs.puppetdb.query-eng-test
  (:require [cheshire.core :as json]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.scf.storage :as scf-store]
            [puppetlabs.puppetdb.query-eng.engine :refer :all]
            [puppetlabs.puppetdb.query-eng :refer [entity-fn-idx]]
            [puppetlabs.puppetdb.jdbc :refer [with-transacted-connection]]
            [puppetlabs.puppetdb.testutils :refer [get-request parse-result]]
            [puppetlabs.puppetdb.testutils.db :refer [*db* with-test-db]]
            [puppetlabs.puppetdb.testutils.http :refer [*app* deftest-http-app]]
            [puppetlabs.puppetdb.time :as time]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.scf.storage-utils :as su]
            [puppetlabs.puppetdb.time :refer [now parse-period]]))

(deftest test-plan-sql
  (let [col1 {:type :string :field :foo}
        col2 {:type :string :field :bar}]
    (are [sql plan] (= sql (plan->sql plan {:node-purge-ttl (parse-period "14d")}))

         [:or [:= (:field col1) "?"]]
         (->BinaryExpression := col1 "?")

         (su/sql-regexp-match (:field col1))
         (->RegexExpression col1 "?")

         (su/sql-array-query-string (:field col1))
         (->ArrayBinaryExpression col1 "?")

         [:and [:or [:= (:field col1) "?"]] [:or [:= (:field col2) "?"]]]
         (->AndExpression [(->BinaryExpression := col1 "?")
                           (->BinaryExpression := col2 "?")])

         [:or [:or [:= (:field col1) "?"]] [:or [:= (:field col2) "?"]]]
         (->OrExpression [(->BinaryExpression := col1 "?")
                          (->BinaryExpression := col2 "?")])

         [:not [:or [:= (:field col1) "?"]]]
         (->NotExpression (->BinaryExpression := col1 "?"))

         [:is (:field col1) nil]
         (->NullExpression col1 true)

         [:is-not (:field col1) nil]
         (->NullExpression col1 false))))

(deftest test-plan-cte
  (is (re-matches
         #"WITH inactive_nodes AS \(SELECT certname FROM certnames WHERE \(deactivated IS NOT NULL AND deactivated > '\d\d\d\d-\d\d-\d\dT\d\d:\d\d:\d\d\.\d\d\dZ'\) OR \(expired IS NOT NULL and expired > '\d\d\d\d-\d\d-\d\dT\d\d:\d\d:\d\d\.\d\d\dZ'\)\), active_nodes AS \(SELECT certname FROM certnames WHERE \(deactivated IS NULL AND expired IS NULL\)\) SELECT table.foo AS foo FROM table WHERE \(1 = 1\)"
         (plan->sql (map->Query {:projections {"foo" {:type :string
                                           :queryable? true
                                           :field :table.foo}}
                      :alias "thefoo"
                      :subquery? false
                      :where (->BinaryExpression := 1 1)
                      :selection {:from [:table]}
                      :source-table "table"}) {:node-purge-ttl (parse-period "14d")}))))

(deftest test-extract-params

  (are [expected plan] (= expected (extract-all-params plan))

       {:plan (->AndExpression [(->BinaryExpression "="  "foo" "?")
                                (->RegexExpression "bar" "?")
                                (->NotExpression (->BinaryExpression "=" "baz" "?"))])
        :params ["1" "2" "3"]}
       (->AndExpression [(->BinaryExpression "=" "foo" "1")
                         (->RegexExpression "bar" "2")
                         (->NotExpression (->BinaryExpression "=" "baz" "3"))])

       {:plan (map->Query {:where (->BinaryExpression "=" "foo" "?")})
        :params ["1"]}
       (map->Query {:where (->BinaryExpression "=" "foo" "1")})))

(deftest test-expand-user-query
  (is (= [["=" "prop" "foo"]]
         (expand-user-query [["=" "prop" "foo"]])))

  (is (= [["=" "prop" "foo"]
          ["in" "certname"
           ["extract" "certname"
            ["select_active_nodes"]]]]
         (expand-user-query [["=" "prop" "foo"]
                             ["=" ["node" "active"] true]])))
  (is (= [["=" "prop" "foo"]
          ["in" "resource"
           ["extract" "res_param_resource"
            ["select_params"
             ["and"
              ["=" "res_param_name" "bar"]
              ["=" "res_param_value" "\"baz\""]]]]]]
         (expand-user-query [["=" "prop" "foo"]
                             ["=" ["parameter" "bar"] "baz"]])))

  (testing "implicit subqueries"
    (are [context in out]
      (= (expand-user-query
          (push-down-context context in)) out)

      ;; Simplistic 1 column examples (catalogs example)
      (map->Query {:relationships
                   certname-relations})
      ["subquery" "resources"
       ["=" "type" "Class"]]
      ["in" ["certname"]
       ["extract" ["certname"]
        ["select_resources"
         ["=" "type" "Class"]]]]

      ;; Where local and foreign differ (resources example)
      (map->Query {:relationships
                   {"environments" {:local-columns ["environment"]
                                    :foreign-columns ["name"]}}})
      ["subquery" "environments"
       ["=" "name" "production"]]
      ["in" ["environment"]
       ["extract" ["name"]
        ["select_environments"
         ["=" "name" "production"]]]]

      ;; Two column examples (fact-contents example)
      (map->Query {:relationships
                   {"facts" {:columns ["certname" "name"]}}})
      ["subquery" "facts"
       ["=" "name" "networking"]]
      ["in" ["certname" "name"]
       ["extract" ["certname" "name"]
        ["select_facts"
         ["=" "name" "networking"]]]])))

(deftest test-extract-with-no-subexpression-compiles
  (is (re-find #"SELECT .*certname FROM reports"
               (->> ["extract" "certname"]
                    (compile-user-query->sql reports-query)
                    :results-query
                    first)))
  (is (re-find #"SELECT .*certname FROM reports"
               (->> ["extract" ["certname"]]
                    (compile-user-query->sql reports-query)
                    :results-query
                    first)))
  (is (re-find #"SELECT count\(reports.certname\) count FROM reports"
               (->> ["extract" [["function" "count" "certname"]]]
                    (compile-user-query->sql reports-query)
                    :results-query
                    first)))
  (is (re-find #"SELECT .*certname AS certname, count\(\*\) .* FROM reports"
               (->> ["extract" [["function" "count"] "certname"] ["group_by" "certname"]]
                    (compile-user-query->sql reports-query)
                    :results-query
                    first))))

(deftest test-extract-json-subtree-compiles
  (testing "with differing levels of subtrees"
    (is (re-find #"SELECT \(fs.stable\|\|fs.volatile\)->'os' AS \"facts\.os\" .*FROM factsets"
                 (->> ["extract" "facts.os"]
                      (compile-user-query->sql inventory-query)
                      :results-query
                      first)))
    (is (re-find #"SELECT \(fs.stable\|\|fs.volatile\)->'os'->'family' AS \"facts\.os\.family\" .*FROM factsets"
                 (->> ["extract" "facts.os.family"]
                      (compile-user-query->sql inventory-query)
                      :results-query
                      first))))

  (testing "when field is raw sql"
    (is (re-find #"SELECT \(fs.stable\|\|fs.volatile\)->'trusted'->'certname' AS \"trusted\.certname\" .*FROM factsets"
                 (->> ["extract" "trusted.certname"]
                      (compile-user-query->sql inventory-query)
                      :results-query
                      first))))

  (testing "when field is a keyword"
    (is (re-find #"SELECT rpc.parameters->'foo' AS \"parameters\.foo\" .*FROM catalog_resources"
                 (->> ["extract" "parameters.foo"]
                      (compile-user-query->sql resources-query)
                      :results-query
                      first)))))

(deftest test-valid-query-fields
  (is (thrown-with-msg? IllegalArgumentException
                        #"'foo' is not a queryable object for resources. Known queryable objects are.*"
                        (compile-user-query->sql resources-query ["=" "foo" "bar"])))
  (let [err #"All values in array must be the same type\."]
    (is (thrown-with-msg? IllegalArgumentException
                          err
                          (compile-user-query->sql nodes-query ["in" ["fact" "uptime_seconds"] ["array" [500 100.0]]])))
    (is (thrown-with-msg? IllegalArgumentException
                          err
                          (compile-user-query->sql nodes-query ["in" ["fact" "uptime_seconds"] ["array" ["500" 100.0]]])))))

(deftest test-valid-subqueries
  (is (thrown-with-msg? IllegalArgumentException
                        #"Unsupported subquery `foo`"
                        (compile-user-query->sql facts-query ["and",
                                                              ["=", "name", "uptime_hours"],
                                                              ["in", "certname",
                                                               ["extract", "certname",
                                                                ["foo",
                                                                 ["=", "facts_environment", "production"]]]]])))
  (is (thrown-with-msg? IllegalArgumentException
                        #"Unsupported subquery `select-facts` - did you mean `select_facts`?"
                        (compile-user-query->sql fact-contents-query ["in", "certname",
                                                                      ["extract", "certname",
                                                                       ["select-facts",
                                                                        ["=", "name", "osfamily"]]]])))
  (is (not (nil? (:results-query (compile-user-query->sql reports-query ["extract", ["hash"],
                                                                         ["or", ["=", "certname", "host-3"]]]))))))

(deftest no-jsonb-test
  (are [query-rec user-query] (not (re-matches
                                    #".*jsonb_each.*"
                                    (-> (compile-user-query->sql query-rec user-query)
                                        :results-query
                                        first)))
    nodes-query
    ["=" ["fact" "osfamily"] "Linux"]

    nodes-query
    ["in" "certname" ["extract" "certname"
                      ["select_facts"
                       ["and"
                        ["=" "name" "osfamily"]
                        ["=" "value" "Linux"]]]]]

    nodes-query
    ["in" "certname" ["extract" "certname"
                      ["select_facts"
                       ["and"
                        ["=" "name" "foobar"]
                        ["<" "value" 2]]]]]

    nodes-query
    ["and"
     ["~" "certname" ".*test.*"]
     ["=" ["fact" "osfamily"] "Linux"]]

    inventory-query
    ["and"
     ["~" "certname" ".*test.*"]
     ["=" ["fact" "osfamily"] "Linux"]]

    facts-query
    ["extract" ["name" "value"]
     ["=" "name" "foo"]]

    facts-query
    ["and"
     ["=" "name" "foo"]
     ["~" "certname" "abc.*"]]

    facts-query
    ["extract" ["name" "value"]
     ["and"
      ["=" "name" "foo"]
      ["~" "certname" "abc.*"]]]

    facts-query
    ["extract" ["value" ["function" "count"]]
     ["=" "name" "foo"]
     ["group_by" "value"]]))


(deftest-http-app query-recs-are-swappable
  [version [:v4]
   endpoint ["/v4/fact-names"]
   :let [facts1 {"domain" "testing.com"
                  "hostname" "foo1"
                  "kernel" "Linux"
                  "operatingsystem" "Debian"
                  "uptime_seconds" "4000"}
         facts2 {"domain" "testing.com"
                 "hostname" "foo2"
                 "kernel" "Linux"
                 "operatingsystem" "RedHat"
                 "uptime_seconds" "6000"}
         facts3 {"domain" "testing.com"
                 "hostname" "foo3"
                 "kernel" "Darwin"
                 "operatingsystem" "Darwin"
                 "memorysize" "16.00 GB"}
         initial-idx @entity-fn-idx]]

  (with-transacted-connection *db*
    (scf-store/add-certname! "foo1")
    (scf-store/add-certname! "foo2")
    (scf-store/add-certname! "foo3")
    (scf-store/add-facts! {:certname "foo2"
                           :values facts2
                           :timestamp (now)
                           :environment "DEV"
                           :producer_timestamp (now)
                           :producer "bar2"})
    (scf-store/add-facts! {:certname "foo3"
                           :values facts3
                           :timestamp (now)
                           :environment "DEV"
                           :producer_timestamp (now)
                           :producer "bar3"})
    (scf-store/deactivate-node! "foo1")
    (scf-store/add-facts! {:certname "foo1"
                           :values  facts1
                           :timestamp (now)
                           :environment "DEV"
                           :producer_timestamp (now)
                           :producer "bar1"}))

  (let [expected-result ["domain" "hostname" "kernel" "memorysize"
                         "operatingsystem" "uptime_seconds"]]
    (testing "fact-names behaves normally"
      (let [request (get-request endpoint)
            {:keys [status body]} (*app* request)
            result (vec (parse-result body))]
        (is (= status http/status-ok))
        (is (= result expected-result))))

    (testing "query rec is modifiable"
      (swap! entity-fn-idx assoc-in [:fact-names :munge] (fn [_ _] identity))

      (swap! entity-fn-idx
             assoc-in [:fact-names :rec :projections "depth"] {:type :integer
                                                               :queryable? true
                                                               :field :depth})
      (let [request (get-request endpoint)
            {:keys [status body]} (*app* request)
            result (vec (parse-result body))]
        (is (= status http/status-ok))
        (is (= result (map #(hash-map :name % :depth 0) expected-result)))))

    (reset! entity-fn-idx initial-idx)
    (testing "fact-names back to normal"
      (let [request (get-request endpoint)
            {:keys [status body]} (*app* request)
            result (vec (parse-result body))]
        (is (= status http/status-ok))
        (is (= result expected-result))))))

(deftest-http-app fact-expiration-queries
  [version [:v4]
   endpoint ["/v4/nodes"]]

  (with-transacted-connection *db*
    (scf-store/add-certname! "foo1")
    (scf-store/add-certname! "foo2")
    (scf-store/add-certname! "foo3")

    (scf-store/set-certname-facts-expiration "foo1" false (now))
    (scf-store/set-certname-facts-expiration "foo2" true (now)))

  (testing "test facts expiring for nodes set to false"
    (let [request (get-request endpoint
                               (json/generate-string ["=" "expires_facts" false])
                               {:include_facts_expiration true})
          {:keys [status body]} (*app* request)
          result (vec (parse-result body))]

      (is (= status http/status-ok))
      (is (= 1 (count result)))
      (let [node (first result)]
        (is (= false (:expires_facts node)))
        (is (= "foo1" (:certname node)))
        (is (-> node :expires_facts_updated time/parse-wire-datetime time/date-time?)))))

  (testing "test facts expiring for nodes set to true (default)"
    (let [request (get-request endpoint
                               (json/generate-string ["=" "expires_facts" true])
                               {:include_facts_expiration true})
          {:keys [status body]} (*app* request)
          result (vec (parse-result body))]

      (is (= status http/status-ok))
      (is (= 2 (count result)))
      (let [nodes (sort-by :certname result)]
        (is (= true (:expires_facts (first nodes))))
        (is (= "foo2" (:certname (first nodes))))
        (is (-> nodes first :expires_facts_updated time/parse-wire-datetime
                time/date-time?))

        (is (= true (:expires_facts (second nodes))))
        (is (= "foo3" (:certname (second nodes))))
        (is (nil? (:expires_facts_updated (second nodes)))))))

  (testing "/nodes/foo also respects include_facts_expiration=true"
    (let [request (get-request (str endpoint "/foo1")
                               nil
                               {:include_facts_expiration true})
          {:keys [status body]} (*app* request)
          result (parse-result body)]
      (is (= status http/status-ok))
      (is (= "foo1" (:certname result)))
      (is (= false (:expires_facts result)))
      (is (-> result :expires_facts_updated time/parse-wire-datetime
              time/date-time?)))))
