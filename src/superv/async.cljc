(ns superv.async
  #?(:clj (:gen-class :main true))
  (:require #?(:clj [clojure.core.async :refer [<! <!! >! >!! alt! alt!!
                                                alts! alts!! go go-loop
                                                chan thread timeout put! pub sub close!
                                                take!]
                     :as async]
               :cljs [cljs.core.async :refer [<! >! alts! chan timeout put! pub sub close!
                                              take!]
                      :as async])
            #?(:cljs (cljs.core.async.impl.protocols :refer [ReadPort])))
  #?(:cljs (:require-macros [superv.async :refer [wrap-abort! >? <? go-try go-loop-try]]
                            [cljs.core.async.macros :refer [go go-loop alt!]]))
  #?(:clj (:import (clojure.core.async.impl.protocols ReadPort))))


(defn- cljs-env?
  "Take the &env from a macro, and tell whether we are expanding into cljs."
  [env]
  (boolean (:ns env)))

#?(:clj
   (defmacro if-cljs
     "Return then if we are generating cljs code and else for Clojure code.
     https://groups.google.com/d/msg/clojurescript/iBY5HaQda4A/w1lAQi9_AwsJ"
     [then else]
     (if (cljs-env? &env) then else)))


;; The protocols and the binding are needed for the channel ops to be transparent for supervision,
;; most importantly exception tracking
(defprotocol PSupervisor
  (-error [this])
  (-abort [this])
  (-register-go [this body])
  (-unregister-go [this id])
  (-track-exception [this e])
  (-free-exception [this e]))


(defn now []
  #?(:clj (java.util.Date.)
     :cljs (js/Date.)))

