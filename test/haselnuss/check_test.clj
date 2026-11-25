(ns haselnuss.check-test
  "`--check` through the real CLI (TASK-242, doc-11 \"--check\"): the
  problems the fixture libraries plant, the text and JSON output, the
  exit codes, `--extensions`/`--transform`, and build parity -- a check
  reports what building every book prints."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.book-ref :as book-ref]
            [haselnuss.cli :as cli]
            [haselnuss.library :as library]
            [haselnuss.library.filesystem :as fs]
            [haselnuss.library.graph :as graph]
            [haselnuss.library.graph.check :as graph-check]
            [haselnuss.library.graph.scan :as scan]
            [haselnuss.library.references :as references]
            [haselnuss.sanitize :as sanitize])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

;; --- helpers -----------------------------------------------------------------

(def ^:private fixtures-yaml
  "The fixture libraries' own directory file (`test/fixtures/libraries/`)."
  "test/fixtures/libraries/libraries.yaml")

(def ^:private cwd (System/getProperty "user.dir"))

(def ^:private overrides
  "No real `$HASELNUSS_LIBRARIES` or home config, and paths shown relative
  to the working directory, as the CLI shows them."
  {:library-env {} :library-home "/nonexistent/haselnuss-test-home"
   :haselnuss.cli/check-base-dir cwd})

(defn- main-run
  [& args]
  (let [err (java.io.StringWriter.)
        out (java.io.StringWriter.)
        status (binding [*err* err *out* out] (cli/main* args overrides))]
    {:status status :out (str out) :err (str err)}))

(defn- temp-dir ^File []
  (.toFile (Files/createTempDirectory "haselnuss-check-test" (make-array FileAttribute 0))))

(defn- write!
  ^File [^File dir rel text]
  (let [f (io/file dir rel)]
    (io/make-parents f)
    (spit f text)
    f))

(defn- library-dir!
  "A temp folder holding the library `lib` (`books`: relative path ->
  source) and a `libraries.yaml` naming it. Returns the yaml's path."
  ([books] (library-dir! books ""))
  ([books extra-entry-lines]
   (let [dir (temp-dir)]
     (doseq [[path source] books] (write! dir (str "lib/" path) source))
     (.mkdirs (io/file dir "lib"))
     (str (write! dir "libraries.yaml"
                  (str "- name: lib\n  type: filesystem\n  path: lib\n" extra-entry-lines))))))

