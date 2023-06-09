(ns dev.gethop.rbac.next-test
  (:require [clojure.java.io :as io]
            [clojure.spec.test.alpha :as stest]
            [clojure.test :refer :all]
            [dev.gethop.rbac.next :as rbac]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as jdbc.sql])
  (:import [java.util UUID]))

(def ^:const ^:private db
  (System/getenv "JDBC_DATABASE_URL"))

(defn enable-instrumentation []
  (-> (stest/enumerate-namespace 'dev.gethop.rbac.next) stest/instrument))

(defonce ^:const app-users
  {:app-user-1 {:id (UUID/randomUUID)
                :username "first.user"
                :email "first.user@magnet.coop"}
   :app-user-2 {:id (UUID/randomUUID)
                :username "second.user"
                :email "second.user@magnet.coop"}})

(defonce ^:const app-resources
  {:application {:id (UUID/randomUUID)
                 :name "Application"
                 :description "Application description"}
   :organization-1 {:id (UUID/randomUUID)
                    :name "Organization 1"
                    :description "Organization 1 description"}
   :organization-2 {:id (UUID/randomUUID)
                    :name "Organization 2"
                    :description "Organization 2 description"}
   :plant-1 {:id (UUID/randomUUID)
             :name "Plant 1"
             :description "Plant 1 description"}
   :plant-2 {:id (UUID/randomUUID)
             :name "Plant 2"
             :description "Plant 2 description"}
   :asset-1 {:id (UUID/randomUUID)
             :name "Asset 1"
             :description "Asset 1 description"}
   :asset-2 {:id (UUID/randomUUID)
             :name "Asset 2"
             :description "Asset 2 description"}})

(defonce ^:const context-types
  {:application {:name :application
                 :description "Application context"}
   :organization {:name :organization
                  :description "Organization context description"}
   :plant {:name :plant
           :description "Plant context"}
   :asset {:name :asset
           :description "Assets context"}})

(defonce ^:const application-context
  {:context-type-name (get-in context-types [:application :name])
   :resource-id (get-in app-resources [:application :id])})

(defonce ^:const organization-1-context
  {:context-type-name (get-in context-types [:organization :name])
   :resource-id (get-in app-resources [:organization-1 :id])})

(defonce ^:const plant-1-context
  {:context-type-name (get-in context-types [:plant :name])
   :resource-id (get-in app-resources [:plant-1 :id])})

(defonce ^:const asset-1-context
  {:context-type-name (get-in context-types [:asset :name])
   :resource-id (get-in app-resources [:asset-1 :id])})

(defonce ^:const organization-2-context
  {:context-type-name (get-in context-types [:organization :name])
   :resource-id (get-in app-resources [:organization-2 :id])})

(defonce ^:const plant-2-context
  {:context-type-name (get-in context-types [:plant :name])
   :resource-id (get-in app-resources [:plant-2 :id])})

(defonce ^:const asset-2-context
  {:context-type-name (get-in context-types [:asset :name])
   :resource-id (get-in app-resources [:asset-2 :id])})

(defonce ^:const roles
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

(defonce ^:const permissions
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

(defonce ^:const rbac-tables-up-sql
  "dev.gethop.rbac.next/rbac-tables.pg.up.sql")

(defonce ^:const rbac-tables-down-sql
  "dev.gethop.rbac.next/rbac-tables.pg.down.sql")

(defonce ^:const app-tables-up-sql
  "_files/app-tables.up.sql")

(defonce ^:const app-tables-down-sql
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
  :once (fn reset-db [f]
          (enable-instrumentation)
          (setup-app-objects)
          (setup-rbac-tables)
          (f)
          (destroy-rbac-tables)
          (destroy-app-objects)))

(deftest test-1
  (let [_ (rbac/create-context-types! db (vals context-types))
        application-ctx (:context (rbac/create-context! db application-context nil))
        organization-1-ctx (:context (rbac/create-context! db organization-1-context application-ctx))
        plant-1-ctx (:context (rbac/create-context! db plant-1-context organization-1-ctx))
        _ (:context (rbac/create-context! db asset-1-context plant-1-ctx))
        created-roles (rbac/create-roles! db roles)
        _ (rbac/create-permissions! db permissions)
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

    (testing "app-user-1 :application/manage permission on :plant-1 resource"
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
        (is (= has-permission false))))))

(comment
  ;; TODO: Create all the individual unit tests by leveraging the example code below.

  ;; -----------------------------------------------------
  (rbac/create-roles! db roles)
  (rbac/create-role! db (first roles))
  (rbac/get-roles db)
  (rbac/get-role-by-id db
                       (-> (rbac/get-role-by-name db :application/manager)
                           :role
                           :id))
  (rbac/get-role-by-name db :application/manager)
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
  (rbac/create-context-types! db (vals context-types))
  (rbac/create-context-type! db (:application context-types))
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
