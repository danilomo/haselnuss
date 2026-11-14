(ns haselnuss.stderr-escaping-test
  "TASK-247: every line the CLI prints on stderr -- a build's or
  `--list-books`' diagnostics, an error, a `wrote` line, a usage error --
  is exactly one line, its control and format characters escaped as
  `--check` escapes them, whatever a library (a `shelf.clj` exception, a
  reference as written, a file name) or the command line put in it. And
  `warn!` prints an info-level diagnostic as `info:`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.cli :as cli]
            [haselnuss.library.graph.text :as text])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

;; --- helpers -----------------------------------------------------------------

(def ^:private overrides
  "No real `$HASELNUSS_LIBRARIES` or home config."
  {:library-env {} :library-home "/nonexistent/haselnuss-test-home"})

(defn- main-run
  [& args]
  (let [err (java.io.StringWriter.)
        out (java.io.StringWriter.)
        status (binding [*err* err *out* out] (cli/main* args overrides))]
    {:status status :out (str out) :err (str err)}))

(defn- temp-dir ^File []
  (.getCanonicalFile
   (.toFile (Files/createTempDirectory "haselnuss-stderr-test" (make-array FileAttribute 0)))))

(defn- write!
  ^File [^File dir rel text]
  (let [f (io/file dir rel)]
    (io/make-parents f)
    (spit f text)
    f))

(defn- library-dir!
  "A temp folder holding the library `lib` (`books`: relative path ->
  source) and a `libraries.yaml` naming it, plus `extra` lines for its
  entry. Returns `[dir yaml-path]`."
  ([books] (library-dir! books ""))
  ([books extra]
   (let [dir (temp-dir)]
     (doseq [[path source] books] (write! dir (str "lib/" path) source))
     [dir (str (write! dir "libraries.yaml" (str "- name: lib\n  type: filesystem\n  path: lib\n" extra)))])))

(def ^:private esc (str (char 27)))

(defn- escapable?
  "True for a code point `escape-text` writes out escaped (a backslash
  apart): C0, DEL, C1, U+2028/2029 and every format character."
  [^long cp]
  (or (< cp 0x20) (= cp 0x7f) (<= 0x80 cp 0x9f) (= cp 0x2028) (= cp 0x2029)
      (= Character/FORMAT (Character/getType (int cp)))))

(defn- code-points [^String s] (map long (iterator-seq (.iterator (.codePoints s)))))

(defn- control?
  "True when `s` holds any character `escapable?` names, newline included."
  [s]
  (boolean (some escapable? (code-points (str s)))))

