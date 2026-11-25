(ns ^:slow ^:shares-target haselnuss.native-test
  "Fixtures for the GraalVM native binary (TASK-126).

  `^:shares-target` (TASK-302): the differential tests read
  `target/uberjar/haselnuss.jar`, which `haselnuss.uberjar-test` deletes
  and rebuilds, so `bin/test` runs the two in one shard. `^:slow`: outside
  the fast gate.

  The reason this namespace exists at all is that `lein test` only ever
  runs on the JVM, so it structurally cannot see any of the ways a
  native image diverges from the jar it was built from: a class whose
  static initializer ran at image build time and froze the wrong value
  into the heap, a resource that was never included, a reflective call
  the tracing agent never saw. Those are exactly the failures a
  closed-world build introduces, and none of them is reachable from an
  in-process test.

  So this shells out to the real binary, the same trade
  `haselnuss.uberjar-test` already makes for the jar and
  `haselnuss.emit.latex-test` makes for `pdflatex`.

  Unlike `uberjar-test`, this namespace does NOT build its artifact.
  Producing the binary needs a GraalVM `native-image` that the README
  does not require and CI does not install, and a build takes minutes.
  Every test here therefore *skips* when no binary is present rather
  than failing, so the suite stays green on a machine that has never
  heard of GraalVM. `binary-present?` is the single gate; see its
  docstring for why an absent binary is not a failure."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.cli :as cli])
  (:import (java.nio.file Files LinkOption)
           (java.nio.file.attribute FileAttribute)))

(def ^:private binary-path
  "Where `bin/native` puts the binary.

  `dist/`, not `target/` (TASK-127): Leiningen's `:auto-clean` deletes
  the whole `target/` tree before every jar/uberjar build, and
  `haselnuss.uberjar-test` shells out to `lein uberjar` on every `lein
  test` run -- so a binary kept under `target/` was destroyed by the very
  suite this namespace belongs to. `dist/` is gitignored, so a
  verification run still cannot sweep a 100MB artifact into a commit the
  way TASK-50.5 records happening twice before."
  "dist/haselnuss")

(def ^:private jar-path
  "The jar the binary was built from -- the differential test below is
  only meaningful against this exact artifact."
  "target/uberjar/haselnuss.jar")

(defn- binary-present?
  "True when a native binary exists to test.

  False is not a failure. `native-image` is not in this project's stated
  requirements (the README asks for a JDK and Leiningen), it is not
  installed in CI, and a build takes minutes -- so a suite that failed
  without it would fail for everyone who has not opted in, which is the
  opposite of what a gate is for. When this returns false the tests
  below assert nothing and say so."
  []
  (.canExecute (io/file binary-path)))

(defn- jar-present? []
  (.isFile (io/file jar-path)))

(defn- run-binary
  "Runs the native binary with `args`, returning `{:exit :out :err}`."
  [& args]
  (apply shell/sh (.getAbsolutePath (io/file binary-path)) args))

(defn- run-jar
  "Runs the jar the binary was built from, with the same `args`, so the
  two can be compared output-for-output."
  [& args]
  (apply shell/sh "java" "-jar" (.getAbsolutePath (io/file jar-path)) args))

(def ^:private rich-document
  "A document reaching the parts of the pipeline a native image is most
  likely to break: YAML front matter (SnakeYAML, whose build-time
  initialization clj-yaml declares upstream), all three flexmark
  extensions, this parser's own synthetic inline nodes (inline math,
  cross-references, citations, highlights -- the `proxy` classes doc-4
  predicted would be the blocker), and a citation resolved against a
  bibliography read off the filesystem."
  (str "---\ntitle: Native\nlang: en\nbibliography: refs.json\n---\n\n"
       "# Introduction {#sec:intro}\n\n"
       "Prose with a footnote[^n], inline math $x^2$ and ==a highlight==.\n\n"
       "[^n]: The footnote body.\n\n"
       "See @sec:intro and [@knuth1984].\n\n"
       "$$\nE = mc^2\n$$ {#eq:mass}\n\n"
       "| A | B |\n|---|---|\n| 1 | 2 |\n: A table {#tbl:data}\n\n"
       ":::{theorem #thm:main}\nAll good things end.\n:::\n\n"
       "Also @thm:main, @tbl:data and @eq:mass.\n"))

