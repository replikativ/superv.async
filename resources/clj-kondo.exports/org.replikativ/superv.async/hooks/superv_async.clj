(ns hooks.superv-async
  "clj-kondo hooks for the macros that take a supervisor before a binding form.

   `(go-loop-try S [i 0] …)` puts the supervisor where `loop` expects its
   bindings, so without help clj-kondo cannot see `i` and reports every loop
   variable as unresolved and every `recur` as the wrong arity. Each hook
   rewrites the call into the ordinary form plus the supervisor as an
   expression, which is what the macro expands to anyway."
  (:require [clj-kondo.hooks-api :as api]))

(defn- supervised
  "`(macro S rest…)` as `(do S (target rest…))`."
  [target {:keys [node]}]
  (let [[_ supervisor & more] (:children node)]
    (if (nil? supervisor)
      {:node node}
      {:node (api/list-node
              (list (api/token-node 'do)
                    supervisor
                    (api/list-node (list* (api/token-node target) more))))})))

(defn go-loop [ctx] (supervised 'clojure.core/loop ctx))
(defn go-for [ctx] (supervised 'clojure.core/for ctx))
