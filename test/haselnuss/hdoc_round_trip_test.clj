(ns haselnuss.hdoc-round-trip-test
  "Pins the `.hdoc` round trip against every `.hdoc` committed to this
  repository (TASK-99), rather than the hand-built or short-fixture
  documents `haselnuss.emit.hdoc-test` (TASK-95/97) already covers one
  construct at a time. Those prove each spelling in isolation; this
  proves the whole thing against real documents, which is where the
  interactions between constructs live -- a table inside a directive, a
  footnote inside a list item, prose that happens to contain a `$` or an
  `@` -- including `examples/catecismo-heidelberg.hdoc`, 363 KB and the
  project's own torture test.

  Two properties, both from doc-7 and this task's own description:

  - AC #1, an AST FIXED POINT: `parse(emit(parse(src))) = parse(src)`.
    Text identity is not the goal -- the writer re-spells canonically
    (one emphasis delimiter, one list marker, ATX headings, normalized
    wrapping), so `emit(parse(src))` differs from `src` for almost any
    real document, by design (`haselnuss.emit.hdoc`'s own docstring).
  - AC #2, EMIT STABILITY: `emit(parse(emit(parse(src))))` is
    byte-identical to `emit(parse(src))`. This catches a writer that is
    stable in the AST but jitters in its own output -- a bug AC #1 alone
    cannot see, since AC #1 only compares trees, never the text between
    them.

  AC #3: the fixture set is `committed-hdoc-files`, found by scanning the
  real filesystem rather than a hardcoded list -- `haselnuss.spec-test/
  clojure-sources`'s own idiom, applied to `.hdoc` instead of `.clj`, and
  for the identical reason: a document newly committed to `examples/` or
  `test/fixtures/` is covered here without editing this namespace. Both
  directories are read relative to the process's own working directory,
  which is the repository root for every path `lein test`/`./bin/lint`
  run from (CLAUDE.md, \"Finishing a task\") -- unlike `haselnuss.example-
  test`/`haselnuss.thesis-test`'s own `io/resource` idiom, which those
  namespaces need because they copy a fixture into a temp directory and
  shell out to `pdflatex` there; this namespace parses and emits
  in-process and writes nothing, so it has no such reason to avoid a
  relative path.

  AC #4: a mismatch reports the first differing node and its path
  (`first-difference`) or the first differing character (`first-string-
  difference`) rather than a whole-AST or whole-file diff -- both stop at
  the FIRST divergence and print only what is found there, truncated,
  never the surrounding document. `first-difference`'s own path is a
  plain vector of map keys and, right after a sequential-valued key, an
  index -- exactly the shape `haselnuss.walk/fold` already produces
  walking a single tree (that namespace's own docstring: 'a Table's
  third row's second cell's first Block ... is `[:rows 2 :cells 1
  :blocks 0]`'), reached here by a generic two-tree EDN walk instead of
  a second copy of that namespace's schema-derived traversal: a parsed
  Document is itself plain maps and vectors of keyword-tagged nodes
  (`haselnuss.ast`'s own registry has no other representation), so
  walking two of them together in schema-blind lockstep already lands on
  the same path a single-tree `fold` visit would.

  AC #5: the runtime this namespace itself adds is recorded in the task
  notes, not here -- the catechism (`examples/catecismo-heidelberg.hdoc`)
  is the one fixture large enough for it to matter, and it does not
  need pdflatex, a nested `lein` invocation, or an uberjar rebuild the
  way the three namespaces CLAUDE.md already calls out do."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.emit.hdoc :as hdoc]
            [haselnuss.parser :as parser])
  (:import (java.io File)))

(defn- committed-hdoc-files
  "AC #3: every `.hdoc` file committed to this repository, found by
  scanning `examples/` and `test/fixtures/` -- the two directories one
  actually lives in -- rather than a hardcoded list, mirroring
  `haselnuss.spec-test/clojure-sources`'s own 'derive the fixture set
  from the real filesystem' idiom. Sorted by path for a stable,
  reproducible test order."
  []
  (->> (concat (file-seq (io/file "examples")) (file-seq (io/file "test/fixtures")))
       (filter (fn [^File f] (and (.isFile f) (str/ends-with? (.getName f) ".hdoc"))))
       (sort-by (fn [^File f] (.getPath f)))))

(defn- truncate
  "`s` cut to at most 300 characters, with a trailing marker -- long
  enough to show a real node, short enough that a whole node's worth of
  nested content sitting at the point of divergence cannot blow the
  report back up into the whole-AST diff AC #4 rules out."
  [s]
  (if (> (count s) 300) (str (subs s 0 300) "...(truncated)") s))

(defn- leaf-diff
  "The `first-difference` result for a genuine leaf divergence at
  `path`: both values already reduced to a short, printable string
  (`pr-str` then `truncate`), so the failure this ultimately feeds into
  (`hdoc-round-trip-test`'s own `(is (nil? diff) ...)`) stays small even
  when `expected`/`actual` is itself a whole Block or Inline subtree --
  a single node, not the rest of the document around it."
  [path expected actual]
  {:path path :expected (truncate (pr-str expected)) :actual (truncate (pr-str actual))})

(defn- first-difference
  "AC #4: the path (`haselnuss.walk/fold`'s own path-vector shape -- a
  map key, or an index right after a sequential-valued key) to the
  FIRST point at which EDN values `expected` and `actual` diverge,
  walked together depth-first in a stable order -- nil when the two are
  equal. `path` threads the accumulated prefix (the 2-arity call starts
  it at `[]`, the same true root `fold` itself starts from).

  A map is walked key by key over the union of both sides' own keys, in
  sorted order, so a key present on only one side is itself reported
  (rather than treated as absent-so-equal) before either side's value is
  compared; a sequential (`:blocks`, `:inlines`, ...) is walked
  index by index up to the LONGER side's own length, so a length
  mismatch is reported at the first index past the shorter side rather
  than silently ignored. Anything else -- a keyword, a string, a number,
  a boolean -- is compared with `=` directly, the recursion's own base
  case."
  ([expected actual] (first-difference expected actual []))
  ([expected actual path]
   (cond
     (= expected actual) nil

     (and (map? expected) (map? actual))
     (let [ks (sort (into (set (keys expected)) (keys actual)))]
       (some (fn [k]
               (if (not= (contains? expected k) (contains? actual k))
                 (leaf-diff (conj path k) (get expected k ::missing) (get actual k ::missing))
                 (first-difference (get expected k) (get actual k) (conj path k))))
             ks))

     (and (sequential? expected) (sequential? actual))
     (let [n (max (count expected) (count actual))]
       (some (fn [i]
               (if (or (>= i (count expected)) (>= i (count actual)))
                 (leaf-diff (conj path i) (get expected i ::missing) (get actual i ::missing))
                 (first-difference (nth expected i) (nth actual i) (conj path i))))
             (range n)))

     :else
     (leaf-diff path expected actual))))

(defn- string-context
  "A ~80-character window of `text` centered on `index`, for a readable
  diff -- printing an entire re-emitted document (the catechism is ~350
  KB) at the first sign of trouble would defeat AC #4's own point just
  as badly as a whole-AST diff would."
  [text index]
  (subs text (max 0 (- index 40)) (min (count text) (+ index 40))))

(defn- first-string-difference
  "AC #4's own text-identity counterpart, for AC #2: the index of the
  first character at which `expected` and `actual` diverge, plus a
  short window of each around it (`pr-str`'d, so a newline in the
  window is visible rather than silently breaking the report's own
  layout) -- nil when the two strings are equal. AC #2's property is a
  STRING identity (`emit` jittering between two calls given the same
  AST input), not a tree, so this reports a text position rather than
  an AST path the way `first-difference` does."
  [expected actual]
  (when (not= expected actual)
    (let [n (min (count expected) (count actual))
          index (loop [i 0]
                  (cond
                    (>= i n) i
                    (not= (nth expected i) (nth actual i)) i
                    :else (recur (inc i))))]
      {:index index
       :expected (pr-str (string-context expected index))
       :actual (pr-str (string-context actual index))})))

(deftest hdoc-round-trip-test
  (testing "TASK-99: every .hdoc committed to this repository holds both
            the AST-fixed-point and the emit-stability property"
    (doseq [^File f (committed-hdoc-files)]
      (testing (.getPath f)
        (let [src (slurp f)
              document1 (parser/parse src)
              text2 (hdoc/emit-document document1)
              document2 (parser/parse text2)
              text3 (hdoc/emit-document document2)]
          (testing "AC #1: parse(emit(parse(src))) = parse(src)"
            (let [diff (first-difference document1 document2)]
              (is (nil? diff) (when diff (str "first differing node at path " (:path diff))))))
          (testing "AC #2: emit(parse(src)) is byte-identical to emit(parse(emit(parse(src))))"
            (let [diff (first-string-difference text2 text3)]
              (is (nil? diff) (when diff (str "first differing character at index " (:index diff)))))))))))

(deftest fixture-set-is-not-empty-test
  (testing "AC #3's own guard: the derivation actually finds the
            documents this task names, so a change to `committed-hdoc-
            files` that silently stopped scanning one of the two
            directories fails loudly here rather than passing
            `hdoc-round-trip-test` vacuously on an empty `doseq`.
            `draft.hdoc` (TASK-104, examples/publish.clj's own worked
            example) and `review.hdoc` (TASK-124, the annotation overlay
            over hazelnuts.hdoc) are the 6th and 7th names here, each
            added the way this namespace's own docstring says a newly
            committed .hdoc should be: by nothing more than existing on
            disk under examples/, with this literal set updated alongside
            it as the canary AC #3 asks for.

            An overlay file is a .hdoc like any other and holds the same
            property: `review.hdoc` is nothing but directives, and the
            emitter has to write each one back so it re-parses to the
            same directive -- including the one-line `!highlight{...}`
            form, which TASK-115 made the emitter choose for a body-less
            directive.

            The rest are TASK-151's fixture libraries under
            test/fixtures/libraries/, books and non-books alike (an
            !include'd chapter, ignored and invalidly named files)."
    (let [names (into #{} (map (fn [^File f] (.getName f))) (committed-hdoc-files))]
      (is (= #{"catecismo-heidelberg.hdoc" "draft.hdoc" "hazelnuts.hdoc" "review.hdoc"
               "reveal-demo.hdoc" "thesis.hdoc" "cross-format.hdoc"
               ;; test/fixtures/libraries/ (TASK-151)
               "heidelberg.hdoc" "book.hdoc" "ch01.hdoc" "Not_A_Book.hdoc" "apostles.hdoc"
               "Nicene.hdoc" "notes.hdoc" "Bad Name.hdoc" "hidden-by-shelf.hdoc" "wip.hdoc"
               "secret.hdoc" "_skip.hdoc" ".dotted.hdoc" "same.hdoc" "other.hdoc"
               ;; TASK-152
               "book-refs.hdoc"
               ;; test/fixtures/libraries/passages/ (TASK-153)
               "comfort.hdoc" "nicene.hdoc"
               ;; ... and its book that quotes (TASK-154)
               "quoting.hdoc"
               ;; test/fixtures/libraries/generated/, beside a generated shelf (TASK-158)
               "salmos-notas.hdoc"}
             names)))))

(deftest book-refs-fixture-test
  (testing "TASK-152 AC #4: test/fixtures/book-refs.hdoc, which
            `hdoc-round-trip-test` holds to both properties above, really
            does contain both book nodes -- so that round trip is not
            vacuous -- and the emitter writes each ref back verbatim"
    (let [src (slurp "test/fixtures/book-refs.hdoc")
          document (parser/parse src)
          out (hdoc/emit-document document)
          refs (fn [t] (->> (tree-seq coll? seq document)
                            (filter #(and (map? %) (= t (:t %))))
                            (mapv :ref)))]
      (is (= ["bible:romanos#14:8" "reformed:confessions/reformed/heidelberg#sec:q1"
              "reformed:heidelberg#sec:q1..sec:q3" "Not A Ref"]
             (refs :book-ref)))
      (is (= ["bible:romanos#14:8-9" "bible:romanos#14:8" "reformed:heidelberg#sec:q1..sec:q3"]
             (refs :book-quote)))
      (is (= ["sec:proofs"] (mapv :label (filter #(and (map? %) (= :cross-ref (:t %)))
                                                 (tree-seq coll? seq document))))
          "the bare @sec:proofs stays a cross-reference")
      (doseq [written (refs :book-ref)]
        (is (str/includes? out (str "@{" written "}")) written))
      (doseq [written (refs :book-quote)]
        (is (str/includes? out (str "!quote " written "\n")) written))
      (is (str/includes? out "@\\{not:a-ref}")))))