(def ^:private bibliography
  "[{\"id\":\"knuth1984\",\"author\":[{\"family\":\"Knuth\",\"given\":\"Donald\"}],\"issued\":{\"date-parts\":[[1984]]},\"title\":\"The TeXbook\"}]")

(defn- fixture-dir!
  "A fresh temp directory holding the document and its bibliography.
  Temp, not the source tree, so nothing here can be swept into a commit."
  []
  (let [dir (str (Files/createTempDirectory "haselnuss-native-test"
                                            (make-array FileAttribute 0)))]
    (spit (io/file dir "doc.hdoc") rich-document)
    (spit (io/file dir "refs.json") bibliography)
    dir))

(deftest native-too-deep-nesting-test
  (if-not (binary-present?)
    (is true "no native binary built")
    (testing "TASK-306: nesting past the native stack is one error line, not a trace"
      (let [dir (Files/createTempDirectory "haselnuss-deep" (make-array java.nio.file.attribute.FileAttribute 0))
            in (str (.resolve dir "q.hdoc"))
            _ (spit in (str (apply str (repeat 9000 "> ")) "a b\n"))
            {:keys [exit err]} (run-binary "-t" "html" "-o" "-" in)]
        (is (= 1 exit))
        (is (str/includes? err "haselnuss: error: the document is nested too deeply"))
        (is (= 1 (count (str/split-lines err))))))))

(deftest native-binary-test
  (if-not (binary-present?)
    (testing "no native binary built -- skipping (see this namespace's docstring)"
      (is true "native-image is opt-in; absence of a binary is not a failure"))
    (do
      (testing "the binary runs at all, which is the whole question a
                closed-world build raises: Clojure's runtime needs its
                class-initialization split drawn correctly or the image
                dies in clojure.lang.Namespace's static initializer
                before reaching -main"
        (let [{:keys [exit out err]} (run-binary "--help")]
          (is (= 0 exit) err)
          (is (str/includes? out "Usage: haselnuss"))))
      (testing "--version reads a classpath resource (pom.properties),
                which native-image excludes unless it was traced"
        (let [{:keys [exit out err]} (run-binary "--version")]
          (is (= 0 exit) err)
          (is (str/starts-with? (str/trim out) "haselnuss "))
          (is (not (str/includes? out "unknown"))
              "the resource must have been included in the image")))
      (testing "a rich document converts, exercising the flexmark
                extensions and this parser's own synthetic inline nodes"
        (let [dir (fixture-dir!)
              {:keys [exit err]} (run-binary (str (io/file dir "doc.hdoc")))
              html (slurp (io/file dir "doc.html"))]
          (is (= 0 exit) err)
          (is (str/includes? html "<title>Native</title>") "yaml front matter")
          (is (str/includes? html "<table") "table extension")
          ;; The highlight carries its colour class, so match the opening
          ;; tag rather than a bare `<mark>` that the emitter never writes.
          (is (str/includes? html "<mark class=\"highlight") "highlight node")
          (is (str/includes? html "class=\"math inline\"") "inline math node")
          (is (str/includes? html "<a href=\"#sec:intro\">") "cross-reference node")))
      (when (jar-present?)
        (testing "the binary and the jar it was built from agree exactly.
                  This is the bar that matters: a native image which runs
                  but parses differently is worse than no binary at all,
                  because nothing else in the suite would catch it."
          (let [dir (fixture-dir!)
                doc (str (io/file dir "doc.hdoc"))]
            (doseq [target ["html" "json" "hdoc"]]
              (let [native (run-binary doc "--target" target "-o" "-")
                    jvm (run-jar doc "--target" target "-o" "-")]
                (is (= (:exit jvm) (:exit native))
                    (str target ": exit codes must match"))
                (is (= (:out jvm) (:out native))
                    (str target ": stdout must be byte-identical to the jar's"))))
            (testing "and they agree on the error path too, where a
                      missing resource or an uninitialized class would
                      otherwise show up as a different message"
              (let [native (run-binary (str (io/file dir "no-such-file.hdoc")))
                    jvm (run-jar (str (io/file dir "no-such-file.hdoc")))]
                (is (= (:exit jvm) (:exit native)))
                (is (= (:err jvm) (:err native)))))))))))

