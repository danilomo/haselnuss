(ns haselnuss.select
  "The node-selector half of a scripted transformation rule (`m-13`,
  TASK-96): `(matches? selector node)` decides whether `node` -- a
  `haselnuss.ast` Block or Inline, in practice reached while a
  transformation walks a parsed Document -- is one a rule should touch.

  A small declarative vocabulary with a function escape hatch, chosen
  deliberately over both extremes: a bare predicate function is correct
  but verbose for the common case (`{:t :list :ordered true}` beats
  `(fn [n] (and (= :list (:t n)) (:ordered n)))`), and a CSS-like
  selector string is a second surface grammar this codebase would then
  have to parse and diagnose on its own, with a hard ceiling on what it
  can express before falling back to a function anyway. A selector is
  exactly one of four shapes:

  - a keyword -- shorthand for `{:t <kw>}`, matching every node of that
    type
  - a map     -- every entry must match (AND)
  - a vector  -- any element (itself a selector) must match (OR)
  - a function -- `(fn [node] -> truthy)`, called directly on the node

  A map key reads the node field of the same name -- `{:t :list
  :ordered true}` and `{:t :section :level 2}` need no special casing at
  all -- UNLESS it is one of four keys naming something that does not
  live at the top of the node (`Attr`, sec4.1, is a nested map, and a
  node's own text is not a field at all -- it is every `:str` leaf
  reachable from the node, flattened):

  - `:id`    -> `(get-in node [:attr :id])`
  - `:class` -> membership in `(get-in node [:attr :classes])`
  - `:prop`  -> a `{k v}` map, every entry matched against
    `(get-in node [:attr :props])`
  - `:text`  -> the node's own flattened plain text (`flatten-text`)

  Every map value, regardless of which key it sits under, is matched by
  the same rule (`match-value`), so no key needs its own comparison
  logic: a set means membership, a regex means `re-find` against the
  string form of the actual value (a keyword's own `name`, e.g.
  `:section` -> `\"section\"`, so an anchored pattern still matches a
  keyword-valued field such as `:t` -- not `(str actual)`'s
  `\":section\"`, which no anchored pattern beginning `^` could ever
  match), a function is applied to the actual value, and anything else
  -- a string, a number, a boolean, a keyword, a vector, nil, ... -- is
  compared with `=`. That single rule is what gives `{:id #\"^fig:\"}`,
  `{:t #\"^section$\"}`, `{:t #{:emph :strong}}` and `{:level (fn [n] (>
  n 2))}` for free, rather than a special case for each. `:class`
  reuses this exact rule too, just applied to every element of the
  node's classes vector in turn (`some`, i.e. OR across classes) rather
  than to the vector as a whole: `{:class \"warn\"}` is plain membership,
  `{:class #{\"warn\" \"info\"}}` is membership in either, `{:class
  #\"^fig-\"}` is any class matching that pattern -- the collection is
  what makes `:class` different from `:id`, not a different matching
  rule.

  A malformed selector -- a shape that is none of the four above, or a
  `:prop` value that is not itself a map -- is refused with an
  `ex-info` naming what was wrong, never silently treated as \"matches
  nothing\": a rule that quietly matches no node is the transformation-
  authoring failure mode hardest to notice on your own, since a rule
  that runs and visibly does nothing looks identical to a rule that
  already did its job.

  Pure data plus functions: no I/O, and no dependency on the walker or
  on any other `haselnuss` namespace -- a leaf namespace, like
  `haselnuss.registry`, testable and reasoned about entirely on its own
  (AC #7). Requires nothing but `clojure.core` and `clojure.string`."
  (:require [clojure.string :as str]))

(defn- regex-subject
  "The string a regex `expected` is matched against by `match-value`:
  `(name actual)` when `actual` is a keyword, so `#\"^section$\"` matches
  the keyword `:section` the way a human reading the pattern would
  expect, rather than `(str actual)`'s `\":section\"` (the leading colon
  Clojure prints for every keyword, `:t` first among them -- the field
  every selector in this vocabulary matches against most often) silently
  defeating an anchored pattern. Every node field in `haselnuss.ast`'s
  registry holds only simple (unqualified) keywords, so this does not
  need to choose between a namespaced keyword's own name and its
  qualified form; `(str actual)` remains the fallback for every
  non-keyword value, unchanged from before."
  [actual]
  (if (keyword? actual) (name actual) (str actual)))

