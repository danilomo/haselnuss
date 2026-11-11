(ns haselnuss.emit.hdoc-generative-test
  "TASK-236: a permanent, seeded, bounded generative property test for the
  `.hdoc` writer. TASK-220..235 found about fifteen round-trip bugs one at a
  time, each with a throwaway sweep of generated sources; this is that sweep,
  kept.

  The property, per generated source `s`:

      s -> parser/parse -> hdoc/emit-document -> parser/parse

  either yields the first AST again, or the writer refused with an ex-info
  whose `:type` is one of its own `::hdoc/unrepresentable-*` keywords (a
  documented refusal, not a bug). A source the parser itself rejects is
  skipped (counted, so the floors below see it).

  Reproducing a failure: every case is `(case-source seed i)`; the report
  prints the seed, the index, the source, the writer's output and the first
  difference between the two parses (`haselnuss.hdoc-round-trip-test`'s
  differ is private, so a small copy lives here). Paste the source into a
  pinned test in `haselnuss.emit.hdoc-test`.

  KNOWN GAPS (DRAFT-1 and the measured NEW findings in TASK-236's notes) are
  excluded by the named predicates in `known-gaps`, each narrow, each with a
  count assertion: each gap carries a `:repro` that must still FAIL (a fixed
  gap makes the entry stale and `known-gaps-are-not-stale-test` says so), and
  the common ones a `:min` of generated failures they must excuse (so the
  generator keeps reaching the shape). An exclusion applies only to a source
  that really fails and matches the gap's predicate; it is never a catch of
  failures in general. Predicates are not perfectly precise (some sources they
  match round-trip fine); see the TASK-236 notes for the measured numbers."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.emit.hdoc :as hdoc]
            [haselnuss.parser :as parser]))

;; ---------------------------------------------------------------- generator

(def suite-seed
  "The suite's seed (the task number); case `i` uses `(Random. (+ seed i))`."
  236)

(def ^:private chars-hostile
  ["`" " " "\t" "\n" "\r" "|" "[" "]" "(" ")" "{" "}" "@" "$" "!" ":" "*" "_" "~" "^" "=" "\\"
   "1" "2" "a" "b" "x" "-" "." "#" ">" "+" "<"])

(def ^:private pieces
  "Multi-character tokens that start or end constructs, mixed with the raw
  characters above."
  ["a" "b c" "``" "```" "`x`" "*e*" "_e_" "~~s~~" "[l](u)" "[^1]" "[^2]" "@k" "@{a:b}" "$m$" "$$"
   "{.c}" "{#i k=v}" "![i](p.png)" "\n\n" "\r\n" "  " "\t" "1. " "- " "> " "||"])

(def ^:private pipe-pieces
  "Pipes inside constructs that a table cell must write bare or escaped
  (TASK-221/225/227/230): math, book refs, code spans, attribute groups."
  ["$a|b$" "$a\\|b$" "@{a|b}" "@{a\\|b}" "`a|b`" "`a\\|b`" "[t]{.c|d}" "[t]{k=\"a|b\"}" "a|b" "\\|" "|" " " "x"])

(def ^:private refusal-prone
  "A small alphabet over what the writer cannot always spell back: backticks,
  every line end, tabs, pipes, a quote of each kind in an attribute value, a
  link-looking and a citation-looking token."
  ["`" "``" "a" " " "\n" "\r" "\t" "|" "\\" "k=\"a'b\"" "@k" "[@k]" ";" "p. 3" "b"])

