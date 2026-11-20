(ns haselnuss.library.graph.rebuild
  "Rebuild dependencies over the reference graph (doc-11, \"Rebuild
  dependencies\"; TASK-239): which books' output a change to some books,
  or to some files, can alter. `refresh` uses `affected-by` for its
  `:rebuild` set; a future daemon uses `affected-by-files` on a file
  event. `narrowed` (TASK-267) narrows a refresh's `:rebuild` set by
  content, for a site build: see its docstring.

  Written only against the portable `ReferenceGraph` operations
  (`edges-to` and `books-for-file`) -- no backend internals, no
  `GraphQuery` -- so every backend gets these for free.

  ## The rule

  Write `quote*(S)` for S plus, repeated to a fixed point, the source of
  every `:quote` edge into a book already included (A quotes B quotes C:
  `quote*({C})` is {A B C}); a cycle terminates because a book is visited
  once. Then:

      CHANGED = quote*(inputs)
      L       = the source of every :link or dangling edge into CHANGED
      result  = quote*(CHANGED ∪ L)

  Equivalently, Y is in the result for an input B exactly when an edge
  path Y -> ... -> B matches `quote* (any edge)? quote*`.

  - A quote dependent transcludes the changed content, so it changes and
    so does everything quoting it.
  - A `:link` dependent's link text uses the target's title and
    numbering; a DANGLING dependent (either kind) names a book or label
    that did not exist and may now resolve.
  - Those dependents' quoters follow too: a book read for quoting leaves
    its own `@{…}` unresolved and the QUOTING host resolves them
    (`haselnuss.library.passage/context`, `haselnuss.resolver`'s
    book-ref step), so Y quoting X, which links to B, shows B's current
    title. This over-approximates: a quoter of X is rebuilt even when the
    passage it quotes does not contain the link. Records now say where
    each reference sits (`:label-spans` and an edge's `:from :order`,
    TASK-242, which `--check` uses to follow quote cycles), but
    `affected-by` deliberately does not use them: rebuilding a quoter
    too many is harmless, and missing one is not. No further link hop is taken from L or its quoters: a
    link shows its target's title and number, which a link elsewhere in
    the target never changes.
  - A DANGLING `:quote` edge continues `quote*` like a resolved one: its
    target may now resolve and be transcluded.

  Link and dangling dependents are taken from every CHANGED book, not
  only the inputs. That is a harmless over-approximation: quoted content
  has its ids stripped and is never numbered (`haselnuss.resolver`,
  quote expansion), so transcluding changed content does not change the
  title or number a link to the quoting book shows.

  \"Into a book\" is `edges-to` with the book's id, i.e.
  `graph/edge-matches-target?`: a resolved edge into exactly that
  canonical id, and a dangling edge whose written library and slug name
  it -- shelf ignored, and also when it carries a `:to :id` (a dangling
  `:unknown-label`). Ids are canonical. A shelf-less id (the short form,
  or a book at its library's root) also reaches resolved edges into a
  book sharing its slug on another shelf: for a rebuild set that is a
  safe over-approximation, never a missed rebuild.

  An input id need not be indexed: a deleted book still has dependents
  (edges into it stay until their sources are rescanned), and is itself
  part of the answer."
  (:require [haselnuss.library.graph :as graph]))

(defn- sources
  "The `:from :id` of every edge in `edges`."
  [edges]
  (into #{} (map (comp :id :from)) edges))

(defn- quote-closure
  "`quote*`: `seen` (already closed) plus `frontier` plus, to a fixed
  point, the source of every `:quote` edge (resolved or dangling) into
  them. Also answers the sources of every edge into each book it
  visits, as `:any`, so a caller needs no second pass."
  [g seen frontier]
  ;; `frontier` is a vector used as a stack: a closure does not depend on
  ;; the order books are visited in, and `(concat more fresh)` here once
  ;; nested one lazy seq per visited book, which overflowed the stack on
  ;; the 3,000 linkers of a hub note (TASK-267)
  (loop [closed (into seen frontier)
         frontier (into [] (remove seen) (set frontier))
         any #{}]
    (if-let [id (peek frontier)]
      (let [incoming (graph/edges-to g id)
            fresh (into [] (remove closed) (sources (filter #(= :quote (:kind %)) incoming)))]
        (recur (into closed fresh)
               (into (pop frontier) fresh)
               (into any (sources incoming))))
      {:closed closed :any any})))

(defn affected-by
  "The rebuild set for a change to `book-ids` (canonical BookIds,
  indexed or not) in the graph `g`: `quote*(CHANGED ∪ L)`, where CHANGED
  is the books' quote closure and L the one-hop `:link` and dangling
  dependents of CHANGED. See the namespace docstring. A set of BookIds."
  [g book-ids]
  (let [{changed :closed one-hop :any} (quote-closure g #{} book-ids)]
    (:closed (quote-closure g changed (remove changed one-hop)))))

;; --- narrowing by content (TASK-267) ------------------------------------------

(defn- content-key
  "What a book's own page is made from, as far as its record tells:
  its `:document-hash` where it has one, else its `:hash`, and its
  `:labels`."
  [record]
  [(or (:document-hash record) (:hash record)) (:labels record)])

(defn- resolutions
  "`{[kind written] #{[to-id status problem digest]}}` over `edges`: how
  each reference, as written, resolved."
  [edges]
  (reduce (fn [m {:keys [kind to status problem digest]}]
            (update m [kind (:written to)] (fnil conj #{}) [(:id to) status problem digest]))
          {}
          edges))

(defn- resolution-changed?
  "True when a reference written in both `old-edges` and `new-edges`
  resolves differently in them. A reference only one of them has is
  ignored: the book's own text changed, which its content key shows, and
  a quote of a passage that holds it changed too, which its quoter's
  own edge digest shows."
  [old-edges new-edges]
  (let [before (resolutions old-edges)
        after (resolutions new-edges)]
    (boolean (some (fn [[k v]] (and (contains? after k) (not= v (get after k)))) before))))

(defn narrowed
  "`refresh-result`'s `:rebuild` set (`haselnuss.library.graph.scan/
  refresh` of the graph `g`) narrowed by content (doc-12, \"What gets
  rebuilt\" step 3 and \"Scale\" consequence 4; TASK-267): the books whose
  page a build could render differently. A set of BookIds.

  `g` is the graph the refresh started from -- for a site build, the one
  its saved state loaded (`haselnuss.library.state`), so the saved
  document and `:labels` hashes are the ones on its records and the
  caller need not keep its own. The result's `:graph` is the refreshed
  graph and `:changes` what the refresh did; a result without `:changes`,
  or with `{:full? true}` (the directory file changed), is not narrowed.

      CHANGED  = the vanished books, the new ones, and every rescanned
                 book whose content key changed: its `:document-hash`
                 (a generated book's own document), else its `:hash`
                 (its files' bytes), and its `:labels`
      RESOLVED = the rescanned or rewritten books with a reference,
                 written before and after, that now resolves
                 differently: another target, status or problem, or
                 another `:digest` -- for a link, the target passage's
                 title, canonical reference or URL; for a quote, those
                 or its blocks
      result   = CHANGED ∪ quote*(RESOLVED), quote* over both graphs

  - A book whose content key is the same produces the same page from the
    same references, so one edited verse in a shared `:data` file marks
    only the book that holds it, however many books the file feeds.
  - A link's page shows the target's title, number and URL, never its
    body (previews live in the target's `previews.json`), so a body edit
    leaves its linkers alone and a title or number change reaches every
    one of them, through the link's `:digest`.
  - A quote copies the passage in, so its digest covers the blocks; a
    book that quotes a book whose page changed for any reference
    reason (a quote of a quote, a link inside the quoted text) is
    rebuilt too, transitively and over-approximately (any passage), as
    in `affected-by`. A quoter of a book that changed only in its own
    text needs no quote*: its own edge into it was resolved again and
    its digest shows whether the quoted passage changed.

  `refresh` re-resolves every edge whose resolution it may have changed,
  so its graph equals a fresh scan's; that is what makes comparing edges
  enough. Over-inclusion is harmless: a record without a
  `:document-hash`, or edges without a `:digest` (a state saved before
  either existed), only rebuild more."
  [g {g2 :graph :keys [rebuild changes]}]
  (if (or (nil? g2) (not (map? changes)) (:full? changes))
    (set rebuild)
    (let [{:keys [rescanned vanished rewritten]} changes
          before (graph/indexed-books g)
          after (graph/indexed-books g2)
          content-changed? (fn [id]
                             (let [a (get before id) b (get after id)]
                               (or (nil? a) (nil? b) (not= (content-key a) (content-key b)))))
          changed (into (set vanished) (filter content-changed?) rescanned)
          resolved (into #{}
                         (filter #(resolution-changed? (graph/edges-from g %) (graph/edges-from g2 %)))
                         (into (set rescanned) rewritten))]
      (-> changed
          (into (:closed (quote-closure g #{} resolved)))
          (into (:closed (quote-closure g2 #{} resolved)))))))

(defn affected-by-files
  "The rebuild set for a change to the files `paths` (strings, compared
  exactly as `books-for-file` does) in the graph `g`:

      {:books     #{BookId}   ; affected-by over every book claiming a path
       :unclaimed #{path}}    ; the paths no indexed book claims

  An unclaimed path is the caller's signal to re-list its library (a new
  book, or a file nothing includes yet). `:books` is `affected-by`'s
  plain set, so the two compose.

  The comparison is exact, as `books-for-file`'s: a caller holding raw
  paths (a file watcher's) passes each through
  `haselnuss.library.graph.scan/lookup-paths` first -- `(mapcat
  scan/lookup-paths raw)` -- so a path through a symlinked folder, or a
  symbolic link that was retargeted, matches what `:files` holds
  (TASK-250). This namespace stays backend- and scanner-agnostic, so it
  does not do that itself."
  [g paths]
  (let [claims (into {} (map (juxt identity #(graph/books-for-file g %))) paths)]
    {:books (affected-by g (into #{} (mapcat val) claims))
     :unclaimed (into #{} (comp (filter (comp empty? val)) (map key)) claims)}))
