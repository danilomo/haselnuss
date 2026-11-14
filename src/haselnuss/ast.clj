(ns haselnuss.ast
  "Normative Haselnuss AST types and malli schemas (`SPEC.md` sec4, the
  document model).

  This AST is the contract every later task (parser, resolver, both emitters)
  is built against. Per doc-2, schemas are plain malli data (not macros), so
  they can be validated, introspected, and -- for TASK-3 -- transformed
  directly into a JSON Schema without a second source of truth.

  Node tags (`:t` for Block/Inline, `:kind` for Fallback) and multi-word field
  names use Clojure kebab-case rather than the camelCase/PascalCase of the
  TypeScript spec (e.g. `:code-block` for `CodeBlock`, `:csl-style` for
  `cslStyle`, `:suppress-prefix` for `suppressPrefix`); every other field name
  and shape is a direct, 1:1 port of SPEC.md sec4."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]))

(defn- dispatch-by
  "Malli `:multi` dispatch function for key `k` that coerces the dispatched
  value to a keyword before comparing it against the schema's dispatch keys.

  A bare `{:dispatch k}` dispatches on `k`'s *raw*, not-yet-decoded value: for
  `::block`/`::inline` (dispatch `:t`) and `::fallback` (dispatch `:kind`),
  in-memory AST data always has a keyword there (e.g. `:para`), but a value
  freshly parsed from JSON (TASK-3) has a *string* there (e.g. `\"para\"`)
  until malli's json-transformer decodes it -- and that decoding only runs
  for the children of whichever variant dispatch already matched. With a
  bare keyword dispatch, a JSON `\"para\"` never matches the keyword
  dispatch key `:para`, so decode silently leaves the whole node (and its
  children) untouched instead of erroring or converting it. Coercing with
  `keyword` here makes dispatch match either representation: `(keyword
  :para)` and `(keyword \"para\")` both yield `:para`, so in-memory
  validation behaves exactly as `{:dispatch k}` did, while JSON decode now
  resolves the correct variant too. See TASK-3 (haselnuss.json)."
  [k]
  (fn [node] (keyword (get node k))))

