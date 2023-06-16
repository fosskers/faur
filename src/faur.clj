(ns faur
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def db-file "packages-meta-ext-v1.json")
(def ignored-terms #{"for" "and" "the" "with" "from" "that" "your" "git" "bin" "this" "not" "svn" "who" "can" "you" "like" "into" "all" "more" "one" "any" "over" "non" "them" "are" "very" "when" "about" "yet" "many" "its" "also" "most" "lets" "just"})

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

(defn printable-ascii?
  "Is the given char printable ASCII?"
  [c]
  (let [n (int c)]
    (<= 32 n 127)))

(comment
  (every? printable-ascii? "hello")
  (every? printable-ascii? "日本語"))

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
       (take 30)))

(comment
  (->> ["aura-bin" "foo_bar-baz"]
       (map #(str/split % #"(-|_)"))
       (apply concat)))

(defn same-package?
  "Are two packages logically the same?"
  [a b]
  (= (:Name a) (:Name b)))
