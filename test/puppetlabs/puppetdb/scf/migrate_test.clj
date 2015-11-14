(ns puppetlabs.puppetdb.scf.migrate-test
  (:require [puppetlabs.puppetdb.scf.hash :as hash]
            [puppetlabs.puppetdb.scf.migrate :as migrate]
            [puppetlabs.puppetdb.scf.storage :as store]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils
             :refer [db-serialize]]
            [cheshire.core :as json]
            [clojure.java.jdbc :as sql]
            [puppetlabs.puppetdb.scf.migrate :refer :all]
            [clj-time.coerce :refer [to-timestamp]]
            [clj-time.core :refer [now ago days]]
            [clojure.test :refer :all]
            [clojure.set :refer :all]
            [puppetlabs.puppetdb.jdbc :as jdbc :refer [query-to-vec]]
            [puppetlabs.puppetdb.testutils.db :as tdb
             :refer [*db* clear-db-for-testing!
                     schema-info-map diff-schema-maps]]
            [puppetlabs.kitchensink.core :as ks])
  (:import [java.sql SQLIntegrityConstraintViolationException]
           [org.postgresql.util PSQLException]))

(use-fixtures :each tdb/call-with-test-db)

(defn apply-migration-for-testing!
  [i]
  (let [migration (migrations i)]
    (migration)
    (record-migration! i)))

(defn fast-forward-to-migration!
  [migration-number]
  (doseq [[i migration] (sort migrations)
          :while (<= i migration-number)]
    (migration)
    (record-migration! i)))

