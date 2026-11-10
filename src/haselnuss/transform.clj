(ns haselnuss.transform
  "The core of `m-13` (doc-7, TASK-102): given a parsed Document and a
  sequence of rules, produces the transformed Document plus its
  diagnostics. Pure -- no I/O, no SCI sandbox, no CLI (AC #8) -- so a
  rule's own `:transform` function can be an ordinary Clojure function in
  a test, exactly as much as it can be author code TASK-103 later
  extracts from a sandboxed sidecar file. TASK-103 is what feeds this
  namespace author-written rules; this namespace is what runs them.

  A rule is `{:select :transform :name? :scope?}`:

  - `:select`    -- a `haselnuss.select` selector (TASK-96):
    `(select/matches? select node)` decides whether `node` is one this
    rule touches.
  - `:transform` -- `(fn [node ctx] -> node | nil | (node ...))`, the same
    two-argument shape a sidecar `:emit` renderer already takes
    (`haselnuss.registry`'s own `:emit` docstring), so an author learns
    one calling convention for both mechanisms. `ctx` is `{:path
    :ref-kind :meta}` (AC #7) -- `path`/`ref-kind` are `haselnuss.walk`'s
    own `fold`/`rewrite-post` path-and-reference-kind pair for `node`
    (e.g. `[:blocks 0 :inlines 1]` and `::ast/inline`), and `:meta` is the
    DOCUMENT's own `:meta` (sec4.2), read-only and unchanged by this
    rule's own edits -- a rule sees the document's title/authors/etc. for
    context, but has no way to rewrite them through this ctx.
  - `:name`      -- optional string, used to name this rule in every
    diagnostic or error message it produces; when absent, a label built
    from this rule's own position among `rules` and its `:select` value
    is used instead, so a message is never anonymous.
  - `:scope`     -- optional set of `haselnuss.walk` reference kinds
    (`::ast/block`, `::ast/inline`, ...), defaulting to `default-scope`;
    see that var's own docstring.
  - `:matcher`   -- optional `(fn [node] -> boolean)` deciding the match in
    place of `(select/matches? select node)`, `:select` then serving only
    to name the rule. `haselnuss.extensions.rules` sets it for a script's
    rule whose selector holds script code (a function or a regex), so that
    code runs in the sandbox, under its timeout, rather than on this
    thread (TASK-214); it never takes one from the script itself.

  THE RETURN-VALUE CONTRACT (AC #1) -- what `:transform` hands back is the
  whole vocabulary a rule has for changing the tree:

  - a node map            -> replaces `node`
  - `nil`                 -> deletes it
  - a sequential of nodes -> splices them in where `node` was

  `haselnuss.walk/rewrite-post` (TASK-94) already gives all three at a
  `:many`/`:many-of-many` position -- its own callback returns a vector of
  0, 1 or many nodes, `descend-into-position`'s own `mapcat`. This
  namespace's whole job is translating the user-facing spelling above into
  that vector (`translate-result`), validating every node in it against
  `haselnuss.ast` for the position's own reference kind before it is
  spliced in (`validate-replacement!`, AC #4), and writing the messages
  that name which RULE produced a problem `rewrite-post` itself has no way
  to know (AC #5) -- the walker only ever sees a node, never which
  caller's rule was running when it produced one.

  DELETE AND SPLICE ARE NOT UNIVERSALLY LEGAL (AC #5). A `:one` position
  -- a Figure's `:content` (`ast.clj`'s `:figure` variant), a Document's
  own `:meta` (`ast.clj`'s `::document` entry) -- has nowhere in the map
  shape to put zero or several replacement nodes. `walk/expect-count-one!`
  already refuses any other count there, raising `::walk/arity-error`
  naming the field and the node; this namespace catches that and
  re-raises it naming the RULE too (`rewrap-rule-error`), because
  'position :content of a :figure node rewrote to 0 nodes' does not say
  which of an author's several rules did it. The document root itself is
  the same kind of `:one` position -- `apply-rule`'s own top-level
  `rewrite-post` call has nowhere to put zero or several documents either
  -- but it has no PARENT node for `descend-into-position` to enforce that
  from the outside the way it does for every other `:one` position, so
  `apply-rule` enforces it directly (`expect-document-count-one!`, below)
  rather than leaving the root the one ungoverned position in the schema.

  ONE POST-ORDER PASS PER RULE, RULES IN DECLARATION ORDER (AC #2) -- not
  first-match-wins at each node. `transform-document` reduces `apply-rule`
  over `rules` as given, threading each rule's own output document into
  the next: 'uppercase every heading, THEN drop every draft block' means
  what it says, and each pass stays independently explicable. Within one
  rule's own pass, `rewrite-post`'s post-order contract (children walked
  before the node itself, TASK-94, doc-7 decision 2) is what makes AC #3
  true for free -- a callback's own return value is never re-visited
  within that same call, so a rule that wraps every match in a fresh
  parent (`{:t :block-quote :blocks [node] ...}`) meets that wrapper again
  only on the NEXT rule's pass, not this one's, and terminates. This
  namespace does not re-implement any of that; it supplies the
  `:rewrite-fn` `rewrite-post` calls and trusts its own contract.

  A SELECTOR THAT MATCHES NOTHING IS A DIAGNOSTIC, NOT SILENCE (AC #6):
  `:transform-rule-unmatched`, named after the rule, following
  `:dangling-overlay-target`'s own precedent (`haselnuss.resolver`,
  doc-5) -- a rule that runs and visibly does nothing looks identical to
  one that already did its job, exactly the failure mode an author is
  least likely to notice without being told.

  RUNNING OVER A PARTIAL DOCUMENT IS ALSO A DIAGNOSTIC, NOT SILENCE
  (TASK-106): `--transform` runs BEFORE `resolve/expand-includes`
  (doc-7 decision 1, README.md's own 'Includes are not expanded'), so a
  document's own `!include`d chapters are still unexpanded `:include`
  Blocks (`ast.clj`'s `:include` variant, `{:t :include :src}`) at the
  point every rule here runs -- a selector as broad as `:section` matches
  every section the INCLUDING file spells out directly and silently never
  reaches a single one pulled in from another file, with nothing to tell
  an author their rule saw two thirds of a book, not all of it.
  `transform-document` reports this itself, once per call rather than
  once per rule (every rule in one call sees the identical partial
  document, `apply-rule`'s own per-rule reduce never expands one),
  naming every `:src` still unexpanded so the report is something to act
  on rather than a bare 'this document has includes' -- see
  `unexpanded-includes`/`partial-document-diagnostics`, below. A
  document with no `:include` Blocks at all -- the common case, and every
  fixture in this namespace's own tests before this task -- produces
  none of this, unchanged.

  THE DOCUMENT ROOT, MATCHABLE AND ARITY-CHECKED LIKE ANY OTHER NODE:
  `apply-rule` calls `rewrite-post` once, at `::ast/document`, on the WHOLE
  document, and its `:rewrite-fn` runs `select/matches?` only when
  `ref-kind` is itself IN `:scope` -- `default-scope` never contains
  `::ast/document` or `::ast/meta`, so under the default scope the one
  unconditional callback call `rewrite-post` makes on the Document node
  itself (its own docstring: 'THEN calls ... on the already-rewritten
  node', regardless of `:scope`) simply passes it through unchanged, the
  same rule that already keeps a rule from touching `:meta` at all unless
  a caller deliberately widens `:scope` to include it. But `:scope` is an
  open, rule-supplied set (TASK-103 is what lets a sidecar rule populate
  it), and unlike a keyword/map selector, `haselnuss.select`'s
  function-selector shape (`(fn [node] -> truthy)`) needs no `:t` field to
  match anything, including a bare Document node, once a rule's `:scope`
  widens to include `::ast/document` -- so nothing in the selector
  vocabulary itself rules that match out. A rule that DOES match the
  Document root is bound by the exact same one-node contract every other
  `:one` position has (`expect-document-count-one!`, below); nothing about
  matching the root is special, only WHO enforces its arity, since it has
  no parent for `descend-into-position` to do that from the outside."
  (:require [haselnuss.ast :as ast]
            [haselnuss.select :as select]
            [haselnuss.walk :as walk]))

(def default-scope
  "The reference kinds a rule's own walk descends into when it supplies no
  `:scope` of its own: Block, Inline and Fallback (`haselnuss.walk`'s own
  docstring names exactly this triple as 'reachable now for whichever
  future pass needs it' -- this namespace is that pass). Deliberately
  excludes `::ast/meta`, `::ast/attr`, `::ast/cite-item`, `::ast/cell`,
  `::ast/row` and `::ast/col`: none of those carry the `:t`/`:kind` field
  a `haselnuss.select` selector reads (see this namespace's own
  docstring), so a rule has nothing meaningful to match against one of
  them without deliberately opting in via its own `:scope`."
  #{::ast/block ::ast/inline ::ast/fallback})

(defn- rule-label
  "The string every diagnostic or error message this namespace raises
  names `rule` (at position `index` among the `rules` `transform-document`
  was given, 0-based) by: `rule`'s own `:name` when it supplied one, else
  a label built from `index` and `:select` so a message is never
  anonymous -- `(pr-str select)` is exactly what an author reading their
  own rule literal wrote."
  [index rule]
  (or (:name rule) (str "rule #" (inc index) " " (pr-str (:select rule)))))

(def ^:private ref-kind-schema
  "Every `haselnuss.walk` reference kind mapped to the `haselnuss.ast`
  schema it names -- what `validate-replacement!` checks a rule's own
  returned node against (AC #4): `ast/explain` needs the actual malli
  schema, not merely the reference-kind keyword, to produce a useful
  explanation."
  {::ast/attr ast/Attr
   ::ast/block ast/Block
   ::ast/cell ast/Cell
   ::ast/cite-item ast/CiteItem
   ::ast/col ast/Col
   ::ast/document ast/Document
   ::ast/fallback ast/Fallback
   ::ast/inline ast/Inline
   ::ast/meta ast/Meta
   ::ast/row ast/Row})

(defn- malformed-result!
  "Raises `::malformed-transform-result`, naming `label` (`rule-label`'s
  own string) and `path`, for a `:transform` return value that is none of
  this namespace's own contract (a node map, `nil`, or a sequential of
  nodes -- AC #1): a scalar, a set, or anything else an author's function
  might mistakenly hand back."
  [label path result]
  (throw (ex-info
          (str label " at " path " returned " (pr-str result)
               ", not a node map, nil, or a sequential of nodes")
          {:type ::malformed-transform-result :rule label :path path :result result})))

(defn- translate-result
  "`result` (whatever `rule`'s own `:transform` function returned) turned
  into the vector `walk/rewrite-post` wants at this position (AC #1): a
  node map replaces (`[result]`), `nil` deletes (`[]`), and a sequential
  (a vector, a list, a lazy seq) splices those nodes in, realized eagerly
  so a lazy return value is not left to blow up later, outside this
  call's own stack. Anything else is `malformed-result!` (`label`/`path`
  identify which rule and position produced it)."
  [label path result]
  (cond
    (nil? result) []
    (map? result) [result]
    (sequential? result) (vec result)
    :else (malformed-result! label path result)))

(defn- validate-replacement!
  "Raises `::invalid-transform-result`, naming `label`, `path` and
  `ref-kind`, with `ast/explain-summary`'s own readable account of what is
  wrong folded into the message (AC #4, TASK-111), unless `node` validates
  against the `haselnuss.ast` schema `ref-kind` names (`ref-kind-schema`)
  -- checked BEFORE `apply-rule` ever splices it into the tree, so a typo
  in an author's own node map is reported against the rule that produced
  it rather than surfacing later as an unrelated crash inside an emitter.
  A `ref-kind` this namespace has no schema for (there is none today;
  every `haselnuss.walk` reference kind has a `haselnuss.ast` schema of
  its own) is a safe no-op, not a silent pass -- `get` returns nil, and
  `ast/valid?`/`ast/explain` are simply never reached.

  `ast/explain`'s own raw explanation is folded into the message only
  through `explain-summary` (TASK-111: the raw explanation is roughly
  1500 characters for a typo'd `:t` -- every variant of the schema, an
  anonymous dispatch function, a Java object identity hash -- none of it
  the one fact an author needs), but it is not thrown away: it is set on
  `ex-data`'s own `:explanation` key, matching the convention
  `haselnuss.lower`/`haselnuss.parser` already use for a programmatic
  caller that wants the full malli explain data rather than the message."
  [label path ref-kind node]
  (when-let [schema (get ref-kind-schema ref-kind)]
    (when-not (ast/valid? schema node)
      (let [explanation (ast/explain schema node)]
        (throw (ex-info
                (str label " at " path " returned a node that does not fit a "
                     (name ref-kind) " position: " (ast/explain-summary explanation))
                {:type ::invalid-transform-result :rule label :path path
                 :ref-kind ref-kind :node node :explanation explanation}))))))

(defn- rewrap-rule-error
  "`e` (an `ExceptionInfo` `apply-rule`'s own `rewrite-post` call raised
  that does not already carry a `:rule` of its own) re-thrown with `label`
  folded into both the message and `ex-data`'s own `:rule` key, consistent
  with every other purpose-built failure mode this namespace already
  names its rule for (`malformed-result!`/`validate-replacement!`, both of
  which set `:rule` themselves and so never reach this function).

  Two distinct sources reach here (AC #5, and the reviewer's secondary
  finding on this same namespace):

  - a `::walk/arity-error` -- either `walk/expect-count-one!`'s own (a
    `:one` position, unlike a `:many`/`:many-of-many` one, has nowhere in
    the map shape to put zero or several replacement nodes: see its own
    docstring), or `expect-document-count-one!`'s (below), the identical
    check for the document root itself, which has no PARENT position for
    `walk/expect-count-one!` to protect from the outside;
  - anything else an author's own rule can make this call throw --
    `haselnuss.select/matches?`'s own `::select/malformed-selector` for a
    rule with a missing or mistyped `:select`, or any exception a rule's
    own `:select`/`:transform` function throws itself.

  Either way, the underlying exception's own message already says what
  went wrong; it has no way to know which of an author's several rules
  produced it, since it only ever sees the node, never the caller.
  `ex-data`'s other keys, and its own `:type`, pass through unchanged, so
  a caller already handling a specific `:type` (`::walk/arity-error`,
  `::select/malformed-selector`, ...) still catches it, with `:rule` now
  folded in alongside whatever it already reported."
  [e label]
  (ex-info (str label ": " (ex-message e)) (assoc (ex-data e) :rule label) e))

(defn- expect-document-count-one!
  "`replacements` (the vector `walk/rewrite-post`'s own top-level,
  unconditional callback call on the Document node itself returned) when
  it holds exactly one node, else raises an `::walk/arity-error` shaped
  exactly like `walk/expect-count-one!`'s own (`:field`, `:node`, `:count`
  and `:replacements` keys) -- naming a synthetic `:field` of `:document`,
  since the document root is a `:one` position with no PARENT node for
  `descend-into-position` to arity-check it from the outside the way it
  already protects every other `:one` position in the schema (a Figure's
  `:content`, a Document's own `:meta`).

  Without this, a rule whose own `:scope` includes `::ast/document` can
  match the bare Document node (this namespace's own docstring has the
  full reasoning: a function selector needs no `:t` field to match
  anything) and silently delete or truncate the whole document with no
  error and no diagnostic at all, unlike the identical mistake against
  any other `:one` position. `apply-rule`'s own `catch` recognizes this
  exception's `:type` exactly like `walk/expect-count-one!`'s and
  re-reports it naming the rule via `rewrap-rule-error`, the same as every
  other `:one`-position violation already goes through."
  [document replacements]
  (if (= 1 (count replacements))
    (first replacements)
    (throw (ex-info
            (str "the document root rewrote to " (count replacements)
                 " node(s), expected exactly 1")
            {:type ::walk/arity-error
             :field :document
             :node document
             :count (count replacements)
             :replacements replacements}))))

(defn- rewrite-fn-for
  "The `:rewrite-fn` `apply-rule` hands `walk/rewrite-post` for one rule:
  `(fn [path ref-kind node] -> [node...])`, `fold`'s own visit shape
  (`walk/rewrite-post`'s own docstring), not `rewrite`'s 2-arity one, so
  every diagnostic/error this raises can name WHERE a rule matched.

  Passes `node` through unchanged (`[node]`) whenever `ref-kind` is not in
  `scope` -- under `default-scope` this is what keeps the Document/Meta
  nodes `rewrite-post`'s own unconditional top-level callback call would
  otherwise reach from ever being offered to `select/matches?` at all
  (this namespace's own docstring has the full reasoning), with no
  special case in THIS function needed for either; a rule whose own
  `:scope` deliberately widens to include `::ast/document` is a genuine
  match here, arity-checked by `apply-rule`'s own
  `expect-document-count-one!` once this call returns, not by anything in
  this function. Otherwise runs `(select/matches? select node)`, or the
  rule's own `:matcher` when it has one: no match
  leaves `node` unchanged; a match flips `matched?` (a `volatile!`
  `apply-rule` reads back once its own `rewrite-post` call returns, AC
  #6), calls `(transform node ctx)` (AC #7, `ctx` built once by
  `apply-rule` and reused for every match this rule makes), and returns
  `translate-result`'s own vector, each element of it checked by
  `validate-replacement!` before this function hands it back (AC #4)."
  [{:keys [select matcher transform scope]} label ctx matched?]
  (fn [path ref-kind node]
    (if-not (contains? scope ref-kind)
      [node]
      (if-not (if matcher (matcher node) (select/matches? select node))
        [node]
        (do
          (vreset! matched? true)
          (let [replacements (translate-result label path (transform node (assoc ctx :path path :ref-kind ref-kind)))]
            (doseq [replacement replacements]
              (validate-replacement! label path ref-kind replacement))
            replacements))))))

(defn- apply-rule
  "`document` after one full post-order pass of `rule` (AC #2) -- the
  `index`-th of the `rules` `transform-document` was given (0-based, feeds
  `rule-label`'s own fallback naming). Returns `{:document :diagnostics}`:
  `:diagnostics` is `[]` when `rule`'s own selector matched at least one
  node anywhere in `document`, or one `:transform-rule-unmatched`
  diagnostic naming it otherwise (AC #6).

  `scope` defaults to `default-scope` when `rule` supplies none; `ctx`
  fixes `:meta` to `document`'s own, read-only for every call this rule's
  pass makes to its `:transform` function (AC #7) -- `:path`/`:ref-kind`
  are filled in per node by `rewrite-fn-for`. `walk/rewrite-post` runs
  once, at `::ast/document`, over the whole document -- its own
  post-order/never-revisit contract is what gives AC #2/#3 for free (this
  namespace's own docstring) -- and its result is passed through
  `expect-document-count-one!` before anything else touches it, the same
  'exactly 1 node comes back' contract every other `:one` position already
  has (AC #5). The whole thing is wrapped in a `try`/`catch` that lets
  `malformed-result!`/`validate-replacement!`'s own exceptions (already
  naming `label`, carrying their own `:rule` key) pass straight through
  unchanged, and re-throws anything else -- `::walk/arity-error` from
  either `walk/expect-count-one!` or `expect-document-count-one!` itself,
  `haselnuss.select/matches?`'s own `::select/malformed-selector` for a
  rule with a bad `:select`, or any other exception a rule's own code
  raises -- via `rewrap-rule-error`, folding this rule's own `label` in,
  consistent with how every other failure mode in this namespace already
  identifies its rule."
  [document index rule]
  (let [label (rule-label index rule)
        scope (or (:scope rule) default-scope)
        matched? (volatile! false)
        ctx {:meta (:meta document)}
        walker {:table walk/table :scope scope
                :rewrite-fn (rewrite-fn-for (assoc rule :scope scope) label ctx matched?)}
        document' (try
                    (expect-document-count-one!
                     document (walk/rewrite-post walker ::ast/document document))
                    (catch clojure.lang.ExceptionInfo e
                      (if (contains? (ex-data e) :rule)
                        (throw e)
                        (throw (rewrap-rule-error e label)))))]
    {:document document'
     :diagnostics (if @matched?
                    []
                    [{:type :transform-rule-unmatched
                      :name label
                      :message (str label "'s selector matched no node")}])}))

(defn- unexpanded-includes
  "Every `:include` Block (`ast.clj`'s `:include` variant) anywhere in
  `document`, in document order (TASK-106) -- these
  Blocks are still unexpanded at the point `transform-document` runs,
  since `--transform` sits before `resolve/expand-includes` (this
  namespace's own docstring, 'RUNNING OVER A PARTIAL DOCUMENT').

  Folds with `:scope walk/every-ref-kind` rather than `default-scope` (or
  any one rule's own `:scope`) deliberately: whether a document is
  partial is a fact about the DOCUMENT, independent of which fields any
  particular rule chooses to descend into, so this has to reach an
  `:include` tucked anywhere the schema allows one -- inside a Section, a
  BlockQuote, a Directive's `:fallback`, or a Table Cell's own `:blocks`
  (`::ast/cell`, NOT in `default-scope` -- a rule scoped to the default
  never sees inside a table at all) -- not merely where `default-scope`
  (or a narrower rule `:scope`) would have looked."
  [document]
  (walk/fold
   {:table walk/table
    :scope walk/every-ref-kind
    :visit (fn [acc _path ref-kind node]
             (cond-> acc
               (and (= ref-kind ::ast/block) (= :include (:t node)))
               (conj node)))}
   [] ::ast/document document))

(defn- include-location
  "One `:locations` entry for `include`: `{:src :source-line :source-file?}`,
  or nil when the parser recorded no position for it (TASK-163)."
  [include]
  (when-let [{:keys [file line]} (ast/source include)]
    (cond-> {:src (:src include) :source-line line}
      file (assoc :source-file file))))

(defn- partial-document-diagnostics
  "`includes` (`unexpanded-includes`'s own return value) turned into `[]`
  when empty, or a single `{:type :transform-partial-document :includes
  :message}` diagnostic naming every one of them (TASK-106 AC #1/#2) --
  concretely, by path, rather than only stating that includes exist, so
  an author can tell exactly which chapters the rules that just ran never
  saw. `:includes` is their `:src`s. Each is also named with the
  `path:line` of its `!include` when the parser recorded one, and the
  diagnostic then carries `:locations` (`include-location`, TASK-163); an
  include with no position (read from JSON, built by a rule) is named by
  its `:src` alone, as before. Joins with `apply`/`interpose` (not
  `clojure.string/join`) so this namespace's own AC #8 -- 'requires
  nothing beyond haselnuss.ast/haselnuss.select/haselnuss.walk' -- stays
  true; the functions doing it are `clojure.core`, already in scope
  everywhere."
  [includes]
  (when (seq includes)
    (let [srcs (mapv :src includes)
          locations (into [] (keep include-location) includes)
          named (map (fn [include]
                       (if-let [label (ast/source-label include)]
                         (str (:src include) " (" label ")")
                         (:src include)))
                     includes)]
      [(cond-> {:type :transform-partial-document
                :includes srcs
                :message (str "--transform ran before " (count srcs)
                              (if (= 1 (count srcs)) " !include was" " !includes were")
                              " expanded, so these rules never saw: "
                              (apply str (interpose ", " named)))}
         (seq locations) (assoc :locations locations))])))

(defn transform-document
  "Applies `rules` (a seq of `{:select :transform :name? :scope?}` maps,
  this namespace's own docstring has the full rule shape and the
  return-value contract) to `document` (a `haselnuss.ast` Document),
  returning `{:document document' :diagnostics diagnostics}`.

  Each rule runs as one full post-order pass, in DECLARATION order (AC
  #2) -- `reduce`d over `rules` as given, each rule's own output document
  becoming the next rule's input, so 'uppercase every heading, THEN drop
  every draft block' means exactly what it says, never first-match-wins
  at each node. `diagnostics` is, in order: at most one
  `:transform-partial-document` diagnostic (TASK-106, computed once from
  `document` exactly as given, before any rule runs -- see this
  namespace's own docstring) when `rules` is non-empty AND `document`
  still carries an unexpanded `:include` Block anywhere, then every
  rule's own `:transform-rule-unmatched` diagnostic (`apply-rule`), in
  rule order -- `[]` when `rules` is empty, `document` has no `:include`
  Block, and every rule matched at least one node.

  With `rules` empty (or nil), `document` is returned unchanged and
  `diagnostics` is `[]` -- the no-op a document with no `--transform` flag
  at all needs (TASK-103's own concern, not this namespace's): the
  `:include` scan itself does not even run, since `:transform-partial-
  document` is exactly as much about there being a `--transform` rule to
  run over a partial document as it is about the document being
  partial."
  [document rules]
  (reduce
   (fn [{:keys [document diagnostics]} [index rule]]
     (let [{document' :document rule-diagnostics :diagnostics} (apply-rule document index rule)]
       {:document document' :diagnostics (into diagnostics rule-diagnostics)}))
   {:document document
    :diagnostics (if (seq rules)
                   (vec (partial-document-diagnostics (unexpanded-includes document)))
                   [])}
   (map-indexed vector rules)))
