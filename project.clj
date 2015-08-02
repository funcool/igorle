(defproject funcool/igorle "0.1.0-SNAPSHOT"
  :description "A POSTAL client for ClojureScript"
  :url "http://github.com/funcool/igorle"
  :license {:name "BSD (2-Clause)"
            :url "http://opensource.org/licenses/BSD-2-Clause"}
  :source-paths ["src"]
  :jar-exclusions [#"\.swp|\.swo|user.clj"]
  :javac-options ["-target" "1.8" "-source" "1.8" "-Xlint:-options"]
  :dependencies [[org.clojure/clojure "1.7.0" :scope "provided"]
                 [org.clojure/clojurescript "1.7.28" :scope "provided"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [funcool/postal-parser "0.1.0-alpha1"]
                 [funcool/promesa "0.3.0"]
                 [funcool/cats "0.5.0"]
                 [funcool/cuerdas "0.5.0"]
                 [com.taoensso/timbre "4.0.2"]])
