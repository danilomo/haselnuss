(ns haselnuss.uberjar-test
  "Fixtures for the packaged CLI (TASK-26).

  Deliberately builds and runs the *real* jar rather than exercising
  `haselnuss.cli` in-process, because every acceptance criterion here is
  about the artifact: `lein uberjar` producing one runnable file, that
  file printing usage, and that file's own process exit code. It also
  catches the failure modes an in-process test structurally cannot -- a
  missing `:gen-class`, a namespace not reachable from `-main` and so
  never AOT-compiled into the jar, or code that resolves a resource from
  a REPL classpath but not from inside a jar.

  Slow by nature (a full AOT build plus several JVM starts). That is the
  same trade `haselnuss.emit.latex-test` already makes by shelling out
  to a real `pdflatex`: an AC that names an external toolchain is only
  honestly checked by running it."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.awt.image BufferedImage)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (javax.imageio ImageIO)))

(def ^:private jar-path
  "Where `:uberjar-name` puts the artifact. Hardcoded rather than
  globbed, since pinning that exact path is part of what this task
  delivers -- a jar whose name changes with the version is not something
  a script can reference."
  "target/uberjar/haselnuss.jar")

(defn- build-jar!
  "Runs `lein uberjar` once for this namespace, returning the jar file.
  Memoized so the several tests below share one build."
  []
  (let [{:keys [exit out err]} (shell/sh "lein" "uberjar")]
    (assert (zero? exit) (str "lein uberjar failed:\n" out err))
    (io/file jar-path)))

(def ^:private jar (memoize build-jar!))

(defn- run-jar
  "Runs the packaged jar with `args`, returning `{:exit :out :err}`.
  Trailing `clojure.java.shell` options pass straight through, so a
  caller can add `:dir`; the jar path is absolute precisely so that
  works."
  [& args]
  (apply shell/sh "java" "-jar" (.getAbsolutePath ^java.io.File (jar)) args))

(def ^:private rich-document
  "A document reaching the parts of the pipeline an uberjar actually
  breaks on: YAML front matter (`clj-yaml`), the flexmark table,
  footnote and attribute extensions, a figure, display and inline math,
  a citation resolved against a bibliography file, and directives from
  both the registry-native and degraded paths. The trivial two-line
  fixture this test first used exercised none of them, which is exactly
  the coverage an AOT/jar test exists to provide (found by review)."
  (str "---\ntitle: Packaged\nlang: en\nbibliography: refs.json\n---\n\n"
       "# Introduction {#sec:intro}\n\n"
       "Prose with a footnote[^n] and inline math $x^2$.\n\n"
       "[^n]: The footnote body.\n\n"
       "![A hazel tree.](pic.png){#fig:tree}\n\n"
       "See @fig:tree, @eq:mass and [@knuth1984].\n\n"
       "$$\nE = mc^2\n$$ {#eq:mass}\n\n"
       ;; The caption line must immediately follow the table, with no
       ;; blank line between them (sec5.9).
       "| A | B |\n|---|---|\n| 1 | 2 |\n: A table {#tbl:data}\n\n"
       ":::{theorem #thm:main}\nAll good things end.\n:::\n\n"
       ":::{collapsable summary=Details}\nHidden.\n:::\n\n"
       "Also @thm:main and @tbl:data.\n"))

(def ^:private bibliography
  "[{\"id\":\"knuth1984\",\"author\":[{\"family\":\"Knuth\",\"given\":\"Donald\"}],\"issued\":{\"date-parts\":[[1984]]},\"title\":\"The TeXbook\"}]")

(defn- fixture-dir!
  "A fresh temp directory holding `source` as `doc.hdoc`, plus the
  bibliography and image a rich document needs."
  [source]
  (let [dir (str (Files/createTempDirectory "haselnuss-jar-test" (make-array FileAttribute 0)))]
    (spit (io/file dir "doc.hdoc") source)
    (spit (io/file dir "refs.json") bibliography)
    (ImageIO/write (BufferedImage. 1 1 BufferedImage/TYPE_INT_RGB) "png" (io/file dir "pic.png"))
    dir))

(deftest uberjar-test
  (testing "AC #1: lein uberjar produces a single runnable jar"
    (is (.isFile (jar)))
    (is (pos? (.length (jar))))
    (testing "and it really is self-contained -- run with a bare `java
              -jar`, no classpath, no Leiningen"
      (is (= 0 (:exit (run-jar "--help"))))))
  (testing "AC #2: the jar prints usage instructions covering target
            selection, input/output paths and the available flags, for
            both a help flag and no arguments at all"
    (doseq [[label result] [["--help" (run-jar "--help")]
                            ["-h" (run-jar "-h")]
                            ["no arguments" (run-jar)]]]
      (let [text (str (:out result) (:err result))]
        (is (str/includes? text "Usage: haselnuss") label)
        (is (str/includes? text "--target") (str label " covers target selection"))
        (is (str/includes? text "--output") (str label " covers output path"))
        (is (str/includes? text "INPUT.hdoc") (str label " covers the input path"))
        (is (str/includes? text "--computed-numbers") (str label " covers the flags"))))
    (testing "a help flag is not an error, so its usage goes to stdout
              and its status is success"
      (let [{:keys [exit out]} (run-jar "--help")]
        (is (= 0 exit))
        (is (str/includes? out "Usage: haselnuss")))))
  (testing "TASK-46: the jar reports its own version. Asserted here
            rather than only through `lein run` because the flag exists
            for a jar sitting on disk with no version in its filename --
            and because the version is read from a resource Leiningen
            only generates *into the jar*, which a source-tree run never
            exercises.

            Run from a directory with no project.clj in it, which is the
            whole point: `lein test` runs in the repo root, where the
            source-checkout fallback reads the very same version and
            makes a jar that lost its pom.properties indistinguishable
            from one that has it (found by review)."
    (let [elsewhere (str (Files/createTempDirectory "haselnuss-no-project" (make-array FileAttribute 0)))
          {:keys [exit out err]} (run-jar "--version" :dir elsewhere)
          declared (nth (read-string (slurp "project.clj")) 2)]
      (is (= 0 exit) err)
      (is (= (str "haselnuss " declared) (str/trim out))
          "the packaged version must be project.clj's, not a duplicated literal")
      (is (not (str/includes? out "unknown"))
          "the jar must carry its own pom.properties, with no checkout to fall back to")))
  (testing "AC #3: a valid document exits zero and writes its output --
            a rich one, so front matter, the flexmark extensions, the
            bibliography loader and both directive paths are all
            exercised through the packaged jar"
    (let [dir (fixture-dir! rich-document)
          {:keys [exit err]} (run-jar (str (io/file dir "doc.hdoc")))
          html (slurp (io/file dir "doc.html"))]
      (is (= 0 exit) err)
      (is (str/includes? html "<title>Packaged</title>") "yaml front matter")
      (is (str/includes? html "<figcaption>Figure 1.1: A hazel tree.</figcaption>") "figure")
      (is (str/includes? html "<caption>Table 1.1: A table</caption>") "table extension")
      (is (str/includes? html "class=\"footnotes\"") "footnote extension")
      (is (str/includes? html "\\[E = mc^2\\]") "display math")
      (is (str/includes? html "Knuth, D. (1984). The TeXbook.") "bibliography loader")
      (is (str/includes? html "<strong>Theorem 1</strong>") "degraded directive")
      (is (str/includes? html "<details") "registry-native directive")
      (is (not (str/includes? html "??")) "nothing dangled")))
  (testing "AC #3: a document with an unrecoverable build error exits
            non-zero and writes nothing"
    (let [dir (fixture-dir! ":::{widget}\nx\n:::\n")
          {:keys [exit err]} (run-jar (str (io/file dir "doc.hdoc")))]
      (is (= 1 exit))
      (is (str/includes? err "haselnuss: error:"))
      (is (not (.exists (io/file dir "doc.html"))))))
  (testing "the LaTeX target works from the jar too -- AOT is where a
            namespace only reachable through one branch goes missing --
            and its output compiles under a real pdflatex"
    (let [dir (fixture-dir! rich-document)
          {:keys [exit err]} (run-jar (str (io/file dir "doc.hdoc"))
                                      "--target" "latex" "--computed-numbers")
          tex (slurp (io/file dir "doc.tex"))]
      (is (= 0 exit) err)
      (is (str/includes? tex "\\documentclass{article}"))
      (is (str/includes? tex "\\begin{longtable}") "table extension")
      (is (str/includes? tex "\\footnote{") "footnote extension")
      (is (str/includes? tex "\\begin{hnunnumbered}{Theorem 1}") "registry-native directive")
      (is (str/includes? tex "\\textbf{Details}") "degraded directive")
      (let [{:keys [exit out]} (shell/sh "pdflatex" "-interaction=nonstopmode" "-halt-on-error"
                                         "doc.tex" :dir dir)]
        (is (zero? exit) (str "the packaged jar's .tex must compile, pdflatex output:\n" out))))))
