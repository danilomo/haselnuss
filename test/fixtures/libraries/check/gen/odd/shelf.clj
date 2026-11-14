;; A generated shelf with a book entry that cannot be listed (TASK-242,
;; --check): its title is a number, so its library reports
;; :invalid-generated-book and the shelf lists no books.

{:books (fn [_] [{:slug "odd" :title 42}])
 :document (fn [_ _] {:meta {} :blocks []})}
