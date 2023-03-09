(ns async)

(defmacro go-loop-try- [bindings & body]
  `(try (loop ~bindings ~@body)
        (finally)))

(defmacro go-try [S & body]
  `(do (superv.async/check-supervisor ~S)
       (try ~@body (finally))))

(defmacro go-try- [& body]
  `(try ~@body (finally)))

(defmacro go-loop-try [S bindings & body]
  `(do (superv.async/check-supervisor ~S)
       (try (loop ~bindings ~@body)
            (finally))))

(defmacro go-for [S & body]
  `(do (superv.async/check-supervisor ~S)
     (for ~@body)))

(defmacro go-loop-super [S bindings & body]
  `(do (superv.async/check-supervisor ~S)
       (loop ~bindings ~@body)))
