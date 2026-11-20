(ns haselnuss.site-build-test
  "`--build-site DIR` through the real CLI (TASK-268; doc-12, \"The build:
  --build-site\"): a full build of the site fixture set
  (`test/fixtures/site/`) into a temp folder -- a page per book, the
  reader app's files, the messages and exit codes -- the refusals that
  write nothing, and output that never depends on the thread count."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.cli :as cli]
            [haselnuss.library.graph :as graph]
            [haselnuss.library.state :as state]
            [haselnuss.site :as site])
  (:import (java.io File)
           (java.nio.channels FileChannel)
           (java.nio.file Files OpenOption StandardOpenOption)
           (java.nio.file.attribute FileAttribute PosixFilePermissions)))

;; --- helpers -----------------------------------------------------------------

(def ^:private site-yaml "test/fixtures/site/libraries.yaml")

(def ^:private ^File fixture-dir (io/file "test/fixtures/site"))

(def ^:private cwd (System/getProperty "user.dir"))

(def ^:private overrides
  "No real `$HASELNUSS_LIBRARIES` or home config; paths shown relative to
  the working directory."
  {:library-env {} :library-home "/nonexistent/haselnuss-test-home"
   :haselnuss.cli/check-base-dir cwd})

(defn- main-run
  "`args` through `cli/main*`; `extra` merged over the overrides (the
  thread count)."
  [extra & args]
  (let [err (java.io.StringWriter.)
        out (java.io.StringWriter.)
        status (binding [*err* err *out* out] (cli/main* args (merge overrides extra)))]
    {:status status :out (str out) :err (str err)}))

(defn- temp-dir ^File []
  (.toFile (Files/createTempDirectory "haselnuss-site-build" (make-array FileAttribute 0))))

(defn- write!
  ^File [^File dir rel text]
  (let [f (io/file dir rel)]
    (io/make-parents f)
    (spit f text)
    f))

(defn- tree
  "`{relative-path content}` of every file under `dir` (nil when it does
  not exist), folders as `:dir`; `skip?` drops paths."
  ([dir] (tree dir (constantly false)))
  ([dir skip?]
   (let [root (io/file dir)]
     (when (.exists root)
       (into (sorted-map)
             (for [^File f (file-seq root)
                   :let [rel (str (.relativize (.toPath root) (.toPath f)))]
                   :when (and (not= "" rel) (not (skip? rel)))]
               [rel (if (.isDirectory f) :dir (slurp f))]))))))

(def ^:private books
  "The pages the fixture set builds: every book but the two notas books,
  which share one canonical id."
  #{"books/bible/blivre/1-corintios/index.html"
    "books/bible/blivre/genesis/index.html"
    "books/bible/blivre/joao/index.html"
    "books/bible/blivre/mateus/index.html"
    "books/bible/blivre/romanos/index.html"
    "books/reformed/catecismo-heidelberg/index.html"
    "books/reformed/catkins/index.html"
    "books/reformed/dangling/index.html"
    "books/reformed/estudos/queda/index.html"})

(defn- build-fixture
  "Build the fixture set into `dir` with `threads` threads."
  ([dir] (build-fixture dir 2))
  ([dir threads]
   (main-run {:haselnuss.cli/site-threads threads} "--libraries" site-yaml "--build-site" (str dir))))

