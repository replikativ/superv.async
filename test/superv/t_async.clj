(ns superv.t-async
  (:require [midje.sweet :refer :all]
            [superv.async :refer :all]
            [superv.lab :refer :all]
            [clojure.core.async :refer [<!! >! >!! go chan close! alt! timeout] :as async]))

(facts
 (fact
  (<!! (go (let [ch (chan 2)]
             (>! ch "1")
             (>! ch "2")
             (close! ch)
             (<<! ch))))
  => ["1" "2"])

 (fact
  (<!! (go (let [ch (chan 2)]
             (>! ch "1")
             (>! ch "2")
             (close! ch)
             (<<! ch))))
  => ["1" "2"])

 (fact
  (<?? S (go-try S
                 (let [ch (chan 2)]
                 (>! ch "1")
                 (>! ch "2")
                 (close! ch)
                 (<<? S ch))))
  => ["1" "2"])


 (fact
  (<?? S (go-try S
          (let [ch (chan 2)]
                 (>! ch "1")
                 (>! ch (Exception.))
                 (close! ch)
                 (<<? S ch))))
  => (throws Exception))

 (fact
  (<<!! (let [ch (chan 2)]
          (>!! ch "1")
          (>!! ch "2")
          (close! ch)
          ch))
  => ["1" "2"])

 (fact
  (<!! (go (<<! (let [ch (chan 2)]
                  (>! ch "1")
                  (>! ch "2")
                  (close! ch)
                  ch))))
  => ["1" "2"])

 (fact
  (<<?? S (let [ch (chan 2)]
          (>!! ch "1")
          (>!! ch (Exception.))
          (close! ch)
          ch))
  => (throws Exception))

 (fact
  (<!!* [(go "1") (go "2")])
  => ["1" "2"])

 (fact
  (<??* S [(go "1") (go "2")])
  => ["1" "2"])

 (fact
  (<??* S (list (go "1") (go "2")))
  => ["1" "2"])

 (fact
  (<??* S [(go "1") (go (Exception. ))])
  => (throws Exception))

 (fact
  (->> (let [ch (chan)]
         (go (doto ch (>!! 1) (>!! 2) close!))
         ch)
       (pmap>> S #(go (inc %)) 2)
       (<<?? S)
       (set))
  => #{2 3})

 (fact
  (let [ch1 (chan)
        ch2 (chan)]
    (go (doto ch2 (>!! 3) (>!! 4) close!))
    (go (doto ch1 (>!! 1) (>!! 2) close!))
    (<<?? S (concat>> S ch1 ch2))
    => [1 2 3 4]))

 (fact
  (->> (let [ch (chan)]
         (go (doto ch (>!! 1)
                   (>!! 2)
                   (>!! 3)
                   close!))
         ch)
       (partition-all>> S 2)
       (<<?? S))
  => [[1 2] [3]])

 (fact
  (try<?? S
   (go-try S (throw (Exception.)))
   false
   (catch Exception _
     true))
  => true))

;; alt?
(fact
 (<?? S (go (alt? S (go 42)
                :success

                (timeout 100)
                :fail)))
 => :success)

;; go-try
(fact
 (<?? S (go-try S (alt? S (timeout 100) 43
                        :default (ex-info "foo" {}))))
 => (throws Exception))

(fact
 (<?? (go-try :foo 1))
 => (throws Exception))

;; thread-try
(fact
 (<?? S (thread-try S 42))
 => 42)

(fact
 (<?? S (thread-try S (throw (ex-info "bar" {}))))
 => (throws Exception))

;; thread-super
(fact
 (let [err-ch (chan)
       abort (chan)
       super (map->TrackingSupervisor {:error err-ch :abort abort
                                       :registered (atom {})
                                       :pending-exceptions (atom {})})]
   (<?? S (thread-super super 42))) => 42)

(fact
 (let [err-ch (chan)
       abort (chan)
       super (map->TrackingSupervisor {:error err-ch :abort abort
                                       :registered (atom {})
                                       :pending-exceptions (atom {})})]
   (thread-super super (/ 1 0))
   (<?? S err-ch))
 => (throws Exception))

;; go-loop-try
(fact
 (<?? S (go-loop-try S [[f & r] [1 0]]
                   (/ 1 f)
                   (recur r)))
 => (throws Exception))

;; go-super
(fact
 (let [err-ch (chan)
       abort (chan)
       super (map->TrackingSupervisor {:error err-ch :abort abort
                                       :registered (atom {})
                                       :pending-exceptions (atom {})})]
   (go-super super (/ 1 0)) 
   (<?? super err-ch))
 => (throws Exception))

;; go-loop-super
(fact
 (let [err-ch (chan)
       abort (chan)
       super (map->TrackingSupervisor {:error err-ch :abort abort
                                       :registered (atom {})})]
   (go-loop-super super [[f & r] [1 0]]
                  (/ 1 f)
                  (recur r))
   (<?? super err-ch))
 => (throws Exception))


;; go-for
(fact ;; traditional for comprehension
 (<<?? S (go-for S [a (range 5)
                :let [c 42]
                b [5 6]
                :when (even? b)]
               [a b c]))
 => '([0 6 42] [1 6 42] [2 6 42] [3 6 42] [4 6 42]))

(fact ;; async operations spliced in bindings and body
 (<<?? S (go-for S [a [1 2 3]
                :let [b (<? S (go (* a 2)))]]
               (<? S (go [a b]))))
 => '([1 2] [2 4] [3 6]))

(fact ;; verify that nils don't prematurely terminate go-for
 (<<?? S (go-for S [a [1 nil 3]]
               [a a]))
 => [[1 1] [nil nil] [3 3]])


(facts ;; async operations propagate exceptions
 (<<?? S (go-for S [a [1 2 3]
                :let [b 0]]
               (/ a b)))
 => (throws Exception)

 (<<?? S (go-for S [a [1 2 3]
                :let [b (/ 1 0)]]
               42))
 => (throws Exception))


;; supervisor

(fact
 (let [start-fn (fn [S]
                  (go-super S 42))]
   (<?? S (restarting-supervisor start-fn :retries 3 :stale-timeout 100)))
 => 42)

(<!! (go-super S
               (println "starting")
               (throw (ex-info "foo" {}))))

(fact
 (let [start-fn (fn [S]
                  (go-super S
                            (throw (ex-info "foo" {}))))]
   (<?? S (restarting-supervisor start-fn :retries 3 :stale-timeout 100)))
 => (throws Exception))

;; fails
(fact
 (let [try-fn (fn [S] (go-try S (throw (ex-info "stale" {}))))
       start-fn (fn [S]
                  (go-try S
                   (try-fn S) ;; should trigger restart after max 2*stale-timeout
                   42))]
   (<?? S (restarting-supervisor start-fn :retries 3 :stale-timeout 10)))
 => (throws Exception))


(let [recovered-publication? (atom false)]
  (fact
   (let [pub-fn (fn [S]
                  (go-try S
                   (let [ch (chan)
                         p (async/pub ch :type)
                         pch (chan)]
                     (on-abort S
                      (>! ch {:type :foo :continue true})
                      (reset! recovered-publication? true))
                     (sub S p :foo pch)
                     (put? S ch {:type :foo})
                     (<? S pch)
                     (async/put! ch {:type :foo :blocked true}))))

         start-fn (fn [S]
                    (go-try S
                     (pub-fn S) ;; concurrent part which holds subscription
                     (throw (ex-info "Abort." {:abort :context}))
                     42))]
     (try
         (<?? S (restarting-supervisor start-fn :retries 0 :stale-timeout 100))
       (catch Exception e)))
   @recovered-publication?))

;; a trick: test correct waiting with staleness in other part
(fact
 (let [slow-fn (fn [S]
                 (on-abort S
                  (println "Cleaning up."))
                 (go-try S
                  (try
                    (<? S (timeout 5000))
                    (catch Exception e
                      #_(println "Aborted by:" (.getMessage e)))
                    (finally
                      (async/<! (timeout 500))
                      #_(println "Cleaned up slowly.")))))
       try-fn (fn [S] (go-try S (throw (ex-info "stale" {}))))

       start-fn (fn [S]
                  (go-try S
                   (try-fn S) ;; should trigger restart after max 2*stale-timeout
                   (slow-fn S) ;; concurrent part which needs to free resources
                   42))]
   (<?? S (restarting-supervisor start-fn :retries 3 :stale-timeout 100)))
 => (throws Exception))


;; transducer embedding

(fact
 (let [err-ch (chan)
       abort (chan)
       super (map->TrackingSupervisor {:error err-ch :abort abort
                                       :registered (atom {})
                                       :pending-exceptions (atom {})})]

   (go-super super
    (let [ch (chan-super super 10 (comp (map (fn [b] (/ 1 b)))
                                        ;; wrong comp order causes division by zero
                                        (filter pos?)))]
      (async/onto-chan ch [1 0 3])))
   (<?? super err-ch))
 => (throws Exception))

(facts "superv.async/count>"
  (fact (<!! (count> S (async/to-chan [1 2 3 4]))) => 4)
  (fact (<!! (count> S (async/to-chan []))) => 0))
