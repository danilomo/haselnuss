(ns haselnuss.library.graph.check-test
  "`check-report` and its renderers (TASK-242, doc-11 \"--check\"): a pure
  function of the graph -- every graph here is built by hand, with no
  library directory behind it -- whose data both renderers print."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.book-ref :as book-ref]
            [haselnuss.library.graph :as graph]
            [haselnuss.library.graph.check :as check]
            [haselnuss.library.graph.libraries :as libraries]
            [haselnuss.library.graph.registry :as registry]
            [haselnuss.library.graph.text :as text]
            [haselnuss.resolver :as resolver]))

;; --- helpers -----------------------------------------------------------------

(defn- id [lib slug] (book-ref/book-id lib slug))

(defn- record
  [book-id & {:keys [edges diagnostics]}]
  {:id book-id :hash "h" :files #{} :labels {} :edges (vec edges) :diagnostics (vec diagnostics)})

(defn- quote-edge
  "A resolved quote from `from` (in passage `from-passage`) to `to`,
  taking `passage` (parsed) of it, written on `line` of `file`."
  [from to & {:keys [from-passage passage line file column]}]
  (cond-> {:from (cond-> {:id from} from-passage (assoc :passage from-passage))
           :to (cond-> {:id to :written (book-ref/format-book-id to)}
                 passage (assoc :passage passage))
           :kind :quote
           :status :resolved}
    line (assoc :position (cond-> {:line line} file (assoc :file file) column (assoc :column column)))))

(defn- graph-of
  "A library-indexed `:memory-index` graph of `records` and `library-records`."
  ([records] (graph-of records []))
  ([records library-records]
   (as-> (libraries/library-indexed (registry/new-graph registry/default-registry :memory-index nil)) g
     (reduce graph/with-book g records)
     (reduce graph/with-library g library-records))))

;; --- AC #1: items, from the graph alone ----------------------------------------

(deftest item-shape-test
  (let [a (id "lib" "a")
        g (graph-of
           [(record a :diagnostics
                    [{:type :dangling-cross-ref
                      :source-file "/base/lib/a.hdoc" :source-line 7
                      :message "/base/lib/a.hdoc:7: dangling cross-reference: no target with id \"sec:x\""}
                     {:type :unknown-book :level :warning :ref "lib:nope"
                      :source-file "/base/lib/a.hdoc" :source-line 3
                      :message "/base/lib/a.hdoc:3: book reference \"lib:nope\": library \"lib\" has no book \"nope\""}
                     {:type :stale-shelf :level :info :ref "lib:old/a" :canonical "lib:a"
                      :source-file "/elsewhere/a.hdoc" :source-line 9
                      :message "/elsewhere/a.hdoc:9: book reference \"lib:old/a\": the book is now at lib:a"}
                     {:type :unnumbered-section-id :message "section id \"cap:1\" is not numbered"}])]
           [{:name "lib" :status :available :root "/base/lib"
             :diagnostics [{:type :duplicate-slug :library "lib" :message "two books"}
                           {:type :invalid-book-name :library "lib" :path "/base/lib/Bad.hdoc"
                            :message "skipped /base/lib/Bad.hdoc"}
                           {:type :invalid-url-template :level :error :library "lib" :message "bad url"}]}])
        report (check/check-report g {:base-dir "/base"})]
    (testing "every book and library diagnostic is one item; levels default to warning"
      (is (= {:problems 7 :errors 1 :warnings 5 :info 1 :books 1} (:summary report)))
      (is (= check/report-version (:version report))))
    (testing "sorted by file then line, a problem with no file last"
      (is (= [["/elsewhere/a.hdoc" 9 :stale-shelf]
              ["lib" nil :duplicate-slug] ["lib" nil :invalid-url-template]
              ["lib/Bad.hdoc" nil :invalid-book-name]
              ["lib/a.hdoc" 3 :unknown-book] ["lib/a.hdoc" 7 :dangling-cross-ref]
              [nil nil :unnumbered-section-id]]
             (mapv (juxt :file :line :type) (:diagnostics report)))
          "a library problem naming no file is placed at the library's root"))
    (testing "paths inside :base-dir are shown relative to it, others as recorded"
      (is (= ["/base/lib" "/base/lib" "/base/lib/Bad.hdoc" "/base/lib/a.hdoc" "/base/lib/a.hdoc"
              "/elsewhere/a.hdoc" nil]
             (mapv :file (:diagnostics (check/check-report g {})))))
      (is (some #{"/base/lib/a.hdoc"}
                (map :file (:diagnostics (check/check-report g {:base-dir "/base/lib/a.hdoc"}))))
          "a path equal to :base-dir is not made empty")
      (is (= "../x.hdoc" (:file (first (:diagnostics (check/check-report
                                                      (graph-of [(record (id "lib" "a") :diagnostics
                                                                         [{:type :t :source-file "../x.hdoc" :source-line 1 :message "m"}])])
                                                      {:base-dir "/base"})))))
          "a relative path is left as it is"))
    (testing "the message loses the location prefix and the reference prefix"
      (let [by-type (into {} (map (juxt :type identity)) (:diagnostics report))]
        (is (= "dangling cross-reference: no target with id \"sec:x\"" (:message (by-type :dangling-cross-ref))))
        (is (= "library \"lib\" has no book \"nope\"" (:message (by-type :unknown-book))))
        (is (= "lib:nope" (:ref (by-type :unknown-book))))
        (is (= "lib:a" (:canonical (by-type :stale-shelf))))
        (is (= "lib:a" (:book (by-type :unknown-book))))
        (is (nil? (:book (by-type :duplicate-slug))))
        (is (= "lib" (:library (by-type :duplicate-slug))))
        (is (every? nil? (map :column (:diagnostics report))) "no column is invented")))
    (testing "the same graph gives the same report"
      (is (= report (check/check-report g {:base-dir "/base"}))))))

(deftest exit-status-test
  (let [report (fn [levels]
                 (check/check-report
                  (graph-of [(record (id "lib" "a")
                                     :diagnostics (map (fn [l] {:type :x :level l :message "m"}) levels))])))]
    (is (= 0 (check/exit-status (report []))) "nothing found")
    (is (= 0 (check/exit-status (report [:info :info]))) "infos do not count")
    (is (= 1 (check/exit-status (report [:info :warning]))))
    (is (= 1 (check/exit-status (report [:error]))))))

;; --- rendering -----------------------------------------------------------------

(deftest render-text-test
  (let [a (id "lib" "a")
        g (graph-of [(record a :diagnostics
                             [{:type :unknown-label :level :warning :ref "lib:b#sec:x" :canonical "lib:b"
                               :source-file "/base/a.hdoc" :source-line 42
                               :message "/base/a.hdoc:42: book reference \"lib:b#sec:x\": book lib:b has no label \"sec:x\""}
                              {:type :stale-shelf :level :info :ref "lib:old/b"
                               :source-file "/base/a.hdoc" :source-line 7 :message "book is now on lib:b"}])
                     (record (id "lib" "b"))])]
    (is (= (str "a.hdoc:7: info: [stale-shelf] lib:old/b: book is now on lib:b\n"
                "a.hdoc:42: warning: [unknown-label] lib:b#sec:x: book lib:b has no label \"sec:x\"\n"
                "2 problems (0 errors, 1 warning, 1 info) in 2 books\n")
           (check/render-text (check/check-report g {:base-dir "/base"}))))
    (testing "a known column is printed file:line:col:, and the build's file:line: prefix still stripped (TASK-248)"
      (let [g (graph-of [(record a :diagnostics
                                 [{:type :unknown-book :ref "lib:x" :source-file "/base/a.hdoc" :source-line 3
                                   :source-column 12
                                   :message "/base/a.hdoc:3: book reference \"lib:x\": no book"}
                                  {:type :malformed-book-ref :ref "x" :source-file "/base/a.hdoc" :source-line 4
                                   :source-column 1 :message "/base/a.hdoc:4:1: malformed"}])])
            report (check/check-report g {:base-dir "/base"})]
        (is (= (str "a.hdoc:3:12: warning: [unknown-book] lib:x: no book\n"
                    "a.hdoc:4:1: warning: [malformed-book-ref] x: malformed\n"
                    "2 problems (0 errors, 2 warnings, 0 info) in 1 book\n")
               (check/render-text report)))
        (is (= [12 1] (mapv :column (:diagnostics report))))
        (is (= {"line" 3 "column" 12}
               (select-keys (first (get (json/read-str (check/render-json report)) "diagnostics"))
                            ["line" "column"])))))
    (testing "singular and empty summaries"
      (is (= "0 problems (0 errors, 0 warnings, 0 info) in 0 books\n"
             (check/render-text (check/check-report (graph-of []))))))
    (testing "a problem with no file is placed at its book, or its library"
      (let [g (graph-of [(record a :diagnostics [{:type :unnumbered-section-id :message "m"}])]
                        [{:name "other" :status :unavailable :diagnostics [{:type :unavailable-library :message "gone"}]}])]
        (is (= (str "lib:a: warning: [unnumbered-section-id] m\n"
                    "other: warning: [unavailable-library] gone\n"
                    "2 problems (0 errors, 2 warnings, 0 info) in 1 book\n")
               (check/render-text (check/check-report g))))))))

(deftest render-json-test
  (let [g (graph-of [(record (id "lib" "a")
                             :diagnostics [{:type :unknown-book :ref "lib:x" :source-file "/base/é/a.hdoc"
                                            :source-line 3 :message "no book"}
                                           {:type :unnumbered-section-id :message "m"}])])
        text (check/render-json (check/check-report g {:base-dir "/base"}))
        parsed (json/read-str text)]
    (testing "one line: a versioned envelope, keys in the documented order"
      (is (str/ends-with? text "\n"))
      (is (= 1 (count (str/split-lines text))))
      (is (str/starts-with? text "{\"version\":1,\"diagnostics\":[{\"type\":\"unknown-book\",\"level\":\"warning\",\"message\":\"no book\",\"file\":\"é/a.hdoc\",\"line\":3,\"column\":null,\"ref\":\"lib:x\",\"canonical\":null,\"book\":\"lib:a\",\"library\":\"lib\"}")
          "slashes and non-ASCII are written as they are"))
    (is (= {"problems" 2 "errors" 0 "warnings" 2 "info" 0 "books" 1} (get parsed "summary")))
    (is (= {"type" "unnumbered-section-id" "level" "warning" "message" "m" "file" nil "line" nil
            "column" nil "ref" nil "canonical" nil "book" "lib:a" "library" "lib"}
           (second (get parsed "diagnostics"))))))

;; --- quote cycles and nesting ----------------------------------------------------

(defn- quote-types [g] (mapv (fn [[book d]] [(book-ref/format-book-id book) (:type d) (:source-line d)])
                             (check/quote-problems g)))

(deftest quote-cycle-test
  (let [[a b c] (map #(id "lib" %) ["a" "b" "c"])]
    (testing "two books quoting each other: each quote is a cycle some build reports"
      (let [g (graph-of [(record a :edges [(quote-edge a b :line 3 :file "/a.hdoc" :column 4)])
                         (record b :edges [(quote-edge b a :line 5 :file "/b.hdoc")])])
            problems (check/quote-problems g)]
        (is (= [["lib:a" :quote-cycle 3] ["lib:b" :quote-cycle 5]] (quote-types g)))
        (is (= "quote cycle: !quote lib:b names a book that is already being quoted, via lib:b -> lib:a -> lib:b"
               (:message (second (first problems)))))
        (is (= "/a.hdoc" (:source-file (second (first problems)))))
        (is (= [4 nil] (mapv (comp :source-column second) problems))
            "placed at the edge's column when it has one (TASK-248)")
        (is (= ["/a.hdoc:3:4" "/b.hdoc:5"]
               (mapv #(str (:file %) ":" (:line %) (when (:column %) (str ":" (:column %))))
                     (:diagnostics (check/check-report g)))))))
    (testing "a three-book cycle"
      (let [g (graph-of [(record a :edges [(quote-edge a b :line 1)])
                         (record b :edges [(quote-edge b c :line 2)])
                         (record c :edges [(quote-edge c a :line 3)])])]
        (is (= [["lib:a" :quote-cycle 1] ["lib:b" :quote-cycle 2] ["lib:c" :quote-cycle 3]] (quote-types g)))))
    (testing "a quote of a label holds only the quotes whose nearest label it is"
      (let [label {:form :label :label "sec:x"}
            g-in (graph-of [(record a :edges [(quote-edge a b :passage label :line 1)])
                            (record b :edges [(quote-edge b a :from-passage "sec:x" :line 2)])])
            g-out (graph-of [(record a :edges [(quote-edge a b :passage label :line 1)])
                             (record b :edges [(quote-edge b a :from-passage "sec:y" :line 2)])])]
        (is (= [["lib:a" :quote-cycle 1] ["lib:b" :quote-cycle 2]] (quote-types g-in)))
        (is (= [["lib:a" :quote-cycle 1]] (quote-types g-out))
            "a build of b meets a's quote of b inside the whole of a it quotes; a build
             of a never meets b's quote, which sits outside the passage a quotes")))
    (testing "a range holds the quotes at either end; a locator none"
      (let [g (graph-of [(record a :edges [(quote-edge a b :passage {:form :range :from "sec:1" :to "sec:3"} :line 1)])
                         (record b :edges [(quote-edge b a :from-passage "sec:3" :line 2)])])
            g-loc (graph-of [(record a :edges [(quote-edge a b :passage {:form :locator :locator "1"} :line 1)])
                             (record b :edges [(quote-edge b a :line 2)])])]
        (is (= [["lib:a" :quote-cycle 1] ["lib:b" :quote-cycle 2]] (quote-types g)))
        (is (= [["lib:a" :quote-cycle 1]] (quote-types g-loc))
            "b's whole-book quote of a holds a's quote of b (a cycle in a build of b);
             a's locator quote of b holds nothing known, so b's quote is never followed")))
    (testing "dangling quotes and links are not followed"
      (let [g (graph-of [(record a :edges [(assoc (quote-edge a b :line 1) :kind :link)])
                         (record b :edges [(quote-edge b a :line 2)])])]
        (is (= [] (quote-types g)))))))

(defn- dangling
  "`edge` as a quote whose passage did not resolve with `problem`,
  written as `written` (its `:to :id` kept only when `keep-id?`)."
  [edge problem written & {:keys [keep-id?]}]
  (cond-> (-> edge
              (assoc :status :dangling :problem problem)
              (assoc-in [:to :written] written))
    (not keep-id?) (update :to dissoc :id)))

(deftest dangling-quote-cycle-test
  (let [[a b c] (map #(id "lib" %) ["a" "b" "c"])]
    (testing "a quote whose passage did not resolve still closes a cycle: a build checks
              the book before the passage (TASK-249)"
      (let [g (graph-of [(record a :edges [(dangling (quote-edge a b :line 7 :file "/a.hdoc" :column 1)
                                                     :bad-label-range "lib:b#sec:p..sec:inner" :keep-id? true)])
                         (record b :edges [(quote-edge b a :line 9 :file "/b.hdoc")])])
            problems (check/quote-problems g)]
        (is (= [["lib:a" :quote-cycle 7]] (quote-types g))
            "only a build of b meets it; a build of a never expands the quote, so b's quote
             is never reached from a")
        (is (= "quote cycle: !quote lib:b#sec:p..sec:inner names a book that is already being quoted, via lib:b -> lib:a -> lib:b"
               (:message (second (first problems)))))
        (is (= ["lib:b#sec:p..sec:inner" "lib:b" 1]
               ((juxt :ref :canonical :source-column) (second (first problems)))))))
    (testing "the written book is matched by library and slug, whatever shelf it writes,
              with or without a known :to :id"
      (doseq [written ["lib:b#sec:nope" "lib:old/b#sec:nope" "lib:b"]]
        (let [b-shelved (book-ref/book-id "lib" ["new"] "b")
              g (graph-of [(record a :edges [(dangling (quote-edge a b-shelved :line 1) :unknown-label written)])
                           (record b-shelved :edges [(quote-edge b-shelved a :line 2)])])]
          (is (= [["lib:a" :quote-cycle 1]] (quote-types g)) written)
          (is (= "lib:new/b" (:canonical (second (first (check/quote-problems g)))))))))
    (testing "a slug two books share is never on a build's stack (quoting it is ambiguous),
              so a dangling quote of it closes no cycle"
      (let [[bx by] [(book-ref/book-id "lib" ["x"] "b") (book-ref/book-id "lib" ["y"] "b")]
            g (graph-of [(record a :edges [(dangling (quote-edge a bx :line 7) :ambiguous-book "lib:b#sec:p")])
                         (record bx :edges [(quote-edge bx a :line 5)])
                         (record by)])]
        (is (= [] (quote-types g)))))
    (testing "a dangling quote is never followed: it can only end a cycle"
      (let [g (graph-of [(record a :edges [(dangling (quote-edge a b :line 1) :unknown-label "lib:b#sec:x")])
                         (record b :edges [(quote-edge b c :line 2)])
                         (record c :edges [(quote-edge c a :line 3)])])]
        (is (= [["lib:a" :quote-cycle 1]] (quote-types g))
            "b -> c -> a meets a's quote of b; nothing passes through a's quote")))
    (testing "a dangling quote of a book no build has on its stack is no cycle"
      (let [g (graph-of [(record a :edges [(dangling (quote-edge a c :line 1) :unknown-book "lib:c")
                                           (quote-edge a b :line 2)])
                         (record b :edges [(quote-edge b a :line 3)])])]
        (is (= [["lib:a" :quote-cycle 2] ["lib:b" :quote-cycle 3]] (quote-types g)))))
    (testing "not a malformed quote, and not the scan's own self-quote cycle (no duplicate)"
      (let [g (graph-of [(record a :edges [(dangling (quote-edge a b :line 1) :malformed-book-ref "lib")
                                           (dangling (quote-edge a a :line 2) :quote-cycle "lib:a" :keep-id? true)
                                           (quote-edge a b :line 3)])
                         (record b :edges [(quote-edge b a :line 4)])])]
        (is (= [["lib:a" :quote-cycle 3] ["lib:b" :quote-cycle 4]] (quote-types g)))))
    (testing "links that dangle are still not quotes"
      (let [g (graph-of [(record a :edges [(assoc (dangling (quote-edge a b :line 1) :unknown-label "lib:b#x") :kind :link)])
                         (record b :edges [(quote-edge b a :line 2)])])]
        (is (= [] (quote-types g)))))))

(deftest dangling-quote-too-deep-test
  (testing "a build checks the depth before the passage too, so a dangling quote at the
            end of a chain one level too long is too deep"
    (let [n (inc resolver/max-quote-depth)
          ids (mapv #(id "lib" (format "d%02d" %)) (range 1 (inc n)))
          g (graph-of (for [i (range n)]
                        (record (ids i) :edges [(if (< (inc i) n)
                                                  (quote-edge (ids i) (ids (inc i)) :line (inc i))
                                                  (dangling (quote-edge (ids i) (id "lib" "nowhere") :line (inc i))
                                                            :unknown-book "lib:nowhere"))])))]
      (is (= [[(book-ref/format-book-id (peek ids)) :quote-too-deep n]] (quote-types g)))
      (is (nil? (:canonical (second (first (check/quote-problems g)))))
          "no book in the graph is named, so no canonical id"))))

(deftest quote-cycle-spans-test
  (testing "with :label-spans and :from :order (what scan records), a quoted label holds
            every reference within its block's span, however deeply nested"
    (let [[a b] (map #(id "lib" %) ["a" "b"])
          outer {:form :label :label "sec:outer"}
          rec (fn [self other order]
                (assoc (record self :edges [(assoc-in (quote-edge self other :passage outer :from-passage "sec:inner"
                                                                  :line order)
                                                      [:from :order] order)])
                       :label-spans {"sec:outer" [0 10] "sec:inner" [4 10]}))]
      (is (= [["lib:a" :quote-cycle 6] ["lib:b" :quote-cycle 6]]
             (quote-types (graph-of [(rec a b 6) (rec b a 6)]))))
      (is (= [["lib:a" :quote-cycle 6]] (quote-types (graph-of [(rec a b 6) (rec b a 11)])))
          "b's quote, past the span, is outside the passage a quotes: only a build of b
           (whose content holds a's quote) meets a cycle")))
  (testing "a range holds everything from the start of its first label to the end of its last"
    (let [[a b] (map #(id "lib" %) ["a" "b"])
          g (fn [order]
              (graph-of [(record a :edges [(quote-edge a b :passage {:form :range :from "sec:p" :to "sec:r"} :line 1)])
                         (assoc (record b :edges [(assoc-in (quote-edge b a :from-passage "sec:q" :line 2)
                                                            [:from :order] order)])
                                :label-spans {"sec:p" [0 3] "sec:q" [4 7] "sec:r" [8 9] "sec:z" [10 12]})]))]
      (is (= [["lib:a" :quote-cycle 1] ["lib:b" :quote-cycle 2]] (quote-types (g 5))))
      (is (= [["lib:a" :quote-cycle 1]] (quote-types (g 11)))
          "past the range's end: only a build of b meets a cycle")))
  (testing "spans are validated: one may not end before it starts"
    (is (graph/valid-book-record? (assoc (record (id "lib" "a")) :label-spans {"sec:a" [2 5]})))
    (is (not (graph/valid-book-record? (assoc (record (id "lib" "a")) :label-spans {"sec:a" [5 2]}))))
    (is (not (graph/valid-book-record? (assoc-in (record (id "lib" "a") :edges [(quote-edge (id "lib" "a") (id "lib" "b"))])
                                                 [:edges 0 :from :order] -1))))))

(deftest escape-text-test
  (is (= "plain é text ✓ 𝄞" (text/escape-text "plain é text ✓ 𝄞")) "nothing to escape")
  (testing "a literal backslash is doubled, so a written \\n differs from an escaped newline"
    (is (= "a\\\\nb \\\\ c" (text/escape-text "a\\nb \\ c")))
    (is (not= (text/escape-text "a\\nb") (text/escape-text "a\nb"))))
  (testing "format characters: bidi overrides and isolates, zero-width, BOM, tags"
    (is (= "A\\u202eevil\\u202c.hdoc" (text/escape-text (str "A" (char 0x202e) "evil" (char 0x202c) ".hdoc"))))
    (is (= "\\u2066x\\u2069" (text/escape-text (str (char 0x2066) "x" (char 0x2069)))))
    (is (= "a\\u200bb\\u200dc\\ufeff" (text/escape-text (str "a" (char 0x200b) "b" (char 0x200d) "c" (char 0xfeff)))))
    (is (= "t\\U+0e0041" (text/escape-text (str "t" (String. (Character/toChars 0xE0041)))))
        "a format character above the BMP is one escape, not two surrogates"))
  (is (= "a\\nb\\rc\\td\\u001b[31m\\u0000\\u007f\\u0085\\u2028\\u2029"
         (text/escape-text (str "a\nb\rc\td" (char 27) "[31m" (char 0) (char 127) (char 0x85)
                                (char 0x2028) (char 0x2029)))))
  (testing "render-line escapes the file, the ref and the message"
    (is (= "x\\ny.hdoc:3: warning: [t] r\\u001b: m\\nfake.hdoc:1: error: x"
           (check/render-line {:file "x\ny.hdoc" :line 3 :level :warning :type :t
                               :ref (str "r" (char 27)) :message "m\nfake.hdoc:1: error: x"})))))

(deftest quote-too-deep-test
  (let [n (inc (inc resolver/max-quote-depth))
        ids (mapv #(id "lib" (format "d%02d" %)) (range 1 (inc n)))
        chain (fn [k] (graph-of (for [i (range k)]
                                  (record (ids i) :edges (when (< (inc i) k) [(quote-edge (ids i) (ids (inc i)) :line (inc i))])))))]
    (testing "a chain of max-quote-depth + 2 books: the last quote is one level too deep"
      (let [problems (check/quote-problems (chain n))]
        (is (= [[(book-ref/format-book-id (ids (- n 2))) :quote-too-deep (dec n)]]
               (quote-types (chain n))))
        (is (= (str "!quote lib:d" (format "%02d" n) " is nested " (inc resolver/max-quote-depth)
                    " quotes deep, more than the " resolver/max-quote-depth " allowed, via "
                    (str/join " -> " (map book-ref/format-book-id (take (dec n) ids))))
               (:message (second (first problems)))))))
    (testing "one book fewer nests exactly as deep as allowed"
      (is (= [] (quote-types (chain (dec n))))))))

(deftest check-report-includes-quote-problems-test
  (let [[a b] (map #(id "lib" %) ["a" "b"])
        g (graph-of [(record a :edges [(quote-edge a b :line 3 :file "/x/a.hdoc")])
                     (record b :edges [(quote-edge b a :line 5 :file "/x/b.hdoc")])])]
    (is (= [["a.hdoc" 3 :quote-cycle "lib:b" "lib:b" "lib:a"] ["b.hdoc" 5 :quote-cycle "lib:a" "lib:a" "lib:b"]]
           (mapv (juxt :file :line :type :ref :canonical :book)
                 (:diagnostics (check/check-report g {:base-dir "/x"})))))))
