(ns haselnuss.source-position-test
  "TASK-143: a duplicate-id, dangling-cross-reference or unknown-citation
  warning prints `path:line` for the node it concerns -- including a node
  written in an `!include`d file or an overlay file -- while the position
  itself stays metadata, so it is invisible to equality and to the json and
  hdoc targets."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.ast :as ast]
            [haselnuss.cli :as cli]
            [haselnuss.emit.hdoc :as hdoc]
            [haselnuss.json :as json]
            [haselnuss.parser :as parser]
            [haselnuss.resolver :as resolver]
            [haselnuss.transform :as transform]
            [haselnuss.walk :as walk])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-dir []
  (str (Files/createTempDirectory "haselnuss-source-position" (make-array FileAttribute 0))))

(defn- nodes
  "Every AST node (map with a `:t`) in `x`, in document order."
  [x]
  (cond-> (into [] (mapcat nodes) (cond (map? x) (vals x) (sequential? x) x :else nil))
    (and (map? x) (:t x)) (->> (cons x) vec)))

(defn- find-node [doc pred] (first (filter pred (nodes (:blocks doc)))))

(def ^:private source
  (str "---\ntitle: T\n---\n"            ; lines 1-3
       "\n"                              ; 4
       "# Intro {#sec:a}\n"              ; 5
       "\n"                              ; 6
       "See @sec:a, @sec:nope and [@ghost].\n" ; 7
       "\n"                              ; 8
       "![cap](x.png){#fig:a}\n"         ; 9
       "\n"                              ; 10
       "::: {note #n1}\n"                ; 11
       "hello [t]{#sp}\n"                ; 12
       ":::\n"                           ; 13
       "\n"                              ; 14
       "$$ x $$ {#eq:one}\n"             ; 15
       "\n"                              ; 16
       "| a | b |\n|---|---|\n| 1 | 2 |\n" ; 17-19
       ": Cap @fig:zzz {#tbl:x}\n"))     ; 20

