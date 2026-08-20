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
  (:require [malli.core :as m]))

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
     ;; TASK-53. Which sectioning division a level-1 Section is: the
     ;; default `:section` is every document written before this key
     ;; existed, and `:chapter` is the book-length shape -- level 1
     ;; becomes a chapter, every deeper level shifts down with it, and a
     ;; section-scoped figure/table/equation number composes with the
     ;; chapter rather than with the whole section path (see
     ;; `haselnuss.resolver/number-document`). An enum rather than an
     ;; open string: the two emitters and the numbering pass each branch
     ;; on it, and a third value none of them recognizes would be a
     ;; silent no-op.
     [:top-level-division {:optional true} [:enum :section :chapter]]]

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
             [:inlines [:vector [:ref ::inline]]]
             [:attr [:ref ::attr]]]]
     [:image [:map
              [:t [:= :image]]
              [:src :string]
              [:alt :string]
              [:attr [:ref ::attr]]]]
     [:span [:map
             [:t [:= :span]]
             [:inlines [:vector [:ref ::inline]]]
             [:attr [:ref ::attr]]]]
     [:cross-ref [:map
                  [:t [:= :cross-ref]]
                  [:label :string]
                  [:suppress-prefix {:optional true} :boolean]]]
     [:cite [:map
             [:t [:= :cite]]
             [:items [:vector [:ref ::cite-item]]]]]
     [:note [:map
             [:t [:= :note]]
             [:blocks [:vector [:ref ::block]]]]]]}))

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
