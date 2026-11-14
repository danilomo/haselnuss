(ns haselnuss.library.graph.rebuild
  "Rebuild dependencies over the reference graph (doc-11, \"Rebuild
  dependencies\"; TASK-239): which books' output a change to some books,
  or to some files, can alter. `refresh` uses `affected-by` for its
  `:rebuild` set; a future daemon uses `affected-by-files` on a file
  event.

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
  (loop [closed (into seen frontier)
         frontier (seq (remove seen (set frontier)))
         any #{}]
    (if-let [[id & more] (seq frontier)]
      (let [incoming (graph/edges-to g id)
            fresh (remove closed (sources (filter #(= :quote (:kind %)) incoming)))]
        (recur (into closed fresh)
               (concat more fresh)
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
