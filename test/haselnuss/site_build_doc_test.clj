(ns haselnuss.site-build-doc-test
  "`--build-doc ID... --site DIR` (TASK-328; doc-13, section 4) on a full
  site: the named books' pages rebuilt as `--build-site` builds them,
  without loading or saving the saved state. What keeps the site honest is
  the pending list: every page written is a `page <id>` line, so the next
  `--build-site` rebuilds it and its dependents and the site it leaves is
  byte for byte a clean build's -- even when the edit was undone in
  between (`edit-build-doc-undo-test`, which also shows the test would
  fail without the pending line). Built from copies of the site fixture
  set (`test/fixtures/site/`); every file a test writes gets an old,
  distinct modification time (`touch!`), as in
  `haselnuss.site-incremental-test`."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.book-ref :as book-ref]
            [haselnuss.cli :as cli]
            [haselnuss.library.digest :as digest]
            [haselnuss.library.graph.check :as check]
            [haselnuss.library.state :as state]
            [haselnuss.serve :as serve]
            [haselnuss.site :as site]
            [haselnuss.site.incremental :as incremental]
            [haselnuss.site.unchanged :as unchanged]
            [haselnuss.version :as version])
  (:import (java.io File StringWriter)
           (java.nio.file Files LinkOption)
           (java.nio.file.attribute FileAttribute FileTime)))

;; --- helpers -----------------------------------------------------------------------

(def ^:private ^File fixture-dir (io/file "test/fixtures/site"))

(defn- temp-dir ^File []
  (.toFile (Files/createTempDirectory "haselnuss-build-doc" (make-array FileAttribute 0))))

(def ^:private clock
  "A fake modification clock, a day ago, one second further each time."
  (atom (- (System/currentTimeMillis) (* 24 3600 1000))))

(defn- touch! [^File f]
  (Files/setLastModifiedTime (.toPath f) (FileTime/fromMillis (swap! clock + 1000))))

(defn- rel-path [^File root ^File f]
  (str/replace (str (.relativize (.toPath root) (.toPath f))) File/separator "/"))

(defn- write-file! ^File [^File root rel content]
  (let [f (io/file root rel)]
    (io/make-parents f)
    (if (string? content)
      (spit f content)
      (Files/write (.toPath f) ^bytes content ^"[Ljava.nio.file.OpenOption;" (make-array java.nio.file.OpenOption 0)))
    (touch! f)
    f))

(defn- read-file [^File root rel] (slurp (io/file root rel)))

(defn- fixture-copy
  "A copy of the site fixture set in a new folder, every file touched."
  ^File []
  (let [root (temp-dir)]
    (doseq [^File f (file-seq fixture-dir) :when (.isFile f)]
      (let [target (io/file root (rel-path fixture-dir f))]
        (io/make-parents target)
        (io/copy f target)
        (touch! target)))
    root))

(defn- run!*
  "`args` through `cli/main*` with no real environment: `{:status :err}`.
  `env` stands for the environment (`$HASELNUSS_SITE`)."
  ([args] (run!* args {}))
  ([args env]
   (let [err (StringWriter.)
         status (binding [*err* err *out* (StringWriter.)]
                  (cli/main* args {:library-env env :library-home "/nonexistent/haselnuss-test-home"
                                   :haselnuss.cli/check-base-dir (System/getProperty "user.dir")
                                   :haselnuss.cli/site-threads 2}))]
     {:status status :err (str err)})))

(defn- libraries [^File root] (str (io/file root "libraries.yaml")))

(defn- build-site! [^File root dir & args]
  (run!* (into ["--libraries" (libraries root) "--build-site" (str dir)] args)))

(defn- build-doc! [^File root dir & ids]
  (run!* (into ["--libraries" (libraries root) "--build-doc"] (concat ids ["--site" (str dir)]))))