(defrecord TrackingSupervisor [error abort registered pending-exceptions]
  PSupervisor
  (-error [this] error)
  (-abort [this] abort)
  (-register-go [this body]
    (let [id #?(:clj (java.util.UUID/randomUUID) :cljs (random-uuid))]
      (swap! registered assoc id body)
      id))
  (-unregister-go [this id]
    (swap! registered dissoc id))
  (-track-exception [this e]
    (swap! pending-exceptions assoc e (now)))
  (-free-exception [this e]
    (swap! pending-exceptions dissoc e)))

#?(:cljs (enable-console-print!))

(defn simple-supervisor
  "A simple supervisor which deals with errors through callbacks."
  [& {:keys [stale-timeout error-fn pending-fn]
      :or {stale-timeout (* 10 1000)
           error-fn (fn [e] (println "Supervisor:" e
                                     #?(:cljs (.-stack e))))
           pending-fn (fn [e]
                        (println "Supervisor detected stale error:" e
                                 #?(:cljs (.-stack e))))}}]
  (let [s (assoc (map->TrackingSupervisor {:error (chan)
                                           :abort (chan)
                                           :registered (atom {})
                                           :pending-exceptions (atom {})})
                 :global? true)
        err-ch (:error s)]

    ;; avoid using go-loops with aot here
    (take! err-ch (fn loop-fn [e]
                    (error-fn e)
                    (take! err-ch loop-fn)))
    ((fn pending [_]
       (let [[[e _]] (filter (fn [[k v]]
                               (> (- (.getTime (now)) stale-timeout)
                                  (.getTime v)))
                             @(:pending-exceptions s))]

         (when e
           (pending-fn e)
           (-free-exception s e))
         (take! (timeout stale-timeout) pending))) nil)
    s))

;; a simple global instance, will probably be removed
(def S (simple-supervisor))

(defn throw-if-exception
  "Helper method that checks if x is Exception and if yes, wraps it in a new
  exception, passing though ex-data if any, and throws it. The wrapping is done
  to maintain a full stack trace when jumping between multiple contexts."
  [S x]
  (if (instance? #?(:clj Exception :cljs js/Error) x)
    (do (-free-exception S x)
        (throw (ex-info (or #?(:clj (.getMessage x)) (str x))
                        (or (ex-data x) {})
                        x)))
    x))

;; HACK ensure cljs vars dependency for macro referenced vars
(defn ^:export superv-init []
  [-error -abort -register-go -unregister-go -track-exception -free-exception throw-if-exception])

(superv-init)

#?(:clj
   (defmacro go-try
     "Asynchronously executes the body in a go block. Returns a channel
  which will receive the result of the body when completed or the
  exception if an exception is thrown. You are responsible to take
  this exception and deal with it! This means you need to take the
  result from the cannel at some point."
     {:style/indent 1}
     [S & body]
     `(let [id# (-register-go ~S (quote ~body))]
        ;; if-cljs is sensible to symbol pass-through it is not yet
        ;; clear to me why (if-cljs `cljs.core.async/go `async/go)
        ;; does not work
        (if-cljs (cljs.core.async.macros/go
                   (try ~@body
                        (catch js/Error e#
                          (when-not (= (:type (ex-data e#))
                                       :aborted)
                            (-track-exception ~S e#))
                          e#)
                        (finally
                          (-unregister-go ~S id#))))
                 (go
                   (try
                     ~@body
                     (catch Exception e#
                       (when-not (= (:type (ex-data e#))
                                    :aborted)
                         (-track-exception ~S e#))
                       e#)
                     (finally
                       (-unregister-go ~S id#))))))))




#?(:clj
   (defmacro go-loop-try
     "Loop binding for go-try."
     {:style/indent 2}
     [S bindings & body]
     `(go-try ~S (loop ~bindings ~@body))))


#?(:clj
   (defmacro thread-try
     "Asynchronously executes the body in a thread. Returns a channel
  which will receive the result of the body or the exception if one is
  thrown. "
     {:style/indent 1}
     [S & body]
     `(if-cljs (throw (ex-info "thread-try is not supported in cljs." {:code body}))
               (let [id# (-register-go ~S (quote ~body))]
                 (thread
                   (try
                     ~@body
                     (catch Exception e#
                       (when-not (= (:type (ex-data e#))
                                    :aborted)
                         (-track-exception ~S e#))
                       e#)
                     (finally
                       (-unregister-go ~S id#))))))))


#?(:clj
   (defmacro wrap-abort!
     "Internal."
     [S & body]
     `(if-cljs (let [abort# (-abort ~S)
                     to# (cljs.core.async/timeout 0)
                     [val# port#] (cljs.core.async/alts! [abort# to#] :priority true)]
                 (if (= port# abort#)
                   (ex-info "Aborted operations" {:type :aborted})
                   (do ~@body)))
               (let [abort# (-abort ~S)
                     to# (timeout 0)
                     [val# port#] (alts! [abort# to#] :priority true)]
                 (if (= port# abort#)
                   (ex-info "Aborted operations" {:type :aborted})
                   (do ~@body))))))

#?(:clj
  (defmacro <?
    "Same as core.async <! but throws an exception if the channel returns a
throwable object or the context has been aborted."
    [S ch]
    `(if-cljs (throw-if-exception ~S
              (let [abort# (-abort ~S)
                    [val# port#] (cljs.core.async/alts! [abort# ~ch] :priority :true)]
                (if (= port# abort#)
                  (ex-info "Aborted operations" {:type :aborted})
                  val#)))
              (throw-if-exception ~S
              (let [abort# (-abort ~S)
                    [val# port#] (alts! [abort# ~ch] :priority :true)]
                (if (= port# abort#)
                  (ex-info "Aborted operations" {:type :aborted})
                  val#))))))


#?(:clj
  (defn <??
    "Same as core.async <!! but throws an exception if the channel returns a
throwable object or the context has been aborted. "
    [S ch]
    (throw-if-exception S
    (let [abort (-abort S)
          [val port] (alts!! [abort ch] :priority :true)]
      (if (= port abort)
        (ex-info "Aborted operations" {:type :aborted})
        val)))))


#?(:clj
  (defmacro try<?
    [S ch & body]
    `(try (<? ~S ~ch) ~@body)))

#?(:clj
  (defmacro try<??
    [S ch & body]
    `(try (<?? ~S ~ch) ~@body)))



(defn take?
"Same as core.async/take!, but tracks exceptions in supervisor. TODO
deal with abortion."
([S port fn1] (take? S port fn1 true))
([S port fn1 on-caller?]
  (async/take! port
              (fn [v]
                (when (instance? #?(:clj Exception :cljs js/Error) v)
                  (-free-exception S v))
                (fn1 v))
              on-caller?)))


#?(:clj
  (defmacro >?
    "Same as core.async >! but throws an exception if the context has been aborted."
    [S ch m]
    `(if-cljs (throw-if-exception ~S (wrap-abort! ~S (cljs.core.async/>! ~ch ~m)))
              (throw-if-exception ~S (wrap-abort! ~S (>! ~ch ~m))))))

(defn put?
"Same as core.async/put!, but tracks exceptions in supervisor. TODO
deal with abortion."
([S port val]
  (put? S port val (fn noop [_])))
([S port val fn1]
  (put? S port val fn1 true))
([S port val fn1 on-caller?]
  (async/put! port
              val
              (fn [ret]
                (when (and (instance? #?(:clj Exception :cljs js/Error) val)
                          (not (= (:type (ex-data val))
                                  :aborted)))
                  (-track-exception S val))
                (fn1 ret))
              on-caller?)))

(defn alts?
"Same as core.async alts! but throws an exception if the channel returns a
throwable object or the context has been aborted."
[S ports & opts]
(wrap-abort! S
  (let [[val port] (apply alts! ports opts)]
    [(throw-if-exception S val) port])))


#?(:clj
  (defmacro alt?
    "Same as core.async alt! but throws an exception if the channel returns a
throwable object or the context has been aborted."
    [S & clauses]
    `(if-cljs (throw-if-exception ~S (wrap-abort! ~S (cljs.core.async.macros/alt! ~@clauses)))
              (throw-if-exception ~S (wrap-abort! ~S (alt! ~@clauses))))))

#?(:clj
  (defmacro <<!
    "Takes multiple results from a channel and returns them as a vector.
The input channel must be closed."
    [ch]
    `(if-cljs (let [ch# ~ch]
                (cljs.core.async/<! (cljs.core.async/into [] ch#)))
              (let [ch# ~ch]
                (<! (async/into [] ch#))))))

#?(:clj
  (defmacro <<?
    "Takes multiple results from a channel and returns them as a vector.
Throws if any result is an exception or the context has been aborted."
    [S ch]
    `(if-cljs (cljs.core.async.macros/alt!
                (-abort ~S)
                ([v#] (throw (ex-info "Aborted operations" {:type :aborted})))

                (cljs.core.async.macros/go (<<! ~ch))
                ([v#] (doall (map (fn [e#] (throw-if-exception ~S e#)) v#))))
              (alt! (-abort ~S)
                    ([v#] (throw (ex-info "Aborted operations" {:type :aborted})))

                    (go (<<! ~ch))
                    ([v#] (doall (map (fn [e#] (throw-if-exception ~S e#)) v#)))))))

;; TODO lazy-seq vs. full vector in <<! ?
#?(:clj
  (defn <<!!
    [ch]
    (lazy-seq
    (let [next (<!! ch)]
      (when next
        (cons next (<<!! ch)))))))

#?(:clj
  (defn <<??
    [S ch]
    (lazy-seq
    (let [next (<?? S ch)]
      (when next
        (cons next (<<?? S ch)))))))

#?(:clj
  (defmacro <!*
    "Takes one result from each channel and returns them as a collection.
    The results maintain the order of channels."
    [chs]
    `(let [chs# ~chs]
      (loop [chs# chs#
             results# (if-cljs cljs.core.PersistentQueue.EMPTY
                                (clojure.lang.PersistentQueue/EMPTY))]
        (if-let [head# (first chs#)]
          (->> (if-cljs (cljs.core.async/<! head#) (<! head#))
                (conj results#)
                (recur (rest chs#)))
          (vec results#))))))

#?(:clj
  (defmacro <?*
     "Takes one result from each channel and returns them as a collection.
      The results maintain the order of channels. Throws if any of the
      channels returns an exception."
     [S chs]
     `(let [chs# ~chs]
        (loop [chs# chs#
               results# (if-cljs cljs.core.PersistentQueue.EMPTY
                                 (clojure.lang.PersistentQueue/EMPTY))]
          (if-let [head# (first chs#)]
            (->> (<? ~S head#)
                 (conj results#)
                 (recur (rest chs#)))
            (vec results#))))))

#?(:clj
   (defn <!!*
     [chs]
     (<!! (go (<!* chs)))))

#?(:clj
   (defn <??*
     [S chs]
     (<?? S (go-try S (<?* S chs)))))


(defn pmap>>
  "Takes objects from in-ch, asynchrously applies function f> (function should
  return a channel), takes the result from the returned channel and if it's
  truthy, puts it in the out-ch. Returns the closed out-ch. Closes the
  returned channel when the input channel has been completely consumed and all
  objects have been processed.
  If out-ch is not provided, an unbuffered one will be used."
  ([S f> parallelism in-ch]
   (pmap>> S f> parallelism (async/chan) in-ch))
  ([S f> parallelism out-ch in-ch]
   {:pre [(fn? f>)
          (and (integer? parallelism) (pos? parallelism))
          (instance? ReadPort in-ch)]}
   (let [threads (atom parallelism)]
     (dotimes [_ parallelism]
       (go-try S
         (loop []
           (when-let [obj (<? S in-ch)]
             (if (instance? #?(:clj Exception :cljs js/Error) obj)
               (do
                 (>? S out-ch obj)
                 (async/close! out-ch))
               (do
                 (when-let [result (<? S (f> obj))]
                   (>? S out-ch result))
                 (recur)))))
         (when (zero? (swap! threads dec))
           (async/close! out-ch))))
     out-ch)))



(defn engulf
  "Similiar to dorun. Simply takes messages from channels but does nothing with
  them. Returns channel that will close when all messages have been consumed."
  [S & cs]
  (let [ch (async/merge cs)]
    (go-loop-try S []
      (when (<? S ch) (recur)))))

(defn reduce>
  "Performs a reduce on objects from ch with the function f> (which
  should return a channel). Returns a channel with the resulting
  value."
  [S f> acc ch]
  (let [result (chan)]
    (go-loop-try S [acc acc]
      (if-let [x (<? S ch)]
        (if (instance? #?(:clj Exception :cljs js/Error) x)
          (do
            (>? S result x)
            (async/close! result))
          (->> (f> acc x) (<? S) recur))
        (do
          (>? S result acc)
          (async/close! result))))
    result))

(defn concat>>
  "Concatenates two or more channels. First takes all values from first channel
  and supplies to output channel, then takes all values from second channel and
  so on. Similiar to core.async/merge but maintains the order of values."
  [S & cs]
  (let [out (chan)]
    (go-try S
      (loop [cs cs]
        (if-let [c (first cs)]
          (if-let [v (<? S c)]
            (do
              (>? S out v)
              (recur cs))
            ; channel empty - move to next channel
            (recur (rest cs)))
          ; no more channels remaining - close output channel
          (async/close! out))))
    out))


(defn partition-all>> [S n in-ch & {:keys [out-ch]}]
  "Batches results from input channel into vectors of n size and supplies
  them to ouput channel. If any input result is an exception, it is put onto
  output channel directly and ouput channel is closed."
  {:pre [(pos? n)]}
  (let [out-ch (or out-ch (chan))]
    (go-loop-try S [batch []]
      (if-let [obj (<? S in-ch)]
        (if (instance? #?(:clj Exception :cljs js/Error) obj)
          ; exception - put on output and close
          (do (>? S out-ch obj)
              (async/close! out-ch))
          ; add object to current batch
          (let [new-batch (conj batch obj)]
            (if (= n (count new-batch))
              ; batch size reached - put batch on output and start a new one
              (do
                (>? S out-ch new-batch)
                (recur []))
              ; process next object
              (recur new-batch))))
        ; no more results - put outstanding batch onto output and close
        (do
          (when (not-empty batch)
            (>? S out-ch batch))
          (async/close! out-ch))))
    out-ch))

(defn count>
  "Counts items in a channel. Returns a channel with the item count."
  [S ch]
  (async/reduce (fn [acc obj] (if (instance? #?(:clj Exception :cljs js/Error) obj)
                                (put? S (-error S) obj)
                                (inc acc))) 0 ch))


(comment
  ;; jack in figwheel cljs REPL
  (require 'figwheel-sidecar.repl-api)
  (figwheel-sidecar.repl-api/cljs-repl)


  (go
    (let [old (+ foo 0)]
      (set! foo 45)
      (println old foo)
      (set! foo old)))

  (defn bar []
    (go
      (let [old foo
            baz 42]
        (set! foo 46)
        (println foo old baz)
        (set! foo old))))

)
