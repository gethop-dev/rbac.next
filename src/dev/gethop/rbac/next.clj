(ns dev.gethop.rbac.next
  (:require [clj-uuid :as clj-uuid]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as st]
            [clojure.string :as str]
            [honey.sql :as hsql]
            [next.jdbc :as jdbc]
            [next.jdbc.specs :as jdbc.specs]
            [next.jdbc.sql :as jdbc.sql]))

(declare instrument
         unstrument
         has-permission?)

(def fns-to-instrument
  "Functions in the namespace to instrument/unstrument."
  (-> (st/enumerate-namespace (ns-name *ns*))
      (disj (symbol #'has-permission?)
            (symbol #'instrument)
            (symbol #'unstrument))))

(defn instrument
  "Instrument functions in the namespace.

  This is a simple way to instrument the functions with specs in the
  namespace, so that their arguments are validated against their
  specs. `has-permission?` is excluded from the instrumentation, for
  performance purposes. That is the function that will more frequently
  used, and often in the performance critical paths."
  []
  (st/instrument fns-to-instrument))

(defn unstrument
  "Undo the work done by `instrument`."
  []
  (st/unstrument fns-to-instrument))

(def gen-primary-key-fn
  "Atom that contains the function used to generate the primary keys
  created by the library itself.

  The default function creates UUIDs.

  In case the user of the library needs to use a different data
  type (or UUID type) in the future, or in case the caller wants to
  use a different data type. E.g., sqlite3 and MySQL don't have native
  data types for it, and have to use work-arounds. The caller can set
  this atom to its own custom function, where such work-arounds are
  implemented."
  (atom clj-uuid/v7))

(defn set-primary-key-fn
  "Use `f` to generate the primary keys of the entities managed by the library"
  [f]
  (reset! gen-primary-key-fn f))

(defn- kw->str
  [k]
  (str (symbol k)))

(defn- str->kw
  [s]
  (keyword s))

(defn- get-*
  [db-spec table vals-kw]
  (try
    (let [query (hsql/format {:select [:*]
                              :from [table]})
          return-values (jdbc.sql/query db-spec query jdbc/unqualified-snake-kebab-opts)]
      {:success? true
       vals-kw return-values})
    (catch Exception _
      {:success? false})))

(defn- get-*-where-y
  [db-spec table conditions]
  (try
    (let [query (hsql/format {:select [:*]
                              :from [table]
                              :where conditions})
          return-values (jdbc.sql/query db-spec query jdbc/unqualified-snake-kebab-opts)]
      {:success? true
       :values return-values})
    (catch Exception _
      {:success? false})))

(defn- delete-where-x!
  [db-spec table conditions]
  (try
    (let [query (hsql/format {:delete-from table
                              :where conditions})
          _ (jdbc/execute-one! db-spec query)]
      {:success? true})
    (catch Exception _
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

(s/def ::db-spec ::jdbc.specs/proto-connectable)
(s/def ::id uuid?)
(s/def ::string-id (s/and string? (complement str/blank?)))
(s/def ::ids (s/coll-of ::id
                        :kind sequential?))
(s/def ::name keyword?)
(s/def ::names (s/coll-of ::name
                          :kind sequential?))
(s/def ::description string?)
(s/def ::role (s/keys :req-un [::id ::name]
                      :opt-un [::description]))
(s/def ::create-role (s/keys :req-un [::name]
                             :opt-un [::id ::description]))
(s/def ::success? boolean)
(s/def ::create-role!-args (s/cat :db-spec ::db-spec
                                  :role ::create-role))
(s/def ::create-role!-ret (s/keys :req-un [::success?]
                                  :opt-un [::role]))
(s/fdef create-role!
  :args ::create-role!-args
  :ret  ::create-role!-ret)

(defn create-role!
  "Create a `role`, in the database specified by `db-spec`.

  `db-spec` is a `:next.jdbc.specs/db-spec` compliant value.
  `role` is a map with the following keys and values:

   :name A mandatory key which contains a keyword with the role name.
   :description An optional key which contains a string with the
                description of the role.

  E.g.,
     {:name        :asset-manager
      :description \"Role used to manage the assets in the application\"}"
  [db-spec role]
  (try
    (let [role-id (@gen-primary-key-fn)
          db-role (-> role
                      (assoc :id role-id)
                      (role->db-role))]
      (jdbc.sql/insert! db-spec :rbac-role db-role
                        jdbc/unqualified-snake-kebab-opts)
      {:success? true
       :role (assoc role :id role-id)})
    (catch Exception _
      {:success? false})))

(s/def ::roles (s/coll-of ::role
                          :kind sequential?))
(s/def ::create-roles (s/coll-of ::create-role
                                 :kind sequential?))
(s/def ::create-roles!-args (s/cat :db-spec ::db-spec
                                   :roles ::create-roles))
(s/def ::create-roles!-ret (s/coll-of ::create-role!-ret
                                      :kind sequential?))
(s/fdef create-roles!
  :args ::create-roles!-args
  :ret  ::create-roles!-ret)

(defn create-roles!
  "Create a collection of `roles`, in the database specified by `db-spec`.

  `db-spec` is a `:next.jdbc.specs/db-spec` compliant value.
  `roles` is a collection of `role`, as specified in `create-role!`"
  [db-spec roles]
  (mapv #(create-role! db-spec %) roles))

(s/def ::get-roles-args (s/cat :db-spec ::db-spec))
(s/def ::get-roles-ret (s/keys :req-un [::success?]
                               :opt-un [::roles]))
(s/fdef get-roles
  :args ::get-roles-args
  :ret  ::get-roles-ret)

(defn get-roles
  "Get all role definitions, from the db using `db-spec` connection

  `db-spec` is a `:next.jdbc.specs/db-spec` compliant value."
  [db-spec]
  (let [result (get-* db-spec :rbac_role :roles)]
    (if-not (:success? result)
      {:success? false}
      (update result :roles #(mapv db-role->role %)))))

(defn- get-role-by-*
  [db-spec column value]
  (let [{:keys [success? values]} (get-*-where-y db-spec :rbac-role
                                                 [:= column value])]
    (if-not success?
      {:success? false}
      {:success? success?
       :role (db-role->role (first values))})))

(defn- get-roles-by-*
  [db-spec column value]
  (let [{:keys [success? values]} (get-*-where-y db-spec :rbac-role
                                                 [:= column [:any [:array value]]])]
    {:success? success?
     :roles (map db-role->role values)}))

(s/def ::get-role-by-id-args (s/cat :db-spec ::db-spec
                                    :role-id ::id))
(s/def ::get-role-by-id-ret (s/keys :req-un [::success?]
                                    :opt-un [::role]))
(s/fdef get-role-by-id
  :args ::get-role-by-id-args
  :ret  ::get-role-by-id-ret)

(defn get-role-by-id
  "Get the role whose id is `role-id`, from the db using `db-spec` connection

  `db-spec` is a `:next.jdbc.specs/db-spec` compliant value."
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
  "Get the role whose name is `name`, from the db using `db-spec` connection

  `db-spec` is a `:next.jdbc.specs/db-spec` compliant value."
  [db-spec name]
  (get-role-by-* db-spec :name (kw->str name)))

(s/def ::get-roles-by-names-args (s/cat :db-spec ::db-spec
                                        :names ::names))
(s/def ::get-roles-by-names-ret (s/keys :req-un [::success?]
                                        :opt-un [::roles]))
(s/fdef get-roles-by-names
  :args ::get-roles-by-names-args
  :ret  ::get-roles-by-names-ret)

(defn get-roles-by-names
  "Get the roles whose name is in `names`, from the db using `db-spec` connection

  `db-spec` is a `:next.jdbc.specs/db-spec` compliant value."
  [db-spec names]
  (get-roles-by-* db-spec :name (map kw->str names)))

(s/def ::update-role!-args (s/cat :db-spec ::db-spec
                                  :role ::role))
(s/def ::update-role!-ret (s/keys :req-un [::success?]
                                  :opt-un [::role]))
(s/fdef update-role!
  :args ::update-role!-args
  :ret  ::update-role!-ret)

(defn update-role!
  "Update `role` definition, in the db using `db-spec` connection

  `db-spec` is a `:next.jdbc.specs/db-spec` compliant value.
  `role` is a map, as returned by `create-role!`."
  [db-spec role]
  (try
    (let [result (jdbc.sql/update! db-spec
                                   :rbac-role
                                   (role->db-role role)
                                   ["id = ?" (:id role)]
                                   jdbc/unqualified-snake-kebab-opts)]
      (if (> (::jdbc/update-count result) 0)
        {:success? true
         :role role}
        {:success? false}))
    (catch Exception _
      {:success? false})))

(s/def ::update-roles!-args (s/cat :db-spec ::db-spec
                                   :roles ::roles))
(s/def ::update-roles!-ret (s/coll-of ::update-role!-ret
                                      :kind sequential?))
(s/fdef update-roles!
  :args ::update-roles!-args
  :ret  ::update-roles!-ret)

(defn update-roles!
  "Update `roles`, in the db using `db-spec` connection

  `db-spec` is a `:next.jdbc.specs/db-spec` compliant value.
  `roles` is a collection of `role`, as returned by `create-role!`."
  [db-spec roles]
  (mapv #(update-role! db-spec %) roles))

(s/def ::delete-role!-args (s/cat :db-spec ::db-spec
                                  :role ::role))
(s/def ::delete-role!-ret (s/keys :req-un [::success?]))
(s/fdef delete-role!
  :args ::delete-role!-args
  :ret  ::delete-role!-ret)

(defn delete-role!
  "Delete `role` definition, from the db using `db-spec` connection

  `db-spec` is a `:next.jdbc.specs/db-spec` compliant value.
  `role` is a map, as returned by `create-role!`."
  [db-spec role]
  (delete-where-x! db-spec :rbac-role [:= :id (:id role)]))

(s/def ::delete-role-by-id!-args (s/cat :db-spec ::db-spec
                                        :role-id ::id))
(s/def ::delete-role-by-id!-ret (s/keys :req-un [::success?]))
(s/fdef delete-role-by-id!
  :args ::delete-role-by-id!-args
  :ret  ::delete-role-by-id!-ret)

(defn delete-role-by-id!
  "Delete role whose id is `role-id`, from the db using `db-spec` connection

  `db-spec` is a `:next.jdbc.specs/db-spec` compliant value."
  [db-spec role-id]
  (delete-where-x! db-spec :rbac-role [:= :id role-id]))

(s/def ::delete-role-by-name!-args (s/cat :db-spec ::db-spec
                                          :name ::name))
(s/def ::delete-role-by-name!-ret (s/keys :req-un [::success?]))
(s/fdef delete-role-by-name!
  :args ::delete-role-by-name!-args
  :ret  ::delete-role-by-name!-ret)

(defn delete-role-by-name!
  "Delete role whose name is `name`, from the db using `db-spec` connection

  `db-spec` is a `:next.jdbc.specs/db-spec` compliant value."
  [db-spec name]
  (delete-where-x! db-spec :rbac-role [:= :name (kw->str name)]))

(s/def ::delete-roles!-args (s/cat :db-spec ::db-spec
                                   :roles ::roles))
(s/def ::delete-roles!-ret (s/keys :req-un [::success?]))
(s/fdef delete-roles!
  :args ::delete-roles!-args
  :ret  ::delete-roles!-ret)

(defn delete-roles!
  "Delete `roles` definitions, from the db using `db-spec` connection

  `db-spec` is a `:next.jdbc.specs/db-spec` compliant value.
  `roles` is a collection of maps, as specified in
  `create-role!`. Except in this case, the role `id` is mandatory."
  [db-spec roles]
  (mapv #(delete-role-by-name! db-spec (:id %)) roles))

(s/def ::delete-roles-by-ids!-args (s/cat :db-spec ::db-spec
                                          :role-ids ::ids))
(s/def ::delete-roles-by-ids!-ret (s/keys :req-un [::success?]))
(s/fdef delete-roles-by-ids!
  :args ::delete-roles-by-ids!-args
  :ret  ::delete-roles-by-ids!-ret)

(defn delete-roles-by-ids!
  "Delete roles whose ids are in `role-ids`, from the db using `db-spec` connection

  `db-spec` is a `:next.jdbc.specs/db-spec` compliant value."
  [db-spec role-ids]
  (mapv #(delete-role-by-id! db-spec %) role-ids))

(s/def ::delete-roles-by-names!-args (s/cat :db-spec ::db-spec
                                            :names ::names))
(s/def ::delete-roles-by-names!-ret (s/keys :req-un [::success?]))
(s/fdef delete-roles-by-names!
  :args ::delete-roles-by-names!-args
  :ret  ::delete-roles-by-names!-ret)

(defn delete-roles-by-names!
  "Delete roles whose names are in `names`, from the db using `db-spec` connection

  `db-spec` is a `:next.jdbc.specs/db-spec` compliant value."
  [db-spec names]
  (mapv #(delete-role-by-name! db-spec %) names))

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
  "Create a `context-type`, in the database specified by `db-spec`.

  `db-spec` is a `:next.jdbc.specs/db-spec` compliant value.
  `context-type` is a map with the following keys and values:

   :name A mandatory key which contains a keyword with the context
         type name.
   :description An optional key which contains a string with the
                description of the context type.

  E.g.,
     {:name        :some-context-type
      :description \"Context type description\"}"
  [db-spec context-type]
  (try
    (let [db-context-type (context-type->db-context-type context-type)]
      (jdbc.sql/insert! db-spec :rbac-context-type db-context-type
                        jdbc/unqualified-snake-kebab-opts)
      {:success? true
       :context-type context-type})
    (catch Exception _
      {:success? false})))

(s/def ::context-types (s/coll-of ::context-type
                                  :kind sequential?))
(s/def ::create-context-types!-args (s/cat :db-spec ::db-spec
                                           :context-types ::context-types))
(s/def ::create-context-types!-ret (s/coll-of ::create-context-type!-ret
                                              :kind sequential?))
(s/fdef create-context-types!
  :args ::create-context-types!-args
  :ret  ::create-context-types!-ret)

(defn create-context-types!
  "Create a collection of `context-type`, in the database specified by `db-spec`.

  `db-spec` is a `:next.jdbc.specs/db-spec` compliant value.
  `context-types` is a collection of `context-type` values, as defined
                in `create-context-type!`."
  [db-spec context-types]
  (mapv #(create-context-type! db-spec %) context-types))

(s/def ::get-context-types-args (s/cat :db-spec ::db-spec))
(s/def ::get-context-types-ret (s/keys :req-un [::success?]
                                       :opt-un [::context-types]))
(s/fdef get-context-types
  :args ::get-context-types-args
  :ret  ::get-context-types-ret)

(defn get-context-types
  "Get all context type definitions, from the db using `db-spec` connection

  `db-spec` is a `:next.jdbc.specs/db-spec` compliant value."
  [db-spec]
  (let [result (get-* db-spec :rbac_context_type :context-types)]
    (if-not (:success? result)
      {:success? false}
      (update result :context-types #(mapv db-context-type->context-type %)))))

(s/def ::get-context-type-args (s/cat :db-spec ::db-spec
                                      :context-type-name ::context-type-name))
(s/def ::get-context-type-ret (s/keys :req-un [::success? ::context-type]))
(s/fdef get-context-type
  :args ::get-context-type-args
  :ret  ::get-context-type-ret)

(defn get-context-type
  "Get the context type whose name is `name`, from the db using `db-spec` connection

  `db-spec` is a `:next.jdbc.specs/db-spec` compliant value."
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
  "Update `context-type` definition, in the db using `db-spec` connection

  `db-spec` is a `:next.jdbc.specs/db-spec` compliant value.
  `context-type` is a map, as returned by `create-context-type!`."
  [db-spec context-type]
  (try
    (let [db-context-type (context-type->db-context-type context-type)
          result (jdbc.sql/update! db-spec
                                   :rbac-context-type
                                   db-context-type
                                   ["name = ?" (:name db-context-type)]
                                   jdbc/unqualified-snake-kebab-opts)]
      (if (> (::jdbc/update-count result) 0)
        {:success? true
         :context-type context-type}
        {:success? false}))
    (catch Exception _
      {:success? false})))

(s/def ::update-context-types!-args (s/cat :db-spec ::db-spec
                                           :context-types ::context-types))
(s/def ::update-context-types!-ret (s/keys :req-un [::success?]
                                           :opt-un [::context-types]))
(s/fdef update-context-types!
  :args ::update-context-types!-args
  :ret  ::update-context-types!-ret)

(defn update-context-types!
  "Update `context-types`, in the db using `db-spec` connection

  `db-spec` is a `:next.jdbc.specs/db-spec` compliant value.
  `context-types` is a collection of `context-type`, as returned by
  `create-context-type!`."
  [db-spec context-types]
  (mapv #(update-context-type! db-spec %) context-types))

(s/def ::delete-context-type!-args (s/cat :db-spec ::db-spec
                                          :context-type ::context-type))
(s/def ::delete-context-type!-ret (s/keys :req-un [::success?]))
(s/fdef delete-context-type!
  :args ::delete-context-type!-args
  :ret  ::delete-context-type!-ret)

(defn delete-context-type!
  "Delete `context-type`, from the db using `db-spec` connection

  `db-spec` is a `:next.jdbc.specs/db-spec` compliant value.
  `context-type` is a map, as returned by `create-context-type!`."
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
  "Delete `context-types`, from the db using `db-spec` connection

  `db-spec` is a `:next.jdbc.specs/db-spec` compliant value.
  `context-types` is a collection of `context-type`, as returned by
  `create-context-type!`."
  [db-spec context-types]
  (mapv #(delete-context-type! db-spec %) context-types))

;; -----------------------------------------------------------
(defn- context->db-context
  [context]
  (-> context
      (update :context-type-name kw->str)))

(defn- db-context->context
  [db-context]
  (-> db-context
      (update-if-exists :context-type-name str->kw)))

(s/def ::resource-id (s/or :string-id ::string-id
                           :int-id pos-int?
                           :uuid uuid?))
(s/def ::new-context (s/keys :req-un [::context-type-name
                                      ::resource-id]))
(s/def ::context (s/keys :req-un [::id
                                  ::context-type-name
                                  ::resource-id]))
(s/def ::contexts (s/coll-of ::context
                             :kind sequential?))
(s/def ::create-context!-args (s/cat :db-spec ::db-spec
                                     :context ::new-context
                                     :parent-contexts ::contexts))
(s/def ::create-context!-ret (s/keys :req-un [::success?]
                                     :opt-un [::context]))
(s/fdef create-context!
  :args ::create-context!-args
  :ret  ::create-context!-ret)

(defn create-context!
  "Create a `context`, in the database specified by `db-spec`.

  `db-spec` is a `:next.jdbc.specs/db-spec` compliant value.

  `context` is a map with the following keys and values:
     `:resource-id` The application id that identifies the resource
                    for which the context is created.
     `:context-type-name` The name (as a keyword) of the context type to
                          use for this context. It must be a valid
                          context-type-name previously created using
                          `create-context-type!`
     E.g.,
       {:resource-id #uuid \"28b81079-a2b7-419d-92b1-7249bc326ea1\"
        :context-type-name :device}

  `parent-contexts` is a sequential collection of already existing
  contexts, that will be set as the parents for `context`. To be able
  to create a top-level context, pass an empty collection."
  [db-spec context parent-contexts]
  (let [context-id (@gen-primary-key-fn)
        db-context (-> context
                       (assoc :id context-id)
                       (context->db-context))
        parent-ids (mapv :id parent-contexts)
        success-result {:success? true
                        :context (assoc context :id context-id)}]
    (try
      (jdbc/with-transaction [tx db-spec]
        (jdbc.sql/insert! tx :rbac-context db-context
                          jdbc/unqualified-snake-kebab-opts)
        (if-not (seq parent-ids)
          success-result
          (let [rows (mapv (fn [child-id parent-id]
                             {:child-id child-id
                              :parent-id parent-id})
                           (repeat context-id)
                           parent-ids)
                jdbc-opts (assoc jdbc/unqualified-snake-kebab-opts :batch true)
                result (jdbc.sql/insert-multi! tx :rbac-context-parent
                                               rows jdbc-opts)]
            (if (and (seq rows) (not (seq result)))
              (do
                (.rollback tx)
                {:success? false})
              success-result))))
      (catch Exception _
        {:success? false}))))

;; Work-around to use a given spec with a map key name that doesn't
;; match the spec name. E.g., the spec name that we want to use is
;; called `::new-context`, but we want to use the key name ::context
;; for the map that we are specifying. See
;; https://stackoverflow.com/a/43873866.
(s/def :dev.gethop.rbac.next.create-contexts!/context ::new-context)
(s/def :dev.gethop.rbac.next.create-contexts!/parent-contexts ::contexts)
(s/def ::context-parents-pair (s/keys :req-un [:dev.gethop.rbac.next.create-contexts!/context
                                               :dev.gethop.rbac.next.create-contexts!/parent-contexts]))
(s/def ::context-parents-pairs (s/coll-of ::context-parents-pair
                                          :kind sequential?))
(s/def ::create-contexts!-args (s/cat :db-spec ::db-spec
                                      :context-parents-pairs ::context-parents-pairs))
(s/def ::create-contexts!-ret (s/keys :req-un [::success?]
                                      :opt-un [::contexts]))
(s/fdef create-contexts!
  :args ::create-contexts!-args
  :ret  ::create-contexts!-ret)

(defn create-contexts!
  "Create several `context`s, in the database specified by `db-spec`.

  `db-spec` is a `:next.jdbc.specs/db-spec` compliant value.

  `context-parents-pairs` is a sequential collection of maps, where
  each map must have the following keys and values:

    - `:context` A map with the following keys and values:
         - `:resource-id` The application id that identifies the resource
                        for which the context is created.
         - `:context-type-name` The name of the context type to use for this
                              context. It must be a valid context-type-name
                              previously created using `create-context-type!`
         E.g.,
           {:resource-id #uuid \"28b81079-a2b7-419d-92b1-7249bc326ea1\"
            :context-type-name :device}

    - `:parent-contexts` A sequential collection of already existing contexts,
      that will be set as the parents for `context`. To be able to create a
      top-level context, pass an empty collection."
  [db-spec context-parents-pairs]
  (let [data (reduce (fn [acc {:keys [context parent-contexts]}]
                       (let [context-id (@gen-primary-key-fn)
                             context (assoc context :id context-id)
                             parent-ids (mapv :id parent-contexts)
                             child-parents (mapv (fn [child-id parent-id]
                                                   {:child-id child-id
                                                    :parent-id parent-id})
                                                 (repeat context-id)
                                                 parent-ids)]
                         (-> acc
                             (update :contexts conj context)
                             (update :db-contexts conj (context->db-context context))
                             (update :db-child-parents into child-parents))))
                     {:contexts []
                      :db-contexts []
                      :db-child-parents []}
                     context-parents-pairs)
        success-result {:success? true
                        :contexts (:contexts data)}]
    (try
      (jdbc/with-transaction [tx db-spec]
        (jdbc.sql/insert-multi! tx :rbac-context
                                (:db-contexts data)
                                (assoc jdbc/unqualified-snake-kebab-opts :batch true))
        (if (empty? (:db-child-parents data))
          success-result
          (let [result (jdbc.sql/insert-multi! tx :rbac-context-parent
                                               (:db-child-parents data)
                                               (assoc jdbc/unqualified-snake-kebab-opts :batch true))]
            (if-not (= (count (:db-child-parents data))
                       (count result))
              (do
                (.rollback tx)
                {:success? false})
              success-result))))
      (catch Exception _
        {:success? false}))))

(s/def ::get-contexts-args (s/cat :db-spec ::db-spec))
(s/def ::get-contexts-ret (s/keys :req-un [::success?
                                           ::contexts]))
(s/fdef get-contexts
  :args ::get-contexts-args
  :ret  ::get-contexts-ret)

(defn get-contexts
  [db-spec]
  (let [result (get-* db-spec :rbac_context :contexts)]
    (if-not (:success? result)
      {:success? false}
      (update result :contexts #(mapv db-context->context %)))))

(s/def ::context-selector (s/keys :req-un [::context-type-name
                                           ::resource-id]))
(s/def ::context-selectors (s/coll-of ::context-selector))
(s/def ::get-contexts-by-selectors-args (s/cat :db-spec ::db-spec :context-selectors ::context-selectors))
(s/def ::get-contexts-by-selectors-ret (s/keys :req-un [::success?
                                                        ::contexts]))
(s/fdef get-contexts-by-selectors
  :args ::get-contexts-by-selectors-args
  :ret  ::get-contexts-by-selectors-ret)

(defn get-contexts-by-selectors
  [db-spec context-selectors]
  (let [{:keys [success? values]}
        (get-*-where-y db-spec :rbac-context
                       (reduce
                        (fn [condition {:keys [context-type-name resource-id]}]
                          (conj condition [:and
                                           [:= :context-type-name (kw->str context-type-name)]
                                           [:= :resource-id resource-id]]))
                        [:or]
                        context-selectors))]
    {:success? success?
     :contexts (map db-context->context values)}))

(s/def ::get-context-args (s/cat :db-spec ::db-spec
                                 :context-type-name ::context-type-name
                                 :resource-id ::resource-id))
(s/def ::get-context-ret (s/keys :req-un [::success?]
                                 :opt-un [::context]))
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
    (if-not success?
      {:success? false}
      {:success? success?
       :context (db-context->context (first values))})))

(s/def ::update-context!-args (s/cat :db-spec ::db-spec
                                     :context ::context))
(s/def ::update-context!-ret (s/keys :req-un [::success?
                                              ::context]))
(s/fdef update-context!
  :args ::update-context!-args
  :ret  ::update-context!-ret)

(defn update-context!
  [db-spec context]
  (try
    (let [result (jdbc.sql/update! db-spec
                                   :rbac-context
                                   (context->db-context context)
                                   ["id = ?" (:id context)]
                                   jdbc/unqualified-snake-kebab-opts)]
      (if (> (::jdbc/update-count result) 0)
        {:success? true
         :context context}
        {:success? false}))
    (catch Exception _
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
  (mapv #(update-context! db-spec %) contexts))

(s/def ::delete-context!-args (s/cat :db-spec ::db-spec
                                     :context ::context))
(s/def ::delete-context!-ret (s/keys :req-un [::success?]))
(s/fdef delete-context!
  :args ::delete-context!-args
  :ret  ::delete-context!-ret)

(defn delete-context!
  "Delete `context`, from the db using `db-spec` connection

  `db-spec` is a `:next.jdbc.specs/db-spec` compliant value.
  `context` is a map as returned by `create-context!`."
  [db-spec context]
  (let [where-cond (if (:id context)
                     [:= :id (:id context)]
                     [:and
                      [:= :context-type-name (kw->str (:context-type-name context))]
                      [:= :resource-id (:resource-id context)]])]
    (delete-where-x! db-spec :rbac-context where-cond)))

(s/def ::delete-context-by-id!-args (s/cat :db-spec ::db-spec
                                           :context-id ::id))
(s/def ::delete-context-by-id!-ret (s/keys :req-un [::success?]))
(s/fdef delete-context-by-id!
  :args ::delete-context-by-id!-args
  :ret  ::delete-context-by-id!-ret)

(defn delete-context-by-id!
  "Delete context whose id is `context-id`, from the db using `db-spec` connection

  `db-spec` is a `:next.jdbc.specs/db-spec` compliant value."
  [db-spec context-id]
  (delete-where-x! db-spec :rbac-context [:= :id context-id]))

(s/def ::delete-contexts!-args (s/cat :db-spec ::db-spec
                                      :contexts ::contexts))
(s/def ::delete-contexts!-ret (s/keys :req-un [::success?]))
(s/fdef delete-contexts!
  :args ::delete-contexts!-args
  :ret  ::delete-contexts!-ret)

(defn delete-contexts!
  "FIXME FIXME FIXME FIXME"
  [db-spec contexts]
  (mapv #(delete-context! db-spec %) contexts))

(s/def ::delete-contexts-by-ids!-args (s/cat :db-spec ::db-spec
                                             :context-ids ::ids))
(s/def ::delete-contexts-by-ids!-ret (s/keys :req-un [::success?]))
(s/fdef delete-contexts-by-ids!
  :args ::delete-contexts-by-ids!-args
  :ret  ::delete-contexts-by-ids!-ret)

(s/def ::delete-contexts!-args (s/cat :db-spec ::db-spec
                                      :contexts ::contexts))
(s/def ::delete-contexts!-ret (s/keys :req-un [::success?]))
(s/fdef delete-contexts!
  :args ::delete-contexts!-args
  :ret  ::delete-contexts!-ret)

(defn delete-contexts-by-ids!
  "Delete contexts whose ids are in `context-ids`, from the db using `db-spec` connection

  `db-spec` is a `:next.jdbc.specs/db-spec` compliant value."
  [db-spec context-ids]
  (mapv #(delete-context-by-id! db-spec %) context-ids))

(defn- add-parent-child-context-rows!
  [db-spec rows]
  (try
    (let [query {:insert-into :rbac-context-parent
                 :values rows
                 :on-conflict [:child-id :parent-id]
                 :do-nothing true}]
      (jdbc/execute-one! db-spec (hsql/format query)
                         (assoc jdbc/unqualified-snake-kebab-opts
                                :batch true))
      {:success? true})
    (catch Exception _
      {:success? false})))

(defn- remove-parent-child-context-rows!
  [db-spec where-cond]
  (try
    (let [query {:delete-from [:rbac-context-parent]
                 :where where-cond}]
      (jdbc/execute-one! db-spec (hsql/format query)
                         jdbc/unqualified-snake-kebab-opts)
      {:success? true})
    (catch Exception _
      {:success? false})))

(s/def ::add-parent-contexts!-args (s/cat :db-spec ::db-spec
                                          :context ::context
                                          :parent-contexts ::contexts))
(s/def ::add-parent-contexts!-ret (s/keys :req-un [::success?]))
(s/fdef add-parent-contexts!
  :args ::add-parent-contexts!-args
  :ret  ::add-parent-contexts!-ret)

(defn add-parent-contexts!
  [db-spec context parent-contexts]
  (if-not (seq parent-contexts)
    {:success? true}
    (let [parent-ids (mapv :id parent-contexts)
          rows (mapv (fn [child-id parent-id]
                       {:child-id child-id
                        :parent-id parent-id})
                     (repeat (:id context))
                     parent-ids)]
      (add-parent-child-context-rows! db-spec rows))))

(s/def ::remove-parent-contexts!-args (s/cat :db-spec ::db-spec
                                             :context ::context
                                             :parent-contexts ::contexts))
(s/def ::remove-parent-contexts!-ret (s/keys :req-un [::success?]))
(s/fdef remove-parent-contexts!
  :args ::remove-parent-contexts!-args
  :ret  ::remove-parent-contexts!-ret)

(defn remove-parent-contexts!
  [db-spec context parent-contexts]
  (if-not (seq parent-contexts)
    {:success? true}
    (let [child-ids (mapv :id parent-contexts)
          where-cond [:and
                      [:= :child-id (:id context)]
                      (case (count parent-contexts)
                        1 [:= :parent-id (-> parent-contexts first :id)]
                        [:= :parent-id [:any [:array child-ids]]])]]
      (remove-parent-child-context-rows! db-spec where-cond))))

(s/def ::add-child-contexts!-args (s/cat :db-spec ::db-spec
                                         :context ::context
                                         :child-contexts ::contexts))
(s/def ::add-child-contexts!-ret (s/keys :req-un [::success?]))
(s/fdef add-child-contexts!
  :args ::add-child-contexts!-args
  :ret  ::add-child-contexts!-ret)

(defn add-child-contexts!
  "FIXME FIXME FIXME FIXME"
  [db-spec context child-contexts]
  (if-not (seq child-contexts)
    {:success? true}
    (let [child-ids (mapv :id child-contexts)
          rows (mapv (fn [parent-id child-id]
                       {:child-id child-id
                        :parent-id parent-id})
                     (repeat (:id context))
                     child-ids)]
      (add-parent-child-context-rows! db-spec rows))))

(s/def ::remove-child-contexts!-args (s/cat :db-spec ::db-spec
                                            :context ::context
                                            :child-contexts ::contexts))
(s/def ::remove-child-contexts!-ret (s/keys :req-un [::success?]))
(s/fdef remove-child-contexts!
  :args ::remove-child-contexts!-args
  :ret  ::remove-child-contexts!-ret)

(defn remove-child-contexts!
  "FIXME FIXME FIXME FIXME"
  [db-spec context child-contexts]
  (if-not (seq child-contexts)
    {:success? true}
    (let [child-ids (mapv :id child-contexts)
          where-cond [:and
                      [:= :parent-id (:id context)]
                      (case (count child-contexts)
                        1 [:= :child-id (-> child-contexts first :id)]
                        [:= :child-id [:any [:array child-ids]]])]]
      (remove-parent-child-context-rows! db-spec where-cond))))

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
  (try
    (let [permission-id (@gen-primary-key-fn)
          db-permission (-> permission
                            (assoc :id permission-id)
                            perm->db-perm)]
      (jdbc.sql/insert! db-spec :rbac-permission db-permission
                        jdbc/unqualified-snake-kebab-opts)
      {:success? true
       :permission (assoc permission :id permission-id)})
    (catch Exception _
      {:success? false})))

(s/def ::permissions (s/coll-of ::permission
                                :kind sequential?))
(s/def ::create-permissions!-args (s/cat :db-spec ::db-spec
                                         :permissions ::permissions))
(s/def ::create-permissions!-ret (s/keys :req-un [::success?]
                                         :opt-un [::permissions]))
(s/fdef create-permissions!
  :args ::create-permissions!-args
  :ret  ::create-permissions!-ret)

(defn create-permissions!
  [db-spec permissions]
  (mapv #(create-permission! db-spec %) permissions))

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
      (update result :permissions #(mapv db-perm->perm %)))))

(defn- get-permission-by-*
  [db-spec column value]
  (let [{:keys [success? values]} (get-*-where-y db-spec :rbac_permission
                                                 [:= column value])]
    (if-not success?
      {:success? false}
      {:success? success?
       :permission (db-perm->perm (first values))})))

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
  (try
    (let [result (jdbc.sql/update! db-spec
                                   :rbac-permission
                                   (perm->db-perm permission)
                                   ["id = ?" (:id permission)]
                                   jdbc/unqualified-snake-kebab-opts)]
      (if (> (::jdbc/update-count result) 0)
        {:success? true
         :permission permission}
        {:success? false}))
    (catch Exception _
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
  (mapv #(update-permission! db-spec %) permissions))

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
  (mapv #(delete-permission! db-spec %) permissions))

(s/def ::delete-permissions-by-ids!-args (s/cat :db-spec ::db-spec
                                                :ids ::ids))
(s/def ::delete-permissions-by-ids!-ret (s/keys :req-un [::success?]))
(s/fdef delete-permission-by-idss!
  :args ::delete-permissions-by-ids!-args
  :ret  ::delete-permissions-by-ids!-ret)

(defn delete-permissions-by-ids!
  [db-spec ids]
  (mapv #(delete-permission-by-id! db-spec %) ids))

(s/def ::delete-permission-by-names!-args (s/cat :db-spec ::db-spec
                                                 :names ::names))
(s/def ::delete-permission-by-names!-ret (s/keys :req-un [::success?]))
(s/fdef delete-permission-by-names!
  :args ::delete-permission-by-names!-args
  :ret  ::delete-permission-by-names!-ret)

(defn delete-permissions-by-names!
  [db-spec names]
  (mapv #(delete-permission-by-name! db-spec %) names))

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
  (try
    (jdbc.sql/insert! db-spec :rbac-super-admin {:user-id user-id}
                      jdbc/unqualified-snake-kebab-opts)
    {:success? true}
    (catch Exception _
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
(s/def ::permission-value #{::permission-granted ::permission-denied})

(defn- set-perm-with-value!
  [db-spec role permission permission-value]
  (let [perm-val (case permission-value
                   ::permission-granted 1
                   ::permission-denied -1)
        role-permission {:role-id (:id role)
                         :permission-id (:id permission)
                         :permission-value perm-val}]
    ;; Because we don't know which database engine is being used, we
    ;; can't rely on any specific "UPSERT" syntax (different db
    ;; engines use different syntax for it). We need to fall back to
    ;; simulate it using database transactions.
    (try
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
            :else
            (do
              (.rollback tx)
              {:success? false}))))
      (catch Exception _
        {:success? false}))))

(s/def ::grant-role-permission!-args (s/cat :db-spec ::db-spec
                                            :role ::role
                                            :permission ::permission))
(s/def ::grant-role-permission!-ret (s/keys :req-un [::success?]))
(s/fdef grant-role-permission!
  :args ::grant-role-permission!-args
  :ret  ::grant-role-permission!-ret)

(defn grant-role-permission!
  [db-spec role permission]
  (set-perm-with-value! db-spec role permission ::permission-granted))

(s/def ::grant-role-permissions!-args (s/cat :db-spec ::db-spec
                                             :role ::role
                                             :permissions ::permissions))
(s/def ::grant-role-permissions!-ret (s/keys :req-un [::success?]))
(s/fdef grant-role-permissions!
  :args ::grant-role-permissions!-args
  :ret  ::grant-role-permissions!-ret)

(defn grant-role-permissions!
  [db-spec role permissions]
  (mapv #(set-perm-with-value! db-spec role % ::permission-granted) permissions))

(s/def ::deny-role-permission!-args (s/cat :db-spec ::db-spec
                                           :role ::role
                                           :permission ::permission))
(s/def ::deny-role-permission!-ret (s/keys :req-un [::success?]))
(s/fdef deny-role-permission!
  :args ::deny-role-permission!-args
  :ret  ::deny-role-permission!-ret)

(defn deny-role-permission!
  [db-spec role permission]
  (set-perm-with-value! db-spec role permission ::permission-denied))

(s/def ::deny-role-permissions!-args (s/cat :db-spec ::db-spec
                                            :role ::role
                                            :permissions ::permissions))
(s/def ::deny-role-permissions!-ret (s/keys :req-un [::success?]))
(s/fdef deny-role-permissions!
  :args ::deny-role-permissions!-args
  :ret  ::deny-role-permissions!-ret)

(defn deny-role-permissions!
  [db-spec role permissions]
  (mapv #(set-perm-with-value! db-spec role % ::permission-denied) permissions))

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
  (mapv #(remove-role-permission! db-spec role %) permissions))

;; -----------------------------------------------------------

(defn- db-role-assignment->role-assignment
  [{:keys [role-id role-name role-description
           context-id context-resource-id context-type-name
           assignment-user-id]}]
  {:role {:id role-id
          :name (str->kw role-name)
          :description role-description}
   :context {:id context-id
             :resource-id context-resource-id
             :context-type-name (str->kw context-type-name)}
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
  (try
    (let [role-assignement {:role-id (:id role)
                            :context-id (:id context)
                            :user-id (:id user)}]
      (jdbc.sql/insert! db-spec :rbac-role-assignment role-assignement
                        jdbc/unqualified-snake-kebab-opts)
      {:success? true})
    (catch Exception _
      {:success? false})))

(s/def ::role-assignments (s/coll-of ::role-assignment
                                     :kind sequential?))
(s/def ::assign-roles!-args (s/cat :db-spec ::db-spec
                                   :role-assignments ::role-assignments))
(s/def ::assign-roles!-ret (s/coll-of (s/keys :req-un [::success?])
                                      :kind sequential?))
(s/fdef assign-roles!
  :args ::assign-roles!-args
  :ret  ::assign-roles!-ret)

(defn assign-roles!
  [db-spec role-assignments]
  (mapv #(assign-role! db-spec %) role-assignments))

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
  (mapv #(unassign-role! db-spec %) unassignments))

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
      :role-assignments (mapv db-role-assignment->role-assignment return-values)})))

;; -----------------------------------------------------------
(s/def ::has-permission?-args (s/cat :db-spec ::db-spec
                                     :user-id ::user-id
                                     :resource-id ::resource-id
                                     :context-type-name ::context-type-name
                                     :permission-name ::permission-name))
(s/def ::has-permission?-ret boolean?)
(s/fdef has-permission?
  :args ::has-permission?-args
  :ret  ::has-permission?-ret)

(defn has-permission?
  [db-spec user-id resource-id context-type-name permission-name]
  (let [query (hsql/format
               {:with-recursive
                [[[:ancestor-of-context {:columns [:id]}]
                  {:union
                   [{:select [:id]
                     :from [:rbac-context]
                     :where [:and
                             [:= :resource-id resource-id]
                             [:= :context-type-name (kw->str context-type-name)]]}
                    {:select [:rcp.parent-id]
                     :from [[:ancestor-of-context :aoc]]
                     :inner-join [[:rbac-context-parent :rcp] [:= :rcp.child-id :aoc.id]]}]}]
                 [:applicable-contexts
                  {:select [:id]
                   :from [:rbac-context]
                   :join [[:ancestor-of-context]
                          [:using :id]]}]
                 [:super-admin
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
                            [:= :rra.context-id [:any {:select [:id]
                                                       :from [:applicable-contexts]}]]]}]]
                :select [[[:or :sa.super-admin :hp.has-permission] :has-permission]]
                :from   [[:super-admin :sa]]
                :join   [[:has-permission :hp] [:= :hp.user-id :sa.user-id]]})
        return-values (jdbc.sql/query db-spec query jdbc/unqualified-snake-kebab-opts)]
    (if (empty? return-values)
      false
      (-> (first return-values) :has-permission))))
