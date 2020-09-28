(ns superv.async-test
  (:require [clojure.test :refer [deftest is testing #?@(:cljs [async])]]
            [clojure.core.async :as async :refer [<! >! go chan close! alt! timeout chan close! take! to-chan! put! pub sub onto-chan!
                                                  #?@(:clj [<!! >!!])]]
            [superv.async :refer [<? <?- >? S go-try go-try- <<! <<? <?* concat>> partition-all>> count> pmap>> alt? alts? restarting-supervisor go-super go-for map->TrackingSupervisor on-abort put? chan-super go-loop-try go-loop-super
                                  #?@(:clj [<<!! <<?? <?? <!!* <??* thread-try thread-super reduce< <?? chan-super])]]))

(defn test-async
  "Asynchronous test awaiting ch to produce a value or close."
  [ch]
  #?(:clj (<!! ch)
     :cljs (async done (take! ch (fn [_] (done))))))

(defn e []
  #?(:clj (Exception.)
     :cljs (js/Error.)))

(deftest test-<?
  (test-async
   (go
     (is (= (<? S (go
                    (let [ch (chan 1)]
                      (>? S ch "foo")
                      (close! ch)
                      (<? S ch))))
            "foo")))))

(deftest test-<?-
  (test-async
   (go
     (is (= (<?- (go
                   (let [ch (chan 1)]
                     (>! ch "foo")
                     (close! ch)
                     (<?- ch))))
            "foo")))))

(deftest test-go-try-<?
  (test-async
   (go
     (is (thrown? #?(:clj Exception :cljs js/Error)
                  (<? S (go-try S
                                (throw (e))))))
     (is (let [finally-state (atom nil)
               exception-state (atom nil)]
           (<? S (go-try S
                         #?(:clj (/ 1 0)
                            :cljs (throw (js/Error. "Oops")))
                         (catch #?(:clj java.lang.ArithmeticException
                                   :cljs js/Error) e
                           (reset! exception-state 42))
                         (finally (reset! finally-state 42))))
           (= @exception-state @finally-state 42))))))

(deftest test-go-try-<?-
  (test-async
   (go
     (is (thrown? #?(:clj Exception :cljs js/Error)
                  (<?- (go-try- (throw (e))))))
     (is (let [finally-state   (atom nil)
               exception-state (atom nil)]
           (<?- (go-try-
                 #?(:clj (/ 1 0)
                    :cljs (throw (js/Error. "Oops")))
                 (catch #?(:clj java.lang.ArithmeticException
                           :cljs js/Error) e
                   (reset! exception-state 42))
                 (finally (reset! finally-state 42))))
           (= @exception-state @finally-state 42))))))

(deftest test-<<!
  (test-async
   (go
     (is (= (<! (go (let [ch (chan 2)]
                      (>! ch "1")
                      (>! ch "2")
                      (close! ch)
                      (<<! ch))))
            ["1" "2"]))
     (is (= (<! (go (<<! (let [ch (chan 2)]
                           (>! ch "1")
                           (>! ch "2")
                           (close! ch)
                           ch))))
            ["1" "2"])))))

(deftest test-<<?
  (test-async
   (go
     (is (thrown? #?(:clj Exception :cljs js/Error)
                  (<? S (go-try S
                                (let [ch (chan 2)]
                                  (>! ch "1")
                                  (>! ch (e))
                                  (close! ch)
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
     (is (= (->> (let [ch (chan)]
                   (async/onto-chan! ch [1 2])
                   ch)
                 (pmap>> S #(go (inc %)) 2)
                 (<<? S)
                 (set))
            #{2 3})))))

(deftest test-concat>>
  (test-async
   (go
     (is (= (let [ch1 (chan)
                  ch2 (chan)]
              (go (doto ch2 (>! 3) (>! 4) close!))
              (go (doto ch1 (>! 1) (>! 2) close!))
              (<<? S (concat>> S ch1 ch2)))
            [1 2 3 4])))))

(deftest test-partition-all>>
  (test-async
   (go
     (is (= (->> (let [ch (chan)]
                   (go (doto ch (>! 1)
                             (>! 2)
                             (>! 3)
                             close!))
                   ch)
                 (partition-all>> S 2)
                 (<<? S))
            [[1 2] [3]])))))

(deftest test-count>
  (test-async
   (go
     (is (= (<! (count> S (to-chan! [1 2 3 4]))) 4))
     (is (= (<! (count> S (to-chan! []))) 0)))))

#?(:clj
   (do
     (deftest test-<<!!
       (is (= (<<!! (let [ch (chan 2)]
                      (>!! ch "1")
                      (>!! ch "2")
                      (close! ch)
                      ch))
              ["1" "2"])))

     (deftest test-<<??
       (is (thrown? Exception
                    (doall
                     (<<?? S (let [ch (chan 2)]
                               (>!! ch "1")
                               (>!! ch (Exception.))
                               (close! ch)
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
                    (<??* S [(go "1") (go (Exception.))]))))))




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

(deftest test-alts?
  (testing "Test alts? error handling."
    (test-async
     (go
       (let [ch (go 42)
             [v _ch] (alts? S [ch])]
         (is (= v 42)))
       (is (thrown? #?(:clj Exception :cljs js/Error)
                    (let [ch (go (ex-info "foo" {}))
                          [_v _ch] (alts? S [ch])])))))))

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
      (thrown? #?(:clj Exception :cljs js/Error)
               (<? S (go-loop-try S [[f & r] [1 0]]
                                  #?(:clj (/ 1 f)
                                        ;; TODO - Better JS error. In cljs this never terminates without an explicit thrown error
                                     :cljs (throw (js/Error. "Oops")))
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
     (go (is (thrown? #?(:clj Exception :cljs js/Error)
                      (<? super err-ch)))))

    (test-async
     (go
       (is (let [finally-state   (atom nil)
                 exception-state (atom nil)]
             (<? S (go-super super
                             #?(:clj (/ 1 0)
                                :cljs (throw (js/Error. "Oops")))
                             (catch #?(:clj java.lang.ArithmeticException
                                       :cljs js/Error) e
                               (reset! exception-state 42))
                             (finally (reset! finally-state 42))))
             (= @exception-state @finally-state 42)))))))


;; go-loop-super


(deftest test-go-loop-super
  (let [err-ch (chan)
        abort (chan)
        super (map->TrackingSupervisor {:error err-ch :abort abort
                                        :registered (atom {})})]
    (go-loop-super super [[f & r] [1 0]]
                   #?(:clj (/ 1 f)
                                ;; TODO - Better JS error. In cljs this never terminates without an explicit thrown error
                      :cljs (throw (js/Error. "Oops")))
                   (recur r))
    (test-async
     (go (is (thrown? #?(:clj Exception :cljs js/Error)
                      (<? super err-ch)))))))


;; go-for


(deftest test-go-for ;; traditional for comprehension
  (test-async
   (go
     (is (= (<<? S (go-for S [a (range 5)
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

     (is (thrown? #?(:clj Exception :cljs js/Error)
                  (<<? S (go-for S [a [1 2 3]
                                    :let [b 0]]
                                 #?(:clj (/ a b)
                                    :cljs (get-in a b))))))
     (is (thrown? #?(:clj Exception :cljs js/Error)
                  (<<? S (go-for S [a [1 2 3]
                                    :let [b #?(:clj (/ 1 0) :cljs (throw (js/Error. "Oops")))]]
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
       (go (is (thrown? #?(:clj Exception :cljs js/Error)
                        (<? S (restarting-supervisor start-fn :retries 3 :stale-timeout 100))))))

        ;#?(:clj)  ;; TODO: get this one working in cljs
     (let [try-fn (fn [S] (go-try S (throw (ex-info "stale" {}))))
           start-fn (fn [S]
                      (go-try S
                              (try-fn S) ;; should trigger restart after max 2*stale-timeout
                              42))]
       (go (is (thrown? #?(:clj Exception :cljs js/Error))
               (<? S (restarting-supervisor start-fn :retries 3 :stale-timeout 10))))))))

#_(deftest test-recover-publication
    (let [recovered-publication? (atom false)]
      (test-async
       (let [pub-fn (fn [S]
                      (go-try S
                              (let [ch (chan)
                                    p (pub ch :type)
                                    pch (chan)]
                                (on-abort S
                                          (>! ch {:type :foo :continue true})
                                          (reset! recovered-publication? true))
                                (sub S p :foo pch)
                                (put? S ch {:type :foo})
                                (<? S pch)
                                (put! ch {:type :foo :blocked true}))))

             start-fn (fn [S]
                        (go-try S
                                (pub-fn S) ;; concurrent part which holds subscription
                                (throw (ex-info "Abort." {:abort :context}))
                                42))]
         (go (try
               (<? S (restarting-supervisor start-fn :retries 0 :stale-timeout 100))
               (catch #?(:clj Exception :cljs js/Error) _e))
             (is @recovered-publication?))))))

;; a trick: test correct waiting with staleness in other part
(deftest test-waiting-supervisor
  (let [slow-fn (fn [S]
                  (on-abort S
                            (println "Cleaning up."))
                  (go-try S
                          (try
                            (<? S (timeout 5000))
                            (catch #?(:clj Exception :cljs js/Error) _e
                              #_(println "Aborted by:" (.getMessage e)))
                            (finally
                              (<! (timeout 500))
                              #_(println "Cleaned up slowly.")))))
        try-fn (fn [S] (go-try S (throw (ex-info "stale" {}))))

        start-fn (fn [S]
                   (go-try S
                           (try-fn S) ;; should trigger restart after max 2*stale-timeout
                           (slow-fn S) ;; concurrent part which needs to free resources
                           42))]
    (test-async
     (go (is (thrown? #?(:clj Exception :cljs js/Error)
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
                (onto-chan! ch [1 0 3])))
    (test-async
     (go (is (thrown? #?(:clj Exception :cljs js/Error)
                      (<? super err-ch)))))))

#?(:clj
   (deftest reduce<-test
     (is (= 45 (<?? S (reduce< S (fn [S res s]
                                   (go-try S (+ res s)))
                               0
                               (range 10)))))))
