(ns repl
  (:require
   [data-fetch :as fetch]
   [faur]
   [ring.adapter.jetty :as ring]
   [ring.middleware.params :refer [wrap-params]]))

#_(comment
    (require '[portal.api :as p])
    (def portal (p/open))
    (add-tap #'p/submit))

;; Manually pull updated package data.
(comment
  (fetch/fetch)
  (fetch/unpack))

(comment
  (fetch/refresh-package-data faur/by-names faur/by-provides faur/by-words))

;; Manually start a local server.
(comment
  (swap! faur/server
         (constantly (ring/run-jetty
                      (wrap-params #'faur/handler)
                      {:port 3030 :join? false}))))

;; Manually start a production, HTTPS server.
(comment
  (faur/start-server @faur/opts))

;; Stop a server started in either mode.
(comment
  (.stop @faur/server))

;; Request counts.
(comment
  (deref faur/req-count))

;; Manually controlling the data refresher.
(comment
  (future-done? @faur/fut)
  (future-cancel @faur/fut)
  (future-cancelled? @faur/fut)
  (faur/update-data-forever))
