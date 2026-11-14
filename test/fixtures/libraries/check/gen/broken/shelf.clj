;; A generated shelf whose script fails (TASK-242, --check): `:books`
;; throws, so the shelf lists no books and its library reports
;; :script-failed.

{:books (fn [_] (throw (ex-info "planted failure: this shelf lists nothing" {})))
 :document (fn [_ _] nil)}
