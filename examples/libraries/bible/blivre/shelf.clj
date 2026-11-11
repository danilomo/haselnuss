;; The generated shelf of the example Bible library (TASK-159, text replaced in
;; TASK-212): one book per entry of blivre.json, each `{"slug" "title"
;; "verses"}` with its verses as [chapter verse text] triples. The text is the
;; Bíblia Livre (BLIVRE), CC BY 3.0 Brasil; see ../README.md. Run in
;; haselnuss.sandbox, so it reads nothing itself: the host parses blivre.json
;; and passes it in.
;;
;; Locators are `C`, `C:V` or `C:V-W`. A passage of one verse is that
;; verse's paragraph; a passage of several numbers each one, `**34** ...`
;; -- the shape the catechism's proof texts have always had.
;;
;; `C:V` and `C:V-W` are rejected unless every verse they name is in the
;; library. A bare chapter `C` cannot be checked that way, since the library
;; does not record how many verses a chapter has: it answers the verses the
;; library holds of that chapter, which may be only some of them.

(defn- book-of [data slug]
  (some (fn [b] (when (= slug (:slug b)) b)) (get data "blivre.json")))

(defn- verse-para [text] {:t :para :inlines [{:t :str :text text}]})

(defn- numbered-verse [verse text]
  {:t :para :inlines [{:t :strong :inlines [{:t :str :text (str verse)}]}
                      {:t :str :text (str " " text)}]})

(defn- chapter-of [title chapter verses]
  {:t :section
   :level 1
   :heading [{:t :str :text (str title " " chapter)}]
   :attr {:id (str "cap:" chapter) :classes [] :props {}}
   :blocks (mapv (fn [[_ v text]] (numbered-verse v text)) verses)})

{:data ["blivre.json"]

 :books
 (fn [data]
   (for [{:keys [slug title]} (get data "blivre.json")]
     {:slug slug :title title :lang "pt-BR"}))

 :document
 (fn [data slug]
   (let [{:keys [title verses]} (book-of data slug)]
     {:meta {:title [{:t :str :text title}] :lang "pt-BR"}
      :blocks (vec (for [[chapter vs] (sort-by key (group-by first verses))]
                     (chapter-of title chapter vs)))}))

 :locate
 (fn [data slug locator]
   (let [{:keys [title verses]} (book-of data slug)]
     ;; No leading zero, which read-string would take for octal.
     (if-let [[_ c from to] (re-matches #"([1-9]\d{0,2})(?::([1-9]\d{0,2})(?:-([1-9]\d{0,2}))?)?"
                                        locator)]
       (let [chapter (read-string c)
             from (some-> from read-string)
             to (or (some-> to read-string) from)
             found (filterv (fn [[ch v]] (and (= ch chapter) (or (nil? from) (<= from v to))))
                            verses)]
         (cond
           (and from (> from to))
           {:type :bad-locator
            :message (str title " " locator " is a reversed range: its first verse comes after its last")}
           (empty? found)
           {:type :bad-locator
            :message (str title " " locator " is not in this library (a subset of the Bíblia Livre)")}
           (and from (not= (inc (- to from)) (count found)))
           {:type :bad-locator
            :message (str title " " locator " is only partly in this library (a subset of the Bíblia Livre)")}
           :else
           {:title (str title " " locator)
            :blocks (if (= 1 (count found))
                      [(verse-para (nth (first found) 2))]
                      (mapv (fn [[_ v text]] (numbered-verse v text)) found))}))
       {:type :bad-locator
        :message (str "a locator is C, C:V or C:V-W, not " (pr-str locator))})))}
