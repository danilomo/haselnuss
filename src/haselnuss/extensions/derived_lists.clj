(ns haselnuss.extensions.derived-lists
  "The three derived lists a document can print (TASK-59): a table of
  contents, a list of figures and a list of tables.

  `haselnuss.resolver` has derived all three since TASK-14 --
  `derive-toc`, `derive-list-of-figures`, `derive-list-of-tables` -- and
  until now nothing consumed any of them: three finished passes
  producing data no emitter read, and neither target ever printing a
  list. A short paper does not miss them; the book-length document this
  milestone is scoped from prints all three.

  WHERE a list goes is the author's decision, not a fixed position: a
  thesis puts the lists between the abstract and the table of contents,
  a report puts the contents first and no lists at all. So the surface
  is an empty block directive written exactly where the list belongs,
  which needed no parser change at all:

      :::{toc}
      :::

      :::{list-of-figures}
      :::

  This namespace owns the *data* -- which names are list placeholders,
  which derivation each prints, which LaTeX command builds it natively,
  and what each is called in each language -- plus the registry entries
  that keep them alive through `haselnuss.lower`. It owns no rendering,
  for the same reason `haselnuss.extensions.front-matter` owns none: a
  list entry carries Inline content (a heading, a caption), and the
  registry's `(fn [directive target])` renderer signature gives an
  extension no way to reach an emitter's own `render-inlines`. What is
  registered is a marker, `list-renderer`, recognized by both emitters
  and never called.

  One namespace rather than a table inside each emitter, and this is
  the decision worth recording: the two targets need the SAME facts
  about a list -- which derivation it prints, and what it is called in
  the document's language -- and a heading word that existed twice
  would be two places for \"Lista de Figuras\" to drift. What differs
  between the targets is only how the list is drawn, which is each
  emitter's own business and lives there.

  A registry `:lower` rule is the one shape this deliberately does NOT
  take, tempting though it is (`haselnuss.cli` already closes such a
  rule over the label table). Native-mode LaTeX must emit
  `\\tableofcontents` and let LaTeX build the list from its own
  counters, while computed-numbers mode must render the derived list
  itself -- and `lower` is given the target but never the emission
  MODE, so it cannot make that choice.")

(def lists
  "The list-placeholder directive names, each mapped to
  `{:derivation :command :words}`:

  - `:derivation` -- which of `haselnuss.resolver`'s derived structures
    this placeholder prints, and the key it arrives under in the
    emitters' own `:derived-lists` option. `haselnuss.cli` derives all
    three from the same resolved document and the same label table it
    numbered with, the discipline `:labels` already follows: a second
    derivation somewhere downstream would be a second table that has to
    agree with the first by luck.
  - `:command` -- the LaTeX command that builds this list from LaTeX's
    own counters, used in native mode where those counters ARE the
    document's numbers.
  - `:words` -- the heading each prints, per `meta.lang`, mirroring
    `haselnuss.resolver/default-lexicon`'s own per-language `:words`
    and carrying the same two languages for the same reason: this text
    is printed, not authored.

  The three names are spelled as the author writes them, hyphenated
  rather than run together (`list-of-figures`, not `listoffigures`):
  the LaTeX spellings are LaTeX's, and a document's surface syntax has
  no reason to inherit them."
  {"toc" {:derivation :toc
          :command "\\tableofcontents"
          :words {"en" "Contents"
                  "pt-BR" "Sumário"}}
   "list-of-figures" {:derivation :list-of-figures
                      :command "\\listoffigures"
                      :words {"en" "List of Figures"
                              "pt-BR" "Lista de Figuras"}}
   "list-of-tables" {:derivation :list-of-tables
                     :command "\\listoftables"
                     :words {"en" "List of Tables"
                             "pt-BR" "Lista de Tabelas"}}})

(defn spec
  "The `lists` entry for Block `block`, or nil when `block` is not a list
  placeholder at all -- the one lookup both emitters do, so neither has
  to know that a placeholder is a `:directive` with a particular name."
  [block]
  (when (= :directive (:t block))
    (get lists (:name block))))

(defn heading-word
  "The heading `entry` (a `lists` value) prints for `lang`: an exact
  match, else English -- the same fallback `haselnuss.resolver/words-for`
  applies to a lexicon kind's own words, and for the same reason (a
  document in a language nobody translated this into still gets a
  heading, in a language it can be recognized by)."
  [entry lang]
  (let [words (:words entry)]
    (or (get words lang) (get words "en"))))

(defn entry-inlines
  "`inlines` (a heading's, or a caption's) reduced to what is safe to
  render INSIDE a list entry (TASK-59, found by review).

  A list entry is itself one link to the node it names, and it is
  rendered from the same Inlines the node itself renders. Left alone
  that has two consequences, both real and both reproduced:

  - a footnote in a heading is collected TWICE, once from the heading
    and once from the entry, so the document grows a duplicate note and
    every note after it is renumbered;
  - a link in a heading lands inside the entry's own link -- `<a>`
    inside `<a>`, which a browser closes early, and `\\href` inside
    `\\hyperref`, which is exactly the entry-is-one-link property the
    lists exist to have.

  So a link becomes its own text, a resolved cross-reference becomes
  the text it resolved to, and a footnote is dropped: a note belongs
  where the author put it, not in a table of contents. Everything else
  -- emphasis, code, math, the wrappers around them -- is kept, and the
  recursion goes through every Inline that holds Inlines, since a link
  inside emphasis is no less a link.

  This is LaTeX's own `\\section[short]{long}` distinction, made
  automatically because this format has no spelling for it."
  [inlines]
  (into []
        (mapcat (fn [{:keys [t] :as inline}]
                  (cond
                    (= :note t) []
                    (= :link t) (entry-inlines (:inlines inline))
                    (= :cross-ref t) (if-let [text (:text inline)]
                                       [{:t :str :text text}]
                                       [])
                    (contains? inline :inlines) [(update inline :inlines entry-inlines)]
                    :else [inline])))
        inlines))

(defn list-renderer
  "The marker registered as every list placeholder's own per-target
  renderer. Registering *something* is what makes `haselnuss.lower/lower`
  keep the directive rather than demand a fallback the author has no way
  to declare (the same reason
  `haselnuss.extensions.front-matter/front-matter-renderer` exists);
  both emitters recognize a placeholder through `spec` and draw the list
  themselves, so this is never called.

  A function that throws rather than an inert value, for the reason
  `haselnuss.emit.latex/environment-renderer` records in full: the
  registry is one table shared across targets, and a consumer that
  called whatever non-nil `:emit` value it found would otherwise splice
  something meaningless into its output."
  [directive target]
  (throw (ex-info
          (str "the derived-list marker was called as a renderer for directive "
               (pr-str (:name directive)) " on target " (pr-str target)
               ": a list placeholder is drawn by the emitter itself from the resolver's"
               " own derivations, never dispatched through the registry")
          {:type ::list-marker-called :block directive :target target})))

(def extensions
  "One `haselnuss.registry/register`-shaped entry per list-placeholder
  name, each carrying the `list-renderer` marker for both targets.

  No `:kind`: a list is not a numbering target, so nothing can
  cross-reference one -- and it prints numbers that belong to other
  nodes. No `:lower` either: it has a representation on both targets
  this project emits, so it never degrades. A third target would find
  no representation and stop the build naming the directive, which is
  the honest answer for a target that has no way to build a table of
  contents."
  (mapv (fn [list-name]
          {:name list-name
           :emit {:html list-renderer :latex list-renderer}})
        (sort (keys lists))))
