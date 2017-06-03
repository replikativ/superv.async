(ns superv.async-test
  (:require
    #?(:clj [clojure.test :refer :all]
       :cljs [cljs.test :refer-macros [deftest is async]])
    #?(:clj [clojure.core.async :refer [<!! <! >! >!! go chan close! alt! timeout] :as async]
       :cljs [cljs.core.async :refer [<! >!] :as async])
    #?(:clj [superv.async :refer :all]
       :cljs [superv.async :refer-macros [<<! <<? <? <?* go-try go-super go-loop-super]]))
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go]])))

(defn test-async
  "Asynchronous test awaiting ch to produce a value or close."
  [ch]
  #?(:clj (<!! ch)
     :cljs (async done (async/take! ch (fn [_] (done))))))

(defn e []
  #?(:clj (Exception.)
     :cljs (js/Error.)))

(deftest test-<?
  (test-async
    (go
      (is (= (<? S (go
                     (let [ch (async/chan 1)]
                       (>? S ch "foo")
                       (async/close! ch)
                       (<? S ch))))
             "foo")))))

(deftest test-go-try-<?
  (test-async
   (go
     (is (thrown? #?(:clj Exception :cljs js/Error)
                  (<? S (go-try S
                          (throw (e)))))))))



(deftest test-<<!
  (test-async
   (go
     (is (= (<! (go (let [ch (async/chan 2)]
                      (>! ch "1")
                      (>! ch "2")
                      (async/close! ch)
                      (<<! ch))))
            ["1" "2"]))
     (is (= (<! (go (<<! (let [ch (async/chan 2)]
                           (>! ch "1")
                           (>! ch "2")
                           (async/close! ch)
                           ch))))
            ["1" "2"])))))

(deftest test-<<?
  (test-async
   (go
     (is (thrown? #?(:clj Exception :cljs js/Error)
                  (<? S (go-try S
                          (let [ch (async/chan 2)]
                            (>! ch "1")
                            (>! ch (e))
                            (async/close! ch)
                            (<<? S ch)))))))))

(deftest ^:async test-<?*
  (test-async
    (go
      (is (= (<?* S [(go "1") (go "2")])
             ["1" "2"]))
      (is (= (<?* S (list (go "1") (go "2")))
             ["1" "2"]))
      (is (thrown? #?(:clj Exception :cljs js/Error)
                   (<?* S [(go "1") (go (e))]))))))


