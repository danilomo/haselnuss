(ns haselnuss.library.graph.check
  "`--check` as data (doc-11, \"--check\"; TASK-242): `check-report` turns
  a reference graph into a report, and `render-text`/`render-json` print
  one. Report and printing are kept apart so a future `--client --check`
  gets byte-identical output from a daemon's warm graph: the daemon runs
  `check-report`, sends `render-json`'s envelope, and the client renders
  that same data. Nothing here is CLI-specific.

  ## What the report holds

  `check-report` reads ONLY the graph -- `indexed-books` and `edges-from`
  of `haselnuss.library.graph/ReferenceGraph`, and `indexed-libraries` of
  the optional `LibraryIndex` -- and never lists or resolves anything
  again:

  - every diagnostic of every book record (its load warnings, its own
    resolver diagnostics, one per dangling reference, `:stale-shelf`
    infos; see `haselnuss.library.graph.scan`);
  - every diagnostic of every library record (`:duplicate-slug`,
    `:invalid-book-name`, `:scripts-disabled`, `:script-failed`,
    `:invalid-generated-book`, `:unavailable-library`,
    `:invalid-url-template`, ...);
  - `:quote-cycle` and `:quote-too-deep` derived from the `:quote` edges
    (`quote-problems`). A scan reports a book quoting itself; a cycle
    through other books, or a chain nested deeper than
    `haselnuss.resolver/max-quote-depth`, only shows when a build expands
    the quotes, so it is derived here -- placed at the `!quote` a build
    of some book in the cycle (or at the head of the chain) reports it at.
    Resolved quotes are followed; one whose passage did not resolve can
    still close a cycle or a chain, as a build checks the book first
    (TASK-249). Under `--check --transform` the quoted books' edges and
    spans are those of their transformed copies, which a build does not
    quote (the README documents the difference).

  Each diagnostic becomes one item:

      {:type :unknown-label :level :warning :message \"...\"
       :file \"pass/creeds/quoting.hdoc\" :line 9 :column nil
       :ref \"pass:nowhere\" :canonical \"pass:creeds/nicene\"
       :book \"pass:creeds/quoting\" :library \"pass\"}

  - `:level` is `:error`, `:warning` or `:info`; a diagnostic that names
    none is a warning, as a build prints it.
  - `:file` is where the problem is: the diagnostic's own `:source-file`,
    else the file it names (`:file`, `:path`, the first of `:paths`),
    else -- for a library-level problem -- the library's root. Shown
    relative to `opts`' `:base-dir` when it lies inside it, else as
    recorded (absolute, for a library read from a directory file): a path
    an editor's quickfix list opens from the directory the command ran in.
    nil when the problem has no file at all (a generated book's own
    resolver diagnostic): the item still names its `:book`.
  - `:line` is the 1-based line, or nil. `:column` is the diagnostic's
    `:source-column` (an edge's `:position :column`) when it has one, else
    nil -- never an invented one (TASK-248). A book reference's
    diagnostics, and the quote problems derived here, carry the column
    of the `@` of its `@{...}` or the `!` of its `!quote`, in its own file
    (an `!include`d chapter's included file), counted in code points; one
    the parser could not place on its line (one inside a citation's
    prefix or suffix) has none. A
    book's own resolver diagnostics are placed on a line only.
  - `:message` is the diagnostic's message without what the item already
    says separately: the `file:line:col: `, `file:line: ` (or `file: `)
    prefix a located diagnostic carries and the `book reference \"...\": `
    prefix a reference's carries.
  - `:ref` is the reference as written, `:canonical` the canonical id or
    reference of its target when known, `:book` the canonical id of the
    book whose record holds the diagnostic (nil on a library's), and
    `:library` the library.

  Items are sorted by file, then line (problems with no file last,
  ordered by book or library), then type, ref and message, so the order
  depends on nothing but the data.

  `:summary` counts `:problems` (every item), `:errors`, `:warnings` and
  `:info`, and `:books` (the book records in the graph). `exit-status` is
  0 when there is no error or warning -- infos do not count -- else 1.

  ## The text form

  `render-text` prints one `render-line` per item and `render-summary`.
  The items' text comes from libraries -- file names, references as
  written, a generated shelf's exception message -- so every part of a
  line is `escape-text`ed: a newline, an ANSI escape or a bidi override
  in it is printed as `\\n`, `\\u001b` or `\\u202e` (and a backslash as
  `\\\\`), and can neither fake a second problem line in an editor's
  quickfix list, nor drive the terminal, nor make a file name display
  differently from the one it opens.

  ## The JSON envelope (a stable contract)

  `render-json` prints one line:

      {\"version\":1,
       \"diagnostics\":[{\"type\":...,\"level\":...,\"message\":...,\"file\":...,
                        \"line\":...,\"column\":...,\"ref\":...,\"canonical\":...,
                        \"book\":...,\"library\":...}, ...],
       \"summary\":{\"problems\":...,\"errors\":...,\"warnings\":...,\"info\":...,
                   \"books\":...}}

  with the keys in that order and every value a string, number or null.
  `version` changes only when a field is removed or changes meaning;
  adding a field does not change it."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [haselnuss.book-ref :as book-ref]
            [haselnuss.library.graph :as graph]
            [haselnuss.library.graph.text :as text]
            [haselnuss.resolver :as resolver]))

(def report-version
  "The `version` of the report and its JSON envelope."
  1)

;; --- normalising one diagnostic ----------------------------------------------

(def ^:private level-order {:error 0 :warning 1 :info 2})

(defn- level
  [diagnostic]
  (let [l (:level diagnostic)]
    (if (contains? level-order l) l :warning)))

(defn- strip-prefix
  [^String s prefix]
  (if (and (string? prefix) (str/starts-with? s prefix)) (subs s (count prefix)) s))

(defn- diagnostic-file
  [d]
  (some #(when (and (string? %) (not (str/blank? %))) %)
        [(:source-file d) (:file d) (:path d) (first (:paths d))]))

(defn- item
  "The report item of `diagnostic`, found on the record of `book` (a
  canonical id string, or nil) of library `lib-name`; `fallback-file` is
  where a diagnostic naming no file is placed (a library's root), or nil."
  [diagnostic {:keys [book lib-name fallback-file base-dir]}]
  (let [source-file (:source-file diagnostic)
        source-line (:source-line diagnostic)
        source-column (:source-column diagnostic)
        written (when (string? (:ref diagnostic)) (:ref diagnostic))
        file (or (diagnostic-file diagnostic) fallback-file)
        line (when (pos-int? source-line) source-line)
        message (str (:message diagnostic))
        ;; `path:line:col: ` (a located parse error's) or `path:line: ` (a
        ;; build's, which carries no column even where the diagnostic does)
        location-prefix (when (string? source-file)
                          (let [at-line (str source-file (when (some? source-line) (str ":" source-line)))
                                at-column (when (and (some? source-line) (some? source-column))
                                            (str at-line ":" source-column ": "))]
                            (if (and at-column (str/starts-with? message at-column))
                              at-column
                              (str at-line ": "))))]
    {:type (:type diagnostic)
     :level (level diagnostic)
     :message (-> message
                  (strip-prefix location-prefix)
                  (strip-prefix (when written (str "book reference " (pr-str written) ": "))))
     :file (text/display-path file base-dir)
     :line (when file line)
     :column (when (and file line (pos-int? (:source-column diagnostic))) (:source-column diagnostic))
     :ref written
     :canonical (when (string? (:canonical diagnostic)) (:canonical diagnostic))
     :book book
     :library lib-name}))

;; --- quote cycles and nesting, from the edges --------------------------------

(defn- target-id [edge] (get-in edge [:to :id]))

(defn- within? [order [first-order last-order]] (<= first-order order last-order))

(defn- contains-edge?
  "True when the passage the quote `edge` takes holds the reference
  `next-edge` (written in the quoted book), from the quoted book's record
  (`haselnuss.library.graph`, `:label-spans` and `:from :order`): a
  whole-book quote holds every reference; a label those within its
  block's span, however deeply nested; a range `from..to` those from the
  start of `from` through the end of `to`, unlabelled blocks between
  included. A locator passage is the book's own to interpret, so it holds
  nothing known. A record without spans (built by hand or by an older
  scanner) falls back to a reference directly under the label (or under
  either end of the range)."
  [{:keys [spans]} edge next-edge]
  (let [passage (get-in edge [:to :passage])
        book-spans (get spans (target-id edge))
        order (get-in next-edge [:from :order])
        nearest (get-in next-edge [:from :passage])]
    (case (:form passage)
      nil (nil? passage)
      :label (let [span (get book-spans (:label passage))]
               (if (and span (nat-int? order))
                 (within? order span)
                 (= nearest (:label passage))))
      :range (let [from-span (get book-spans (:from passage))
                   to-span (get book-spans (:to passage))]
               (if (and from-span to-span (nat-int? order))
                 (within? order [(first from-span) (second to-span)])
                 (contains? #{(:from passage) (:to passage)} nearest)))
      false)))

(defn- written-id
  "The BookId the quote `edge` writes, or nil when its reference is
  malformed."
  [edge]
  (let [parsed (book-ref/parse-book-ref (get-in edge [:to :written]))]
    (when-not (book-ref/malformed? parsed) (:id parsed))))

(defn- resolved-quote? [edge] (and (= :quote (:kind edge)) (= :resolved (:status edge)) (target-id edge)))

(defn- closing-quote?
  "True when the quote `edge` can be where a build meets a cycle or a
  chain too deep: a build checks both by BOOK before it resolves the
  passage (`haselnuss.resolver`'s `expand-quote`), so a quote whose
  passage does not resolve counts as well as a resolved one (TASK-249).
  Not a malformed one (a build rejects it first), and not one the scan
  already reports as quoting its own book (`:problem :quote-cycle`)."
  [edge]
  (and (= :quote (:kind edge))
       (or (resolved-quote? edge)
           (and (= :dangling (:status edge))
                (not= :quote-cycle (:problem edge))
                (some? (written-id edge))))))

(defn- quoted-by
  "`{book-id [resolved quote edges naming it]}` from `quotes` (`:quotes`
  of `quote-index`), each vector in canonical-id order of the quoting
  book -- a stable sort, so one book's edges keep their order."
  [quotes]
  (update-vals (group-by target-id (for [[_ es] quotes e es] e))
               (fn [es] (vec (sort-by #(book-ref/format-book-id (get-in % [:from :id])) es)))))

(defn- holders
  "The resolved quote edges whose passage holds `edge` (`contains-edge?`):
  one step back up a chain of quotes. Remembered in `index`'s `:holders`
  cache (a mutable map private to one `quote-problems` run), as the
  searches below ask for the same edges' holders many times."
  [index edge]
  (let [compute #(filterv (fn [h] (contains-edge? index h edge)) (get (:quoted-by index) (get-in edge [:from :id])))]
    (if-let [^java.util.Map cache (:holders index)]
      (.computeIfAbsent cache edge (reify java.util.function.Function (apply [_ _] (compute))))
      (compute))))

(defn- strong-components
  "The strongly connected components of the graph with `nodes` and
  successors `(succ node)` (nodes it reaches only through successors
  included), each a vector of its nodes, in the order Tarjan's algorithm
  finishes them: each after every component it reaches. Iterative, so a
  long chain does not exhaust the stack."
  [nodes succ]
  (let [order (java.util.HashMap.)
        low (java.util.HashMap.)
        on-stack (java.util.HashSet.)
        stack (java.util.ArrayDeque.)
        work (java.util.ArrayDeque.)
        out (java.util.ArrayList.)
        counter (volatile! 0)
        visit! (fn [v]
                 (.put order v @counter)
                 (.put low v @counter)
                 (vswap! counter inc)
                 (.push stack v)
                 (.add on-stack v)
                 (.push work [v (.iterator ^Iterable (vec (succ v)))]))
        finish! (fn [v]
                  (.add out (loop [acc []]
                              (let [w (.pop stack)]
                                (.remove on-stack w)
                                (if (= w v) (conj acc w) (recur (conj acc w)))))))]
    (doseq [root nodes
            :when (not (.containsKey order root))]
      (visit! root)
      (while (not (.isEmpty work))
        (let [[v ^java.util.Iterator it] (.peek work)]
          (if (.hasNext it)
            (let [w (.next it)]
              (cond
                (not (.containsKey order w)) (visit! w)
                (.contains on-stack w) (.put low v (min (.get low v) (.get order w)))))
            (do (.pop work)
                (when-let [[u] (.peek work)]
                  (.put low u (min (.get low u) (.get low v))))
                (when (= (.get low v) (.get order v))
                  (finish! v)))))))
    (vec out)))

(defn- neighbours
  "`{vertex #{vertex}}`: the undirected graph with the arcs `arcs` (`[u
  v]` pairs), loops left out; a vertex with no other arc is not in it."
  [arcs]
  (reduce (fn [m [u v]]
            (if (= u v)
              m
              (-> m (update u (fnil conj #{}) v) (update v (fnil conj #{}) u))))
          {} arcs))

(defn- degree-class [d] (min d 2))

(defn- path-room
  "The most vertices a path of distinct vertices starting at `s` can have
  in the undirected graph `nbrs` (`neighbours`) with the vertices in
  `taken` (`s` not one of them) removed, bounded from above: every
  vertex inside the path has two neighbours left, and the one at its
  other end at least one, so it is at most the vertices left with two
  neighbours or more besides `s`, plus `s`, plus one when a vertex left
  has a single neighbour -- or just `s` when no neighbour of it is left.
  `counts` is `{1 n 2 m}`, the vertices of `nbrs` with one neighbour and
  with two or more (`degree-class`); only the neighbours of `taken` are
  looked at again, and only once per set of them in `nbrs` for each
  `cache` (a mutable map, private to one `quote-problems` run, or nil;
  `cache-key` tells the graphs apart in it), as the searches ask with
  one set and many `s` (TASK-260)."
  [nbrs counts [^java.util.Map cache cache-key] taken s]
  (let [removed (into #{} (filter #(contains? nbrs %)) taken)
        degree (fn [v] (let [adjacent (get nbrs v)]
                         (- (count adjacent) (count (filter #(contains? adjacent %) removed)))))
        compute (fn []
                  (let [affected (into #{} (comp (mapcat #(get nbrs %)) (remove #(contains? removed %))) removed)
                        counts (reduce (fn [m t] (update m (degree-class (count (get nbrs t))) dec)) counts removed)]
                    (reduce (fn [m v] (-> m
                                          (update (degree-class (count (get nbrs v))) dec)
                                          (update (degree-class (degree v)) (fnil inc 0))))
                            counts affected)))
        own (degree s)]
    (if (zero? own)
      1
      (let [left (cond
                   (empty? removed) counts
                   cache (.computeIfAbsent cache [cache-key removed] (reify java.util.function.Function (apply [_ _] (compute))))
                   :else (compute))
            left (update left (degree-class own) dec)]
        (+ (get left 2 0) 1 (min 1 (get left 1 0)))))))

(defn- cover
  "A vertex cover of the undirected graph with the arcs `arcs` (`[u v]`
  pairs, loops left out): a set of vertices touching every arc. Greedy,
  the vertex with the most arcs left first: not always the smallest, but
  small where a few vertices carry every arc. Any cover bounds a path:
  each of its vertices touches at most two of the path's arcs."
  [arcs]
  (loop [adj (neighbours arcs)
         acc #{}]
    (if (empty? adj)
      acc
      (let [[v adjacent] (apply max-key (comp count val) adj)]
        (recur (reduce (fn [m n]
                         (let [left (disj (get m n) v)]
                           (if (empty? left) (dissoc m n) (assoc m n left))))
                       (dissoc adj v)
                       adjacent)
               (conj acc v))))))

(defn- chain-bounds
  "What bounds the chains `deep-chain` searches, for every resolved quote
  edge of `index`: `{:component {edge c} :weight [n] :above [n] :books
  [{book-key n}] :spread [n] :cover [#{book-key}] :graph [...]}` (`:graph`
  described below), the vectors indexed
  by component `c`,
  `:spread` the most quoting books of a component that share one library
  and slug. No chain of quote edges starting at an edge and going
  backwards (`holders`) with every quoting book on it distinct is longer
  than its component's `:weight`, its number of quoting books, plus its
  `:above` (TASK-254). The edges a chain can return to form one strongly
  connected component of the `holders` graph, which a chain crosses once
  and in which it takes at most one edge per distinct quoting book;
  `:above` is the longest path over the components above `c`, each
  weighing its number of quoting books (exact where no quotes loop, an
  upper bound where they do), and `:books` counts a component's quoting
  books per library and slug, so that `deep-chain` can leave out the ones
  a chain may no longer take. Components come finished after every one
  they reach, so each is weighed after its holders.

  `:cover` is a vertex cover of each component's book graph (`cover`):
  the part of a chain inside its component is a path of distinct books
  there, each quoting the one below, so it is at most twice as long as
  any cover, plus one -- far shorter than the count of books where one
  small group of books is quoted by and quotes many others (TASK-257).
  `:graph` is that book graph itself, `{:neighbours :counts}` for
  `path-room`: neither bound sees that books quoting and quoted by one
  book only can be at an end of such a path and nowhere else (TASK-260)."
  [index]
  (let [components (strong-components (for [[_ es] (:quotes index) e es] e) #(holders index %))
        ;; c, a component's place in `components`; read-only once built
        component-of (java.util.HashMap.)]
    (loop [c 0
           weight (transient [])
           above (transient [])
           books (transient [])
           spread (transient [])
           covers (transient [])
           graphs (transient [])]
      (if (= c (count components))
        {:component component-of :weight (persistent! weight) :above (persistent! above)
         :books (persistent! books) :spread (persistent! spread) :cover (persistent! covers)
         :graph (persistent! graphs)}
        (let [members (nth components c)
              _ (doseq [m members] (.put component-of m c))
              ids (distinct (map #(get-in % [:from :id]) members))
              src-key #(book-ref/book-key (get-in % [:from :id]))
              inside (for [m members
                           h (holders index m)
                           :when (= c (.get component-of h))]
                       [(src-key h) (src-key m)])
              higher (reduce max 0 (for [m members
                                         h (holders index m)
                                         :let [hc (.get component-of h)]
                                         :when (not= c hc)]
                                     (+ (nth weight hc) (nth above hc))))
              by-key (frequencies (map book-ref/book-key ids))
              nbrs (neighbours inside)]
          (recur (inc c)
                 (conj! weight (count ids))
                 (conj! above higher)
                 (conj! books by-key)
                 (conj! spread (reduce max 0 (vals by-key)))
                 ;; one edge has no arc but a loop
                 (conj! covers (if (next members) (cover inside) #{}))
                 (conj! graphs {:neighbours nbrs
                                :counts (merge {1 0 2 0} (frequencies (map #(degree-class (count %)) (vals nbrs))))})))))))

(defn- on-stack-candidates
  "The books the quote `edge` names for a build's cycle check, which
  compares the BookId as WRITTEN with each book being quoted by library
  and slug, whatever shelf either writes (`book-ref/same-book?`): a
  resolved edge's own target; for one whose passage did not resolve,
  the one book in the graph with the written library and slug. None when
  several books share that library and slug: quoting such a book is
  `:ambiguous-book`, so a build never has it on its quote stack (and a
  build of one by path quotes nothing as itself), and no build meets a
  cycle there -- also when they share even the canonical id, and so are
  one record (a library `:duplicate-slug` names the slug; TASK-250)."
  [index edge]
  (if (resolved-quote? edge)
    [(target-id edge)]
    (let [k (book-ref/book-key (written-id edge))
          books (get (:by-key index) k [])]
      (if (and (= 1 (count books)) (not (contains? (:duplicate-keys index) k))) books []))))

(defn- book-components
  "`{book-id c}`: the strongly connected component of each book in the
  graph whose arcs are the resolved quotes and each closing quote to the
  books it names for the cycle check (`on-stack-candidates`). A quote
  closes a cycle only when a chain of resolved quotes leads from the book
  it names back to its own, so only when both are in one component; and
  every quote of that chain is between books of that component."
  [index]
  (let [succ (fn [id] (concat (map target-id (get (:quotes index) id))
                              (mapcat #(on-stack-candidates index %) (get (:closing index) id))))]
    (into {} (for [[c members] (map-indexed vector (strong-components (keys (:closing index)) succ))
                   id members]
               [id c]))))

(defn- quote-index
  "`{:quotes {book-id [resolved quote edges]} :closing {book-id [quote
  edges a build can meet a cycle or a too-deep chain at]} :quoted-by
  {book-id [resolved quote edges naming it]} :chain-bounds {...}
  :book-component {book-id c} :spans {book-id label-spans} :by-key
  {book-key [book-id ...]} :duplicate-keys #{book-key} ...caches}`
  for every book in `g`, `:duplicate-keys` holding every slug a library
  record's `:duplicate-slug` names. `:quotes` are the edges a build
  expands, so the ones followed; `:closing` adds the quotes whose passage
  did not resolve (`closing-quote?`), which are never followed.
  `:quoted-by` and `:chain-bounds` (see those functions) are what
  `deep-chain` walks, `:book-component` (`book-components`) where
  `cycle-path` looks."
  [g]
  (let [books (graph/indexed-books g)
        edges (into {} (map (fn [id] [id (graph/edges-from g id)])) (keys books))
        libraries (if (satisfies? graph/LibraryIndex g) (graph/indexed-libraries g) {})
        quotes (update-vals edges #(filterv resolved-quote? %))
        index {:quotes quotes
               :closing (update-vals edges #(filterv closing-quote? %))
               :quoted-by (quoted-by quotes)
               :spans (update-vals books #(or (:label-spans %) {}))
               :by-key (update-vals (group-by book-ref/book-key (keys books))
                                    #(vec (sort-by book-ref/format-book-id %)))
               ;; TASK-250: two books under ONE canonical id are one (merged)
               ;; record, so `:by-key` alone cannot see that they collide
               :duplicate-keys (into #{}
                                     (for [[lib-name record] libraries
                                           d (:diagnostics record)
                                           :when (and (= :duplicate-slug (:type d)) (string? (:slug d)))]
                                       [(or (:library d) lib-name) (:slug d)]))
               ;; caches, private to one `quote-problems` run
               :holders (java.util.HashMap.)
               :followers (java.util.HashMap.)
               :to-edge-tables (java.util.HashMap.)
               :path-rooms (java.util.HashMap.)
               :dead-ends (java.util.HashSet.)}]
    (assoc index
           :chain-bounds (chain-bounds index)
           :book-component (book-components index))))

(defn- followers
  "The quote edges inside what `edge` quotes, remembered as `holders` are."
  [index edge]
  (let [compute #(filterv (fn [f] (contains-edge? index edge f)) (get (:quotes index) (target-id edge)))]
    (if-let [^java.util.Map cache (:followers index)]
      (.computeIfAbsent cache edge (reify java.util.function.Function (apply [_ _] (compute))))
      (compute))))

(defn- chain-text [ids] (str/join " -> " (map book-ref/format-book-id ids)))

(defn- cycle-path
  "The shortest chain of books from `y` to `x` through resolved quote
  edges, each inside what the one before quotes, whose last edge holds
  `edge` (`x` quoting `y`, whether or not its passage resolved): `[y ...
  x]`, or nil. Only a chain a build of `y` expands all the way to `edge`
  counts (TASK-256), as a build checks each quote's book against the
  books above it before its depth (`haselnuss.resolver`'s
  `expand-quote`):

  - every book on it distinct, by library and slug: a chain back into a
    book already on it is a cycle the build meets there, before `edge`;
  - at most `haselnuss.resolver/max-quote-depth` edges: the build expands
    them at depths 1, 2, ... and meets `edge` one deeper -- as a cycle
    even past the limit, since the book is checked first -- but stops a
    longer chain too deep before `edge`.

  Iterative deepening, depth-first in edge order, so the first shortest
  chain; each edge is cut once the fewest edges from it to `edge`
  (breadth-first backwards, ignoring distinctness, so never too many)
  exceed the edges left, so a quote no chain returns from costs nothing.
  That table is grown one level per deepening step, as a step with `k`
  edges cuts every edge more than `k` away, and holds only edges between
  books of the component of `x` and `y` (`:book-component`), where every
  such chain runs; `y` outside it has none (TASK-257)."
  [index edge y]
  (let [x (get-in edge [:from :id])
        limit resolver/max-quote-depth
        component (get (:book-component index) x)
        in-component? #(= component (get (:book-component index) (get-in % [:from :id])))]
    (when-let [last-edges (and (some? component)
                               (= component (get (:book-component index) y))
                               (not-empty (filterv in-component? (holders index edge))))]
      (let [;; {e n}: n the fewest edges from e (itself included) to `edge`,
            ;; for the n up to `levels`; `frontier` the edges one further.
            ;; It depends on `edge` only through `last-edges`, so the
            ;; quotes of one passage of `x` share it.
            {:keys [^java.util.HashMap to-edge frontier levels]}
            (.computeIfAbsent ^java.util.Map (:to-edge-tables index) last-edges
                              (reify java.util.function.Function
                                (apply [_ _] {:to-edge (java.util.HashMap.)
                                              :frontier (volatile! last-edges)
                                              :levels (volatile! 0)})))
            grow! (fn [k]
                    (while (and (< @levels k) (seq @frontier))
                      (let [n (vswap! levels inc)
                            fresh (into [] (comp (distinct) (remove #(.containsKey to-edge %))) @frontier)]
                        (doseq [e fresh] (.put to-edge e n))
                        (vreset! frontier (into [] (comp (mapcat #(holders index %)) (filter in-component?)) fresh)))))
            step (fn step [e books k]
                   (let [t (target-id e)]
                     (when (and (<= (or (.get to-edge e) (inc limit)) k)
                                (not-any? #(book-ref/same-book? t %) books))
                       (if (and (= x t) (contains-edge? index e edge))
                         (conj books t)
                         (some #(step % (conj books t) (dec k)) (followers index e))))))]
        (some (fn [k] (grow! k) (some #(step % [y] k) (get (:quotes index) y)))
              (range 1 (inc limit)))))))

(defn- deep-chain
  "A chain of `n` resolved quote edges above `edge` -- the last one
  holding `edge`, each holding the next, every book on it distinct and
  none of them the book `edge` writes (both by library and slug, as a
  build compares) -- as its `n + 1` books `[host ... (edge's source)]`, or
  nil. Depth-first, backwards, `n` steps deep at most; the first chain
  in canonical-id order, so the answer is stable. A holder whose bound
  (`chain-bounds`) is under the steps left is skipped: no chain through it
  is long enough, so skipping it changes no answer, only how long finding
  one takes on densely quoting libraries (TASK-254). The bound leaves out
  of the holder's component the books the chain may no longer take -- the
  ones already on it and the one `edge` names -- save one for a host built
  by path that may be one of them (TASK-257). The bounds count chains
  whose ids are distinct, a superset of these, so they stay upper bounds.

  Such a chain is exactly a build of its host reaching `edge`: a build
  checks a quote for a cycle (its book already on the chain above it)
  before its depth (`haselnuss.resolver`'s `expand-quote`), so it expands
  each quote of the chain -- each names a book not yet on it, at a depth
  within the limit -- and meets `edge` past the limit, as no cycle. A
  chain that passes a book twice, or through the book `edge` names, is
  one the build stops earlier, at a cycle; it is not searched. So `edge`
  may close a cycle in other builds and still be too deep in this one
  (TASK-256).

  The host is on the chain only when the build knows it as a book (`:self`
  of a build by `lib:` reference). One of several books sharing a library
  and slug (`:ambiguous-book` by reference, or a `:duplicate-slug`) is
  built by path, so without it: as the host it may be the book `edge`
  names, or share a key with another book on the chain, and the build
  still meets `edge` too deep rather than as a cycle. No quote resolves
  to such a book, so it is never further down a chain, and the chain's
  ids stay distinct: `:chain-bounds` still holds."
  [index edge n]
  (let [target-key (book-ref/book-key (or (target-id edge) (written-id edge)))
        {:keys [component weight above spread] component-books :books covers :cover graphs :graph} (:chain-bounds index)
        ambiguous-key? (fn [k]
                         (or (contains? (:duplicate-keys index) k)
                             (< 1 (count (get (:by-key index) k)))))
        ;; true when a chain from `p` up can take `k` edges with `taken`
        ;; (book keys) off limits. Its part inside `p`'s component has at
        ;; most the component's other quoting books, and at most twice
        ;; the rest of its `:cover`, plus one, and at most the
        ;; `path-room` from `p`'s book in the component's book graph
        ;; without `taken`; one more for a host built by path that may be
        ;; one of the books off limits; the components above add theirs
        ;; (TASK-257: a small library quoting itself all over, or a few
        ;; books quoting and quoted by many, has no long enough chain,
        ;; and the plain bound does not see it; TASK-260: nor do these
        ;; two where a group of books quoting each other has one that
        ;; also quotes and is quoted by many books quoting nothing else)
        enough? (fn [p taken k]
                  (let [c (get component p)
                        higher (get above c 0)
                        books (get component-books c)
                        cover-size (count (get covers c))
                        n-taken (count taken)
                        slack #(if (some (fn [t] (and (contains? books t) (ambiguous-key? t))) taken) 1 0)]
                    ;; most often each bound minus as many books as can be
                    ;; off limits is enough, and nothing need be looked up
                    (and (or (<= k (- (+ (get weight c 0) higher) (* n-taken (get spread c 0))))
                             (let [off-limits (reduce (fn [n t] (+ n (get books t 0))) 0 taken)]
                               (<= k (+ (- (get weight c 0) off-limits) higher
                                        (if (pos? off-limits) (slack) 0)))))
                         (or (<= k (+ (* 2 (- cover-size n-taken)) 1 higher))
                             (let [covered (count (filter (get covers c) taken))]
                               (<= k (+ (* 2 (- cover-size covered)) 1 higher (slack)))))
                         (or (<= k (inc higher))
                             (let [s (book-ref/book-key (get-in p [:from :id]))
                                   {nbrs :neighbours counts :counts} (get graphs c)]
                               ;; `p`'s own book off limits: turned down
                               ;; below, or a host built by path (k = 1)
                               (or (contains? taken s)
                                   (<= k (+ (path-room nbrs counts [(:path-rooms index) c] taken s)
                                            higher (slack)))))))))
        ;; `taken`: the keys of `books` and `target-key`, the ones a book
        ;; on the chain may not have. Whether a chain goes on from `e`
        ;; depends on `e`, `taken` and `k` only -- not on the order the
        ;; books were taken in -- so a search that found none is
        ;; remembered (`:dead-ends`, across edges) and not repeated: the
        ;; orderings of one set of books, which a library with many more
        ;; books than its longest chain has many of, cost one search
        ;; (TASK-257). Skipping it skips no chain, so the first found is
        ;; the same.
        ;; Searches two steps from the end cost little and are not
        ;; remembered, as remembering costs a hash of `taken`.
        ^java.util.Set dead-ends (:dead-ends index)
        step (fn step [e books taken k]
               (let [remember? (and dead-ends (< 2 k))]
                 (cond
                   (zero? k) books
                   (and remember? (.contains dead-ends [e taken k])) nil
                   :else
                   (or (some (fn [p]
                               (let [src (get-in p [:from :id])
                                     src-key (book-ref/book-key src)
                                     off-chain? (and (= 1 k) (ambiguous-key? src-key))]
                                 (when (and (enough? p taken k)
                                            (or off-chain? (not (contains? taken src-key))))
                                   (step p (into [src] books) (conj taken src-key) (dec k)))))
                             (holders index e))
                       (when remember?
                         (.add dead-ends [e taken k])
                         nil)))))
        source (get-in edge [:from :id])]
    ;; not a set literal: a book of an ambiguous slug quoting that slug
    ;; (`:ambiguous-book`, TASK-259) has both keys the same
    (step edge [source] (conj #{target-key} (book-ref/book-key source)) n)))

(defn quote-problems
  "`[[book-id diagnostic] ...]`: a `:quote-cycle` for each quote edge
  that a build of some book in its cycle reports, and a `:quote-too-deep`
  for each one a build reaches nested deeper than
  `haselnuss.resolver/max-quote-depth` -- both for one edge that builds of
  different books meet differently (TASK-256) -- each placed at the edge's
  `!quote` with the message a build gives it. See `contains-edge?` for
  what is followed: resolved quotes only, as a build expands nothing
  else. A quote whose passage did not resolve is still where a build
  meets a cycle or a chain too deep -- it checks both by book before
  resolving -- so it can end one (`closing-quote?`); the book it names is
  matched by library and slug (`on-stack-candidates`), and the item
  names the book found on the cycle as its `:canonical`. Such a quote
  also carries its own dangling diagnostic, from the scan: a build of its
  own book reports that one."
  [g]
  (let [index (quote-index g)]
    (vec
     (for [[_ id edges] (sort-by first (map (fn [[id es]] [(book-ref/format-book-id id) id es]) (:closing index)))
           edge edges
           :let [written (get-in edge [:to :written])
                 [target looped] (or (some (fn [y] (when-let [path (cycle-path index edge y)] [y path]))
                                           (on-stack-candidates index edge))
                                     [(target-id edge) nil])
                 {:keys [file line column]} (:position edge)
                 ;; the book found on the cycle names a dangling quote's
                 ;; cycle only; its too-deep item names what the edge does
                 place (fn [d canonical]
                         (cond-> (assoc d :level :warning :ref written)
                           canonical (assoc :canonical (book-ref/format-book-id canonical))
                           (string? file) (assoc :source-file file)
                           (pos-int? line) (assoc :source-line line)
                           (pos-int? column) (assoc :source-column column)))
                 ;; a quote at depth max+1 has max quotes above it; searched
                 ;; whether or not the quote also closes a cycle, since a
                 ;; build of another host can meet it too deep (TASK-256)
                 deep (deep-chain index edge resolver/max-quote-depth)]
           problem [(when looped
                      (place {:type :quote-cycle
                              :cycle (mapv book-ref/format-book-id (conj looped target))
                              :message (str "quote cycle: !quote " written
                                            " names a book that is already being quoted, via "
                                            (chain-text (conj looped target)))}
                             target))
                    (when deep
                      (place {:type :quote-too-deep
                              :depth (count deep)
                              :message (str "!quote " written " is nested " (count deep)
                                            " quotes deep, more than the " resolver/max-quote-depth
                                            " allowed, via " (chain-text deep))}
                             (target-id edge)))]
           :when problem]
       [id problem]))))

;; --- the report --------------------------------------------------------------

(defn- sort-key
  [{:keys [file line message book library] :as it}]
  [(if file 0 1) (or file book library "") (or line 0) (str (:type it)) (str (:ref it)) message])

(defn check-report
  "The `--check` report of the graph `g` (see the namespace docstring):
  `{:version :diagnostics [item ...] :summary {...}}`. Pure: reads only
  `g`. `opts`: `:base-dir`, an absolute folder paths are shown relative
  to (the CLI passes its working directory); nil shows them as recorded."
  ([g] (check-report g {}))
  ([g {:keys [base-dir]}]
   (let [books (graph/indexed-books g)
         libraries (if (satisfies? graph/LibraryIndex g) (graph/indexed-libraries g) {})
         book-items (for [[id record] books
                          d (:diagnostics record)]
                      (item d {:book (book-ref/format-book-id id) :lib-name (:library id)
                               :base-dir base-dir}))
         derived (for [[id d] (quote-problems g)]
                   (item d {:book (book-ref/format-book-id id) :lib-name (:library id)
                            :base-dir base-dir}))
         library-items (for [[lib-name record] libraries
                             d (:diagnostics record)]
                         (item d {:lib-name lib-name :fallback-file (:root record)
                                  :base-dir base-dir}))
         items (vec (sort-by sort-key (concat book-items derived library-items)))
         counts (frequencies (map :level items))]
     {:version report-version
      :diagnostics items
      :summary {:problems (count items)
                :errors (get counts :error 0)
                :warnings (get counts :warning 0)
                :info (get counts :info 0)
                :books (count books)}})))

(defn exit-status
  "0 when `report` holds no error or warning (infos do not count), else 1."
  [report]
  (let [{:keys [errors warnings]} (:summary report)]
    (if (pos? (+ errors warnings)) 1 0)))

;; --- rendering ---------------------------------------------------------------

(defn- where
  [{:keys [file line column book library]}]
  (cond
    (and file line column) (str file ":" line ":" column)
    (and file line) (str file ":" line)
    file file
    :else (or book library)))

(defn render-line
  "One item as a compiler-style line, `file:line:col: level: [type] ref:
  message` (`:col` only when the column is known, `ref: ` only for a
  reference's problem; the book or library in place of `file:line` for
  one with no file). Every part is
  `escape-text`ed, so the item is exactly one line however its text was
  written."
  [{:keys [message] :as it}]
  (str (text/escape-text (where it)) ": " (name (:level it)) ": [" (text/escape-text (name (:type it))) "] "
       (when-let [written (:ref it)] (str (text/escape-text written) ": "))
       (text/escape-text message)))

(defn render-summary
  "`N problems (E errors, W warnings, I info) in B books`."
  [{{:keys [problems errors warnings info books]} :summary}]
  (str (text/plural problems "problem") " (" (text/plural errors "error") ", "
       (text/plural warnings "warning") ", " info " info) in " (text/plural books "book")))

(defn render-text
  "`report` for a terminal or an editor's quickfix list: one
  `render-line` per item, then `render-summary`, each line ending in a
  newline."
  [report]
  (str (str/join (map #(str (render-line %) "\n") (:diagnostics report)))
       (render-summary report) "\n"))

(defn- json-item
  [{:keys [message file line column canonical book library] :as it}]
  (array-map "type" (name (:type it)) "level" (name (:level it)) "message" message
             "file" file "line" line "column" column "ref" (:ref it) "canonical" canonical
             "book" book "library" library))

(defn render-json
  "`report` as its versioned JSON envelope (the namespace docstring has
  the contract), one line ending in a newline."
  [{:keys [version diagnostics summary]}]
  (let [{:keys [problems errors warnings info books]} summary]
    (str (json/write-str (array-map "version" version
                                    "diagnostics" (mapv json-item diagnostics)
                                    "summary" (array-map "problems" problems "errors" errors
                                                         "warnings" warnings "info" info
                                                         "books" books))
                         :escape-slash false :escape-unicode false)
         "\n")))
