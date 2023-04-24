(defproject dev.gethop/rbac.next "0.1.0-alpha-3-SNAPSHOT"
  :description "A Clojure library designed to provide role-based access control (RBAC)"
  :url "https://github.com/gethop-dev/rbac"
  :license {:name "Mozilla Public Licence 2.0"
            :url "https://www.mozilla.org/en-US/MPL/2.0/"}
  :min-lein-version "2.9.8"
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [com.github.seancorfield/next.jdbc "1.3.874"]
                 [com.github.seancorfield/honeysql "2.4.1026"]]
  :deploy-repositories [["snapshots" {:url "https://clojars.org/repo"
                                      :username :env/clojars_username
                                      :password :env/clojars_password
                                      :sign-releases false}]
                        ["releases"  {:url "https://clojars.org/repo"
                                      :username :env/clojars_username
                                      :password :env/clojars_password
                                      :sign-releases false}]]
  :profiles
  {:dev [:project/dev :profiles/dev]
   :repl {:repl-options {:host "0.0.0.0"
                         :port 4001}}
   :profiles/dev {}
   :project/dev {:dependencies [[org.postgresql/postgresql "42.6.0"]]
                 :plugins [[jonase/eastwood "1.4.0"]
                           [lein-cljfmt "0.9.2"]]}})
