(defproject funcool/igorle "0.1.0-SNAPSHOT"
  :description "A POSTAL client for ClojureScript"
  :url "http://github.com/funcool/igorle"
  :license {:name "BSD (2-Clause)"
            :url "http://opensource.org/licenses/BSD-2-Clause"}
  :source-paths ["src"]
  :test-paths ["test"]
  :jar-exclusions [#"\.swp|\.swo|user\.clj"]
  :javac-options ["-target" "1.8" "-source" "1.8" "-Xlint:-options"]
  :plugins [[lein-ancient "0.6.7"]]
  :dependencies [[org.clojure/clojure "1.7.0" :scope "provided"]
                 [org.clojure/clojurescript "1.7.48" :scope "provided"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 ;; Commented while development.
                 ;; [funcool/postal "0.2.0-SNAPSHOT"]
                 [funcool/promesa "0.5.0"]
                 [funcool/cats "1.0.0"]
                 [funcool/cuerdas "0.6.0"]

                 ;; Specific to POSTAL builtin
                 [org.clojure/tools.reader "0.10.0-alpha3"]])

