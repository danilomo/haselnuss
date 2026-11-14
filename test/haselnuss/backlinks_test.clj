(ns haselnuss.backlinks-test
  "`--backlinks` through the real CLI (TASK-243, doc-11 \"--backlinks\"):
  the catechism example's quotes into its Bible library (doc-11's success
  criterion), grouping by passage, the passage filter, cross-library and
  dangling edges over the fixture libraries, self-references, the JSON
  report and the exit codes."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.cli :as cli]
            [haselnuss.library.graph.backlinks :as backlinks])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

;; --- helpers -----------------------------------------------------------------

(def ^:private fixtures-yaml "test/fixtures/libraries/libraries.yaml")

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
  (.toFile (Files/createTempDirectory "haselnuss-backlinks-test" (make-array FileAttribute 0))))

(defn- write!
  ^File [^File dir rel text]
  (let [f (io/file dir rel)]
    (io/make-parents f)
    (spit f text)
    f))

(def ^:private broken-library-warning
  "What every run over the fixture directory prints on stderr: its library
  `broken` failed to load, so whatever it holds is missing from the list."
  (str "haselnuss: warning: test/fixtures/libraries/libraries.yaml:24: library \"broken\" has type"
       " \"nope\", which is not a registered provider (registered: filesystem, memory)\n"))

;; --- AC #1: the catechism's quotes into its Bible --------------------------------

(def ^:private example-file (io/file "examples/catecismo-heidelberg.hdoc"))

(def ^:private catechism-yaml
  "A library directory of the example's Bible library and a library `cat`
  holding a copy of the example itself -- which is no library book in the
  repository -- so the catechism's quotes are backlinks."
  (delay
    (let [dir (temp-dir)
          copy (io/file dir "cat" "catecismo-heidelberg.hdoc")]
      (io/make-parents copy)
      (io/copy example-file copy)
      (str (write! dir "libraries.yaml"
                   (str "- name: bible\n  type: filesystem\n  path: "
                        (.getCanonicalPath (io/file "examples/libraries/bible"))
                        "\n  scripts: true\n"
                        "- name: cat\n  type: filesystem\n  path: cat\n"))))))

(defn- source-quotes
  "`[[written line] ...]` of every `!quote` of `bible:<slug>` in the
  example, read straight from its source lines -- independently of the
  parser and the scan."
  [slug]
  (let [re (re-pattern (str "^\\s*!quote\\s+(bible:" slug "(?:#\\S+)?)\\s*$"))]
    (vec (keep-indexed (fn [i line] (when-let [[_ written] (re-find re line)] [written (inc i)]))
                       (str/split-lines (slurp example-file))))))