(deftest test-pmap>>
  (test-async
    (go
      (is (= (->> (let [ch (async/chan)]
                    (go (doto ch (>! 1) (>! 2) async/close!))
                    ch)
                  (pmap>> S #(go (inc %)) 2)
                  (<<? S)
                  (set))
             #{2 3})))))

(deftest test-concat>>
  (test-async
    (go
      (is (= (let [ch1 (async/chan)
                   ch2 (async/chan)]
               (go (doto ch2 (>! 3) (>! 4) async/close!))
               (go (doto ch1 (>! 1) (>! 2) async/close!))
               (<<? S (concat>> S ch1 ch2)))
             [1 2 3 4])))))

(deftest test-partition-all>>
  (test-async
    (go
      (is (= (->> (let [ch (async/chan)]
                    (go (doto ch (>! 1)
                                 (>! 2)
                                 (>! 3)
                                 async/close!))
                    ch)
                  (partition-all>> S 2)
                  (<<? S))
             [[1 2] [3]])))))

(deftest test-count>
  (test-async
    (go
      (is (= (<! (count> S (async/to-chan [1 2 3 4]))) 4))
      (is (= (<! (count> S (async/to-chan []))) 0)))))


;;; CLOJURE ONLY


#?(:clj
   (do
     (deftest test-<<!!
       (is (= (<<!! (let [ch (async/chan 2)]
                      (>!! ch "1")
                      (>!! ch "2")
                      (async/close! ch)
                      ch))
              ["1" "2"])))

     (deftest test-<<??
       (is (thrown? Exception
                    (doall
                      (<<?? S (let [ch (async/chan 2)]
                                (>!! ch "1")
                                (>!! ch (Exception.))
                                (async/close! ch)
                                ch))))))

     (deftest test-<!!*
       (is (= (<!!* [(go "1") (go "2")])
              ["1" "2"])))

     (deftest test-<??*
       (is (= (<??* S [(go "1") (go "2")])
              ["1" "2"]))
       (is (= (<??* S (list (go "1") (go "2")))
              ["1" "2"]))
       (is (thrown? Exception
                    (<??* S [(go "1") (go (Exception. ))]))))))




;; alt?
(deftest test-alt?
  (testing "Test alt? error handling."
    (test-async
     (go
       (is
        (= (<? S (go (alt? S (go 42)
                           :success

                           (timeout 100)
                           :fail)))
           :success))))))

#?(:clj
   (deftest test-alts?
     (testing "Test alts? error handling."
       (test-async
        (go
          (let [ch (go 42)
                [v ch] (alts? S [ch])]
            (is (= v 42)))
          (is (thrown? Exception
                       (let [ch (go (ex-info "foo" {}))
                             [v ch] (alts? S [ch])]))))))))

;; thread-try
#?(:clj
   (deftest test-thread-try
     (testing "Test threading macro."
       (is (= (<?? S (thread-try S 42))
              42))
       (is (thrown? Exception
                    (<?? S (thread-try S
                             (throw (ex-info "bar" {})))))))))


;; thread-super
#?(:clj
   (deftest test-thread-super
     (let [err-ch (chan)
           abort (chan)
           super (map->TrackingSupervisor {:error err-ch :abort abort
                                           :registered (atom {})
                                           :pending-exceptions (atom {})})]
       (is (= (<?? S (thread-super super 42))
              42))
       (is (thrown? Exception
                    (let [err-ch (chan)
                          abort (chan)
                          super (map->TrackingSupervisor {:error err-ch :abort abort
                                                          :registered (atom {})
                                                          :pending-exceptions (atom {})})]
                      (thread-super super (/ 1 0))
                      (<?? S err-ch)))))))



;; go-loop-try
(deftest test-go-loop-try
  (test-async
   (go
     (is
      (thrown? Exception (<? S (go-loop-try S [[f & r] [1 0]]
                                 (/ 1 f)
                                 (recur r))))))))

;; go-super
(deftest test-go-super
 (let [err-ch (chan)
       abort (chan)
       super (map->TrackingSupervisor {:error err-ch :abort abort
                                       :registered (atom {})
                                       :pending-exceptions (atom {})})]
   (go-super super (/ 1 0)) 
   (test-async
    (go (is (thrown? Exception
             (<? super err-ch)))))))

;; go-loop-super
(deftest test-go-loop-super
 (let [err-ch (chan)
       abort (chan)
       super (map->TrackingSupervisor {:error err-ch :abort abort
                                       :registered (atom {})})]
   (go-loop-super super [[f & r] [1 0]]
                  (/ 1 f)
                  (recur r))
   (test-async
    (go (is (thrown? Exception
                     (<? super err-ch)))))))


;; go-for
(deftest test-go-for ;; traditional for comprehension
  (test-async
   (go (is
        (= (<<? S (go-for S [a (range 5)
                             :let [c 42]
                              b [5 6]
                              :when (even? b)]
                           [a b c]))
           '([0 6 42] [1 6 42] [2 6 42] [3 6 42] [4 6 42])))
       (is (= (<<? S (go-for S [a [1 2 3]
                                :let [b (<? S (go (* a 2)))]]
                             (<? S (go [a b]))))
              '([1 2] [2 4] [3 6])))
       (is (= (<<? S (go-for S [a [1 nil 3]]
                             [a a]))
              [[1 1] [nil nil] [3 3]]))
       (is (thrown? Exception
                    (<<? S (go-for S [a [1 2 3]
                                      :let [b 0]]
                                   (/ a b)))))
       (is (thrown? Exception
                    (<<? S (go-for S [a [1 2 3]
                                      :let [b (/ 1 0)]]
                                   42)))))))



;; supervisor

(deftest test-supervisor
  (test-async
   (do
     (let [start-fn (fn [S]
                      (go-super S 42))]
       (go (is (= (<? S (restarting-supervisor start-fn :retries 3 :stale-timeout 100))
                  42))))
     (let [start-fn (fn [S]
                      (go-super S
                        (throw (ex-info "foo" {}))))]
       (go (is (thrown? Exception
                        (<? S (restarting-supervisor start-fn :retries 3 :stale-timeout 100))))))
     (let [try-fn (fn [S] (go-try S (throw (ex-info "stale" {}))))
           start-fn (fn [S]
                      (go-try S
                        (try-fn S) ;; should trigger restart after max 2*stale-timeout
                        42))]
       (go (is (thrown? Exception)
               (<? S (restarting-supervisor start-fn :retries 3 :stale-timeout 10))))))))



(deftest test-recover-publication
  (let [recovered-publication? (atom false)]
    (test-async
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
       (go (try
             (<? S (restarting-supervisor start-fn :retries 0 :stale-timeout 100))
             (catch Exception e))
           (is @recovered-publication?))))))

;; a trick: test correct waiting with staleness in other part
(deftest test-waiting-supervisor
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
   (test-async
    (go (is (thrown? Exception
                     (<? S (restarting-supervisor start-fn :retries 3 :stale-timeout 100))))))))


;; transducer embedding

(deftest test-transducer-error-handling
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
   (test-async
    (go (is (thrown? Exception
                     (<? super err-ch)))))))



(deftest reduce<-test
  (is (= 45 (<?? S (reduce< S (fn [S res s]
                                (go-try S (+ res s)))
                            0
                            (range 10))))))
