(ns haselnuss.library.graph.backlinks-test
  "`backlinks-report` and its renderers (TASK-243, doc-11 \"--backlinks\"):
  a pure function of the graph's `edges-to` -- every graph here is built by
  hand, with no library directory behind it -- whose data both renderers
  print."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.book-ref :as book-ref]
            [haselnuss.library.graph :as graph]
            [haselnuss.library.graph.backlinks :as backlinks]
            [haselnuss.library.graph.registry :as registry]))

;; --- helpers -----------------------------------------------------------------

(defn- id
  ([lib slug] (book-ref/book-id lib slug))
  ([lib shelf slug] (assoc (book-ref/book-id lib slug) :shelf shelf)))

(defn- record
  [book-id edges]
  {:id book-id :hash "h" :files #{} :labels {} :edges (vec edges) :diagnostics []})

(defn- edge
  "An edge from `from` to the reference `written` (resolving to `to`
  unless `:problem` is given), on `line` of `file`."
  [from written to & {:keys [kind from-passage problem line file]}]
  (let [parsed (book-ref/parse-book-ref written)]
    (cond-> {:from (cond-> {:id from} from-passage (assoc :passage from-passage))
             :to (cond-> {:written written}
                   to (assoc :id to)
                   (:passage parsed) (assoc :passage (:passage parsed)))
             :kind (or kind :link)
             :status (if problem :dangling :resolved)}
      problem (assoc :problem problem)
      line (assoc :position (cond-> {:line line} file (assoc :file file))))))

(defn- graph-of [records]
  (reduce graph/with-book (registry/new-graph registry/default-registry :memory-index nil) records))

(def ^:private rom (id "bible" ["nt"] "romanos"))
(def ^:private heid (id "cat" "heidelberg"))
(def ^:private conf (id "ref" ["c"] "belgic"))

(def ^:private g
  "Edges into `bible:nt/romanos` from two other books and itself, in every
  form: whole book, locators that sort naturally (2:3 before 14:8), a
  label, a dangling unknown label, a dangling unknown book (no `:to
  :id`), plus edges into another book that must not show."
  (graph-of
   [(record heid [(edge heid "bible:romanos#14:8" rom :kind :quote :from-passage "q:1" :line 40 :file "/base/cat/h.hdoc")
                  (edge heid "bible:romanos#2:3" rom :kind :quote :from-passage "q:1" :line 41 :file "/base/cat/h.hdoc")
                  (edge heid "bible:romanos" rom :line 3 :file "/base/cat/h.hdoc")
                  (edge heid "bible:romanos#sec:x" rom :problem :unknown-label :line 9 :file "/base/cat/h.hdoc")
                  (edge heid "bible:joao#1:1" (id "bible" ["nt"] "joao") :line 12 :file "/base/cat/h.hdoc")])
    (record conf [(edge conf "bible:nt/romanos#14:8" rom :line 7 :file "/elsewhere/b.hdoc")
                  (edge conf "bible:romanos#14:8" rom :kind :quote :line 2 :file "/elsewhere/b.hdoc")])
    (record rom [(edge rom "bible:romanos#1:1" rom :from-passage "cap:2")])
    (record (id "bible" ["ot"] "romanos")
            ;; a same-slug book on another shelf: its resolved edges must not match
            [(edge (id "bible" ["ot"] "romanos") "bible:ot/romanos#1:1" (id "bible" ["ot"] "romanos"))])
    (record (id "x" "y") [(edge (id "x" "y") "bible:romanos#9:9" nil :problem :unknown-book :line 5)])]))

(defn- summary-of [report] (:summary report))

(defn- rows
  "Each entry as `[group source-id from-passage written kind status problem file line]`."
  [entries]
  (mapv (fn [e] [(backlinks/group-key e) (:id (:from e)) (:passage (:from e)) (:written (:to e))
                 (:kind e) (:status e) (:problem e) (:file (:position e)) (:line (:position e))])
        entries))

;; --- AC #1/#2: the report ------------------------------------------------------

(deftest report-groups-and-order-test
  (let [report (backlinks/backlinks-report g {:id rom} {:base-dir "/base"})]
    (is (= 1 (:version report)))
    (is (= "bible:nt/romanos" (:book report)))
    (is (nil? (:passage report)))
    (testing "whole book first, then passages in natural order; within one, by source then line"
      (is (= [[nil "cat:heidelberg" nil "bible:romanos" :link :resolved nil "cat/h.hdoc" 3]
              ["2:3" "cat:heidelberg" "q:1" "bible:romanos#2:3" :quote :resolved nil "cat/h.hdoc" 41]
              ["9:9" "x:y" nil "bible:romanos#9:9" :link :dangling :unknown-book nil 5]
              ["14:8" "cat:heidelberg" "q:1" "bible:romanos#14:8" :quote :resolved nil "cat/h.hdoc" 40]
              ["14:8" "ref:c/belgic" nil "bible:romanos#14:8" :quote :resolved nil "/elsewhere/b.hdoc" 2]
              ["14:8" "ref:c/belgic" nil "bible:nt/romanos#14:8" :link :resolved nil "/elsewhere/b.hdoc" 7]
              ["sec:x" "cat:heidelberg" nil "bible:romanos#sec:x" :link :dangling :unknown-label "cat/h.hdoc" 9]]
             (rows (:edges report)))))
    (testing "a dangling edge whose book is known only from what was written has no :to :id"
      (is (= [nil] (map (comp :id :to) (filter #(= "x:y" (:id (:from %))) (:edges report))))))
    (testing "self-references are listed apart, not counted as citations"
      (is (= [["1:1" "bible:nt/romanos" "cap:2" "bible:romanos#1:1" :link :resolved nil nil nil]]
             (rows (:self report))))
      (is (= {:references 7 :books 3 :self 1} (summary-of report))))
    (testing "the order does not depend on the order edges-to answers in"
      (let [shuffled (reify graph/ReferenceGraph
                       (with-book [_ r] (graph/with-book g r))
                       (without-book [_ i] (graph/without-book g i))
                       (indexed-books [_] (graph/indexed-books g))
                       (edges-from [_ i] (graph/edges-from g i))
                       (edges-to [_ target] (reverse (shuffle (vec (graph/edges-to g target)))))
                       (books-for-file [_ p] (graph/books-for-file g p)))]
        (is (= report (backlinks/backlinks-report shuffled {:id rom} {:base-dir "/base"})))))))

(deftest report-reads-only-edges-to-test
  (let [calls (atom [])
        spy (reify graph/ReferenceGraph
              (with-book [_ _] (throw (ex-info "with-book" {})))
              (without-book [_ _] (throw (ex-info "without-book" {})))
              (indexed-books [_] (throw (ex-info "indexed-books" {})))
              (edges-from [_ _] (throw (ex-info "edges-from" {})))
              (edges-to [_ target] (swap! calls conj target) (graph/edges-to g target))
              (books-for-file [_ _] (throw (ex-info "books-for-file" {}))))]
    (is (= 3 (:references (:summary (backlinks/backlinks-report spy {:id rom :passage "14:8"} {})))))
    (is (= [{:id rom :passage "14:8"}] @calls) "one edges-to call, with the target as given")))

(deftest passage-filter-test
  (testing "exact passage, whitespace-normalised, parsed or written"
    (doseq [p ["14:8" " 14:8 " {:form :locator :locator "14:8"}]]
      (let [report (backlinks/backlinks-report g {:id rom :passage p} {})]
        (is (= "14:8" (:passage report)))
        (is (= #{"14:8"} (set (map backlinks/group-key (:edges report)))))
        (is (= {:references 3 :books 2 :self 0} (:summary report))))))
  (testing "no overlap matching: 14:8-9 is not 14:8"
    (is (= 0 (:references (:summary (backlinks/backlinks-report g {:id rom :passage "14:8-9"} {}))))))
  (testing "a dangling label is still found by its passage"
    (is (= [:unknown-label] (map :problem (:edges (backlinks/backlinks-report g {:id rom :passage "sec:x"} {}))))))
  (testing "nothing references the book: an empty report, not an error"
    (let [report (backlinks/backlinks-report g {:id (id "bible" ["nt"] "atos")} {})]
      (is (= [] (:edges report) (:self report)))
      (is (= "bible:nt/atos\n0 references from 0 books\n" (backlinks/render-text report))))))

(deftest passage-order-test
  (is (= [nil "1" "1:2" "1:10" "2:3" "2:3-4" "14:8" "014:9" "99999999999999999999:1" "a" "sec:2" "sec:10" "sec:x"]
         (sort backlinks/passage-order
               ["sec:x" "14:8" "2:3-4" "sec:10" nil "1:10" "a" "99999999999999999999:1" "1" "sec:2" "1:2" "2:3"
                "014:9"]))))

;; --- rendering -----------------------------------------------------------------

(deftest render-text-test
  ;; one source column for both sections, as wide as the widest source
  (is (= (str "bible:nt/romanos\n"
              "  (whole book)\n"
              "    cat:heidelberg           link    cat/h.hdoc:3\n"
              "  #2:3\n"
              "    cat:heidelberg#q:1       quote   cat/h.hdoc:41\n"
              "  #9:9\n"
              "    x:y                      link    line 5   dangling: unknown-book\n"
              "  #14:8\n"
              "    cat:heidelberg#q:1       quote   cat/h.hdoc:40\n"
              "    ref:c/belgic             quote   /elsewhere/b.hdoc:2\n"
              "    ref:c/belgic             link    /elsewhere/b.hdoc:7\n"
              "  #sec:x\n"
              "    cat:heidelberg           link    cat/h.hdoc:9   dangling: unknown-label\n"
              "self-references\n"
              "  #1:1\n"
              "    bible:nt/romanos#cap:2   link    -\n"
              "7 references from 3 books; 1 self-reference\n")
         (backlinks/render-text (backlinks/backlinks-report g {:id rom} {:base-dir "/base"}))))
  (testing "with a passage, the heading names it"
    (is (str/starts-with? (backlinks/render-text (backlinks/backlinks-report g {:id rom :passage "14:8"} {}))
                          "bible:nt/romanos#14:8\n  #14:8\n"))))

(deftest render-text-escapes-library-strings-test
  (let [evil (id "lib" "a")
        evil-g (graph-of [(record evil [(edge evil "bible:romanos#sec:x" rom :problem :unknown-label
                                              :from-passage (str "q" (char 0x202e) "1") :line 3
                                              :file (str "/base/a\nfake.hdoc:1: " (char 27) "[31m\\.hdoc"))])])
        out (backlinks/render-text (backlinks/backlinks-report evil-g {:id rom} {:base-dir "/base"}))]
    (is (= 4 (count (str/split-lines out))) "the entry is one line: book, heading, entry, summary")
    (is (str/includes? out "lib:a#q\\u202e1   link    a\\nfake.hdoc:1: \\u001b[31m\\\\.hdoc:3   dangling: unknown-label\n"))
    (testing "JSON carries the exact text"
      (let [{:strs [edges]} (json/read-str (backlinks/render-json (backlinks/backlinks-report evil-g {:id rom} {:base-dir "/base"})))]
        (is (= (str "a\nfake.hdoc:1: " (char 27) "[31m\\.hdoc") (get-in edges [0 "position" "file"])))))))

(deftest render-json-test
  (let [report (backlinks/backlinks-report g {:id rom} {:base-dir "/base"})
        out (backlinks/render-json report)
        parsed (json/read-str out)]
    (is (str/ends-with? out "}\n"))
    (is (= 1 (count (str/split-lines out))))
    (is (= ["version" "book" "passage" "edges" "self" "summary"] (map key (json/read-str out :key-fn str)))
        "envelope keys in the documented order")
    (is (str/starts-with? out "{\"version\":1,\"book\":\"bible:nt/romanos\",\"passage\":null,\"edges\":[{\"from\":"))
    (is (= {"references" 7 "books" 3 "self" 1} (get parsed "summary")))
    (testing "each edge in the doc-11 edge shape, keys in order"
      (is (str/includes? out (str "{\"from\":{\"id\":\"cat:heidelberg\",\"passage\":\"q:1\"},"
                                  "\"to\":{\"id\":\"bible:nt/romanos\",\"written\":\"bible:romanos#14:8\",\"passage\":\"14:8\"},"
                                  "\"kind\":\"quote\",\"status\":\"resolved\",\"problem\":null,"
                                  "\"position\":{\"file\":\"cat/h.hdoc\",\"line\":40,\"column\":null}}")))
      (is (str/includes? out (str "{\"from\":{\"id\":\"x:y\",\"passage\":null},"
                                  "\"to\":{\"id\":null,\"written\":\"bible:romanos#9:9\",\"passage\":\"9:9\"},"
                                  "\"kind\":\"link\",\"status\":\"dangling\",\"problem\":\"unknown-book\","
                                  "\"position\":{\"file\":null,\"line\":5,\"column\":null}}")))
      (is (= [{"from" {"id" "bible:nt/romanos" "passage" "cap:2"}
               "to" {"id" "bible:nt/romanos" "written" "bible:romanos#1:1" "passage" "1:1"}
               "kind" "link" "status" "resolved" "problem" nil "position" nil}]
             (get parsed "self"))))
    (testing "the same entries as the text, in the same order"
      (is (= (map (comp :written :to) (:edges report)) (map #(get-in % ["to" "written"]) (get parsed "edges")))))))