(defn- strip-reader-app
  "`page` without what the site adds to its head: the theme style and
  script, the reader app's `<link>` and `<script>`, and `viewport-fit=cover`
  (TASK-275)."
  [page]
  (-> page
      (str/replace (str site/theme-head) "")
      (str/replace #"<link rel=\"stylesheet\" href=\"(\.\./)+app\.css\"/><script src=\"(\.\./)+app\.js\" defer></script>" "")
      (str/replace "initial-scale=1, viewport-fit=cover\"/>" "initial-scale=1\"/>")))

(defn- unlink-books
  "`page` as the fixture set's plain `html` build writes it, which has no
  `url:` template and so links no book: each book link a `<span>`, no
  quote `cite`, and each quote source's title unlinked. A site page links
  them all into the site (TASK-269; `haselnuss.site-links-test`)."
  [page]
  (-> page
      ;; TASK-275: a site's book link also says its passage
      (str/replace #"<a class=\"book-ref\" data-passage=\"[^\"]*\" href=\"[^\"]*\">(.*?)</a>" "<span class=\"book-ref\">$1</span>")
      (str/replace #"(<blockquote class=\"book-quote\"[^>]*?) cite=\"[^\"]*\"" "$1")
      (str/replace #"<cite><a href=\"[^\"]*\">(.*?)</a></cite>" "<cite>$1</cite>")))

;; --- AC #1: a full build into an empty folder -----------------------------------

(def ^:private expected-err
  (delay
    (let [fixture (.getCanonicalPath fixture-dir)]
      (str "rebuilt  bible:blivre/1-corintios       (full build)\n"
           "rebuilt  bible:blivre/genesis           (full build)\n"
           "rebuilt  bible:blivre/joao              (full build)\n"
           "rebuilt  bible:blivre/mateus            (full build)\n"
           "rebuilt  bible:blivre/romanos           (full build)\n"
           "rebuilt  reformed:catecismo-heidelberg  (full build)\n"
           "rebuilt  reformed:catkins               (full build)\n"
           "rebuilt  reformed:dangling              (full build)\n"
           "rebuilt  reformed:estudos/queda         (full build)\n"
           "skipped  reformed:notas                 (2 books share this id)\n"
           "test/fixtures/site/reformed/dangling.hdoc:9:1: warning: [unknown-book] bible:tobias#1:1: "
           "library \"bible\" has no book \"tobias\"\n"
           "test/fixtures/site/reformed/notas: warning: [duplicate-slug] library \"reformed\" has more "
           "than one book with slug \"notas\": reformed:notas (" fixture "/reformed/notas), reformed:notas ("
           fixture "/reformed/notas.hdoc)\n"
           ;; 11 books: the two notas books count as two (not TASK-285's one),
           ;; and the duplicate-slug warning is in both of them.
           "9 of 11 books rebuilt, 0 up to date, 2 not built; 2 warnings in 3 books\n"))))

(deftest full-build-test
  (let [dir (io/file (temp-dir) "site")
        {:keys [status out err]} (build-fixture dir)
        files (tree dir #(str/starts-with? % ".haselnuss"))]
    (testing "a folder that does not exist yet is made; the run prints a line per book,
              the problems as --check prints them, and the summary, all on stderr"
      (is (= 1 status) err)
      (is (= "" out))
      (is (= @expected-err err)))
    (testing (str "one index.html per book (none for the two books sharing an id), with its"
                  " backlinks.json and previews.json (TASK-271), the image a book shows (TASK-270),"
                  " and the app files")
      (is (= (-> books
                 (into (mapcat (fn [page]
                                 (let [d (subs page 0 (- (count page) (count "index.html")))]
                                   [(str d "backlinks.json") (str d "previews.json")])))
                       books)
                 (conj "books/reformed/catkins/catkins.png"))
             (set (for [[rel content] files
                        :when (and (str/starts-with? rel "books/") (string? content))]
                    rel))))
      (is (= #{"catalog/index.json" "catalog/bible/1.json" "catalog/reformed/1.json"}
             (set (for [[rel content] files
                        :when (and (str/starts-with? rel "catalog/") (string? content))]
                    rel))))
      (doseq [file-name site/app-files]
        (is (= (String. ^bytes (get (site/app-file-bytes) file-name) "UTF-8") (get files file-name)) file-name)
        (is (= (str/replace (slurp (io/resource (str "haselnuss/site/" file-name)))
                            site/app-version-token "")
               (str/replace (get files file-name) #"\b[0-9a-f]{16}\b" ""))
            (str file-name ": the resource as it is, but for the app's version in sw.js"))))
    (testing "the folder is a site: its state is saved and loads back with every book"
      (is (site/site? dir))
      (let [loaded (state/load-state dir)]
        (is (:usable? loaded) (:message loaded))
        (is (= 10 (count (graph/indexed-books (:graph loaded)))) "the notas books share a record")
        (is (= (set (map #(subs % 0 (- (count %) (count "/index.html"))) books))
               (set (map (comp #(subs % 0 (- (count %) (count "/index.html"))) :path)
                         (vals (:books loaded))))))))
    (testing "each page links the app once, by paths that reach the app's files"
      (doseq [page books
              :let [html (get files page)
                    page-dir (.getParentFile (io/file dir page))]]
        (is (= 1 (count (re-seq #"app\.css" html))) page)
        (is (= 1 (count (re-seq #"<script src=\"[./]*app\.js\" defer></script>" html))) page)
        (doseq [[_ href] (re-seq #"(?:href|src)=\"((?:\.\./)+app\.(?:css|js))\"" html)]
          (is (.isFile (io/file page-dir href)) (str page " " href)))
        (testing "and is otherwise a whole page on its own: doctype, default stylesheet"
          (is (str/starts-with? html "<!DOCTYPE html>") page)
          (is (str/ends-with? html "</html>") page)
          (is (str/includes? html "<style>") page))))
    (testing (str "a filesystem book's page is exactly the html build of its book ID, plus the app,"
                  " but for its links into the site")
      (doseq [[id page] [["reformed:catecismo-heidelberg" "books/reformed/catecismo-heidelberg/index.html"]
                         ["reformed:estudos/queda" "books/reformed/estudos/queda/index.html"]
                         ["reformed:catkins" "books/reformed/catkins/index.html"]
                         ["reformed:dangling" "books/reformed/dangling/index.html"]]]
        (let [out-file (io/file (temp-dir) "book.html")]
          (cli/build (merge overrides {:libraries site-yaml :input id :target "html" :output (str out-file)}))
          (is (= (slurp out-file) (unlink-books (strip-reader-app (get files page)))) id))))
    (testing "MathJax where the book has maths; a generated book's page carries its text"
      (is (str/includes? (get files "books/reformed/catkins/index.html") "MathJax"))
      (is (str/includes? (get files "books/bible/blivre/romanos/index.html") "<title>Romanos</title>")))))

;; --- AC #3: warnings still build; exit codes ----------------------------------------

(deftest warnings-and-exit-codes-test
  (testing "a book with a warning still gets its page, the run exits 1 and the summary counts it"
    (let [dir (temp-dir)
          {:keys [status err]} (build-fixture dir)]
      (is (= 1 status))
      (is (.isFile (io/file dir "books/reformed/dangling/index.html")))
      (is (str/ends-with? err "; 2 warnings in 3 books\n"))))
  (testing "a clean set of libraries exits 0"
    (let [root (temp-dir)
          yaml (write! root "libraries.yaml"
                       (str "- name: bible\n  type: filesystem\n  path: "
                            (.getCanonicalPath (io/file fixture-dir "bible")) "\n  scripts: true\n"))
          dir (io/file root "site")
          {:keys [status err]} (main-run {} "--libraries" (str yaml) "--build-site" (str dir))]
      (is (= 0 status) err)
      (is (str/ends-with? err "\n5 of 5 books rebuilt, 0 up to date; no warnings\n") err)))
  (testing "a book whose page cannot be emitted is an error; the others are written"
    (let [root (temp-dir)
          _ (write! root "lib/ok.hdoc" "---\ntitle: Ok\n---\n\nFine.\n")
          _ (write! root "lib/bad.hdoc" "---\ntitle: Bad\nbibliography: missing.json\n---\n\nText.\n")
          yaml (write! root "libraries.yaml" "- name: lib\n  type: filesystem\n  path: lib\n")
          dir (io/file root "site")
          {:keys [status err]} (main-run {} "--libraries" (str yaml) "--build-site" (str dir))]
      (is (= 1 status) err)
      (is (.isFile (io/file dir "books/lib/ok/index.html")))
      (is (not (.exists (io/file dir "books/lib/bad"))))
      (is (str/includes? err "failed   lib:bad  (its page could not be emitted)\n") err)
      (is (re-find #"bad\.hdoc: error: \[emit-failed\] the page of lib:bad was not written: bibliography file not found" err) err)
      (is (str/ends-with? err "1 of 2 books rebuilt, 0 up to date, 1 not built; 1 error, 1 warning in 1 book\n") err))))

;; --- re-running into a site ------------------------------------------------------

(deftest rebuild-into-a-site-test
  (let [dir (temp-dir)
        _ (build-fixture dir)
        reader (write! dir "readers/ana.json" "{\"positions\":{}}")
        stale (write! dir "books/reformed/gone/index.html" "old page")
        nested (write! dir "books/old/deep/shelf/x/index.html" "old page")
        before (tree dir #(str/starts-with? % ".haselnuss"))
        {:keys [status err]} (build-fixture dir)
        after (tree dir #(str/starts-with? % ".haselnuss"))]
    (testing "a site is built into again"
      (is (= 1 status) err))
    (testing "a page no book wrote is removed, with the folders it leaves empty"
      (is (not (.exists stale)))
      (is (not (.exists (io/file dir "books/reformed/gone"))))
      (is (not (.exists (io/file dir "books/old"))))
      (is (not (.exists nested)) "at any depth")
      ;; TASK-272: the second run rebuilds nothing, so only these lines are
      ;; printed and the ids are padded to their own column
      (is (str/includes? err "removed  old:deep/shelf/x  (no longer in the libraries)\n") err)
      (is (str/includes? err "removed  reformed:gone     (no longer in the libraries)\n") err)
      (is (str/ends-with? err "0 of 11 books rebuilt, 9 up to date, 2 not built; 2 warnings in 3 books\n") err))
    (testing "readers/ is never touched"
      (is (= "{\"positions\":{}}" (slurp reader))))
    (testing "everything else is as before"
      (is (= (apply dissoc before (filter #(or (str/starts-with? % "books/reformed/gone")
                                               (str/starts-with? % "books/old"))
                                          (keys before)))
             after)))))

;; --- AC #2: refusals write nothing ----------------------------------------------------

(defn- refused?
  "`args` exit 2, print `message` on stderr and leave `dir` as it was."
  [dir message & args]
  (let [before (tree dir)
        {:keys [status out err]} (apply main-run {} args)]
    (is (= 2 status) (pr-str args err))
    (is (= "" out))
    (is (str/includes? err message) (pr-str args err))
    (is (= before (tree dir)) (str "nothing written: " (pr-str args)))))

(deftest refusals-write-nothing-test
  (testing "a folder that is neither empty nor a site"
    (let [dir (temp-dir)]
      (write! dir "notes.txt" "mine")
      (refused? dir "the folder is neither empty nor a Haselnuss site"
                "--libraries" site-yaml "--build-site" (str dir)))
    (let [dir (temp-dir)]
      (write! dir "readers/ana.json" "{}")
      (refused? dir "neither empty nor a Haselnuss site" "--libraries" site-yaml "--build-site" (str dir))))
  (testing "a file, not a folder"
    (let [f (write! (temp-dir) "file" "x")]
      (refused? f "not a folder" "--libraries" site-yaml "--build-site" (str f))))
  (testing "an unwritable folder"
    (let [dir (temp-dir)]
      (Files/setPosixFilePermissions (.toPath dir) (PosixFilePermissions/fromString "r-xr-xr-x"))
      (try
        (when-not (.canWrite dir) ; root can write anyway
          (refused? dir "cannot write" "--libraries" site-yaml "--build-site" (str dir)))
        (finally
          (Files/setPosixFilePermissions (.toPath dir) (PosixFilePermissions/fromString "rwxr-xr-x"))))))
  (testing "another build holding the lock"
    (let [dir (temp-dir)
          _ (.mkdirs (state/state-dir dir))
          lock-file (io/file dir ".haselnuss" "lock")]
      (with-open [ch (FileChannel/open (.toPath lock-file)
                                       ^"[Ljava.nio.file.OpenOption;"
                                       (into-array OpenOption [StandardOpenOption/CREATE StandardOpenOption/WRITE]))]
        (let [lock (.lock ch)]
          (try
            (refused? dir "another build holds" "--libraries" site-yaml "--build-site" (str dir))
            (finally (.release lock)))))
      (testing "and once it is released, the build runs"
        (is (= 1 (:status (build-fixture dir)))))))
  (testing "no, a missing or an invalid directory file"
    (let [root (temp-dir)
          dir (io/file root "site")
          bad (write! root "bad.yaml" "- name: lib\n  type: filesystem\n- not a map\n")]
      (refused? root "no library directory was found" "--build-site" (str dir))
      (refused? root "cannot read the library directory: no such file"
                "--libraries" (str root "/missing.yaml") "--build-site" (str dir))
      (refused? root "bad.yaml:3: an entry must be a map" "--libraries" (str bad) "--build-site" (str dir))
      (is (not (.exists dir)))))
  (testing "an --extensions or --transform file that cannot be loaded"
    (let [root (temp-dir)
          dir (io/file root "site")
          broken (write! root "broken.clj" "{:name \"x\" :emit")]
      (.mkdirs dir)
      (doseq [flag ["--extensions" "--transform"]
              file [(str root "/nowhere.clj") (str broken)]]
        (refused? root (.getName (io/file file)) "--libraries" site-yaml "--build-site" (str dir) flag file))))
  (testing "the command line"
    (doseq [[args message] [[["--build-site" "s" "doc.hdoc"] "--build-site takes no input file"]
                            [["--build-site" "s" "--check"] "--build-site cannot be combined with --check"]
                            [["--build-site" "s" "--backlinks" "a:b"] "--build-site cannot be combined with --backlinks"]
                            [["--build-site" "s" "--list-books" "a"] "--build-site cannot be combined with --list-books"]
                            [["--build-site" "s" "--build-site" "t"] "--build-site given more than once"]
                            [["--build-site="] "--build-site requires a folder"]
                            [["--build-site"] "option --build-site requires a value"]
                            [["--full" "doc.hdoc"] "--full applies only to --build-site"]
                            [["--check" "--full"] "--full applies only to --build-site"]
                            [["--build-site" "s" "--format" "json"] "--format applies only to --check"]]]
      (let [{:keys [status err]} (apply main-run {} args)]
        (is (= 2 status) (pr-str args))
        (is (str/includes? err message) (pr-str args err))
        (is (not (.exists (io/file "s"))))))))

(deftest flags-test
  (testing "a build option a page does not use is warned about; the site is built as without it"
    (let [dir (temp-dir)
          {:keys [status err]} (main-run {} "--libraries" site-yaml "--build-site" (str dir)
                                         "--target" "latex" "--no-stylesheet" "--full")]
      (is (= 1 status) err)
      (is (str/starts-with? err (str "haselnuss: warning: --target has no effect with --build-site\n"
                                     "haselnuss: warning: --no-stylesheet has no effect with --build-site\n"
                                     "rebuilt  ")) err)
      (is (str/includes? (slurp (io/file dir "books/reformed/dangling/index.html")) "<style>"))))
  (testing "--extensions and --transform apply to every filesystem book's page, as in its html build"
    (let [root (temp-dir)
          _ (write! root "lib/a.hdoc" "---\ntitle: A\n---\n\n# Old {#sec:old}\n\n:::{callout}\nA note.\n:::\n")
          ext (write! root "callout.clj" (str "{:name \"callout\"\n"
                                              " :emit {:html (fn [d ctx] [:aside {:class \"callout\"} "
                                              "((:render-blocks ctx) (:blocks d))])}}"))
          rule (write! root "rename.clj" (str "{:select :section\n"
                                              " :transform (fn [node ctx] (assoc-in node [:attr :id] \"sec:new\"))}"))
          yaml (write! root "libraries.yaml" "- name: lib\n  type: filesystem\n  path: lib\n")
          dir (io/file root "site")
          {:keys [status err]} (main-run {} "--libraries" (str yaml) "--build-site" (str dir)
                                         "--extensions" (str ext) "--transform" (str rule))
          page (slurp (io/file dir "books/lib/a/index.html"))]
      (is (= 0 status) err)
      (is (str/includes? page "<aside class=\"callout\">") page)
      (is (str/includes? page "id=\"sec:new\"") page)
      (is (= (some-> (state/load-state dir) :inputs keys set)
             #{[:libraries 0 (.getCanonicalPath yaml)] [:extensions 0 (.getCanonicalPath ext)]
               [:transform 0 (.getCanonicalPath rule)]})
          "the input files' digests are saved, keyed by flag and position")))
  (testing "reordering the --transform files, or moving one to --extensions, changes the saved inputs"
    (let [root (temp-dir)
          _ (write! root "lib/a.hdoc" "---\ntitle: A\n---\n\nA.\n")
          r1 (write! root "r1.clj" "{:select :section :transform (fn [n _] n)}")
          r2 (write! root "r2.clj" "{:select :para :transform (fn [n _] n)}")
          yaml (write! root "libraries.yaml" "- name: lib\n  type: filesystem\n  path: lib\n")
          inputs (fn [& flags]
                   (let [dir (temp-dir)]
                     (apply main-run {} "--libraries" (str yaml) "--build-site" (str dir) flags)
                     (:inputs (state/load-state dir))))
          a (inputs "--transform" (str r1) "--transform" (str r2))]
      (is (= 3 (count a)))
      (is (not= a (inputs "--transform" (str r2) "--transform" (str r1))))
      (is (not= a (inputs "--extensions" (str r1) "--transform" (str r2)))))))

;; --- review round: emit-stage warnings, symbolic links, temp files -------------------

(deftest emit-warnings-test
  (testing "a warning only emitting the page finds (a dropped javascript: link) is reported
            in --check's format, counted, and kept in the state with the page"
    (let [root (temp-dir)
          _ (write! root "lib/a.hdoc" "---\ntitle: A\n---\n\nFine.\n")
          _ (write! root "lib/b.hdoc" "---\ntitle: B\n---\n\nSee [x](javascript:alert(1)).\n")
          yaml (write! root "libraries.yaml" "- name: lib\n  type: filesystem\n  path: lib\n")
          dir (io/file root "site")
          {:keys [status err]} (main-run {:haselnuss.cli/check-base-dir (.getCanonicalPath root)}
                                         "--libraries" (str yaml) "--build-site" (str dir))
          page (slurp (io/file dir "books/lib/b/index.html"))]
      (is (= 1 status) err)
      (is (str/includes? err "\nlib/b.hdoc: warning: [unsafe-url] Dropped the link to \"javascript:alert(1)\"") err)
      (is (str/ends-with? err "2 of 2 books rebuilt, 0 up to date; 1 warning in 1 book\n") err)
      (is (not (str/includes? page "javascript:")) "and the link is still dropped")
      (is (= [:unsafe-url] (map :type (get-in (state/load-state dir)
                                              [:books {:library "lib" :shelf [] :slug "b"} :emit-diagnostics]))))))
  (testing "the fixture set has none, so its output is unchanged by them"
    (let [{:keys [err]} (build-fixture (temp-dir))]
      (is (= @expected-err err)))))

(deftest symbolic-links-test
  (testing "a DIR that is a symbolic link to an empty folder is built where it leads"
    (let [root (temp-dir)
          real (doto (io/file root "real") (.mkdirs))
          link (io/file root "site")]
      (Files/createSymbolicLink (.toPath link) (.toPath real) (make-array FileAttribute 0))
      (let [{:keys [status err]} (build-fixture link)]
        (is (= 1 status) err)
        (is (= @expected-err err))
        (is (.isFile (io/file real "books/reformed/dangling/index.html")))
        (is (site/site? real)))
      (testing "and again, as a site"
        (is (= 1 (:status (build-fixture link)))))))
  (testing "a symbolic link inside books/ is never written through: those pages fail"
    (doseq [linked ["books/reformed" "books"]]
      (let [dir (temp-dir)
            elsewhere (temp-dir)
            _ (build-fixture dir)
            target (io/file dir linked)]
        (doseq [^File f (reverse (file-seq target))] (.delete f))
        (Files/createSymbolicLink (.toPath target) (.toPath elsewhere) (make-array FileAttribute 0))
        (let [{:keys [status err]} (build-fixture dir)]
          (is (= 1 status) err)
          (is (= [] (vec (.list elsewhere))) (str linked ": nothing written where the link leads"))
          (is (str/includes? err (str "failed   reformed:dangling")) err)
          (is (re-find (re-pattern (str "\\[emit-failed\\] the page of reformed:dangling was not written: "
                                        linked " is a symbolic link or not a folder"))
                       err)
              err)
          (is (Files/isSymbolicLink (.toPath target)) "the link itself is left alone"))))))

(deftest sweep-temp-files-test
  (let [dir (temp-dir)
        _ (build-fixture dir)
        own (write! dir "books/reformed/dangling/.index.html.8123456789.tmp" "half a page")
        draft (write! dir "books/.draft.2024.tmp" "mine")
        other (write! dir "books/reformed/notes.txt" "mine too")]
    (build-fixture dir)
    (is (not (.exists own)) "a page's leftover temporary file goes")
    (is (= "mine" (slurp draft)) "a file merely ending in .<digits>.tmp stays")
    (is (= "mine too" (slurp other)))))

;; --- AC #4: output independent of order -------------------------------------------------

(deftest same-output-whatever-the-thread-count-test
  (let [runs (for [threads [1 3 8]]
               (let [dir (temp-dir)
                     {:keys [status err]} (build-fixture dir threads)]
                 {:status status :err err :files (tree dir #(str/starts-with? % ".haselnuss"))}))
        [one & more] runs]
    (is (= 1 (:status one)))
    (is (= books (set (filter #(and (str/starts-with? % "books/") (str/ends-with? % "/index.html"))
                              (keys (:files one))))) "not vacuous")
    (doseq [r more]
      (is (= (:files one) (:files r)) "byte-identical files")
      (is (= (:err one) (:err r)) "and the same messages"))))