(def registry
  "Malli registry for the Haselnuss AST: malli's default schemas plus every
  AST node type, keyed by qualified keyword (`::attr`, `::block`, ...) so the
  mutually recursive node types (Block contains Inline and Block; Inline
  contains Inline; both can carry an Attr or a Fallback) can refer to each
  other via `[:ref ::block]` etc. Exported so TASK-3's JSON Schema generation,
  and any other future consumer, builds against these exact definitions
  rather than a second copy."
  (merge
   (m/default-schemas)
   {::attr
    ;; sec4.1: every referenceable/styleable node carries an Attr.
    [:map
     [:id {:optional true} :string]
     [:classes [:vector :string]]
     [:props [:map-of :string :string]]]

    ::meta
    ;; sec4.2. The map is open (extra keys allowed) by default, matching the
    ;; TS type's `[k: string]: unknown` open-ended front matter.
    [:map
     [:title {:optional true} [:vector [:ref ::inline]]]
     [:authors {:optional true} [:vector :string]]
     [:date {:optional true} :string]
     [:bibliography {:optional true} :string]
     [:csl-style {:optional true} :string]
     [:lang {:optional true} :string]
     ;; TASK-53 (:section/:chapter), extended by TASK-82 (:part). Which
     ;; sectioning division a level-1 Section is: the default `:section`
     ;; is every document written before this key existed, `:chapter` is
     ;; the book-length shape -- level 1 becomes a chapter, every deeper
     ;; level shifts down with it, and a section-scoped figure/table/
     ;; equation number composes with the chapter rather than with the
     ;; whole section path -- and `:part` shifts everything down once
     ;; more (level 1 a part, level 2 a chapter, ...): a part gets its
     ;; own roman-numeral counter but never resets or composes with the
     ;; chapter/figure/table/equation numbering, exactly as a real
     ;; `\part`-bearing `report`/`book` class behaves (see
     ;; `haselnuss.resolver/number-document`). An enum rather than an
     ;; open string: the two emitters and the numbering pass each branch
     ;; on it, and a fourth value none of them recognizes would be a
     ;; silent no-op.
     [:top-level-division {:optional true} [:enum :section :chapter :part]]]

    ::document
    [:map
     [:meta [:ref ::meta]]
     [:blocks [:vector [:ref ::block]]]]

    ::col
    [:map
     [:align {:optional true} [:enum :left :center :right]]
     [:width {:optional true} :string]]

    ::cell
    [:map
     [:blocks [:vector [:ref ::block]]]
     [:align {:optional true} [:enum :left :center :right]]
     [:span {:optional true} :int]]

    ::row
    [:map
     [:cells [:vector [:ref ::cell]]]]

    ::cite-item
    [:map
     [:key :string]
     [:prefix {:optional true} [:vector [:ref ::inline]]]
     [:suffix {:optional true} [:vector [:ref ::inline]]]
     [:mode [:enum :normal :author :year]]]

    ::fallback
    ;; sec4.5: exactly these four variants.
    [:multi {:dispatch (dispatch-by :kind)}
     [:blocks [:map
               [:kind [:= :blocks]]
               [:blocks [:vector [:ref ::block]]]]]
     [:poster [:map
               [:kind [:= :poster]]
               [:src :string]
               [:caption {:optional true} [:vector [:ref ::inline]]]]]
     [:transform [:map
                  [:kind [:= :transform]]
                  [:rule :string]]]
     [:drop [:map
             [:kind [:= :drop]]]]]

    ::block
    ;; sec4.3.
    [:multi {:dispatch (dispatch-by :t)}
     ;; TASK-194: a parsed Block sequence never holds anything after a
     ;; Section but another Section of the same or a higher (smaller)
     ;; level -- the parser folds every later Block into it -- and a
     ;; Section's own Blocks hold only Sections of a deeper level. Deliberately
     ;; NOT enforced here: transforms and JSON input build ASTs this
     ;; schema already accepts, and HTML/LaTeX render them as they stand.
     ;; Only `haselnuss.emit.hdoc` (no closing line to write) refuses it.
     [:section [:map
                [:t [:= :section]]
                [:level :int]
                [:heading [:vector [:ref ::inline]]]
                [:blocks [:vector [:ref ::block]]]
                [:attr [:ref ::attr]]]]
     [:para [:map
             [:t [:= :para]]
             [:inlines [:vector [:ref ::inline]]]]]
     [:list [:map
             [:t [:= :list]]
             [:ordered :boolean]
             [:start {:optional true} :int]
             [:tight :boolean]
             [:items [:vector [:vector [:ref ::block]]]]
             [:attr [:ref ::attr]]]]
     [:code-block [:map
                   [:t [:= :code-block]]
                   [:lang {:optional true} :string]
                   [:text :string]
                   [:attr [:ref ::attr]]]]
     [:math-block [:map
                   [:t [:= :math-block]]
                   [:tex :string]
                   [:attr [:ref ::attr]]]]
     [:figure [:map
               [:t [:= :figure]]
               [:content [:ref ::block]]
               [:caption [:vector [:ref ::inline]]]
               [:attr [:ref ::attr]]]]
     [:table [:map
              [:t [:= :table]]
              [:head [:ref ::row]]
              [:rows [:vector [:ref ::row]]]
              [:caption [:vector [:ref ::inline]]]
              [:colspec [:vector [:ref ::col]]]
              [:attr [:ref ::attr]]]]
     ;; `:attr` is optional here, unlike Section/Figure/Table/etc: sec4.3
     ;; gives BlockQuote no Attr, and `haselnuss.parser` accordingly
     ;; produces none -- but `haselnuss.emit.html` renders one when
     ;; present, and a directive degraded by a registry `:lower` rule
     ;; uses a BlockQuote to carry its own id (see `haselnuss.cli`). It
     ;; was previously undeclared and therefore valid only by malli's
     ;; open-map default, i.e. by accident.
     [:block-quote [:map
                    [:t [:= :block-quote]]
                    [:blocks [:vector [:ref ::block]]]
                    [:attr {:optional true} [:ref ::attr]]]]
     [:directive [:map
                  [:t [:= :directive]]
                  [:name :string]
                  [:blocks [:vector [:ref ::block]]]
                  [:attr [:ref ::attr]]
                  [:fallback {:optional true} [:ref ::fallback]]]]
     [:include [:map
                [:t [:= :include]]
                [:src :string]]]
     ;; TASK-152/doc-10 ("AST and parser"): a `!quote <ref>` line, the
     ;; format's second whole-line block construct beside `!include`.
     ;; `:ref` is the book reference EXACTLY as written -- never
     ;; normalized and never validated here, so a malformed one survives
     ;; to the resolver, which reports it as a located
     ;; `malformed-book-ref` warning (doc-10, "Error handling"). Every
     ;; other field is optional and filled only by the resolver's quote
     ;; expansion (doc-10 step 0b, TASK-154): `:canonical` the source
     ;; book's canonical reference, `:title` the passage title, `:blocks`
     ;; the spliced passage, `:url` the source's URL when its library
     ;; has a template (TASK-155/156: HTML's `cite=`).
     [:book-quote [:map
                   [:t [:= :book-quote]]
                   [:ref :string]
                   [:canonical {:optional true} :string]
                   [:title {:optional true} [:vector [:ref ::inline]]]
                   [:blocks {:optional true} [:vector [:ref ::block]]]
                   [:url {:optional true} :string]]]
     [:thematic-break [:map
                       [:t [:= :thematic-break]]]]]

    ::inline
    ;; sec4.4.
    [:multi {:dispatch (dispatch-by :t)}
     [:str [:map
            [:t [:= :str]]
            [:text :string]]]
     [:space [:map
              [:t [:= :space]]]]
     [:soft-break [:map
                   [:t [:= :soft-break]]]]
     [:line-break [:map
                   [:t [:= :line-break]]]]
     [:emph [:map
             [:t [:= :emph]]
             [:inlines [:vector [:ref ::inline]]]]]
     [:strong [:map
               [:t [:= :strong]]
               [:inlines [:vector [:ref ::inline]]]]]
     [:strike [:map
               [:t [:= :strike]]
               [:inlines [:vector [:ref ::inline]]]]]
     [:small-caps [:map
                   [:t [:= :small-caps]]
                   [:inlines [:vector [:ref ::inline]]]]]
     [:sub [:map
            [:t [:= :sub]]
            [:inlines [:vector [:ref ::inline]]]]]
     [:sup [:map
            [:t [:= :sup]]
            [:inlines [:vector [:ref ::inline]]]]]
     [:code [:map
             [:t [:= :code]]
             [:text :string]]]
     [:math-inline [:map
                    [:t [:= :math-inline]]
                    [:tex :string]]]
     [:link [:map
             [:t [:= :link]]
             [:target :string]
             [:title {:optional true} :string]
             [:inlines [:vector [:ref ::inline]]]
             [:attr [:ref ::attr]]]]
     [:image [:map
              [:t [:= :image]]
              [:src :string]
              [:title {:optional true} :string]
              [:alt :string]
              [:attr [:ref ::attr]]]]
     [:span [:map
             [:t [:= :span]]
             [:inlines [:vector [:ref ::inline]]]
             [:attr [:ref ::attr]]]]
     ;; TASK-116/doc-8 sec2: an annotation laid over a run of words, the
     ;; coloured wash a PDF reader draws. Structurally a `:span` -- a
     ;; wrapping inline that marks a run and changes nothing about it --
     ;; and deliberately NOT a `:span` carrying a class: classes select
     ;; directive/role *behaviour* (sec4.1), and an emitter branching on a
     ;; string inside `attr.classes` would be the first place in this
     ;; codebase that happens.
     [:highlight [:map
                  [:t [:= :highlight]]
                  [:inlines [:vector [:ref ::inline]]]
                  [:attr [:ref ::attr]]]]
     [:cross-ref [:map
                  [:t [:= :cross-ref]]
                  [:label :string]
                  [:suppress-prefix {:optional true} :boolean]]]
     ;; TASK-152/doc-10 ("AST and parser"): an inline link to a passage
     ;; of another book, written `@{bible:romanos#14:8}`. The braces are
     ;; what set it apart from a bare `@prefix:label` `:cross-ref`
     ;; (sec5.10). `:ref` is the text between them EXACTLY as written,
     ;; unvalidated, for the same reason as `:book-quote`'s. The rest is
     ;; optional and filled by resolution (doc-10 step 2, TASK-155):
     ;; `:passage` the parsed passage in `haselnuss.book-ref`'s own
     ;; `:form`-tagged shape, `:canonical` the target's canonical
     ;; reference, `:text` the link text (Inlines, since it is the
     ;; Passage's `:title`, which is Inlines), `:url` when the library
     ;; has a URL template.
     [:book-ref [:map
                 [:t [:= :book-ref]]
                 [:ref :string]
                 ;; Exactly `haselnuss.book-ref`'s three passage shapes.
                 ;; `dispatch-by` for the same reason `::block` uses it:
                 ;; JSON hands `:form` over as a string until decoded.
                 [:passage {:optional true}
                  [:multi {:dispatch (dispatch-by :form)}
                   [:label [:map [:form [:= :label]] [:label :string]]]
                   [:range [:map [:form [:= :range]] [:from :string] [:to :string]]]
                   [:locator [:map [:form [:= :locator]] [:locator :string]]]]]
                 [:canonical {:optional true} :string]
                 [:text {:optional true} [:vector [:ref ::inline]]]
                 [:url {:optional true} :string]]]
     [:cite [:map
             [:t [:= :cite]]
             [:items [:vector [:ref ::cite-item]]]]]
     [:note [:map
             [:t [:= :note]]
             [:blocks [:vector [:ref ::block]]]]]
     ;; TASK-116/doc-8 sec2: the balloon wedged between two words, opened
     ;; on click and dismissed again. Structurally a `:note` -- a footnote
     ;; is already "an inline marker whose content lives elsewhere and is
     ;; revealed on demand", which is a sticky note with different
     ;; presentation -- plus the `:attr` `:note` has never had, so an
     ;; annotation can be given an id and cross-referenced.
     [:sticky-note [:map
                    [:t [:= :sticky-note]]
                    [:blocks [:vector [:ref ::block]]]
                    [:attr [:ref ::attr]]]]]}))

(def Attr
  "Attr (sec4.1): id/classes/props carried by every referenceable/styleable node."
  (m/schema ::attr {:registry registry}))

(def Meta
  "Document metadata / open-ended front matter (sec4.2)."
  (m/schema ::meta {:registry registry}))

(def Row
  "A Table row: a vector of Cells (sec4.3)."
  (m/schema ::row {:registry registry}))

(def Cell
  "A Table cell: nested Blocks plus optional align/span (sec4.3)."
  (m/schema ::cell {:registry registry}))

(def Col
  "A Table column spec: optional align/width (sec4.3)."
  (m/schema ::col {:registry registry}))

(def CiteItem
  "One citation key with optional prefix/suffix Inlines and a mode (sec4.4)."
  (m/schema ::cite-item {:registry registry}))

(def Fallback
  "Static substitute for a Directive a target can't render natively (sec4.5).
  Exactly four variants: :blocks, :poster, :transform, :drop."
  (m/schema ::fallback {:registry registry}))

(def Block
  "Every Block node variant (sec4.3), a malli :multi schema dispatching on :t."
  (m/schema ::block {:registry registry}))

(def Inline
  "Every Inline node variant (sec4.4), a malli :multi schema dispatching on :t."
  (m/schema ::inline {:registry registry}))

(def Document
  "A whole Haselnuss document: Meta plus top-level Blocks (sec4.2)."
  (m/schema ::document {:registry registry}))

(defn valid?
  "True if `value` (plain Clojure data) conforms to `schema`, e.g. `(valid?
  Block {:t :para :inlines []})`."
  [schema value]
  (m/validate schema value))

(defn explain
  "Malli's explanation of why `value` does not conform to `schema`, or nil if
  it does conform."
  [schema value]
  (m/explain schema value))

(defn- dispatch-value-message
  "One `:malli.core/invalid-dispatch-value` error from `explain`'s own
  `:errors` (TASK-111) turned into a message naming the actual offending
  value and every value that WOULD have matched, e.g. `:not-a-real-node-
  type does not match any of :section, :para, :list, ...` -- the one fact
  `malli.error/humanize` never states for this error type (it says only
  'invalid dispatch value', for every dispatch failure in every `:multi`
  schema alike).

  `err`'s own `:schema` is the `:multi` schema itself (a compiled malli
  schema, not raw EDN): `(m/properties ...)`'s own `:dispatch` is exactly
  the function `dispatch-by` built it from, applied here to `(:value
  err)` (the whole invalid node) the same way malli itself just did, so
  this reads the one field that actually failed (`:t` for Block/Inline,
  `:kind` for Fallback) without this namespace needing to know which
  field name a particular schema dispatches on. `(m/children (:schema
  err))` is every variant `[tag ...]` pair of that same schema, in
  declaration order -- exactly the vocabulary `dispatch-by`'s own
  docstring calls 'the schema's dispatch keys'."
  [err]
  (let [dispatch (:dispatch (m/properties (:schema err)))
        got (dispatch (:value err))
        options (map first (m/children (:schema err)))]
    (str (pr-str got) " does not match any of: " (str/join ", " (map pr-str options)))))

(defn- humanized-leaf-message
  "The readable message for one leaf `messages` (a vector of strings,
  `malli.error/humanize`'s own leaf shape) found at `path` (a vector of
  map keys/vector indices from the invalid value's own root) while
  walking a `humanize` result -- `dispatch-messages` (`:in` ->
  `dispatch-value-message` string, built once per `explain-summary` call)
  takes over at exactly the paths where the underlying error was
  `:malli.core/invalid-dispatch-value`, since humanize's own leaf there
  ('invalid dispatch value') is useless on its own; every other leaf is
  humanize's own messages, joined."
  [dispatch-messages path messages]
  (or (get dispatch-messages path) (str/join ", " messages)))

(defn- flatten-humanized
  "`humanized` (`malli.error/humanize`'s own return value, which mirrors
  the invalid value's own map/vector shape -- a missing/mistyped `:inlines`
  becomes `{:inlines [\"missing required key\"]}`) turned into `[path
  message]` pairs, `path` the same vector of keys/indices `explain`'s own
  `:in` already uses for the same location, so `dispatch-messages` (keyed
  by that same `:in`) lines up with the paths this walk produces.

  A leaf is a non-empty sequential all of whose elements are strings
  (`humanized-leaf-message`'s own vector); this never collides with a
  per-index array of sub-results (a `:blocks`-typed field's own vector),
  since humanize always wraps each index's own messages one level deeper
  -- `[nil [\"invalid dispatch value\"]]`, never a bare string beside a
  nested result at the same level. `nil` (humanize's own 'this position
  was valid') contributes nothing."
  [dispatch-messages humanized]
  (letfn [(walk [path node]
            (cond
              (nil? node) []
              (map? node) (mapcat (fn [[k v]] (walk (conj path k) v)) node)
              (and (sequential? node) (seq node) (every? string? node))
              [[path (humanized-leaf-message dispatch-messages path node)]]
              (sequential? node) (mapcat (fn [[i v]] (walk (conj path i) v))
                                         (map-indexed vector node))
              :else [[path (str node)]]))]
    (walk [] humanized)))

(defn explain-summary
  "A short, readable account of every problem `explanation` (`explain`'s
  own return value, or nil when the value conformed) reports, in the
  schema's own field vocabulary -- never the raw malli schema data
  `explanation` itself carries (every variant of a `:multi` schema, an
  anonymous dispatch function, a Java object identity hash), which is
  exactly what makes `(pr-str explanation)` unusable as a message a human
  reads (TASK-111, found dogfooding a `--transform` sidecar with a typo'd
  `:t`: a single `pr-str`'d explanation ran to roughly 1500 characters,
  none of them the one fact -- `:not-a-real-node-type` is not a real
  Block type -- an author actually needed).

  `malli.error/humanize` already turns most of `explanation` into
  concise, path-shaped messages (`flatten-humanized`'s own docstring);
  this adds only what humanize leaves generic for a `:multi` schema's own
  dispatch failure (`dispatch-value-message`), matched back to humanize's
  own path by `:in`. Each `[path message]` pair becomes one line, `path`
  prefixed on when non-empty (a nested problem, e.g. inside a Section's
  own `:blocks`) and omitted when the whole value's own root is the
  problem (the common case: a rule's `:transform` returned one wrong top-
  level node), multiple lines joined with `\"; \"`.

  nil in, nil out."
  [explanation]
  (when explanation
    (let [dispatch-messages (into {}
                                  (keep (fn [err]
                                          (when (= :malli.core/invalid-dispatch-value (:type err))
                                            [(:in err) (dispatch-value-message err)])))
                                  (:errors explanation))
          entries (flatten-humanized dispatch-messages (me/humanize explanation))]
      (str/join "; "
                (map (fn [[path message]]
                       (if (seq path) (str path " " message) message))
                     entries)))))

;; --- Source positions (TASK-143) ---------------------------------------------
;;
;; A node's source position is Clojure METADATA, under `::source`, not a
;; field. Metadata is invisible to `=`, to malli validation and to every
;; serializer, so a document that does not ask for positions is
;; byte-identical to one built before they existed (JSON interchange form,
;; `.hdoc` output, equality-based round trips), with no strip step to forget.
;; It survives `assoc`/`update`/`assoc-in` -- every way the passes rewrite a
;; node -- and is lost only where a node is built afresh, which then simply
;; has no position to report.

(defn with-source
  "`node` carrying `{:file file :line line :column column}` as its source
  position, unless `line` is nil. `file` may be nil (parsed without
  `:source`), and `column` -- the 1-based column, in code points, a tab
  counting one (TASK-248) -- is left out when nil or not given. `node`s
  that cannot carry metadata are returned unchanged."
  ([node file line] (with-source node file line nil))
  ([node file line column]
   (if (and line (instance? clojure.lang.IObj node))
     (vary-meta node assoc ::source (cond-> {:line line}
                                      file (assoc :file file)
                                      (pos-int? column) (assoc :column column)))
     node)))

(defn source
  "The `{:line n :file path :column c}` (`:file` absent when unknown,
  `:column` absent when the parser could not place the node on its line)
  recorded on `node` by the parser, or nil."
  [node]
  (::source (meta node)))

(defn source-label
  "`\"path:line\"` for `node`'s source position, or nil when the node has no
  position or no file name to print it against."
  [node]
  (let [{:keys [file line]} (source node)]
    (when (and file line) (str file ":" line))))
