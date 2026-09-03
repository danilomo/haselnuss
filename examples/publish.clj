;; publish.clj -- a worked example of a `--transform` sidecar file (m-13,
;; TASK-104): three rules that strip drafting scaffolding out of a document
;; before it is published, exercising all three shapes a rule's own
;; `:transform` function can hand back (`haselnuss.transform`'s own
;; docstring, THE RETURN-VALUE CONTRACT) -- a node replaces, `nil` deletes,
;; and a sequence of nodes splices.
;;
;; Try it (from the repository root, matching this project's own README
;; "Example" section -- build into a scratch directory, never beside the
;; sources):
;;
;;   mkdir -p examples/out
;;   lein run -- examples/draft.hdoc --transform examples/publish.clj \
;;     --output examples/out/draft.html
;;   lein run -- examples/draft.hdoc --target latex \
;;     --transform examples/publish.clj --output examples/out/draft.tex
;;   pdflatex -output-directory=examples/out examples/out/draft.tex
;;
;; and, to see `--in-place` rewrite a document over itself once it has been
;; transformed -- never the committed source; a scratch copy, exactly the
;; way an author would run this against a real draft:
;;
;;   cp examples/draft.hdoc examples/out/draft-published.hdoc
;;   lein run -- examples/out/draft-published.hdoc \
;;     --transform examples/publish.clj --in-place
;;
;; THE SOURCE: examples/draft.hdoc, one section of a real (if small)
;; document with three things a draft carries that a published page should
;; not:
;;
;;   1. a `:::{draft}` directive -- an internal-only editorial note. `draft`
;;      is not a name this build's registry knows (SPEC.md sec5.5: "a
;;      directive name that is not registered stops the build and names
;;      it"), DELIBERATELY -- try `lein run -- examples/draft.hdoc` with no
;;      `--transform` at all and the build refuses it by name. That is the
;;      point, not an oversight: this section's own scaffolding must never
;;      reach `resolve`/`emit` unstripped, and `--transform`'s pass sits
;;      before both for exactly this reason (doc-7 decision 1) -- the rule
;;      below has to run before the unknown name is ever a problem, not
;;      after.
;;   2. a bulleted list of `TODO:` items -- shorthand for a reviewer, not
;;      prose for a reader.
;;   3. a heading carrying a `[DRAFT]` marker -- useful while writing,
;;      wrong on a published page.
;;
;; THE THREE RULES, in file order (declaration order, `haselnuss.
;; transform`'s own docstring: one full pass per rule, each rule seeing the
;; last rule's own output):
;;
;; delete-draft-scaffolding: `:select {:t :directive :name "draft"}` matches
;; the whole `:::{draft} ... :::` block; its `:transform` returns `nil`,
;; the delete shape -- the block, and the internal note inside it, is
;; simply gone from the published document.
;;
;; promote-todo-list: `:select {:t :list :text #"^TODO:"}` -- `:text`
;; (`haselnuss.select`'s own flattened-text key) matches the whole TODO
;; list by its own leading word, one plain map selector rather than a
;; hand-written predicate. Its `:transform` returns a SEQUENCE: one
;; `{:name "admonition"}` Directive per list item (SPEC.md sec5.5's own
;; built-in, the same one `examples/hazelnuts.hdoc` already uses), each
;; item's own `TODO: ` prefix stripped, so a paragraph of reviewer
;; shorthand becomes several separate, visible, numbered notes -- the
;; splice shape, and the one demonstrated here with more than one node
;; coming back, not a one-element vector standing in for `translate-
;; result`'s own single-node branch.
;;
;; tidy-draft-marked-headings: `:select {:t :section :text #"\[DRAFT\]"}`
;; matches this section by its own flattened text (heading and body both,
;; though only the heading carries the marker); its `:transform` returns
;; the SAME section with its `:heading` rebuilt, the replace shape. The
;; regex cannot run leaf by leaf (`haselnuss.ext/map-text`) here: CommonMark
;; tokenizes "yield [DRAFT]" into separate `:str`/`:space` leaves
;; (`"yield"`, `:space`, `"[DRAFT]"`), so a pattern anchored on the marker
;; and the space before it has no single leaf to match against. Flattening
;; the whole heading to one string first (`haselnuss.ext/text`), running
;; the regex there, then re-tokenizing the result (`haselnuss.ext/
;; text->inlines`) is what TASK-103 added those two functions for.

(defn- delete-draft-scaffolding
  [_node _ctx]
  nil)

(defn- todo-item->admonition
  "One TODO list item (a vector of Blocks, `haselnuss.ast` sec4.3's own
  `:list` shape), at position `index` (0-based) among its own list's items,
  turned into a numbered `{:name \"admonition\"}` Directive holding the same
  text, its `TODO: ` prefix stripped. Given its own `adm:todo-<n>` id
  (`adm`, `haselnuss.emit.latex`'s own `admonition` lexicon-kind, is what
  makes it a numbering target at all -- SPEC.md sec9 step 1 skips any node
  whose id has no recognized kind prefix; an id-LESS admonition would still
  render, just as an unnumbered \"Note\" rather than \"Note 1\"/\"Note 2\")."
  [index item]
  (let [stripped (clojure.string/replace (haselnuss.ext/text item) #"^TODO:\s*" "")]
    {:t :directive
     :name "admonition"
     :attr {:id (str "adm:todo-" (inc index)) :classes [] :props {}}
     :blocks [{:t :para :inlines (haselnuss.ext/text->inlines stripped)}]}))

(defn- promote-todo-list
  [node _ctx]
  (into [] (map-indexed todo-item->admonition) (:items node)))

(defn- tidy-draft-marked-headings
  [node _ctx]
  (assoc node :heading
         (haselnuss.ext/text->inlines
          (clojure.string/replace (haselnuss.ext/text (:heading node))
                                   #"\s*\[DRAFT\]\s*$" ""))))

[{:name "delete draft scaffolding"
  :select {:t :directive :name "draft"}
  :transform delete-draft-scaffolding}
 {:name "promote TODO list to admonitions"
  :select {:t :list :text #"^TODO:"}
  :transform promote-todo-list}
 {:name "tidy DRAFT-marked headings"
  :select {:t :section :text #"\[DRAFT\]"}
  :transform tidy-draft-marked-headings}]
