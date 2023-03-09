(ns superv.node-runner
  (:require [cljs.nodejs]
            [cljs.test]
            [superv.async-test]))

(defn run-tests
  ([] (run-tests nil))
  ([opts]
   (cljs.test/run-tests (merge (cljs.test/empty-env) opts)
                        'superv.async-test)))

(defn -main [& _args]
  (cljs.nodejs/enable-util-print!)
  (defmethod cljs.test/report [:cljs.test/default :end-run-tests] [_] (js/process.exit 0))
  (run-tests))

(set! *main-cli-fn* -main)