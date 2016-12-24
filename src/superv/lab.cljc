(ns superv.lab
  "This namespace contains experimental functionality, which might or
  might not enter the stable full.async namespace."
  (:require #?(:clj [superv.async :refer [<? >? go-try go-loop-try PSupervisor map->TrackingSupervisor
                                        -free-exception -track-exception
                                        -register-go -unregister-go -error -abort
                                        if-cljs now]]
               :cljs [superv.async :refer [PSupervisor map->TrackingSupervisor
                                         -free-exception -track-exception
                                         -register-go -unregister-go -error -abort now]])

            #?(:clj [clojure.core.async :refer [<! <!! >! >!! alt! alt!!
                                                alts! alts!! go go-loop
                                                chan thread timeout put! pub unsub close!]
                     :as async]
               :cljs [cljs.core.async :refer [<! >! alts! chan timeout put! pub unsub close!]
                      :as async]))
  #?(:cljs (:require-macros [superv.async :refer [<? >? go-try go-loop-try if-cljs <<? <<!]]
                            [superv.lab :refer [on-abort go-for go-super go-loop-super
                                              thread-super]]
                            [cljs.core.async.macros :refer [go go-loop alt!]])))

#?(:clj
   (defmacro on-abort
     "Executes body if the supervisor aborts the context. You *need* to
  use this to free up any external resources. This is necessary,
  because our error handling is not part of the runtime which could
  free the resources for us as is the case with the Erlang VM."
     [S & body]
     `(if-cljs
       (go-try ~S
        (cljs.core.async/<! (-abort ~S))
        ~@body)
       (go-try ~S
        (<! (-abort ~S))
        ~@body))))

(defn tap
  "Safely managed tap. The channel is closed on abortion and all
  pending puts are flushed."
  ([S mult ch]
   (tap S mult ch false))
  ([S mult ch close?]
   (on-abort S (close! ch)
             (go-try (while (<! ch))))
   (async/tap mult ch close?)))

(defn sub
  "Safely managed subscription. The channel is closed on abortion and
  all pending puts are flushed."
  ([S p topic ch]
   (sub S p topic ch false))
  ([S p topic ch close?]
   (on-abort S (close! ch) (go-try S (while (<! ch))))
   (async/sub p topic ch close?)))


#?(:clj
   (defmacro go-super
     "Asynchronously executes the body in a go block. Returns a channel which
  will receive the result of the body when completed or nil if an
  exception is thrown. Communicates exceptions via supervisor channels."
     {:style/indent 1}
     [S & body]
     `(let [id# (-register-go ~S (quote ~body))]
        (if-cljs (cljs.core.async.macros/go
                   (try
                     ~@body
                     (catch js/Error e#
                       (let [err-ch# (-error ~S)]
                         (cljs.core.async/>! err-ch# e#)))
                     (finally
                       (-unregister-go ~S id#))))
                 (go
                   (try
                     ~@body
                     (catch Exception e#
                       (let [err-ch# (-error ~S)]
                         (>! err-ch# e#)))
                     (finally
                       (-unregister-go ~S id#))))))))

#?(:clj
   (defmacro go-loop-super
     "Supervised loop binding."
     {:style/indent 2}
     [S bindings & body]
     `(go-super ~S (loop ~bindings ~@body))))

#?(:clj
   (defmacro thread-super
     "Asynchronously executes the body in a thread. Returns a channel
  which will receive the result of the body when completed or nil if
  an exception is thrown. Communicates exceptions via supervisor
  channels."
     {:style/indent 1}
     [S & body]
     `(if-cljs (throw (ex-info "thread-super not supported in cljs." {:code body}))
               (let [id# (-register-go ~S (quote ~body))]
                 (thread
                   (try
                     ~@body
                     (catch #?(:clj Exception :cljs js/Error) e#
                       ;; bug in core.async:
                       ;; No method in multimethod '-item-to-ssa' for dispatch value: :protocol-invoke
                       (let [err-ch# (-error ~S)]
                         (put! err-ch# e#)))
                     (finally
                       (-unregister-go ~S id#))))))))


(defn chan-super
  "Creates a supervised channel for transducer xform. Exceptions
  immediatly propagate to the supervisor."
  [S buf-or-n xform]
  (chan buf-or-n xform (fn [e] (put! (:error S) e))))



;; taken from clojure/core ~ 1.7
#?(:clj
   (defmacro ^{:private true} assert-args
     [& pairs]
     `(do (when-not ~(first pairs)
            (throw (IllegalArgumentException.
                    (str (first ~'&form) " requires " ~(second pairs) " in " ~'*ns* ":" (:line (meta ~'&form))))))
          ~(let [more (nnext pairs)]
             (when more
               (list* `assert-args more))))))


#?(:clj
   (defmacro go-for
     "List comprehension adapted from clojure.core 1.7. Takes a vector of
  one or more binding-form/collection-expr pairs, each followed by
  zero or more modifiers, and yields a channel of evaluations of
  expr. It is eager on all but the outer-most collection.
  TODO This can cause too many pending puts.

  Collections are iterated in a nested fashion, rightmost fastest, and
  nested coll-exprs can refer to bindings created in prior
  binding-forms.  Supported modifiers are: :let [binding-form expr
  ...],
  :while test, :when test. If a top-level entry is nil, it is skipped
  as it cannot be put on the result channel by core.async semantics.

  (<<? (go-for [x (range 10) :let [y (<? (go-try 4))] :while (< x y)] [x y]))"
     [S seq-exprs body-expr]
     (assert-args
      (vector? seq-exprs) "a vector for its binding"
      (even? (count seq-exprs)) "an even number of forms in binding vector")
     (let [to-groups (fn [seq-exprs]
                       (reduce (fn [groups [k v]]
                                 (if (keyword? k)
                                   (conj (pop groups) (conj (peek groups) [k v]))
                                   (conj groups [k v])))
                               [] (partition 2 seq-exprs)))
           err (fn [& msg] (throw (IllegalArgumentException. ^String (apply str msg))))
           emit-bind (fn emit-bind [res-ch [[bind expr & mod-pairs]
                                            & [[_ next-expr] :as next-groups]]]
                       (let [giter (gensym "iter__")
                             gxs (gensym "s__")
                             do-mod (fn do-mod [[[k v :as pair] & etc]]
                                      (cond
                                        (= k :let) `(let ~v ~(do-mod etc))
                                        (= k :while) `(when ~v ~(do-mod etc))
                                        (= k :when) `(if ~v
                                                       ~(do-mod etc)
                                                       (recur (rest ~gxs)))
                                        (keyword? k) (err "Invalid 'for' keyword " k)
                                        next-groups
                                        `(let [iterys# ~(emit-bind res-ch next-groups)
                                               fs# (<? ~S (iterys# ~next-expr))]
                                           (if fs#
                                             (concat fs# (<? ~S (~giter (rest ~gxs))))
                                             (recur (rest ~gxs))))
                                        :else `(let [res# ~body-expr]
                                                 (when res#
                                                   (>? ~S ~res-ch res#))
                                                 (<? ~S (~giter (rest ~gxs))))
                                        #_`(cons ~body-expr (<? (~giter (rest ~gxs))))))]
                         `(fn ~giter [~gxs]
                            (go-loop-try ~S [~gxs ~gxs]
                                         (let [~gxs (seq ~gxs)]
                                           (when-first [~bind ~gxs]
                                             ~(do-mod mod-pairs)))))))
           res-ch (gensym "res_ch__")]
       `(let [~res-ch (if-cljs (cljs.core.async/chan) (chan))
              iter# ~(emit-bind res-ch (to-groups seq-exprs))]
          (if-cljs (cljs.core.async.macros/go
                     (try (<? ~S (iter# ~(second seq-exprs)))
                          (catch js/Error e#
                            (-track-exception ~S e#)
                            (cljs.core.async/>! ~res-ch e#))
                          (finally (cljs.core.async/close! ~res-ch))))
                   (go (try (<? ~S (iter# ~(second seq-exprs)))
                            (catch Exception e#
                              (-track-exception ~S e#)
                              (>! ~res-ch e#))
                            (finally (async/close! ~res-ch)))))
          ~res-ch))))


(defn restarting-supervisor
  "Starts a subsystem with supervised go-routines initialized by
  start-fn. Restarts the system on error for retries times with a
  potential delay in milliseconds, an optional error-fn predicate
  determining the retry and a optional filter by exception type.

  All blocking channel ops in the subroutines (supervised context) are
  aborted with an exception on error to force total termination. The
  supervisor waits until all supervised go-routines are finished and
  have freed resources before restarting.

  If exceptions are not taken from go-try channels (by error), they
  become stale after stale-timeout and trigger a restart. "
  [start-fn & {:keys [retries delay error-fn exception stale-timeout log-fn]
               :or {retries #?(:clj Long/MAX_VALUE :cljs js/Infinity)
                    delay 0
                    error-fn nil
                    exception #?(:clj Exception :cljs js/Error)
                    stale-timeout (* 60 1000)
                    log-fn (fn [level msg]
                             (println level msg))}}]
  (let [retries (or retries #?(:clj Long/MAX_VALUE :cljs js/Infinity))
        out-ch (chan)]
    (go-loop [retries retries]
      (let [err-ch (chan)
            ab-ch (chan)
            close-ch (chan)
            s (map->TrackingSupervisor {:error err-ch :abort ab-ch
                                        :registered (atom {})
                                        :pending-exceptions (atom {})
                                        :restarting true})
            res-ch (start-fn s)
            stale-timeout 1000]

        (go-loop [] 
          (when-not (async/poll! ab-ch)
            (<! (timeout stale-timeout))
            (let [[[e _]] (filter (fn [[k v]]
                                    (> (- (.getTime (now)) stale-timeout)
                                       (.getTime v)))
                                  @(:pending-exceptions s))]
              (if e
                (do
                  (when-not (= (:type (ex-data e)) :aborted)
                    (log-fn :info {:event :stale-error-in-supervisor
                                   :error e}))
                  (-free-exception s e)
                  (put! err-ch e))
                (recur)))))

        (go-loop [i 0]
          (if-not (and (empty? @(:registered s))
                       (empty? @(:pending-exceptions s)))
            (do
              (when (= (mod i 1000) 0) ;; every 100 seconds
                #_(log-fn :debug
                        ["waiting for go-routines to restart: "
                         #?(:clj (java.util.Date.)
                            :cljs (js/Date.))
                         @(:registered s)
                         @(:pending-exceptions s)]))
              (<! (timeout 100))
              (recur (inc i)))
            (close! close-ch)))


        (let [[e? c] (alts! [err-ch close-ch] :priority true)]
          (if-not (= c close-ch) ;; an error occured
            (do
              (close! err-ch)
              (close! ab-ch)
              (<! close-ch) ;; wait until we are finished
              (if (or (not (instance? exception e?))
                      (not (or (not error-fn) (error-fn e?)))
                      (not (pos? retries)))
                (do
                  (log-fn :error {:event :passing-error :error e?})
                  (put! out-ch e?)
                  (close! out-ch))
                (do (<! (timeout delay))
                    (log-fn :debug {:event :retry :error e? :further-retries retries})
                    (recur (dec retries)))))
            (do (put! out-ch (<! res-ch))
                (close! out-ch))))))
    out-ch))


(comment
  (<?? (go-try (<? (go 42)) (throw (ex-info "foo" {}))))

  (go-try (throw (ex-info "foo" {})))

  (go (let [slow-fn (fn [super]
                      (go-super
                       (try
                         (<? (timeout 5000))
                         (catch Exception e
                           (println e))
                         (finally
                           (<! (timeout 1000))
                           (println "Cleaned up slowly.")))))
            try-fn (fn [] (go-try (throw (ex-info "stale" {}))))
            database-lookup (fn [key] (go-try (vec (repeat 3 (inc key)))))

            start-fn (fn [super]
                       (go-super super
                                 (try-fn) ;; should trigger restart after max 2*stale-timeout
                                 #_(slow-fn super) ;; concurrent part which needs to free resources

                                 ;; transducer exception handling
                                 #_(let [ch (chan-super 10 (comp (map (fn [b] (/ 1 b)))
                                                                 (filter pos?)))]
                                     (async/onto-chan ch [1 0 3]))

                                 ;; go-for for complex control flow with
                                 ;; blocking (read) ops (avoiding function
                                 ;; boundary core.async boilerplate)
                                 #_(println (<<? (go-for [a [1 2 #_nil -3] ;; comment out nil => BOOOM
                                                          :let [[b] (<? (database-lookup a))]
                                                          :when (even? b)
                                                          c (<? (database-lookup b))]
                                                         [a b c])))
                                 (<? (timeout 100))
                                 #_(throw (ex-info "foo" {}))
                                 3))]
        (<? (restarting-supervisor start-fn :retries 3 :stale-timeout 100))))


  (go (.info js/console (pr-str (<<? (go-for [a [1 2 -3] ;; comment out nil => BOOOM
                                              :let [c (<? (go 42))]
                                              b [3 4]]
                                             [a b c])))))

  (with-super (map->TrackingSupervisor {:error (chan) :abort (chan)
                                        :registered (atom {})
                                        :pending-exceptions (atom {})})
    (go-super 42 (throw (ex-info "fooz" {}))))

  (go (binding [*super* 49] (.log js/console full.async/*super*)))

  *super*

  (go-super (throw (ex-info "barz" {})))


  (go (js/alert (let [start-fn (fn []
                                 (go-super 42))]
                  (<! (restarting-supervisor start-fn :retries 3 :stale-timeout 100)))))


  (enable-console-print!)
  ;; works
  (go (.log js/console (pr-str (let [start-fn (fn []
                                                (go-super (throw (ex-info "foo" {}))))]
                                 (<! (restarting-supervisor start-fn :retries 3 :stale-timeout 100))))))


  (go (.log js/console (let [slow-fn (fn []
                                       (go-try
                                        #_(on-abort
                                           (.log js/console "Cleaning up."))
                                        (try
                                          (<? (timeout 5000))
                                          (catch js/Error e
                                            (.log js/console "Aborted by:" (.pr-str e)))
                                          (finally
                                            (async/<! (timeout 150))
                                            (.log js/console "Cleaned up slowly.")))))
                             try-fn (fn [] (go-try (throw (ex-info "stale" {}))))

                             start-fn (fn []
                                        (go-try
                                         (try-fn) ;; should trigger restart after max 2*stale-timeout
                                         (slow-fn) ;; concurrent part which needs to free resources
                                         42))]
                         (<! (restarting-supervisor start-fn :retries 3 :stale-timeout 100))))))
