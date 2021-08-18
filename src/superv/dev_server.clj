(ns superv.dev-server
  (:require
   [ring.util.response :as resp])
  (:gen-class))

(defn handle-index [handler]
  (fn [request]
    (if (= [:get "/"] ((juxt :request-method :uri) request))
      (resp/response
       "<!DOCTYPE html>
        <html>
        <body>
        <script src=\"js/client.js\"></script>
        </body>
        </html>")
      (handler request))))

(def main-handler
  (-> {}
      handle-index))


