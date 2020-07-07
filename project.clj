(defproject io.replikativ/superv.async "0.2.9-SNAPSHOT"
  :description "Supervised channel management for core.async."

  :url "https://github.com/replikativ/superv.async"

  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo}

  :deploy-repositories [["releases" {:url "https://clojars.org/repo/" :creds :gpg}]]

  :dependencies [[org.clojure/clojure "1.10.1" :scope "provided"]
                 [org.clojure/clojurescript "1.10.773" :scope "provided"]
                 [org.clojure/core.async "1.2.603"]]

  :target-path "target/%s"

  :plugins [[lein-cljsbuild "1.1.7"]]

  :aliases {"all" ["with-profile" "default:+1.7:+1.8"]}
  :profiles {:dev {:source-paths ["src" "dev"]
                   :dependencies [[org.clojure/tools.nrepl     "0.2.13"]
                                  [org.clojure/tools.namespace "0.2.11"]
                                  [lambdaisland/kaocha         "0.0-389"]
                                  [lambdaisland/kaocha-cljs    "0.0-21"]]}}

  :cljsbuild
  {:builds [{:id "cljs_repl"
             :source-paths ["src"]
             :figwheel true
             :compiler
             {:main superv.async
              :asset-path "js/out"
              :output-to "resources/public/js/client.js"
              :output-dir "resources/public/js/out"
              :optimizations :none
              :pretty-print true}}]})
