(ns haselnuss.code-block-whitespace-test
  "TASK-314: a whitespace-only line of a code block inside a list item (or
  a footnote) keeps its whitespace past the container's indentation.
  flexmark empties such a line -- its list item swallows a line that is
  blank from where the item sees it -- and the writers then reproduce the
  damaged text faithfully, so a round trip alone proves nothing: every
  check here is against the code string the source was built from."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.emit.hdoc :as hdoc]
            [haselnuss.emit.html :as html]
            [haselnuss.parser :as parser]))

(defn- code-texts
  "The `:text` of every `:code-block` in `x`, in document order."
  [x]
  (cond
    (map? x) (if (= :code-block (:t x)) [(:text x)] (mapcat code-texts (vals x)))
    (sequential? x) (mapcat code-texts x)
    :else []))

(defn- texts [src] (vec (code-texts (parser/parse src))))

(def ^:private report-code
  "The bug report's code: an empty line, spaces only, a tab only, mixed,
  and nonblank lines with leading and trailing whitespace."
  "\n  left  \n    \n\t\n \t \n\tright\t \n  \n")

(def ^:private fence (str "```text\n" report-code "```\n"))

(defn- indent-lines
  "`s` with `prefix` put in front of every line (`first-prefix` on the first)."
  ([prefix s] (indent-lines prefix prefix s))
  ([first-prefix prefix s]
   (apply str (map-indexed (fn [i line] (str (if (zero? i) first-prefix prefix) line "\n"))
                           (str/split-lines s)))))

(def ^:private list-cases
  {"2-space bullet item" (str "- Item\n\n" (indent-lines "  " fence))
   "wide ordered marker" (str "10. Item\n\n" (indent-lines "    " fence))
   "nested list" (str "- a\n\n  - b\n\n" (indent-lines "    " fence))
   "list inside a directive" (str ":::{note}\n- Item\n\n" (indent-lines "  " fence) ":::\n")
   "fence on the marker line" (indent-lines "- " "  " fence)
   "list inside a block quote" (str "> - Item\n>\n" (indent-lines ">   " fence))
   "fence indented by itself in the item" (str "- Item\n\n" (indent-lines "    " fence))
   "footnote" (str "Text[^n]\n\n[^n]: Note\n\n" (indent-lines "    " fence))})

(def ^:private unchanged-cases
  {"top level" fence
   "block quote" (indent-lines "> " fence)
   "quote inside a list" (str "- Item\n\n" (indent-lines "  > " fence))
   "directive" (str ":::{note}\n" fence ":::\n")})

(deftest list-item-code-keeps-whitespace-lines-test
  (testing "AC #1/#5: inside a list item the text is the code exactly"
    (doseq [[label src] list-cases]
      (testing label
        (is (= [report-code] (texts src))))))
  (testing "AC #2: outside one it is what it always was -- the code exactly"
    (doseq [[label src] unchanged-cases]
      (testing label
        (is (= [report-code] (texts src))))))
  (testing "AC #2: top-level indented code is unchanged"
    (is (= ["a\n  \n\t\nb\n"] (texts "    a\n      \n    \t\n    b\n")))))

(deftest only-container-indentation-is-removed-test
  (testing "AC #3: whitespace that is all container indentation stays an empty line"
    (is (= ["a\n\nb\n"] (texts "- ```\n  a\n  \n  b\n  ```\n")))
    (is (= ["a\n\nb\n"] (texts "- ```\n  a\n \n  b\n  ```\n")))
    (is (= ["a\n\nb\n"] (texts "10. ```\n    a\n    \n    b\n    ```\n"))))
  (testing "AC #3: a tab across the container boundary is split per tab stops of 4"
    ;; The 2-column item takes columns 0-1 of a tab spanning 0-3.
    (is (= ["a\n  \nb\n"] (texts "- ```\n  a\n\t\n  b\n  ```\n")))
    (is (= ["a\n   \nb\n"] (texts "- ```\n  a\n\t \n  b\n  ```\n")))
    ;; A 4-column item takes the whole tab.
    (is (= ["a\n\t\nb\n"] (texts "10. ```\n    a\n\t\t\n    b\n    ```\n")))
    ;; One space then a tab: the tab spans columns 1-3, the item takes column 1.
    (is (= ["a\n  \nb\n"] (texts "- ```\n  a\n \t\n  b\n  ```\n"))))
  (testing "the fence's own opener indent is removed too, as from a nonblank line"
    ;; Item content at column 2, fence at column 3: 3 columns come off.
    (is (= ["a\n    \nb\n"] (texts "- Item\n\n   ```\n   a\n       \n   b\n   ```\n")))
    (is (= ["a\n \nb\n"] (texts "- Item\n\n   ```\n   a\n    \n   b\n   ```\n")))
    (is (= ["a\n\nb\n"] (texts "- Item\n\n   ```\n   a\n   \n   b\n   ```\n"))))
  (testing "an indented code block in a list loses the item's and its own four columns"
    (is (= ["x\n   \ny\n"] (texts "- a\n\n      x\n         \n      y\n")))
    (is (= ["x\n\ny\n"] (texts "- a\n\n      x\n      \n      y\n")))))