(defn- library-fixture-dir!
  "A fresh temp directory holding a filesystem library `lib` (two books,
  one with a bibliography of its own) under `lib/`, a `libraries.yaml`
  naming it with a URL template, and a `host.hdoc` that links to and
  quotes it (TASK-157)."
  []
  (let [dir (str (Files/createTempDirectory "haselnuss-native-libraries"
                                            (make-array FileAttribute 0)))]
    (.mkdirs (io/file dir "lib" "creeds"))
    (spit (io/file dir "lib" "creeds" "nicene.hdoc")
          (str "---\ntitle: Nicene Creed\nbibliography: refs.json\n---\n\n"
               "# The Creed {#sec:creed}\n\nWe believe, as [@knuth1984] did.\n"))
    (spit (io/file dir "lib" "creeds" "refs.json") bibliography)
    (spit (io/file dir "lib" "apostles.hdoc") "---\ntitle: Apostles' Creed\n---\n\nI believe.\n")
    (spit (io/file dir "libraries.yaml")
          (str "- name: lib\n  type: filesystem\n  path: lib\n"
               "  url: \"https://lib.example/{slug}.html#{label}\"\n"))
    (spit (io/file dir "host.hdoc")
          "# Host\n\nSee @{lib:nicene#sec:creed}.\n\n!quote lib:nicene#sec:creed\n")
    dir))

(deftest native-libraries-test
  (if-not (binary-present?)
    (testing "no native binary built -- skipping (see this namespace's docstring)"
      (is true "native-image is opt-in; absence of a binary is not a failure"))
    (let [dir (library-fixture-dir!)
          yaml (str (io/file dir "libraries.yaml"))
          host (str (io/file dir "host.hdoc"))]
      (testing "TASK-157: --libraries reaches the library namespaces, which the
                CLI never loaded before -- a missing reflect-config entry
                shows up here and nowhere else"
        (let [{:keys [exit out err]} (run-binary "--libraries" yaml host "-o" "-")]
          (is (= 0 exit) err)
          (is (str/includes? out "We believe") "the quote expanded")
          (is (str/includes? out "https://lib.example/nicene.html#sec:creed") "the URL template")
          (is (str/includes? out "Nicene Creed, Section 1") "the link text")))
      (testing "--list-books"
        (let [{:keys [exit out err]} (run-binary "--libraries" yaml "--list-books" "lib")]
          (is (= 0 exit) err)
          (is (= "lib:apostles\tApostles' Creed\nlib:creeds/nicene\tNicene Creed\n" out))))
      (testing "a book ID as input, written into the working directory"
        (let [{:keys [exit err]} (shell/sh (.getAbsolutePath (io/file binary-path))
                                           "--libraries" yaml "lib:nicene" "--target" "latex"
                                           :dir dir)]
          (is (= 0 exit) err)
          (is (.isFile (io/file dir "nicene.tex"))))
        (testing "and never over a file already there unless --output names it"
          (let [before (slurp (io/file dir "nicene.tex"))
                {:keys [exit err]} (shell/sh (.getAbsolutePath (io/file binary-path))
                                             "--libraries" yaml "lib:nicene" "--target" "latex"
                                             :dir dir)]
            (is (= 1 exit))
            (is (str/includes? err "refusing to overwrite nicene.tex") err)
            (is (= before (slurp (io/file dir "nicene.tex")))))))
      (when (jar-present?)
        (testing "and the binary agrees with the jar, byte for byte"
          (doseq [args [["--libraries" yaml host "-o" "-"]
                        ["--libraries" yaml "--list-books" "lib"]
                        ["--libraries" yaml "lib:nowhere"]]]
            (let [native (apply run-binary args)
                  jvm (apply run-jar args)]
              (is (= (:exit jvm) (:exit native)) (pr-str args))
              (is (= (:out jvm) (:out native)) (pr-str args))
              (is (= (:err jvm) (:err native)) (pr-str args)))))))))