(deftest catechism-quotes-test
  (let [expected (source-quotes "romanos")
        {:keys [status out err]} (main-run "--libraries" @catechism-yaml "--backlinks" "bible:romanos"
                                           "--format" "json")
        {:strs [version book edges self summary]} (json/read-str out)]
    (is (= 0 status) err)
    (is (= "" err))
    (is (< 100 (count expected)) "the source count is not vacuous")
    (is (= 1 version))
    (is (= "bible:blivre/romanos" book) "the short form is resolved to the book's canonical id")
    (testing "every !quote of the book, at its line, and nothing else"
      (is (= (sort expected)
             (sort (map (fn [e] [(get-in e ["to" "written"]) (get-in e ["position" "line"])]) edges)))))
    (is (every? #(= ["cat:catecismo-heidelberg" "quote" "resolved" "bible:blivre/romanos"]
                    [(get-in % ["from" "id"]) (get % "kind") (get % "status") (get-in % ["to" "id"])])
                edges))
    (is (= [] self))
    (is (= {"references" (count expected) "books" 1 "self" 0} summary))
    (testing "the text lists the same quotes, grouped by passage in natural order"
      (let [text (:out (main-run "--libraries" @catechism-yaml "--backlinks" "bible:romanos"))
            lines (str/split-lines text)
            headings (keep #(second (re-find #"^  #(\S+)$" %)) lines)
            passages (fn [ws] (map #(second (str/split % #"#" 2)) ws))]
        (is (= "bible:blivre/romanos" (first lines)))
        (is (= (distinct (sort backlinks/passage-order (passages (map first expected)))) headings))
        (is (< -1 (.indexOf ^java.util.List (vec headings) "3:10") (.indexOf ^java.util.List (vec headings) "14:8"))
            "verses in numeric, not string, order")
        (is (= (count expected) (count (filter #(str/starts-with? % "    cat:catecismo-heidelberg   quote   ") lines))))
        (is (= (str (count expected) " references from 1 book") (last lines)))))
    (testing "another book: the whole set of its quotes, too"
      (let [expected (source-quotes "tito")
            {:strs [edges]} (json/read-str (:out (main-run "--libraries" @catechism-yaml "--backlinks" "bible:tito"
                                                           "--format" "json")))]
        (is (seq expected))
        (is (= (sort expected) (sort (map (fn [e] [(get-in e ["to" "written"]) (get-in e ["position" "line"])]) edges))))))
    (testing "a passage: exactly the quotes of that verse"
      (let [{:strs [edges passage]} (json/read-str (:out (main-run "--libraries" @catechism-yaml "--backlinks"
                                                                   "bible:blivre/romanos#3:10" "--format" "json")))]
        (is (= "3:10" passage))
        (is (= (filter #(= "bible:romanos#3:10" (first %)) expected)
               (map (fn [e] [(get-in e ["to" "written"]) (get-in e ["position" "line"])]) edges)))
        (is (= 2 (count edges)))))))

;; --- AC #2: grouping, the passage filter, cross-library and dangling edges -------

(deftest fixture-libraries-test
  (testing "cross-library and dangling edges, grouped by passage"
    (let [{:keys [status out err]} (main-run "--libraries" fixtures-yaml "--backlinks" "pass:nicene")]
      (is (= 0 status) err)
      (is (= broken-library-warning err))
      (is (= (str "pass:creeds/nicene\n"
                  "  #14:8\n"
                  "    check:links#sec:links            link    test/fixtures/libraries/check/links.hdoc:15"
                  "   dangling: locator-unsupported\n"
                  "  #sec:creed\n"
                  "    pass:creeds/quoting#sec:quotes   quote   test/fixtures/libraries/passages/creeds/quoting.hdoc:7\n"
                  "  #sec:nope\n"
                  "    check:links#sec:links            link    test/fixtures/libraries/check/links.hdoc:11"
                  "   dangling: unknown-label\n"
                  "3 references from 2 books\n")
             out))))
  (testing "a passage filters to exact matches"
    (doseq [[arg expected] [["pass:creeds/nicene#sec:creed" "pass:creeds/quoting#sec:quotes   quote"]
                            ["pass:nicene#sec:nope" "check:links#sec:links   link"]
                            ["pass:nicene# sec:nope " "check:links#sec:links   link"]]]
      (let [{:keys [status out]} (main-run "--libraries" fixtures-yaml "--backlinks" arg)
            lines (str/split-lines out)]
        (is (= 0 status) arg)
        (is (= 4 (count lines)) out)
        (is (str/starts-with? (nth lines 2) (str "    " expected)) out)
        (is (= "1 reference from 1 book" (last lines)) out))))
  (testing "a whole-book reference, through a stale shelf path, is a whole-book backlink"
    (let [{:keys [out]} (main-run "--libraries" fixtures-yaml "--backlinks" "reformed:heidelberg")]
      (is (= (str "reformed:confessions/reformed/heidelberg\n"
                  "  (whole book)\n"
                  "    check:links#sec:links   link    test/fixtures/libraries/check/links.hdoc:19\n"
                  "1 reference from 1 book\n")
             out))))
  (testing "an argument with a stale shelf: the book's references, with an info"
    (let [{:keys [status out err]} (main-run "--libraries" fixtures-yaml "--backlinks" "pass:old/nicene#sec:creed")]
      (is (= 0 status))
      (is (str/starts-with? out "pass:creeds/nicene#sec:creed\n"))
      (is (str/ends-with? out "1 reference from 1 book\n"))
      (is (str/includes? err "--backlinks \"pass:old/nicene#sec:creed\": the book is now at pass:creeds/nicene") err))))

(deftest self-references-test
  (let [dir (temp-dir)
        _ (write! dir "lib/a.hdoc" "---\ntitle: A\n---\n\n# A {#sec:a}\n\nSee @{lib:a#sec:a}.\n")
        _ (write! dir "lib/b.hdoc" "---\ntitle: B\n---\n\nSee @{lib:a} and @{lib:a#sec:a}.\n\n!quote lib:a#sec:a\n")
        yaml (str (write! dir "libraries.yaml" "- name: lib\n  type: filesystem\n  path: lib\n"))
        {:keys [status out err]} (main-run "--libraries" yaml "--backlinks" "lib:a")]
    (is (= 0 status) err)
    (is (= (str "lib:a\n"
                "  (whole book)\n"
                "    lib:b         link    lib/b.hdoc:5\n"
                "  #sec:a\n"
                "    lib:b         link    lib/b.hdoc:5\n"
                "    lib:b         quote   lib/b.hdoc:7\n"
                "self-references\n"
                "  #sec:a\n"
                "    lib:a#sec:a   link    lib/a.hdoc:7\n"
                "3 references from 1 book; 1 self-reference\n")
           ;; the temp folder lies outside the working directory: paths stay absolute
           (str/replace out (str (.getCanonicalPath dir) "/") "")))))

;; --- AC #3/#4: JSON, exit codes ---------------------------------------------------

(deftest json-format-test
  (let [{:keys [status out]} (main-run "--libraries" fixtures-yaml "--backlinks" "pass:nicene" "--format" "json")
        {:strs [version book passage edges self summary] :as report} (json/read-str out)]
    (is (= 0 status))
    (is (= ["version" "book" "passage" "edges" "self" "summary"] (keys report)))
    (is (= [1 "pass:creeds/nicene" nil [] {"references" 3 "books" 2 "self" 0}] [version book passage self summary]))
    (is (= [{"from" {"id" "check:links" "passage" "sec:links"}
             "to" {"id" "pass:creeds/nicene" "written" "pass:nicene#14:8" "passage" "14:8"}
             "kind" "link" "status" "dangling" "problem" "locator-unsupported"
             "position" {"file" "test/fixtures/libraries/check/links.hdoc" "line" 15 "column" 43}}
            {"from" {"id" "pass:creeds/quoting" "passage" "sec:quotes"}
             "to" {"id" "pass:creeds/nicene" "written" "pass:nicene#sec:creed" "passage" "sec:creed"}
             "kind" "quote" "status" "resolved" "problem" nil
             "position" {"file" "test/fixtures/libraries/passages/creeds/quoting.hdoc" "line" 7 "column" 1}}
            {"from" {"id" "check:links" "passage" "sec:links"}
             "to" {"id" "pass:creeds/nicene" "written" "pass:nicene#sec:nope" "passage" "sec:nope"}
             "kind" "link" "status" "dangling" "problem" "unknown-label"
             "position" {"file" "test/fixtures/libraries/check/links.hdoc" "line" 11 "column" 33}}]
           edges))))

(deftest exit-codes-test
  (testing "nothing references the book: exit 0, an empty list"
    (let [{:keys [status out]} (main-run "--libraries" fixtures-yaml "--backlinks" "reformed:apostles")]
      (is (= 0 status))
      (is (= "reformed:creeds/apostles\n0 references from 0 books\n" out))))
  (testing "exit 2, nothing on stdout, when it cannot run"
    (let [dir (temp-dir)
          yaml (str (write! dir "libraries.yaml" "- name: lib\n  type: filesystem\n  path: lib\n"))
          bad (str (write! dir "bad.yaml" "- name: lib\n  type: filesystem\n- not a map\n"))]
      (.mkdirs (io/file dir "lib"))
      (doseq [[args message]
              [[["--libraries" yaml "--backlinks" "lib:"] "haselnuss: error: malformed book reference \"lib:\""]
               [["--libraries" yaml "--backlinks" "lib:a#x#y"] "malformed book reference"]
               [["--libraries" yaml "--backlinks" "lib:nope"]
                "haselnuss: error: --backlinks lib:nope: library \"lib\" has no book \"nope\"\n"]
               [["--libraries" yaml "--backlinks" "zzz:a"]
                "haselnuss: error: --backlinks zzz:a: the library directory has no library \"zzz\" (it has: lib)"]
               [["--libraries" fixtures-yaml "--backlinks" "shared:same"]
                "--backlinks shared:same: slug \"same\" in library \"shared\" names more than one book"]
               [["--libraries" fixtures-yaml "--backlinks" "broken:x"]
                "--backlinks broken:x: library \"broken\" is unavailable"]
               [["--libraries" (str yaml ".missing") "--backlinks" "lib:a"]
                "libraries.yaml.missing:1: cannot read the library directory: no such file"]
               [["--libraries" bad "--backlinks" "lib:a"] "bad.yaml:3: an entry must be a map"]
               [["--backlinks" "lib:a"] "haselnuss: error: --backlinks: no library directory was found"]
               [["--libraries" yaml "--backlinks" "lib:a" "doc.hdoc"]
                "haselnuss: error: --backlinks takes no input; got \"doc.hdoc\""]
               [["doc.hdoc" "--backlinks" "lib:a"] "--backlinks takes no input; got \"doc.hdoc\""]
               [["--backlinks"] "option --backlinks requires a value"]
               [["--libraries" yaml "--backlinks" "lib:a" "--backlinks" "lib:b"]
                "--backlinks given more than once"]
               [["--libraries" yaml "--backlinks" "lib:a" "--format" "xml"] "unknown format \"xml\""]
               [["--libraries" yaml "--backlinks" "lib:a" "--check"] "--backlinks cannot be combined with --check"]
               [["--libraries" yaml "--backlinks" "lib:a" "--list-books" "lib"]
                "--backlinks cannot be combined with --list-books"]
               [["--libraries" yaml "--backlinks" "lib:a" "--transform" "nowhere.clj"] "nowhere.clj"]]]
        (let [{:keys [status out err]} (apply main-run args)]
          (is (= 2 status) (pr-str args err))
          (is (= "" out) (pr-str args))
          (is (str/includes? err message) (pr-str args err))))))
  (testing "a build option it has no use for is a warning on stderr; the listing is unaffected"
    (let [{:keys [status out err]} (main-run "--libraries" fixtures-yaml "--backlinks" "reformed:apostles"
                                             "--target" "latex" "--trusted-extensions")]
      (is (= 0 status) err)
      (is (= "reformed:creeds/apostles\n0 references from 0 books\n" out))
      (doseq [flag ["--target" "--trusted-extensions"]]
        (is (str/includes? err (str "haselnuss: warning: " flag " has no effect with --backlinks")) err))))
  (testing "--transform is honoured: the citing book is read as a build transforms it"
    (let [dir (temp-dir)
          _ (write! dir "lib/a.hdoc" "---\ntitle: A\n---\n\nA.\n")
          _ (write! dir "lib/b.hdoc" "---\ntitle: B\n---\n\n# B {#sec:old}\n\nSee @{lib:a}.\n")
          yaml (str (write! dir "libraries.yaml" "- name: lib\n  type: filesystem\n  path: lib\n"))
          rule (str (write! dir "rename.clj"
                            (str "{:select :section\n"
                                 " :transform (fn [node ctx] (assoc-in node [:attr :id] \"sec:new\"))}")))]
      (is (str/includes? (:out (main-run "--libraries" yaml "--backlinks" "lib:a")) "    lib:b#sec:old   link"))
      (let [{:keys [status out err]} (main-run "--libraries" yaml "--backlinks" "lib:a" "--transform" rule)]
        (is (= 0 status) err)
        (is (str/includes? out "    lib:b#sec:new   link") out)))))

;; --- review: what --backlinks prints on stderr is escaped too ---------------------

(def ^:private esc (str (char 27)))

(defn- raw-control?
  "True when `s` holds a control or format character other than the
  newline ending each line."
  [s]
  (boolean (some #(let [cp (int %)]
                    (and (not= cp 10)
                         (or (< cp 0x20) (= cp 0x7f) (= Character/FORMAT (Character/getType cp)))))
                 s)))

(deftest stderr-escaping-test
  (let [dir (.getCanonicalFile (temp-dir))
        _ (write! dir "lib/new/a.hdoc" "---\ntitle: A\n---\n\nA.\n")
        ;; YAML double quotes: \e is ESC, \u202E a right-to-left override
        yaml (str (write! dir "libraries.yaml"
                          (str "- name: lib\n  type: filesystem\n  path: lib\n"
                               "- name: bad\n  type: filesystem\n  path: \"missing\\e[31mdir\\u202e\"\n")))
        bad-dir (str dir "/missing\\u001b[31mdir\\u202e")
        bad-warning (str "haselnuss: warning: " yaml ":4: library \"bad\": cannot build filesystem library"
                         " \"bad\": no such folder: " bad-dir "\n")]
    (doseq [[args status expected-err]
            [[["--backlinks" "lib:nope#x\nhaselnuss: error: injected"] 2
              (str bad-warning "haselnuss: error: --backlinks lib:nope#x\\nhaselnuss: error: injected:"
                   " library \"lib\" has no book \"nope\"\n")]
             [["--backlinks" (str "lib:nope#" esc "]0;pwned" (char 7))] 2
              (str bad-warning "haselnuss: error: --backlinks lib:nope#\\u001b]0;pwned\\u0007:"
                   " library \"lib\" has no book \"nope\"\n")]
             [["--backlinks" (str "lib:old/a#x" esc "[31m")] 0
              (str bad-warning "haselnuss: info: --backlinks \"lib:old/a#x\\u001b[31m\": the book is now at"
                   " lib:new/a; listing the references to it there\n")]
             [["--backlinks" "bad:x"] 2
              (str bad-warning "haselnuss: error: --backlinks bad:x: library \"bad\" is unavailable: it failed"
                   " to load (" yaml ":4: library \"bad\": cannot build filesystem library \"bad\": no such"
                   " folder: " bad-dir ")\n")]
             [["--backlinks" (str "li" esc "b:x")] 2
              (str "haselnuss: error: malformed book reference \"li\\u001bb:x\": library name \"li\\u001bb\""
                   " must match [a-z0-9][a-z0-9-]*\nTry 'haselnuss --help' for usage.\n")]
             [["--backlinks" "lib:a" (str "x" esc "\n.hdoc")] 2
              "haselnuss: error: --backlinks takes no input; got \"x\\u001b\\n.hdoc\"\nTry 'haselnuss --help' for usage.\n"]]]
      (let [{s :status err :err} (apply main-run "--libraries" yaml args)]
        (is (= status s) (pr-str args))
        (is (= expected-err err) (pr-str args))
        (is (not (raw-control? err)) (pr-str args))))
    (testing "the ambiguous-slug message's paths are escaped as well"
      (let [dir (.getCanonicalFile (temp-dir))
            root (str "x" esc "y")
            _ (write! dir (str root "/a/same.hdoc") "A.\n")
            _ (write! dir (str root "/b/same.hdoc") "B.\n")
            yaml (str (write! dir "libraries.yaml" "- name: lib\n  type: filesystem\n  path: \"x\\ey\"\n"))
            {s :status err :err} (main-run "--libraries" yaml "--backlinks" "lib:same")]
        (is (= 2 s) err)
        (is (str/includes? err "names more than one book") err)
        (is (str/includes? err "x\\u001by/a/same.hdoc") err)
        (is (not (raw-control? err)) err)))))

;; --- AC #5: --help -----------------------------------------------------------------

(deftest help-test
  (let [{:keys [out]} (main-run "--help")]
    (doseq [needle ["haselnuss [--libraries PATH] --backlinks BOOK-REF [--format json]"
                    "--backlinks BOOK-REF" "self-references" "\"edges\"" "dangling:"]]
      (is (str/includes? out needle) needle))))