(deftest list-item-code-through-the-writers-test
  (let [src (get list-cases "2-space bullet item")
        doc (parser/parse src)]
    (testing "AC #4: HTML shows the original whitespace lines"
      (is (str/includes? (html/emit-document doc) (str "<code class=\"language-text\">" report-code "</code>"))))
    (testing "AC #4: parse -> hdoc -> parse keeps the code, and the hdoc is byte-stable"
      (let [text2 (hdoc/emit-document doc)
            doc2 (parser/parse text2)]
        (is (= [report-code] (vec (code-texts doc2))))
        (is (= text2 (hdoc/emit-document doc2)))))))

;; --- Seeded fuzz (seed = 314) ------------------------------------------------

(defn- container
  "One random container: its opener (the text before the content on its
  first line) and the prefix every later line of it carries."
  [^java.util.Random rnd]
  (case (.nextInt rnd 3)
    0 (let [marker (["-" "*" "+"] (.nextInt rnd 3))
            gap (inc (.nextInt rnd 4))]
        {:kind :item :open (str marker (str/join (repeat gap " ")))
         :cont (str/join (repeat (+ (count marker) gap) " "))})
    1 (let [marker (["1." "10." "100)" "7)"] (.nextInt rnd 4))
            gap (inc (.nextInt rnd 4))]
        {:kind :item :open (str marker (str/join (repeat gap " ")))
         :cont (str/join (repeat (+ (count marker) gap) " "))})
    ;; A quote's continuation lines always carry `> `: its optional space
    ;; would otherwise eat a column of the code's own indentation.
    2 {:kind :quote :open (if (.nextBoolean rnd) "> " ">") :cont "> "}))

(defn- pop-quote-open
  "`stack` with a last container that is a quote opened by a bare `>` given
  the `> ` it continues with."
  [stack]
  (if (= ">" (:open (peek stack))) (conj (pop stack) (assoc (peek stack) :open "> ")) stack))

