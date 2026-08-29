(ns haselnuss.reveal-example-test
  "The TASK-92 worked example, kept building -- `haselnuss.example-test`'s
  own structure, applied to the OTHER committed example.

  `examples/reveal.clj` + `examples/reveal-demo.hdoc` are the trusted half
  of the sidecar-extension story `examples/hazelnuts.hdoc` does not cover:
  a `reveal` directive whose `:html` rendering needs `--trusted-extensions`
  (an inline `<script>` spliced in via `haselnuss.ext/raw`, since an
  ordinary escaped hiccup child cannot carry one intact) and whose `:latex`
  rendering is a distinct, print-appropriate fallback -- the question
  directly followed by its answer, nothing hidden, no `:raw` involved at
  all on that half. `haselnuss.cli-test/trusted-extensions-test` already
  covers the GENERAL mechanism end to end through the real CLI, against a
  throwaway `widget` fixture; this namespace is the regression guard around
  the COMMITTED example specifically, the same division of labour
  `haselnuss.example-test` already has against `haselnuss.cli-test`'s own
  `sidecar-extensions-test`/`callout` fixture.

  Exercises `haselnuss.cli/build` directly (not `cli/main*`, and not a
  `lein run --` subprocess) -- the same fast, in-process path
  `haselnuss.example-test` itself uses, and the one that lets the
  untrusted-build test below inspect the thrown `ex-info` directly rather
  than parsing it back out of captured stderr text."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.cli :as cli])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- copy-example!
  "Copies the committed `reveal.clj`/`reveal-demo.hdoc` pair into a fresh
  temp directory, mirroring `haselnuss.example-test/build!`'s own
  resource-copy idiom: reading them off the classpath (`examples/` is a
  test-only resource path, `project.clj`) keeps this independent of the
  runner's working directory. Returns the directory as a string."
  []
  (let [dir (str (Files/createTempDirectory "haselnuss-reveal-example" (make-array FileAttribute 0)))]
    (doseq [f ["reveal-demo.hdoc" "reveal.clj"]]
      (io/copy (io/reader (io/resource f)) (io/file dir f)))
    dir))

(defn- build!
  "`cli/build` over `dir`'s own copied `reveal-demo.hdoc`, loading its
  sidecar `reveal.clj` via `:extensions`, on `target` (a string, `cli/
  build`'s own convention), `trusted?` forwarded as `:trusted-extensions`.
  Returns `{:output-path :diagnostics :output}`, the last being the text
  actually written to disk -- `haselnuss.cli-test/build!`'s own shape."
  [dir target trusted?]
  (let [ext (str (io/file dir "reveal.clj"))
        opts (cond-> {:input (str (io/file dir "reveal-demo.hdoc")) :target target :extensions [ext]}
               trusted? (assoc :trusted-extensions true))
        result (cli/build opts)]
    (assoc result :output (slurp (:output-path result)))))

(deftest reveal-example-converts-cleanly-test
  (let [dir (copy-example!)]
    (testing "AC #5: the committed example builds cleanly -- no
              diagnostics at all -- on the html target with
              --trusted-extensions, and the reveal widget survived the
              whole pipeline: its visible shell, both authored questions
              and answers, AND the one inline <script> that needs
              :trusted, spliced in verbatim rather than entity-escaped"
      (let [{:keys [output diagnostics]} (build! dir "html" true)]
        (is (= [] diagnostics) (pr-str diagnostics))
        (is (str/includes? output "Before you plant") "the section heading survived")
        (is (str/includes? output "<div class=\"reveal\">"))
        (is (str/includes? output
                           (str "<p class=\"reveal-question\">Why does a single hazel tree in an "
                                "otherwise hazel-free garden set no nuts at all?</p>")))
        (is (str/includes? output "<button class=\"reveal-button\" type=\"button\">Show answer</button>"))
        (is (str/includes? output "<p class=\"reveal-answer\" hidden=\"hidden\">Hazel is wind-pollinated"))
        (is (str/includes? output "What planting distance is the usual compromise")
            "the second reveal, not just the first, survived")
        (is (str/includes? output "<script>") "AC #2: the trusted raw <script> reached the output")
        (is (str/includes? output "document.currentScript.parentElement"))
        (testing "AC #2's concrete evidence: the script's own querySelector
                  arguments still carry a REAL double quote, not the
                  &quot; ordinary (untrusted) hiccup escaping would have
                  produced for the identical text -- proving :raw actually
                  did something here, not merely that it was called"
          (is (str/includes? output "querySelector(\".reveal-button\")"))
          (is (str/includes? output "querySelector(\".reveal-answer\")"))
          (is (not (str/includes? output "&quot;"))))))
    (testing "AC #3/#5: and on the latex target with --trusted-extensions
              -- a DISTINCT, print-appropriate fallback (the question
              directly followed by its answer), not the html widget
              down-rendered: no <script>, no button, nothing hidden"
      (let [{:keys [output diagnostics]} (build! dir "latex" true)]
        (is (= [] diagnostics) (pr-str diagnostics))
        (is (str/includes? output "Before you plant") "the section heading survived")
        (is (str/includes? output
                           (str "\\textbf{Q: }Why does a single hazel tree in an otherwise "
                                "hazel-free garden set no nuts at all?")))
        (is (str/includes? output
                           (str "\\textbf{A: }Hazel is wind-pollinated and self-incompatible, so it "
                                "needs a second, different cultivar flowering at the same time to "
                                "set fruit.")))
        (is (str/includes? output "\\textbf{Q: }What planting distance is the usual compromise")
            "the second reveal, not just the first, survived")
        (is (not (str/includes? output "<script>")) "no widget artifact on a target with no clicking")
        (is (not (str/includes? output "reveal-button")))
        (testing "and it actually compiles under a real pdflatex, not just
                  string-matches -- the same standard haselnuss.example-
                  test already holds examples/hazelnuts.hdoc to"
          (spit (io/file dir "reveal-demo.tex") output)
          (let [{:keys [exit out err]} (shell/sh "pdflatex" "-interaction=nonstopmode"
                                                 "-halt-on-error" "reveal-demo.tex" :dir dir)]
            (is (zero? exit) (str "the example must compile, pdflatex output:\n" out err))
            (is (.isFile (io/file dir "reveal-demo.pdf")))))))))

(deftest reveal-example-requires-trust-test
  (testing "AC #6: the identical document, loading the identical sidecar
            file, but WITHOUT --trusted-extensions, fails with the same
            ::untrusted-raw-output haselnuss.emit.html already gives a
            host-defined untrusted renderer (TASK-89) -- not a second,
            different mechanism -- and writes nothing"
    (let [dir (copy-example!)
          outcome (try [:ok (build! dir "html" false)]
                       (catch Exception e [:threw (:type (ex-data e)) (ex-message e)]))]
      (is (= :threw (first outcome)) (pr-str outcome))
      (is (= :haselnuss.emit.html/untrusted-raw-output (second outcome)))
      (is (str/includes? (nth outcome 2) "not :trusted"))
      (is (str/includes? (nth outcome 2) "reveal")
          "the error names the offending directive")
      (is (not (.exists (io/file dir "reveal-demo.html")))))))
