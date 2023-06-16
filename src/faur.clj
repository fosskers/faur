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

(comment
  (->> db-file
       parse-packages
       first))

(defn same-package?
  "Are two packages logically the same?"
  [a b]
  (= (:Name a) (:Name b)))
