(ns packages
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json]))

(def db-file "packages-meta-ext-v1.json")

(def ignored-terms
  #{"for" "and" "the" "with" "from" "that" "your" "git" "bin" "this" "not" "svn"
    "who" "can" "you" "like" "into" "all" "more" "one" "any" "over" "non" "them"
    "are" "very" "when" "about" "yet" "many" "its" "also" "most" "lets" "just"})

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

(defn char-number?
  "Is the given char a number?"
  [c]
  (let [n (int c)]
    (<= 48 n 57)))

(comment
  (every? printable-ascii? "hello")
  (every? printable-ascii? "日本語"))

(def separators
  "Characters upon which to split a package's description even further."
  #"[-_!,:/()\[\].'+?=*\"#$%&{}|;~\\<>@`^]")

(comment
  (str/split "hello+([why" separators))

(def split-and-clean
  (comp (mapcat #(str/split % separators))
        (filter #(> (count %) 2))
        (filter #(every? printable-ascii? %))
        (filter #(not (char-number? (first %))))
        (map str/lower-case)
        (remove #(contains? ignored-terms %))
        (distinct)))

(comment
  (transduce (split-and-clean)
             conj
             (str/split "The.quick 'brown' 🦊 jumps-OvER th 1337 QUICK <dog>!" #" ")))

(defn packages-by-word
  "Given a list of all packages, yield a map that indexes them by words
  contained in their name and descriptions."
  [packages]
  (reduce (fn [word-map pkg]
            (let [name        (:Name pkg)
                  description (or (:Description pkg) "")
                  name-terms  (str/split name #"(-|_)")
                  desc-terms  (str/split description #" ")
                  keyw-terms  (:Keywords pkg)]
              (transduce split-and-clean
                         (completing (fn [words word] (update words word #(conj (or % #{}) name))))
                         word-map
                         (concat name-terms desc-terms keyw-terms))))
          {} packages))

(comment
  (->> db-file
       parse-packages
       packages-by-word
       (map (fn [[word ps]] {:word word :count (count ps)}))
       (sort-by :count)))
       ;; reverse
       ;; (take 30)))
       ;; (#(doto % tap>))))

(defn read-packages
  "Read raw package metadata from a downloaded DB file, and form new indexes."
  []
  (let [all-packages (parse-packages db-file)]
    {:by-names    (packages-by-name all-packages)
     :by-provides (packages-by-provides all-packages)
     :by-words    (packages-by-word all-packages)}))