(defn- run-main
  "`args` through `haselnuss.cli/main*` in this JVM, as `{:exit :out
  :err}` -- the JVM side of `native-check-backlinks-test`.

  In-process rather than `run-jar`: each case below is a whole library
  scan, and a cold `java -jar` per case would add a JVM start (and an
  unwarmed scan) to every one of them. `main*` is everything `-main` does
  short of `System/exit`, and the jar `haselnuss.uberjar-test` rebuilds
  on every run is these same sources. The overrides keep the real
  `$HASELNUSS_LIBRARIES` and home config out of it, which the binary
  never reads either: every case passes `--libraries`. Paths in the
  reports are shown relative to this JVM's working directory, which is
  the binary's too (`shell/sh` inherits it)."
  [& args]
  (let [err (java.io.StringWriter.)
        out (java.io.StringWriter.)
        status (binding [*err* err *out* out]
                 (cli/main* args {:library-env {}
                                  :library-home "/nonexistent/haselnuss-native-test-home"
                                  :haselnuss.cli/check-base-dir (System/getProperty "user.dir")}))]
    {:exit status :out (str out) :err (str err)}))

(def ^:private fixture-libraries
  "The fixture libraries' directory file (TASK-242): every problem
  `--check` reports, and the cross-library edges `--backlinks` lists."
  "test/fixtures/libraries/libraries.yaml")

(deftest native-check-backlinks-test
  (if-not (binary-present?)
    (testing "no native binary built -- skipping (see this namespace's docstring)"
      (is true "native-image is opt-in; absence of a binary is not a failure"))
    (testing "TASK-245 (doc-11, \"GraalVM investigation phase\"): --check and
              --backlinks print byte for byte what the JVM prints, on stdout
              and stderr, with the same exit code -- the scan, its sandboxed
              shelf scripts and both renderers all run inside the image"
      (let [clean-yaml (str (io/file (library-fixture-dir!) "libraries.yaml"))]
        (doseq [[expected-exit args]
                [;; the planted problems: 1
                 [1 ["--libraries" fixture-libraries "--check"]]
                 [1 ["--libraries" fixture-libraries "--check" "--format" "json"]]
                 [1 ["--libraries" fixture-libraries "--check" "pass" "shared"]]
                 ;; a clean library: 0
                 [0 ["--libraries" clean-yaml "--check"]]
                 [0 ["--libraries" clean-yaml "--check" "--format" "json"]]
                 ;; cannot run: 2, and a usage error is 2 as well
                 [2 ["--libraries" fixture-libraries "--check" "nosuch"]]
                 [2 ["--libraries" fixture-libraries "--check" "nosuch" "--format" "json"]]
                 [2 ["--libraries" fixture-libraries "--check" "x.hdoc"]]
                 ;; TASK-327: named books, a generated one among them, alone
                 ;; and beside a library; an ID naming no book
                 [1 ["--libraries" fixture-libraries "--check" "check:cycle-a"]]
                 [1 ["--libraries" fixture-libraries "--check" "pass" "check:links" "bible:romanos"
                     "--format" "json"]]
                 [2 ["--libraries" fixture-libraries "--check" "check:nope"]]
                 ;; grouped by passage, cross-library and dangling edges, the
                 ;; broken library's warning on stderr
                 [0 ["--libraries" fixture-libraries "--backlinks" "pass:nicene"]]
                 [0 ["--libraries" fixture-libraries "--backlinks" "pass:nicene" "--format" "json"]]
                 [0 ["--libraries" fixture-libraries "--backlinks" "pass:nicene#sec:creed"]]
                 [2 ["--libraries" fixture-libraries "--backlinks" "bible:"]]
                 [2 ["--libraries" fixture-libraries "--backlinks" "bible:" "--format" "json"]]
                 [2 ["--libraries" fixture-libraries "--backlinks" "pass:nowhere"]]]]
          (let [native (apply run-binary args)
                jvm (apply run-main args)]
            (is (= expected-exit (:exit jvm)) (str (pr-str args) " " (:err jvm)))
            (is (= (:exit jvm) (:exit native)) (pr-str args))
            (is (= (:out jvm) (:out native)) (pr-str args))
            (is (= (:err jvm) (:err native)) (pr-str args))
            (is (not (str/blank? (str (:out native) (:err native))))
                (str (pr-str args) ": the comparison must not be between two empty outputs"))))))))

(deftest native-generated-book-conversion-test
  (if-not (binary-present?)
    (testing "no native binary built -- skipping (see this namespace's docstring)"
      (is true "native-image is opt-in; absence of a binary is not a failure"))
    (testing "TASK-325: a generated book converts by its ID in the binary -- the
              shelf script runs in the sandbox -- to the default <slug>.<ext> in
              the current directory, byte for byte as the JVM writes it"
      (let [yaml (.getAbsolutePath (io/file "test/fixtures/site/libraries.yaml"))
            tmp (fn [] (.toFile (Files/createTempDirectory "haselnuss-native-generated"
                                                           (make-array FileAttribute 0))))]
        (doseq [[target ext] [["html" ".html"] ["latex" ".tex"] ["hdoc" ".hdoc"] ["json" ".json"]]]
          (let [native-dir (tmp)
                jvm-dir (tmp)
                native (run-binary "--libraries" yaml "bible:joao" "--target" target :dir native-dir)
                jvm (run-main "--libraries" yaml "bible:joao" "--target" target
                              "--output" (str (io/file jvm-dir (str "joao" ext))))
                written (io/file native-dir (str "joao" ext))]
            (is (= 0 (:exit native)) (:err native))
            (is (= 0 (:exit jvm)) (:err jvm))
            (is (.isFile written) target)
            (is (= (slurp (io/file jvm-dir (str "joao" ext))) (slurp written)) target)
            (is (str/includes? (slurp written) "Jo") target)))))))

(defn- http
  "`[status body]` of one request to `url` (a `java.net.http` client:
  this JVM's, not the binary's); a `body` is sent as
  `Content-Type: application/json`."
  [method url body]
  (let [client (java.net.http.HttpClient/newHttpClient)
        req (-> (java.net.http.HttpRequest/newBuilder (java.net.URI/create url))
                (.method method (if body
                                  (java.net.http.HttpRequest$BodyPublishers/ofString body)
                                  (java.net.http.HttpRequest$BodyPublishers/noBody)))
                (cond-> body (.header "Content-Type" "application/json"))
                (.build))
        resp (.send client req (java.net.http.HttpResponse$BodyHandlers/ofString))]
    [(.statusCode resp) (.body resp)]))

(defn- site-tree
  "`{relative-path bytes-as-vector}` of every file under the built site
  `dir` outside `.haselnuss/`: the build's own state there carries a
  random generation id, so it differs between any two builds."
  [dir]
  (let [root (.toPath (io/file dir))]
    (into (sorted-map)
          (for [^java.io.File f (file-seq (io/file dir))
                :when (.isFile f)
                :let [rel (str (.relativize root (.toPath f)))]
                :when (not (str/starts-with? rel ".haselnuss"))]
            [rel (vec (Files/readAllBytes (.toPath f)))]))))

(def ^:private site-libraries
  "The site fixture set (TASK-264) both `--build-site` runs below build."
  "test/fixtures/site/libraries.yaml")

(deftest native-build-site-test
  (if-not (binary-present?)
    (testing "no native binary built -- skipping (see this namespace's docstring)"
      (is true "native-image is opt-in; absence of a binary is not a failure"))
    (testing "TASK-278: --build-site runs from the binary -- the library scan, the
              Bible shelf script in the sandbox, every emitter, the app files read
              from the image's resources -- and writes what the JVM writes"
      (let [tmp (str (Files/createTempDirectory "haselnuss-native-build-site"
                                                (make-array FileAttribute 0)))
            native-site (str (io/file tmp "native"))
            jvm-site (str (io/file tmp "jvm"))
            native (run-binary "--libraries" site-libraries "--build-site" native-site)
            jvm (run-main "--libraries" site-libraries "--build-site" jvm-site)
            native-tree (site-tree native-site)]
        ;; 1: the fixture set carries two planted warnings
        (is (= 1 (:exit jvm)) (:err jvm))
        (is (= (:exit jvm) (:exit native)) (:err native))
        ;; the progress lines and the summary go to stderr
        (is (= (:out jvm) (:out native)))
        (is (= (:err jvm) (:err native)) "the same books rebuilt, the same warnings")
        (is (str/includes? (:err native) "9 of 11 books rebuilt") (:err native))
        (is (contains? native-tree "app.js") "the app files come from the image's resources")
        (is (contains? native-tree "icon-512.png"))
        (is (contains? native-tree "catalog/index.json"))
        (is (some #(str/starts-with? % "books/bible/") (keys native-tree))
            "the generated (shelf-script) books were built")
        (is (= (keys (site-tree jvm-site)) (keys native-tree)) "the same files")
        (is (= (site-tree jvm-site) native-tree) "and every one byte for byte")
        (testing "a second run reads the saved state back (EDN in the image) and
                  rebuilds nothing"
          (let [again (run-binary "--libraries" site-libraries "--build-site" native-site)]
            (is (= 1 (:exit again)) (:err again))
            (is (str/includes? (:err again) "0 of 11 books rebuilt, 9 up to date") (:err again))
            (is (= native-tree (site-tree native-site)))))
        (testing "TASK-328: --build-doc runs from the binary -- a filesystem and a generated
                  book, the header of the saved state read back -- and writes what the
                  JVM writes"
          (let [ids ["reformed:catecismo-heidelberg" "bible:romanos"]
                native-doc (apply run-binary "--libraries" site-libraries "--build-doc"
                                  (concat ids ["--site" native-site]))
                jvm-doc (apply run-main "--libraries" site-libraries "--build-doc"
                               (concat ids ["--site" jvm-site]))]
            (is (= 0 (:exit native-doc)) (:err native-doc))
            (is (= (:exit jvm-doc) (:exit native-doc)))
            (is (= (:err jvm-doc) (:err native-doc)))
            (is (str/includes? (:err native-doc) "2 books rebuilt") (:err native-doc))
            (is (= (site-tree jvm-site) (site-tree native-site)))
            (is (= "page bible:blivre/romanos\npage reformed:catecismo-heidelberg\n"
                   (slurp (io/file native-site ".haselnuss" "pending"))))))
        (testing "TASK-296: the binary and the jar each know their build identity, so a
                  run after a settled build answers from the record -- which leaves
                  it as it is -- and prints what the long way printed; the other one
                  goes the long way"
          (doseq [[what run other] (if (jar-present?)
                                     [["the binary" run-binary run-jar] ["the jar" run-jar run-binary]]
                                     [["the binary" run-binary run-main]])]
            (let [site (str (io/file tmp (str "unchanged-" (if (= run run-binary) "native" "jar"))))
                  build #(run "--libraries" site-libraries "--build-site" site)
                  record (io/file site ".haselnuss" "unchanged")
                  stamp #(when (.isFile record)
                           [(Files/getLastModifiedTime (.toPath record) (make-array LinkOption 0))
                            (Files/getAttribute (.toPath record) "unix:ino" (make-array LinkOption 0))])
                  _ (build)
                  _ (Thread/sleep 2100)                 ; past the racy window of every folder
                  settled (build)                       ; the long way, which saves the record
                  saved (stamp)
                  hit (build)]
              (is (some? saved) (str what ": a record is saved\n" (:err settled)))
              (is (= saved (stamp)) (str what ": the next run answers from it and leaves it"))
              (is (= (:err settled) (:err hit)) what)
              (is (= (:exit settled) (:exit hit)) what)
              (let [other-run (other "--libraries" site-libraries "--build-site" site)]
                (is (not= saved (stamp)) (str what ": another build takes the long way and saves its own"))
                (is (= (:err settled) (:err other-run)))))))))))

(deftest native-heap-defaults-test
  (if-not (binary-present?)
    (testing "no native binary built -- skipping (see this namespace's docstring)"
      (is true "native-image is opt-in; absence of a binary is not a failure"))
    (testing "TASK-278: bin/native bakes in a 128 MB young generation (-R:MaxNewSize),
              which keeps a plain `--serve` under 512 MB across catalog reloads;
              the maximum heap stays the image's default, which a large
              --build-site needs"
      (let [{:keys [exit out err]} (run-binary "-XX:+VerboseGC" "--version")]
        (is (= 0 exit) err)
        (is (str/starts-with? out "haselnuss "))
        (is (str/includes? err "Maximum young generation size: 128M") err))
      (testing "and -XX:MaxNewSize given to the binary still overrides it"
        (let [{:keys [err]} (run-binary "-XX:+VerboseGC" "-XX:MaxNewSize=64m" "--version")]
          (is (str/includes? err "Maximum young generation size: 64M") err))))))

(deftest ^:timing native-serve-test
  (if-not (binary-present?)
    (testing "no native binary built -- skipping (see this namespace's docstring)"
      (is true "native-image is opt-in; absence of a binary is not a failure"))
    (testing "TASK-273: --serve runs from the binary -- the JDK HTTP server, the
              static files, the readers API and the in-memory search -- over a
              site the binary built itself (TASK-278)"
      (let [site (str (io/file (str (Files/createTempDirectory "haselnuss-native-serve"
                                                               (make-array FileAttribute 0)))
                               "site"))
            _ (is (contains? #{0 1} (:exit (run-binary "--libraries" site-libraries
                                                       "--build-site" site))))
            command ^java.util.List (vector (.getAbsolutePath (io/file binary-path)) "--serve" site "--port" "0")
            proc (.start (doto (ProcessBuilder. command)
                           (.redirectOutput java.lang.ProcessBuilder$Redirect/DISCARD)))]
        (try
          (let [line (.readLine ^java.io.BufferedReader (io/reader (.getErrorStream proc)))
                base (second (re-find #" at (http://\S+/) " (str line)))]
            (is base (str "the serving line: " line))
            (when base
              (is (= 200 (first (http "GET" base nil))))
              (is (= 200 (first (http "GET" (str base "app.js") nil))))
              (let [[status body] (http "GET" (str base "books/bible/blivre/romanos/") nil)]
                (is (= 200 status) "a book page the binary built")
                (is (str/includes? (str body) "<title>Romanos")))
              (is (= 200 (first (http "GET" (str base "books/bible/blivre/romanos/backlinks.json") nil))))
              (is (= 404 (first (http "GET" (str base ".haselnuss/lock") nil))))
              (is (= 404 (first (http "GET" (str base "%2e%2e/%2e%2e/etc/passwd") nil))))
              (is (= [201 "{\"id\":\"ana\",\"name\":\"Ana\"}"] (http "POST" (str base "api/readers") "{\"name\":\"Ana\"}")))
              (is (= 200 (first (http "PUT" (str base "api/readers/ana/positions/bible:blivre/romanos")
                                      "{\"savedAt\":\"2026-11-13T21:04:11Z\",\"percent\":3}"))))
              (is (str/includes? (second (http "GET" (str base "api/readers/ana/positions") nil)) "2026-11-13T21:04:11Z"))
              (is (str/includes? (second (http "GET" (str base "api/search?q=romanos") nil)) "\"title\":\"Romanos\""))
              (testing "TCP_NODELAY reaches the image (sun.net.httpserver.ServerConfig is
                        initialized at run time): kept-alive requests do not each
                        wait ~40 ms on a delayed ACK"
                (let [client (-> (java.net.http.HttpClient/newBuilder)
                                 (.version java.net.http.HttpClient$Version/HTTP_1_1)
                                 (.build))
                      req (.build (java.net.http.HttpRequest/newBuilder (java.net.URI/create (str base "app.js"))))
                      fetch #(.statusCode (.send client req (java.net.http.HttpResponse$BodyHandlers/ofString)))
                      _ (fetch)
                      start (System/nanoTime)
                      statuses (vec (repeatedly 60 fetch))
                      seconds (/ (- (System/nanoTime) start) 1e9)]
                  (is (every? #{200} statuses))
                  (is (< seconds 1.5) (str "60 kept-alive requests took " seconds " s"))))))
          (finally
            (.destroy proc)
            (.waitFor proc)))))))
