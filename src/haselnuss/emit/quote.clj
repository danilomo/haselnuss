(ns haselnuss.emit.quote
  "What the HTML and LaTeX emitters share about rendering a quoted passage
  (a resolved `:book-quote`, doc-10 \"Emitters\", TASK-156).

  A quoted passage's Sections keep the levels they have in their own book
  (TASK-154 splices them unchanged), and a book's top heading is level 1.
  Rendered as written, quoting a whole chapter of a book into Section 2.3
  of a thesis put an `<h1>` (or a `\\chapter*`) inside the quotation. Both
  emitters instead move every quoted heading by one amount, `level-shift`,
  chosen so the passage's shallowest heading sits one level below the
  host Section the quote appears in, and the passage's own nesting is
  kept below it.")

(defn- section-levels
  "Every Section `:level` in `x` (a Block, Blocks, or any part of one),
  not descending into a nested `:book-quote`: that quote is moved by its
  own shift, measured from where it lands."
  [x]
  (cond
    (and (map? x) (= :book-quote (:t x))) nil
    (map? x) (concat (when (= :section (:t x)) [(:level x)])
                     (mapcat section-levels (vals x)))
    (sequential? x) (mapcat section-levels x)
    :else nil))

(defn level-shift
  "How far to move the levels of the Sections in `blocks` (a quoted
  passage) so its shallowest one renders at `host-level` + 1, where
  `host-level` is the level of the host Section the quote sits in (0 at
  the top of a document). 0 when `blocks` has no Section. Negative when
  the passage starts deeper than that, so quoting one subsection at the
  top of a chapter does not leave a gap in the outline.

  `min-level` (default 1) is the shallowest level the shallowest quoted
  heading may take (the LaTeX emitter passes the first level below
  `\\chapter`/`\\part`). It raises the one shift for the whole passage,
  never one heading at a time, so the passage's own nesting survives:
  flooring each heading separately would flatten a quoted h1/h2 into two
  `\\section*`s."
  ([host-level blocks] (level-shift host-level blocks 1))
  ([host-level blocks min-level]
   (if-let [levels (seq (section-levels blocks))]
     (let [shallowest (apply min levels)]
       (max (- (inc host-level) shallowest) (- min-level shallowest)))
     0)))
