;; A generated shelf (TASK-158): one book per entry of biblia.json, a
;; small sample in the shape of a verse list, titled from books.edn.
;; Locators are chapter, chapter:verse or chapter:verse-verse.

(defn- verses-of
  "The [chapter verse text] triples of the book `slug`."
  [data slug]
  (let [abbrev (some (fn [[a {s :slug}]] (when (= s slug) a)) (get data "books.edn"))]
    (some (fn [b] (when (= abbrev (:abbrev b)) (:verses b))) (get data "biblia.json"))))

(defn- title-of [data slug]
  (some (fn [{s :slug t :title}] (when (= s slug) t)) (vals (get data "books.edn"))))

(defn- para [text] {:t :para :inlines [{:t :str :text text}]})

(defn- numbered-para [verse text]
  {:t :para :inlines [{:t :strong :inlines [{:t :str :text (str verse)}]}
                      {:t :str :text (str " " text)}]})

(defn- passage-blocks
  "One paragraph per verse, numbered when there is more than one."
  [verses]
  (if (= 1 (count verses))
    [(para (nth (first verses) 2))]
    (mapv (fn [[_ v text]] (numbered-para v text)) verses)))

(defn- chapter-section [title chapter verses]
  {:t :section
   :level 1
   :heading [{:t :str :text (str title " " chapter)}]
   :attr {:id (str "cap:" chapter) :classes [] :props {}}
   :blocks (mapv (fn [[_ v text]] (numbered-para v text)) verses)})

{:data ["biblia.json" "books.edn"]

 :books
 (fn [data]
   (for [{:keys [abbrev]} (get data "biblia.json")
         :let [{:keys [slug title]} (get (get data "books.edn") abbrev)]]
     {:slug slug :title title :lang "pt-BR"}))

 :document
 (fn [data slug]
   (let [title (title-of data slug)]
     {:meta {:title [{:t :str :text title}] :lang "pt-BR"}
      :blocks (vec (for [[chapter verses] (sort-by key (group-by first (verses-of data slug)))]
                     (chapter-section title chapter verses)))}))

 :locate
 (fn [data slug locator]
   (when-let [[_ c from to] (re-matches #"(\d+)(?::(\d+)(?:-(\d+))?)?" locator)]
     (let [chapter (read-string c)
           from (some-> from read-string)
           to (or (some-> to read-string) from)
           verses (filterv (fn [[ch v]] (and (= ch chapter) (or (nil? from) (<= from v to))))
                           (verses-of data slug))]
       (when (seq verses)
         {:title (str (title-of data slug) " " locator)
          :blocks (passage-blocks verses)}))))}