(def contexts
  "Name -> template; `%s` is replaced by the generated fragment. Every one of
  the task's contexts appears, some more than once with a different frame."
  {:paragraph    "%s\n"
   :emphasis     "*%s* b\n"
   :link         "[%s](u)\n"
   :list-item    "- %s\n"
   :nested-list  "- a\n  - %s\n"
   :ordered-list "1. a\n2. %s\n"
   :loose-list   "- a\n\n- %s\n"
   :quote        "> %s\n"
   :quote-list   "> - %s\n"
   :footnote     "a[^1]\n\n[^1]: %s\n"
   :footnote-list "- a[^1]\n\n  [^1]: %s\n"
   :footnote-list-2 "- a[^1]\n\n- b\n\n  [^1]: %s\n"
   :footnote-nested "- a[^1]\n\n  - b\n\n  [^1]: %s\n"
   :footnote-outer "- - a[^1]\n\n  [^1]: %s\n"
   :footnote-ordered-outer "1. - a[^1]\n\n   [^1]: %s\n"
   :footnote-two "- a[^1] b[^2]\n\n  [^1]: %s\n\n  [^2]: y\n"
   :footnote-quote "> a[^1]\n>\n> [^1]: %s\n"
   :table-cell   "| h | i |\n|---|---|\n| %s | b |\n"
   :heading      "# %s\n"
   :setext       "%s\n===\n"
   :fenced       "```\n%s\n```\n"
   :quote-fence  "> ```\n> %s\n> ```\n"
   :list-fence   "- ```\n  %s\n  ```\n"
   :code-span    "a `%s` b\n"
   :code-span-2  "a ``%s`` b\n"
   :code-span-pad "a `` %s `` b\n"
   :code-span-3  "a ``` %s ``` b\n"
   :list-code-span "- a `%s` b\n"
   :quote-code-span "> a `` %s `` b\n"
   :table-head   "| %s | i |\n|---|---|\n| b | c |\n"
   :table-3col   "| h | %s | j |\n|---|---|---|\n| x | %s | y |\n"
   :table-code   "| h | i |\n|---|---|\n| `%s` | b |\n"
   :link-cite    "[see @k, %s](u)\n"
   :citation     "[@k, %s]\n"
   :inline-math  "a $%s$ b\n"
   :display-math "$$\n%s\n$$\n"
   :book-ref     "@{%s}\n"
   :attr-group   "a [b]{%s}\n"
   :heading-attr "# h {%s}\n"})

(defn- pick [^java.util.Random rng xs] (nth xs (.nextInt rng (count xs))))