(defn- raw-control?
  "True when printed stderr `err` holds such a character other than the
  newline ending each line."
  [err]
  (boolean (some #(and (not= 10 %) (escapable? %)) (code-points (str err)))))

(def ^:private line-head
  "What every stderr line starts with."
  #"^(haselnuss: (warning: |info: |error: |wrote )|Try 'haselnuss --help' for usage\.$)")

(defn- well-formed-lines?
  "True when every line of `err` starts like a line the CLI writes."
  [err]
  (every? #(re-find line-head %) (str/split-lines err)))

;; --- AC #2: the shelf.clj injection repro -------------------------------------------

(def ^:private injecting-shelf
  (str "{:books (fn [_] (throw (ex-info \"boom\\nfake.hdoc:1: error: [injected] pwned\\n"
       "\\u001b[31mRED\" {})))\n :document (fn [_ _] nil)}\n"))

(deftest shelf-script-injection-test
  (let [[dir yaml] (library-dir! {"a.hdoc" "---\ntitle: A\n---\n\nSee @{lib:x}.\n"
                                  "gen/shelf.clj" injecting-shelf}
                                 "  scripts: true\n")
        out-file (str (io/file dir "out.html"))]
    (doseq [[what status level args]
            [["building a book of the library by its ID, whose reference the
               shelf could have generated" 0 "warning"
              ["--libraries" yaml "--output" out-file "lib:a"]]
             ["building a book ID only the shelf could list" 1 "error"
              ["--libraries" yaml "--output" out-file "lib:gen/x"]]
             ["--list-books" 0 "warning" ["--libraries" yaml "--list-books" "lib"]]]]
      (testing what
        (let [{s :status err :err} (apply main-run args)
              lines (str/split-lines err)]
          (is (= status s) err)
          (testing "the exception text stays on its one line, escaped"
            (is (some #(and (str/starts-with? % (str "haselnuss: " level ": "))
                            (str/includes? % (str "boom\\nfake.hdoc:1: error: [injected] pwned\\n"
                                                  "\\u001b[31mRED")))
                      lines)
                err))
          (is (not-any? #(str/starts-with? % "fake.hdoc") lines) err)
          (is (not (raw-control? err)) err)
          (is (well-formed-lines? err) err))))))

;; --- AC #2: a written reference holding ESC -----------------------------------------

(deftest written-ref-with-esc-test
  (let [[dir yaml] (library-dir! {"a.hdoc" "---\ntitle: A\n---\n\nA.\n"})
        doc (str (write! dir "doc.hdoc" (str "See @{lib:nope" esc "[31m} and @{lib:a" esc "]0;t" (char 7) "}.\n")))
        {:keys [status err]} (main-run "--libraries" yaml "--output" (str (io/file dir "doc.html")) doc)
        lines (str/split-lines err)]
    (is (= 0 status) err)
    (is (some #(and (str/starts-with? % (str "haselnuss: warning: " doc ":1: "))
                    (str/includes? % "lib:nope\\u001b[31m"))
              lines)
        err)
    (is (str/includes? err "\\u001b]0;t\\u0007") err)
    (is (not (raw-control? err)) err)
    (is (well-formed-lines? err) err)))

;; --- the rest of what a build prints: errors, wrote lines, usage errors -----------

(deftest error-and-wrote-lines-test
  (let [dir (temp-dir)
        doc (str (write! dir "doc.hdoc" "# Hi\n"))]
    (testing "an --output path holding a newline and ESC is a one-line wrote line"
      (let [out-file (str (io/file dir (str "x\n" esc "[2J.html")))
            {:keys [status err]} (main-run "--output" out-file doc)]
        (is (= 0 status) err)
        (is (= (str "haselnuss: wrote " dir "/x\\n\\u001b[2J.html\n") err))))
    (testing "an input that does not exist is echoed escaped in the error"
      (let [{:keys [status err]} (main-run (str "/nonexistent/a\nhaselnuss: error: b" esc ".hdoc"))]
        (is (= 1 status))
        (is (= "haselnuss: error: input file not found: /nonexistent/a\\nhaselnuss: error: b\\u001b.hdoc\n" err))))
    (testing "a backslash is printed as it is, so a message without a control
              character is unchanged (AC #3)"
      (is (= "haselnuss: error: input file not found: /nonexistent/a\\b\"c.hdoc\n"
             (:err (main-run "/nonexistent/a\\b\"c.hdoc")))))))

(deftest usage-error-test
  (doseq [[args expected]
          [[["--check" "--format" (str esc "[31m")]
            "haselnuss: error: unknown format \"\\u001b[31m\" (expected one of: json, text)"]
           [["--backlinks" "lib:a" "--format" (str esc "[31m")]
            "haselnuss: error: unknown format \"\\u001b[31m\" (expected one of: json, text)"]
           [["--backlinks" "lib:a" (str "--" esc "[31m")]
            "haselnuss: error: unknown option \"--\\u001b[31m\""]
           [[(str "--x\u202e\u2028" (char 0x85))]
            "haselnuss: error: unknown option \"--x\\u202e\\u2028\\u0085\""]
           [["-t" (str "h" esc "tml") "doc.hdoc"]
            "haselnuss: error: unknown target \"h\\u001btml\" (expected one of: hdoc, html, json, latex)"]
           [["a.hdoc" (str "b" esc)]
            "haselnuss: error: unexpected extra argument \"b\\u001b\" (only one input file is accepted)"]
           [["--backlinks" "lib:a" (str "x" esc "\n.hdoc")]
            "haselnuss: error: --backlinks takes no input; got \"x\\u001b\\n.hdoc\""]]]
    (let [{:keys [status err]} (apply main-run args)]
      (is (= 2 status) (pr-str args))
      (is (= (str expected "\nTry 'haselnuss --help' for usage.\n") err) (pr-str args)))))

;; --- review: --list-books' titles on stdout --------------------------------------

(deftest list-books-titles-test
  (testing "a shelf.clj title cannot forge a row, a column or reach the terminal"
    (let [[_ yaml] (library-dir! {"gen/shelf.clj"
                                  (str "{:books (fn [_] [{:slug \"x\" :title \"T\\u001b[31m\\nlib:fake\\tFAKE\"}])\n"
                                       " :document (fn [_ _] nil)}\n")}
                                 "  scripts: true\n")
          {:keys [status out err]} (main-run "--libraries" yaml "--list-books" "lib")]
      (is (= 0 status) err)
      (is (= "lib:gen/x\tT\\u001b[31m\\nlib:fake\\tFAKE\n" out))))
  (testing "nor a front-matter one (a YAML escape), and a backslash is doubled as
            --check doubles it"
    (let [[_ yaml] (library-dir! {"a.hdoc" "---\ntitle: \"A\\e[2J\\u202e\\\\b\"\n---\n\nA.\n"})
          {:keys [status out err]} (main-run "--libraries" yaml "--list-books" "lib")]
      (is (= 0 status) err)
      (is (= "lib:a\tA\\u001b[2J\\u202e\\\\b\n" out))))
  (testing "a plain title prints as it is"
    (let [[_ yaml] (library-dir! {"a.hdoc" "---\ntitle: \"Ação de Graças: 1\"\n---\n\nA.\n"})]
      (is (= "lib:a\tAção de Graças: 1\n" (:out (main-run "--libraries" yaml "--list-books" "lib")))))))

;; --- review: snakeyaml's errors as one readable line --------------------------------

(deftest yaml-errors-test
  (testing "a malformed libraries.yaml is one line naming the problem and where,
            not the escaped source excerpt with its ^ marker"
    (let [dir (temp-dir)
          yaml (str (write! dir "libraries.yaml" "- name: a\n  type: [unclosed\n"))
          {:keys [status err]} (main-run "--libraries" yaml "--list-books" "a")]
      (is (= 1 status) err)
      (is (= (str "haselnuss: error: " yaml ":3: invalid YAML: while parsing a flow sequence (line 2,"
                  " column 9): expected ',' or ']', but got <stream end> (line 3, column 1)\n")
             err))))
  (testing "so is malformed front matter, its lines counted in the document"
    (let [dir (temp-dir)
          doc (str (write! dir "bad.hdoc" "---\ntitle: x\nauthor: [a\n---\n\nText.\n"))
          {:keys [status err]} (main-run "--output" (str (io/file dir "bad.html")) doc)]
      (is (= 1 status) err)
      (is (re-find #"^haselnuss: error: .*bad\.hdoc:\d+: while parsing a flow sequence \(line 3, column 9\): " err)
          err)
      (is (not (str/includes? err "^")) err)
      (is (not (str/includes? err "\\n")) err))))

;; --- the diagnostic's own level -----------------------------------------------------

(deftest info-level-test
  (let [[dir yaml] (library-dir! {"new/a.hdoc" "---\ntitle: A\n---\n\nA.\n"})
        doc (str (write! dir "doc.hdoc" "See @{lib:old/a}.\n"))
        out-file (str (io/file dir "doc.html"))]
    (testing "a stale shelf path in a document is an info, not a warning"
      (is (= (str "haselnuss: info: " doc ":1: book reference \"lib:old/a\": the book is now at lib:new/a;"
                  " the reference still resolves, but its shelf path is stale\n"
                  "haselnuss: wrote " out-file "\n")
             (:err (main-run "--libraries" yaml "--output" out-file doc)))))
    (testing "and so is a BOOK-ID input's"
      (let [{:keys [status err]} (main-run "--libraries" yaml "--output" out-file "lib:old/a")]
        (is (= 0 status) err)
        (is (re-find #"(?m)^haselnuss: info: book ID \"lib:old/a\": the book is now at lib:new/a" err) err)
        (is (not (str/includes? err "warning:")) err)))))

;; --- escape-controls: seeded fuzz (seed = 247) ------------------------------------

(def ^:private alphabet
  "The characters and contexts the escaping is about: C0 (with the three
  named ones), ESC, DEL, C1, the line and paragraph separators, format
  characters (bidi, zero-width, BOM, a tag character above the BMP), the
  backslash and quote, and ordinary text, ASCII and not."
  (into [] (concat ["\n" "\r" "\t" (str (char 0)) (str (char 7)) esc (str (char 0x7f))
                    (str (char 0x85)) (str (char 0x9b)) "\u2028" "\u2029" "\u202e" "\u2066"
                    "\u200b" "\ufeff" (String. (Character/toChars 0xE0041)) "\\" "\"" "\\n" "\\u001b"]
                   ["a" "Z" "0" " " ":" "[" "é" "ß" "日" (String. (Character/toChars 0x1F600))])))

(defn- fuzz-strings
  [n]
  (let [rnd (java.util.Random. 247)]
    (vec (repeatedly n (fn [] (apply str (repeatedly (.nextInt rnd 12)
                                                     #(nth alphabet (.nextInt rnd (count alphabet))))))))))

(deftest escape-controls-fuzz-test
  (let [cases (fuzz-strings 2000)
        escaping (filter control? cases)
        with-backslash (filter #(str/includes? % "\\") cases)]
    (testing "the fuzz is not vacuous"
      (is (< 1000 (count escaping)) (str (count escaping)))
      (is (< 500 (count with-backslash)) (str (count with-backslash)))
      (is (< 100 (count (remove control? cases)))))
    (doseq [s cases
            :let [e (text/escape-controls s)]]
      (is (not (control? e)) (pr-str s))
      (testing "escapes each character exactly as escape-text does, a backslash
                apart, which it leaves as it is"
        (is (= e (str/join "\\" (map text/escape-text (str/split s #"\\" -1)))) (pr-str s)))
      (when-not (control? s)
        (is (= s e) (pr-str s))))))

(deftest cli-fuzz-test
  (testing "an unknown option made of any of those characters is always the
            error line and the pointer, nothing else"
    (let [cases (map #(str "--" %) (fuzz-strings 300))
          hit (atom 0)]
      (doseq [arg cases
              :let [{:keys [status err]} (main-run arg)
                    lines (str/split-lines err)]
              :when (not= arg "--")]
        (when (control? arg) (swap! hit inc))
        (is (= 2 status) (pr-str arg))
        (is (= 2 (count lines)) (pr-str [arg err]))
        (is (str/starts-with? (first lines) "haselnuss: error: unknown option \"--") (pr-str [arg err]))
        (is (not (raw-control? err)) (pr-str arg)))
      (is (< 150 @hit) (str @hit)))))