(defn- line-heads
  "Each report line of `out` up to its `[type]`, and the summary line."
  [out]
  (let [lines (str/split-lines out)]
    [(mapv #(second (re-find #"^(.*?\] )" %)) (butlast lines)) (last lines)]))

(defn- canonical [path] (when path (.getCanonicalPath (io/file path))))

;; --- AC #2: the planted problems ------------------------------------------------

(def ^:private planted
  "What `--check` reports over the fixture libraries, each line up to its
  type, sorted by file then line; the generated books' own resolver
  diagnostics (the fixture shelf numbers its chapters `cap:N`, an id
  prefix no kind numbers) last, having no file."
  ["test/fixtures/libraries/check/gen/broken/shelf.clj:5: warning: [script-failed] "
   "test/fixtures/libraries/check/gen/odd/shelf.clj: warning: [invalid-generated-book] "
   "test/fixtures/libraries/check/links.hdoc:7:40: warning: [unknown-library] "
   "test/fixtures/libraries/check/links.hdoc:9:25: warning: [ambiguous-book] "
   "test/fixtures/libraries/check/links.hdoc:11:33: warning: [unknown-label] "
   "test/fixtures/libraries/check/links.hdoc:13:43: warning: [bad-label-range] "
   "test/fixtures/libraries/check/links.hdoc:15:43: warning: [locator-unsupported] "
   "test/fixtures/libraries/check/links.hdoc:17:29: warning: [bad-locator] "
   "test/fixtures/libraries/check/links.hdoc:19:39: info: [stale-shelf] "
   "test/fixtures/libraries/check/links.hdoc:21:49: warning: [unavailable-library] "
   "test/fixtures/libraries/check/links.hdoc:23: warning: [dangling-cross-ref] "
   "test/fixtures/libraries/check/quotes/cycle-a.hdoc:7:1: warning: [quote-cycle] "
   "test/fixtures/libraries/check/quotes/cycle-b.hdoc:7:1: warning: [quote-cycle] "
   "test/fixtures/libraries/check/quotes/deep-09.hdoc:7:1: warning: [quote-too-deep] "
   "test/fixtures/libraries/generated/scripture/shelf.clj: warning: [scripts-disabled] "
   "test/fixtures/libraries/libraries.yaml:24: warning: [unavailable-library] "
   "test/fixtures/libraries/passages/catechisms/heidelberg/book.hdoc:9: warning: [unreadable-include] "
   "test/fixtures/libraries/passages/creeds/nicene.hdoc: warning: [unreadable-bibliography] "
   "test/fixtures/libraries/passages/creeds/quoting.hdoc:9:1: warning: [unknown-book] "
   "test/fixtures/libraries/reformed/Bad Name.hdoc: warning: [invalid-book-name] "
   "test/fixtures/libraries/reformed/UPPER: warning: [invalid-book-name] "
   "test/fixtures/libraries/reformed/creeds/Nicene.hdoc: warning: [invalid-book-name] "
   "test/fixtures/libraries/shared/a/same.hdoc: warning: [duplicate-slug] "
   "bible:scripture/hebreus: warning: [unnumbered-section-id] "
   "bible:scripture/joao: warning: [unnumbered-section-id] "
   "bible:scripture/joao: warning: [unnumbered-section-id] "
   "bible:scripture/romanos: warning: [unnumbered-section-id] "])

(deftest fixture-libraries-test
  (let [{:keys [status out err]} (main-run "--libraries" fixtures-yaml "--check")
        [heads summary] (line-heads out)]
    (testing "every planted problem, at its file:line, sorted by file then line, then the summary"
      (is (= planted heads) out)
      (is (= "27 problems (0 errors, 26 warnings, 1 info) in 28 books" summary)))
    (testing "warnings found: exit 1; stderr has nothing to say"
      (is (= 1 status))
      (is (= "" err)))
    (testing "the message follows the reference, without repeating it or its place"
      (is (str/includes? out (str "test/fixtures/libraries/passages/creeds/quoting.hdoc:9:1: warning: "
                                  "[unknown-book] pass:nowhere: library \"pass\" has no book \"nowhere\"\n")))
      (is (str/includes? out (str "test/fixtures/libraries/check/quotes/cycle-a.hdoc:7:1: warning: [quote-cycle] "
                                  "check:cycle-b: quote cycle: !quote check:cycle-b names a book that is already "
                                  "being quoted, via check:quotes/cycle-b -> check:quotes/cycle-a -> "
                                  "check:quotes/cycle-b\n"))))))

(deftest json-format-test
  (let [text (main-run "--libraries" fixtures-yaml "--check")
        {:keys [status out]} (main-run "--libraries" fixtures-yaml "--check" "--format" "json")
        {:strs [version diagnostics summary]} (json/read-str out)]
    (is (= 1 status) "the format does not change the exit status")
    (is (= 1 version))
    (is (= {"problems" 27 "errors" 0 "warnings" 26 "info" 1 "books" 28} summary))
    (testing "the same items as the text, in the same order"
      (is (= (first (line-heads (:out text)))
             (mapv (fn [{:strs [file line column level book] :as d}]
                     (str (or file book) (when line (str ":" line)) (when column (str ":" column))
                          ": " level ": [" (get d "type") "] "))
                   diagnostics))))
    (testing "every item carries the documented fields"
      (is (every? #(= #{"type" "level" "message" "file" "line" "column" "ref" "canonical" "book" "library"}
                      (set (keys %)))
                  diagnostics))
      (is (= 27 (count (re-seq (re-pattern (str "\\{\"type\":\"[a-z-]+\",\"level\":\"(error|warning|info)\","
                                                "\"message\":\".*?\",\"file\":(null|\".*?\"),"
                                                "\"line\":(null|\\d+),\"column\":(null|\\d+),"
                                                "\"ref\":(null|\".*?\"),\"canonical\":(null|\".*?\"),"
                                                "\"book\":(null|\".*?\"),\"library\":\"[a-z-]+\"\\}"))
                               out)))
          "each item's keys in the documented order")
      (testing "a column on every book reference's problem, none on anything else (TASK-248)"
        (is (= 12 (count (filter #(get % "column") diagnostics))))
        (is (every? #(= (some? (get % "ref")) (some? (get % "column"))) diagnostics)))
      (is (some #(= {"type" "unknown-label" "ref" "pass:nicene#sec:nope" "canonical" "pass:creeds/nicene"
                     "book" "check:links" "library" "check" "line" 11 "column" 33}
                    (select-keys % ["type" "ref" "canonical" "book" "library" "line" "column"]))
                diagnostics)))
    (testing "the envelope's keys come in the documented order"
      (is (str/starts-with? out "{\"version\":1,\"diagnostics\":[{\"type\":"))
      (is (re-find #"\],\"summary\":\{\"problems\":27,\"errors\":0,\"warnings\":26,\"info\":1,\"books\":28\}\}\n$" out)))))

(deftest included-chapter-column-test
  (testing "a reference in an !include-d chapter is placed in the chapter's file, at its column there (TASK-248)"
    (let [yaml (library-dir! {"a.hdoc" "---\ntitle: A\n---\n\n!include chapters/one.hdoc\n"
                              "chapters/one.hdoc" "# One\n\nIndented é 𝄞 @{lib:nope} here.\n"})
          {:keys [status out]} (main-run "--libraries" yaml "--check" "--format" "json")
          [d] (get (json/read-str out) "diagnostics")
          text (:out (main-run "--libraries" yaml "--check"))]
      (is (= 1 status))
      (is (= {"type" "unknown-book" "line" 3 "column" 14} (select-keys d ["type" "line" "column"])))
      (is (str/ends-with? (get d "file") "lib/chapters/one.hdoc"))
      (is (re-find #"(?m)lib/chapters/one\.hdoc:3:14: warning: \[unknown-book\] lib:nope: " text) text))))

(deftest citation-fragment-position-test
  (testing "TASK-252: a reference in a citation group's prefix or suffix -- in the book's
            own file and in an !include-d chapter, on one line or across a line break, in
            a quote and a list item -- is reported at file:line:col, not just at lib:a"
    (let [yaml (library-dir! {"a.hdoc" (str "---\ntitle: A\n---\n\n"          ; 1-4
                                            "Text [see @{lib:nope} @key].\n"   ; 5
                                            "\n!include chapters/one.hdoc\n")  ; 6-7
                              "chapters/one.hdoc" (str "# One\n\n"                          ; 1-2
                                                       "A é 𝄞 [cf.\n"                         ; 3
                                                       "  also @{lib:wrapped} @key, p. 3\n"  ; 4
                                                       "and @{lib:suffix}; @k2] end.\n"      ; 5
                                                       "\n"                                  ; 6
                                                       "> Q [see\n"                          ; 7
                                                       "> it @{lib:quoted} @key]\n"          ; 8
                                                       "\n"                                  ; 9
                                                       "- L [see\n"                          ; 10
                                                       "  it @{lib:listed} @key]\n")})       ; 11
          {:keys [status out]} (main-run "--libraries" yaml "--check")
          json-out (:out (main-run "--libraries" yaml "--check" "--format" "json"))
          ;; only a book reference's problem has a :ref (the unknown
          ;; citation keys' have none)
          placed (into {} (keep (fn [{:strs [file line column] written "ref"}]
                                  (when written [written [(last (str/split file #"/")) line column]])))
                       (get (json/read-str json-out) "diagnostics"))]
      (is (= 1 status))
      (is (= {"lib:nope" ["a.hdoc" 5 11]
              "lib:wrapped" ["one.hdoc" 4 8]
              "lib:suffix" ["one.hdoc" 5 5]
              "lib:quoted" ["one.hdoc" 8 6]
              "lib:listed" ["one.hdoc" 11 6]}
             placed))
      (is (re-find #"(?m)lib/a\.hdoc:5:11: warning: \[unknown-book\] lib:nope: " out) out)
      (is (re-find #"(?m)lib/chapters/one\.hdoc:4:8: warning: \[unknown-book\] lib:wrapped: " out) out)
      (is (not (re-find #"(?m)^lib:a: " out)) out))))

(deftest duplicate-canonical-id-test
  (testing "TASK-250: x.hdoc beside x/book.hdoc (one canonical id): --check reports the
            problems inside both files, each at its own file:line:col, while the id
            itself stays ambiguous for --backlinks and for a reference"
    (let [yaml (library-dir! {"x.hdoc" "---\ntitle: X1\n---\n\nSee @{lib:nosuch1}.\n"
                              "x/book.hdoc" "---\ntitle: X2\n---\n\nAnd   @{lib:nosuch2}.\n"
                              "y.hdoc" "---\ntitle: Y\n---\n\nLinks @{lib:x}.\n"})
          {:keys [status out]} (main-run "--libraries" yaml "--check")
          backlinks (main-run "--libraries" yaml "--backlinks" "lib:x")]
      (is (= 1 status))
      (is (re-find #"(?m)lib/x\.hdoc:5:5: warning: \[unknown-book\] lib:nosuch1: " out) out)
      (is (re-find #"(?m)lib/x/book\.hdoc:5:7: warning: \[unknown-book\] lib:nosuch2: " out) out)
      (is (re-find #"(?m)lib/y\.hdoc:5:7: warning: \[ambiguous-book\] lib:x: " out) out)
      (is (re-find #"(?m)\[duplicate-slug\]" out) out)
      ;; TASK-285: both books of the id and y are checked: three, not two
      (is (str/ends-with? out "in 3 books\n") out)
      (is (= 2 (:status backlinks)))
      (is (str/includes? (:err backlinks) "names more than one book") (:err backlinks))))
  (testing "a generated shelf listing one slug twice reads one document: one book, each
            problem reported once (not merged with itself)"
    (let [yaml (library-dir! {"h.hdoc" "---\ntitle: H\n---\n\nH.\n"
                              "g/shelf.clj"
                              (str "{:books (fn [_] [{:slug \"dup\" :title \"A\"} {:slug \"dup\" :title \"B\"}])\n"
                                   " :document (fn [_ _] {:meta {:title [{:t :str :text \"T\"}]}\n"
                                   "                      :blocks [{:t :para :inlines [{:t :book-ref :ref \"lib:h\"}\n"
                                   "                                                   {:t :space}\n"
                                   "                                                   {:t :book-ref :ref \"lib:gone\"}]}]})}\n")}
                             "  scripts: true\n")
          {:keys [out]} (main-run "--libraries" yaml "--check")
          backlinks (main-run "--libraries" yaml "--backlinks" "lib:h")]
      (is (= 1 (count (re-seq #"\[unknown-book\] lib:gone" out))) out)
      (is (str/includes? (:out backlinks) "1 reference from 1 book") (:out backlinks)))))

(deftest duplicate-canonical-id-quote-cycle-test
  (testing "TASK-250 review: x.hdoc beside x/book.hdoc are one record, but quoting p:x is
            ambiguous, so no build has it on its quote stack and --check reports no cycle"
    (let [yaml (library-dir! {"x.hdoc" "---\ntitle: X1\n---\n\n!quote lib:y\n"
                              "x/book.hdoc" "---\ntitle: X2\n---\n\nText.\n"
                              "y.hdoc" "---\ntitle: Y\n---\n\n!quote lib:x\n"})
          {:keys [out]} (main-run "--libraries" yaml "--check")]
      (is (re-find #"(?m)y\.hdoc:5:1: warning: \[ambiguous-book\] lib:x: " out) out)
      (is (not (str/includes? out "[quote-cycle]")) out))))

;; --- scope -----------------------------------------------------------------------

(deftest scope-test
  (testing "named libraries only: their books and library records, while references
            into other libraries are still followed"
    (let [{:keys [status out]} (main-run "--libraries" fixtures-yaml "--check" "pass" "shared")
          [heads summary] (line-heads out)]
      (is (= 1 status))
      (is (= ["test/fixtures/libraries/passages/catechisms/heidelberg/book.hdoc:9: warning: [unreadable-include] "
              "test/fixtures/libraries/passages/creeds/nicene.hdoc: warning: [unreadable-bibliography] "
              "test/fixtures/libraries/passages/creeds/quoting.hdoc:9:1: warning: [unknown-book] "
              "test/fixtures/libraries/shared/a/same.hdoc: warning: [duplicate-slug] "]
             heads))
      (is (= "4 problems (0 errors, 4 warnings, 0 info) in 6 books" summary))))
  (testing "a problem of an out-of-scope target shows only on the citing edge"
    (let [{:keys [out]} (main-run "--libraries" fixtures-yaml "--check" "check")]
      (is (str/includes? out "links.hdoc:21:49: warning: [unavailable-library] broken:anything: "))
      (is (not (re-find #"(?m)^test/fixtures/libraries/libraries\.yaml:24: " out))
          "the failed library's own load problem")
      (is (not (str/includes? out "[unreadable-include]")) "a target book's own load warning"))))

;; --- AC #2/#4: exit codes --------------------------------------------------------

(deftest exit-codes-test
  (testing "a clean library: exit 0"
    (let [yaml (library-dir! {"a.hdoc" "---\ntitle: A\n---\n\n# A {#sec:a}\n\nSee @sec:a and @{lib:b}.\n"
                              "b.hdoc" "---\ntitle: B\n---\n\nB.\n"})
          {:keys [status out err]} (main-run "--libraries" yaml "--check")]
      (is (= 0 status) out)
      (is (= "0 problems (0 errors, 0 warnings, 0 info) in 2 books\n" out))
      (is (= "" err))))
  (testing "info-only results: exit 0"
    (let [yaml (library-dir! {"a.hdoc" "---\ntitle: A\n---\n\nSee @{lib:old/b}.\n"
                              "new/b.hdoc" "---\ntitle: B\n---\n\nB.\n"})
          {:keys [status out]} (main-run "--libraries" yaml "--check")]
      (is (= 0 status) out)
      (is (str/ends-with? out "1 problem (0 errors, 0 warnings, 1 info) in 2 books\n"))
      (is (re-find #"a\.hdoc:5:5: info: \[stale-shelf\] lib:old/b: the book is now at lib:new/b" out))))
  (testing "exit 2, nothing on stdout, when the check cannot run"
    (let [yaml (library-dir! {"a.hdoc" "A.\n"})
          bad (str (write! (temp-dir) "bad.yaml" "- name: lib\n  type: filesystem\n- not a map\n"))]
      (doseq [[args message]
              [[["--libraries" yaml "--check" "lib" "zzz"] "haselnuss: error: unknown library \"zzz\"; known: lib\n"]
               [["--libraries" (str yaml ".missing") "--check"] "libraries.yaml.missing:1: cannot read the library directory: no such file"]
               [["--libraries" bad "--check"] "bad.yaml:3: an entry must be a map"]
               [["--check"] "haselnuss: error: --check: no library directory was found"]
               [["--libraries" yaml "--check" "doc.hdoc"]
                "haselnuss: error: --check takes no input file, only library names and book IDs; got \"doc.hdoc\""]
               [["--libraries" yaml "--check" "--" "lib"] nil]
               [["--libraries" yaml "--check" "--format" "xml"] "unknown format \"xml\""]
               [["--libraries" yaml "--check" "--list-books" "lib"] "--check cannot be combined with --list-books"]
               [["--format" "json" "doc.hdoc"] "--format applies only to --check"]
               [["--libraries" yaml "--check" "--extensions" "nowhere.clj"] "nowhere.clj"]]]
        (let [{:keys [status out err]} (apply main-run args)]
          (if message
            (do (is (= 2 status) (pr-str args err))
                (is (= "" out) (pr-str args))
                (is (str/includes? err message) (pr-str args err)))
            (is (= 0 status) (pr-str args err)))))))
  (testing "a build option a check has no use for is a warning on stderr; the check is unaffected"
    (let [yaml (library-dir! {"a.hdoc" "A.\n"})
          {:keys [status out err]} (main-run "--libraries" yaml "--check" "--target" "latex" "-o" "x"
                                             "--trusted-extensions")]
      (is (= 0 status) err)
      (is (= "0 problems (0 errors, 0 warnings, 0 info) in 1 book\n" out))
      (doseq [flag ["--target" "--output" "--trusted-extensions"]]
        (is (str/includes? err (str "haselnuss: warning: " flag " has no effect with --check")) err)))))

;; --- build parity ----------------------------------------------------------------

(defn- fixture-directory []
  ((references/directory-loader {:path fixtures-yaml :env {} :home "/nonexistent"})))

(defn- problem-key
  "What a build's diagnostic and a check's item are compared by: the
  problem type and where it is (canonical file, line). Messages and paths
  are formatted differently (a check strips the location prefix)."
  [problem-type file line]
  [problem-type (canonical file) line])

(deftest build-parity-test
  (testing "TASK-240 review: the problems a check reports for the fixture books are
            exactly the warnings the CLI prints building each of them, taken together.
            Together, because a build also prints problems inside what it quotes -- a
            quoted book's load warnings, a cycle closed in the quoted book -- each of
            which the check reports once, on the book or library it concerns"
    (let [dir (fixture-directory)
          books (for [lib-name (library/library-names dir)
                      :let [lib (library/find-library dir lib-name)]
                      :when (satisfies? library/Library lib)
                      entry (library/list-books lib)
                      :let [book (library/find-book lib (:slug (:id entry)))]
                      :when (fs/filesystem-book? book)]
                  (book-ref/format-book-id (:id entry)))
          out-file (str (io/file (temp-dir) "out.html"))
          builds (into {} (for [b books]
                            [b (try (cli/build (merge overrides {:libraries fixtures-yaml :input b
                                                                 :target "html" :output out-file}))
                                    (catch clojure.lang.ExceptionInfo e {:failed (ex-message e)}))]))
          ;; The directory's own load problem (library "broken"), which
          ;; every build prints and the check reports on the library's
          ;; record, as the :unavailable-library it makes it.
          key-of (fn [d] (problem-key (:type d) (or (:source-file d) (:file d)) (:source-line d)))
          directory-keys (set (map key-of (library/library-diagnostics dir)))
          build-keys (set (for [[_ {:keys [diagnostics]}] builds
                                d diagnostics
                                :let [k (key-of d)]
                                :when (not (contains? directory-keys k))]
                            k))
          failed (set (keep (fn [[b r]] (when (:failed r) b)) builds))
          report (:report (cli/check-libraries (merge overrides {:libraries fixtures-yaml}) []))
          generated? (fn [book] (str/starts-with? (str book) "bible:scripture/"))
          check-keys (set (for [{:keys [file line book] :as d} (:diagnostics report)
                                :when (and book (not (generated? book)))]
                            (problem-key (:type d) file line)))]
      (is (= 23 (count books)) "every filesystem book a build can be given")
      (is (= #{"pass:creeds/nicene"} failed)
          "a missing bibliography fails a build of the book itself, while the builds
           quoting or linking it print it as a warning, which the check reports")
      (is (= 1 (count directory-keys)))
      (is (some #(and (nil? (:book %)) (= :unavailable-library (:type %))
                      (= (canonical fixtures-yaml) (canonical (:file %))))
                (:diagnostics report)))
      (is (seq build-keys))
      (is (= build-keys check-keys))
      (testing "what only the check reports: library records, and the generated books'
                own resolver diagnostics (no build takes a generated book as input)"
        (is (= #{:duplicate-slug :invalid-book-name :invalid-generated-book :script-failed
                 :scripts-disabled :unavailable-library :unnumbered-section-id}
               (set (map :type (remove #(contains? check-keys (problem-key (:type %) (:file %) (:line %)))
                                       (:diagnostics report))))))))))

(defn- transform-library!
  "Library `lib`: `a` renames its section to `sec:new` under the transform,
  which its own cross-reference needs; `b` links to `a#sec:new`."
  []
  (let [yaml (library-dir! {"a.hdoc" "---\ntitle: A\n---\n\n# A {#sec:old}\n\nSee @sec:new.\n"
                            "b.hdoc" "---\ntitle: B\n---\n\nSee @{lib:a#sec:new}.\n"})
        rule (str (write! (.getParentFile (io/file yaml)) "rename.clj"
                          (str "{:select :section\n"
                               " :transform (fn [node ctx] (assoc-in node [:attr :id] \"sec:new\"))}")))]
    {:yaml yaml :rule rule}))

(deftest transform-test
  (let [{:keys [yaml rule]} (transform-library!)
        types (fn [out] (set (map #(second (re-find #"\[([a-z-]+)\]" %)) (butlast (str/split-lines out)))))]
    (testing "without --transform, a's cross-reference dangles and b's label is unknown"
      (is (= #{"dangling-cross-ref" "unknown-label"} (types (:out (main-run "--libraries" yaml "--check"))))))
    (testing "with it, each book is checked as a build of it transforms it: a resolves;
              b's link still dangles, since a build of b reads a untransformed; and b,
              which has no section, gets the build's unmatched-rule warning"
      (let [{:keys [out]} (main-run "--libraries" yaml "--check" "--transform" rule)]
        (is (= #{"unknown-label" "transform-rule-unmatched"} (types out)) out)
        (is (re-find #"b\.hdoc: warning: \[transform-rule-unmatched\]" out) out)))
    (testing "the same as building each book with --transform prints"
      (let [build (fn [input] (set (map :type (:diagnostics (cli/build (merge overrides {:libraries yaml :input input :target "html"
                                                                                         :output (str (io/file (temp-dir) "o.html"))
                                                                                         :transform [rule]}))))))]
        (is (= #{} (build "lib:a")))
        (is (= #{:unknown-label :transform-rule-unmatched} (build "lib:b")))))))

(deftest extensions-test
  (let [dir (temp-dir)
        yaml (library-dir! {"a.hdoc" "---\ntitle: A\n---\n\n:::{callout}\nA note.\n:::\n"})
        ext (str (write! dir "callout.clj" (str "{:name \"callout\"\n"
                                                " :emit {:html (fn [d ctx] [:div ((:render-blocks ctx) (:blocks d))])}}")))]
    (testing "a directive only a sidecar defines is unknown without --extensions"
      (is (re-find #"\[unknown-directive\]" (:out (main-run "--libraries" yaml "--check")))))
    (testing "and known with it, as in a build given the same --extensions"
      (let [{:keys [status out]} (main-run "--libraries" yaml "--check" "--extensions" ext)]
        (is (= 0 status) out)
        (is (= "0 problems (0 errors, 0 warnings, 0 info) in 1 book\n" out))))))

;; --- review: text from a library cannot break the one-line-per-problem form -------

(def ^:private esc (str (char 27)))

(deftest control-characters-test
  (let [yaml (library-dir!
              {"a.hdoc" (str "---\ntitle: A\n---\n\nSee @{lib:nope" esc "[31m}.\n")
               "gen/shelf.clj"
               (str "{:books (fn [_] (throw (ex-info \"boom\\nfake.hdoc:1: error: [injected] pwned\\n"
                    "\\u001b[31mRED\" {})))\n :document (fn [_ _] nil)}\n")}
              "  scripts: true\n")
        {:keys [status out]} (main-run "--libraries" yaml "--check")
        lines (str/split-lines out)]
    (testing "a script's exception text and a reference holding ESC each stay on one line,
              escaped, with no raw control character on stdout"
      (is (= 1 status))
      (is (= 3 (count lines)) out)
      (is (= "2 problems (0 errors, 2 warnings, 0 info) in 1 book" (last lines)))
      (is (not (some #(str/starts-with? % "fake.hdoc") lines)) out)
      (is (not (re-find #"[\u0000-\u0009\u000b-\u001f\u007f-\u009f\u2028\u2029]" out)) out)
      (is (some #(re-find #"\[script-failed\] .*boom\\nfake\.hdoc:1: error: \[injected\] pwned\\n\\u001b\[31mRED" %)
                lines)
          out)
      (is (some #(re-find #"\[[a-z-]+\] lib:nope\\u001b\[31m: " %) lines) out))
    (testing "the JSON form carries the exact text, escaped by JSON itself"
      (let [{json-out :out} (main-run "--libraries" yaml "--check" "--format" "json")
            items (get (json/read-str json-out) "diagnostics")]
        (is (= 1 (count (str/split-lines json-out))))
        (is (not (str/includes? json-out esc)))
        (is (some #(str/includes? (get % "message") (str "boom\nfake.hdoc:1: error: [injected] pwned\n" esc "[31mRED"))
                  items))
        (is (some #(= (str "lib:nope" esc "[31m") (get % "ref")) items))))))

;; --- review: quote cycles through nested labels and range interiors ---------------

(defn- quote-cycles-by-check
  [yaml]
  (set (for [{:keys [file line] :as d} (:diagnostics (:report (cli/check-libraries (merge overrides {:libraries yaml}) [])))
             :when (= :quote-cycle (:type d))]
         [(canonical file) line])))

(defn- quote-cycles-by-builds
  [yaml books]
  (set (for [b books
             d (:diagnostics (cli/build (merge overrides {:libraries yaml :input b :target "html"
                                                          :output (str (io/file (temp-dir) "o.html"))})))
             :when (= :quote-cycle (:type d))]
         [(canonical (:source-file d)) (:source-line d)])))

(defn- lib-file [yaml rel] (canonical (io/file (.getParentFile (io/file yaml)) "lib" rel)))

(deftest nested-quote-cycle-test
  (testing "both quotes under a nested label of the quoted section: every build warns, so
            does --check, at the same file:line"
    (let [book (fn [other] (str "---\ntitle: T\n---\n\n# Outer {#sec:outer}\n\n## Inner {#sec:inner}\n\n"
                                "!quote lib:" other "#sec:outer\n"))
          yaml (library-dir! {"a.hdoc" (book "b") "b.hdoc" (book "a")})
          expected #{[(lib-file yaml "a.hdoc") 9] [(lib-file yaml "b.hdoc") 9]}]
      (is (= expected (quote-cycles-by-builds yaml ["lib:a" "lib:b"])))
      (is (= expected (quote-cycles-by-check yaml)))
      (is (= 1 (:status (main-run "--libraries" yaml "--check"))))))
  (testing "one side nested: both quotes are reported"
    (let [yaml (library-dir! {"a.hdoc" "---\ntitle: A\n---\n\n# Outer {#sec:outer}\n\n!quote lib:b#sec:outer\n"
                              "b.hdoc" (str "---\ntitle: B\n---\n\n# Outer {#sec:outer}\n\nText.\n\n"
                                            "## Inner {#sec:inner}\n\n!quote lib:a#sec:outer\n")})
          expected #{[(lib-file yaml "a.hdoc") 7] [(lib-file yaml "b.hdoc") 11]}]
      (is (= expected (quote-cycles-by-builds yaml ["lib:a" "lib:b"])))
      (is (= expected (quote-cycles-by-check yaml)))))
  (testing "a quote under a label strictly inside a quoted range"
    (let [yaml (library-dir! {"a.hdoc" "---\ntitle: A\n---\n\n# A {#sec:a}\n\n!quote lib:b#sec:p..sec:r\n"
                              "b.hdoc" (str "---\ntitle: B\n---\n\n# P {#sec:p}\n\nP.\n\n# Q {#sec:q}\n\n"
                                            "!quote lib:a\n\n# R {#sec:r}\n\nR.\n")})
          expected #{[(lib-file yaml "a.hdoc") 7] [(lib-file yaml "b.hdoc") 11]}]
      (is (= expected (quote-cycles-by-builds yaml ["lib:a" "lib:b"])))
      (is (= expected (quote-cycles-by-check yaml)))))
  (testing "a quote outside the quoted label is no cycle for builds or --check"
    (let [yaml (library-dir! {"a.hdoc" "---\ntitle: A\n---\n\n# A {#sec:a}\n\n!quote lib:b#sec:p\n"
                              "b.hdoc" (str "---\ntitle: B\n---\n\n# P {#sec:p}\n\nP.\n\n# Q {#sec:q}\n\n"
                                            "!quote lib:a\n")})
          expected #{[(lib-file yaml "a.hdoc") 7]}]
      (is (= expected (quote-cycles-by-builds yaml ["lib:a" "lib:b"]))
          "a build of b meets the cycle at a's quote; b's own quote sits outside the
           passage a quotes, so a build of a never meets it")
      (is (= expected (quote-cycles-by-check yaml))))))

;; --- TASK-249: a cycle closed by a quote whose passage does not resolve -------------

(deftest dangling-quote-cycle-test
  (testing "the task's repro: a's range is bad (labels not siblings), but a build of b
            meets a's quote of b while b is being quoted -- a quote-cycle, found by book
            before the passage is resolved -- and --check reports it at the same place"
    (let [yaml (library-dir! {"a.hdoc" "---\ntitle: A\n---\n\n# A {#sec:a}\n\n!quote lib:b#sec:p..sec:inner\n"
                              "b.hdoc" (str "---\ntitle: B\n---\n\n# P {#sec:p}\n\n## Inner {#sec:inner}\n\n"
                                            "!quote lib:a\n")})
          expected #{[(lib-file yaml "a.hdoc") 7]}
          {:keys [out]} (main-run "--libraries" yaml "--check")]
      (is (= expected (quote-cycles-by-builds yaml ["lib:a" "lib:b"])))
      (is (= expected (quote-cycles-by-check yaml)))
      (testing "next to the passage's own problem, which a build of a reports"
        (is (re-find #"(?m)^.*a\.hdoc:7:1: warning: \[bad-label-range\] lib:b#sec:p\.\.sec:inner: " out) out)
        (is (re-find #"(?m)^.*a\.hdoc:7:1: warning: \[quote-cycle\] lib:b#sec:p\.\.sec:inner: quote cycle: !quote lib:b#sec:p\.\.sec:inner names a book that is already being quoted, via lib:b -> lib:a -> lib:b$" out)
            out))))
  (testing "an unknown label, written with a shelf path the book is not on: still the
            book on the stack (same library and slug), as a build matches it"
    (let [yaml (library-dir! {"a.hdoc" "---\ntitle: A\n---\n\n!quote lib:old/b#sec:nope\n"
                              "b.hdoc" "---\ntitle: B\n---\n\nText.\n\n!quote lib:a\n"})
          expected #{[(lib-file yaml "a.hdoc") 5]}]
      (is (= expected (quote-cycles-by-builds yaml ["lib:a" "lib:b"])))
      (is (= expected (quote-cycles-by-check yaml)))))
  (testing "a dangling quote of a slug two books share is no cycle: quoting it is
            ambiguous, so no build has either book on its stack"
    (let [yaml (library-dir! {"a.hdoc" "---\ntitle: A\n---\n\n# A {#sec:a}\n\n!quote lib:b#sec:p\n"
                              "x/b.hdoc" "---\ntitle: XB\n---\n\n!quote lib:a\n"
                              "y/b.hdoc" "---\ntitle: YB\n---\n\nText.\n"})]
      (is (= #{} (quote-cycles-by-builds yaml ["lib:a" (lib-file yaml "x/b.hdoc") (lib-file yaml "y/b.hdoc")])))
      (is (= #{} (quote-cycles-by-check yaml)))))
  (testing "a dangling quote of a book that is not on the stack is no cycle"
    (let [yaml (library-dir! {"a.hdoc" "---\ntitle: A\n---\n\n!quote lib:nowhere#sec:x\n\n!quote lib:b\n"
                              "b.hdoc" "---\ntitle: B\n---\n\n!quote lib:a#sec:nope\n"})
          expected #{[(lib-file yaml "b.hdoc") 5]}]
      (is (= expected (quote-cycles-by-builds yaml ["lib:a" "lib:b"])))
      (is (= expected (quote-cycles-by-check yaml))))))

(def ^:private fuzz-passages
  "What a fuzzed quote takes: the whole book, labels at both levels, a
  good range, a bad one (`sec:p..sec:inner` are not siblings) and an
  unknown label -- the last two never resolve."
  ["" "#sec:p" "#sec:inner" "#sec:q" "#sec:p..sec:q" "#sec:p..sec:inner" "#sec:nope"])

(def ^:private fuzz-books
  "What a fuzzed quote names: the library's three books, one with a shelf
  path it is not on, and one that does not exist."
  ["a" "b" "c" "old/a" "nowhere"])

(def ^:private fuzz-slot-lines
  "The line each of a fuzzed book's three slots is on: under `sec:p`,
  under `sec:inner` (nested in it), under `sec:q`."
  [7 11 15])

(defn- fuzz-book
  "A book whose three slots hold `slots` (a quote's text, or nil for a
  paragraph)."
  [slots]
  (let [[s0 s1 s2] (map #(or % "Text.") slots)]
    (str "---\ntitle: T\n---\n\n# P {#sec:p}\n\n" s0 "\n\n## Inner {#sec:inner}\n\n" s1
         "\n\n# Q {#sec:q}\n\n" s2 "\n")))

(deftest dangling-quote-cycle-fuzz-test
  (testing "seeded (249): random three-book libraries whose quotes may not resolve report,
            under --check, exactly the quote-cycles that building every book prints"
    (let [rng (java.util.Random. 249)
          pick (fn [v] (nth v (.nextInt rng (count v))))
          runs 30
          hits (atom 0)]
      (dotimes [_ runs]
        (let [slots (into {} (for [b ["a" "b" "c"]]
                               [b (vec (repeatedly 3 #(when (< (.nextInt rng 10) 6)
                                                        [(pick fuzz-books) (pick fuzz-passages)])))]))
              yaml (library-dir! (into {} (for [[b ss] slots]
                                            [(str b ".hdoc")
                                             (fuzz-book (map #(when % (str "!quote lib:" (first %) (second %))) ss))])))
              by-builds (quote-cycles-by-builds yaml ["lib:a" "lib:b" "lib:c"])
              dangling-at (set (for [[b ss] slots
                                     [i s] (map-indexed vector ss)
                                     :when (and s (contains? #{"#sec:p..sec:inner" "#sec:nope"} (second s)))]
                                 [(lib-file yaml (str b ".hdoc")) (fuzz-slot-lines i)]))]
          (swap! hits + (count (filter dangling-at by-builds)))
          (is (= by-builds (quote-cycles-by-check yaml)) (pr-str slots))))
      (is (<= 10 @hits)
          (str "not vacuous: builds met " @hits " cycles at quotes whose passage does not resolve")))))

;; --- TASK-254: dense quoting, too-deep chains --------------------------------------

(defn- quote-problems-by-check
  [yaml]
  (set (for [{:keys [file line] :as d} (:diagnostics (:report (cli/check-libraries (merge overrides {:libraries yaml}) [])))
             :when (#{:quote-cycle :quote-too-deep} (:type d))]
         [(:type d) (canonical file) line])))

(defn- quote-problems-by-builds
  [yaml books]
  (set (for [b books
             d (:diagnostics (cli/build (merge overrides {:libraries yaml :input b :target "html"
                                                          :output (str (io/file (temp-dir) "o.html"))})))
             :when (#{:quote-cycle :quote-too-deep} (:type d))]
         [(:type d) (canonical (:source-file d)) (:source-line d)])))

(defn- dense-book
  "A fuzz book (`fuzz-book`'s sections) whose three slots each hold the
  quotes `slots` names, one paragraph apiece."
  [slots]
  (let [[s0 s1 s2] (map #(if (seq %) (str/join "\n\n" %) "Text.") slots)]
    (str "---\ntitle: T\n---\n\n# P {#sec:p}\n\n" s0 "\n\n## Inner {#sec:inner}\n\n" s1
         "\n\n# Q {#sec:q}\n\n" s2 "\n")))

(defn- dense-quote-parity-fuzz
  "Runs `runs` seeded random libraries of 9-11 books along a quoting chain
  long enough to nest past the depth limit, with random extra quotes
  forward (several paths to a book), backward (cycles; `backward` the
  chance per earlier book) and whose passage does not resolve, asserting
  --check reports exactly the quote-cycles and quote-too-deeps building
  every book prints. `{:deep n :cycle n :both n}`: how many too-deeps,
  cycles, and quotes reported as both, builds met."
  [seed runs backward]
  (let [rng (java.util.Random. (long seed))
        deep-hits (atom 0)
        cycle-hits (atom 0)
        both-hits (atom 0)
        passages ["" "" "#sec:p" "#sec:inner" "#sec:q" "#sec:p..sec:q" "#sec:p..sec:inner" "#sec:nope"]]
    (dotimes [_ runs]
      (let [n (+ 9 (.nextInt rng 3))
            names (mapv #(str "b" (char (+ (int \a) %))) (range n))
            quote-of (fn [j] (str "!quote lib:" (if (zero? (.nextInt rng 25)) "nowhere" (names j))
                                  (nth passages (.nextInt rng (count passages)))))
            slots (into {} (for [i (range n)]
                             [(names i)
                              (vec (for [s (range 3)]
                                     (vec (concat
                                             ;; the backbone: each book quotes the next whole
                                           (when (and (= s (mod i 3)) (< (inc i) n)) [(str "!quote lib:" (names (inc i)))])
                                           (for [j (range n)
                                                 :when (< (.nextDouble rng) (cond (= j (+ i 2)) 0.25
                                                                                  (> j i) 0.03
                                                                                  :else backward))]
                                             (quote-of j))))))]))
            yaml (library-dir! (into {} (for [[b ss] slots] [(str b ".hdoc") (dense-book ss)])))
            by-builds (quote-problems-by-builds yaml (map #(str "lib:" %) names))
            at (fn [kind] (set (keep (fn [[t f l]] (when (= kind t) [f l])) by-builds)))]
        (swap! deep-hits + (count (at :quote-too-deep)))
        (swap! cycle-hits + (count (at :quote-cycle)))
        (swap! both-hits + (count (set/intersection (at :quote-too-deep) (at :quote-cycle))))
        (is (= by-builds (quote-problems-by-check yaml)) (pr-str slots))))
    {:deep @deep-hits :cycle @cycle-hits :both @both-hits}))

(deftest ^:slow dense-quote-parity-fuzz-test
  (testing "seeded (254): dense libraries report under --check exactly the quote-cycles
            and quote-too-deeps building every book prints (`dense-quote-parity-fuzz`)"
    (let [{:keys [deep] cycles :cycle} (dense-quote-parity-fuzz 254 20 0.015)]
      (is (<= 10 deep) (str "not vacuous: builds met " deep " quotes nested too deep"))
      (is (<= 10 cycles) (str "not vacuous: builds met " cycles " quote cycles"))))
  (testing "seeded (256): the same with many more backward quotes, so that a quote one
            build meets as a cycle is often one another build meets too deep, and many
            chains pass through a cycle's books before reaching the depth limit"
    (let [{:keys [deep both] cycles :cycle} (dense-quote-parity-fuzz 256 20 0.06)]
      (is (<= 10 deep) (str "not vacuous: builds met " deep " quotes nested too deep"))
      (is (<= 10 cycles) (str "not vacuous: builds met " cycles " quote cycles"))
      (is (<= 5 both) (str "not vacuous: builds met " both " quotes both as a cycle and too deep")))))

(deftest cycle-and-too-deep-test
  (testing "TASK-256: x quotes y and y quotes x, under a chain a0 -> ... -> a7 -> x. A
            build of y meets x's quote as a cycle, a build of a0 meets it at depth 9 --
            too deep -- and --check reports both, at the same place. y's quote is only a
            cycle: every chain to it nine books long passes through x, which it names."
    (let [chain (into {} (for [i (range 8)]
                           [(str "a" i ".hdoc")
                            (str "---\ntitle: T\n---\n\n!quote lib:" (if (= i 7) "x" (str "a" (inc i))) "\n")]))
          yaml (library-dir! (merge chain {"x.hdoc" "---\ntitle: X\n---\n\n!quote lib:y\n"
                                           "y.hdoc" "---\ntitle: Y\n---\n\n!quote lib:x\n"}))
          x [(lib-file yaml "x.hdoc") 5]
          y [(lib-file yaml "y.hdoc") 5]
          expected #{(into [:quote-cycle] x) (into [:quote-cycle] y) (into [:quote-too-deep] x)}
          items (:diagnostics (:report (cli/check-libraries (merge overrides {:libraries yaml}) [])))
          deep (first (filter #(= :quote-too-deep (:type %)) items))]
      (is (= expected
             (quote-problems-by-builds yaml (concat (map #(str "lib:a" %) (range 8)) ["lib:x" "lib:y"]))))
      (is (= expected (quote-problems-by-check yaml)))
      (is (= [5 1] [(:line deep) (:column deep)]) (pr-str deep))
      (is (str/includes? (:message deep) (str "nested 9 quotes deep, more than the 8 allowed, via "
                                              "lib:a0 -> lib:a1 -> lib:a2 -> lib:a3 -> lib:a4 -> lib:a5"
                                              " -> lib:a6 -> lib:a7 -> lib:x"))
          (:message deep))))
  (testing "the chain passes through the cycle first: a0 -> ... -> a6 -> x -> y, y quoting
            x and z. A build of a0 meets y's quote of x as a cycle at depth 9, so it is not
            too deep; y's quote of z, at depth 9 too, is."
    (let [chain (into {} (for [i (range 7)]
                           [(str "a" i ".hdoc")
                            (str "---\ntitle: T\n---\n\n!quote lib:" (if (= i 6) "x" (str "a" (inc i))) "\n")]))
          yaml (library-dir! (merge chain {"x.hdoc" "---\ntitle: X\n---\n\n!quote lib:y\n"
                                           "y.hdoc" "---\ntitle: Y\n---\n\n!quote lib:x\n\n!quote lib:z\n"
                                           "z.hdoc" "---\ntitle: Z\n---\n\nText.\n"}))
          by-builds (quote-problems-by-builds yaml (concat (map #(str "lib:a" %) (range 7)) ["lib:x" "lib:y" "lib:z"]))]
      (is (= #{[:quote-cycle (lib-file yaml "x.hdoc") 5]
               [:quote-cycle (lib-file yaml "y.hdoc") 5]
               [:quote-too-deep (lib-file yaml "y.hdoc") 7]}
             by-builds))
      (is (= by-builds (quote-problems-by-check yaml)))))
  (testing "a ring of books each quoting the next: a build of one meets the quote back to
            it as a cycle only when the ring is short enough to expand that far -- nine
            books (the closing quote at depth 9, checked for a cycle first) but not ten,
            where each build stops too deep one quote short of the cycle"
    (doseq [[n kind] [[9 :quote-cycle] [10 :quote-too-deep]]]
      (let [names (mapv #(str "r" %) (range n))
            yaml (library-dir! (into {} (for [i (range n)]
                                          [(str (names i) ".hdoc")
                                           (str "---\ntitle: T\n---\n\n!quote lib:" (names (mod (inc i) n)) "\n")])))
            expected (set (for [b names] [kind (lib-file yaml (str b ".hdoc")) 5]))]
        (is (= expected (quote-problems-by-builds yaml (map #(str "lib:" %) names))) (str n " books"))
        (is (= expected (quote-problems-by-check yaml)) (str n " books")))))
  (testing "the only way from y back to x's quote of y passes through b twice (y quotes
            b's section P, which quotes c, which quotes b's section Q, which quotes x): every
            build stops at a cycle before x's quote, so it is no cycle itself"
    (let [yaml (library-dir! {"y.hdoc" "---\ntitle: Y\n---\n\n!quote lib:b#sec:p\n"
                              "b.hdoc" "---\ntitle: B\n---\n\n# P {#sec:p}\n\n!quote lib:c\n\n# Q {#sec:q}\n\n!quote lib:x\n"
                              "c.hdoc" "---\ntitle: C\n---\n\n!quote lib:b#sec:q\n"
                              "x.hdoc" "---\ntitle: X\n---\n\n!quote lib:y\n"})
          by-builds (quote-problems-by-builds yaml ["lib:y" "lib:b" "lib:c" "lib:x"])]
      (is (seq by-builds))
      (is (not (contains? by-builds [:quote-cycle (lib-file yaml "x.hdoc") 5])) (pr-str by-builds))
      (is (= by-builds (quote-problems-by-check yaml)))))
  (testing "a host two books share the library and slug of is built by path, so it is not
            on its own build's chain: s1/d quotes a1, a1 ... a7 each the next, a8 quotes
            lib:d -- at depth 9, too deep, though it names the host's slug"
    (let [chain (into {} (for [i (range 1 9)]
                           [(str "a" i ".hdoc")
                            (str "---\ntitle: T\n---\n\n!quote lib:" (if (= i 8) "d" (str "a" (inc i))) "\n")]))
          yaml (library-dir! (merge chain {"s1/d.hdoc" "---\ntitle: D1\n---\n\n!quote lib:a1\n"
                                           "s2/d.hdoc" "---\ntitle: D2\n---\n\nText.\n"}))
          by-builds (quote-problems-by-builds yaml (concat [(lib-file yaml "s1/d.hdoc") (lib-file yaml "s2/d.hdoc")]
                                                           (map #(str "lib:a" %) (range 1 9))))]
      (is (= #{[:quote-too-deep (lib-file yaml "a8.hdoc") 5]} by-builds))
      (is (= by-builds (quote-problems-by-check yaml))))))

(defn- layered-library!
  "`layers` x `width` books, each quoting every book of the next layer."
  [layers width]
  (library-dir! (into {} (for [l (range layers) w (range width)]
                           [(format "l%db%d.hdoc" l w)
                            (str "---\ntitle: T\n---\n\n"
                                 (if (< l (dec layers))
                                   (apply str (for [v (range width)] (format "!quote lib:l%db%d\n\n" (inc l) v)))
                                   "Text.\n"))]))))

(deftest ^:timing dense-quote-timing-test
  (testing "TASK-254: quote-too-deep detection does not walk every path of a densely
            quoting library -- 8 layers of 6 books, each quoting every book of the next
            (6^7 paths down from a top book), took over 9 minutes"
    (let [timed (fn [yaml]
                  (let [t (System/nanoTime)
                        r (main-run "--libraries" yaml "--check")]
                    [(/ (- (System/nanoTime) t) 1e9) r]))
          [t8 r8] (timed (layered-library! 8 6))
          [t7 r7] (timed (layered-library! 7 5))
          [t10 r10] (timed (layered-library! 10 6))]
      (is (< t8 5.0) (str "8x6 took " t8 " s"))
      (is (< t7 1.0) (str "7x5 took " t7 " s"))
      (is (= 0 (:status r8) (:status r7)) "the deepest quote is at depth 7: within the limit")
      (testing "10 layers: each quote of the ninth layer is at depth 9, past the limit of 8"
        (is (< t10 5.0) (str "10x6 took " t10 " s"))
        (is (= 36 (count (re-seq #"\[quote-too-deep\]" (:out r10)))) (:out r10))
        (is (str/includes? (:out r10) (str "!quote lib:l9b0 is nested 9 quotes deep, more than the 8 allowed, via "
                                           "lib:l0b0 -> lib:l1b0 -> lib:l2b0 -> lib:l3b0 -> lib:l4b0 -> lib:l5b0"
                                           " -> lib:l6b0 -> lib:l7b0 -> lib:l8b0"))
            (:out r10))))))

(deftest ^:timing dense-cyclic-timing-test
  (testing "TASK-257: nine books each quoting every other. Every quote closes a cycle,
            and none is too deep -- a chain of nine quotes above one needs nine books
            besides the one it names, one more than there are -- which the search for a
            too-deep chain tried every ordering of the books to find out (over 20 s)"
    (let [names (mapv #(str "c" %) (range 9))
          yaml (library-dir! (into {} (for [b names]
                                        [(str b ".hdoc")
                                         (str "---\ntitle: T\n---\n\n"
                                              (str/join (for [o names :when (not= o b)] (str "!quote lib:" o "\n\n"))))])))
          t (System/nanoTime)
          {:keys [out]} (main-run "--libraries" yaml "--check")
          secs (/ (- (System/nanoTime) t) 1e9)]
      (is (< secs 3.0) (str "took " secs " s"))
      (is (= 72 (count (re-seq #"\[quote-cycle\]" out))) out)
      (is (not (str/includes? out "[quote-too-deep]")) out)))
  (testing "four books each quoting fifteen others, which each quote the four back: a
            chain goes back and forth, so takes at most four of the fifteen, and none
            is long enough -- for which the search tried every ordering of the fifteen
            (over a minute)"
    (let [whole (fn [others] (str "---\ntitle: T\n---\n\n" (str/join (for [o others] (str "!quote lib:" o "\n\n")))))
          as (mapv #(str "a" %) (range 4))
          bs (mapv #(str "b" %) (range 15))
          yaml (library-dir! (merge (into {} (for [a as] [(str a ".hdoc") (whole bs)]))
                                    (into {} (for [b bs] [(str b ".hdoc") (whole as)]))))
          t (System/nanoTime)
          {:keys [out]} (main-run "--libraries" yaml "--check")
          secs (/ (- (System/nanoTime) t) 1e9)]
      (is (< secs 3.0) (str "took " secs " s"))
      (is (= 120 (count (re-seq #"\[quote-cycle\]" out))) out)
      (is (not (str/includes? out "[quote-too-deep]")) out)))
  (testing "TASK-260: eight books each quoting every other, one of them also quoting and
            quoted by sixty books quoting nothing else. No chain is long enough -- the
            sixty can only be a chain's host -- but neither bound saw it, and the search
            tried the orderings of the eight for each of the 176 quotes (over 10 s)"
    (let [whole (fn [others] (str "---\ntitle: T\n---\n\n" (str/join (for [o others] (str "!quote lib:" o "\n\n")))))
          cs (mapv #(str "c" %) (range 8))
          ls (mapv #(str "l" %) (range 60))
          yaml (library-dir! (merge (into {} (for [c cs]
                                               [(str c ".hdoc") (whole (concat (remove #{c} cs) (when (= c "c0") ls)))]))
                                    (into {} (for [l ls] [(str l ".hdoc") (whole ["c0"])]))))
          t (System/nanoTime)
          {:keys [out]} (main-run "--libraries" yaml "--check")
          secs (/ (- (System/nanoTime) t) 1e9)]
      (is (< secs 3.0) (str "took " secs " s"))
      (is (= 176 (count (re-seq #"\[quote-cycle\]" out))) out)
      (is (not (str/includes? out "[quote-too-deep]")) out))))

(deftest transform-quote-cycle-difference-test
  (testing "the README's documented difference (TASK-249): a quoted book's quotes and
            labels are followed in its transformed copy, while a build quotes it
            untransformed -- so a rule renaming the quoted label hides the cycle from
            --check that both builds meet. If this starts failing because --check found
            the cycles, drop the README paragraph."
    (let [yaml (library-dir! {"a.hdoc" "---\ntitle: A\n---\n\n# A {#sec:old}\n\n!quote lib:b#sec:old\n"
                              "b.hdoc" "---\ntitle: B\n---\n\n# B {#sec:old}\n\n!quote lib:a#sec:old\n"})
          rule (str (write! (.getParentFile (io/file yaml)) "rename.clj"
                            (str "{:select :section\n"
                                 " :transform (fn [node ctx] (assoc-in node [:attr :id] \"sec:new\"))}")))
          builds (set (for [b ["lib:a" "lib:b"]
                            d (:diagnostics (cli/build (merge overrides {:libraries yaml :input b :target "html"
                                                                         :transform [rule]
                                                                         :output (str (io/file (temp-dir) "o.html"))})))
                            :when (= :quote-cycle (:type d))]
                        [(canonical (:source-file d)) (:source-line d)]))]
      (is (= #{[(lib-file yaml "a.hdoc") 7] [(lib-file yaml "b.hdoc") 7]} builds))
      (is (not (str/includes? (:out (main-run "--libraries" yaml "--check" "--transform" rule)) "[quote-cycle]")))
      (is (= #{[(lib-file yaml "a.hdoc") 7] [(lib-file yaml "b.hdoc") 7]} (quote-cycles-by-check yaml))
          "without the rule, --check and the builds agree"))))

;; --- TASK-259: duplicate slugs -----------------------------------------------------

(defn- problems-by-check
  "Every problem --check reports on a book record, as `[type file line]`."
  [yaml]
  (set (for [{:keys [file line book] :as d} (:diagnostics (:report (cli/check-libraries (merge overrides {:libraries yaml}) [])))
             :when book]
         [(:type d) (canonical file) line])))

(defn- build-diagnostics
  "Every diagnostic building each of `inputs` prints."
  [yaml inputs]
  (for [input inputs
        d (:diagnostics (cli/build (merge overrides {:libraries yaml :input input :target "html"
                                                     :output (str (io/file (temp-dir) "o.html"))})))]
    d))

(defn- problem-keys [diagnostics]
  (set (map (fn [d] [(:type d) (canonical (:source-file d)) (:source-line d)]) diagnostics)))

(deftest duplicate-slug-self-quote-test
  (testing "the task's repros: a book whose slug another book shares quotes that slug. A
            build of it (by path: no reference singles it out) finds the quote ambiguous,
            and --check reports that, not a quote of the book itself"
    (doseq [[books quoting slug]
            [[{"b0.hdoc" "---\ntitle: B\n---\n\n!quote lib:b0\n"
               "zz/b0.hdoc" "---\ntitle: Z\n---\n\nText.\n"} "b0.hdoc" "b0"]
             [{"x.hdoc" "---\ntitle: X1\n---\n\n!quote lib:x\n"
               "x/book.hdoc" "---\ntitle: X2\n---\n\nText.\n"} "x.hdoc" "x"]
             [{"s1/d.hdoc" "---\ntitle: D1\n---\n\n!quote lib:d#sec:nope\n"
               "s2/d.hdoc" "---\ntitle: D2\n---\n\n!quote lib:s2/d\n"} "s1/d.hdoc" "d"]]]
      (let [yaml (library-dir! books)
            builds (problem-keys (build-diagnostics yaml (map #(lib-file yaml %) (keys books))))
            {:keys [out]} (main-run "--libraries" yaml "--check")]
        (is (contains? builds [:ambiguous-book (lib-file yaml quoting) 5]) (pr-str builds))
        (is (= builds (problems-by-check yaml)) out)
        (is (not (str/includes? out "[quote-cycle]")) out)
        (is (re-find (re-pattern (str "(?m)\\[ambiguous-book\\] lib:\\S*" slug "\\S*: slug \"" slug "\" ")) out) out)))))

(def ^:private dup-passages
  ["" "" "#sec:p" "#sec:inner" "#sec:q" "#sec:p..sec:q" "#sec:nope"])

(defn- duplicate-slug-library
  "A seeded random library (`rng`): a chain c0 -> c1 -> ... of 8-10 books,
  long enough to nest past the depth limit, and 1-3 slugs d0, d1, ... each
  held by two books -- on two shelves, or as `d.hdoc` beside `d/book.hdoc`
  (one canonical id) -- which mostly quote the head of the chain. Every
  book also quotes random books (a duplicated slug's own included), some
  of them through a passage that does not resolve. `{:books {path source}
  :inputs [...]}`, `:inputs` what a build of each book is given (a
  duplicated slug's books by path, relative to `lib/`)."
  [^java.util.Random rng]
  (let [shuffled (fn [v] (let [l (java.util.ArrayList. ^java.util.Collection v)]
                           (java.util.Collections/shuffle l rng)
                           (vec l)))
        m (+ 8 (.nextInt rng 3))
        chain (mapv #(str "c" %) (range m))
        dups (mapv #(str "d" %) (range (inc (.nextInt rng 3))))
        slugs (into chain dups)
        layout (merge (into {} (for [s chain] [s [(str s ".hdoc")]]))
                      (into {} (for [s dups]
                                 [s (if (< (.nextInt rng 10) 6)
                                      (let [[a b] (shuffled ["s1" "zz" "a" "m"])]
                                        [(str a "/" s ".hdoc") (str b "/" s ".hdoc")])
                                      [(str s ".hdoc") (str s "/book.hdoc")])])))
        quote-of (fn [] (str "!quote lib:" (if (zero? (.nextInt rng 25)) "nowhere" (nth slugs (.nextInt rng (count slugs))))
                             (nth dup-passages (.nextInt rng (count dup-passages)))))
        extra (fn [] (vec (repeatedly (if (< (.nextInt rng 10) 6) 0 (inc (.nextInt rng 2))) quote-of)))]
    {:books (into {} (for [s (sort (keys layout))
                           path (layout s)
                           :let [i (.indexOf ^java.util.List chain s)]]
                       [path (dense-book
                              (for [k (range 3)]
                                (vec (concat (cond
                                               (and (<= 0 i) (= k (mod i 3)) (< (inc i) m)) [(str "!quote lib:" (chain (inc i)))]
                                               (and (neg? i) (zero? k) (< (.nextInt rng 10) 7)) [(str "!quote lib:" (chain (.nextInt rng 2)))])
                                             (extra)))))]))
     :inputs (vec (for [s (sort (keys layout))
                        :let [paths (layout s)]
                        input (if (= 1 (count paths)) [(str "lib:" s)] paths)]
                    input))}))

(defn- own-slug
  "The slug of the book whose entry file is `file` (`x.hdoc`, or `x/book.hdoc`)."
  [file]
  (let [f (io/file file)
        n (.getName f)]
    (if (= "book.hdoc" n) (.getName (.getParentFile f)) (str/replace n #"\.hdoc$" ""))))

(deftest ^:slow duplicate-slug-parity-fuzz-test
  (testing "seeded (259): libraries with duplicated slugs report under --check exactly the
            problems building every book prints -- a duplicated slug's books by path"
    (let [rng (java.util.Random. 259)
          hits (atom {})]
      (dotimes [_ 15]
        (let [{:keys [books inputs]} (duplicate-slug-library rng)
              yaml (library-dir! books)
              built (build-diagnostics yaml (map #(if (str/starts-with? % "lib:") % (lib-file yaml %)) inputs))
              self (filter #(and (= :ambiguous-book (:type %))
                                 (= (own-slug (:source-file %))
                                    (:slug (:id (book-ref/parse-book-ref (:ref %))))))
                           built)]
          (swap! hits #(merge-with + % (frequencies (map :type built)) {:self-quote (count self)}))
          (is (= (problem-keys built) (problems-by-check yaml)) (pr-str books))))
      (doseq [[k at-least] {:self-quote 5 :ambiguous-book 100 :quote-too-deep 50 :quote-cycle 50}]
        (is (<= at-least (get @hits k 0)) (str "not vacuous: builds met " (get @hits k 0) " " k))))))

(defn- relisted
  "`lib` listing its books in the order `perm` (indexes into its listing),
  every other answer its own."
  [lib perm]
  (let [books (vec (library/listed-books lib))
        entries (vec (library/list-books lib))]
    (reify
      library/Library
      (library-name [_] (library/library-name lib))
      (shelf-tree [_] (library/shelf-tree lib))
      (list-books [_] (mapv entries perm))
      (find-book [_ slug] (library/find-book lib slug))
      library/LibraryDiagnostics
      (library-diagnostics [_] (library/library-diagnostics lib))
      library/ListedBooks
      (listed-books [_] (mapv books perm)))))

(defn- scanned
  "`{:report check-report :records {id record-with-edges}}` of a scan of
  `lib` alone."
  [lib]
  (let [g (scan/scan (scan/context {:directory (library/library-directory [lib])}) nil)]
    {:report (graph-check/check-report g)
     :records (into {} (for [[id r] (graph/indexed-books g)] [id (assoc r :edges (graph/edges-from g id))]))}))

(deftest duplicate-slug-listing-order-fuzz-test
  (testing "seeded (259): the scan of a library with duplicated slugs -- every record,
            edges and hash included, and the --check report -- is the same whatever
            order the library lists its books in"
    (let [rng (java.util.Random. 259)
          swapped (atom 0)]
      (dotimes [_ 25]
        (let [{:keys [books]} (duplicate-slug-library rng)
              yaml (library-dir! books)
              lib (library/find-library ((references/directory-loader {:path yaml :env {} :home "/nonexistent"})) "lib")
              n (count (library/listed-books lib))
              keys-listed (mapv #(book-ref/book-key (:id (library/book-entry %))) (library/listed-books lib))
              base (scanned (relisted lib (vec (range n))))]
          (dotimes [_ 3]
            (let [perm (let [l (java.util.ArrayList. ^java.util.Collection (range n))]
                         (java.util.Collections/shuffle l rng)
                         (vec l))]
              (when (some (fn [[i j]] (and (= (keys-listed (perm i)) (keys-listed (perm j))) (> (perm i) (perm j))))
                          (for [i (range n) j (range (inc i) n)] [i j]))
                (swap! swapped inc))
              (is (= base (scanned (relisted lib perm))) (pr-str perm books))))))
      (is (<= 30 @swapped) (str "not vacuous: " @swapped " listings swapped two books sharing a slug")))))

;; --- TASK-288: what the sanitizer drops ----------------------------------------------

(deftest sanitizer-drops-are-reported-test
  (let [yaml (library-dir! {"b.hdoc" "---\ntitle: B\n---\n\nSee [x](javascript:alert(1)) and [y](https://ok.example).\n"
                            "c.hdoc" "---\ntitle: C\n---\n\nClean.\n"})
        dropped "Dropped the link to \"javascript:alert(1)\""]
    (testing "text: one warning for the book, exit 1, as a build reports it"
      (let [{:keys [status out]} (main-run "--libraries" yaml "--check")
            lines (str/split-lines out)]
        (is (= 1 status))
        (is (= 2 (count lines)) out)
        (is (re-find #"lib/b\.hdoc: warning: \[unsafe-url\] " (first lines)))
        (is (str/includes? (first lines) dropped))
        (is (= "1 problem (0 errors, 1 warning, 0 info) in 2 books" (last lines)))))
    (testing "json: the same item, summary consistent"
      (let [{:keys [status out]} (main-run "--libraries" yaml "--check" "--format" "json")
            report (json/read-str out)]
        (is (= 1 status))
        (is (= ["unsafe-url"] (map #(get % "type") (get report "diagnostics"))))
        (is (= "lib:b" (get-in report ["diagnostics" 0 "book"])))
        (is (= 1 (get-in report ["summary" "warnings"])))
        (is (= 1 (get-in report ["summary" "problems"])))))
    (testing "scoped to the library that holds the book"
      (is (= 1 (:status (main-run "--libraries" yaml "--check" "lib"))))))
  (testing "a book without such a link stays clean"
    (let [yaml (library-dir! {"c.hdoc" "---\ntitle: C\n---\n\nSee [y](https://ok.example).\n"})
          {:keys [status out]} (main-run "--libraries" yaml "--check")]
      (is (= 0 status))
      (is (str/starts-with? out "0 problems")))))

(defn- findings
  "`[[book file-relative-to-lib message-needle] ...]` of `--check`'s json report
  over `yaml`, sorted."
  [yaml]
  (let [{:keys [out]} (main-run "--libraries" yaml "--check" "--format" "json")
        root (str (.getParent (io/file yaml)) "/")]
    (vec (sort (for [d (get (json/read-str out) "diagnostics")]
                 [(get d "book") (some-> (get d "file") (str/replace root "")) (get d "type")
                  (second (re-find #"link to \"([^\"]*)\"" (get d "message" "")))])))))

(deftest sanitizer-findings-placement-test
  (testing "a clean library reports nothing, exit 0"
    (let [yaml (library-dir! {"c.hdoc" "---\ntitle: C\n---\n\nSee [y](https://ok.example).\n"})
          {:keys [status out]} (main-run "--libraries" yaml "--check" "lib")]
      (is (= 0 status))
      (is (str/starts-with? out "0 problems") out)))
  (testing "a link in a book with chapters is placed at the entry file, not a chapter"
    ;; the chapter's name is picked so the set of the book's files, which
    ;; the scan once took an arbitrary element of, lists it ahead of the entry
    (let [yaml (library-dir! {})
          root (.getParentFile (io/file yaml))
          canon #(canonical (str (io/file root %)))
          entry (canon "lib/b/book.hdoc")
          chapter (first (filter #(not= entry (first (into #{} [entry (canon (str "lib/b/ch/" % ".hdoc"))])))
                                 (map (partial str "c") (range 1000))))]
      (is (some? chapter))
      (write! root "lib/b/book.hdoc"
              (str "---\ntitle: B\n---\n\n!include ch/" chapter ".hdoc\n\nSee [x](javascript:1).\n"))
      (write! root (str "lib/b/ch/" chapter ".hdoc") "Clean chapter.\n")
      (is (= [["lib:b" "lib/b/book.hdoc" "unsafe-url" "javascript:1"]] (findings yaml)))))
  (testing "a URL attribute and an event-handler attribute are reported too"
    (let [yaml (library-dir! {"s.hdoc" "---\ntitle: S\n---\n\n[s]{onclick=\"x()\"} and [t]{href=\"javascript:1\"}.\n"})
          kinds (set (map #(nth % 2) (findings yaml)))]
      (is (contains? kinds "event-handler-attribute") (pr-str kinds))
      (is (contains? kinds "unsafe-url") (pr-str kinds))))
  (testing "both books of a duplicate id sharing one include keep their own findings"
    (let [yaml (library-dir! {"x.hdoc" "---\ntitle: X1\n---\n\n!include _s/h.hdoc\n\n[a](javascript:one(1))\n"
                              "x/book.hdoc" "---\ntitle: X2\n---\n\n!include ../_s/h.hdoc\n\n[b](javascript:two(1))\n"
                              "_s/h.hdoc" "Shared.\n"})
          fs (findings yaml)]
      (is (= #{"lib/x.hdoc" "lib/x/book.hdoc"}
             (set (for [[_ file kind] fs :when (= "unsafe-url" kind)] file)))
          (pr-str fs))
      (is (= #{"javascript:one(1)" "javascript:two(1)"}
             (set (for [[_ _ kind m] fs :when (= "unsafe-url" kind)] m)))
          (pr-str fs))
      (is (= 2 (count (filter #(= "unsafe-url" (nth % 2)) fs))) (pr-str fs))))
  (testing "a generated book's finding is placed at its script"
    (let [yaml (library-dir! {"g/shelf.clj"
                              (str "{:books (fn [_] [{:slug \"gb\" :title \"G\"}])\n"
                                   " :document (fn [_ _] {:meta {:title [{:t :str :text \"T\"}]}\n"
                                   "  :blocks [{:t :para :inlines [{:t :link :target \"javascript:1\" :attr {:classes [] :props {}} :inlines [{:t :str :text \"x\"}]}]}]})}\n")}
                             "  scripts: true\n")]
      (is (= [["lib:g/gb" "lib/g/shelf.clj" "unsafe-url" "javascript:1"]]
             (filterv #(= "unsafe-url" (nth % 2)) (findings yaml)))))))

;; --- TASK-307: drops after quote and book-link expansion ---------------------------

(defn- two-libraries!
  "A temp folder holding the libraries `src` and `host` (`src-books`/`host-books`:
  relative path -> source) and a `libraries.yaml` naming both, `src-extra` lines
  added to src's entry. Returns the yaml's path."
  [src-books host-books src-extra]
  (let [dir (temp-dir)]
    (doseq [[path source] src-books] (write! dir (str "src/" path) source))
    (doseq [[path source] host-books] (write! dir (str "host/" path) source))
    (.mkdirs (io/file dir "src"))
    (.mkdirs (io/file dir "host"))
    (str (write! dir "libraries.yaml"
                 (str "- name: src\n  type: filesystem\n  path: src\n" src-extra
                      "- name: host\n  type: filesystem\n  path: host\n")))))

(defn- drops
  "`[[book type link-or-kind] ...]`, sorted, of `--check`'s unsafe-url findings
  over `yaml` (scoped to `libs`, none: every library)."
  [yaml & libs]
  (let [{:keys [out]} (apply main-run "--libraries" yaml "--check" "--format" "json" libs)]
    (vec (sort (for [d (get (json/read-str out) "diagnostics")
                     :when (= "unsafe-url" (get d "type"))]
                 [(get d "book") (second (re-find #"Dropped the ([^\"]*?)(?: to)? \"" (get d "message")))
                  (second (re-find #"\"([^\"]*)\"" (get d "message")))])))))

(def ^:private src-book "---\ntitle: S\n---\n\nSee [x](javascript:1) and [y](https://ok.example).\n")

(deftest quoted-drops-are-reported-at-the-quoting-book-test
  (let [host-book "---\ntitle: H\n---\n\nIntro.\n\n!quote src:s\n\n!quote src:s\n"
        yaml (two-libraries! {"s.hdoc" src-book} {"h.hdoc" host-book} "")]
    (testing "source library outside the scope: reported at the quoting book, once for two quotes"
      (is (= [["host:h" "link" "javascript:1"]] (drops yaml "host"))))
    (testing "both in scope: once, at the book that holds the link"
      (is (= [["src:s" "link" "javascript:1"]] (drops yaml))))
    (testing "the source scoped alone"
      (is (= [["src:s" "link" "javascript:1"]] (drops yaml "src"))))
    (testing "a build of the quoting book drops the same link"
      (let [{:keys [err]} (main-run "--libraries" yaml "--output" (str (temp-dir) "/h.html") "host:h")]
        (is (str/includes? err "javascript:1") err))))
  (testing "two quoting books each report it, a clean quote reports nothing"
    (let [yaml (two-libraries! {"s.hdoc" src-book "ok.hdoc" "---\ntitle: O\n---\n\nFine.\n"}
                               {"h1.hdoc" "---\ntitle: H1\n---\n\n!quote src:s\n"
                                "h2.hdoc" "---\ntitle: H2\n---\n\n!quote src:s\n"
                                "h3.hdoc" "---\ntitle: H3\n---\n\n!quote src:ok\n"}
                               "")]
      (is (= [["host:h1" "link" "javascript:1"] ["host:h2" "link" "javascript:1"]]
             (drops yaml "host")))))
  (testing "a quote in an included chapter is found as well, placed at the entry file"
    (let [yaml (two-libraries! {"s.hdoc" src-book}
                               {"b/book.hdoc" "---\ntitle: B\n---\n\n!include ch/one.hdoc\n"
                                "b/ch/one.hdoc" "# One\n\n!quote src:s\n"}
                               "")]
      (is (= [["host:b" "link" "javascript:1"]] (drops yaml "host")))))
  (testing "a link two quotes deep, with the middle book in scope: reported at the middle book's quoting book"
    (let [yaml (two-libraries! {"s.hdoc" src-book}
                               {"mid.hdoc" "---\ntitle: M\n---\n\n!quote src:s\n"
                                "top.hdoc" "---\ntitle: T\n---\n\n!quote host:mid\n"}
                               "")]
      (is (= [["host:mid" "link" "javascript:1"] ["host:top" "link" "javascript:1"]]
             (drops yaml "host"))))))

(deftest template-drops-are-reported-test
  (let [yaml (two-libraries! {"s.hdoc" "---\ntitle: S\n---\n\nClean.\n"}
                             {"h.hdoc" (str "---\ntitle: H\n---\n\nSee @{src:s} and @{src:s} again.\n\n"
                                            "!quote src:s\n\n!quote src:s\n")}
                             "  url: \"javascript:alert('{slug}')\"\n")
        found (drops yaml "host")]
    (testing "the book link and the quote's source link, each once"
      (is (= [["host:h" "book link" "javascript:alert('s')"]
              ["host:h" "book quote's source link" "javascript:alert('s')"]]
             found)))
    (testing "the build drops the same two"
      (let [{:keys [err]} (main-run "--libraries" yaml "--output" (str (temp-dir) "/h.html") "host:h")]
        (is (str/includes? err "Dropped the book link") err)
        (is (str/includes? err "Dropped the book quote's source link") err)))
    (testing "scoped to the library with the template: nothing quotes or links there"
      (is (= [] (drops yaml "src")))))
  (testing "a safe template reports nothing"
    (let [yaml (two-libraries! {"s.hdoc" "---\ntitle: S\n---\n\nClean.\n"}
                               {"h.hdoc" "---\ntitle: H\n---\n\n@{src:s}\n\n!quote src:s\n"}
                               "  url: \"https://e.org/{slug}\"\n")]
      (is (= [] (drops yaml))))))

(deftest ^:timing dense-quote-drops-timing-test
  (testing "TASK-307: a link dropped at the bottom of a densely quoting library -- 8 layers
            of 6 books, 6^7 paths down -- is found without expanding the paths, and is
            reported once, at the book that holds it"
    (let [layers 8 width 6
          yaml (library-dir! (into {} (for [l (range layers) w (range width)]
                                        [(format "l%db%d.hdoc" l w)
                                         (str "---\ntitle: T\n---\n\n"
                                              (if (< l (dec layers))
                                                (apply str (for [v (range width)] (format "!quote lib:l%db%d\n\n" (inc l) v)))
                                                (str "See [x](javascript:" w ").\n")))])))
          t (System/nanoTime)
          found (drops yaml)
          secs (/ (- (System/nanoTime) t) 1e9)]
      (is (< secs 10.0) (str "took " secs " s"))
      (is (= (mapv (fn [w] [(str "lib:l7b" w) "link" (str "javascript:" w)]) (range width)) found))))
  (testing "the same shape with the source outside the check: every quoting book reports"
    (let [yaml (two-libraries! {"s.hdoc" src-book}
                               (into {} (for [l (range 6) w (range 5)]
                                          [(format "l%db%d.hdoc" l w)
                                           (apply str "---\ntitle: T\n---\n\n"
                                                  (if (< l 5)
                                                    (for [v (range 5)] (format "!quote host:l%db%d\n\n" (inc l) v))
                                                    ["!quote src:s\n"]))]))
                               "")
          found (drops yaml "host")]
      (is (= 30 (count found)))
      (is (every? #(= ["link" "javascript:1"] (rest %)) found)))))

(deftest nested-quote-template-drop-is-reported-once-test
  (let [yaml (let [dir (temp-dir)]
               (write! dir "d/x.hdoc" "---\ntitle: X\n---\n\nClean.\n")
               (write! dir "c/c.hdoc" "---\ntitle: C\n---\n\n!quote d:x\n")
               (write! dir "h/h.hdoc" "---\ntitle: H\n---\n\n!quote c:c\n")
               (str (write! dir "libraries.yaml"
                            (str "- name: d\n  type: filesystem\n  path: d\n  url: \"javascript:alert('{slug}')\"\n"
                                 "- name: c\n  type: filesystem\n  path: c\n"
                                 "- name: h\n  type: filesystem\n  path: h\n"))))
        dropped "javascript:alert('x')"]
    (testing "both books in the check: reported at the book that wrote the !quote, once"
      (is (= [["c:c" "book quote's source link" dropped]] (drops yaml))))
    (testing "the writing book outside the check: the quoting book reports it"
      (is (= [["h:h" "book quote's source link" dropped]] (drops yaml "h"))))))

(deftest quote-walk-stops-where-the-build-stops-test
  (testing "depth: a link at the ninth level down is not in the build, nor in the report"
    (let [chain (fn [n]
                  (into {"h.hdoc" "---\ntitle: H\n---\n\n!quote host:s0\n"}
                        (for [i (range n)]
                          [(str "s" i ".hdoc")
                           (str "---\ntitle: S\n---\n\n"
                                (if (< i (dec n)) (str "!quote host:s" (inc i) "\n") "!quote src:s\n"))])))
          books (fn [n] (drops (two-libraries! {"s.hdoc" src-book} (chain n) "") "host"))]
      ;; src:s is at depth n+1 from h, n from s0
      (is (= (set (cons "host:h" (map #(str "host:s" %) (range 7)))) (set (map first (books 7)))))
      (is (= (set (map #(str "host:s" %) (range 8))) (set (map first (books 8)))))))
  (testing "cycle: a quote that comes back to a book above it brings nothing"
    (let [yaml (two-libraries! {"x.hdoc" (str "---\ntitle: X\n---\n\n# P1 {#sec:p1}\n\n!quote src:b\n\n"
                                              "# P2 {#sec:p2}\n\nSee [x](javascript:1).\n")
                                "b.hdoc" "---\ntitle: B\n---\n\n!quote src:x#sec:p2\n"}
                               {"h.hdoc" "---\ntitle: H\n---\n\n!quote src:x#sec:p1\n"}
                               "")]
      (is (= [] (drops yaml "host")))
      (is (= #{"src:x"} (set (map first (drops yaml "src"))))))))

(deftest sanitizer-failure-fails-closed-test
  (let [yaml (library-dir! {"b.hdoc" "---\ntitle: B\n---\n\nSee [x](javascript:1).\n"})]
    (with-redefs [sanitize/sanitize-document (fn [_] (throw (RuntimeException. "boom")))]
      (let [{:keys [status out]} (main-run "--libraries" yaml "--check")]
        (is (= 1 status))
        (is (re-find #"lib/b\.hdoc: error: \[sanitize-failed\] .*boom" out) out)))))

;; --- TASK-327: named books (doc-13 section 3) -------------------------------------

(defn- named-report
  "`check-libraries`' report over `yaml` for the `--check` arguments `names`."
  [yaml names]
  (:report (cli/check-libraries (merge overrides {:libraries yaml}) names)))

(def ^:private sanitizer-types
  "The item types the sanitizer findings give (TASK-288)."
  #{:unsafe-url :event-handler-attribute :sanitize-failed})

(defn- sanitizer-item? [it] (contains? sanitizer-types (:type it)))

(defn- named-expected
  "The items of `full` -- a report of the libraries holding what was named --
  that a check of the libraries `libs` and the books `books` (canonical id
  text) reports, sanitizer findings aside (`named-drops-expected`): every
  item of those libraries, and a named book's own, except a quote nested
  too deep at the book's own quote, which only a build of a book quoting it
  meets."
  [full libs books]
  (filterv (fn [{:keys [library book] :as it}]
             (and (not (sanitizer-item? it))
                  (or (contains? (set libs) library)
                      (and (contains? (set books) book) (not= :quote-too-deep (:type it))))))
           (:diagnostics full)))

(defn- drop-keys
  "`[type message]` of the sanitizer items of `items` the book `book` holds,
  in order: what a drop is compared by between two collections whose files
  are named differently."
  [items book]
  (into [] (comp (filter #(and (sanitizer-item? %) (= book (:book %)))) (map (juxt :type :message))) items))

(deftest named-book-fixture-parity-test
  (testing "every book of the fixture libraries, checked by its ID, reports exactly
            what a check of its library reports for it, and counts one book. A drop
            is reported at the named book when the book it is written in is not
            checked too, so the library's check has no drop the book's lacks"
    (let [dir (fixture-directory)
          ids (for [lib-name (library/library-names dir)
                    :let [lib (library/find-library dir lib-name)]
                    :when (satisfies? library/Library lib)
                    {:keys [id]} (library/list-books lib)
                    :when (satisfies? library/Book (library/find-book lib (:slug id)))]
                id)
          full (memoize #(named-report fixtures-yaml [%]))
          compared (atom [])]
      (is (<= 20 (count ids)))
      (doseq [id ids
              :let [book (book-ref/format-book-id id)
                    expected (named-expected (full (:library id)) [] [book])
                    report (named-report fixtures-yaml [book])]]
        (swap! compared into expected)
        (is (= expected (filterv (complement sanitizer-item?) (:diagnostics report))) book)
        (is (set/subset? (set (drop-keys (:diagnostics (full (:library id))) book))
                         (set (drop-keys (:diagnostics report) book)))
            book)
        (is (= 1 (get-in report [:summary :books])) book)
        (is (= (count (:diagnostics report)) (get-in report [:summary :problems])) book))
      (testing "not vacuous: items of many kinds were compared, quote cycles among them"
        (is (<= 12 (count @compared)) (pr-str (frequencies (map :type @compared))))
        (is (= 2 (count (filter #(= :quote-cycle (:type %)) @compared)))))))
  (testing "a quote nested too deep is reported at the quote, but only by the check of
            the library: a build of check:deep-09 meets no chain too deep, the build of
            check:deep-01, which quotes it, does"
    (let [full (named-report fixtures-yaml ["check"])
          one (named-report fixtures-yaml ["check:deep-09"])]
      (is (some #(and (= :quote-too-deep (:type %)) (= "check:quotes/deep-09" (:book %))) (:diagnostics full)))
      (is (= [] (:diagnostics one))))))

(defn- fuzz-slot-text
  "A fuzzed slot as text: nil, `[:quote target passage]`, `[:link n]` or
  `[:both target passage n]`, each quote's target written as `(ref-of
  target)`."
  [ref-of [kind & args]]
  (let [quote-text (fn [target passage] (str "!quote " (ref-of target) passage))
        link-text (fn [n] (str "See [x](javascript:" n ")."))]
    (case kind
      nil nil
      :quote (apply quote-text args)
      :link (link-text (first args))
      :both (let [[target passage n] args] (str (quote-text target passage) "\n\n" (link-text n))))))

(defn- two-library-fuzz-yaml!
  "A temp library directory with libraries `a` and `b`, each book in
  `books` (`{[lib name] [slot slot slot]}`) a `fuzz-book` of its slots
  (`fuzz-slot-text`), and `b`'s url template `b-url` (nil: none)."
  [books b-url]
  (let [dir (temp-dir)]
    (doseq [[[lib b] slots] books]
      (write! dir (str lib "/" b ".hdoc") (fuzz-book (map #(fuzz-slot-text identity %) slots))))
    (str (write! dir "libraries.yaml"
                 (str "- name: a\n  type: filesystem\n  path: a\n"
                      "- name: b\n  type: filesystem\n  path: b\n"
                      (when b-url (str "  url: \"" b-url "\"\n")))))))

(defn- book-per-library-fuzz-yaml!
  "The collection `two-library-fuzz-yaml!` writes, with every book in a
  library of its own, named as the book (`a:a0` becomes `a0:a0`; `a:nowhere`
  `a0:nowhere`), the libraries of `b`'s books with its url template. A check
  of one of these libraries is a check that covers that book and no other,
  by the code a library check has always run."
  [books b-url]
  (let [dir (temp-dir)
        ref-of (fn [target]
                 (let [b (second (str/split target #":"))]
                   (if (= "nowhere" b) "a0:nowhere" (str b ":" b))))]
    (doseq [[[_ b] slots] books]
      (write! dir (str b "/" b ".hdoc") (fuzz-book (map #(fuzz-slot-text ref-of %) slots))))
    (str (write! dir "libraries.yaml"
                 (apply str (for [[[lib b] _] (sort books)]
                              (str "- name: " b "\n  type: filesystem\n  path: " b "\n"
                                   (when (and b-url (= "b" lib)) (str "  url: \"" b-url "\"\n")))))))))

(deftest named-book-parity-fuzz-test
  (testing "seeded (327): random two-library collections, quotes across both (resolved,
            unresolved, of unknown books) and links the sanitizer drops (a library url
            template too, half the time).
            - Each book of a, checked by its ID, reports what a check of a reports for
              it, sanitizer findings aside, and the drops a check covering that book
              alone reports -- the same collection with one library per book, checked
              by that book's library, by the library check's own code.
            - Library b beside a book of a nothing quotes: b's items are a check of b's.
            - Library b beside any book of a: b's items, drops aside, lie between a
              check of b's and a check of a and b's; a drop a check of b reports at a
              book of b is still reported, at that book or at the named book that
              holds it."
    (let [rng (java.util.Random. 327)
          pick (fn [v] (nth v (.nextInt rng (count v))))
          names {"a" ["a0" "a1" "a2" "a3" "lone"] "b" ["b0" "b1" "b2"]}
          ;; `lone` is never quoted and quotes nothing
          targets (into ["a:nowhere"] (for [[lib bs] names b bs :when (not= "lone" b)] (str lib ":" b)))
          slot (fn []
                 (case (int (.nextInt rng 6))
                   (0 1) nil
                   (2 3) [:quote (pick targets) (pick fuzz-passages)]
                   4 [:link (.nextInt rng 3)]
                   5 [:both (pick targets) (pick fuzz-passages) (.nextInt rng 3)]))
          lone-slot (fn [] (when (.nextBoolean rng) [:link (.nextInt rng 3)]))
          hits (atom {})
          count! (fn [k n] (swap! hits update k (fnil + 0) n))
          non-drops (fn [items] (filterv (complement sanitizer-item?) items))
          b-items (fn [report] (filterv #(= "b" (:library %)) (:diagnostics report)))
          drop-triples (fn [items] (set (for [it items :when (sanitizer-item? it)] [(:book it) (:type it) (:message it)])))]
      (dotimes [_ 30]
        (let [books (into {} (for [[lib bs] names b bs]
                               [[lib b] (vec (repeatedly 3 (if (= "lone" b) lone-slot slot)))]))
              b-url (when (.nextBoolean rng) "javascript:alert('{slug}')")
              yaml (two-library-fuzz-yaml! books b-url)
              apart (book-per-library-fuzz-yaml! books b-url)
              full-a (named-report yaml ["a"])
              full-b (named-report yaml ["b"])
              full-ab (named-report yaml ["a" "b"])]
          (doseq [b (names "a")
                  :let [book (str "a:" b)
                        report (named-report yaml [book])
                        expected (named-expected full-a [] [book])
                        own-drops (drop-keys (:diagnostics report) book)]]
            (swap! hits #(merge-with + % (frequencies (map :type expected))))
            (count! :drop (count own-drops))
            ;; drops the library's check reports where they are written instead
            (count! :moved (count (remove (set (drop-keys (:diagnostics full-a) book)) own-drops)))
            (is (= expected (non-drops (:diagnostics report))) (pr-str book books))
            (is (= (drop-keys (:diagnostics (named-report apart [b])) (str b ":" b)) own-drops)
                (pr-str book books))
            (is (= 1 (get-in report [:summary :books]))))
          (testing "beside a book nothing quotes: b's drops are a check of b's; its other
                    items too, but for the quote cycles through books of a that b's
                    quotes reach, which a check of a and b finds as well"
            (let [report (named-report yaml ["b" "a:lone"])
                  mixed (b-items report)
                  extra (set/difference (set (non-drops mixed)) (set (non-drops (:diagnostics full-b))))]
              (count! :lone-extra (count extra))
              (is (= (filterv sanitizer-item? (:diagnostics full-b)) (filterv sanitizer-item? mixed)) (pr-str books))
              (is (set/subset? (set (non-drops (:diagnostics full-b))) (set (non-drops mixed))) (pr-str books))
              (is (every? #(= :quote-cycle (:type %)) extra) (pr-str extra))
              (is (set/subset? extra (set (b-items full-ab))) (pr-str extra))
              (is (= (inc (count (names "b"))) (get-in report [:summary :books])))))
          (let [book (str "a:" (pick (names "a")))
                report (named-report yaml ["b" book])
                mixed (b-items report)
                alone (set (non-drops (:diagnostics full-b)))
                both (set (non-drops (b-items full-ab)))
                missing (set/difference (drop-triples (:diagnostics full-b)) (drop-triples mixed))]
            (count! :mixed-moved (count missing))
            (is (set/subset? alone (set (non-drops mixed))) (pr-str book books))
            (is (set/subset? (set (non-drops mixed)) both) (pr-str book books))
            (is (set/subset? (drop-triples mixed) (drop-triples (:diagnostics full-b))) (pr-str book books))
            (is (every? (set (drop-keys (:diagnostics report) book)) (map (comp vec rest) missing))
                (pr-str book books)))))
      (testing "not vacuous"
        (is (<= 20 (get @hits :quote-cycle 0)) (pr-str @hits))
        (is (<= 100 (get @hits :drop 0)) (pr-str @hits))
        (is (<= 20 (get @hits :moved 0)) (pr-str @hits))
        (is (<= 3 (get @hits :mixed-moved 0)) (pr-str @hits))
        (is (<= 3 (get @hits :lone-extra 0)) (pr-str @hits))
        (is (<= 20 (get @hits :unknown-label 0)) (pr-str @hits))))))

(deftest named-book-drop-placement-test
  (testing "a link dropped from a passage of a book the check does not cover is reported
            at the named book that quotes it, as building that book reports it"
    (let [yaml (library-dir! {"h.hdoc" "---\ntitle: H\n---\n\n!quote lib:s\n"
                              "s.hdoc" "---\ntitle: S\n---\n\nSee [x](javascript:1).\n"})
          {:keys [status out]} (main-run "--libraries" yaml "--check" "lib:h")
          {:keys [err]} (main-run "--libraries" yaml "--output" (str (temp-dir) "/h.html") "lib:h")]
      (is (= 1 status) out)
      (is (re-find #"lib/h\.hdoc: warning: \[unsafe-url\] Dropped the link to \"javascript:1\"" out) out)
      (is (str/includes? err "javascript:1") err)
      (testing "with the book it is written in checked too, it is reported there, once"
        (let [{:keys [out]} (main-run "--libraries" yaml "--check" "lib:h" "lib:s")]
          (is (re-find #"lib/s\.hdoc: warning: \[unsafe-url\] " out) out)
          (is (not (re-find #"lib/h\.hdoc: warning: \[unsafe-url\] " out)) out)))))
  (testing "naming a book of another library does not change what a named library reports:
            m:p quotes l:y, which drops a link; l:x is clean and unrelated"
    (let [dir (temp-dir)
          _ (write! dir "m/p.hdoc" "---\ntitle: P\n---\n\n!quote l:y\n")
          _ (write! dir "l/y.hdoc" "---\ntitle: Y\n---\n\nSee [x](javascript:1).\n")
          _ (write! dir "l/x.hdoc" "---\ntitle: X\n---\n\nClean.\n")
          yaml (str (write! dir "libraries.yaml" (str "- name: m\n  type: filesystem\n  path: m\n"
                                                      "- name: l\n  type: filesystem\n  path: l\n")))
          alone (main-run "--libraries" yaml "--check" "m")
          beside (main-run "--libraries" yaml "--check" "m" "l:x")
          m-lines (fn [out] (filterv #(str/includes? % "/m/") (str/split-lines out)))]
      (is (= 1 (:status alone)) (:out alone))
      (is (= 1 (:status beside)) (:out beside))
      (is (= 1 (count (m-lines (:out alone)))) (:out alone))
      (is (= (m-lines (:out alone)) (m-lines (:out beside))))
      (is (str/ends-with? (:out beside) "in 2 books\n") (:out beside)))))

(deftest named-book-scans-only-what-it-quotes-test
  (testing "one book of a large library: the scan covers the book and what it quotes,
            transitively, not the books it links to, refers to it, or are unrelated"
    (let [fillers (into {} (for [i (range 60)]
                             [(str "f" i ".hdoc")
                              (str "---\ntitle: F\n---\n\n!quote lib:f" (mod (inc i) 60) "#sec:nope\n")]))
          yaml (library-dir! (merge fillers
                                    {"x.hdoc" "---\ntitle: X\n---\n\n!quote lib:y\n\nSee @{lib:w#sec:gone}.\n"
                                     "y.hdoc" "---\ntitle: Y\n---\n\n# P {#sec:p}\n\n!quote lib:z#sec:p\n"
                                     "z.hdoc" "---\ntitle: Z\n---\n\n# P {#sec:p}\n\nSee @sec:missing.\n"
                                     "w.hdoc" "---\ntitle: W\n---\n\nSee @sec:also-missing.\n"
                                     "r.hdoc" "---\ntitle: R\n---\n\n!quote lib:x#sec:nope\n"}))
          scanned-ids (atom [])
          run (fn [names]
                (reset! scanned-ids [])
                (:report (cli/check-libraries (merge overrides {:libraries yaml
                                                                :haselnuss.cli/on-scan-book #(swap! scanned-ids conj %)})
                                              names)))
          one (run ["lib:x"])
          one-scanned @scanned-ids
          full (run ["lib"])]
      (is (= #{"lib:x" "lib:y" "lib:z"} (set (map book-ref/format-book-id one-scanned))))
      (is (= 3 (count one-scanned)) "each once")
      (is (= 65 (count @scanned-ids)) "a check of the library scans every book")
      (testing "the book's own problem only: not z's or w's (it only reads them), not r's
                (r refers to it)"
        (is (= [[:unknown-label "lib:w#sec:gone"]] (map (juxt :type :ref) (:diagnostics one))))
        (is (= (named-expected full [] ["lib:x"]) (:diagnostics one)))
        (is (some #(= "lib:r" (:book %)) (:diagnostics full)))
        (is (some #(= "lib:z" (:book %)) (:diagnostics full)))))))

(deftest named-book-cli-test
  (let [yaml (library-dir! {"a.hdoc" "---\ntitle: A\n---\n\nSee @{lib:b#sec:nope}.\n"
                            "s/b.hdoc" "---\ntitle: B\n---\n\nFine.\n"
                            "c.hdoc" "---\ntitle: C\n---\n\nSee @sec:nope.\n"
                            "x/d.hdoc" "D.\n"
                            "y/d.hdoc" "D.\n"})]
    (testing "a book with a warning: its items, the summary counting one book, exit 1"
      (let [{:keys [status out err]} (main-run "--libraries" yaml "--check" "lib:a")
            [heads summary] (line-heads out)]
        (is (= 1 status))
        (is (= "" err))
        (is (= [1 "1 problem (0 errors, 1 warning, 0 info) in 1 book"] [(count heads) summary]) out)
        (is (re-find #"lib/a\.hdoc:5:5: warning: \[unknown-label\] lib:b#sec:nope: " out) out)))
    (testing "a clean book, short or canonical: exit 0"
      (doseq [id ["lib:b" "lib:s/b"]]
        (let [{:keys [status out]} (main-run "--libraries" yaml "--check" id)]
          (is (= 0 status) id)
          (is (= "0 problems (0 errors, 0 warnings, 0 info) in 1 book\n" out) id))))
    (testing "json: the same envelope, counting what was named"
      (let [{:keys [status out]} (main-run "--libraries" yaml "--check" "lib:a" "lib:c" "--format" "json")
            {:strs [version diagnostics summary]} (json/read-str out)]
        (is (= 1 status))
        (is (= 1 version))
        (is (= {"problems" 2 "errors" 0 "warnings" 2 "info" 0 "books" 2} summary))
        (is (= #{"lib:a" "lib:c"} (set (map #(get % "book") diagnostics))))))
    (testing "the same book twice, or by itself and through its library, is checked once"
      (is (str/ends-with? (:out (main-run "--libraries" yaml "--check" "lib:a" "lib:a")) "in 1 book\n"))
      (is (str/ends-with? (:out (main-run "--libraries" yaml "--check" "lib:a" "lib:s/b" "lib:b")) "in 2 books\n"))
      (is (= (:out (main-run "--libraries" yaml "--check" "lib"))
             (:out (main-run "--libraries" yaml "--check" "lib" "lib:a")))))
    (testing "a stale shelf path checks the book, with an info on stderr"
      (let [{:keys [status out err]} (main-run "--libraries" yaml "--check" "lib:old/b")]
        (is (= 0 status))
        (is (str/ends-with? out "in 1 book\n"))
        (is (= "haselnuss: info: --check \"lib:old/b\": the book is now at lib:s/b; checking it there\n" err))))
    (testing "exit 2, nothing on stdout, nothing checked"
      (doseq [[args message]
              [[["lib:nope"] "--check lib:nope: library \"lib\" has no book \"nope\""]
               [["zzz:a"] "--check zzz:a: the library directory has no library \"zzz\" (it has: lib)"]
               [["lib:d"] "--check lib:d: "]
               [["lib" "lib:a#sec:x"] "--check takes whole books, not passages; drop #sec:x from \"lib:a#sec:x\""]
               [["lib:A"] "--check takes library names and book IDs"]
               [["zzz" "lib:a"] "unknown library \"zzz\"; known: lib"]
               [["--doc" "lib:a"] "--doc cannot be combined with --check"]]]
        (let [scan-count (atom 0)
              err (java.io.StringWriter.)
              out (java.io.StringWriter.)
              status (binding [*err* err *out* out]
                       (cli/main* (into ["--libraries" yaml "--check"] args)
                                  (assoc overrides :haselnuss.cli/on-scan-book (fn [_] (swap! scan-count inc)))))]
          (is (= 2 status) (pr-str args (str err)))
          (is (= "" (str out)) (pr-str args))
          (is (str/includes? (str err) message) (pr-str args (str err)))
          (is (= 0 @scan-count) (pr-str args)))))))

;; --- AC #5: --help ----------------------------------------------------------------

(deftest help-test
  (let [{:keys [out]} (main-run "--help")]
    (doseq [needle ["haselnuss [--libraries PATH] --check [LIBRARY|ID ...] [--format json]"
                    "--check [LIBRARY|ID ...]" "--format FORMAT" "\"version\""
                    "A book ID (library:[shelf/...]slug" "--backlinks lists"
                    "takes no input" "unknown library" "info" "unsafe-url"]]
      (is (str/includes? out needle) needle))))