(deftest parser-records-positions-test
  (let [doc (parser/parse source {:source "a.hdoc"})
        at (fn [pred] (ast/source (find-node doc pred)))]
    (is (= {:file "a.hdoc" :line 5} (at #(and (= :section (:t %)) (= "sec:a" (get-in % [:attr :id]))))))
    (is (= {:file "a.hdoc" :line 7} (at #(= "sec:nope" (:label %)))))
    (is (= {:file "a.hdoc" :line 7} (at #(= :cite (:t %)))))
    (is (= {:file "a.hdoc" :line 9} (at #(= :figure (:t %)))))
    (is (= {:file "a.hdoc" :line 11} (at #(= :directive (:t %)))))
    (is (= {:file "a.hdoc" :line 12} (at #(= :span (:t %)))))
    (is (= {:file "a.hdoc" :line 15} (at #(= :math-block (:t %)))))
    (is (= {:file "a.hdoc" :line 17} (at #(= :table (:t %)))))
    (testing "a cross-reference in a table caption is placed on the caption's own line"
      (is (= {:file "a.hdoc" :line 20} (at #(= "fig:zzz" (:label %)))))))
  (testing "without :source the line is recorded, the file is not"
    (is (= {:line 5} (ast/source (first (:blocks (parser/parse source))))))))

(deftest crlf-and-bom-positions-test
  (testing "TASK-162 AC #4: CRLF line endings and a BOM leave every node on its LF line"
    (let [lf (parser/parse source {:source "a.hdoc"})
          lines (fn [doc] (mapv #(:line (ast/source %)) (nodes (:blocks doc))))
          crlf (str/replace source "\n" "\r\n")]
      (is (some some? (lines lf)))
      (doseq [s [crlf (str "\uFEFF" source) (str "\uFEFF" crlf)]]
        (is (= (lines lf) (lines (parser/parse s {:source "a.hdoc"}))))))))

(deftest book-nodes-record-positions-test
  (testing "TASK-152 AC #5: a :book-ref and a :book-quote carry their
            file and line like a :cross-ref and a :directive do, front
            matter and directive nesting included"
    (let [src (str "---\ntitle: T\n---\n"                   ; 1-3
                   "\n"                                     ; 4
                   "Prose and\n"                            ; 5
                   "a link @{bible:romanos#14:8}.\n"        ; 6
                   "\n"                                     ; 7
                   "!quote bible:romanos#14:8-9\n"          ; 8
                   ":::{note}\n"                            ; 9
                   "!quote Bad:Ref\n"                       ; 10
                   ":::\n")                                 ; 11
          doc (parser/parse src {:source "b.hdoc"})
          at (fn [pred] (ast/source (find-node doc pred)))]
      (is (= {:file "b.hdoc" :line 6} (at #(= :book-ref (:t %)))))
      (is (= {:file "b.hdoc" :line 8} (at #(= "bible:romanos#14:8-9" (:ref %)))))
      (is (= {:file "b.hdoc" :line 10} (at #(= "Bad:Ref" (:ref %)))))
      (testing "and the positions stay invisible to equality and JSON"
        (is (= doc (parser/parse src)))
        (is (= doc (json/json->ast (json/->json doc))))))))

(def ^:private include-source
  (str "---\ntitle: T\n---\n"       ; 1-3
       "\n"                         ; 4
       "Prose and\n"                ; 5
       "!include gone.hdoc\n"       ; 6
       ":::{note}\n"                ; 7
       "!include self.hdoc\n"       ; 8
       ":::\n"))                    ; 9

(deftest include-nodes-record-positions-test
  (testing "TASK-163 AC #1: an :include carries its !include line like a
            :book-quote does, front matter and directive nesting included"
    (let [doc (parser/parse include-source {:source "c.hdoc"})
          at (fn [src] (ast/source (find-node doc #(= src (:src %)))))]
      (is (= {:file "c.hdoc" :line 6} (at "gone.hdoc")))
      (is (= {:file "c.hdoc" :line 8} (at "self.hdoc")))
      (testing "without :source the line is recorded, the file is not"
        (is (= {:line 6} (ast/source (find-node (parser/parse include-source)
                                                #(= "gone.hdoc" (:src %))))))))))

(deftest include-positions-are-invisible-test
  (testing "TASK-163 AC #3: JSON and .hdoc output do not see an include's position"
    (let [doc (parser/parse include-source {:source "c.hdoc"})
          bare (parser/parse include-source)]
      (is (= doc bare))
      (is (= (json/->json doc) (json/->json bare)))
      (is (not (str/includes? (json/->json doc) "c.hdoc")))
      (is (= doc (json/json->ast (json/->json doc))))
      (is (= (hdoc/emit-document doc) (hdoc/emit-document bare)))
      (is (not (str/includes? (hdoc/emit-document doc) "c.hdoc")))
      (is (= doc (parser/parse (hdoc/emit-document doc)))))))

(deftest include-diagnostics-name-the-include-line-test
  (let [dir (temp-dir)
        file (fn [n] (str (io/file dir n)))
        top (file "top.hdoc")
        load-doc (fn [f] (parser/parse (slurp f) {:source (str f)}))
        expand (fn [path]
                 (resolver/expand-includes (load-doc path)
                                           {:base-dir dir :source-path path :load load-doc}))]
    (spit (file "self.hdoc") "Self.\n\n!include self.hdoc\n")
    (spit (file "broken.hdoc") "---\ntags: [unclosed\n---\n\nx\n")
    (spit top (str "# Top\n"               ; 1
                   "\n"                    ; 2
                   "!include gone.hdoc\n"  ; 3
                   "\n"                    ; 4
                   "!include self.hdoc\n")) ; 5
    (let [{:keys [warnings]} (expand top)
          by-type (group-by :type warnings)]
      (testing "an unreadable include names the including file and line"
        (let [w (first (:unreadable-include by-type))]
          (is (= 3 (:source-line w)))
          (is (= top (:source-file w)))
          (is (str/starts-with? (:message w) (str top ":3: cannot include \"gone.hdoc\"")))))
      (testing "an include cycle names the !include that closes it, in the file it is written in"
        (let [w (first (:include-cycle by-type))]
          (is (= 3 (:source-line w)))
          (is (= (file "self.hdoc") (:source-file w)))
          (is (str/starts-with? (:message w) (str (file "self.hdoc") ":3: include cycle: "))))))
    (testing "an unparseable include names the !include line too"
      (spit (file "bad-top.hdoc") "Intro.\n\n!include broken.hdoc\n")
      (let [e (try (expand (file "bad-top.hdoc")) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (= :haselnuss.resolver/unparseable-include (:type (ex-data e))))
        (is (= 3 (:source-line (ex-data e))))
        (is (= (file "bad-top.hdoc") (:source-file (ex-data e))))
        (is (str/starts-with? (ex-message e) (str (file "bad-top.hdoc") ":3: cannot include \"broken.hdoc\"")))
        (testing "and keeps the parser's own located error as its cause"
          (let [cause (ex-cause e)]
            (is (instance? clojure.lang.ExceptionInfo cause))
            (is (= (file "broken.hdoc") (:source-file (ex-data cause))))
            (is (= 2 (:source-line (ex-data cause))))))))
    (testing "through a chain, the error lands at the !include that reached the broken
              file (mid.hdoc), not at the root's"
      (spit (file "mid.hdoc") "Mid.\n\nMore.\n\n!include broken.hdoc\n")
      (spit (file "chain-top.hdoc") "Top.\n\n!include mid.hdoc\n")
      (let [e (try (expand (file "chain-top.hdoc")) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (= :haselnuss.resolver/unparseable-include (:type (ex-data e))))
        (is (= (file "mid.hdoc") (:source-file (ex-data e))))
        (is (= 5 (:source-line (ex-data e))))
        (is (str/starts-with? (ex-message e) (str (file "mid.hdoc") ":5: cannot include \"broken.hdoc\"")))))
    (testing "an include with no position reports as before"
      (let [{:keys [warnings]} (resolver/expand-includes
                                {:meta {} :blocks [{:t :include :src "gone.hdoc"}]}
                                {:base-dir dir :load load-doc})
            w (first warnings)]
        (is (str/starts-with? (:message w) "cannot include \"gone.hdoc\""))
        (is (not (contains? w :source-line)))))))

(deftest transform-partial-document-names-include-lines-test
  (testing "TASK-163 AC #2: --transform's partial-document report names each !include's line"
    (let [rules [{:select {:t :para} :transform (fn [node _] node)}]
          {:keys [diagnostics]} (transform/transform-document
                                 (parser/parse include-source {:source "c.hdoc"}) rules)
          w (first (filter #(= :transform-partial-document (:type %)) diagnostics))]
      (is (= ["gone.hdoc" "self.hdoc"] (:includes w)))
      (is (= [{:src "gone.hdoc" :source-line 6 :source-file "c.hdoc"}
              {:src "self.hdoc" :source-line 8 :source-file "c.hdoc"}]
             (:locations w)))
      (is (str/ends-with? (:message w) "never saw: gone.hdoc (c.hdoc:6), self.hdoc (c.hdoc:8)")))))

(deftest positions-are-invisible-test
  (let [doc (parser/parse source {:source "a.hdoc"})
        bare (parser/parse source)]
    (testing "equality, JSON and hdoc output do not see them"
      (is (= doc bare))
      (is (= (json/->json doc) (json/->json bare)))
      (is (not (str/includes? (json/->json doc) "a.hdoc")))
      (is (= (hdoc/emit-document doc) (hdoc/emit-document bare)))
      (is (not (str/includes? (hdoc/emit-document doc) "a.hdoc")))
      (is (nil? (ast/source (json/json->ast (json/->json doc))))))))

(deftest warnings-print-positions-test
  (let [doc (parser/parse (str source "\n# Again {#sec:a}\n") {:source "a.hdoc"})
        {:keys [diagnostics]} (resolver/resolve-document doc {:bibliography {}})
        by-type (group-by :type diagnostics)
        only (fn [t] (first (get by-type t)))]
    (testing "dangling cross-reference"
      (let [w (first (filter #(= "sec:nope" (:label %)) (:dangling-cross-ref by-type)))]
        (is (= 7 (:source-line w)))
        (is (= "a.hdoc" (:source-file w)))
        (is (str/starts-with? (:message w) "a.hdoc:7: dangling cross-reference: "))))
    (testing "unknown citation"
      (let [w (only :dangling-citation)]
        (is (= 7 (:source-line w)))
        (is (= "a.hdoc:7: dangling citation: no bibliography entry for key \"ghost\"" (:message w)))))
    (testing "duplicate id names every node that uses it"
      (let [w (only :duplicate-id)]
        (is (= "duplicate id \"sec:a\": used by 2 nodes (section at a.hdoc:5, section at a.hdoc:22)"
               (:message w)))
        (is (= [5 22] (mapv :source-line (:locations w)))))))
  (testing "parsed without :source: wording unchanged, line still in the data"
    (let [{:keys [diagnostics]} (resolver/resolve-document (parser/parse source) {:bibliography {}})
          w (first (filter #(= :dangling-citation (:type %)) diagnostics))]
      (is (= "dangling citation: no bibliography entry for key \"ghost\"" (:message w)))
      (is (= 7 (:source-line w)))
      (is (not (contains? w :source-file))))))

(deftest included-file-warning-test
  (let [dir (temp-dir)
        file (fn [n] (str (io/file dir n)))]
    (spit (file "chapter.hdoc") "# Chapter {#sec:dup}\n\nok\n\nSee @fig:missing here.\n")
    (spit (file "top.hdoc") "# Top {#sec:dup}\n\n!include chapter.hdoc\n")
    (let [top (file "top.hdoc")
          doc (parser/parse (slurp top) {:source top})
          {:keys [diagnostics document]}
          (resolver/resolve-document
           doc {:bibliography {}
                :includes {:base-dir dir
                           :source-path top
                           :load (fn [f] (parser/parse (slurp f) {:source (str f)}))}})
          ch (file "chapter.hdoc")]
      (testing "the positions of an included file survive expansion, naming that file"
        (let [w (first (filter #(= :dangling-cross-ref (:type %)) diagnostics))]
          (is (= (str ch ":5: dangling cross-reference: no target with id \"fig:missing\"")
                 (:message w)))))
      (testing "a duplicate across the including and the included file names both"
        (let [w (first (filter #(= :duplicate-id (:type %)) diagnostics))]
          (is (= (str "duplicate id \"sec:dup\": used by 2 nodes (section at " top ":1, section at " ch ":1)")
                 (:message w)))))
      (testing "the expanded document still validates"
        (is (ast/valid? ast/Document document))))))

(deftest overlay-and-rule-keep-positions-test
  (testing "a node appended by an overlay keeps its overlay-file position"
    (let [base (parser/parse "# Base {#sec:base}\n\ntext\n" {:source "base.hdoc"})
          overlay (:blocks (parser/parse "::: {append target=sec:base}\nSee @fig:late.\n:::\n"
                                         {:source "over.hdoc"}))
          {:keys [diagnostics]} (resolver/resolve-document base {:overlay-sources [overlay]})
          w (first (filter #(= :dangling-cross-ref (:type %)) diagnostics))]
      (is (= 2 (:source-line w)))
      (is (= "over.hdoc" (:source-file w)))))
  (testing "a transformation rule that edits a node leaves its descendants' positions alone"
    (let [doc (parser/parse "# T\n\nSee @fig:late.\n" {:source "d.hdoc"})
          rule {:select {:t :para}
                :transform (fn [node _] (update node :attr (fnil identity {:classes [] :props {}})))}
          rule2 {:select {:t :section}
                 :transform (fn [node _] (assoc-in node [:attr :classes] ["x"]))}
          {out :document} (transform/transform-document doc [rule rule2])
          {:keys [diagnostics]} (resolver/resolve-document out)
          w (first (filter #(= :dangling-cross-ref (:type %)) diagnostics))]
      (is (= "d.hdoc:3: dangling cross-reference: no target with id \"fig:late\"" (:message w)))
      (is (= {:file "d.hdoc" :line 1} (ast/source (first (:blocks out))))))))

(deftest cli-prints-position-test
  (let [dir (temp-dir)
        file (fn [n] (str (io/file dir n)))]
    (spit (file "chapter.hdoc") "# Chapter\n\nSee @fig:missing here.\n")
    (spit (file "top.hdoc") "# Top\n\n!include chapter.hdoc\n")
    (let [err (java.io.StringWriter.)
          out (java.io.StringWriter.)
          status (binding [*err* err *out* out]
                   (cli/main* [(file "top.hdoc") "--target" "html" "--output" (file "out.html")]))]
      (is (zero? status))
      (is (str/includes? (str err) (str (file "chapter.hdoc") ":3: dangling cross-reference")) (str err)))))

(deftest rewrite-preserves-metadata-test
  (testing "the walker's own rebuild keeps a node's position"
    (let [doc (parser/parse "# T\n\ntext\n" {:source "d"})
          walker {:table walk/table :scope walk/every-ref-kind
                  :rewrite-fn (fn [_ node] [node])}
          [out] (walk/rewrite walker ::ast/document doc)]
      (is (= {:file "d" :line 1} (ast/source (first (:blocks out))))))))
