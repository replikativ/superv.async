(defproject io.replikativ/superv.async "0.2.4"
  :description "Supervised channel management for core.async."

  :url "https://github.com/replikativ/superv.async"

  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo}

  :deploy-repositories [["releases" {:url "https://clojars.org/repo/" :creds :gpg}]]

  :dependencies [[org.clojure/clojure "1.9.0-alpha14" :scope "provided"]
                 [org.clojure/clojurescript "1.9.293" :scope "provided"]
                 [org.clojure/core.async "0.2.391"]]

  :plugins [[lein-cljsbuild "1.1.4"]]

  :aliases {"all" ["with-profile" "default:+1.7:+1.8"]}
  :profiles {:dev {:dependencies [[binaryage/devtools "0.8.2"]
                                  [figwheel-sidecar "0.5.8"]
                                  [com.cemerick/piggieback "0.2.1"]]
                   :source-paths ["src" "dev"]
                   :plugins [[lein-figwheel "0.5.8"]]
                   :repl-options {; for nREPL dev you really need to limit output
                                  :init (set! *print-length* 50)
                                  :nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}}

             :1.7 {:dependencies [[org.clojure/clojure "1.7.0"]
                                  [org.clojure/clojurescript "1.7.228"]]}
             :1.8 {:dependencies [[org.clojure/clojure "1.8.0"]
                                  [org.clojure/clojurescript "1.8.51"]]}}

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