(deftest migration
  (testing "pending migrations"
    (testing "should return every migration if the *db* isn't migrated"
      (jdbc/with-db-connection *db*
        (clear-db-for-testing!)
        (is (= (pending-migrations) migrations))))

    (testing "should return nothing if the *db* is completely migrated"
      (jdbc/with-db-connection *db*
        (clear-db-for-testing!)
        (migrate! *db*)
        (is (empty? (pending-migrations)))))

    (testing "should return missing migrations if the *db* is partially migrated"
      (jdbc/with-db-connection *db*
        (clear-db-for-testing!)
        (let [applied '(34 35 37)]
          (doseq [m applied]
            (apply-migration-for-testing! m))
          (is (= (set (keys (pending-migrations)))
                 (difference (set (keys migrations))
                             (set applied))))))))

  (testing "applying the migrations"
    (let [expected-migrations (apply sorted-set (keys migrations))]
      (jdbc/with-db-connection *db*
        (clear-db-for-testing!)
        (is (= (applied-migrations) #{}))
        (testing "should migrate the database"
          (migrate! *db*)
          (is (= (applied-migrations) expected-migrations)))

        (testing "should not do anything the second time"
          (migrate! *db*)
          (is (= (applied-migrations) expected-migrations)))

        (testing "should attempt a partial migration if there are migrations missing"
          (clear-db-for-testing!)
          ;; We are using migration 19 here because it is isolated enough to be able
          ;; to execute on its own. This might need to be changed in the future.
          (doseq [m (filter (fn [[i migration]] (not= i 36)) (pending-migrations))]
            (apply-migration-for-testing! (first m)))
          (is (= (keys (pending-migrations)) '(36)))
          (migrate! *db*)
          (is (= (applied-migrations) expected-migrations))))))

  (testing "should throw error if *db* is at a higher schema rev than we support"
    (jdbc/with-transacted-connection *db*
      (migrate! *db*)
      (jdbc/insert! :schema_migrations
                    {:version (inc migrate/desired-schema-version)
                     :time (to-timestamp (now))})
      (is (thrown? IllegalStateException (migrate! *db*))))))

(deftest migration-37
  (testing "should contain same reports before and after migration"
    (jdbc/with-db-connection *db*
      (clear-db-for-testing!)
      (fast-forward-to-migration! 36)

      (let [current-time (to-timestamp (now))]
        (jdbc/insert! :report_statuses
                      {:status "testing1" :id 1})
        (jdbc/insert! :environments
                      {:id 1 :environment "testing1"})
        (jdbc/insert! :certnames
                      {:certname "testing1" :deactivated nil}
                      {:certname "testing2" :deactivated nil})
        (jdbc/insert! :reports
                      {:hash (sutils/munge-hash-for-storage "01")
                       :transaction_uuid (sutils/munge-uuid-for-storage
                                          "bbbbbbbb-2222-bbbb-bbbb-222222222222")
                       :configuration_version "thisisacoolconfigversion"
                       :certname "testing1"
                       :puppet_version "0.0.0"
                       :report_format 1
                       :start_time current-time
                       :end_time current-time
                       :receive_time current-time
                       :producer_timestamp current-time
                       :environment_id 1
                       :status_id 1
                       :metrics (sutils/munge-json-for-storage [{:foo "bar"}])
                       :logs (sutils/munge-json-for-storage [{:bar "baz"}])}
                      {:hash (sutils/munge-hash-for-storage "0000")
                       :transaction_uuid (sutils/munge-uuid-for-storage
                                          "aaaaaaaa-1111-aaaa-1111-aaaaaaaaaaaa")
                       :configuration_version "blahblahblah"
                       :certname "testing2"
                       :puppet_version "911"
                       :report_format 1
                       :start_time current-time
                       :end_time current-time
                       :receive_time current-time
                       :producer_timestamp current-time
                       :environment_id 1
                       :status_id 1
                       :metrics (sutils/munge-json-for-storage [{:foo "bar"}])
                       :logs (sutils/munge-json-for-storage [{:bar "baz"}])})

        (jdbc/update! :certnames
                      {:latest_report_id 1}
                      ["certname = ?" "testing1"])
        (jdbc/update! :certnames
                      {:latest_report_id 2}
                      ["certname = ?" "testing2"])

        (apply-migration-for-testing! 37)

        (let [response
              (query-to-vec
               (format
                "SELECT %s AS hash, r.certname, e.environment, rs.status,
                        r.transaction_uuid::text AS uuid,
                        coalesce(metrics_json::jsonb, metrics) as metrics,
                        coalesce(logs_json::jsonb, logs) as logs
                   FROM certnames c
                     INNER JOIN reports r
                       ON c.latest_report_id=r.id AND c.certname=r.certname
                     INNER JOIN environments e ON r.environment_id=e.id
                     INNER JOIN report_statuses rs ON r.status_id=rs.id
                   ORDER BY c.certname"
                (sutils/sql-hash-as-str "r.hash")))]
          ;; every node should with facts should be represented
          (is (= [{:metrics [{:foo "bar"}] :logs [{:bar "baz"}]
                   :hash "01" :environment "testing1" :certname "testing1" :status "testing1" :uuid "bbbbbbbb-2222-bbbb-bbbb-222222222222"}
                  {:metrics [{:foo "bar"}] :logs [{:bar "baz"}]
                   :hash "0000" :environment "testing1" :certname "testing2" :status "testing1" :uuid "aaaaaaaa-1111-aaaa-1111-aaaaaaaaaaaa"}]
                 (map (comp #(update % :metrics sutils/parse-db-json)
                            #(update % :logs sutils/parse-db-json)) response))))

        (let [[id1 id2] (map :id
                              (query-to-vec "SELECT id from reports order by certname"))]

          (let [latest-ids (map :latest_report_id
                                (query-to-vec "select latest_report_id from certnames order by certname"))]
            (is (= [id1 id2] latest-ids))))))))

(deftest migration-in-different-schema
  (jdbc/with-db-connection *db*
    (clear-db-for-testing!)
    (jdbc/with-db-connection (tdb/db-admin-config)
      (let [db (tdb/subname->validated-db-name (:subname *db*))
            user (get-in tdb/test-env [:user :name])]
        (assert (tdb/valid-sql-id? db))
        (jdbc/do-commands (format "grant create on database %s to %s"
                                  db (get-in tdb/test-env [:user :name])))))
    (jdbc/do-commands
     ;; Cleaned up in clear-db-for-testing!
     "CREATE SCHEMA pdbtestschema"
     "SET SCHEMA 'pdbtestschema'")
    ((migrations 34))
    (record-migration! 34)
    (let [tables (sutils/sql-current-connection-table-names)]
      ;; Currently sql-current-connection-table-names only looks in public.
      (is (empty? (sutils/sql-current-connection-table-names)))
      (migrate! *db*))))

(deftest test-hash-field-not-nullable
  (jdbc/with-db-connection *db*
    (clear-db-for-testing!)
    (fast-forward-to-migration! 38)

    (let [factset-template {:timestamp (to-timestamp (now))
                            :environment_id (store/ensure-environment "prod")
                            :producer_timestamp (to-timestamp (now))}
          factset-data (map (fn [fs]
                               (merge factset-template fs))
                             [{:certname "foo.com"
                               :hash nil}
                              {:certname "bar.com"
                               :hash nil}
                              {:certname "baz.com"
                               :hash (sutils/munge-hash-for-storage "abc123")}])]

      (apply jdbc/insert! :certnames (map (fn [{:keys [certname]}]
                                            {:certname certname :deactivated nil})
                                          factset-data))
      (apply jdbc/insert! :factsets factset-data)

      (is (= 2 (:c (first (query-to-vec "SELECT count(*) as c FROM factsets where hash is null")))))

      (apply-migration-for-testing! 39)

      (is (zero? (:c (first (query-to-vec "SELECT count(*) as c FROM factsets where hash is null"))))))))

(deftest test-only-hash-field-change
  (clear-db-for-testing!)
  (fast-forward-to-migration! 38)
  (let [before-migration (schema-info-map *db*)]
    (apply-migration-for-testing! 39)
    (is (= {:index-diff nil,
            :table-diff [{:left-only [{:nullable? "YES"}],
                          :right-only [{:nullable? "NO"}]
                          :same [{:numeric_scale nil,
                                  :column_default nil,
                                  :character_octet_length nil,
                                  :datetime_precision nil,
                                  :character_maximum_length nil,
                                  :numeric_precision nil,
                                  :numeric_precision_radix nil,
                                  :data_type "bytea",
                                  :column_name "hash",
                                  :table_name "factsets"}]}]}
           (diff-schema-maps before-migration (schema-info-map *db*))))))

(deftest test-migrate-from-unsupported-version
  (clear-db-for-testing!)
  (fast-forward-to-migration! 34)
  (jdbc/do-commands "DELETE FROM schema_migrations")
  (record-migration! 33)
  (is (thrown-with-msg? IllegalStateException
                        #"Found an old and unuspported database migration.*"
                        (migrate! *db*))))

(deftest test-upgrade-migration
  (clear-db-for-testing!)
  ;;This represents a database from a 2.x version of PuppetDB
  (fast-forward-to-migration! 34)
  (doseq [migration-num (range 1 34)]
    (record-migration! migration-num))
  (let [latest-known-migration (apply max (keys migrations))]
    (is (= (set (range 35 (inc latest-known-migration)))
           (ks/keyset (pending-migrations))))
    (migrate! *db*)
    (is (empty? (pending-migrations)))))