(defn- code-line
  "One random code line: whitespace only (possibly empty) about half the
  time, else `x` with random leading and trailing whitespace."
  [^java.util.Random rnd]
  (let [ws (fn [] (apply str (repeatedly (.nextInt rnd 5) #(if (< (.nextInt rnd 3) 2) \space \tab))))]
    (if (.nextBoolean rnd)
      (ws)
      (str (apply str (repeat (.nextInt rnd 3) \space)) (ws) "x" (ws)))))

(defn- fuzz-case
  "`{:src :code :list?}`: a fenced code block of random lines inside a random
  stack of list items and quotes (opened one per line, or all on the fence
  line), with a random fence indent of 0-3, sometimes inside a footnote or
  a directive."
  [^java.util.Random rnd]
  (let [stack (vec (repeatedly (.nextInt rnd 4) #(container rnd)))
        one-line? (.nextBoolean rnd)
        last-item (peek stack)
        ;; On the marker's line, the fence's indent and the marker's gap
        ;; together stay under 5 columns, or the fence is indented code.
        room (if (and one-line? (= :item (:kind last-item)))
               (- 5 (- (count (:open last-item)) (count (str/trimr (:open last-item)))))
               4)
        indent (str/join (repeat (.nextInt rnd room) " "))
        lines (vec (repeatedly (inc (.nextInt rnd 6)) #(code-line rnd)))
        code (str (str/join "\n" lines) "\n")
        cont (apply str (map :cont stack))
        head (if one-line?
               ;; `>` straight before the fence's own indent would take a
               ;; column of it as the quote's optional space.
               (str (apply str (map :open (pop-quote-open stack))) indent "```text\n")
               ;; One container per line, each after a blank line of the
               ;; ones outside it, so that an ordered list not starting
               ;; at 1 is never read as a paragraph's continuation.
               (str (apply str (map-indexed (fn [i c]
                                              (let [outer (apply str (map :cont (take i stack)))]
                                                (str (when (pos? i) (str (str/trimr outer) "\n"))
                                                     outer (:open c) "Item\n")))
                                            stack))
                    cont indent "```text\n"))
        body (apply str (map #(str cont indent % "\n") lines))
        doc (str head body cont indent "```\n")
        footnote? (and (not one-line?) (zero? (.nextInt rnd 5)))
        doc (if footnote?
              (str "Text[^n]\n\n[^n]: Note\n\n" (indent-lines "    " doc))
              doc)
        ;; Not around a fence on a marker's line: the directive scanner
        ;; does not see `- ```text` open a fence, takes its closer for an
        ;; opener and the directive's `:::` for code (pre-existing,
        ;; reported in TASK-314's notes).
        directive? (and (not one-line?) (zero? (.nextInt rnd 5)))]
    {:src (if directive? (str ":::{note}\n" doc ":::\n") doc)
     :code code
     :hit? (and (or footnote? (some #(= :item (:kind %)) stack))
                (some #(and (re-matches #"[ \t]+" %) true) lines))}))

(deftest seeded-fuzz-test
  (testing "AC #1/#2/#5: random code in random containers parses to the code exactly"
    (let [rnd (java.util.Random. 314)
          cases (vec (repeatedly 600 #(fuzz-case rnd)))
          failures (remove (fn [{:keys [src code]}] (= [code] (texts src))) cases)
          hits (count (filter :hit? cases))]
      (is (empty? failures) (pr-str (take 3 failures)))
      (testing "not vacuous: many cases put a nonempty whitespace-only line inside a list item or footnote"
        (is (< 250 hits) (str hits " hits"))))))

;; --- Oracle fuzz: flexmark's own nonblank line (seed = 314) ------------------
;;
;; The same raw line with an `x` appended is a nonblank line, which flexmark
;; strips correctly; the recovered whitespace-only line must be exactly that
;; line minus its `x`. Unlike the fuzz above, the container prefixes here
;; carry tabs: markers followed by a tab, and continuation prefixes with a
;; tab standing for four columns. (Adapted from the TASK-314 review probe.)

(defn- oracle-case
  [^java.util.Random rnd]
  (let [pick (fn [v] (v (.nextInt rnd (count v))))
        prefix (fn []
                 (case (.nextInt rnd 3)
                   0 (let [marker (pick ["-" "*" "1." "10." "3)"])
                           gap (inc (.nextInt rnd 4))]
                       {:item? true :open (str (pick ["" " " "  "]) marker (str/join (repeat gap " ")))})
                   1 {:item? true :open (str (pick ["-" "1."]) "\t")}
                   2 {:open (pick ["> " ">" " > "])}))
        stack (vec (repeatedly (inc (.nextInt rnd 3)) prefix))
        opener (apply str (map :open stack))
        cont (str/replace opener #"[^>\t]" " ")
        cont (if (.nextBoolean rnd) (str/replace cont "    " "\t") cont)
        indent (str/join (repeat (.nextInt rnd 3) " "))
        ws (apply str (repeatedly (.nextInt rnd 6) #(pick [\space \space \tab])))
        doc (fn [line] (str opener indent "```\n" cont indent line "\n" cont indent "```\n"))]
    {:blank (doc ws) :nonblank (doc (str ws "x")) :ws ws
     :item? (boolean (some :item? stack))
     :tab? (str/includes? (str cont ws) "\t")}))

(deftest oracle-fuzz-test
  (testing "a recovered whitespace-only line is what flexmark keeps of the same line with an x"
    (let [rnd (java.util.Random. 314)
          compared (for [_ (range 2500)
                         :let [{:keys [blank nonblank] :as c} (oracle-case rnd)
                               nb (texts nonblank)
                               b (texts blank)]
                         ;; Only cases flexmark reads as one fence: some
                         ;; prefixes break the structure, both ways alike.
                         :when (and (= 1 (count nb) (count b)) (str/ends-with? (first nb) "x\n"))]
                     (assoc c :expected (str (subs (first nb) 0 (- (count (first nb)) 2)) "\n") :actual (first b)))
          compared (vec compared)
          mismatches (remove #(= (:expected %) (:actual %)) compared)
          in-items (filter #(and (:item? %) (re-find #"[ \t]" (:expected %))) compared)]
      (is (empty? mismatches) (pr-str (take 3 mismatches)))
      (testing "not vacuous: most cases compare, many keep whitespace inside an item, with tabs"
        ;; Seed 314: 1838 compared, 1203 in items, 1126 of them with a tab;
        ;; flexmark's own empty line mismatches 914.
        (is (< 1700 (count compared)) (str (count compared) " compared"))
        (is (< 1000 (count in-items)) (str (count in-items) " in items with whitespace kept"))
        (is (< 900 (count (filter :tab? in-items))) (str (count (filter :tab? in-items)) " with a tab"))))))

;; --- Deep nesting (TASK-314 review) -------------------------------------------

(defn- nested-list
  "A list nested `depth` deep, the innermost item holding a fence whose
  middle source line is `middle` -- given whole, so `\"\"` is a truly empty
  line -- or, when `middle` is a function, `(middle prefix)` with `prefix`
  the containers' indentation."
  [depth middle]
  (let [ind (fn [k] (str/join (repeat (* 2 k) " ")))
        prefix (ind depth)]
    (str (apply str (for [k (range depth)] (str (ind k) "- i" k "\n\n")))
         prefix "```\n" prefix "a\n" (if (fn? middle) (middle prefix) middle) "\n"
         prefix "b\n" prefix "```\n")))

(deftest deep-nesting-test
  (testing "each enclosing item's indent is worked out once: these parses used
            to take 2^depth container walks per line (7 s at depth 20) and
            did not return at depth 40"
    (is (= ["a\n\nb\n"] (texts (nested-list 40 ""))) "an empty source line")
    (is (= ["a\n\nb\n"] (texts (nested-list 40 identity))) "only the containers' indentation")
    (is (= ["a\n \t\nb\n"] (texts (nested-list 40 #(str % " \t")))))
    (is (= ["a\n \t\nb\n"] (texts (nested-list 100 #(str % " \t")))))))
