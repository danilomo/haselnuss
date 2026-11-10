(ns haselnuss.list-start-test
  "TASK-129: an ordered list's CommonMark start number is authored content and
  must survive into the AST and every emitter that can express it."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.ast :as ast]
            [haselnuss.emit.hdoc :as hdoc]
            [haselnuss.emit.html :as html]
            [haselnuss.emit.latex :as latex]
            [haselnuss.parser :as parser])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:private src "5. Starts here\n6. Then this\n")

(deftest start-number-test
  (let [doc (parser/parse src)
        lst (first (:blocks doc))]
    (testing "the parser records a start other than 1, and the AST validates"
      (is (= 5 (:start lst)))
      (is (ast/valid? ast/Block lst)))
    (testing "a list starting at 1 records no :start"
      (is (not (contains? (first (:blocks (parser/parse "1. a\n2. b\n"))) :start))))
    (testing "HTML emits start=N, and omits it for 1 and for bullet lists"
      (is (re-find #"<ol start=\"5\">" (html/emit-document doc)))
      (is (not (re-find #"start=" (html/emit-document (parser/parse "1. a\n")))))
      (is (not (re-find #"start=" (html/emit-document (parser/parse "- a\n"))))))
    (testing "LaTeX sets the enumi counter to N-1"
      (is (re-find #"\\begin\{enumerate\}\n\\setcounter\{enumi\}\{4\}\n\\item Starts here"
                   (latex/emit-document doc)))
      (is (not (re-find #"setcounter" (latex/emit-document (parser/parse "1. a\n"))))))
    (testing "hdoc re-spells the list from its start and round-trips"
      (is (= src (hdoc/emit-document doc)))
      (is (= doc (parser/parse (hdoc/emit-document doc)))))))

(def ^:private nested-src
  "3. outer three\n   7. inner seven\n   8. inner eight\n4. outer four\n\n0. zero lead\n1. zero next\n")

(deftest latex-nested-and-zero-start-test
  (let [tex (latex/emit-document (parser/parse nested-src))]
    (testing "a nested ordered list sets enumii, not enumi"
      (is (re-find #"\\setcounter\{enumi\}\{2\}" tex))
      (is (re-find #"\\setcounter\{enumii\}\{6\}" tex)))
    (testing "an itemize between ordered lists does not advance the depth"
      (let [t (latex/emit-document (parser/parse "- bullet\n  5. inner\n"))]
        (is (re-find #"\\setcounter\{enumi\}\{4\}" t))))))

(deftest latex-start-numbers-compile-test
  (let [dir (Files/createTempDirectory "haselnuss-list-start" (make-array FileAttribute 0))
        tex-file (io/file (str dir) "doc.tex")
        tex (latex/emit-document (parser/parse "3. outer three\n   7. inner seven\n   8. inner eight\n4. outer four\n"))
        zero (latex/emit-document (parser/parse "0. zero lead\n1. zero next\n"))
        run (fn [t]
              (spit tex-file t)
              (let [r (shell/sh "pdflatex" "-interaction=nonstopmode" "-halt-on-error" "doc.tex" :dir (str dir))]
                (is (zero? (:exit r)) (:out r))
                (:out (shell/sh "pdftotext" "-layout" (str (io/file (str dir) "doc.pdf")) "-"))))]
    (testing "nested numbering in the compiled PDF"
      (let [text (run tex)]
        (is (re-find #"3\.\s+outer three" text))
        (is (re-find #"\(g\)\s+inner seven" text))
        (is (re-find #"\(h\)\s+inner eight" text))
        (is (re-find #"outer four" text))
        (is (re-find #"4\.\s+outer four" text))))
    (testing "a list starting at 0 prints 0"
      (is (re-find #"0\.\s+zero lead" (run zero))))))
