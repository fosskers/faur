(ns data-fetch
  (:require [clojure.java.io :as io]
            [taoensso.timbre :refer [info]]))

(def db-file "packages-meta-ext-v1.json")

(def seconds-in-hour 3600000)

(def tarball-url
  "The location we expect to be able to download the tarball of all current
  package data."
  (format "https://aur.archlinux.org/%s.gz" db-file))

(defn fetch
  "Make a shell call to `wget` to download a fresh copy of the JSON data."
  []
  ())

(defn unpack
  "Decompress a tarball downloaded by `wget`."
  []
  ())

(defn endless-update []
  (loop []
    (info "Fetching updated package data.")
    (fetch)
    (unpack)
    (Thread/sleep seconds-in-hour)
    (recur)))

(defn update-data
  "Update the package data every hour.

  Upon first startup, this will sleep for one hour if it detects that _some_
  data already exists locally. This it to prevent bad looping behaviour there is
  no internet connection or there is something wrong with the AUR."
  []
  (when (.exists (io/file db-file))
    (info "Local data already exists. Sleeping...")
    (Thread/sleep seconds-in-hour))
  (endless-update))
