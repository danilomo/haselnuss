(ns haselnuss.library.graph.backlinks
  "`--backlinks` as data (doc-11, \"--backlinks\"; TASK-243):
  `backlinks-report` turns the edges into one book of a reference graph
  into a report, and `render-text`/`render-json` print one. As for
  `haselnuss.library.graph.check`, report and printing are kept apart so a
  future `--client --backlinks` -- and a reader's \"Referenced by\" panel --
  get the same data from a daemon's warm graph. Nothing here is
  CLI-specific.

  ## What the report holds

  `backlinks-report` reads ONLY `haselnuss.library.graph/edges-to` -- one
  call, with the target it is given -- and never lists or resolves
  anything. The target is `{:id BookId :passage? Passage}`, the id being
  the book's CURRENT canonical id (the CLI resolves its argument through
  the library directory first: `edges-to` matches a resolved edge only on
  its exact canonical id, so a stale shelf would find none). Without a
  passage it answers every edge into the book; with one, the edges whose
  passage is that one as written, after whitespace normalisation
  (`haselnuss.library.graph/passage-key`). Dangling edges that still name
  the book -- an unknown label, a bad locator, a book the library does not
  list under that slug -- are included: their author meant this book.

  Each edge becomes one entry, the doc-11 edge shape with BookIds as
  canonical strings and passages as written:

      {:from {:id \"cat:heidelberg\" :passage \"q:1\"}       ; nearest labelled ancestor, or nil
       :to {:id \"bible:blivre/romanos\" :written \"bible:romanos#14:8\" :passage \"14:8\"}
       :kind :quote :status :resolved :problem nil
       :position {:file \"cat/heidelberg.hdoc\" :line 40 :column 1}}  ; or nil

  - `:to :id` is nil for a dangling edge whose book is known only from
    what was written (`:unknown-book`); `:to :passage` is the edge's
    parsed passage written out (`book-ref/format-passage`), else the one
    parsed from `:written`, nil for the whole book.
  - `:position :file` is shown relative to `opts`' `:base-dir` when it
    lies inside it (`haselnuss.library.graph.text/display-path`).
    `:column` is the edge's 1-based column (TASK-248), or nil where the
    parser could not place the reference on its line. A generated
    book's references carry no position at all: `:position` is nil.
  - `:from :order`, the edge's place in its book's document order, is
    left out: it means something only beside that book's label spans,
    which the report does not carry.

  `:edges` holds the references from OTHER books, `:self` the book's
  references to itself (doc-11, Open point 11: stored, but no citation by
  somebody else, so listed apart). Both are ordered the same way: grouped
  by target passage -- references to the whole book first, then each
  passage in `passage-order` -- and within a group by source id, source
  passage, file, line, then what was written, kind, status and problem.
  `edges-to` answers in no particular order; this order depends on nothing
  but the data.

  `:summary` counts `:references` (the entries of `:edges`), `:books`
  (the distinct books they come from) and `:self`.

  ## The text form

  `render-text` prints the book, one indented heading per passage group
  (`(whole book)`, `#14:8`, ...), and under it one line per entry -- the
  source with its `#label`, the kind, `file:line`, and `dangling:
  <problem>` for a dangling edge -- then the self-references under a
  `self-references` heading, then `render-summary`. Every string that
  comes from a library is `escape-text`ed, so each entry is one line that
  reads as what it is.

  ## The JSON envelope (a stable contract)

  `render-json` prints one line:

      {\"version\":1,\"book\":...,\"passage\":...,
       \"edges\":[{\"from\":{\"id\":...,\"passage\":...},
                  \"to\":{\"id\":...,\"written\":...,\"passage\":...},
                  \"kind\":...,\"status\":...,\"problem\":...,
                  \"position\":{\"file\":...,\"line\":...,\"column\":null}}, ...],
       \"self\":[...same shape...],
       \"summary\":{\"references\":...,\"books\":...,\"self\":...}}

  with the keys in that order, `position` null when the edge has none,
  and every other value a string, number or null. `version` changes only
  when a field is removed or changes meaning; adding a field does not
  change it."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [haselnuss.book-ref :as book-ref]
            [haselnuss.library.graph :as graph]
            [haselnuss.library.graph.text :as text]))

(def report-version
  "The `version` of the report and its JSON envelope."
  1)

;; --- ordering ----------------------------------------------------------------

(defn- natural-tokens
  "`s` as alternating runs: `[0 digits]` for a run of digits (leading
  zeros dropped, so the run compares by length then text, i.e. as a
  number of any size) and `[1 text]` for any other run."
  [s]
  (mapv (fn [[run digits]]
          (if digits
            (let [d (str/replace digits #"^0+(?=\d)" "")] [0 (count d) d])
            ;; three elements like a digit run's: `compare` orders
            ;; vectors by length before content
            [1 0 run]))
        (re-seq #"(\d+)|\D+" s)))

(defn- compare-tokens
  [xs ys]
  (loop [[x & xs'] xs [y & ys'] ys]
    (cond
      (and (nil? x) (nil? y)) 0
      (nil? x) -1
      (nil? y) 1
      :else (let [c (compare x y)]
              (if (zero? c) (recur xs' ys') c)))))

(defn passage-order
  "A comparator over passage keys (strings, nil for the whole book): nil
  first, then natural order -- runs of digits compared as numbers, so
  `#2:3` comes before `#14:8` and a verse before a label (`#14:8` before
  `#sec:x`) -- and the plain string order to break a tie."
  [a b]
  (cond
    (= a b) 0
    (nil? a) -1
    (nil? b) 1
    :else (let [c (compare-tokens (natural-tokens a) (natural-tokens b))]
            (if (zero? c) (compare a b) c))))

(defn- entry-key
  [{:keys [from to kind status problem position]}]
  [(:id from) (or (:passage from) "") (or (:file position) "") (or (:line position) 0)
   (:written to) (name kind) (name status) (if problem (name problem) "")])

(defn group-key
  "The passage group of a report entry: its target passage's
  `haselnuss.library.graph/passage-key`, nil for the whole book."
  [entry]
  (graph/passage-key (:passage (:to entry))))

(defn- sort-entries
  [entries]
  (vec (sort (fn [a b]
               (let [c (passage-order (group-key a) (group-key b))]
                 (if (zero? c) (compare (entry-key a) (entry-key b)) c)))
             entries)))

;; --- the report --------------------------------------------------------------

(defn- written-passage
  "The target passage of `edge` as written: its `:to :passage`, else the
  one parsed from `:to :written`; nil when it names none."
  [edge]
  (let [p (or (:passage (:to edge))
              (let [parsed (book-ref/parse-book-ref (:written (:to edge)))]
                (when-not (book-ref/malformed? parsed) (:passage parsed))))]
    (when p (book-ref/format-passage p))))

(defn- entry
  [edge base-dir]
  (let [{:keys [from to kind status problem position]} edge]
    {:from {:id (book-ref/format-book-id (:id from)) :passage (:passage from)}
     :to {:id (some-> (:id to) book-ref/format-book-id)
          :written (:written to)
          :passage (written-passage edge)}
     :kind kind
     :status status
     :problem problem
     :position (when position
                 {:file (text/display-path (:file position) base-dir)
                  :line (:line position)
                  :column (:column position)})}))

(defn- self-edge?
  "True when `edge` comes from the queried book `id` itself: exactly that
  id when it is canonical (has a shelf), else the same library and slug
  -- the rule `edges-to` matches a resolved edge by."
  [id edge]
  (let [from (:id (:from edge))]
    (if (seq (:shelf id)) (= id from) (= (book-ref/book-key id) (book-ref/book-key from)))))

(defn backlinks-report
  "The `--backlinks` report of the edges into `target` in the graph `g`
  (see the namespace docstring): `{:version :book :passage :edges [entry
  ...] :self [entry ...] :summary {:references :books :self}}`. `target`
  is `{:id BookId :passage? Passage-or-string}` -- the book's current
  canonical id -- or a bare BookId. Pure: reads only `edges-to`. `opts`:
  `:base-dir`, an absolute folder paths are shown relative to (the CLI
  passes its working directory); nil shows them as recorded."
  ([g target] (backlinks-report g target {}))
  ([g target {:keys [base-dir]}]
   (let [{:keys [id passage]} (graph/parse-target target)
         edges (graph/edges-to g target)
         {self true others false} (group-by #(boolean (self-edge? id %)) edges)
         others (sort-entries (map #(entry % base-dir) others))
         self (sort-entries (map #(entry % base-dir) self))]
     {:version report-version
      :book (book-ref/format-book-id id)
      :passage passage
      :edges others
      :self self
      :summary {:references (count others)
                :books (count (distinct (map (comp :id :from) others)))
                :self (count self)}})))

;; --- rendering ---------------------------------------------------------------

(def ^:private escape text/escape-text)

(defn- source-text
  [{:keys [from]}]
  (str (:id from) (when-let [p (:passage from)] (str "#" p))))

(defn- where
  [{:keys [position]}]
  (let [{:keys [file line]} position]
    (cond
      (and file line) (str file ":" line)
      file file
      line (str "line " line)
      :else "-")))

(defn- pad [s width] (str s (apply str (repeat (- width (count s)) \space))))

(defn- group-heading
  [k]
  (if (nil? k) "(whole book)" (str "#" k)))

(defn- render-entries
  "The heading and entry lines of `entries` (already in report order),
  the source column `width` wide."
  [entries width]
  (mapcat (fn [group]
            (cons (str "  " (escape (group-heading (group-key (first group)))))
                  (for [e group]
                    (str "    " (pad (escape (source-text e)) width)
                         "   " (pad (name (:kind e)) 5)
                         "   " (escape (where e))
                         (when (= :dangling (:status e))
                           (str "   dangling: " (escape (name (or (:problem e) :unknown)))))))))
          (partition-by group-key entries)))

(defn render-summary
  "`N references from B books`, and `; S self-references` when there are
  any."
  [{{:keys [references books self]} :summary}]
  (str (text/plural references "reference") " from " (text/plural books "book")
       (when (pos? self) (str "; " (text/plural self "self-reference")))))

(defn render-text
  "`report` for a terminal: the book (with `#passage` when the report is
  about one), the grouped entries, the self-references under their own
  heading, and `render-summary`, each line ending in a newline. Every
  library-supplied string is `escape-text`ed."
  [{:keys [book passage edges self] :as report}]
  (let [width (reduce max 0 (map (comp count escape source-text) (concat edges self)))
        lines (concat [(escape (str book (when passage (str "#" passage))))]
                      (render-entries edges width)
                      (when (seq self) (cons "self-references" (render-entries self width)))
                      [(render-summary report)])]
    (str/join (map #(str % "\n") lines))))

(defn- json-entry
  [{:keys [from to kind status problem position]}]
  (array-map "from" (array-map "id" (:id from) "passage" (:passage from))
             "to" (array-map "id" (:id to) "written" (:written to) "passage" (:passage to))
             "kind" (name kind)
             "status" (name status)
             "problem" (some-> problem name)
             "position" (when position
                          (array-map "file" (:file position) "line" (:line position)
                                     "column" (:column position)))))

(defn render-json
  "`report` as its versioned JSON envelope (the namespace docstring has
  the contract), one line ending in a newline."
  [{:keys [version book passage edges self summary]}]
  (str (json/write-str (array-map "version" version
                                  "book" book
                                  "passage" passage
                                  "edges" (mapv json-entry edges)
                                  "self" (mapv json-entry self)
                                  "summary" (array-map "references" (:references summary)
                                                       "books" (:books summary)
                                                       "self" (:self summary)))
                       :escape-slash false :escape-unicode false)
       "\n"))
