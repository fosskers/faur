(ns repl)

(comment
  (require '[portal.api :as p]))

(comment
  (def portal (p/open))
  (add-tap #'p/submit))
