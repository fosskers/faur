(ns repl
  (:require
   [data-fetch :as fetch]
   [faur]
   [ring.adapter.jetty :as ring]
   [ring.middleware.params :refer [wrap-params]]))

(comment
  (require '[portal.api :as p]))

(comment
  (def portal (p/open))
  (add-tap #'p/submit))

(comment
  (fetch/fetch)
  (fetch/unpack))

(comment
  (def server (ring/run-jetty
               (wrap-params #'faur/handler)
               {:port 3030 :join? false})))

(comment
  (.stop server))
