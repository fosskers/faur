(ns faur
  (:require
   [babashka.cli :as cli]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]
   [nrepl.server :as nrepl]
   [ring.adapter.jetty :as ring]
   [ring.middleware.params :refer [wrap-params]]
   [taoensso.timbre :refer [info set-min-level!]]))

(def db-file "packages-meta-ext-v1.json")

(def ignored-terms
  #{"for" "and" "the" "with" "from" "that" "your" "git" "bin" "this" "not" "svn"
    "who" "can" "you" "like" "into" "all" "more" "one" "any" "over" "non" "them"
    "are" "very" "when" "about" "yet" "many" "its" "also" "most" "lets" "just"})

(def cli-options {:port {:default 8080 :coerce :long}
                  :cert {:coerce :long}
                  :key {:coerce :long}})

(defn parse-packages
  "Given a path to raw package data, parse it all into memory."
  [file]
  (->> file
       io/reader
       (#(json/parse-stream % true))))

(defn packages-by-name
  "Given a list of all packages, yield a map that indexes them by their `:Name`
  field."
  [packages]
  (transduce (map (fn [pkg] [(:Name pkg) pkg])) conj {} packages))

(comment
  (->> db-file
       parse-packages
       packages-by-name
       (#(get % "aura-bin"))
       (#(doto % tap>))))

(defn packages-by-provides
  "Given a list of all packages, yield a map that indexes them by the package
  'identities' that they provide."
  [packages]
  (reduce (fn [prov-map pkg]
            (let [provides (:Provides pkg)]
              (if (or (nil? provides)
                      (empty? provides))
                (update prov-map
                        (:Name pkg)
                        (fn [set]
                          (let [set (or set #{})]
                            (conj set (:Name pkg)))))
                (reduce (fn [provs provided]
                          (update provs
                                  provided
                                  (fn [set]
                                    (let [set (or set #{})]
                                      (conj set (:Name pkg))))))
                        prov-map provides))))
          {} packages))

(comment
  (->> db-file
       parse-packages
       packages-by-provides
       (#(get % "emacs"))
       (#(doto % tap>))))

(defn printable-ascii?
  "Is the given char printable ASCII?"
  [c]
  (let [n (int c)]
    (<= 32 n 127)))

(comment
  (every? printable-ascii? "hello")
  (every? printable-ascii? "日本語"))

;; TODO account for keywords
(defn packages-by-word
  "Given a list of all packages, yield a map that indexes them by words
  contained in their name and descriptions."
  [packages]
  (reduce (fn [word-map pkg]
            (let [name-terms  (str/split (:Name pkg) #"(-|_)")
                  description (or (:Description pkg) "")
                  desc-terms  (str/split description #" ")]
              (->> (concat name-terms desc-terms)
                   (mapcat #(str/split % #"(-|_|!|,|[\|]|:|/|[(]|[)]|[.]|'|[+]|\?|=|\*|\")"))
                   (map #(str/replace % #"(\\|%|\])$" ""))
                   (map #(str/replace % #"^(\[|~)" ""))
                   (filter #(> (count %) 2))
                   (filter #(every? printable-ascii? %))
                   (map str/lower-case)
                   (remove #(contains? ignored-terms %))
                   (into #{})
                   (reduce (fn [words word]
                             (update words
                                     word
                                     (fn [set]
                                       (let [set (or set #{})]
                                         (conj set (:Name pkg))))))
                           word-map))))
          {} packages))

(comment
  (->> db-file
       parse-packages
       packages-by-word
       (map (fn [[word ps]] {:word word :count (count ps)}))
       (sort-by :count)
       reverse
       (take 30)
       (#(doto % tap>))))

#_(defn wrap-pkg-names
    "Parse the given package names to query and yield them as a vector."
    [handler]
    (fn [request]
      (->> (update-in request [:params :names] #(str/split % #","))
           handler)))

(def all-packages (->> db-file parse-packages atom))
(def by-names (->> @all-packages packages-by-name atom))
(def by-provides (->> @all-packages packages-by-provides atom))
(def by-words (->> @all-packages packages-by-word atom))

(defn bad-route []
  {:status 404
   :headers {"Content-Type" "text/html"}
   :body "That does not exist."})

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
  (->> ["firefox"]
       (find-by-provs @by-names @by-provides)
       (map :Name)))

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
  (->> ["python" "pandas"]
       (map #(get @by-words %))
       (apply set/intersection)
       (map #(get @by-names %))
       (sort-by :LastModified)
       reverse
       (map #(select-keys % [:Name :LastModified]))
       (#(doto % tap>))))
       ;; json/generate-string))

(defn handler [request]
  (let [uri    (:uri request)
        method (:request-method request)
        pkgs   (->> (get (:params request) "names")
                    (#(if (nil? %) [] (str/split % #","))))
        by     (get (:params request) "by")]
    (if (and (= :get method) (= "/packages" uri))
      (cond (= by "prov") (->> pkgs (find-by-provs @by-names @by-provides) success)
            (= by "desc") (->> pkgs (find-by-words @by-names @by-words) success)
            (nil? by)     (->> pkgs (find-by-names @by-names) success)
            :else         (bad-route))
      (bad-route))))

(defn -main [& args]
  (let [{port :port} (cli/parse-opts args {:spec cli-options})]
    (set-min-level! :info)
    (ring/run-jetty (wrap-params #'handler) {:port port :join? false})
    (nrepl/start-server :port 7888)))

(comment
  (def server (ring/run-jetty
               (wrap-params #'handler)
               {:port 3030 :join? false})))

(comment
  (.stop server))

(comment
  (set-min-level! :info)
  (def counter (atom 0))
  (def fut (future
             (while true
               (info (format "Printing: %d" @counter))
               (swap! counter inc)
               (Thread/sleep 1000))))
  (future-cancel fut))