(defn- fragment [^java.util.Random rng]
  (let [n (inc (.nextInt rng 6))
        mode (.nextInt rng 4)
        pool (case mode 0 chars-hostile 1 pieces 2 refusal-prone 3 pipe-pieces)]
    (apply str (repeatedly n #(pick rng (if (and (< mode 2) (zero? (.nextInt rng 4))) (if (zero? mode) pieces chars-hostile) pool))))))

(defn case-source
  "The `[context source]` of case `i` under `seed`: reproducible from the two
  numbers alone."
  [seed i]
  (let [rng (java.util.Random. (+ seed i))
        ctx (pick rng (vec (sort (keys contexts))))
        tmpl (contexts ctx)
        frag (fragment rng)
        ;; string/string replace is literal: `$` and `\` in frag stay as written
        src (str/replace tmpl "%s" frag)]
    [ctx src]))

;; ------------------------------------------------------------------ oracle

(defn- diff
  "The first point at which two EDN trees differ: `{:path .. :expected .. :actual ..}`."
  ([a b] (diff a b []))
  ([a b path]
   (let [leaf (fn [p x y] {:path p :expected (pr-str x) :actual (pr-str y)})]
     (cond (= a b) nil
           (and (map? a) (map? b))
           (some (fn [k] (if (not= (contains? a k) (contains? b k))
                           (leaf (conj path k) (get a k ::missing) (get b k ::missing))
                           (diff (get a k) (get b k) (conj path k))))
                 (sort (into (set (keys a)) (keys b))))
           (and (sequential? a) (sequential? b))
           (some (fn [i] (if (or (>= i (count a)) (>= i (count b)))
                           (leaf (conj path i) (get a i ::missing) (get b i ::missing))
                           (diff (nth a i) (nth b i) (conj path i))))
                 (range (max (count a) (count b))))
           :else (leaf path a b)))))

(defn- unrepresentable?
  [e]
  (let [t (:type (ex-data e))]
    (and (keyword? t) (= "haselnuss.emit.hdoc" (namespace t)) (str/starts-with? (name t) "unrepresentable-"))))

(defn classify
  "`{:status s ...}` for one source: `:unparsable`, `:ok` (re-parses equal),
  `:refused` (known ex-info type, kept as `:type`), `:crashed` (the writer or
  the second parse threw something else) or `:bad` (carries `:emitted` and
  the `:diff`)."
  [src]
  (let [d (try (parser/parse src) (catch Exception _ ::none))]
    (if (= ::none d)
      {:status :unparsable}
      (let [out (try (hdoc/emit-document d) (catch Exception e e))]
        (cond (and (instance? Exception out) (unrepresentable? out))
              {:status :refused :type (:type (ex-data out))}
              (instance? Exception out)
              {:status :crashed :error (str out)}
              :else
              (let [d2 (try (parser/parse out) (catch Exception e e))]
                (cond (instance? Exception d2) {:status :bad :emitted out :diff {:reparse-threw (str d2)}}
                      (= d d2) {:status :ok}
                      :else {:status :bad :emitted out :diff (diff d d2)})))))))

(defn report
  "The readable failure text: everything needed to reproduce and pin the case."
  [seed i ctx src result]
  (str "seed " seed " index " i " context " ctx
       "\n  source:  " (pr-str src)
       (when (:emitted result) (str "\n  emitted: " (pr-str (:emitted result))))
       (when (:diff result) (str "\n  diff:    " (pr-str (:diff result))))
       (when (:error result) (str "\n  error:   " (:error result)))))

;; -------------------------------------------------------------- known gaps

(defn- nodes
  "Every map in `doc` with the number of container blocks (`:block-quote`,
  `:list`, `:note`) around it: `[node depth]`."
  [doc]
  (letfn [(walk [x depth]
            (cond (map? x) (let [depth' (if (#{:block-quote :list :note} (:t x)) (inc depth) depth)]
                             (cons [x depth] (mapcat #(walk % depth') (vals x))))
                  (sequential? x) (mapcat #(walk % depth) x)
                  :else nil))]
    (walk doc 0)))

(defn- cr-before-line-end? [s] (boolean (re-find #"\r\n|\r\z" s)))

(defn- top-level-code-with-cr-line-end?
  "NEW-2: a code block outside any quote, list or footnote whose text holds a CR
  right before an LF, or ends in a CR: the writer puts the text in a fence as it
  is, and the parser reads that CR as part of the line end it normalizes."
  [_src doc]
  (boolean (some (fn [[n depth]] (and (zero? depth) (= :code-block (:t n)) (cr-before-line-end? (:text n))))
                 (nodes doc))))

(defn- space-after-soft-break?
  "NEW-5: a `:space` right after a `:soft-break` (a continuation line that
  kept its leading blank, seen after a citation); the writer drops it as the
  re-parse would, so the second parse has none."
  [_src doc]
  (letfn [(seqs [x] (cond (map? x) (mapcat seqs (vals x))
                          (sequential? x) (cons x (mapcat seqs x))
                          :else nil))]
    (boolean (some (fn [xs] (some (fn [[a b]] (and (map? a) (map? b) (= :soft-break (:t a)) (= :space (:t b))))
                                  (partition 2 1 xs)))
                   (seqs doc)))))

(defn- container-code-with-cr?
  "DRAFT-1 (1): a lone CR in a fenced block's text inside a quote, list or
  footnote. Code spans (`:code`) are deliberately NOT matched: they mostly
  round-trip (measured 0 of the failures are spans), so matching them would
  only mask other bugs."
  [_src doc]
  (boolean (some (fn [[n depth]] (and (pos? depth) (= :code-block (:t n)) (string? (:text n))
                                      (str/includes? (:text n) "\r")))
                 (nodes doc))))

(defn- empty-cell-paragraph?
  "NEW-3: a table cell whose paragraph has no inlines at all (a CR in a row
  line leaves one); the writer writes `|  |`, which reads back with a space."
  [_src doc]
  (boolean (some (fn [[n _]] (and (= :table (:t n))
                                  (some (fn [c] (some #(and (= :para (:t %)) (empty? (:inlines %))) (:blocks c)))
                                        (mapcat :cells (cons (:head n) (:rows n))))))
                 (nodes doc))))

(defn- unreferenced-definition?
  "DRAFT-1 (5)/(4): a `[^n]:` definition whose label has no `[^n]` reference in
  the source; the parser drops it and leaves a hole (an empty item, a loose
  list) the writer does not reproduce. The failing shape (the repro
  `- a\\n  - [^2]:\\n`) is an orphan definition that leaves an EMPTY list item,
  so the predicate also requires one. It had 0 hits in a 100k sweep: only the
  `:repro` keeps this entry honest."
  [src doc]
  (and (boolean (some (fn [[_ l]]
                        (let [all (count (re-seq (re-pattern (str "\\[\\^" l "\\]")) src))
                              defs (count (re-seq (re-pattern (str "\\[\\^" l "\\]:")) src))]
                          (= all defs)))
                      (re-seq #"\[\^(\d+)\]:" src)))
       (boolean (some (fn [[n _]] (and (= :list (:t n)) (some empty? (:items n)))) (nodes doc)))))

(defn- directive-after-list-code?
  "NEW-4: a directive block right after a list whose last item ends in a fenced
  block: written `\\n\\n!name`, it reads back as a paragraph."
  [_src doc]
  (boolean (some (fn [[n _]]
                   (some (fn [[a b]] (and (= :list (:t a)) (= :directive (:t b))
                                          (some #(= :code-block (:t %)) (last (:items a)))))
                         (partition 2 1 (:blocks n))))
                 (nodes doc))))

(def known-gaps
  "Each gap: `:id`, `:ref` (where it is recorded), `:pred` over `[src doc]`
  (narrow: it must name the shape, not the symptom), `:repro` (a source that
  fails today -- if it stops failing the gap is `stale`) and `:min` (how many of
  the generated sources must be excluded by it, so the generator keeps reaching
  the shape)."
  [{:id :top-level-code-with-cr-line-end :ref "TASK-236 notes, NEW-2" :pred top-level-code-with-cr-line-end?
    :repro "```\na\r\r\n```\n" :min 0}
   {:id :space-after-soft-break :ref "TASK-236 notes, NEW-5" :pred space-after-soft-break?
    :repro "[@k, [@k]\r \t]\n" :min 0}
   {:id :container-code-with-cr :ref "DRAFT-1 (1)" :pred container-code-with-cr?
    :repro "> ```\n> a\r> \n> ```\n" :min 10}
   {:id :empty-table-cell-paragraph :ref "TASK-236 notes, NEW-3" :pred empty-cell-paragraph?
    :repro "| h | i |\n|---|---|\n| \r | b |\n" :min 10}
   {:id :unreferenced-definition :ref "DRAFT-1 (4)/(5)" :pred unreferenced-definition?
    :repro "- a\n  - [^2]:\n" :min 0}
   {:id :directive-after-list-code :ref "TASK-236 notes, NEW-4" :pred directive-after-list-code?
    :repro "- ```\n  \n!aa\n  ```\n" :min 0}])

(defn- gaps-of
  "Every gap whose predicate claims the failure (all of them, so an overlap is
  visible rather than hidden by the first match)."
  [src doc]
  (vec (keep (fn [g] (when ((:pred g) src doc) (:id g))) known-gaps)))

;; ------------------------------------------------------------------- tests

(defn run
  "Classifies `n` cases from `seed`; each result also carries `:i :ctx :src`
  and, for a `:bad`, the `:gaps` (every gap id claiming it; empty: a real failure)."
  [seed n]
  (vec (for [i (range n)]
         (let [[ctx src] (case-source seed i)
               r (classify src)]
           (cond-> (assoc r :i i :ctx ctx :src src)
             (#{:bad :crashed} (:status r)) (assoc :gaps (when (= :bad (:status r)) (gaps-of src (parser/parse src)))))))))

(def ^:private cases
  "About 0.4 ms a case: 10000 cases is a few seconds next to the ~90 s suite."
  10000)

(def ^:private suite-results (delay (run suite-seed cases)))

(deftest generative-round-trip-test
  (let [results @suite-results
        by-status (group-by :status results)
        freq (frequencies (map :status results))
        unexcused (remove (comp seq :gaps) (concat (:bad by-status) (:crashed by-status)))]
    (testing "no failure is claimed by two gap predicates (they stay disjoint)"
      (is (= [] (map #(select-keys % [:i :src :gaps]) (filter #(< 1 (count (:gaps %))) (:bad by-status))))))
    (testing "every generated source round-trips, is refused with a known type, or is a named known gap"
      (is (= [] (map #(report suite-seed (:i %) (:ctx %) (:src %) %) (take 5 unexcused)))
          (str (count unexcused) " unexcused failures (first five below); pin the source in hdoc-test.clj")))
    (testing "non-vacuity floors"
      (is (< 4000 (:ok freq 0)) (pr-str freq))
      (is (< 30 (:refused freq 0)) (pr-str freq))
      (is (< (:unparsable freq 0) (* 0.25 cases)) (pr-str freq))
      (doseq [ctx (keys contexts)]
        (is (<= 40 (count (filter #(and (= ctx (:ctx %)) (= :ok (:status %))) results)))
            (str "context " ctx " round-trips often enough")))
      ;; refusal families: a context name prefix -> minimum refused sources
      (doseq [[prefix lo] {"table" 5 "code-span" 5 "link-cite" 3}]
        (is (<= lo (count (filter #(and (str/starts-with? (name (:ctx %)) prefix) (= :refused (:status %))) results)))
            (str "contexts " prefix "* are refused often enough"))))))

(deftest known-gaps-are-not-stale-test
  (testing "each repro still fails and is excused by exactly its own predicate"
    (doseq [{:keys [id repro pred]} known-gaps]
      (is (= :bad (:status (classify repro))) (str id " no longer fails: remove it from known-gaps"))
      (is (pred repro (parser/parse repro)) (str id " predicate no longer matches its own repro"))))
  (testing "each gap excuses at least :min of the generated failures"
    (let [freq (frequencies (mapcat :gaps (filter #(= :bad (:status %)) @suite-results)))]
      (doseq [{:keys [id] lo :min} known-gaps]
        (is (<= lo (get freq id 0)) (str id " excuses " (get freq id 0) " < " lo " failures: stale or generator drift"))))))