(defn- sha [^bytes bs]
  (apply str (map #(format "%02x" %) (.digest (java.security.MessageDigest/getInstance "SHA-256") bs))))

(defn- site-tree
  "`{relative-path digest}` of the site `dir`, folders as `:dir`,
  `.haselnuss/` left out."
  [^File dir]
  (into (sorted-map)
        (for [^File f (file-seq dir)
              :let [rel (rel-path dir f)]
              :when (and (not= "" rel) (not (str/starts-with? rel ".haselnuss")))]
          [rel (if (.isDirectory f) :dir (sha (Files/readAllBytes (.toPath f))))])))

(defn- whole-tree
  "`{relative-path [digest mtime]}` of everything under `dir`, `.haselnuss/`
  included: what \"nothing was written\" compares."
  [^File dir]
  (into (sorted-map)
        (for [^File f (file-seq dir)
              :let [rel (rel-path dir f)]
              :when (not= "" rel)]
          [rel (if (.isDirectory f)
                 :dir
                 [(sha (Files/readAllBytes (.toPath f)))
                  (Files/getLastModifiedTime (.toPath f) (make-array LinkOption 0))])])))

(defn- state-files
  "`{name [digest mtime]}` of the saved state's files: what \"the state is
  never written\" compares."
  [^File dir]
  (into (sorted-map)
        (for [^File f (.listFiles (state/state-dir dir))]
          [(.getName f) [(sha (Files/readAllBytes (.toPath f)))
                         (Files/getLastModifiedTime (.toPath f) (make-array LinkOption 0))]])))

(defn- tree-diff [a b]
  (sort (for [k (set/union (set (keys a)) (set (keys b))) :when (not= (get a k) (get b k))] k)))

(defn- clean-tree
  "The site tree of a clean build of `root` into a new folder."
  [^File root]
  (let [dir (io/file (temp-dir) "site")]
    (build-site! root dir)
    (site-tree dir)))

(defn- same-as-clean? [^File root ^File dir what]
  (let [clean (clean-tree root)
        tree (site-tree dir)]
    (is (= clean tree) (str what ": differs from a clean build at " (vec (tree-diff clean tree))))))

(defn- pending-lines [^File dir]
  (let [f (io/file dir ".haselnuss/pending")]
    (if (.isFile f) (str/split-lines (slurp f)) [])))

(defn- rebuilt-ids
  "The ids of the `rebuilt` lines of `err`, as `{id reason}`."
  [err]
  (into {} (for [[_ id reason] (re-seq #"(?m)^rebuilt  (\S+)\s+\((.*)\)$" err)] [id reason])))

(defn- built-site
  "A fixture copy and a site built from it: `[root dir]`."
  []
  (let [root (fixture-copy)
        dir (io/file (temp-dir) "site")]
    (build-site! root dir)
    [root dir]))

(def ^:private heidelberg "reformed/catecismo-heidelberg.hdoc")
(def ^:private heidelberg-page "books/reformed/catecismo-heidelberg/index.html")

;; --- AC #1: the command line, --site and $HASELNUSS_SITE ---------------------------

(deftest command-line-test
  (testing "usage errors are exit 2 and write nothing"
    (doseq [[args message]
            [[["--build-doc"] "--build-doc needs at least one book ID"]
             [["--build-doc" "--site" "s"] "--build-doc needs at least one book ID"]
             [["--build-doc" "reformed" "--site" "s"] "--build-doc takes book IDs"]
             [["--build-doc" "doc.hdoc" "--site" "s"] "--build-doc takes book IDs"]
             [["--build-doc" "reformed:catkins#sec:flowers" "--site" "s"] "--build-doc takes whole books, not passages"]
             [["--build-doc" "Bad Lib:x" "--site" "s"] "--build-doc takes book IDs"]
             [["--build-doc" "a:b" "--site" "s" "--site" "t"] "--site given more than once"]
             [["--build-doc" "a:b" "--site="] "option --site requires a folder"]
             [["--build-doc" "a:b" "--site"] "option --site requires a value"]
             [["--site" "s" "doc.hdoc"] "--site applies only to --build-doc"]
             [["--build-site" "s" "--site" "t"] "--site applies only to --build-doc"]
             [["--build-doc" "a:b" "--build-site" "s"] "--build-site cannot be combined with --build-doc"]
             [["--build-doc" "a:b" "--check"] "--build-doc cannot be combined with --check"]
             [["--build-doc" "a:b" "--backlinks" "a:b"] "--build-doc cannot be combined with --backlinks"]
             [["--build-doc" "a:b" "--list-books" "a"] "--build-doc cannot be combined with --list-books"]
             [["--build-doc" "a:b" "--serve" "s"] "--serve cannot be combined with --build-doc"]
             [["--build-doc" "a:b" "--doc" "a:b"] "--doc cannot be combined with --build-doc"]
             [["--build-doc" "a:b" "--format" "json"] "--format applies only to --check and --backlinks"]
             [["--build-doc" "a:b" "--full"] "--full applies only to --build-site"]
             [["--build-doc=x" "a:b"] "option --build-doc takes no value"]]]
      (let [{:keys [status err]} (run!* args)]
        (is (= 2 status) (pr-str args))
        (is (str/includes? err message) (pr-str args err))
        (is (str/includes? err "Try 'haselnuss --help'") (pr-str args))
        (is (not (.exists (io/file "s")))))))
  (testing "neither --site nor $HASELNUSS_SITE is exit 2"
    (let [{:keys [status err]} (run!* ["--build-doc" "reformed:catkins"])]
      (is (= 2 status))
      (is (str/includes? err "--build-doc needs a site: pass --site DIR or set $HASELNUSS_SITE") err))
    (let [{:keys [status]} (run!* ["--build-doc" "reformed:catkins"] {"HASELNUSS_SITE" "  "})]
      (is (= 2 status) "a blank variable is no site")))
  (testing "AC #10: --help documents --build-doc, --site and HASELNUSS_SITE"
    (let [out (with-out-str (cli/main* ["--help"]))]
      (is (str/includes? out "--build-doc ID..."))
      (is (str/includes? out "--site DIR"))
      (is (str/includes? out "$HASELNUSS_SITE")))))

(deftest site-from-option-and-environment-test
  (let [[root a] (built-site)
        b (io/file (temp-dir) "site")]
    ;; a second site, the same build: a copy of the first
    (doseq [^File f (file-seq a) :when (.isFile f)]
      (let [target (io/file b (rel-path a f))]
        (io/make-parents target)
        (Files/copy (.toPath f) (.toPath target)
                    ^"[Ljava.nio.file.CopyOption;" (into-array java.nio.file.CopyOption
                                                               [java.nio.file.StandardCopyOption/COPY_ATTRIBUTES]))))
    (let [args ["--libraries" (libraries root) "--build-doc" "reformed:catkins"]]
      (testing "$HASELNUSS_SITE names the site when --site is not given"
        (let [{:keys [status err]} (run!* args {"HASELNUSS_SITE" (str a)})]
          (is (= 0 status) err)
          (is (= ["page reformed:catkins"] (pending-lines a)))
          (is (= [] (pending-lines b)))))
      (testing "--site overrides it"
        (let [{:keys [status err]} (run!* (into args ["--site" (str b)]) {"HASELNUSS_SITE" (str a)})]
          (is (= 0 status) err)
          (is (= ["page reformed:catkins"] (pending-lines b))))))))

;; --- AC #2-#4: what is written, and the next --build-site ---------------------------

(deftest edit-build-doc-undo-test
  (testing "AC #3: edit a book, --build-doc it, undo the edit, --build-site: a clean build's site"
    (let [[root dir] (built-site)
          original (read-file root heidelberg)
          has-record? #(.isFile (io/file dir unchanged/record-rel))
          ;; no racy window, so the settled build saves the record of an
          ;; unchanged site (as haselnuss.site-incremental-test does)
          _ (with-redefs [digest/racy-window-ms 0]
              (is (= {} (rebuilt-ids (:err (build-site! root dir)))) "settled"))
          _ (is (has-record?) "the record of an unchanged site is there before")
          state-before (state-files dir)
          backlinks-before (read-file dir "books/reformed/catecismo-heidelberg/backlinks.json")
          _ (write-file! root heidelberg (str/replace original "Sobre esta edição" "Sobre esta edição revista"))
          {:keys [status err]} (build-doc! root dir "reformed:catecismo-heidelberg")]
      (is (= 0 status) err)
      (is (= (str "rebuilt  reformed:catecismo-heidelberg  (requested)\n"
                  "stale    reformed:estudos/queda         (refers to this book)\n"
                  "1 book rebuilt, 1 dependent book not rebuilt; no warnings\n")
             err))
      (testing "the page is the edited book's"
        (is (str/includes? (read-file dir heidelberg-page) "Sobre esta edição revista")))
      (testing "the state is not written, the book is pending and the unchanged-site record is gone"
        (is (= state-before (state-files dir)))
        (is (= ["page reformed:catecismo-heidelberg"] (pending-lines dir)))
        (is (not (has-record?))))
      (testing "backlinks.json is kept as it was"
        (is (= backlinks-before (read-file dir "books/reformed/catecismo-heidelberg/backlinks.json"))))
      (write-file! root heidelberg original)
      (let [twin (io/file (temp-dir) "twin")]
        ;; the same site, but for its pending list: what the test would see
        ;; were --build-doc to rely on the state alone
        (doseq [^File f (file-seq dir) :when (.isFile f)]
          (let [target (io/file twin (rel-path dir f))]
            (io/make-parents target)
            (io/copy f target)))
        (.delete (io/file twin ".haselnuss/pending"))
        (let [{:keys [err]} (build-site! root dir)]
          (is (= {"reformed:catecismo-heidelberg" "left pending by an earlier run"} (rebuilt-ids err))
              err)
          (is (= [] (pending-lines dir)) "the build clears the pending list")
          (same-as-clean? root dir "edit, --build-doc, undo, --build-site"))
        (testing "not vacuous: without the pending line the undone edit stays on the page"
          (build-site! root twin)
          (is (str/includes? (read-file twin heidelberg-page) "Sobre esta edição revista"))
          (is (not= (clean-tree root) (site-tree twin))))))))

(deftest edit-kept-test
  (testing "AC #4: --build-doc, then --build-site with the edit kept: the dependents are rebuilt too"
    (let [[root dir] (built-site)
          _ (write-file! root heidelberg (str/replace (read-file root heidelberg)
                                                      "O meu único fundamento" "O meu único e firme fundamento"))
          _ (build-doc! root dir "reformed:catecismo-heidelberg")
          quoting "books/reformed/estudos/queda/index.html"
          _ (is (not (str/includes? (read-file dir quoting) "único e firme")) "the quoting book is stale")
          {:keys [err]} (build-site! root dir)]
      (is (contains? (rebuilt-ids err) "reformed:estudos/queda") err)
      (is (str/includes? (read-file dir quoting) "único e firme"))
      (same-as-clean? root dir "edit kept")))
  (testing "a generated book: its page and previews.json show the new text"
    (let [[root dir] (built-site)
          bible "bible/blivre/blivre.json"
          previews "books/bible/blivre/romanos/previews.json"
          _ (write-file! root bible (str/replace (read-file root bible) "do Senhor somos" "do Senhor somos sempre"))
          before (json/read-str (read-file dir previews))
          {:keys [status err]} (build-doc! root dir "bible:romanos")
          after (json/read-str (read-file dir previews))]
      (is (= 0 status) err)
      (is (= (str "rebuilt  bible:blivre/romanos           (requested)\n"
                  "stale    reformed:catecismo-heidelberg  (refers to this book)\n"
                  "1 book rebuilt, 1 dependent book not rebuilt; no warnings\n")
             err))
      (is (str/includes? (read-file dir "books/bible/blivre/romanos/index.html") "do Senhor somos sempre"))
      (is (= (map #(get % "passage") (get before "previews")) (map #(get % "passage") (get after "previews")))
          "the same passages, in the same order")
      (is (some #(str/includes? (get % "html") "do Senhor somos sempre") (get after "previews")) (pr-str after))
      (is (not-any? #(str/includes? (get % "html") "do Senhor somos sempre") (get before "previews")))
      (build-site! root dir)
      (same-as-clean? root dir "a generated book, edit kept"))))

(deftest previews-test
  (let [root (fixture-copy)
        _ (write-file! root "reformed/leitor.hdoc"
                       (str "---\ntitle: Leitor\n---\n\nVeja @{reformed:catkins#sec:flowers} e "
                            "@{reformed:catkins#sec:counting}.\n"))
        dir (io/file (temp-dir) "site")
        _ (build-site! root dir)
        catkins "reformed/catkins/book.hdoc"
        previews "books/reformed/catkins/previews.json"
        keys-of #(mapv (fn [p] (get p "passage")) (get (json/read-str (read-file dir previews)) "previews"))]
    (is (= ["sec:counting" "sec:flowers"] (keys-of)))
    (testing "previews.json is made again for the passages it lists; one that no longer resolves is left out"
      (write-file! root catkins (-> (read-file root catkins)
                                    (str/replace "A hazel carries" "A hazel tree carries")
                                    (str/replace "{#sec:counting}" "{#sec:tally}")))
      (let [{:keys [status err]} (build-doc! root dir "reformed:catkins")
            data (json/read-str (read-file dir previews))]
        (is (= 0 status) "a preview left out is an info, not a warning")
        (is (= ["sec:flowers"] (keys-of)))
        (is (str/includes? (get-in data ["previews" 0 "html"]) "A hazel tree carries"))
        (is (re-find #"(?m)^stale    reformed:leitor\s+\(refers to this book\)$" err) err)
        (is (re-find #"info: \[preview-failed\] the preview of reformed:catkins#sec:counting was left out" err)
            err)
        (build-site! root dir)
        (same-as-clean? root dir "a passage renamed")))))

(deftest new-book-test
  (testing "AC #2: a book with no page yet gets its page and empty data files"
    (let [[root dir] (built-site)
          _ (write-file! root "reformed/novo.hdoc" "---\ntitle: Novo\n---\n\n# Novo {#sec:novo}\n\nVeja @{reformed:catkins}.\n")
          {:keys [status err]} (build-doc! root dir "reformed:novo")
          page-dir "books/reformed/novo/"]
      (is (= 0 status) err)
      (is (= "rebuilt  reformed:novo  (requested)\n1 book rebuilt, 0 dependent books not rebuilt; no warnings\n" err))
      (is (.isFile (io/file dir (str page-dir "index.html"))))
      (is (= {"version" 1 "book" "reformed:novo" "passage" nil "edges" [] "self" []
              "summary" {"references" 0 "books" 0 "self" 0}}
             (json/read-str (read-file dir (str page-dir "backlinks.json")))))
      (is (= [] (get (json/read-str (read-file dir (str page-dir "previews.json"))) "previews")))
      (is (= ["page reformed:novo"] (pending-lines dir)))
      (build-site! root dir)
      (same-as-clean? root dir "a new book"))))

(deftest images-test
  (testing "AC #2: a copied image is recorded in .haselnuss/images, and a later sweep removes a stale one"
    (let [[root dir] (built-site)
          catkins "reformed/catkins/book.hdoc"
          original (read-file root catkins)
          _ (write-file! root "reformed/catkins/second.png" (Files/readAllBytes (.toPath (io/file root "reformed/catkins/catkins.png"))))
          _ (write-file! root catkins (str original "\n![Another](second.png)\n"))
          {:keys [status err]} (build-doc! root dir "reformed:catkins")
          manifest #(set (str/split-lines (read-file dir ".haselnuss/images")))]
      (is (= 0 status) err)
      (is (.isFile (io/file dir "books/reformed/catkins/second.png")))
      (is (str/includes? (read-file dir "books/reformed/catkins/index.html") "\"second.png\""))
      (is (contains? (manifest) "books/reformed/catkins/second.png"))
      (is (contains? (manifest) "books/reformed/catkins/catkins.png") "the manifest keeps what it had")
      (is (not (.exists (io/file dir ".haselnuss/images.pending"))))
      (write-file! root catkins original)
      (build-site! root dir)
      (is (not (.exists (io/file dir "books/reformed/catkins/second.png"))) "the copy nothing shows is swept")
      (same-as-clean? root dir "an image added, then removed"))))

;; --- AC #5: refusals ---------------------------------------------------------------

(defn- refused? [root dir message & args]
  (let [before (whole-tree dir)
        {:keys [status err]} (run!* (into ["--libraries" (libraries root)] args))]
    (is (= 2 status) (pr-str args err))
    (is (str/includes? err message) (pr-str args err))
    (is (= before (whole-tree dir)) (str (pr-str args) ": nothing written"))))

(deftest refusals-write-nothing-test
  (let [[root dir] (built-site)
        site (str dir)]
    (testing "a folder that is not a site: empty, absent, or holding other files"
      (let [empty-dir (temp-dir)]
        (refused? root empty-dir "not a Haselnuss site" "--build-doc" "reformed:catkins" "--site" (str empty-dir))
        (let [absent (io/file empty-dir "absent")]
          (refused? root empty-dir "not a Haselnuss site" "--build-doc" "reformed:catkins" "--site" (str absent))
          (is (not (.exists absent))))
        (write-file! empty-dir "notes.txt" "mine")
        (refused? root empty-dir "not a Haselnuss site" "--build-doc" "reformed:catkins" "--site" (str empty-dir))))
    (testing "an unknown library or book, an ambiguous one, a passage"
      (refused? root dir "the library directory has no library \"nowhere\"" "--build-doc" "nowhere:x" "--site" site)
      (refused? root dir "library \"reformed\" has no book \"nothing\""
                "--build-doc" "reformed:catkins" "reformed:nothing" "--site" site)
      (refused? root dir "names more than one book" "--build-doc" "reformed:notas" "--site" site)
      (refused? root dir "not passages" "--build-doc" "reformed:catkins#sec:flowers" "--site" site))
    (testing "another build holding the lock"
      (let [lock (site/acquire-lock! dir)]
        (try
          (refused? root dir "another build holds" "--build-doc" "reformed:catkins" "--site" site)
          (finally (site/release-lock! lock)))))
    (testing "other inputs than the site's: the directory file, --extensions, --transform, --trusted-extensions"
      (let [rule (str (write-file! root "rule.clj" "{:select :para :transform (fn [n _] n)}"))
            other-yaml (write-file! root "other.yaml" (str (read-file root "libraries.yaml") "\n# changed\n"))]
        (refused? root dir "differ from the ones the site was built with"
                  "--build-doc" "reformed:catkins" "--site" site "--transform" rule)
        (refused? root dir "differ from the ones the site was built with"
                  "--build-doc" "reformed:catkins" "--site" site "--extensions" rule)
        (refused? root dir "--trusted-extensions was given or withdrawn"
                  "--build-doc" "reformed:catkins" "--site" site "--trusted-extensions")
        (let [before (whole-tree dir)
              {:keys [status err]} (run!* ["--libraries" (str other-yaml) "--build-doc" "reformed:catkins" "--site" site])]
          (is (= 2 status) err)
          (is (str/includes? err "differ from the ones the site was built with") err)
          (is (= before (whole-tree dir))))))
    (testing "another Haselnuss version"
      (with-redefs [version/version (constantly "0.0.0-other")]
        (refused? root dir "another Haselnuss version built the site" "--build-doc" "reformed:catkins" "--site" site)))
    (testing "no saved state"
      (let [header (io/file (state/state-dir dir) state/header-name)
            saved (Files/readAllBytes (.toPath header))]
        (.delete header)
        (try
          (refused? root dir "the site has no saved build state" "--build-doc" "reformed:catkins" "--site" site)
          (finally (Files/write (.toPath header) saved
                                ^"[Ljava.nio.file.OpenOption;" (make-array java.nio.file.OpenOption 0))))))
    (testing "and with nothing in the way, it runs"
      (is (= 0 (:status (build-doc! root dir "reformed:catkins")))))))

;; --- AC #6, #7: messages, warnings, exit codes, a failing book ---------------------

(defn- warning-lines
  "The `haselnuss: warning: ...` messages of a conversion's stderr."
  [err]
  (vec (for [[_ m] (re-seq #"(?m)^haselnuss: warning: (.*)$" err)] m)))

(deftest diagnostics-test
  (let [[root dir] (built-site)]
    (testing "AC #6: the warnings a conversion of the book by its ID reports, as --check prints them; exit 1"
      (let [out (io/file (temp-dir) "dangling.html")
            conversion (run!* ["--libraries" (libraries root) "reformed:dangling" "-o" (str out)])
            {:keys [status err]} (build-doc! root dir "reformed:dangling")
            lines (str/split-lines err)]
        (is (= 0 (:status conversion)) (:err conversion))
        (is (= 1 (count (warning-lines (:err conversion)))) (:err conversion))
        (is (= 1 status) err)
        (is (= "rebuilt  reformed:dangling  (requested)" (first lines)) err)
        (is (= 1 (count (filter #(str/includes? % ": warning: [") lines))) err)
        (is (str/includes? (first (warning-lines (:err conversion))) "library \"bible\" has no book \"tobias\""))
        (is (some #(str/ends-with? % "bible:tobias#1:1: library \"bible\" has no book \"tobias\"") lines) err)
        (is (= "1 book rebuilt, 0 dependent books not rebuilt; 1 warning in 1 book" (last lines)))))
    (testing "a stale shelf path in the ID is an info, and the book is built once however often it is named"
      (let [{:keys [status err]} (build-doc! root dir "reformed:old/catkins" "reformed:catkins")]
        (is (= 0 status) err)
        (is (str/includes? err "haselnuss: info: book ID \"reformed:old/catkins\": the book is now at reformed:catkins")
            err)
        (is (= 1 (count (re-seq #"(?m)^rebuilt" err))) err)))))

(deftest one-failing-book-test
  (testing "AC #7: a book that fails keeps its old page, is not pending, and the others are written (exit 1)"
    (let [[root dir] (built-site)
          dangling "reformed/dangling.hdoc"
          old-page (read-file dir "books/reformed/dangling/index.html")
          _ (write-file! root dangling (str/replace (read-file root dangling) "lang: en"
                                                    "lang: en\nbibliography: missing.json"))
          _ (write-file! root heidelberg (str (read-file root heidelberg) "\nUm parágrafo novo.\n"))
          {:keys [status err]} (build-doc! root dir "reformed:dangling" "reformed:catecismo-heidelberg")]
      (is (= 1 status) err)
      (is (str/includes? err "failed   reformed:dangling") err)
      (is (str/includes? err "rebuilt  reformed:catecismo-heidelberg") err)
      (is (re-find #"error: \[emit-failed\] the page of reformed:dangling was not built" err) err)
      (is (str/includes? err "1 of 2 books rebuilt") err)
      (is (= old-page (read-file dir "books/reformed/dangling/index.html")))
      (is (str/includes? (read-file dir heidelberg-page) "Um parágrafo novo."))
      (is (= ["page reformed:catecismo-heidelberg"] (pending-lines dir))))))

(deftest failure-after-pages-were-written-test
  (testing "review: the images manifest failing after the pages were written is exit 1, with the
            rebuilt lines and an emit-failed error -- not the exit 2 of a run that wrote nothing"
    (let [[root dir] (built-site)
          catkins "reformed/catkins/book.hdoc"
          _ (write-file! root catkins (str (read-file root catkins) "\nAn added line.\n"))
          {:keys [status err]} (with-redefs [site/record-image-copies!
                                             (fn [& _] (throw (java.io.IOException. "disk full")))]
                                 (build-doc! root dir "reformed:catkins"))]
      (is (= 1 status) err)
      (is (str/starts-with? err "rebuilt  reformed:catkins  (requested)\n") err)
      (is (re-find #"error: \[emit-failed\] the images copied for reformed:catkins were not recorded in \.haselnuss/images: disk full" err)
          err)
      (is (str/includes? (read-file dir "books/reformed/catkins/index.html") "An added line."))
      (is (contains? (set (str/split-lines (read-file dir ".haselnuss/images.pending")))
                     "books/reformed/catkins/catkins.png")
          "the copy stays listed for a later sweep")
      (is (= ["page reformed:catkins"] (pending-lines dir)))
      (build-site! root dir)
      (same-as-clean? root dir "after a manifest failure")))
  (testing "making the report failing after the pages were written is exit 1 too, with the rebuilt lines"
    (let [[root dir] (built-site)
          {:keys [status err]} (with-redefs [check/sort-items (fn [& _] (throw (IllegalStateException. "boom")))]
                                 (build-doc! root dir "reformed:catkins" "reformed:dangling"))]
      (is (= 1 status) err)
      (is (str/includes? err "rebuilt  reformed:catkins") err)
      (is (str/includes? err "rebuilt  reformed:dangling") err)
      (is (str/includes? err "error: [emit-failed] the report of this --build-doc could not be made: boom") err)
      (is (str/includes? err "2 books rebuilt") err)
      (is (= ["page reformed:catkins" "page reformed:dangling"] (pending-lines dir)))))
  (testing "a failure before any page is written stays exit 2, with nothing written"
    (let [[root dir] (built-site)
          before (whole-tree dir)
          {:keys [status]} (with-redefs [incremental/add-pending-pages!
                                         (fn [& _] (throw (java.io.IOException. "read-only")))]
                             (build-doc! root dir "reformed:catkins"))]
      (is (= 2 status))
      (is (= (dissoc before ".haselnuss/unchanged") (dissoc (whole-tree dir) ".haselnuss/unchanged"))
          "no page, image or data file written (only the unchanged-site record may be gone)"))))

;; --- AC #8: the site's write rules --------------------------------------------------

(deftest symbolic-link-test
  (testing "AC #8: a page folder that is a symbolic link is not written through"
    (let [[root dir] (built-site)
          outside (temp-dir)
          page-dir (io/file dir "books/reformed/catkins")]
      (doseq [^File f (reverse (file-seq page-dir))] (.delete f))
      (Files/createSymbolicLink (.toPath page-dir) (.toPath outside) (make-array FileAttribute 0))
      (let [{:keys [status err]} (build-doc! root dir "reformed:catkins")]
        (is (= 1 status) err)
        (is (str/includes? err "failed   reformed:catkins  (its page could not be written)") err)
        (is (str/includes? err "symbolic link") err)
        (is (empty? (.list outside)) "nothing is written where the link leads")
        (is (= ["page reformed:catkins"] (pending-lines dir)) "the next --build-site rebuilds it")))))

(deftest no-temp-files-test
  (testing "AC #8: every file is written by rename; no temporary file is left"
    (let [[root dir] (built-site)]
      (build-doc! root dir "reformed:catkins" "bible:romanos")
      (is (empty? (for [^File f (file-seq dir) :when (str/ends-with? (.getName f) ".tmp")] f))))))

;; --- TASK-329: the catalog is patched ---------------------------------------------------

(defn- catalog-tree
  "`{relative-path digest}` of the files under `catalog/` of the site `dir`."
  [^File dir]
  (into (sorted-map) (filter (fn [[rel _]] (str/starts-with? rel "catalog/"))) (site-tree dir)))

(defn- catalog-mtimes [^File dir]
  (into (sorted-map)
        (for [^File f (file-seq (io/file dir "catalog")) :when (.isFile f)]
          [(rel-path dir f) (Files/getLastModifiedTime (.toPath f) (make-array LinkOption 0))])))

(defn- catalog-equals-clean-build?
  "The catalog of `dir` is that of a clean build of `root`."
  [^File root ^File dir what]
  (let [clean (io/file (temp-dir) "site")]
    (build-site! root clean)
    (is (= (catalog-tree clean) (catalog-tree dir))
        (str what ": catalog differs at " (vec (tree-diff (catalog-tree clean) (catalog-tree dir)))))))

(defn- catalog-entry [^File dir lib id-text]
  (let [index (json/read-str (read-file dir "catalog/index.json"))
        pages (get (first (filter #(= lib (get % "name")) (get index "libraries"))) "pages")]
    (first (for [i (range 1 (inc pages))
                 e (get (json/read-str (read-file dir (str "catalog/" lib "/" i ".json"))) "books")
                 :when (= id-text (get e "id"))]
             e))))

(deftest catalog-patched-test
  (testing "AC #1: a new book, a changed title and changed authors give a clean build's catalog"
    (let [[root dir] (built-site)
          _ (write-file! root "reformed/novo.hdoc" "---\ntitle: Aaa Novo\n---\n\n# Novo {#sec:novo}\n\nTexto.\n")
          {:keys [status err]} (build-doc! root dir "reformed:novo")]
      (is (= 0 status) err)
      (is (= "Aaa Novo" (get (catalog-entry dir "reformed" "reformed:novo") "title")))
      (catalog-equals-clean-build? root dir "a new book")
      (write-file! root "reformed/novo.hdoc" "---\ntitle: Zzz Novo\n---\n\n# Novo {#sec:novo}\n\nTexto.\n")
      (is (= 0 (:status (build-doc! root dir "reformed:novo"))))
      (is (= "Zzz Novo" (get (catalog-entry dir "reformed" "reformed:novo") "title")))
      (catalog-equals-clean-build? root dir "a changed title")
      (write-file! root "reformed/dangling.hdoc"
                   (str/replace (read-file root "reformed/dangling.hdoc") "lang: en"
                                "authors:\n  - Maria Avelã\nlang: en"))
      (is (= 1 (:status (build-doc! root dir "reformed:dangling"))) "the dangling reference warns")
      (is (= ["Maria Avelã"] (get (catalog-entry dir "reformed" "reformed:dangling") "authors")))
      (catalog-equals-clean-build? root dir "changed authors")
      (build-site! root dir)
      (same-as-clean? root dir "a following --build-site")))
  (testing "AC #2: only the files whose bytes changed are written"
    (let [[root dir] (built-site)
          before (catalog-mtimes dir)
          bytes-before (catalog-tree dir)
          ;; a rebuilt book with the same title and authors: nothing changes
          _ (write-file! root heidelberg (str (read-file root heidelberg) "\nUm parágrafo novo.\n"))
          _ (is (= 0 (:status (build-doc! root dir "reformed:catecismo-heidelberg"))))]
      (is (= bytes-before (catalog-tree dir)))
      (is (= before (catalog-mtimes dir)) "no catalog file rewritten")
      (write-file! root "reformed/dangling.hdoc"
                   (str/replace (read-file root "reformed/dangling.hdoc") "A Dangling Reference" "Retitled"))
      (is (= 1 (:status (build-doc! root dir "reformed:dangling"))) "the dangling reference warns")
      (let [after (catalog-mtimes dir)
            changed (set (for [[k v] after :when (not= v (get before k))] k))]
        (is (= #{"catalog/reformed/1.json"} changed)
            "the page of the library changed; the index and the other library's pages did not"))))
  (testing "AC #3: a book that failed to build leaves the catalog unchanged"
    (let [[root dir] (built-site)
          before (whole-tree (io/file dir "catalog"))
          dangling "reformed/dangling.hdoc"
          _ (write-file! root dangling (str/replace (read-file root dangling) "lang: en"
                                                    "bibliography: missing.json\nlang: en"))
          _ (write-file! root dangling (str/replace (read-file root dangling) "A Dangling Reference" "Not Seen"))
          {:keys [status]} (build-doc! root dir "reformed:dangling")]
      (is (= 1 status))
      (is (= before (whole-tree (io/file dir "catalog")))))))

(defn- synthetic [lib n title-of]
  (for [i (range n)
        :let [slug (format "b%04d" i)]]
    {:id (:id (book-ref/parse-book-ref (str lib ":" slug)))
     :title (title-of i) :authors ["A"] :page (str "books/" lib "/" slug "/index.html")}))

(deftest catalog-patch-page-boundary-test
  (testing "AC #1: patching equals catalog-files over the books with the new entry, across a page boundary"
    (let [big (vec (synthetic "big" site/catalog-page-size #(format "Title %04d" (inc %))))
          other (vec (synthetic "other" 3 #(str "O" %)))
          dir (temp-dir)
          check! (fn [books libs patch what]
                   (let [written (site/patch-catalog! dir patch)
                         expected (site/catalog-files books libs)
                         actual (into {} (for [^File f (file-seq (io/file dir "catalog")) :when (.isFile f)]
                                           [(rel-path dir f) (vec (Files/readAllBytes (.toPath f)))]))]
                     (is (= (update-vals expected vec) actual) what)
                     written))
          zero {:id (:id (book-ref/parse-book-ref "big:zero")) :title "Title 0000" :authors ["Z"]
                :page "books/big/zero/index.html"}
          retitled (assoc zero :title "Title 9999" :authors ["Y" "W"])
          fresh (first (synthetic "fresh" 1 (constantly "Só")))]
      (doseq [[rel bs] (site/catalog-files (concat big other) ["big" "other"])]
        (site/write-under! dir rel bs true))
      (is (= 1 (count (filter #(str/starts-with? % "catalog/big/") (keys (site/catalog-files big ["big"]))))))
      (testing "a new book sorting first pushes a book onto a second page"
        (is (= #{"catalog/big/1.json" "catalog/big/2.json" "catalog/index.json"}
               (set (check! (concat (cons zero big) other) ["big" "other"] [zero] "crossing 500")))))
      (testing "a changed title and changed authors: the entry is replaced"
        (check! (concat (cons retitled big) other) ["big" "other"] [retitled] "retitled"))
      (testing "a new library has no pages yet"
        (is (= #{"catalog/fresh/1.json" "catalog/index.json"}
               (set (check! (concat [fresh retitled] big other) ["big" "other" "fresh"] [fresh] "a new library"))))))))

(deftest catalog-patch-refusals-test
  (let [book {:id (:id (book-ref/parse-book-ref "reformed:novo")) :title "Novo" :authors []
              :page "books/reformed/novo/index.html"}
        patch-error (fn [^File dir]
                      (try (site/patch-catalog! dir [book]) nil
                           (catch clojure.lang.ExceptionInfo e [(:type (ex-data e)) (ex-message e)])))]
    (testing "a symbolic link is not followed: index.json, or a library's folder"
      (let [[_ dir] (built-site)
            outside (temp-dir)
            before (catalog-tree dir)
            index (io/file dir "catalog/index.json")]
        (Files/copy (.toPath index) (.toPath (io/file outside "index.json"))
                    ^"[Ljava.nio.file.CopyOption;" (make-array java.nio.file.CopyOption 0))
        (.delete index)
        (Files/createSymbolicLink (.toPath index) (.toPath (io/file outside "index.json")) (make-array FileAttribute 0))
        (is (= :haselnuss.site/bad-catalog (first (patch-error dir))))
        (is (str/includes? (second (patch-error dir)) "symbolic link"))
        (Files/delete (.toPath index))
        (Files/copy (.toPath (io/file outside "index.json")) (.toPath index)
                    ^"[Ljava.nio.file.CopyOption;" (make-array java.nio.file.CopyOption 0))
        (is (= before (catalog-tree dir)) "index.json restored")
        (let [lib (io/file dir "catalog/reformed")
              moved (io/file outside "reformed")]
          (Files/move (.toPath lib) (.toPath moved) ^"[Ljava.nio.file.CopyOption;" (make-array java.nio.file.CopyOption 0))
          (Files/createSymbolicLink (.toPath lib) (.toPath moved) (make-array FileAttribute 0))
          (is (= :haselnuss.site/bad-catalog (first (patch-error dir))))
          (is (= (get before "catalog/reformed/1.json") (sha (Files/readAllBytes (.toPath (io/file moved "1.json")))))
              "nothing written where the link leads"))))
    (testing "pages that is not a non-negative integer"
      (doseq [bad ["\"x\"" "-1" "1.5" "null"]]
        (let [[_ dir] (built-site)
              index (read-file dir "catalog/index.json")
              broken (str/replace-first index #"\"pages\":\d+" (str "\"pages\":" bad))]
          (is (not= index broken))
          (spit (io/file dir "catalog/index.json") broken)
          (let [[kind message] (patch-error dir)]
            (is (= :haselnuss.site/bad-catalog kind) bad)
            (is (str/includes? message "non-negative integer") message)))))))

(deftest catalog-failure-reported-test
  (testing "a catalog that cannot be patched is an emit-failed error (exit 1); the page stays written"
    (let [[root dir] (built-site)
          _ (write-file! root "reformed/novo.hdoc" "---\ntitle: Novo\n---\n\n# N {#sec:n}\n\nT.\n")
          index (io/file dir "catalog/index.json")
          _ (spit index "{not json")
          before (catalog-tree dir)
          {:keys [status err]} (build-doc! root dir "reformed:novo")]
      (is (= 1 status) err)
      (is (re-find #"error: \[emit-failed\] the catalog entry of reformed:novo was not written: catalog/index.json is not JSON" err)
          err)
      (is (str/starts-with? err "rebuilt  reformed:novo") err)
      (is (.isFile (io/file dir "books/reformed/novo/index.html")))
      (is (= ["page reformed:novo"] (pending-lines dir)))
      (is (= before (catalog-tree dir)) "the catalog is untouched"))))

(deftest serve-sees-the-patched-catalog-test
  (testing "AC #4: a server started before --build-doc finds the new and the retitled book after a reload"
    (let [[root dir] (built-site)
          srv (serve/start! {:dir (str dir) :port 0 :reload-interval-ms 0 :threads 2})
          search (fn [q] (mapv #(get % "title")
                               (get (json/read-str (slurp (str "http://127.0.0.1:" (:port srv) "/api/search?q=" q)))
                                    "books")))]
      (try
        (is (= [] (search "haselnussstrauch")))
        (write-file! root "reformed/novo.hdoc" "---\ntitle: Haselnussstrauch Novo\n---\n\n# N {#sec:n}\n\nT.\n")
        (write-file! root "reformed/dangling.hdoc"
                     (str/replace (read-file root "reformed/dangling.hdoc") "A Dangling Reference" "Ein Zwergwuchs"))
        (is (= 1 (:status (build-doc! root dir "reformed:novo" "reformed:dangling"))))
        (is (= ["Haselnussstrauch Novo"] (search "haselnussstrauch")))
        (is (= ["Ein Zwergwuchs"] (search "zwergwuchs")))
        (is (= [] (search "dangling")))
        (finally (serve/stop! srv))))))
