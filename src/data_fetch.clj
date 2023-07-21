(ns data-fetch
  (:require
   [babashka.process :refer [sh]]
   [clojure.java.io :as io]
   [packages :as pkgs]
   [taoensso.timbre :refer [info warn]]))

(def seconds-in-hour 3600000)

(def tarball (format "%s.gz" pkgs/db-file))

(def tarball-url
  "The location we expect to be able to download the tarball of all current
  package data."
  (format "https://aur.archlinux.org/%s" tarball))

(defn fetch
  "Make a shell call to `wget` to download a fresh copy of the JSON data."
  []
  (->> (sh ["wget" "--quiet" tarball-url])
       :exit
       zero?))

(defn unpack
  "Decompress a tarball downloaded by `wget`."
  []
  (->> (sh ["gzip" "--quiet" "--force" "--decompress" tarball])
       :exit
       zero?))

(defn refresh-package-data
  "Given an atom for the various package indices, read the current on-disk data
  and refresh them."
  [all-packages by-names by-provides by-words]
  (let [pkgs (pkgs/read-packages)]
    (swap! all-packages (constantly (:all-packages pkgs)))
    (swap! by-names     (constantly (:by-names pkgs)))
    (swap! by-provides  (constantly (:by-provides pkgs)))
    (swap! by-words     (constantly (:by-words pkgs)))))

(defn endless-update
  "Endlessly fetch package data from the AUR server. Upon an individual failure,
  does not crash, but prints a warning."
  [all-packages by-names by-provides by-words]
  (loop []
    (info "Fetching updated package data.")
    (if (and (fetch) (unpack))
      (do (info "Reforming package indices...")
          (refresh-package-data all-packages by-names by-provides by-words))
      (warn "Unable to fetch new package data!"))
    (info "Sleeping...")
    (Thread/sleep seconds-in-hour)
    (recur)))

(defn update-data
  "Update the package data every hour.

  Upon first startup, this will sleep for one hour if it detects that _some_
  data already exists locally. This it to prevent bad looping behaviour there is
  no internet connection or there is something wrong with the AUR."
  [all-packages by-names by-provides by-words]
  (when (.exists (io/file pkgs/db-file))
    (info "Local data already exists. Sleeping...")
    (Thread/sleep seconds-in-hour))
  (endless-update all-packages by-names by-provides by-words))
