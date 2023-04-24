(ns dev.gethop.rbac.next
  (:require [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [honey.sql :as hsql]
            [next.jdbc :as jdbc]
            [next.jdbc.specs :as jdbc.specs]
            [next.jdbc.sql :as jdbc.sql])
  (:import [java.util UUID]))

(defn- kw->str
  [k]
  (str (symbol k)))

(defn- str->kw
  [s]
  (keyword s))

(defn- get-*
  [db-spec table vals-kw]
  (let [query (hsql/format {:select [:*]
                            :from [table]})
        return-values (jdbc.sql/query db-spec query jdbc/unqualified-snake-kebab-opts)]
    {:success? true
     vals-kw return-values}))

(defn- get-*-where-y
  [db-spec table conditions]
  (let [query (hsql/format {:select [:*]
                            :from [table]
                            :where conditions})
        return-values (jdbc.sql/query db-spec query jdbc/unqualified-snake-kebab-opts)]
    (if (> (count return-values) 0)
      {:success? true :values return-values}
      {:success? false})))

(defn- delete-where-x!
  [db-spec table conditions]
  (let [query (hsql/format {:delete-from table
                            :where conditions})
        result (jdbc/execute-one! db-spec query)]
    (if (> (::jdbc/update-count result) 0)
      {:success? true}
      {:success? false})))

(defn- update-if-exists
  [m k update-fn & args]
  (if-not (= ::not-found (get m k ::not-found))
    (apply update m k update-fn args)
    m))

;; -----------------------------------------------------------
(defn- role->db-role
  [role]
  (-> role
      (update :name kw->str)))

(defn- db-role->role
  [db-role]
  (-> db-role
      (update-if-exists :name str->kw)))

(s/def ::db-spec ::jdbc.specs/db-spec)
(s/def ::id uuid?)
(s/def ::string-id (s/and string? (complement str/blank?)))
(s/def ::ids (s/coll-of ::id))
(s/def ::name keyword?)
(s/def ::names (s/coll-of ::name))
(s/def ::description string?)
(s/def ::role (s/keys :req-un [::name]
                      :opt-un [::id ::description]))
(s/def ::success? boolean)
(s/def ::create-role!-args (s/cat :db-spec ::db-spec
                                  :role ::role))
(s/def ::create-role!-ret (s/keys :req-un [::success?]
                                  :opt-un [::role]))
(s/fdef create-role!
  :args ::create-role!-args
  :ret  ::create-role!-ret)

(defn create-role!
  [db-spec role]
  {:pre [(and (s/valid? ::db-spec db-spec)
              (s/valid? ::role role))]}
  (let [role-id (UUID/randomUUID)
        db-role (-> role
                    (assoc :id role-id)
                    (role->db-role))
        result (jdbc.sql/insert! db-spec :rbac-role db-role
                                 jdbc/unqualified-snake-kebab-opts)]
    (if (seq result)
      {:success? true
       :role (assoc role :id role-id)}
      {:success? false})))

(s/def ::roles (s/coll-of ::role))
(s/def ::create-roles!-args (s/cat :db-spec ::db-spec
                                   :roles ::roles))
(s/def ::create-roles!-ret (s/coll-of ::create-role!-ret))
(s/fdef create-roles!
  :args ::create-roles!-args
  :ret  ::create-roles!-ret)

(defn create-roles!
  [db-spec roles]
  (doall (map #(create-role! db-spec %) roles)))

(s/def ::get-roles-args (s/cat :db-spec ::db-spec))
(s/def ::get-roles-ret (s/keys :req-un [::success?]
                               :opt-un [::roles]))
(s/fdef get-roles
  :args ::get-roles-args
  :ret  ::get-roles-ret)

(defn get-roles
  [db-spec]
  (let [return (get-* db-spec :rbac_role :roles)]
    (update return :roles #(map db-role->role %))))

(defn- get-role-by-*
  [db-spec column value]
  (let [{:keys [success? values]} (get-*-where-y db-spec :rbac-role
                                                 [:= column value])]
    {:success? success?
     :role (first (map db-role->role values))}))

(s/def ::get-role-by-id-args (s/cat :db-spec ::db-spec
                                    :role-id ::id))
(s/def ::get-role-by-id-ret (s/keys :req-un [::success?]
                                    :opt-un [::role]))
(s/fdef get-role-by-id
  :args ::get-role-by-id-args
  :ret  ::get-role-by-id-ret)

(defn get-role-by-id
  [db-spec role-id]
  (get-role-by-* db-spec :id role-id))

(s/def ::get-role-by-name-args (s/cat :db-spec ::db-spec
                                      :name ::name))
(s/def ::get-role-by-name-ret (s/keys :req-un [::success?]
                                      :opt-un [::role]))
(s/fdef get-role-by-name
  :args ::get-role-by-name-args
  :ret  ::get-role-by-name-ret)

(defn get-role-by-name
  [db-spec name]
  (get-role-by-* db-spec :name (kw->str name)))

(s/def ::update-role!-args (s/cat :db-spec ::db-spec
                                  :role ::role))
(s/def ::update-role!-ret (s/keys :req-un [::success?]
                                  :opt-un [::role]))
(s/fdef update-role!
  :args ::update-role!-args
  :ret  ::update-role!-ret)

(defn update-role!
  [db-spec role]
  (let [result (jdbc.sql/update! db-spec
                                 :rbac-role
                                 (role->db-role role)
                                 ["id = ?" (:id role)]
                                 jdbc/unqualified-snake-kebab-opts)]
    (if (> (::jdbc/update-count result) 0)
      {:success? true
       :role role}
      {:success? false})))

(s/def ::update-roles!-args (s/cat :db-spec ::db-spec
                                   :roles ::roles))
(s/def ::update-roles!-ret (s/keys :req-un [::success?]
                                   :opt-un [::roles]))
(s/fdef update-roles!
  :args ::update-roles!-args
  :ret  ::update-roles!-ret)

(defn update-roles!
  [db-spec roles]
  (doall (map #(update-role! db-spec %) roles)))

(s/def ::delete-role!-args (s/cat :db-spec ::db-spec
                                  :role ::role))
(s/def ::delete-role!-ret (s/keys :req-un [::success?]))
(s/fdef delete-role!
  :args ::delete-role!-args
  :ret  ::delete-role!-ret)

(defn delete-role!
  [db-spec role]
  (delete-where-x! db-spec :rbac-role [:= :id (:id role)]))

(s/def ::delete-role-by-id!-args (s/cat :db-spec ::db-spec
                                        :role-id ::id))
(s/def ::delete-role-by-id!-ret (s/keys :req-un [::success?]))
(s/fdef delete-role-by-id!
  :args ::delete-role-by-id!-args
  :ret  ::delete-role-by-id!-ret)

(defn delete-role-by-id!
  [db-spec role-id]
  (delete-where-x! db-spec :rbac-role [:= :id role-id]))

(s/def ::delete-role-by-name!-args (s/cat :db-spec ::db-spec
                                          :name ::name))
(s/def ::delete-role-by-name!-ret (s/keys :req-un [::success?]))
(s/fdef delete-role-by-name!
  :args ::delete-role-by-name!-args
  :ret  ::delete-role-by-name!-ret)

(defn delete-role-by-name!
  [db-spec name]
  (delete-where-x! db-spec :rbac-role [:= :name (kw->str name)]))

(s/def ::delete-roles!-args (s/cat :db-spec ::db-spec
                                   :roles ::roles))
(s/def ::delete-roles!-ret (s/keys :req-un [::success?]))
(s/fdef delete-roles!
  :args ::delete-roles!-args
  :ret  ::delete-roles!-ret)

(defn delete-roles!
  [db-spec roles]
  (doall (map #(delete-role-by-name! db-spec (:name %)) roles)))

(s/def ::delete-roles-by-ids!-args (s/cat :db-spec ::db-spec
                                          :ids ::ids))
(s/def ::delete-roles-by-ids!-ret (s/keys :req-un [::success?]))
(s/fdef delete-roles-by-ids!
  :args ::delete-roles-by-ids!-args
  :ret  ::delete-roles-by-ids!-ret)

(defn delete-roles-by-ids!
  [db-spec ids]
  (doall (map #(delete-role-by-id! db-spec %) ids)))

(s/def ::delete-roles-by-names!-args (s/cat :db-spec ::db-spec
                                            :names ::names))
(s/def ::delete-roles-by-names!-ret (s/keys :req-un [::success?]))
(s/fdef delete-roles-by-names!
  :args ::delete-roles-by-names!-args
  :ret  ::delete-roles-by-names!-ret)

(defn delete-roles-by-names!
  [db-spec names]
  (doall (map #(delete-role-by-name! db-spec %) names)))

;; -----------------------------------------------------------
(defn- context-type->db-context-type
  [context-type]
  (-> context-type
      (update :name kw->str)))

(defn- db-context-type->context-type
  [db-context-type]
  (-> db-context-type
      (update-if-exists :name str->kw)))

(s/def ::context-type-name ::name)
(s/def ::description string?)
(s/def ::context-type (s/keys :req-un [::name]
                              :opt-un [::description]))
(s/def ::create-context-type!-args (s/cat :db-spec ::db-spec
                                          :context-type ::context-type))
(s/def ::create-context-type!-ret (s/keys :req-un [::success?]
                                          :opt-un [::context-type]))
(s/fdef create-context-type!
  :args ::create-context-type!-args
  :ret  ::create-context-type!-ret)

(defn create-context-type!
  [db-spec context-type]
  (let [db-context-type (context-type->db-context-type context-type)
        result (jdbc.sql/insert! db-spec :rbac-context-type db-context-type
                                 jdbc/unqualified-snake-kebab-opts)]
    (if (seq result)
      {:success? true
       :context-type context-type}
      {:success? false})))

(s/def ::context-types (s/coll-of ::context-type))
(s/def ::create-context-types!-args (s/cat :db-spec ::db-spec
                                           :context-types ::context-types))
(s/def ::create-context-types!-ret (s/coll-of ::create-context-type!-ret))
(s/fdef create-context-types!
  :args ::create-context-types!-args
  :ret  ::create-context-types!-ret)

(defn create-context-types!
  [db-spec context-types]
  (doall (map #(create-context-type! db-spec %) context-types)))

(s/def ::get-context-types-args (s/cat :db-spec ::db-spec))
(s/def ::get-context-types-ret (s/keys :req-un [::success?]
                                       :opt-un [::context-types]))
(s/fdef get-context-types
  :args ::get-context-types-args
  :ret  ::get-context-types-ret)

(defn get-context-types
  [db-spec]
  (let [return (get-* db-spec :rbac_context_type :context-types)]
    (update return :context-types #(map db-context-type->context-type %))))

(s/def ::get-context-type-args (s/cat :db-spec ::db-spec
                                      :context-type-name ::context-type-name))
(s/def ::get-context-type-ret (s/keys :req-un [::success? ::context-type]))
(s/fdef get-context-type
  :args ::get-context-type-args
  :ret  ::get-context-type-ret)

(defn get-context-type
  [db-spec context-type-name]
  (let [{:keys [success? values]}
        (get-*-where-y db-spec :rbac-context-type
                       [:= :name (kw->str context-type-name)])]
    (if-not success?
      {:success? false}
      {:success? success?
       :context-type (db-context-type->context-type (first values))})))

(s/def ::update-context-type!-args (s/cat :db-spec ::db-spec
                                          :context-type ::context-type))
(s/def ::update-context-type!-ret (s/keys :req-un [::success?]
                                          :opt-un [::context-type]))
(s/fdef update-context-type!
  :args ::update-context-type!-args
  :ret  ::update-context-type!-ret)

(defn update-context-type!
  [db-spec context-type]
  (let [db-context-type (context-type->db-context-type context-type)
        result (jdbc.sql/update! db-spec
                                 :rbac-context-type
                                 db-context-type
                                 ["name = ?" (:name db-context-type)]
                                 jdbc/unqualified-snake-kebab-opts)]
    (if (> (::jdbc/update-count result) 0)
      {:success? true
       :context-type context-type}
      {:success? false})))

(s/def ::update-context-types!-args (s/cat :db-spec ::db-spec
                                           :context-types ::context-types))
(s/def ::update-context-types!-ret (s/keys :req-un [::success?]
                                           :opt-un [::context-types]))
(s/fdef update-context-types!
  :args ::update-context-types!-args
  :ret  ::update-context-types!-ret)

(defn update-context-types!
  [db-spec context-types]
  (doall (map #(update-context-type! db-spec %) context-types)))

(s/def ::delete-context-type!-args (s/cat :db-spec ::db-spec
                                          :context-type ::context-type))
(s/def ::delete-context-type!-ret (s/keys :req-un [::success?]))
(s/fdef delete-context-type!
  :args ::delete-context-type!-args
  :ret  ::delete-context-type!-ret)

(defn delete-context-type!
  [db-spec context-type]
  (delete-where-x! db-spec :rbac-context-type
                   [:= :name (kw->str (:name context-type))]))

(s/def ::delete-context-types!-args (s/cat :db-spec ::db-spec
                                           :context-types ::context-types))
(s/def ::delete-context-types!-ret (s/keys :req-un [::success?]))
(s/fdef delete-context-types!
  :args ::delete-context-types!-args
  :ret  ::delete-context-types!-ret)

(defn delete-context-types!
  [db-spec context-types]
  (doall (map #(delete-context-type! db-spec %) context-types)))

;; -----------------------------------------------------------
(defn- context->db-context
  [context]
  (-> context
      (set/rename-keys {:parent-id :parent})
      (update :context-type-name kw->str)))

(defn- db-context->context
  [db-context]
  (-> db-context
      (set/rename-keys {:parent :parent-id})
      (update-if-exists :context-type-name str->kw)))

(s/def ::resource-id (s/or :string-id ::string-id
                           :int-id pos-int?
                           :uuid uuid?))
(s/def ::parent-id (s/nilable ::id))
(s/def ::context (s/keys :req-un [::context-type-name
                                  ::resource-id]
                         :opt-un [::id
                                  ::parent-id]))
(s/def ::create-context!-args (s/cat :db-spec ::db-spec
                                     :context ::context
                                     :parent-context (s/nilable ::context)))
(s/def ::create-context!-ret (s/keys :req-un [::success?]))
(s/fdef create-context!
  :args ::create-context!-args
  :ret  ::create-context!-ret)

(defn create-context!
  "To be able to create the top-level context, pass `nil` for PARENT-CONTEXT"
  [db-spec context parent-context]
  (let [context-id (UUID/randomUUID)
        db-context (-> context
                       (assoc :id context-id)
                       (assoc :parent-id (:id parent-context))
                       (context->db-context))
        result (jdbc.sql/insert! db-spec :rbac-context db-context
                                 jdbc/unqualified-snake-kebab-opts)]
    (if (seq result)
      {:success? true
       :context (assoc context :id context-id :parent-id (:id parent-context))}
      {:success? false})))

(s/def ::contexts (s/coll-of ::context))
(s/def ::get-contexts-args (s/cat :db-spec ::db-spec))
(s/def ::get-contexts-ret (s/keys :req-un [::success?
                                           ::contexts]))
(s/fdef get-contexts
  :args ::get-contexts-args
  :ret  ::get-contexts-ret)

(defn get-contexts
  [db-spec]
  (let [return (get-* db-spec :rbac_context :contexts)]
    (update return :contexts #(map db-context->context %))))

(s/def ::get-context-args (s/cat :db-spec ::db-spec
                                 :context-type-name ::context-type-name
                                 :resource-id ::resource-id))
(s/def ::get-context-ret (s/keys :req-un [::success?
                                          ::context]))
(s/fdef get-context
  :args ::get-context-args
  :ret  ::get-context-ret)

(defn get-context
  [db-spec context-type-name resource-id]
  (let [{:keys [success? values]}
        (get-*-where-y db-spec :rbac-context
                       [:and
                        [:= :context-type-name (kw->str context-type-name)]
                        [:= :resource-id resource-id]])]
    {:success? success?
     :context (db-context->context (first values))}))

(s/def ::update-context!-args (s/cat :db-spec ::db-spec
                                     :context ::context))
(s/def ::update-context!-ret (s/keys :req-un [::success?
                                              ::context]))
(s/fdef update-context!
  :args ::update-context!-args
  :ret  ::update-context!-ret)

(defn update-context!
  [db-spec context]
  (let [result (jdbc.sql/update! db-spec
                                 :rbac-context
                                 (context->db-context context)
                                 ["id = ?" (:id context)]
                                 jdbc/unqualified-snake-kebab-opts)]
    (if (> (::jdbc/update-count result) 0)
      {:success? true
       :context context}
      {:success? false})))

(s/def ::update-contexts!-args (s/cat :db-spec ::db-spec
                                      :contexts ::contexts))
(s/def ::update-contexts!-ret (s/keys :req-un [::success?
                                               ::contexts]))
(s/fdef update-contexts!
  :args ::update-contexts!-args
  :ret  ::update-contexts!-ret)

(defn update-contexts!
  [db-spec contexts]
  (doall (map #(update-context! db-spec %) contexts)))

(s/def ::delete-context!-args (s/cat :db-spec ::db-spec
                                     :context ::context))
(s/def ::delete-context!-ret (s/keys :req-un [::success?]))
(s/fdef delete-context!
  :args ::delete-context!-args
  :ret  ::delete-context!-ret)

(defn delete-context!
  [db-spec {:keys [context-type-name resource-id]}]
  (delete-where-x! db-spec :rbac-context [:and
                                          [:= :context-type-name (kw->str context-type-name)]
                                          [:= :resource-id resource-id]]))

(s/def ::delete-contexts!-args (s/cat :db-spec ::db-spec
                                      :contexts ::contexts))
(s/def ::delete-contexts!-ret (s/keys :req-un [::success?]))
(s/fdef delete-contexts!
  :args ::delete-contexts!-args
  :ret  ::delete-contexts!-ret)

(defn delete-contexts!
  [db-spec contexts]
  (doall (map #(delete-context! db-spec %) contexts)))

;; -----------------------------------------------------------
(defn- perm->db-perm
  [perm]
  (-> perm
      (update :name kw->str)
      (update :context-type-name kw->str)))

(defn- db-perm->perm
  [db-perm]
  (-> db-perm
      (update-if-exists :name str->kw)
      (update-if-exists :context-type-name str->kw)))

(s/def ::permission-name ::name)
(s/def ::permission (s/keys :req-un [::name
                                     ::context-type-name]
                            :opt-un [::id
                                     ::description]))
(s/def ::create-permission!-args (s/cat :db-spec ::db-spec
                                        :permission ::permission))
(s/def ::create-permission!-ret (s/keys :req-un [::success?]
                                        :opt-un [::permission]))
(s/fdef create-permission!
  :args ::create-permission!-args
  :ret  ::create-permission!-ret)

(defn create-permission!
  [db-spec permission]
  (let [permission-id (UUID/randomUUID)
        db-permission (-> permission
                          (assoc :id permission-id)
                          perm->db-perm)
        result (jdbc.sql/insert! db-spec :rbac-permission db-permission
                                 jdbc/unqualified-snake-kebab-opts)]
    (if (seq result)
      {:success? true
       :permission (assoc permission :id permission-id)}
      {:success? false})))

(s/def ::permissions (s/coll-of ::permission))
(s/def ::create-permissions!-args (s/cat :db-spec ::db-spec
                                         :permissions ::permissions))
(s/def ::create-permissions!-ret (s/keys :req-un [::success?]
                                         :opt-un [::permissions]))
(s/fdef create-permissions!
  :args ::create-permissions!-args
  :ret  ::create-permissions!-ret)

(defn create-permissions!
  [db-spec permissions]
  (doall (map #(create-permission! db-spec %) permissions)))

(s/def ::get-permissions-args (s/cat :db-spec ::db-spec))
(s/def ::get-permissions-ret (s/keys :req-un [::success?]
                                     :opt-un [::permissions]))
(s/fdef get-permissions
  :args ::get-permissions-args
  :ret  ::get-permissions-ret)

(defn get-permissions
  [db-spec]
  (let [result (get-* db-spec :rbac_permission :permissions)]
    (if-not (:success? result)
      {:success? false}
      (update result :permissions #(map db-perm->perm %)))))

(defn- get-permission-by-*
  [db-spec column value]
  (let [{:keys [success? values]} (get-*-where-y db-spec :rbac_permission
                                                 [:= column value])]
    {:success? success?
     :permission (db-perm->perm (first values))}))

(s/def ::get-permission-by-id-args (s/cat :db-spec ::db-spec
                                          :id ::id))
(s/def ::get-permission-by-id-ret (s/keys :req-un [::success?]
                                          :opt-un [::permission]))
(s/fdef get-permission-by-id
  :args ::get-permission-by-id-args
  :ret  ::get-permission-by-id-ret)

(defn get-permission-by-id
  [db-spec id]
  (get-permission-by-* db-spec :id id))

(s/def ::get-permission-by-name-args (s/cat :db-spec ::db-spec
                                            :name ::name))
(s/def ::get-permission-by-name-ret (s/keys :req-un [::success?]
                                            :opt-un [::permission]))
(s/fdef get-permission-by-name
  :args ::get-permission-by-name-args
  :ret  ::get-permission-by-name-ret)

(defn get-permission-by-name
  [db-spec name]
  (get-permission-by-* db-spec :name (kw->str name)))

(s/def ::update-permission!-args (s/cat :db-spec ::db-spec
                                        :permission ::permission))
(s/def ::update-permission!-ret (s/keys :req-un [::success?]
                                        :opt-un [::permission]))
(s/fdef update-permission!
  :args ::update-permission!-args
  :ret  ::update-permission!-ret)

(defn update-permission!
  [db-spec permission]
  (let [result (jdbc.sql/update! db-spec
                                 :rbac-permission
                                 (perm->db-perm permission)
                                 ["id = ?" (:id permission)]
                                 jdbc/unqualified-snake-kebab-opts)]
    (if (> (::jdbc/update-count result) 0)
      {:success? true
       :permission permission}
      {:success? false})))

(s/def ::update-permissions!-args (s/cat :db-spec ::db-spec
                                         :permissions ::permissions))
(s/def ::update-permissions!-ret (s/keys :req-un [::success?]
                                         :opt-un [::permissions]))
(s/fdef update-permissions!
  :args ::update-permissions!-args
  :ret  ::update-permissions!-ret)

(defn update-permissions!
  [db-spec permissions]
  (doall (map #(update-permission! db-spec %) permissions)))

(s/def ::delete-permission!-args (s/cat :db-spec ::db-spec
                                        :permission ::permission))
(s/def ::delete-permission!-ret (s/keys :req-un [::success?]))
(s/fdef delete-permission!
  :args ::delete-permission!-args
  :ret  ::delete-permission!-ret)

(defn delete-permission!
  [db-spec permission]
  (delete-where-x! db-spec :rbac-permission [:= :id (:id permission)]))

(s/def ::delete-permission-by-id!-args (s/cat :db-spec ::db-spec
                                              :id ::id))
(s/def ::delete-permission-by-id!-ret (s/keys :req-un [::success?]))
(s/fdef delete-permission-by-id!
  :args ::delete-permission-by-id!-args
  :ret  ::delete-permission-by-id!-ret)

(defn delete-permission-by-id!
  [db-spec id]
  (delete-where-x! db-spec :rbac-permission [:= :id id]))

(s/def ::delete-permission-by-name!-args (s/cat :db-spec ::db-spec
                                                :name ::name))
(s/def ::delete-permission-by-name!-ret (s/keys :req-un [::success?]))
(s/fdef delete-permission-by-name!
  :args ::delete-permission-by-name!-args
  :ret  ::delete-permission-by-name!-ret)

(defn delete-permission-by-name!
  [db-spec name]
  (delete-where-x! db-spec :rbac-permission [:= :name (kw->str name)]))

(s/def ::delete-permissions!-args (s/cat :db-spec ::db-spec
                                         :permissions ::permissions))
(s/def ::delete-permissions!-ret (s/keys :req-un [::success?]))
(s/fdef delete-permissions!
  :args ::delete-permissions!-args
  :ret  ::delete-permissions!-ret)

(defn delete-permissions!
  [db-spec permissions]
  (doall (map #(delete-permission! db-spec %) permissions)))

(s/def ::delete-permissions-by-ids!-args (s/cat :db-spec ::db-spec
                                                :ids ::ids))
(s/def ::delete-permissions-by-ids!-ret (s/keys :req-un [::success?]))
(s/fdef delete-permission-by-idss!
  :args ::delete-permissions-by-ids!-args
  :ret  ::delete-permissions-by-ids!-ret)

(defn delete-permissions-by-ids!
  [db-spec ids]
  (doall (map #(delete-permission-by-id! db-spec %) ids)))

(s/def ::delete-permission-by-names!-args (s/cat :db-spec ::db-spec
                                                 :names ::names))
(s/def ::delete-permission-by-names!-ret (s/keys :req-un [::success?]))
(s/fdef delete-permission-by-names!
  :args ::delete-permission-by-names!-args
  :ret  ::delete-permission-by-names!-ret)

(defn delete-permissions-by-names!
  [db-spec names]
  (doall (map #(delete-permission-by-name! db-spec %) names)))

;; -----------------------------------------------------------
(s/def ::user-id (s/or :string-id ::string-id
                       :int-id pos-int?
                       :uuid uuid?))
(s/def ::add-super-admin-args (s/cat :db-spec ::db-spec
                                     :user-id ::user-id))
(s/def ::add-super-admin-ret (s/keys :req-un [::success?]))
(s/fdef add-super-admin
  :args ::add-super-admin-args
  :ret  ::add-super-admin-ret)

(defn add-super-admin!
  [db-spec user-id]
  (let [result (jdbc.sql/insert! db-spec :rbac-super-admin {:user-id user-id}
                                 jdbc/unqualified-snake-kebab-opts)]
    (if (seq result)
      {:success? true}
      {:success? false})))

(s/def ::super-admin?-args (s/cat :db-spec ::db-spec
                                  :user-id ::user-id))
(s/def ::super-admin? boolean)
(s/def ::super-admin?-ret (s/keys :req-un [::success?]
                                  :opt-un [::super-admin?]))
(s/fdef super-admin?
  :args ::super-admin?-args
  :ret  ::super-admin?-ret)

(defn super-admin?
  [db-spec user-id]
  (let [query (hsql/format {:select [:user-id]
                            :from [:rbac-super-admin]
                            :where [:= :user-id user-id]})
        return-values (jdbc.sql/query db-spec query jdbc/unqualified-snake-kebab-opts)]
    (if (> (count return-values) 0)
      {:success? true :super-admin? true}
      {:success? true :super-admin? false})))

(s/def ::remove-super-admin-args (s/cat :db-spec ::db-spec
                                        :user-id ::user-id))
(s/def ::remove-super-admin-ret (s/keys :req-un [::success?]))
(s/fdef remove-super-admin
  :args ::remove-super-admin-args
  :ret  ::remove-super-admin-ret)

(defn remove-super-admin!
  [db-spec user-id]
  (delete-where-x! db-spec :rbac-super-admin [:= :user-id user-id]))

;; -----------------------------------------------------------
(s/def ::permission-value #{:permission-granted :permission-denied})

(defn- set-perm-with-value!
  [db-spec role permission permission-value]
  {:pre [(s/valid? ::role role)
         (s/valid? ::permission permission)
         (s/valid? ::permission-value permission-value)]}
  (let [perm-val (case permission-value
                   :permission-granted 1
                   :permission-denied -1)
        role-permission {:role-id (:id role)
                         :permission-id (:id permission)
                         :permission-value perm-val}]
    ;; Because we don't know which database engine is being used, we
    ;; can't rely on any specific "UPSERT" syntax (different db
    ;; engines use different syntax for it). We need to fall back to
    ;; simulate it using database transactions.
    (jdbc/with-transaction [tx db-spec]
      (let [result (jdbc.sql/update! tx
                                     :rbac-role-permission
                                     role-permission
                                     ["role_id = ? AND permission_id = ?"
                                      (:id role) (:id permission)]
                                     jdbc/unqualified-snake-kebab-opts)
            count (::jdbc/update-count result)]
        (cond
          ;; Nothing was updated, so insert a new row.
          (zero? count)
          (let [result (jdbc.sql/insert! tx :rbac-role-permission role-permission
                                         jdbc/unqualified-snake-kebab-opts)]
            (if (seq result)
              {:success? true}
              {:success? false}))

          ;; A single row updated, we are good to go!
          (= 1 count)
          {:success? true}

          ;; Whoops, we updated too many rows. Roll the update back.
          ;; Throwing an exception will roll back the update,
          ;; leaving the database untouched.
          :else
          (throw (Exception. "Tried to update more than one row")))))))

(s/def ::grant-role-permission!-args (s/cat :db-spec ::db-spec
                                            :role ::role
                                            :permission ::permission))
(s/def ::grant-role-permission!-ret (s/keys :req-un [::success?]))
(s/fdef grant-role-permission!
  :args ::grant-role-permission!-args
  :ret  ::grant-role-permission!-ret)

(defn grant-role-permission!
  [db-spec role permission]
  (set-perm-with-value! db-spec role permission :permission-granted))

(s/def ::grant-role-permissions!-args (s/cat :db-spec ::db-spec
                                             :role ::role
                                             :permissions ::permissions))
(s/def ::grant-role-permissions!-ret (s/keys :req-un [::success?]))
(s/fdef grant-role-permissions!
  :args ::grant-role-permissions!-args
  :ret  ::grant-role-permissions!-ret)

(defn grant-role-permissions!
  [db-spec role permissions]
  (doall (map #(set-perm-with-value! db-spec role % :permission-granted) permissions)))

(s/def ::deny-role-permission!-args (s/cat :db-spec ::db-spec
                                           :role ::role
                                           :permission ::permission))
(s/def ::deny-role-permission!-ret (s/keys :req-un [::success?]))
(s/fdef deny-role-permission!
  :args ::deny-role-permission!-args
  :ret  ::deny-role-permission!-ret)

(defn deny-role-permission!
  [db-spec role permission]
  (set-perm-with-value! db-spec role permission :permission-denied))

(s/def ::deny-role-permissions!-args (s/cat :db-spec ::db-spec
                                            :role ::role
                                            :permissions ::permissions))
(s/def ::deny-role-permissions!-ret (s/keys :req-un [::success?]))
(s/fdef deny-role-permissions!
  :args ::deny-role-permissions!-args
  :ret  ::deny-role-permissions!-ret)

(defn deny-role-permissions!
  [db-spec role permissions]
  (doall (map #(set-perm-with-value! db-spec role % :permission-denied) permissions)))

(s/def ::remove-role-permission!-args (s/cat :db-spec ::db-spec
                                             :role ::role
                                             :permission ::permission))
(s/def ::remove-role-permission!-ret (s/keys :req-un [::success?]))
(s/fdef remove-role-permission!
  :args ::remove-role-permission!-args
  :ret  ::remove-role-permission!-ret)

(defn remove-role-permission!
  [db-spec role permission]
  (delete-where-x! db-spec :rbac-role-permission [:and
                                                  [:= :role-id (:id role)]
                                                  [:= :permission-id (:id permission)]]))

(s/def ::remove-role-permissions!-args (s/cat :db-spec ::db-spec
                                              :role ::role
                                              :permissions ::permissions))
(s/def ::remove-role-permissions!-ret (s/keys :req-un [::success?]))
(s/fdef remove-role-permissions!
  :args ::remove-role-permissions!-args
  :ret  ::remove-role-permissions!-ret)

(defn remove-role-permissions!
  [db-spec role permissions]
  (doall (map #(remove-role-permission! db-spec role %) permissions)))

;; -----------------------------------------------------------

(defn- db-role-assignment->role-assignment
  [{:keys [role-id role-name role-description
           context-id context-resource-id context-type-name
           context-parent assignment-user-id]}]
  {:role {:id role-id
          :name (str->kw role-name)
          :description role-description}
   :context {:id context-id
             :resource-id context-resource-id
             :context-type-name (str->kw context-type-name)
             :parent-id context-parent}
   :user {:id assignment-user-id}})

(s/def ::role-assignment (s/keys :req-un [::role
                                          ::context
                                          ::user]))
(s/def ::assign-role!-args (s/cat :db-spec ::db-spec
                                  :role-assignment ::role-assignment))
(s/def ::assign-role!-ret (s/keys :req-un [::success?]))
(s/fdef assign-role!
  :args ::assign-role!-args
  :ret  ::assign-role!-ret)

(defn assign-role!
  [db-spec {:keys [role context user]}]
  (let [role-assignement {:role-id (:id role)
                          :context-id (:id context)
                          :user-id (:id user)}
        result (jdbc.sql/insert! db-spec :rbac-role-assignment role-assignement
                                 jdbc/unqualified-snake-kebab-opts)]
    (if (seq result)
      {:success? true}
      {:success? false})))

(s/def ::role-assignments (s/coll-of ::role-assignment))
(s/def ::assign-roles!-args (s/cat :db-spec ::db-spec
                                   :role-assignments ::role-assignments))
(s/def ::assign-roles!-ret (s/coll-of (s/keys :req-un [::success?])))
(s/fdef assign-roles!
  :args ::assign-roles!-args
  :ret  ::assign-roles!-ret)

(defn assign-roles!
  [db-spec role-assignments]
  (doall (map #(assign-role! db-spec %) role-assignments)))

(s/def ::unassign-role!-args (s/cat :db-spec ::db-spec
                                    :role-assignment ::role-assignment))
(s/def ::unassign-role!-ret (s/keys :req-un [::success?]))
(s/fdef unassign-role!
  :args ::unassign-role!-args
  :ret  ::unassign-role!-ret)

(defn unassign-role!
  [db-spec {:keys [role context user]}]
  (delete-where-x! db-spec :rbac-role-assignment [:and
                                                  [:= :role-id (:id role)]
                                                  [:= :context-id (:id context)]
                                                  [:= :user-id (:id user)]]))

(s/def ::unassign-roles!-args (s/cat :db-spec ::db-spec
                                     :role-assignments ::role-assignments))
(s/def ::unassign-roles!-ret (s/keys :req-un [::success?]))
(s/fdef unassign-roles!
  :args ::unassign-roles!-args
  :ret  ::unassign-roles!-ret)

(defn unassign-roles!
  [db-spec unassignments]
  (doall (map #(unassign-role! db-spec %) unassignments)))

(s/def ::get-role-assignments-by-user-args (s/cat :db-spec ::db-spec
                                                  :user-id ::user-id
                                                  ::context-id ::id))
(s/def ::get-role-assignments-by-user-ret (s/keys :req-un [::success?]
                                                  :opt-un [::role-assignments]))

(s/fdef get-role-assignments-by-user
  :args ::get-role-assignments-by-user-args
  :ret  ::get-role-assignments-by-user-ret)

(defn get-role-assignments-by-user
  ([db-spec user-id]
   (get-role-assignments-by-user db-spec user-id nil))
  ([db-spec user-id context-id]
   (let [query {:select [[:role.id :role-id]
                         [:role.name :role-name]
                         [:role.description :role-description]
                         [:context.id :context-id]
                         [:context.resource-id :context-resource-id]
                         [:context.context-type-name :context-type-name]
                         [:context.parent :context-parent]
                         [:assignment.user-id :assignment-user-id]]
                :from [[:rbac-role-assignment :assignment]]
                :join [[:rbac-role :role] [:= :assignment.role-id :role.id]
                       [:rbac-context :context] [:= :assignment.context-id :context.id]]
                :where [:and
                        [:= :user-id user-id]
                        (when context-id
                          [:= :context-id context-id])]}
         return-values (jdbc.sql/query db-spec (hsql/format query)
                                       jdbc/unqualified-snake-kebab-opts)]
     {:success? true
      :role-assignments (map db-role-assignment->role-assignment return-values)})))

;; -----------------------------------------------------------
(s/def ::has-permission-args (s/cat :db-spec ::db-spec
                                    :user-id ::user-id
                                    :resource-id ::resource-id
                                    :context-type-name ::context-type-name
                                    :permission-name ::permission-name))
(s/def ::has-permission-ret boolean?)
(s/fdef has-permission
  :args ::has-permission-args
  :ret  ::has-permission-ret)

(defn has-permission
  [db-spec user-id resource-id context-type-name permission-name]
  (let [;; WITH RECURSE construct Inspired by familiy tree example at
        ;; https://sqlite.org/lang_with.html
        ancestors {:with-recursive
                   [[[:parent-of {:columns [:id :resource-id :context-type-name :parent]}]
                     {:select [:id :resource-id :context-type-name :parent]
                      :from [:rbac-context]}]
                    [[:ancestor-of-context {:columns [:id, :resource-id]}]
                     {:union-all
                      [{:select [:parent-of.parent
                                 :parent-of.resource-id
                                 :parent-of.context-type-name]
                        :from [:parent-of]
                        :where [:and
                                [:= :resource-id resource-id]
                                [:= :context-type-name (kw->str context-type-name)]]}
                       {:select [:parent-of.parent
                                 :parent-of.resource-id
                                 :parent-of.context-type-name]
                        :from [:parent-of]
                        :join [:ancestor-of-context
                               [:using :id]]}]}]]
                   :select [:rbac-context.id]
                   :from [:rbac-context :ancestor-of-context]
                   :where [:= :ancestor-of-context.id :rbac-context.id]}
        applicable-contexts {:select [:rc.id]
                             :from [[:rbac-context :rc]]
                             :where [:or
                                     [:and
                                      [:= :rc.resource-id resource-id]
                                      [:= :rc.context-type-name (kw->str context-type-name)]]
                                     [:= :rc.id [:any ancestors]]]}
        query (hsql/format
               {:with
                [[:super-admin
                  {:select [[user-id :user-id]
                            [[:exists {:select [:user-id]
                                       :from   [:rbac-super-admin]
                                       :where  [:= :user-id user-id]}]
                             :super-admin]]}]
                 [:has-permission
                  {:select [[user-id :user-id]
                            [[:coalesce [:every [:> :rrp.permission-value [:inline 0]]] false]
                             :has-permission]]
                   :from   [[:rbac-context :rc]]
                   :join   [[:rbac-role-assignment :rra] [:= :rra.context-id :rc.id]
                            [:rbac-role :rr] [:= :rr.id :rra.role-id]
                            [:rbac-role-permission :rrp] [:= :rrp.role-id :rr.id]
                            [:rbac-permission :rp] [:= :rp.id :rrp.permission-id]]
                   :where  [:and
                            [:= :rra.user-id user-id]
                            [:= :rp.name (kw->str permission-name)]
                            [:= :rra.context-id [:any applicable-contexts]]]}]]
                :select [[:sa.super-admin :super-admin]
                         [:hp.has-permission :has-permission]]
                :from   [[:super-admin :sa]]
                :join   [[:has-permission :hp] [:= :hp.user-id :sa.user-id]]})
        return-values (jdbc.sql/query db-spec query jdbc/unqualified-snake-kebab-opts)]
    (if (empty? return-values)
      false
      (let [{:keys [super-admin has-permission]} (first return-values)]
        (or super-admin has-permission)))))
