(ns faur
  (:require
   [cheshire.core :as json]
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.tools.cli :refer [parse-opts]]
   [data-fetch :as fetch]
   [less.awful.ssl :as ssl]
   [nrepl.server :as nrepl]
   [ring.adapter.jetty :as ring]
   [ring.middleware.params :refer [wrap-params]]
   [taoensso.timbre :refer [info set-min-level!]]))

(def cli-options
  [["-p" "--port PORT" "Port number"
    :default 8080
    :parse-fn #(Integer/parseInt %)]
   [nil "--key PATH"  "Path to a TLS certificate's private key"]
   [nil "--cert PATH" "Path to a TLS certificate"]])

(def all-packages (atom []))
(def by-names (atom {}))
(def by-provides (atom {}))
(def by-words (atom {}))

(defn bad-route
  "Some unrecognized route was called."
  []
  {:status 404
   :headers {"Content-Type" "text/html"}
   :body "That route does not exist."})

(defn success
  "Yield a good response as JSON."
  [edn]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string edn)})

(defn find-by-names
  "Yield the packages the match the given names."
  [all-by-name query-pkgs]
  (->> query-pkgs
       (map #(get all-by-name %))
       (filter identity)))

(defn find-by-provs
  "Yield the packages that match the given provides."
  [all-by-name all-by-provs query-pkgs]
  (->> query-pkgs
       (map #(get all-by-provs %))
       (apply set/union)
       (map #(get all-by-name %))))

(comment
  (->> "aura,git"
       (#(str/split % #","))
       (find-by-provs @by-names @by-provides)
       (#(doto % tap>))))

(defn find-by-words
  "Yield the packages that contain the given words."
  [all-by-name all-by-words query-terms]
  (->> query-terms
       (map #(get all-by-words %))
       (apply set/intersection)
       (take 100)
       (map #(get all-by-name %))))

(comment
  (->> #{"nintendo" "switch"}
       (find-by-words @by-names @by-words)
       (map #(select-keys % [:Name :Description]))
       (sort-by :Name)))

(defn handler [request]
  (let [uri    (:uri request)
        method (:request-method request)
        pkgs   (->> (get (:params request) "names")
                    (#(if (nil? %) [] (str/split % #",")))
                    (into #{}))  ; Prevents repeated query terms.
        by     (get (:params request) "by")]
    (if (and (= :get method) (= "/packages" uri))
      (cond (= by "prov") (->> pkgs (find-by-provs @by-names @by-provides) success)
            (= by "desc") (->> pkgs (find-by-words @by-names @by-words) success)
            (nil? by)     (->> pkgs (find-by-names @by-names) success)
            :else         (bad-route))
      (bad-route))))

(defn server-config
  "Configure the REST server based on the given commandline arguments. Will run in
  HTTS-mode if a TLS cert/key are available."
  [{:keys [key cert port]}]
  (let [basic {:port port :join? false}]
    (if (and key cert)
      (assoc basic
             :ssl-context (ssl/ssl-context key cert)
             :ssl-port 3001
             :client-auth :want)
      basic)))

(defonce fut (atom nil))
(defonce server (atom nil))

(defn -main [& args]
  (let [opts (:options (parse-opts args cli-options))]
    (set-min-level! :info)
    (info "Reading initial package data...")
    (fetch/refresh-package-data all-packages by-names by-provides by-words)
    (info "Spawing refresh thread...")
    (swap! fut (constantly (future (fetch/update-data all-packages by-names by-provides by-words))))
    (info "Starting API server.")
    (swap! server (constantly (ring/run-jetty (wrap-params #'handler) (server-config opts))))
    (info "Starting nREPL.")
    (nrepl/start-server :port 7888)))
