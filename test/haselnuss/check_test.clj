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
            [haselnuss.library.references :as references])
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
                "haselnuss: error: --check takes no input file, only library names; got \"doc.hdoc\""]
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

(deftest dense-quote-parity-fuzz-test
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

(deftest dense-quote-timing-test
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

(deftest dense-cyclic-timing-test
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

(deftest duplicate-slug-parity-fuzz-test
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

;; --- AC #5: --help ----------------------------------------------------------------

(deftest help-test
  (let [{:keys [out]} (main-run "--help")]
    (doseq [needle ["haselnuss [--libraries PATH] --check [LIBRARY ...] [--format json]"
                    "--check [LIBRARY ...]" "--format FORMAT" "\"version\""
                    "takes no input" "unknown library" "info"]]
      (is (str/includes? out needle) needle))))
