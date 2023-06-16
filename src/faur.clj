(ns faur
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]))

(def db-file "packages-meta-ext-v1.json")

;; NOTE
;; No special field conversion necessary!
;; I can use keywords! They are case-sensitive. This should save a lot of memory.

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
       (#(get % "aura-bin"))))

(defn packages-by-provider
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
       packages-by-provider
       (#(get % "emacs"))))

(defn same-package?
  "Are two packages logically the same?"
  [a b]
  (= (:Name a) (:Name b)))