(defn- match-value
  "The one value-matching rule every map key shares (see the namespace
  docstring): `expected` is matched against `actual` by its own kind --
  a set matches by membership (`actual` is one of `expected`'s
  elements), a regex matches by `re-find` against `actual`'s string form
  (`regex-subject` -- a keyword's own `name`, e.g. `:section` -> `\"section\"`,
  so `{:t #\"^section$\"}` matches; `(str actual)` for everything else), a
  function (`fn?`, deliberately narrower than `ifn?` -- a plain vector,
  map, keyword or set `expected` is also `ifn?`, but each of those is
  meant to be compared with `=` or handled by an earlier branch here,
  never invoked as a predicate) is applied to `actual`, and anything
  else is compared with `=`."
  [expected actual]
  (cond
    (set? expected) (contains? expected actual)
    (instance? java.util.regex.Pattern expected) (boolean (re-find expected (regex-subject actual)))
    (fn? expected) (boolean (expected actual))
    :else (= expected actual)))

(declare node-text)

(defn- inlines->text
  "`haselnuss.ast` Inlines (sec4.4) flattened to one plain-text string,
  in order, with NO extra separator inserted between them: a run of
  words already carries its own inter-word whitespace as explicit
  `:space`/`:soft-break`/`:line-break` Inline nodes of its own
  (`haselnuss.parser`), so concatenating their own text contributions
  bare reproduces the original spacing rather than doubling it."
  [inlines]
  (apply str (map node-text inlines)))

(defn- join-parts
  "Non-blank string `parts` joined with a single space -- used to
  combine a node's own structurally distinct regions (a Section's
  heading and its body, a Table's head/rows/caption, a Figure's content
  and caption, two neighbouring Blocks) into one flattened string: no
  AST node marks the boundary between two such regions the way an
  explicit `:space` Inline marks a word boundary, so this supplies a
  separator rather than running them together."
  [& parts]
  (->> parts (remove str/blank?) (str/join " ")))

(defn- blocks->text
  "`haselnuss.ast` Blocks (sec4.3) flattened to one plain-text string,
  each Block's own text joined to its neighbours with `join-parts`:
  unlike Inlines (see `inlines->text`), two neighbouring Blocks (two
  list items, two paragraphs in a Section) carry no AST node of their
  own marking the boundary between them."
  [blocks]
  (apply join-parts (map node-text blocks)))

(defn- cite-item-text
  "One `CiteItem` (sec4.4)'s own contribution: its `:prefix`/`:suffix`
  Inlines (both optional), never its `:key` -- a citation key is an
  identifier into a bibliography, not prose."
  [{:keys [prefix suffix]}]
  (inlines->text (concat prefix suffix)))

(defn- node-text
  "The recursive engine behind `flatten-text`: see that function's own
  docstring for the contract. Kept private and separate from
  `flatten-text` only so the public function can carry the user-facing
  docstring while this one stays a plain `case` dispatch."
  [node]
  (case (:t node)
    ;; Inline (sec4.4).
    :str (:text node)
    (:space :soft-break :line-break) " "
    (:emph :strong :strike :small-caps :sub :sup :span :link :highlight)
    (inlines->text (:inlines node))
    :code (:text node)
    :math-inline (:tex node)
    :image (:alt node)
    :cross-ref (or (:text node) "")
    :cite (apply join-parts (map cite-item-text (:items node)))
    ;; A sticky note's body is prose about the document rather than prose
    ;; in it, but it is prose all the same: `:text` selects on what a node
    ;; says, and a `--transform` rule looking for a phrase should find it
    ;; wherever it was written. Grouped with `:note` for the same reason
    ;; the two share a shape (sec4.4, doc-8 section 2).
    (:note :sticky-note) (blocks->text (:blocks node))

    ;; Block (sec4.3).
    :section (join-parts (inlines->text (:heading node)) (blocks->text (:blocks node)))
    :para (inlines->text (:inlines node))
    :list (blocks->text (apply concat (:items node)))
    :code-block (:text node)
    :math-block (:tex node)
    :figure (join-parts (node-text (:content node)) (inlines->text (:caption node)))
    :table (join-parts (blocks->text (mapcat :blocks (:cells (:head node))))
                       (blocks->text (mapcat (fn [row] (mapcat :blocks (:cells row))) (:rows node)))
                       (inlines->text (:caption node)))
    :block-quote (blocks->text (:blocks node))
    :directive (blocks->text (:blocks node))

    ;; :include's :src and :thematic-break carry no prose at all.
    (:include :thematic-break) ""

    ;; Anything this does not recognize -- including a bare Document,
    ;; which has no :t of its own -- contributes nothing rather than
    ;; throwing: this is a best-effort read for selector matching, not
    ;; a schema-validating pass, mirroring the same permissive default
    ;; `haselnuss.emit.html/plain-text-inline` already uses for the
    ;; identical reason (an unrecognized variant degrading gracefully
    ;; rather than refusing the whole match).
    ""))

