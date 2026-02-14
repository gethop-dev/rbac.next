(ns dev.gethop.rbac.next-test
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [clojure.test :refer :all]
            [dev.gethop.rbac.next :as rbac]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as jdbc.sql]))

(def ^:const ^:private db
  (System/getenv "JDBC_DATABASE_URL"))

(defn- enable-instrumentation []
  (-> (stest/enumerate-namespace 'dev.gethop.rbac.next) stest/instrument))

(defonce ^{:private true, :const true} app-users
  {:app-user-1 {:id (@rbac/gen-primary-key-fn)
                :username "first.user"
                :email "first.user@magnet.coop"}
   :app-user-2 {:id (@rbac/gen-primary-key-fn)
                :username "second.user"
                :email "second.user@magnet.coop"}})

(defonce ^{:private true, :const true} app-resources
  {:application {:id (@rbac/gen-primary-key-fn)
                 :name "Application"
                 :description "Application description"}
   :organization-1 {:id (@rbac/gen-primary-key-fn)
                    :name "Organization 1"
                    :description "Organization 1 description"}
   :organization-2 {:id (@rbac/gen-primary-key-fn)
                    :name "Organization 2"
                    :description "Organization 2 description"}
   :plant-1 {:id (@rbac/gen-primary-key-fn)
             :name "Plant 1"
             :description "Plant 1 description"}
   :plant-2 {:id (@rbac/gen-primary-key-fn)
             :name "Plant 2"
             :description "Plant 2 description"}
   :asset-1 {:id (@rbac/gen-primary-key-fn)
             :name "Asset 1"
             :description "Asset 1 description"}
   :asset-2 {:id (@rbac/gen-primary-key-fn)
             :name "Asset 2"
             :description "Asset 2 description"}})

(defonce ^{:private true, :const true} test-context-types
  {:application {:name :application
                 :description "Application context"}
   :organization {:name :organization
                  :description "Organization context description"}
   :plant {:name :plant
           :description "Plant context"}
   :asset {:name :asset
           :description "Assets context"}})

(defonce ^{:private true, :const true} application-context
  {:context-type-name (get-in test-context-types [:application :name])
   :resource-id (get-in app-resources [:application :id])})

(defonce ^{:private true, :const true} organization-1-context
  {:context-type-name (get-in test-context-types [:organization :name])
   :resource-id (get-in app-resources [:organization-1 :id])})

(defonce ^{:private true, :const true} plant-1-context
  {:context-type-name (get-in test-context-types [:plant :name])
   :resource-id (get-in app-resources [:plant-1 :id])})

(defonce ^{:private true, :const true} asset-1-context
  {:context-type-name (get-in test-context-types [:asset :name])
   :resource-id (get-in app-resources [:asset-1 :id])})

(defonce ^{:private true, :const true} organization-2-context
  {:context-type-name (get-in test-context-types [:organization :name])
   :resource-id (get-in app-resources [:organization-2 :id])})

(defonce ^{:private true, :const true} plant-2-context
  {:context-type-name (get-in test-context-types [:plant :name])
   :resource-id (get-in app-resources [:plant-2 :id])})

(defonce ^{:private true, :const true} asset-2-context
  {:context-type-name (get-in test-context-types [:asset :name])
   :resource-id (get-in app-resources [:asset-2 :id])})

(defonce ^{:private true, :const true} test-contexts
  [application-context
   organization-1-context
   plant-1-context
   asset-1-context
   organization-2-context
   plant-2-context
   asset-2-context])

(defonce ^{:private true, :const true} test-roles
  [{:name :application/manager
    :description "Application manager"}
   {:name :organization/manager
    :description "Organization manager"}
   {:name :plant/manager
    :description "Plant manager"}
   {:name :asset/manager
    :description "Asset manager"}
   {:name :asset-1/manager
    :description "Asset 1 manager"}])

(defonce ^{:private true, :const true} test-permissions
  [{:name :application/manage
    :description "Manage Application"
    :context-type-name :application}
   {:name :organization/manage
    :description "Manage Organization"
    :context-type-name :organization}
   {:name :plant/manage
    :description "Manage Plant"
    :context-type-name :plant}
   {:name :asset/manage
    :description "Manage Asset"
    :context-type-name :asset}])

(defonce ^{:private true, :const true} rbac-tables-up-sql
  "dev.gethop.rbac.next/rbac-tables.pg.up.sql")

(defonce ^{:private true, :const true} rbac-tables-down-sql
  "dev.gethop.rbac.next/rbac-tables.pg.down.sql")

(defonce ^{:private true, :const true} app-tables-up-sql
  "_files/app-tables.up.sql")

(defonce ^{:private true, :const true} app-tables-down-sql
  "_files/app-tables.down.sql")

(defn- setup-app-objects []
  (jdbc/execute! db [(slurp (io/resource app-tables-up-sql))])
  (dorun (map (fn [[_ user]]
                (jdbc.sql/insert! db :appuser user))
              app-users))
  (dorun (map (fn [[_ resource]]
                (jdbc.sql/insert! db :resource resource))
              app-resources)))

(defn- setup-rbac-tables []
  (jdbc/execute! db [(slurp (io/resource rbac-tables-up-sql))]))

(defn- destroy-app-objects []
  (jdbc/execute! db [(slurp (io/resource app-tables-down-sql))]))

(defn- destroy-rbac-tables []
  (jdbc/execute! db [(slurp (io/resource rbac-tables-down-sql))]))

(use-fixtures
  :each (fn reset-db [f]
          (enable-instrumentation)
          (setup-app-objects)
          (setup-rbac-tables)
          (f)
          (destroy-rbac-tables)
          (destroy-app-objects)))

(deftest connectable-spec
  (let [db-spec db
        logger-fn (fn [_operation _statement])
        options {:read-only true}]
    (is (s/valid? ::rbac/db-spec db-spec))
    (is (s/valid? ::rbac/db-spec (-> db-spec (jdbc/with-logging logger-fn))))
    (is (s/valid? ::rbac/db-spec (-> db-spec (jdbc/with-options options))))
    (is (s/valid? ::rbac/db-spec (-> db-spec
                                     (jdbc/with-logging logger-fn)
                                     (jdbc/with-options options))))
    (is (s/valid? ::rbac/db-spec (-> db-spec
                                     (jdbc/with-options options)
                                     (jdbc/with-logging logger-fn))))
    (jdbc/with-transaction [tx db-spec]
      (is (s/valid? ::rbac/db-spec tx)))
    (jdbc/with-transaction+options [tx db-spec options]
      (is (s/valid? ::rbac/db-spec tx)))
    (jdbc/with-transaction+options [tx (-> db-spec (jdbc/with-options options))]
      (is (s/valid? ::rbac/db-spec tx)))))

(deftest test-1
  (let [_ (rbac/create-context-types! db (vals test-context-types))
        application-ctx (:context (rbac/create-context! db application-context []))
        organization-1-ctx (:context (rbac/create-context! db organization-1-context [application-ctx]))
        organization-2-ctx (:context (rbac/create-context! db organization-2-context []))
        add-parents-success? (rbac/add-parent-contexts! db organization-2-ctx [application-ctx])
        plant-1-ctx (:context (rbac/create-context! db plant-1-context [organization-1-ctx]))
        plant-2-ctx (:context (rbac/create-context! db plant-2-context [organization-2-ctx]))
        _asset-1-ctx (:context (rbac/create-context! db asset-1-context [plant-1-ctx]))
        _asset-2-ctx (:context (rbac/create-context! db asset-2-context [plant-2-ctx]))
        created-roles (rbac/create-roles! db test-roles)
        _ (rbac/create-permissions! db test-permissions)
        ;; -------
        _ (rbac/grant-role-permissions! db
                                        (:role (rbac/get-role-by-name db :application/manager))
                                        [(-> (rbac/get-permission-by-name db :application/manage)
                                             :permission)
                                         (-> (rbac/get-permission-by-name db :plant/manage)
                                             :permission)
                                         (-> (rbac/get-permission-by-name db :asset/manage)
                                             :permission)])
        _ (rbac/deny-role-permissions! db
                                       (:role (rbac/get-role-by-name db :application/manager))
                                       [(-> (rbac/get-permission-by-name db :organization/manage)
                                            :permission)])
        _ (rbac/grant-role-permissions! db
                                        (:role (rbac/get-role-by-name db :organization/manager))
                                        [(-> (rbac/get-permission-by-name db :organization/manage)
                                             :permission)
                                         (-> (rbac/get-permission-by-name db :plant/manage)
                                             :permission)
                                         (-> (rbac/get-permission-by-name db :asset/manage)
                                             :permission)])
        _ (rbac/grant-role-permissions! db
                                        (:role (rbac/get-role-by-name db :plant/manager))
                                        [(-> (rbac/get-permission-by-name db :plant/manage)
                                             :permission)
                                         (-> (rbac/get-permission-by-name db :asset/manage)
                                             :permission)])
        _ (rbac/grant-role-permissions! db
                                        (:role (rbac/get-role-by-name db :asset/manager))
                                        [(-> (rbac/get-permission-by-name db :asset/manage)
                                             :permission)])
        _ (rbac/deny-role-permissions! db
                                       (:role (rbac/get-role-by-name db :asset-1/manager))
                                       [(-> (rbac/get-permission-by-name db :asset/manage)
                                            :permission)])
        ;; -------
        _ (rbac/add-super-admin! db (get-in app-users [:app-user-2 :id]))
        _ (rbac/assign-roles! db
                              [{:role (:role (rbac/get-role-by-name db :application/manager))
                                :context
                                (:context (rbac/get-context db
                                                            :application
                                                            (get-in app-resources [:application :id])))
                                :user (:app-user-1 app-users)}
                               {:role (:role (rbac/get-role-by-name db :organization/manager))
                                :context
                                (:context (rbac/get-context db
                                                            :organization
                                                            (get-in app-resources [:organization-1 :id])))
                                :user (:app-user-1 app-users)}
                               {:role (:role (rbac/get-role-by-name db :plant/manager))
                                :context
                                (:context (rbac/get-context db
                                                            :plant
                                                            (get-in app-resources [:plant-1 :id])))
                                :user (:app-user-1 app-users)}
                               {:role (:role (rbac/get-role-by-name db :asset/manager))
                                :context
                                (:context (rbac/get-context db
                                                            :plant
                                                            (get-in app-resources [:plant-1 :id])))
                                :user (:app-user-1 app-users)}
                               {:role (:role (rbac/get-role-by-name db :asset-1/manager))
                                :context
                                (:context (rbac/get-context db
                                                            :asset
                                                            (get-in app-resources [:asset-1 :id])))
                                :user (:app-user-1 app-users)}])]
    (testing "app-user-1 has :application/manage permission on :application resource"
      (let [user-id (-> app-users :app-user-1 :id)
            resource-id (-> app-resources :application :id)
            context-type-name :application
            permission-name :application/manage
            has-permission (rbac/has-permission? db user-id resource-id context-type-name permission-name)]
        (is (= has-permission true))))

    (testing "Check role of app-user-1 has expected value for application context"
      (let [user-id (-> app-users :app-user-1 :id)
            role-name :application/manager
            {:keys [success? role-assignments]} (rbac/get-role-assignments-by-user
                                                 db
                                                 user-id
                                                 (:id application-ctx))
            app-user-role (first role-assignments)
            role-data (some #(when (= role-name (-> % :role :name))
                               (:role %))
                            created-roles)]
        (is (true? success?))
        (is (= app-user-role {:role role-data
                              :context application-ctx
                              :user {:id user-id}}))))

    (testing "app-user-1 has :organization/manage permission on :organization-1 resource"
      (let [user-id (-> app-users :app-user-1 :id)
            resource-id (-> app-resources :organization-1 :id)
            context-type-name :organization
            permission-name :organization/manage
            has-permission (rbac/has-permission? db user-id resource-id context-type-name permission-name)]
        (is (= has-permission false))))

    (testing "app-user-1 has :application/manage permission on :organization-1 resource"
      (let [user-id (-> app-users :app-user-1 :id)
            resource-id (-> app-resources :organization-1 :id)
            context-type-name :organization
            permission-name :application/manage
            has-permission (rbac/has-permission? db user-id resource-id context-type-name permission-name)]
        (is (= has-permission true))))

    (testing "app-user-1 has :plant/manage permission on :plant-1 resource"
      (let [user-id (-> app-users :app-user-1 :id)
            resource-id (-> app-resources :plant-1 :id)
            context-type-name :plant
            permission-name :plant/manage
            has-permission (rbac/has-permission? db user-id resource-id context-type-name permission-name)]
        (is (= has-permission true))))

    (testing "app-user-1 has :application/manage permission on :plant-1 resource"
      (let [user-id (-> app-users :app-user-1 :id)
            resource-id (-> app-resources :plant-1 :id)
            context-type-name :plant
            permission-name :application/manage
            has-permission (rbac/has-permission? db user-id resource-id context-type-name permission-name)]
        (is (= has-permission true))))

    (testing "app-user-1 has :asset/manage permission on :plant-1 resource"
      (let [user-id (-> app-users :app-user-1 :id)
            resource-id (-> app-resources :plant-1 :id)
            context-type-name :plant
            permission-name :asset/manage
            has-permission (rbac/has-permission? db user-id resource-id context-type-name permission-name)]
        (is (= has-permission true))))

    (testing "app-user-1 doesn't have :asset/manage permission on :asset-1 resource"
      (let [user-id (-> app-users :app-user-1 :id)
            resource-id (-> app-resources :asset-1 :id)
            context-type-name :asset
            permission-name :asset/manage
            has-permission (rbac/has-permission? db user-id resource-id context-type-name permission-name)]
        (is (= has-permission false))))
    (testing "organization-2-ctx has its parents successfully added"
      (is (:success? add-parents-success?)))
    (testing "organization-2-ctx has its parents successfully removed"
      (is (rbac/remove-parent-contexts! db organization-2-ctx [application-ctx])))
    (testing "A child context cannot be its own parent"
      (let [{:keys [success?]} (rbac/add-parent-contexts! db application-ctx [application-ctx])]
        (is (not success?))))
    (testing "Cycles in child-parent relationships are not allowed"
      (let [{:keys [success?]} (rbac/add-parent-contexts! db application-ctx [organization-1-ctx])]
        (is (not success?))))))

(deftest create-role!
  (let [role-to-create (first test-roles)]
    (testing "create-role! succeeds"
      (let [{:keys [success? role]} (rbac/create-role! db role-to-create)]
        (is success?)
        (is (= (dissoc role :id) role-to-create))
        (is (:id role))))
    (testing "create-role! fails when creating an existing role"
      (let [{:keys [success?]} (rbac/create-role! db role-to-create)]
        (is (not success?))))
    (testing "create-role! fails with extra rol attributes"
      (let [{:keys [success?]} (rbac/create-role! db role-to-create)]
        (is (not success?))))))

(deftest create-roles!
  (testing "create-roles! succeeds"
    (let [result (rbac/create-roles! db test-roles)]
      (is (every? identity (mapv (fn [role-to-create {:keys [success? role]}]
                                   (and success?
                                        (= (dissoc role :id) role-to-create)
                                        (:id role)))
                                 test-roles
                                 result))))))

(deftest get-roles
  (testing "get-roles succeeds"
    (let [created-roles (mapv :role (rbac/create-roles! db test-roles))
          {:keys [success? roles]} (rbac/get-roles db)]
      (is success?)
      (is (= created-roles roles)))))

(deftest get-roles-by-names
  (testing "get-roles-by-names succeeds"
    (let [created-roles (mapv :role (rbac/create-roles! db test-roles))
          test-roles (take 2 created-roles)
          {:keys [success? roles]} (rbac/get-roles-by-names
                                    db (mapv :name test-roles))]
      (is success?)
      (is (= test-roles roles)))))

(deftest get-rol-by-*
  (let [created-roles (mapv :role (rbac/create-roles! db test-roles))]
    (testing "get-role-by-id succeeds"
      (let [{:keys [success? role]} (rbac/get-role-by-id db (-> created-roles
                                                                first
                                                                :id))]
        (is success?)
        (is (= (first created-roles) role))))
    (testing "get-role-by-name succeeds"
      (let [{:keys [success? role]} (rbac/get-role-by-name db (-> created-roles
                                                                  first
                                                                  :name))]
        (is success?)
        (is (= (first created-roles) role))))))

(deftest update-role!
  (let [created-roles (mapv :role (rbac/create-roles! db test-roles))
        role-to-update (-> created-roles first (assoc :name :updated-role))]
    (testing "update-role! succeeds"
      (let [{:keys [success? role]} (rbac/update-role! db role-to-update)]
        (is success?)
        (is (= role-to-update role))))
    (testing "update-role! fails without role :id"
      (is (thrown? clojure.lang.ExceptionInfo (rbac/update-role! db (dissoc role-to-update :id))))
      (stest/unstrument ['dev.gethop.rbac.next/update-role!])
      (let [{:keys [success?]} (rbac/update-role! db (dissoc role-to-update :id))]
        (is (not success?))))))

(deftest update-roles!
  (let [created-roles (mapv :role (rbac/create-roles! db test-roles))
        roles-to-update (mapv (fn [role]
                                (update role :name
                                        (fn [k]
                                          (keyword (namespace k)
                                                   (str "updated-"
                                                        (name k))))))
                              created-roles)]
    (testing "update-roles! succeeds"
      (let [result (rbac/update-roles! db roles-to-update)]
        (is (every? :success? result))
        (mapv #(is (= %1 %2)) roles-to-update (mapv :role result))))
    (testing "update-roles! fails without role :id"
      (let [roles-without-id (mapv #(dissoc % :id) roles-to-update)]
        (is (thrown? clojure.lang.ExceptionInfo (rbac/update-roles! db roles-without-id)))
        (stest/unstrument ['dev.gethop.rbac.next/update-role!
                           'dev.gethop.rbac.next/update-roles!])
        (let [{:keys [success?]} (rbac/update-roles! db roles-without-id)]
          (is (not success?)))))))

(deftest delete-role!
  (let [created-role (first (mapv :role (rbac/create-roles! db test-roles)))]
    (testing "delete-role! succeeds"
      (let [result (rbac/delete-role! db created-role)]
        (is (:success? result))))
    (testing "delete-role! fails without role :id"
      (is (thrown? clojure.lang.ExceptionInfo (rbac/delete-role! db (dissoc created-role :id)))))))

(deftest delete-role-by*!
  (let [created-roles (mapv :role (rbac/create-roles! db test-roles))]
    (testing "delete-role-by-id! succeeds"
      (let [result (rbac/delete-role-by-id! db (-> created-roles first :id))]
        (is (:success? result))))
    (testing "delete-role-by-name! succeeds"
      (let [result (rbac/delete-role-by-name! db (-> created-roles second :name))]
        (is (:success? result))))
    (testing "delete-role-by-ids! succeeds"
      (let [result (rbac/delete-roles-by-ids! db (vector (-> created-roles (nth 2) :id)))]
        (is (every? :success? result))))
    (testing "delete-roles-by-names! succeeds"
      (let [result (rbac/delete-roles-by-names! db (vector (-> created-roles (nth 3) :name)))]
        (is (every? :success? result))))))

(deftest create-context-type!
  (let [context-type-to-create (first (vals test-context-types))]
    (testing "create-context-type! succeeds"
      (let [{:keys [success? context-type]} (rbac/create-context-type! db context-type-to-create)]
        (is success?)
        (is (= context-type context-type-to-create))))))

(deftest create-context-types!
  (testing "create-context-types! succeeds"
    (let [context-types-to-create (vals test-context-types)
          result (rbac/create-context-types! db context-types-to-create)]
      (is (every? identity (mapv (fn [context-type-to-create {:keys [success? context-type]}]
                                   (and success?
                                        (= context-type context-type-to-create)))
                                 context-types-to-create
                                 result))))))

(deftest get-context-types
  (testing "get-context-types succeeds"
    (let [created-context-types (vals test-context-types)
          _ (rbac/create-context-types! db created-context-types)
          {:keys [success? context-types]} (rbac/get-context-types db)]
      (is success?)
      (is (= created-context-types context-types)))))

(deftest get-context-type
  (testing "get-context-type succeeds"
    (let [created-context-types (vals test-context-types)
          _ (rbac/create-context-types! db created-context-types)
          {:keys [success? context-type]} (rbac/get-context-type db (-> created-context-types first :name))]
      (is success?)
      (is (= (first created-context-types) context-type)))))

(deftest update-context-type!
  (let [context-types-to-create (vals test-context-types)
        created-context-types (mapv :context-type (rbac/create-context-types! db context-types-to-create))
        context-type-to-update (-> created-context-types first (assoc :description "Updated description"))]
    (testing "update-context-type! succeeds"
      (let [{:keys [success? context-type]} (rbac/update-context-type! db context-type-to-update)]
        (is success?)
        (is (= context-type-to-update context-type))))
    (testing "update-context-type! fails without context-type :name"
      (is (thrown? clojure.lang.ExceptionInfo (rbac/update-context-type! db (dissoc context-type-to-update :name))))
      (stest/unstrument ['dev.gethop.rbac.next/update-context-type!])
      (let [{:keys [success?]} (rbac/update-context-type! db (dissoc context-type-to-update :name))]
        (is (not success?))))))

(deftest update-context-types!
  (let [context-types-to-create (vals test-context-types)
        created-context-types (mapv :context-type (rbac/create-context-types! db context-types-to-create))
        context-types-to-update (mapv (fn [context-type]
                                        (update context-type :description
                                                (fn [desc]
                                                  (str "updated-" desc))))
                                      created-context-types)]
    (testing "update-context-types! succeeds"
      (let [result (rbac/update-context-types! db context-types-to-update)]
        (is (every? :success? result))
        (mapv #(is (= %1 %2)) context-types-to-update (mapv :context-type result))))
    (testing "update-context-types! fails without context-type :name"
      (let [context-types-without-id (mapv #(dissoc % :name) context-types-to-update)]
        (is (thrown? clojure.lang.ExceptionInfo (rbac/update-context-types! db context-types-without-id)))
        (stest/unstrument ['dev.gethop.rbac.next/update-context-type!
                           'dev.gethop.rbac.next/update-context-types!])
        (let [{:keys [success?]} (rbac/update-context-types! db context-types-without-id)]
          (is (not success?)))))))

(deftest delete-context-type!
  (let [context-types-to-create (vals test-context-types)
        created-context-type (first (mapv :context-type (rbac/create-context-types! db context-types-to-create)))]
    (testing "delete-context-type! succeeds"
      (let [result (rbac/delete-context-type! db created-context-type)]
        (is (:success? result))))
    (testing "delete-context-type! fails without context-type :name"
      (is (thrown? clojure.lang.ExceptionInfo (rbac/delete-context-type! db (dissoc created-context-type :name)))))))

(deftest delete-context-types!
  (let [context-types-to-create (vals test-context-types)
        created-context-types (mapv :context-type (rbac/create-context-types! db context-types-to-create))]
    (testing "delete-context-types! succeeds"
      (let [result (rbac/delete-context-types! db (nthrest created-context-types 2))]
        (is (every? :success? result))))))

(deftest create-context!
  (let [context-types-to-create (vals test-context-types)
        _ (rbac/create-context-types! db context-types-to-create)
        app-context (first test-contexts)
        app-context-result (rbac/create-context! db app-context [])]
    (testing "create-context! for \"application\" context succeeds"
      (is (:success? app-context-result))
      (is (= (dissoc (:context app-context-result) :id) app-context)))))

(deftest create-contexts!
  (testing "create-contexts! succeeds"
    (let [_ (rbac/create-context-types! db [(:application test-context-types)
                                            (:organization test-context-types)])
          app-test-context (first test-contexts)
          app-context (:context (rbac/create-context! db app-test-context []))
          org-contexts-parents-to-create  [{:context (nth test-contexts 1)
                                            :parent-contexts [app-context]}
                                           {:context (nth test-contexts 4)
                                            :parent-contexts [app-context]}]
          org-contexts-to-create-set (into #{}
                                           (map :context)
                                           org-contexts-parents-to-create)
          result (rbac/create-contexts! db org-contexts-parents-to-create)]
      (is (:success? result))
      (is (every? #(get org-contexts-to-create-set (dissoc % :id))
                  (:contexts result))))))

(deftest get-contexts
  (testing "get-contexts succeeds"
    (let [_ (rbac/create-context-types! db [(:application test-context-types)
                                            (:organization test-context-types)])
          app-test-context (first test-contexts)
          app-context (:context (rbac/create-context! db app-test-context []))
          org-contexts-parents-to-create [{:context (nth test-contexts 1)
                                           :parent-contexts [app-context]}
                                          {:context (nth test-contexts 4)
                                           :parent-contexts [app-context]}]
          org-contexts-to-create-set (into #{}
                                           (map :context)
                                           org-contexts-parents-to-create)
          all-contexts-set (conj org-contexts-to-create-set app-test-context)
          _ (rbac/create-contexts! db org-contexts-parents-to-create)
          result (rbac/get-contexts db)]
      (is (:success? result))
      (is (every? #(get all-contexts-set (dissoc % :id))
                  (:contexts result))))))

(deftest get-contexts-by-selectors
  (let [_ (rbac/create-context-types! db [(:application test-context-types)
                                          (:organization test-context-types)])
        app-test-context (first test-contexts)
        app-context (:context (rbac/create-context! db app-test-context []))
        org-contexts-parents-to-create [{:context (nth test-contexts 1)
                                         :parent-contexts [app-context]}
                                        {:context (nth test-contexts 4)
                                         :parent-contexts [app-context]}]
        _ (rbac/create-contexts! db org-contexts-parents-to-create)]
    (testing "get-contexts-by-selectors succeeds with full selectors"
      (let [context (nth test-contexts 1)
            context-selectors [{:context-type-name :organization
                                :resource-id (:resource-id context)}]
            result (rbac/get-contexts-by-selectors db context-selectors)]
        (is (:success? result))
        (is (= (-> result :contexts first (dissoc :id))
               context))))
    (testing "get-contexts-by-selectors succeeds with wildcard selectors"
      (let [org-contexts-set (into #{}
                                   (map :context)
                                   org-contexts-parents-to-create)
            context-selectors [{:context-type-name :organization
                                :resource-id :*}]
            result (rbac/get-contexts-by-selectors db context-selectors)]
        (is (:success? result))
        (is (every? #(get org-contexts-set (dissoc % :id))
                    (:contexts result)))))))

(deftest get-context
  (let [_ (rbac/create-context-types! db (vals test-context-types))
        app-context (first test-contexts)
        _ (:context (rbac/create-context! db app-context []))]
    (testing "get-context succeeds"
      (let [{:keys [success? context]} (rbac/get-context db
                                                         (:context-type-name app-context)
                                                         (:resource-id app-context))]
        (is success?)
        (is (= (dissoc context :id) app-context))))))

(deftest update-context!
  (let [_ (rbac/create-context-types! db (vals test-context-types))
        app-context (first test-contexts)
        created-context (:context (rbac/create-context! db app-context []))
        context-to-update (-> created-context (assoc :context-type-name :organization))]
    (testing "update-context! succeeds"
      (let [{:keys [success? context]} (rbac/update-context! db context-to-update)]
        (is success?)
        (is (= context-to-update context))))
    (testing "update-context! fails without context :id, :context-type-name, :resource-id"
      (is (thrown? clojure.lang.ExceptionInfo (rbac/update-context! db (dissoc context-to-update :context-type-name))))
      (stest/unstrument ['dev.gethop.rbac.next/update-context!])
      (let [{:keys [success?]} (rbac/update-context! db (dissoc context-to-update :context-type-name))]
        (is (not success?))))
    (testing "update-context! fails with and invalid :context-type-name"
      (stest/unstrument ['dev.gethop.rbac.next/update-context!])
      (let [{:keys [success?]} (rbac/update-context! db (assoc context-to-update :context-type-name :invalid))]
        (is (not success?))))))

(deftest update-contexts!
  (let [_ (rbac/create-context-types! db (vals test-context-types))
        test-contexts-with-parents (mapv (fn [m] {:context m, :parent-contexts []}) test-contexts)
        created-contexts (:contexts (rbac/create-contexts! db test-contexts-with-parents))
        contexts-to-update (mapv (fn [context]
                                   (assoc context :context-type-name :asset))
                                 created-contexts)]
    (testing "update-contexts! succeeds"
      (let [result (rbac/update-contexts! db contexts-to-update)]
        (is (every? :success? result))
        (mapv #(is (= %1 %2)) contexts-to-update (mapv :context result))))
    (testing "update-contexts! fails without context :id"
      (let [contexts-without-resource-id (mapv #(dissoc % :id) contexts-to-update)]
        (is (thrown? clojure.lang.ExceptionInfo (rbac/update-contexts! db contexts-without-resource-id)))
        (stest/unstrument ['dev.gethop.rbac.next/update-context!
                           'dev.gethop.rbac.next/update-contexts!])
        (let [result (rbac/update-contexts! db contexts-without-resource-id)]
          (is (every? (complement :success?) result)))))))

(deftest delete-context!
  (let [context-types-to-create (vals test-context-types)
        _ (rbac/create-context-types! db context-types-to-create)
        test-context (first test-contexts)
        created-context (:context (rbac/create-context! db test-context []))]
    (testing "delete-context! succeeds"
      (let [result (rbac/delete-context! db created-context)]
        (is (:success? result))))
    (testing "delete-context! fails without context :id"
      (is (thrown? clojure.lang.ExceptionInfo (rbac/delete-context! db (dissoc created-context :id)))))
    (testing "delete-context! fails without context :context-type-name or :resource-id"
      (is (thrown? clojure.lang.ExceptionInfo (rbac/delete-context! db (dissoc created-context :context-type-name))))
      (is (thrown? clojure.lang.ExceptionInfo (rbac/delete-context! db (dissoc created-context :resource-id)))))))

(deftest delete-context-by-id!
  (let [context-types-to-create (vals test-context-types)
        _ (rbac/create-context-types! db context-types-to-create)
        test-context (first test-contexts)
        created-context (:context (rbac/create-context! db test-context []))]
    (testing "delete-context-by-id! succeeds"
      (let [result (rbac/delete-context-by-id! db (:id created-context))]
        (is (:success? result))))
    (testing "delete-context-by-id! fails without context :id"
      (is (thrown? clojure.lang.ExceptionInfo (rbac/delete-context-by-id! db nil))))))

(deftest delete-contexts-by-ids!
  (let [context-types-to-create (vals test-context-types)
        _ (rbac/create-context-types! db context-types-to-create)
        test-contexts-with-parents (mapv (fn [m] {:context m, :parent-contexts []}) test-contexts)
        created-contexts (:contexts (rbac/create-contexts! db test-contexts-with-parents))]
    (testing "delete-contexts! succeeds"
      (let [result (rbac/delete-contexts-by-ids! db (mapv :id created-contexts))]
        (is (:success? result))))))

(deftest delete-contexts!
  (let [context-types-to-create (vals test-context-types)
        _ (rbac/create-context-types! db context-types-to-create)
        test-contexts-with-parents (mapv (fn [m] {:context m, :parent-contexts []}) test-contexts)
        created-contexts (:contexts (rbac/create-contexts! db test-contexts-with-parents))]
    (testing "delete-contexts! succeeds"
      (let [result (rbac/delete-contexts! db created-contexts)]
        (is (:success? result))))))

(comment
  ;; TODO: Create all the individual unit tests by leveraging the example code below.

  ;; -----------------------------------------------------
  (rbac/update-role! db
                     (-> (rbac/get-role-by-name db :application/manager)
                         :role
                         (assoc :name :application/manager)))
  (rbac/update-roles! db
                      [(-> (rbac/get-role-by-name db :application/manager)
                           :role
                           (assoc :name :application/manager))
                       (-> (rbac/get-role-by-name db :organization/manager)
                           :role
                           (assoc :name :organization/manager))])
  (rbac/delete-role! db
                     (-> (rbac/get-role-by-name db :application/manager)
                         :role))
  (rbac/delete-role-by-id! db
                           (-> (rbac/get-role-by-name db :organization/manager)
                               :role
                               :id))
  (rbac/delete-role-by-name! db :asset/manager)
  (rbac/delete-roles! db
                      [(-> (rbac/get-role-by-name db :plant/manager)
                           :role)
                       (-> (rbac/get-role-by-name db :application/manager)
                           :role)])
  (rbac/delete-roles-by-ids! db
                             [(-> (rbac/get-role-by-name db :plant/manager)
                                  :role
                                  :id)
                              (-> (rbac/get-role-by-name db :application/manager)
                                  :role
                                  :id)])
  (rbac/delete-roles-by-names! db [:organization/manager :asset/manager])

  ;; -----------------------------------------------------
  (rbac/create-context-types! db (vals test-context-types))
  (rbac/create-context-type! db (:application test-context-types))
  (rbac/get-context-types db)
  (rbac/get-context-type db :application)
  (rbac/get-context-type db :asset)
  (rbac/update-context-type! db (-> (rbac/get-context-type db :application)
                                    :context-type
                                    (assoc :description "Some updated description")))
  (rbac/update-context-types! db
                              [(-> (rbac/get-context-type db :application)
                                   :context-type
                                   (assoc :description "Some updated description for application"))
                               (-> (rbac/get-context-type db :organization)
                                   :context-type
                                   (assoc :description "Some updated description for organization"))])
  (rbac/delete-context-type! db
                             (-> (rbac/get-context-type db :organization)
                                 :context-type))
  (rbac/delete-context-types! db
                              [(-> (rbac/get-context-type db :application)
                                   :context-type)
                               (-> (rbac/get-context-type db :organization)
                                   :context-type)])
  (rbac/delete-context-types! db
                              [(-> (rbac/get-context-type db :plant)
                                   :context-type)
                               (-> (rbac/get-context-type db :asset)
                                   :context-type)])

  ;; -----------------------------------------------------
  (let [_ (rbac/create-context-types! db (vals context-types))
        application-ctx (:context (rbac/create-context! db application-context nil))
        organization-1-ctx (:context (rbac/create-context! db organization-1-context application-ctx))
        organization-2-ctx (:context (rbac/create-context! db organization-2-context application-ctx))
        plant-1-ctx (:context (rbac/create-context! db plant-1-context organization-1-ctx))
        plant-2-ctx (:context (rbac/create-context! db plant-2-context organization-2-ctx))
        asset-1-ctx (:context (rbac/create-context! db asset-1-context plant-1-ctx))
        asset-2-ctx (:context (rbac/create-context! db asset-2-context plant-2-ctx))]
    [application-ctx
     organization-1-ctx
     organization-2-ctx
     plant-1-ctx
     plant-2-ctx
     asset-1-ctx
     asset-2-ctx])
  (rbac/get-contexts db)
  (rbac/get-context db :application (get-in app-resources [:application :id]))
  (rbac/get-context db :asset (get-in app-resources [:asset-1 :id]))
  (rbac/update-context! db (-> (rbac/get-context db :application (get-in app-resources [:application :id]))
                               :context
                               (assoc :context-type-name :asset)))
  (rbac/update-contexts! db
                         [(-> (rbac/get-context db :asset (get-in app-resources [:application :id]))
                              :context
                              (assoc :context-type-name :application))
                          (-> (rbac/get-context db :asset (get-in app-resources [:asset-1 :id]))
                              :context
                              (assoc :context-type-name :application))])
  (rbac/update-context! db (-> (rbac/get-context db :application (get-in app-resources [:asset-1 :id]))
                               :context
                               (assoc :context-type-name :asset)))
  (rbac/delete-context! db
                        (-> (rbac/get-context db :application (get-in app-resources [:application :id]))
                            :context))
  (rbac/delete-contexts! db
                         [(-> (rbac/get-context db :asset (get-in app-resources [:asset-1 :id]))
                              :context)
                          (-> (rbac/get-context db :plant (get-in app-resources [:plant-1 :id]))
                              :context)
                          (-> (rbac/get-context db :organization (get-in app-resources [:organization-1 :id]))
                              :context)])

  ;; -----------------------------------------------------
  (rbac/create-permissions! db permissions)
  (rbac/create-permission! db (first permissions))
  (rbac/get-permissions db)
  (rbac/get-permission-by-id db
                             (-> (rbac/get-permission-by-name db :application/manage)
                                 :permission
                                 :id))
  (rbac/get-permission-by-name db
                               (-> (rbac/get-permission-by-name db :application/manage)
                                   :permission
                                   :name))
  (rbac/update-permission! db
                           (-> (rbac/get-permission-by-name db :application/manage)
                               :permission
                               (assoc :name :application/manageXX)))
  (rbac/update-permissions! db
                            [(-> (rbac/get-permission-by-name db :application/manageXX)
                                 :permission
                                 (assoc :name :application/manageYY))
                             (-> (rbac/get-permission-by-name db :application/manageXX)
                                 :permission
                                 (assoc :name :application/manage))])
  (rbac/delete-permission! db
                           (-> (rbac/get-permission-by-name db :application/manage)
                               :permission))
  (rbac/delete-permission-by-id! db
                                 (-> (rbac/get-permission-by-name db :application/manage)
                                     :permission
                                     :id))
  (rbac/delete-permission-by-name! db
                                   (-> (rbac/get-permission-by-name db :plant/manage)
                                       :permission
                                       :name))
  (rbac/delete-permissions! db
                            [(-> (rbac/get-permission-by-name db :asset/manage)
                                 :permission)
                             (-> (rbac/get-permission-by-name db :organization/manage)
                                 :permission)])
  (rbac/delete-permissions-by-ids! db
                                   [(-> (rbac/get-permission-by-name db :application/manage)
                                        :permission
                                        :id)
                                    (-> (rbac/get-permission-by-name db :organization/manage)
                                        :permission
                                        :id)])
  (rbac/delete-permissions-by-names! db
                                     [(-> (rbac/get-permission-by-name db :application/manage)
                                          :permission
                                          :name)
                                      (-> (rbac/get-permission-by-name db :organization/manage)
                                          :permission
                                          :name)])

  ;; -----------------------------------------------------
  (rbac/add-super-admin! db (get-in app-users [:app-user-1 :id]))
  (rbac/super-admin? db (get-in app-users [:app-user-1 :id]))
  (rbac/super-admin? db (get-in app-users [:app-user-2 :id]))
  (rbac/remove-super-admin! db (get-in app-users [:app-user-1 :id]))
  (rbac/remove-super-admin! db (get-in app-users [:app-user-2 :id]))

  ;; -----------------------------------------------------
  (rbac/grant-role-permission! db
                               (:role (rbac/get-role-by-name db :organization/manager))
                               (-> (rbac/get-permission-by-name db :organization/manage)
                                   :permission))
  (rbac/remove-role-permission! db
                                (:role (rbac/get-role-by-name db :organization/manager))
                                (-> (rbac/get-permission-by-name db :organization/manage)
                                    :permission))
  (rbac/deny-role-permission! db
                              (:role (rbac/get-role-by-name db :organization/manager))
                              (-> (rbac/get-permission-by-name db :organization/manage)
                                  :permission))
  (rbac/remove-role-permission! db
                                (:role (rbac/get-role-by-name db :organization/manager))
                                (-> (rbac/get-permission-by-name db :organization/manage)
                                    :permission))

  ;; -----------------------------------------------------
  (rbac/assign-role! db
                     {:role (:role (rbac/get-role-by-name db :application/manager))
                      :context (:context (rbac/get-context db
                                                           :application
                                                           (get-in app-resources [:application :id])))
                      :user (:app-user-1 app-users)})
  (rbac/assign-roles! db
                      [{:role (:role (rbac/get-role-by-name db :application/manager))
                        :context
                        (:context (rbac/get-context db
                                                    :application
                                                    (get-in app-resources [:application :id])))
                        :user (:app-user-1 app-users)}
                       {:role (:role (rbac/get-role-by-name db :organization/manager))
                        :context (:context (rbac/get-context db
                                                             :organization
                                                             (get-in app-resources [:organization-2 :id])))
                        :user (:app-user-2 app-users)}])
  (rbac/unassign-role! db
                       {:role (:role (rbac/get-role-by-name db :application/manager))
                        :context (:context (rbac/get-context db
                                                             :application
                                                             (get-in app-resources [:application :id])))
                        :user (:app-user-1 app-users)})
  (rbac/unassign-roles! db
                        [{:role (:role (rbac/get-role-by-name db :application/manager))
                          :context
                          (:context (rbac/get-context db
                                                      :application
                                                      (get-in app-resources [:application :id])))
                          :user (:app-user-1 app-users)}
                         {:role (:role (rbac/get-role-by-name db :organization/manager))
                          :context (:context (rbac/get-context db
                                                               :organization
                                                               (get-in app-resources [:organization-2 :id])))
                          :user (:app-user-2 app-users)}]))
