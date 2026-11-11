(ns haselnuss.error-location-test
  "TASK-134: a parse error names the file and line it came from, for the
  top-level document, an `!include`d file and an `--overlay` file, and keeps
  its original wording after the `path:line:` prefix."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.cli :as cli]
            [haselnuss.parser :as parser])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- parse-error
  [source opts]
  (try (parser/parse source opts)
       nil
       (catch clojure.lang.ExceptionInfo e e)))

(deftest parser-location-test
  (testing "an unsupported inline construct, with front matter above it"
    (let [e (parse-error "---\ntitle: x\n---\n\nhello\n\nfoo <div>x</div> bar\n" {:source "doc.hdoc"})]
      (is (= "doc.hdoc:7:5: unsupported inline construct" (ex-message e)))
      (is (= 7 (:source-line (ex-data e))))
      (is (= 5 (:source-column (ex-data e))))
      (is (= "doc.hdoc" (:source-file (ex-data e))))
      (is (= "<div>" (:text (ex-data e))))))
  (testing "TASK-191: an orphaned attribute group keeps its location, at the group"
    (let [e (parse-error "a\n\nx [s] {.c} y\n" {:source "d"})]
      (is (str/starts-with? (ex-message e) "d:3:7: attribute group `{.c}` is separated by whitespace"))
      (is (= [3 7] ((juxt :source-line :source-column) (ex-data e)))))
    (is (str/starts-with? (ex-message (parse-error "a\n\nx [r] {.c} y\n\n[r]: u\n" {:source "d"}))
                          "d:3:7: attribute group `{.c}`")))
  (testing "an HTML entity on the third line"
    (is (= "d:3:3: unsupported inline construct"
           (ex-message (parse-error "a\n\nb &copy; c\n" {:source "d"})))))
  (testing "an unsupported block construct"
    (is (= "d:1:1: unsupported block construct"
           (ex-message (parse-error "<div>\nx\n</div>\n" {:source "d"})))))
  (testing "an unsupported inline construct inside a directive body"
    (let [e (parse-error "a\n\n:::{x}\n- <b>y</b>\n:::\n" {:source "d"})]
      (is (= "d:4:3: unsupported inline construct" (ex-message e)))
      (is (= 3 (:source-column (ex-data e))))))
  (testing "a directive header with two names points at the header group"
    (let [e (parse-error "a\n\n:::{ok}\nx\n:::\n\n:::{a b}\n:::\n" {:source "d"})]
      (is (= "d:7:4: directive header has more than one name token" (ex-message e)))
      (is (= 4 (:source-column (ex-data e))))))
  (testing "a malformed directive header points at the offending token"
    (let [e (parse-error "a\n\n:::{a !!}\n:::\n" {:source "d"})]
      (is (= "d:3:7: malformed directive header: token is not a name, #id, .class, or key=val"
             (ex-message e)))
      (is (= 7 (:source-column (ex-data e))))))
  (testing "a malformed !include points at the path"
    (let [e (parse-error "a\n\n  !include \"\"\n" {:source "d"})]
      (is (= "d:3:12: malformed !include: the quoted path is empty" (ex-message e)))
      (is (= 12 (:source-column (ex-data e))))))
  (testing "a token that also occurs earlier in the line: the column is the offending one"
    (is (= 11 (:source-column (ex-data (parse-error ":::{x .x$ $}\n:::\n" {:source "d"})))))
    (is (= 18 (:source-column (ex-data (parse-error ":::{x title=\"a$\" $}\n:::\n" {:source "d"}))))))
  (testing "a bare word in a one-line directive's attribute group"
    (let [e (parse-error "a\n\n!x{a b}\n" {:source "d"})]
      (is (= 3 (:source-line (ex-data e))))
      (is (= 4 (:source-column (ex-data e))))
      (is (str/starts-with? (ex-message e) "d:3:4: "))))
  (testing "columns count code points, not UTF-16 units"
    (is (= 10 (:source-column (ex-data (parse-error ":::{x .😀 $}\n:::\n" {:source "d"})))))
    (is (= 4 (:source-column (ex-data (parse-error "é😀 <b>x</b>\n" {:source "d"}))))))
  (testing "a tab counts as one column"
    (is (= 7 (:source-column (ex-data (parse-error ":::{x\t$}\n:::\n" {:source "d"})))))
    (is (= 5 (:source-column (ex-data (parse-error "a\tb <b>x</b>\n" {:source "d"}))))))
  (testing "an error with no known column keeps path:line and has no :source-column"
    (let [e (parse-error "a\n\n:::{x}\nzz\n" {:source "d"})]
      (is (str/starts-with? (ex-message e) "d:3: "))
      (is (not (contains? (ex-data e) :source-column)))))
  (testing "a malformed display-math attribute group"
    (is (= 3 (:source-line (ex-data (parse-error "a\n\n$$ x $$ {bare}\n" {:source "d"}))))))
  (testing "TASK-202: an error in a closing `$$` line's group names that line and the token's column"
    (doseq [[source expected] [;; top level: bare word, backslash, unterminated quote (at the group)
                               ["a\n\n$$\nx\ny\n$$ {bare}\n" "d:6:5: malformed attribute group: token is not"]
                               ["a\n\n$$\nx\n$$ {#a .b bare}\n" "d:5:11: malformed attribute group: token is not"]
                               ["a\n\n$$\nx\n$$ {#a\\b}\n" "d:5:5: malformed attribute group: an id cannot contain a backslash"]
                               ["a\n\n$$\nx\n$$ {key=\"v}\n" "d:5:4: unterminated \"-quoted"]
                               ;; the offending word also in the TeX and in an earlier token
                               ["a\n\n$$\nbare\n$$ {.bare bare}\n" "d:5:11: "]
                               ;; inside a directive body
                               ["a\n\n:::{d}\n$$\nx\n$$ {bare}\n:::\n" "d:6:5: "]
                               ;; contained: the column counts the container's own prefix
                               ["a\n\n- item\n\n  $$\n  x\n  $$ {bare}\n" "d:7:7: "]
                               ["a\n\n> $$\n> bare\n> $$ {bare}\n" "d:5:7: "]
                               ["a\n\n> - x\n>\n>   $$\n>   x\n>   $$  {.c  bare}\n" "d:7:14: "]
                               ["a\n\n  $$\n  x\n  $$ {#a\\b}\n" "d:5:7: malformed attribute group: an id cannot"]]
            s [source
               (str/replace source "\n" "\r\n")
               (str "﻿" source)]]
      (is (str/starts-with? (ex-message (parse-error s {:source "d"})) expected) (pr-str s))))
  (testing "TASK-202 AC #2: a one-line `$$` group's error keeps its line and no column"
    (doseq [s ["a\n\n$$ x $$ {bare}\n" "a\n\n$$ x $$ {#a\\b}\n" "a\n\n- $$ x $$ {bare}\n"]]
      (let [e (parse-error s {:source "d"})]
        (is (str/starts-with? (ex-message e) "d:3: malformed attribute group") (pr-str s))
        (is (not (contains? (ex-data e) :source-column)) (pr-str s)))))
  (testing "TASK-202 AC #2: a contained opening line's group stays prose, located at the group"
    (doseq [s ["a\n\n- $$ {bare}\n  x\n  $$\n" "a\n\n> $$ {bare}\n> x\n> $$\n"]]
      (is (str/starts-with? (ex-message (parse-error s {:source "d"})) "d:3:6: attribute group `{bare}`")
          (pr-str s))))
  (testing "TASK-220 AC #1: a top-level opening line's group is refused, located at the group"
    (doseq [[s expected] [["a\n\n$$ {#e}\nx\n$$\n" "d:3:4: a display math block's attribute group goes on its closing `$$` line"]
                          ["a\n\n$$ {bare}\nx\n$$\n" "d:3:4: a display math block's attribute group goes on its closing `$$` line"]
                          ["a\n\n$$ {#e}\nx\n$$ {#f}\n" "d:3:4: "]
                          ["a\n\n:::{d}\n$$ {#e}\nx\n$$\n:::\n" "d:4:4: "]]
            s' [s (str/replace s "\n" "\r\n")]]
      (let [e (parse-error s' {:source "d"})]
        (is (str/starts-with? (ex-message e) expected) (pr-str s'))
        (is (str/includes? (ex-message e) "{") (pr-str s')))))
  (testing "TASK-220 AC #1: an unterminated `$$ {..}` line stays prose, so it keeps the orphan-group error"
    (is (str/starts-with? (ex-message (parse-error "a\n\n$$ {#e}\nx\n" {:source "d"}))
                          "d:3:4: attribute group `{#e}`")))
  (testing "TASK-220 AC #2: a malformed math group is worded as math, not as a directive"
    (doseq [s ["a\n\n$$ x $$ {a{b}\n" "a\n\n$$ x $$ {é bare}\n" "a\n\n$$\nx\n$$ {a{b}\n" "a\n\n$$\nx\n$$ {é}\n"]]
      (let [e (parse-error s {:source "d"})]
        (is (str/includes? (ex-message e) "malformed attribute group: token is not #id, .class, or key=val") (pr-str s))
        (is (not (str/includes? (ex-message e) "directive")) (pr-str s))))
    (is (str/starts-with? (ex-message (parse-error "a\n\n$$\nx\n$$ {#a é}\n" {:source "d"}))
                          "d:5:8: malformed attribute group: token is not"))
    (testing "a directive header keeps its own wording"
      (is (str/includes? (ex-message (parse-error "a\n\n:::{é}\n:::\n" {:source "d"}))
                         "malformed directive header"))))
  (testing "an unterminated fence names the opening line"
    (is (= 3 (:source-line (ex-data (parse-error "a\n\n:::{x}\nzz\n" {:source "d"}))))))
  (testing "TASK-162 AC #4: CRLF and a BOM keep line and column"
    (doseq [[lf-source expected] [["---\ntitle: x\n---\n\nhello\n\nfoo <div>x</div> bar\n" "d:7:5: "]
                                  ["<b>x</b> after\n" "d:1:1: "]
                                  ["a\n\n:::{a !!}\n:::\n" "d:3:7: "]
                                  ["a\n\n  !include \"\"\n" "d:3:12: "]
                                  ["!x{a b}\n" "d:1:4: "]
                                  ["---\ntitle: [x\n---\n" "d:2: "]]
            s [(str/replace lf-source "\n" "\r\n")
               (str "\uFEFF" lf-source)
               (str "\uFEFF" (str/replace lf-source "\n" "\r\n"))]]
      (let [lf-error (parse-error lf-source {:source "d"})
            e (parse-error s {:source "d"})]
        (is (str/starts-with? (ex-message lf-error) expected) (pr-str lf-source))
        (is (= (ex-message lf-error) (ex-message e)) (pr-str s))
        (is (= (select-keys (ex-data lf-error) [:source-line :source-column])
               (select-keys (ex-data e) [:source-line :source-column]))))))
  (testing "no :source: wording untouched, the line is still in the data"
    (let [e (parse-error "a\n\nb &copy; c\n" {})]
      (is (= "unsupported inline construct" (ex-message e)))
      (is (= 3 (:source-line (ex-data e)))))))

(defn- temp-dir []
  (str (Files/createTempDirectory "haselnuss-error-location" (make-array FileAttribute 0))))

(defn- build-error
  "Runs the CLI over `args`, returning `[status stderr]`."
  [args]
  (let [err (java.io.StringWriter.)
        out (java.io.StringWriter.)
        status (binding [*err* err *out* out] (cli/main* args))]
    [status (str err)]))

(deftest cli-location-test
  (let [dir (temp-dir)
        file (fn [n] (str (io/file dir n)))]
    (spit (file "main.hdoc") "# Intro\n\nfine\n\nbad <div>x</div> here\n")
    (spit (file "chapter.hdoc") "# Chapter\n\nok\n\nfine\n\nalso &copy; bad\n")
    (spit (file "top.hdoc") "# Top\n\n!include chapter.hdoc\n")
    (spit (file "overlay.hdoc") "x\n\ny\n\n<note>\n")
    (spit (file "plain.hdoc") "# Plain\n\ntext\n")
    (spit (file "hdr.hdoc") "# H\n\nok\n\n:::{x .x$ $}\n:::\n")
    (spit (file "tophdr.hdoc") "# Top\n\n!include hdr.hdoc\n")
    (testing "an included file with a bad directive header (hand-parsed path)"
      (let [[status err] (build-error [(file "tophdr.hdoc") "--target" "html"])]
        (is (= 1 status))
        (is (str/includes? err (str (file "hdr.hdoc") ":5:11: malformed directive header")) err)))
    (testing "the top-level document"
      (let [[status err] (build-error [(file "main.hdoc") "--target" "html"])]
        (is (= 1 status))
        (is (str/includes? err (str "haselnuss: error: " (file "main.hdoc") ":5:5: unsupported inline construct")) err)))
    (testing "an included file (AC #3): the error names the included file and its own line"
      (let [[status err] (build-error [(file "top.hdoc") "--target" "html"])]
        (is (= 1 status))
        (is (str/includes? err (str (file "chapter.hdoc") ":7:6: unsupported inline construct")) err)))
    (testing "an overlay file"
      (let [[status err] (build-error [(file "plain.hdoc") "--target" "latex" "--overlay" (file "overlay.hdoc")])]
        (is (= 1 status))
        (is (str/includes? err (str (file "overlay.hdoc") ":5:1: unsupported block construct")) err)))))

(deftest front-matter-yaml-location-test
  (testing "parse: the line counts the opening fence, and the YAML wording follows the prefix"
    (let [e (parse-error "---\ntitle: x\nfoo: [a\nbar: 1\n---\n\nbody\n" {:source "d.hdoc"})]
      (is (= :haselnuss.parser/malformed-front-matter (:type (ex-data e))))
      (is (= 4 (:source-line (ex-data e))))
      (is (= "d.hdoc" (:source-file (ex-data e))))
      (is (str/starts-with? (ex-message e) "d.hdoc:4: while parsing a flow sequence"))))
  (testing "no :source: wording untouched, line still in the data"
    (let [e (parse-error "---\ntitle: x\n  b: 2\n---\n" {})]
      (is (= 3 (:source-line (ex-data e))))
      (is (str/starts-with? (ex-message e) "mapping values are not allowed here"))))
  (let [dir (temp-dir)
        file (fn [n] (str (io/file dir n)))]
    (spit (file "bad.hdoc") "---\ntitle: x\nfoo: [a\nbar: 1\n---\n\ntext\n")
    (spit (file "top.hdoc") "# Top\n\n!include bad.hdoc\n")
    (spit (file "plain.hdoc") "# Plain\n\ntext\n")
    (testing "the top-level document"
      (let [[status err] (build-error [(file "bad.hdoc") "--target" "html"])]
        (is (= 1 status))
        (is (str/includes? err (str (file "bad.hdoc") ":4: while parsing a flow sequence")) err)))
    (testing "an included file (AC #3)"
      (let [[status err] (build-error [(file "top.hdoc") "--target" "html"])]
        (is (= 1 status))
        (is (str/includes? err (str (file "bad.hdoc") ":4: while parsing a flow sequence")) err)))
    (testing "an overlay file"
      (let [[status err] (build-error [(file "plain.hdoc") "--target" "latex" "--overlay" (file "bad.hdoc")])]
        (is (= 1 status))
        (is (str/includes? err (str (file "bad.hdoc") ":4: while parsing a flow sequence")) err)))))

(deftest bibliography-not-a-path-test
  (testing "parse: a bibliography that is not a string is a located error at its key (TASK-209)"
    (doseq [[source line kind] [["---\ntitle: T\nbibliography: [a.json]\n---\n\nbody\n" 3 "a list"]
                                ["---\nbibliography:\n  - a.json\n  - b.json\n---\n" 2 "a list"]
                                ["---\ntitle: T\n# c\n\nbibliography: 42\n---\n" 5 "a number"]
                                ["---\nbibliography: true\n---\n" 2 "a boolean"]
                                ["---\nbibliography: 2026-07-24\n---\n" 2 "a date"]
                                ["---\nbibliography:\n  file: refs.json\n---\n" 2 "a map"]
                                ;; the occurrence YAML keeps when the key repeats
                                ["---\nbibliography: a.json\nlang: de\nbibliography: [b]\n---\n" 4 "a list"]
                                ;; a BOM and CRLF endings do not shift the line
                                ["﻿---\r\nlang: de\r\nbibliography: 7\r\n---\r\n" 3 "a number"]]
            :let [e (parse-error source {:source "d.hdoc"})]]
      (is (= :haselnuss.parser/bibliography-not-a-path (:type (ex-data e))) source)
      (is (= line (:source-line (ex-data e))) source)
      (is (= "d.hdoc" (:source-file (ex-data e))) source)
      (is (str/starts-with? (ex-message e)
                            (str "d.hdoc:" line ": front-matter bibliography must be a path to a"
                                 " CSL-JSON file (a string), but this one is " kind))
          source)
      (is (not (str/includes? (ex-message e) "LazySeq")) source)))
  (testing "a scalar YAML did not read as a string is told how to keep it"
    (is (str/ends-with? (ex-message (parse-error "---\nbibliography: 42\n---\n" {}))
                        "a number; quote it to use it as a file name")))
  (testing "the value YAML read is in the data, for a caller that reports it itself"
    (is (= ["a.json"] (:value (ex-data (parse-error "---\nbibliography: [a.json]\n---\n" {}))))))
  (testing "no :source: no path prefix, line still in the data"
    (let [e (parse-error "---\nbibliography: 42\n---\n" {})]
      (is (= 2 (:source-line (ex-data e))))
      (is (not (contains? (ex-data e) :source-file)))
      (is (str/starts-with? (ex-message e) "front-matter bibliography must be a path"))))
  (testing "front-matter (the CLI's bibliography read) reports it the same way"
    (let [e (try (parser/front-matter "---\nbibliography: [a.json]\n---\n" {:source "d.hdoc"})
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (= :haselnuss.parser/bibliography-not-a-path (:type (ex-data e))))
      (is (str/starts-with? (ex-message e) "d.hdoc:2: front-matter bibliography must be a path"))))
  (testing ":omit-non-path-bibliography? leaves it out of Meta instead"
    (let [source "---\nlang: de\nbibliography: [a.json]\n---\n\nbody\n"]
      (is (= {:lang "de"} (parser/front-matter source {:omit-non-path-bibliography? true})))
      (is (= {:lang "de"} (:meta (parser/parse source {:omit-non-path-bibliography? true}))))))
  (testing "a null bibliography is no bibliography: the key is left out"
    (doseq [source ["---\nbibliography:\n---\n" "---\nbibliography: ~\n---\n"
                    "---\nbibliography: null\nlang: de\n---\n"]]
      (is (not (contains? (parser/front-matter source) :bibliography)) source)
      (is (not (contains? (:meta (parser/parse source)) :bibliography)) source)))
  (testing "string paths are read as before (AC #2), a blank one included"
    (doseq [[yaml path] [["refs.json" "refs.json"]
                         ["'42'" "42"]
                         ["\"refs dir/a b.json\"" "refs dir/a b.json"]
                         ["/abs/refs.json" "/abs/refs.json"]
                         ["''" ""]
                         ["'  '" "  "]]
            :let [source (str "---\nbibliography: " yaml "\n---\n\nbody\n")]]
      (is (= {:bibliography path} (parser/front-matter source {:source "d.hdoc"})) source)
      (is (= {:bibliography path} (:meta (parser/parse source {:source "d.hdoc"}))) source)))
  (let [dir (temp-dir)
        file (fn [n] (str (io/file dir n)))]
    (spit (file "bad.hdoc") "---\ntitle: T\nbibliography: [refs.json]\n---\n\ntext\n")
    (spit (file "top.hdoc") "# Top\n\n!include bad.hdoc\n")
    (testing "the CLI prints the located error and exits 1, rather than looking for a nonsense file"
      (doseq [target ["html" "latex" "json"]]
        (let [[status err] (build-error [(file "bad.hdoc") "--target" target])]
          (is (= 1 status) target)
          (is (str/includes? err (str "haselnuss: error: " (file "bad.hdoc")
                                      ":3: front-matter bibliography must be a path"))
              err)
          (is (not (str/includes? err "not found")) err))))
    (testing "an included file: the include error carries the included file's own location"
      (let [[status err] (build-error [(file "top.hdoc") "--target" "html"])]
        (is (= 1 status))
        (is (str/includes? err (str (file "bad.hdoc") ":3: front-matter bibliography must be a path"))
            err)))))

(deftest front-matter-scalar-fields-test
  (testing "an unknown topLevelDivision is located at its key (TASK-224)"
    (doseq [[source line] [["---\ntopLevelDivision: chapters\n---\n" 2]
                           ["---\ntitle: T\nlang: de\ntopLevelDivision: book\n---\n" 4]
                           ["---\ntopLevelDivision:\n---\n" 2]
                           ["---\ntopLevelDivision: [a]\n---\n" 2]
                           ["﻿---\r\n# c\r\ntopLevelDivision: Chapter\r\n---\r\n" 3]]
            :let [e (parse-error source {:source "d.hdoc"})]]
      (is (= :haselnuss.parser/unknown-top-level-division (:type (ex-data e))) source)
      (is (= line (:source-line (ex-data e))) source)
      (is (= "d.hdoc" (:source-file (ex-data e))) source)
      (is (str/starts-with? (ex-message e) (str "d.hdoc:" line ": unknown topLevelDivision")) source))
    (let [e (parse-error "---\ntopLevelDivision: x\n---\n" {})]
      (is (= 2 (:source-line (ex-data e))))
      (is (not (contains? (ex-data e) :source-file)))
      (is (str/starts-with? (ex-message e) "unknown topLevelDivision"))))
  (testing "a non-string cslStyle or lang is a located error saying what it must be"
    (doseq [[source line field kind] [["---\ncslStyle: [a]\n---\n" 2 "cslStyle" "a list"]
                                      ["---\nlang: 42\n---\n" 2 "lang" "a number"]
                                      ["---\ntitle: T\nlang: no\n---\n" 3 "lang" "a boolean"]
                                      ["---\ncslStyle: {a: b}\n---\n" 2 "cslStyle" "a map"]
                                      ["---\nlang: de\nlang: [x]\n---\n" 3 "lang" "a list"]]
            :let [e (parse-error source {:source "d.hdoc"})]]
      (is (= :haselnuss.parser/front-matter-not-a-string (:type (ex-data e))) source)
      (is (= line (:source-line (ex-data e))) source)
      (is (= "d.hdoc" (:source-file (ex-data e))) source)
      (is (str/starts-with? (ex-message e)
                            (str "d.hdoc:" line ": front-matter " field " must be a string, but this one is " kind))
          source)
      (is (not (str/includes? (ex-message e) "LazySeq")) source))
    (is (str/ends-with? (ex-message (parse-error "---\nlang: 42\n---\n" {}))
                        "a number; quote it to use it as a string")))
  (testing "string values behave as before; null leaves the key out"
    (is (= {:csl-style "apa" :lang "42"}
           (parser/front-matter "---\ncslStyle: apa\nlang: '42'\n---\n" {})))
    (is (= {} (parser/front-matter "---\ncslStyle:\nlang: ~\n---\n" {}))))
  (testing ":omit-invalid-front-matter? leaves the bad fields (and a bad bibliography) out"
    (let [source "---\nlang: [x]\ncslStyle: 1\ntopLevelDivision: x\nbibliography: [a]\ntitle: T\n---\n"]
      (is (= [:title] (keys (parser/front-matter source {:omit-invalid-front-matter? true}))))
      (is (= [:title] (keys (:meta (parser/parse source {:omit-invalid-front-matter? true}))))))))

(deftest front-matter-not-a-map-test
  (testing "parse: YAML that is not a map is a located error naming what it is (TASK-178)"
    (doseq [[source line kind] [["---\na\n---\n\nbody\n" 2 "a string"]
                                ["---\nMy Title\n---\n" 2 "a string"]
                                ["---\n- a\n- b\n---\n" 2 "a list"]
                                ["---\n[a, b]\n---\n" 2 "a list"]
                                ["---\n42\n---\n" 2 "a number"]
                                ["---\n4.2\n---\n" 2 "a number"]
                                ["---\ntrue\n---\n" 2 "a boolean"]
                                ["---\n2026-07-24\n---\n" 2 "a date"]
                                ;; a BOM and CRLF endings do not shift the line
                                ["﻿---\r\n# c\r\n\r\n42\r\n---\r\n" 4 "a number"]
                                ["---\n!!binary aGk=\n---\n" 2 "some other kind of value"]
                                ;; the line is where the value starts, past comments
                                ["---\n# a comment\n\n'quoted'\n---\n" 4 "a string"]]
            :let [e (parse-error source {:source "d.hdoc"})]]
      (is (= :haselnuss.parser/front-matter-not-a-map (:type (ex-data e))) source)
      (is (= line (:source-line (ex-data e))) source)
      (is (= "d.hdoc" (:source-file (ex-data e))) source)
      (is (= (str "d.hdoc:" line ": front matter must be a YAML map of `key: value` fields,"
                  " but this one is " kind)
             (ex-message e))
          source)))
  (testing "no :source: no path prefix, line still in the data"
    (let [e (parse-error "---\n42\n---\n" {})]
      (is (= 2 (:source-line (ex-data e))))
      (is (not (contains? (ex-data e) :source-file)))
      (is (str/starts-with? (ex-message e) "front matter must be a YAML map"))))
  (testing "front-matter (the CLI's bibliography read) reports it the same way"
    (let [e (try (parser/front-matter "---\n- a\n---\n" {:source "d.hdoc"})
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (= :haselnuss.parser/front-matter-not-a-map (:type (ex-data e))))
      (is (str/starts-with? (ex-message e) "d.hdoc:2: front matter must be a YAML map"))))
  (testing "two YAML documents in the block: already a located YAML error (TASK-144)"
    (let [e (parse-error "---\nlang: de\n--- \ntitle: T\n---\n" {:source "d.hdoc"})]
      (is (= :haselnuss.parser/malformed-front-matter (:type (ex-data e))))
      (is (= 3 (:source-line (ex-data e))))))
  (testing "YAML that is nothing at all is still no fields (AC #2)"
    (doseq [source ["---\n---\n\nbody\n" "---\n\n---\n" "---\n# only a comment\n---\n"
                    "---\nnull\n---\n" "---\n~\n---\n"]]
      (is (= {} (:meta (parser/parse source {:source "d.hdoc"}))) source)
      (is (= {} (parser/front-matter source {:source "d.hdoc"})) source)))
  (testing "a map is read as before (AC #2)"
    (is (= {:title [{:t :str :text "T"}] :lang "de"}
           (:meta (parser/parse "---\ntitle: T\nlang: de\n---\n\nbody\n" {:source "d.hdoc"})))))
  (let [dir (temp-dir)
        file (fn [n] (str (io/file dir n)))]
    (spit (file "bad.hdoc") "---\nMy Title\n---\n\ntext\n")
    (spit (file "top.hdoc") "# Top\n\n!include bad.hdoc\n")
    (testing "the CLI prints the located error and exits 1"
      (let [[status err] (build-error [(file "bad.hdoc") "--target" "html"])]
        (is (= 1 status))
        (is (str/includes? err (str "haselnuss: error: " (file "bad.hdoc")
                                    ":2: front matter must be a YAML map"))
            err)
        (is (not (str/includes? err "contains?")) err)))
    (testing "an included file: the include error carries the included file's own location"
      (let [[status err] (build-error [(file "top.hdoc") "--target" "html"])]
        (is (= 1 status))
        (is (str/includes? err (str (file "bad.hdoc") ":2: front matter must be a YAML map")) err)
        (is (not (str/includes? err "contains?")) err)))))
