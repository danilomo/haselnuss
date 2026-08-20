(ns haselnuss.spec-test
  "The in-repo format specification (TASK-62).

  Every namespace under `src/` is written against `SPEC.md` and cites it
  by section number. That was true before this file existed too -- the
  difference is that the specification lived in a sibling directory
  outside version control, so the contract the whole codebase is written
  against could not be read from a clone.

  Bringing it in makes two things checkable that were not, and both are
  checked here rather than trusted:

  - no docstring still points at the external copy, which would send a
    reader back to the machine-specific path this move exists to remove;
  - every section a docstring cites is a section the file actually has,
    so a citation cannot rot into a pointer at nothing.

  A specification is prose and this suite is not going to assert on its
  sentences. What it can hold is the two mechanical properties above --
  which are exactly the ones that decay silently."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.io File)))

(def ^:private spec-file (io/file "SPEC.md"))

(defn- clojure-sources
  "Every `.clj` file under `src` and `test` -- the files that cite the
  specification -- except this one, which necessarily contains the
  string it is checking for."
  []
  (->> (concat (file-seq (io/file "src")) (file-seq (io/file "test")))
       (filter (fn [^File f] (and (.isFile f) (str/ends-with? (.getName f) ".clj"))))
       (remove (fn [^File f] (= "spec_test.clj" (.getName f))))))

(def ^:private commonmark-sections
  "Section numbers cited from the COMMONMARK specification rather than
  this project's own -- backslash escapes, link reference definitions,
  link destinations, autolinks. Listed rather than pattern-matched because the two
  specifications number their sections in the same shape, and a citation
  of one is indistinguishable from a citation of the other without
  reading the sentence around it. Every one of these appears in a
  sentence that names CommonMark; none of them is a section `SPEC.md`
  has."
  #{"2.4" "4.7" "6.3" "6.4" "6.5"})

(deftest spec-is-in-the-repository-test
  (testing "TASK-62 AC #1: the specification is a file in this
            repository, not a path on one machine"
    (is (.isFile spec-file))
    (is (< 500 (count (str/split-lines (slurp spec-file))))
        "and it is the whole document, not a stub pointing elsewhere")))

(deftest no-source-cites-the-external-spec-test
  (testing "TASK-62 AC #2: no docstring sends a reader to the copy
            outside this repository. `haselnuss-old` is a sibling
            directory on one machine; a reader of a clone cannot follow
            it, which is the whole reason the file moved in"
    (doseq [^File f (clojure-sources)]
      (let [offenders (->> (str/split-lines (slurp f))
                           (map-indexed (fn [i line] [(inc i) line]))
                           (filter (fn [[_ line]] (str/includes? line "haselnuss-old/SPEC.md"))))]
        (is (empty? offenders)
            (str (.getPath f) " still cites the external specification: " (pr-str offenders)))))))

(deftest every-cited-section-exists-test
  (testing "TASK-62 AC #1: a `secN.M` citation names a section `SPEC.md`
            really has. This is the check that keeps the in-repo file
            honest as it is edited: renaming or renumbering a section
            silently breaks every docstring pointing at it, and nothing
            else in this suite reads those pointers"
    (let [spec (slurp spec-file)
          headings (into #{} (map second) (re-seq #"(?m)^#{2,3} (\d+(?:\.\d+)?)" spec))
          cited (into #{}
                      (comp (mapcat (fn [^File f]
                                      (map second (re-seq #"sec(\d+(?:\.\d+)?)" (slurp f)))))
                            (remove commonmark-sections))
                      (clojure-sources))]
      (is (seq cited) "the sources really do cite the specification")
      (is (contains? headings "4.3") "and the heading scan really finds sections")
      (doseq [section (sort cited)]
        (is (contains? headings section)
            (str "sec" section " is cited by a source file but SPEC.md has no such section"))))))

(deftest unimplemented-behaviour-is-marked-test
  (testing "TASK-62 AC #3: the specification is a specification AND a
            roadmap, so it describes behaviour that does not exist. Those
            places carry an explicit marker, and the file says at the top
            what the marker means -- otherwise a reader has no way to
            tell a description from an intention"
    (let [spec (slurp spec-file)
          ;; Fenced blocks removed first, so the legend's own EXAMPLE of
          ;; the marker does not count toward the markings it explains
          ;; (found by review: with it counted, deleting three real
          ;; notes left this green).
          prose (str/replace spec #"(?s)```.*?```" "")]
      (is (str/includes? prose "**Not implemented.**"))
      (is (<= 6 (count (re-seq #"\*\*Not implemented" prose)))
          "several sections need it, not one")
      (is (str/includes? spec "## How to read this file")
          "and the convention is stated where a reader starts")
      (is (str/includes? spec "> **Not implemented.** …what the code does instead.")
          "and the legend shows the exact form, inside a fence so it is an example"))))

(deftest readme-points-at-the-spec-test
  (testing "TASK-62 AC #4: the README names SPEC.md as the format
            reference, since that is where a reader of this repository
            starts"
    (let [readme (slurp (io/file "README.md"))]
      (is (str/includes? readme "SPEC.md"))
      (is (re-find #"\[`SPEC\.md`\]\(SPEC\.md\)" readme)
          "as a link, so it is one click from the front page"))))