(defn flatten-text
  "`node`'s own flattened plain text (the `:text` selector key -- see
  the namespace docstring): every `:str`/`:code`/`:math-inline`/
  `:math-block`/`:code-block` leaf and Image `:alt` reachable from
  `node`, in source order, with structural markup (emphasis, links,
  spans, table cells, list items, ...) stripped and no contribution at
  all from non-prose fields (`:attr`, a Directive's own `:name`, an
  Image/Link's `:src`/`:target`, a CiteItem's own `:key`, ...). Defined
  for every `haselnuss.ast` Block and Inline variant (AC #5)."
  [node]
  (node-text node))

(defn- malformed!
  [msg data]
  (throw (ex-info msg (assoc data :type ::malformed-selector))))

(defn- field-match?
  "Whether `node` matches one `[k expected]` map-selector entry -- the
  four special keys plus the generic \"same-named node field\" default
  (AC #2, #3, #4)."
  [k expected node]
  (case k
    :id (match-value expected (get-in node [:attr :id]))
    :class (boolean (some #(match-value expected %) (get-in node [:attr :classes] [])))
    :prop (do
            (when-not (map? expected)
              (malformed! (str "selector key :prop needs a map value, got " (pr-str expected))
                          {:key :prop :value expected}))
            (let [props (get-in node [:attr :props] {})]
              (every? (fn [[prop-key prop-value]] (match-value prop-value (get props prop-key)))
                      expected)))
    :text (match-value expected (flatten-text node))
    (match-value expected (get node k))))

(defn matches?
  "True when `node` (a `haselnuss.ast` Block or Inline) matches
  `selector` -- see the namespace docstring for the full vocabulary and
  matching rules (AC #1-#5). Throws `ex-info` (`:type
  ::malformed-selector`) rather than returning false for a selector
  that is none of the four recognized shapes, or a `:prop` map entry
  whose own value is not itself a map (AC #6): a malformed selector is
  a bug in the RULE, not evidence the node simply failed to match it,
  and this distinguishes the two instead of collapsing both into the
  same silent `false`.

  A vector's OR is `some`, i.e. short-circuiting: a malformed element
  that sits AFTER one that already matches is never reached and never
  raises, exactly as `(or true (/ 1 0))` never evaluates its second
  form. This is the one honest gap in AC #6's own \"never silently
  matching nothing\" guarantee -- a vector selector that already found a
  genuine match is not \"matching nothing\", so there is no silent
  failure to catch, but a later, unreached element's own malformedness
  stays undetected for that call. It surfaces the moment a call reaches
  a node the earlier element does not match."
  [selector node]
  (cond
    (keyword? selector) (matches? {:t selector} node)
    (map? selector) (every? (fn [[k expected]] (field-match? k expected node)) selector)
    (vector? selector) (boolean (some #(matches? % node) selector))
    (fn? selector) (boolean (selector node))
    :else (malformed! (str "selector must be a keyword, a map, a vector or a function, got "
                           (pr-str selector))
                      {:selector selector})))
