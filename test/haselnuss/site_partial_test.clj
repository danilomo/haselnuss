(ns haselnuss.site-partial-test
  "Partial sites (TASK-330; doc-13, section 5): a site that holds pages
  only for its chosen books, listed in `.haselnuss/chosen`. `--build-doc`
  makes one of an empty or absent folder and adds to it, `--build-site`
  keeps the chosen books up to date and builds no other, and `--build-site
  --all-books` makes it full. Built from copies of the site fixture set
  (`test/fixtures/site/`), every file a test writes touched with an old,
  distinct modification time, as in `haselnuss.site-incremental-test`
  (whose seeded edit sequence covers partial sites too:
  `partial-edit-sequence-matches-clean-builds-test`)."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.book-ref :as book-ref]
            [haselnuss.cli :as cli]
            [haselnuss.library.digest :as digest]
            [haselnuss.library.state :as state]
            [haselnuss.sandbox :as sandbox]
            [haselnuss.site :as site]
            [haselnuss.site.chosen :as chosen]
            [haselnuss.site.unchanged :as unchanged]
            [haselnuss.test-sandbox :as test-sandbox]
            [haselnuss.version :as version])
  (:import (java.io File StringWriter)
           (java.nio.file CopyOption Files LinkOption)
           (java.nio.file.attribute FileAttribute FileTime)))

;; --- helpers -----------------------------------------------------------------------

(def ^:private ^File fixture-dir (io/file "test/fixtures/site"))

(defn- temp-dir ^File []
  (.toFile (Files/createTempDirectory "haselnuss-partial" (make-array FileAttribute 0))))

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
    (spit f content)
    (touch! f)
    f))

(defn- read-file [^File root rel] (slurp (io/file root rel)))

(defn- exists? [^File root rel] (.isFile (io/file root rel)))

(defn- fixture-copy ^File []
  (let [root (temp-dir)]
    (doseq [^File f (file-seq fixture-dir) :when (.isFile f)]
      (let [target (io/file root (rel-path fixture-dir f))]
        (io/make-parents target)
        (io/copy f target)
        (touch! target)))
    root))

(defn- run!*
  "`args` through `cli/main*` with no real environment: `{:status :err}`."
  [^File root args]
  (let [err (StringWriter.)
        status (binding [*err* err *out* (StringWriter.)]
                 (cli/main* args {:library-env {} :library-home "/nonexistent/haselnuss-test-home"
                                  :haselnuss.cli/check-base-dir (.getCanonicalPath root)
                                  :haselnuss.cli/site-threads 2}))]
    {:status status :err (str err)}))

(defn- libraries [^File root] (str (io/file root "libraries.yaml")))

(defn- build-site! [^File root dir & args]
  (run!* root (into ["--libraries" (libraries root) "--build-site" (str dir)] args)))

(defn- build-doc! [^File root dir & args]
  (run!* root (into ["--libraries" (libraries root) "--build-doc"] (concat args ["--site" (str dir)]))))

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
  "`{relative-path [digest mtime]}` of everything under `dir`."
  [^File dir]
  (into (sorted-map)
        (for [^File f (file-seq dir)
              :let [rel (rel-path dir f)]
              :when (not= "" rel)]
          [rel (if (.isDirectory f)
                 :dir
                 [(sha (Files/readAllBytes (.toPath f)))
                  (Files/getLastModifiedTime (.toPath f) (make-array LinkOption 0))])])))

(defn- mtimes [^File dir]
  (into (sorted-map)
        (for [^File f (file-seq dir)
              :let [rel (rel-path dir f)]
              :when (and (.isFile f) (not (str/starts-with? rel ".haselnuss")))]
          [rel (Files/getLastModifiedTime (.toPath f) (make-array LinkOption 0))])))

(defn- tree-diff [a b]
  (sort (for [k (set/union (set (keys a)) (set (keys b))) :when (not= (get a k) (get b k))] k)))

(defn- pages
  "The ids whose page the site `dir` holds, as `site/page-path` would
  name them back: `lib:shelf/.../slug`."
  [^File dir]
  (into (sorted-set)
        (for [[rel v] (site-tree dir)
              :let [[_ lib more] (re-matches #"books/([^/]+)/(.+)/index\.html" rel)]
              :when (and lib (not= :dir v))]
          (str lib ":" more))))

(defn- chosen-of
  "The ids the chosen list of the site `dir` names, as text, in its order."
  [dir]
  (mapv book-ref/format-book-id (:ids (chosen/read-chosen dir))))

(defn- fresh-partial
  "A new partial site holding nothing yet but the chosen list `ids` (texts)."
  ^File [ids]
  (let [dir (io/file (temp-dir) "site")]
    (.mkdirs (state/state-dir dir))
    (chosen/write-chosen! dir {} (map (comp :id book-ref/parse-book-ref) ids))
    dir))

(defn- summary [err] (last (str/split-lines err)))

(defn- problems [line] (second (str/split line #"; " 2)))

(defn- same-as-clean-partial?
  "Assert that the site `dir`, just built from `root` with `result`, is byte
  for byte a clean `--build-site` of a fresh partial site with the same
  chosen list, with the same status and summary."
  [^File root ^File dir result what]
  (let [clean-dir (fresh-partial (chosen-of dir))
        clean-result (build-site! root clean-dir)
        clean (site-tree clean-dir)
        tree (site-tree dir)]
    (is (= clean tree) (str what ": differs from a clean partial build at " (vec (tree-diff clean tree))
                            "\n" (:err result)))
    (is (= (:status clean-result) (:status result)) what)
    (is (= (problems (summary (:err clean-result))) (problems (summary (:err result))))
        (str what "\nclean: " (:err clean-result) "\nincremental: " (:err result)))
    (is (= (chosen-of clean-dir) (chosen-of dir)) (str what ": the same chosen list"))))

(defn- rebuilt-ids [err]
  (into {} (for [[_ id reason] (re-seq #"(?m)^rebuilt  (\S+)\s+\((.*)\)$" err)] [id reason])))

(defn- catalog-ids
  "Every id the catalog of the site `dir` lists, and the libraries its index names."
  [^File dir]
  (let [index (json/read-str (read-file dir "catalog/index.json"))]
    {:libraries (mapv #(get % "name") (get index "libraries"))
     :ids (into (sorted-set)
                (for [{lib "name" n "pages"} (get index "libraries")
                      i (range 1 (inc n))
                      e (get (json/read-str (read-file dir (str "catalog/" lib "/" i ".json"))) "books")]
                  (get e "id")))}))

(defn- backlink-sources
  "The ids of the books the `backlinks.json` of `page-dir` lists."
  [^File dir page-dir]
  (into (sorted-set) (map #(get-in % ["from" "id"]))
        (get (json/read-str (read-file dir (str page-dir "backlinks.json"))) "edges")))

(defn- preview-keys [^File dir page-dir]
  (mapv #(get % "passage") (get (json/read-str (read-file dir (str page-dir "previews.json"))) "previews")))

(def ^:private chosen-four
  ["reformed:estudos/queda" "bible:genesis" "bible:romanos" "reformed:dangling"])

(defn- partial-site
  "A fixture copy and a partial site of `ids` made by `--build-doc` and
  built by `--build-site`: `[root dir]`."
  [ids]
  (let [root (fixture-copy)
        dir (io/file (temp-dir) "site")
        made (apply build-doc! root dir ids)
        built (build-site! root dir)]
    (is (not= 2 (:status made)) (:err made))
    (is (not= 2 (:status built)) (:err built))
    [root dir]))

;; --- AC #1: --build-doc makes a partial site -----------------------------------

(deftest build-doc-makes-a-partial-site-test
  (let [root (fixture-copy)
        full (io/file (temp-dir) "full")
        _ (build-site! root full)
        full-inputs (:inputs (:header (state/header-check full)))]
    (doseq [[what ^File dir] [["an empty folder" (temp-dir)]
                              ["an absent folder" (io/file (temp-dir) "a" "site")]]]
      (testing (str "AC #1: " what " becomes a partial site holding the named books")
        (let [{:keys [status err]} (build-doc! root dir "reformed:estudos/queda")]
          (is (= 0 status) err)
          (is (= "rebuilt  reformed:estudos/queda  (requested)\n1 book rebuilt, 0 dependent books not rebuilt; no warnings\n"
                 err))
          (is (every? #(exists? dir %) site/app-files) "the reader app's files")
          (is (.isDirectory (state/state-dir dir)) "the state folder, so it is a site")
          (is (site/site? dir))
          (is (chosen/partial-site? dir))
          (let [{:keys [header]} (chosen/read-chosen dir)]
            (is (= (version/version) (:haselnuss header)) "the header holds the Haselnuss version")
            (is (= full-inputs (:inputs header)) "and the inputs a site build records"))
          (is (= ["reformed:estudos/queda"] (chosen-of dir)))
          (is (= #{"reformed:estudos/queda"} (pages dir)))
          (is (= {:libraries ["reformed"] :ids #{"reformed:estudos/queda"}} (catalog-ids dir)))
          (is (= :missing (:reason (state/header-check dir))) "no saved state yet"))
        (testing "and a later run adds its books to it"
          (let [{:keys [status err]} (build-doc! root dir "bible:genesis" "reformed:catkins")]
            (is (= 0 status) err)
            (is (= ["bible:blivre/genesis" "reformed:catkins" "reformed:estudos/queda"] (chosen-of dir)))
            (is (= #{"bible:blivre/genesis" "reformed:catkins" "reformed:estudos/queda"} (pages dir)))
            (is (= {:libraries ["bible" "reformed"]
                    :ids #{"bible:blivre/genesis" "reformed:catkins" "reformed:estudos/queda"}}
                   (catalog-ids dir)))))
        (testing "and --build-site then keeps it a clean partial build's"
          (let [r (build-site! root dir)]
            (is (= 0 (:status r)) (:err r))
            (same-as-clean-partial? root dir r what)))))
    (testing "AC #1: --build-site on an empty folder still makes a full site"
      (let [dir (temp-dir)
            {:keys [status err]} (build-site! root dir)]
        (is (= 1 status) err)
        (is (not (chosen/partial-site? dir)))
        (is (= (pages full) (pages dir)))
        (is (= 9 (count (pages dir))))))
    (testing "a non-empty folder that is not a site is still refused, with nothing written"
      (let [dir (temp-dir)]
        (write-file! dir "notes.txt" "mine")
        (let [before (whole-tree dir)
              {:keys [status err]} (build-doc! root dir "reformed:catkins")]
          (is (= 2 status) err)
          (is (str/includes? err "neither empty nor a Haselnuss site") err)
          (is (= before (whole-tree dir))))))
    (testing "an unknown book in a new site is refused before the folder is made"
      (let [dir (io/file (temp-dir) "site")
            {:keys [status err]} (build-doc! root dir "reformed:nothing")]
        (is (= 2 status) err)
        (is (not (.exists dir)))))))

;; --- AC #2: a partial site with no saved state ---------------------------------

(deftest build-doc-compares-with-the-chosen-header-test
  (let [root (fixture-copy)
        dir (io/file (temp-dir) "site")
        _ (is (= 0 (:status (build-doc! root dir "reformed:catkins"))))
        refused? (fn [message & args]
                   (let [before (whole-tree dir)
                         {:keys [status err]} (run!* root (into ["--libraries" (libraries root)] args))]
                     (is (= 2 status) (pr-str args err))
                     (is (str/includes? err message) (pr-str args err))
                     (is (= before (whole-tree dir)) (str (pr-str args) ": nothing written"))))
        site (str dir)]
    (is (= :missing (:reason (state/header-check dir))) "no saved state")
    (testing "AC #2: other inputs than the chosen list's header records are refused, exit 2"
      (let [rule (str (write-file! root "rule.clj" "{:select :para :transform (fn [n _] n)}"))
            other-yaml (write-file! root "other.yaml" (str (read-file root "libraries.yaml") "\n# changed\n"))]
        (refused? "differ from the ones the site was built with"
                  "--build-doc" "reformed:dangling" "--site" site "--transform" rule)
        (refused? "--trusted-extensions was given or withdrawn"
                  "--build-doc" "reformed:dangling" "--site" site "--trusted-extensions")
        (let [before (whole-tree dir)
              {:keys [status err]} (run!* root ["--libraries" (str other-yaml) "--build-doc" "reformed:dangling"
                                                "--site" site])]
          (is (= 2 status) err)
          (is (str/includes? err "differ from the ones the site was built with") err)
          (is (= before (whole-tree dir))))))
    (testing "AC #2: so is another Haselnuss version"
      (with-redefs [version/version (constantly "0.0.0-other")]
        (refused? "another Haselnuss version built the site" "--build-doc" "reformed:dangling" "--site" site)))
    (testing "a chosen list that cannot be read is refused: the books it holds would be lost"
      (let [f (chosen/chosen-file dir)
            saved (read-file dir chosen/chosen-rel)]
        (spit f "not a chosen list\n")
        (refused? "does not start with" "--build-doc" "reformed:dangling" "--site" site)
        (spit f saved)))
    (testing "and with the same inputs and version it runs, adding the book"
      (let [{:keys [status err]} (build-doc! root dir "reformed:dangling")]
        (is (= 1 status) err)
        (is (= ["reformed:catkins" "reformed:dangling"] (chosen-of dir)))))))

;; --- AC #3, #5: --build-site on a partial site -------------------------------------

(deftest build-site-on-a-partial-site-test
  (let [[root dir] (partial-site chosen-four)
        full (io/file (temp-dir) "full")
        _ (build-site! root full)]
    (testing "AC #3: only the chosen books get a page and a catalog entry"
      (is (= #{"bible:blivre/genesis" "bible:blivre/romanos" "reformed:dangling" "reformed:estudos/queda"} (pages dir)))
      (is (= {:libraries ["bible" "reformed"]
              :ids #{"bible:blivre/genesis" "bible:blivre/romanos" "reformed:dangling" "reformed:estudos/queda"}}
             (catalog-ids dir)))
      (is (empty? (filter #(and (str/starts-with? % "books/") (str/ends-with? % ".json")
                                (not (some (fn [p] (str/starts-with? % (str "books/" (str/replace p ":" "/") "/")))
                                           (pages dir))))
                          (keys (site-tree dir))))
          "no data file of an unchosen book"))
    (testing "AC #3: the chosen ids are canonical, and the header is this build's"
      (is (= ["bible:blivre/genesis" "bible:blivre/romanos" "reformed:dangling" "reformed:estudos/queda"] (chosen-of dir)))
      (is (= (:inputs (:header (state/header-check dir))) (:inputs (:header (chosen/read-chosen dir))))))
    (testing "AC #5: backlinks.json and previews.json count references from chosen books only"
      (is (= #{"reformed:catecismo-heidelberg"} (backlink-sources full "books/bible/blivre/romanos/"))
          "not vacuous: in the full site the catechism links to Romans")
      (is (seq (preview-keys full "books/bible/blivre/romanos/")))
      (is (= #{} (backlink-sources dir "books/bible/blivre/romanos/")))
      (is (= [] (preview-keys dir "books/bible/blivre/romanos/")))
      (is (= #{"reformed:estudos/queda"} (backlink-sources dir "books/bible/blivre/genesis/")))
      (is (= (preview-keys full "books/bible/blivre/genesis/") (preview-keys dir "books/bible/blivre/genesis/")))
      (is (= #{} (backlink-sources dir "books/reformed/estudos/queda/"))))
    (testing "AC #3: the summary and the problems are the chosen books' only"
      (let [r (build-site! root dir)]
        (is (= "0 of 4 books rebuilt, 4 up to date; 1 warning in 1 book" (summary (:err r))) (:err r))
        (is (not (str/includes? (:err r) "duplicate-slug")) "the shared id of an unchosen book is not reported")
        (same-as-clean-partial? root dir r "a partial site")))
    (testing "AC #3: a page is the same bytes as the full site's page of the book"
      (is (= (read-file full "books/reformed/estudos/queda/index.html")
             (read-file dir "books/reformed/estudos/queda/index.html"))))
    (testing "AC #3: a header another version wrote is rewritten by the next --build-site"
      (let [f (chosen/chosen-file dir)]
        (spit f (str/replace (slurp f) (pr-str (version/version)) (pr-str "0.0.0-old")))
        (is (= "0.0.0-old" (:haselnuss (:header (chosen/read-chosen dir)))))
        (build-site! root dir)
        (is (= (version/version) (:haselnuss (:header (chosen/read-chosen dir)))))))))

(deftest a-chosen-book-joins-test
  (testing "AC #5: a book --build-doc adds counts in the data files of the books it refers to,
            once --build-site has run, though neither they nor the graph changed"
    (let [[root dir] (partial-site chosen-four)
          _ (is (= #{} (backlink-sources dir "books/bible/blivre/romanos/")))
          doc (build-doc! root dir "reformed:catecismo-heidelberg")
          r (build-site! root dir)]
      (is (= 0 (:status doc)) (:err doc))
      (is (= {"reformed:catecismo-heidelberg" "left pending by an earlier run"} (rebuilt-ids (:err r))) (:err r))
      (is (= #{"reformed:catecismo-heidelberg"} (backlink-sources dir "books/bible/blivre/romanos/")))
      (is (seq (preview-keys dir "books/bible/blivre/romanos/")))
      (is (= #{"reformed:estudos/queda"} (backlink-sources dir "books/reformed/catecismo-heidelberg/"))
          "and its own data files count the chosen books referring to it")
      (same-as-clean-partial? root dir r "a book added by --build-doc"))))

;; --- AC #6, #7: moved, gone and kept chosen books -----------------------------------

(defn- info-lines [err] (vec (for [[_ m] (re-seq #"(?m)^haselnuss: info: (.*)$" err)] m)))
(defn- warning-lines [err] (vec (for [[_ m] (re-seq #"(?m)^haselnuss: warning: (.*)$" err)] m)))

(deftest moved-and-gone-chosen-books-test
  (let [[root dir] (partial-site chosen-four)]
    (testing "AC #6: a chosen book whose shelf moved is rewritten to its canonical id"
      (io/make-parents (io/file root "reformed/moved/x"))
      (Files/move (.toPath (io/file root "reformed/dangling.hdoc")) (.toPath (io/file root "reformed/moved/dangling.hdoc"))
                  (make-array CopyOption 0))
      (let [r (build-site! root dir)]
        (is (some #(= % (str "chosen book reformed:dangling is now at reformed:moved/dangling; "
                             ".haselnuss/chosen names it so now"))
                  (info-lines (:err r)))
            (:err r))
        (is (= ["bible:blivre/genesis" "bible:blivre/romanos" "reformed:estudos/queda" "reformed:moved/dangling"]
               (chosen-of dir)))
        (is (contains? (pages dir) "reformed:moved/dangling"))
        (is (not (contains? (pages dir) "reformed:dangling")) "the old page is swept")
        (same-as-clean-partial? root dir r "a chosen book moved")))
    (testing "AC #7: a chosen book that is definitely gone is dropped with an info line, its page swept"
      (.delete (io/file root "reformed/estudos/queda.hdoc"))
      (let [r (build-site! root dir)]
        (is (some #(str/starts-with? % "chosen book reformed:estudos/queda is no longer in its library") (info-lines (:err r)))
            (:err r))
        (is (re-find #"(?m)^removed +reformed:estudos/queda" (:err r)) (:err r))
        (is (= ["bible:blivre/genesis" "bible:blivre/romanos" "reformed:moved/dangling"] (chosen-of dir)))
        (is (not (contains? (pages dir) "reformed:estudos/queda")))
        (is (= #{} (backlink-sources dir "books/bible/blivre/genesis/")) "its references no longer count")
        (same-as-clean-partial? root dir r "a chosen book deleted")))
    (testing "restoring the file does not choose it again: chosen books are named on the command line"
      (write-file! root "reformed/estudos/queda.hdoc" (slurp (io/file fixture-dir "reformed/estudos/queda.hdoc")))
      (let [r (build-site! root dir)]
        (is (not (contains? (pages dir) "reformed:estudos/queda")))
        (same-as-clean-partial? root dir r "a deleted book restored")))))

(defn- kept?
  "Assert that a build of `dir` from `root` keeps every chosen id and page
  `before` held, warning `reason` about `id`."
  [root dir id reason what & args]
  (let [chosen-before (chosen-of dir)
        tree-before (site-tree dir)
        page-dir (str "books/" (str/replace id ":" "/") "/")
        r (apply build-site! root dir args)]
    (is (some #(and (str/starts-with? % (str "chosen book " id " was not found, and stays chosen with its page kept: "))
                    (str/includes? % reason))
              (warning-lines (:err r)))
        (str what "\n" (:err r)))
    (is (= chosen-before (chosen-of dir)) (str what ": the chosen list is as it was"))
    (doseq [f ["index.html" "backlinks.json" "previews.json"]]
      (is (= (get tree-before (str page-dir f)) (get (site-tree dir) (str page-dir f)))
          (str what ": " page-dir f " is kept")))
    (is (not (re-find (re-pattern (str "(?m)^removed +" (java.util.regex.Pattern/quote id))) (:err r))) (:err r))
    (is (not (.isFile (io/file dir unchanged/record-rel))) (str what ": no record of an unchanged site"))
    r))

(deftest kept-chosen-books-test
  (testing "AC #7: a library missing from the directory file"
    (let [[root dir] (partial-site ["bible:genesis" "reformed:catkins"])]
      (write-file! root "libraries.yaml" "- name: reformed\n  type: filesystem\n  path: reformed\n")
      (kept? root dir "bible:blivre/genesis" "the library directory has no library \"bible\"" "missing library")
      (testing "and when it is back, the book is built as before"
        (write-file! root "libraries.yaml" (slurp (io/file fixture-dir "libraries.yaml")))
        (let [r (build-site! root dir)]
          (same-as-clean-partial? root dir r "library back")))))
  (testing "AC #7: an unavailable library (its folder gone), with the images its page copied"
    (let [[root dir] (partial-site ["bible:genesis" "reformed:catkins"])]
      (Files/move (.toPath (io/file root "reformed")) (.toPath (io/file root "reformed-away")) (make-array CopyOption 0))
      (kept? root dir "reformed:catkins" "its library is unavailable" "unavailable library")
      (is (exists? dir "books/reformed/catkins/catkins.png") "its copied image is kept")
      (testing "and a --full build, with no state to tell the copies, keeps them too"
        (kept? root dir "reformed:catkins" "its library is unavailable" "unavailable library, --full" "--full")
        (is (exists? dir "books/reformed/catkins/catkins.png")))
      (Files/move (.toPath (io/file root "reformed-away")) (.toPath (io/file root "reformed")) (make-array CopyOption 0))
      (let [r (build-site! root dir)]
        (same-as-clean-partial? root dir r "library available again"))))
  (testing "AC #7: a short id that has become ambiguous"
    (let [[root dir] (partial-site ["reformed:dangling" "bible:genesis"])]
      (io/make-parents (io/file root "reformed/a/x"))
      (Files/move (.toPath (io/file root "reformed/dangling.hdoc")) (.toPath (io/file root "reformed/a/dangling.hdoc"))
                  (make-array CopyOption 0))
      (write-file! root "reformed/b/dangling.hdoc" "---\ntitle: Another\n---\n\nAnother.\n")
      (kept? root dir "reformed:dangling" "its slug now names more than one book" "ambiguous")))
  (testing "AC #7: a transient failure (a generated shelf's script ran out of time, with no listing to fall back on)"
    (let [[root dir] (partial-site ["bible:romanos" "reformed:catkins"])]
      (with-redefs [sandbox/sci-call! (test-sandbox/timing-out #"shelf.clj :books")]
        (kept? root dir "bible:blivre/romanos" "did not finish" "transient" "--full"))
      (let [r (build-site! root dir)]
        (same-as-clean-partial? root dir r "after the failure"))))
  (testing "AC #7: a library that could not be listed completely"
    (let [[root dir] (partial-site ["reformed:estudos/queda" "bible:genesis"])
          shelf (io/file root "reformed/estudos")]
      (.delete (io/file shelf "queda.hdoc"))
      (.setReadable shelf false)
      (try
        (when-not (.canRead shelf) ; as root the folder stays readable: nothing to test
          (kept? root dir "reformed:estudos/queda" "could not be listed completely" "unlisted folder"))
        (finally (.setReadable shelf true))))))

(defn- unix-root?
  "True when the tests run as root, which reads and searches any folder."
  []
  (= "root" (System/getProperty "user.name")))

(deftest a-library-listing-no-book-is-not-a-listing-test
  (testing "review: an unmounted library -- its folder there, but empty -- keeps its chosen books,
            with a warning that counts like any other (exit 1)"
    (let [[root dir] (partial-site ["reformed:estudos/queda" "reformed:catkins" "bible:genesis"])]
      (Files/move (.toPath (io/file root "reformed")) (.toPath (io/file root "reformed-away")) (make-array CopyOption 0))
      (.mkdirs (io/file root "reformed"))
      (let [r (kept? root dir "reformed:catkins" "its library lists no books at all" "unmounted library")]
        (is (some #(str/starts-with? % "chosen book reformed:estudos/queda was not found") (warning-lines (:err r))))
        (is (= 1 (:status r)) (:err r))
        (is (str/ends-with? (summary (:err r)) "; 2 warnings in 2 books") (:err r)))
      (testing "and once it is mounted again, the site is a clean partial build's"
        (doseq [^File f (reverse (file-seq (io/file root "reformed")))] (.delete f))
        (Files/move (.toPath (io/file root "reformed-away")) (.toPath (io/file root "reformed")) (make-array CopyOption 0))
        (let [r (build-site! root dir)]
          (is (= ["bible:blivre/genesis" "reformed:catkins" "reformed:estudos/queda"] (chosen-of dir)))
          (same-as-clean-partial? root dir r "mounted again")))))
  (testing "review: a library folder that cannot be searched lists as empty: its chosen books are kept"
    (let [[root dir] (partial-site ["reformed:catkins" "bible:genesis"])
          lib (io/file root "reformed")]
      (.setExecutable lib false false)
      (try
        (if (or (unix-root?) (.canExecute lib))
          (is true "the folder stays searchable (root): nothing to test")
          (kept? root dir "reformed:catkins" "lists no books at all" "unsearchable library folder"))
        (finally (.setExecutable lib true false)))))
  (testing "review: a generated shelf that lists no books keeps its chosen books, though its library lists others"
    (let [root (fixture-copy)
          _ (write-file! root "bible/salmos.hdoc" "---\ntitle: Salmos\n---\n\nUm livro fora da estante.\n")
          dir (io/file (temp-dir) "site")]
      (build-doc! root dir "bible:genesis" "bible:salmos")
      (build-site! root dir)
      (write-file! root "bible/blivre/blivre.json" "[]")
      (kept? root dir "bible:blivre/genesis" "its generated shelf \"blivre\" lists no books at all" "empty shelf")
      (is (contains? (pages dir) "bible:salmos")))))

(deftest library-problems-of-unchosen-libraries-test
  (testing "review: a problem of a library itself (here: unavailable) is reported on a partial site
            whatever its chosen books are, as a full build reports it (exit 1)"
    (let [[root dir] (partial-site ["reformed:catkins"])
          _ (write-file! root "libraries.yaml" (str (read-file root "libraries.yaml")
                                                    "- name: broken\n  type: filesystem\n  path: nowhere\n"))
          full (build-site! root (io/file (temp-dir) "full"))
          r (build-site! root dir)
          line-of (fn [err] (first (filter #(str/includes? % "nowhere") (str/split-lines err))))]
      (is (some? (line-of (:err full))) (:err full))
      (is (= (line-of (:err full)) (line-of (:err r))) (:err r))
      (is (= (:status full) (:status r) 1) (:err r))
      (same-as-clean-partial? root dir r "a broken library added"))))

(defn- kill
  "Run `f`, a `--build-doc` that `with-redefs-fn` `redefs` makes stop where
  a kill would (an Error nothing catches)."
  [redefs f]
  (try (with-redefs-fn redefs f)
       (is false "the run was not stopped")
       (catch Error _ :killed)))

(deftest build-doc-stopped-while-making-a-site-test
  (let [root (fixture-copy)
        stop (fn [& _] (throw (Error. "killed")))]
    (doseq [[what redefs] [["before the chosen list is written" {#'chosen/sync-chosen! stop}]
                           ["after the chosen list, before the reader app" {#'site/copy-app-files! stop}]
                           ["while the book is built" {#'cli/build-doc-one stop}]]]
      (testing (str "review: --build-doc into an absent folder, stopped " what ", leaves a folder the next
                     --build-doc makes, or keeps, a partial site of, with its reader app and catalog")
        (let [dir (io/file (temp-dir) "site")]
          (kill redefs #(build-doc! root dir "reformed:catkins"))
          (is (site/site? dir) what)
          (let [{:keys [status err]} (build-doc! root dir "reformed:dangling")]
            (is (= 1 status) (str what "\n" err))
            (is (str/includes? err "rebuilt  reformed:dangling") err))
          (is (chosen/partial-site? dir) what)
          (is (every? #(exists? dir %) site/app-files) what)
          (is (contains? (:ids (catalog-ids dir)) "reformed:dangling") what)
          (let [r (build-site! root dir)]
            (is (not= 2 (:status r)) (:err r))
            (is (chosen/partial-site? dir) (str what ": --build-site keeps it partial"))
            (same-as-clean-partial? root dir r what)))))))

(deftest build-doc-on-a-partial-site-test
  (testing "review (AC #5): --build-doc lists as stale only the chosen books its backlinks.json names"
    (let [[root dir] (partial-site ["bible:romanos" "reformed:catkins"])
          full (io/file (temp-dir) "full")
          _ (build-site! root full)
          romanos "books/bible/blivre/romanos/backlinks.json"
          _ (is (= #{} (backlink-sources dir "books/bible/blivre/romanos/")))
          ;; a backlinks.json naming an unchosen book, as no build of this
          ;; site writes one: the full site's
          _ (io/copy (io/file full romanos) (io/file dir romanos))
          _ (is (= #{"reformed:catecismo-heidelberg"} (backlink-sources dir "books/bible/blivre/romanos/")))
          {:keys [status err]} (build-doc! root dir "bible:romanos")]
      (is (= 0 status) err)
      (is (= "rebuilt  bible:blivre/romanos  (requested)\n1 book rebuilt, 0 dependent books not rebuilt; no warnings\n"
             err))
      (build-doc! root dir "reformed:catecismo-heidelberg")
      (let [{:keys [err]} (build-doc! root dir "bible:romanos")]
        (is (re-find #"(?m)^stale +reformed:catecismo-heidelberg +\(refers to this book\)$" err)
            (str "once chosen, it is listed\n" err)))))
  (testing "review: a line of the chosen list that is no book id is left out with a warning"
    (let [[root dir] (partial-site ["reformed:catkins"])
          f (chosen/chosen-file dir)]
      (spit f (str (slurp f) "not an id\n"))
      (let [{:keys [status err]} (build-doc! root dir "reformed:dangling")]
        (is (= 1 status) err)
        (is (some #(= % ".haselnuss/chosen holds \"not an id\", which is not a canonical book ID; it is left out")
                  (warning-lines err))
            err)
        (is (= ["reformed:catkins" "reformed:dangling"] (chosen-of dir))))))
  (testing "review: a partial site missing its reader app and catalog index gets them back"
    (let [[root dir] (partial-site ["reformed:catkins"])]
      (.delete (io/file dir "app.js"))
      (.delete (io/file dir "catalog/index.json"))
      (let [{:keys [status err]} (build-doc! root dir "reformed:dangling")]
        (is (= 1 status) err)
        (is (not (str/includes? err "error:")) err)
        (is (exists? dir "app.js"))
        (is (contains? (:ids (catalog-ids dir)) "reformed:dangling"))))))

;; --- AC #8: --all-books ---------------------------------------------------------------

(deftest all-books-test
  (testing "AC #8: --all-books without --build-site is a usage error"
    (doseq [args [["--all-books" "doc.hdoc"] ["--build-doc" "a:b" "--site" "s" "--all-books"] ["--all-books=x" "--build-site" "s"]]]
      (let [{:keys [status err]} (run!* (temp-dir) args)]
        (is (= 2 status) (pr-str args))
        (is (or (str/includes? err "--all-books applies only to --build-site")
                (str/includes? err "option --all-books takes no value"))
            err))))
  (testing "AC #8: on a partial site it builds every book and removes .haselnuss/chosen"
    (let [[root dir] (partial-site chosen-four)
          r (build-site! root dir "--all-books")
          clean (io/file (temp-dir) "clean")
          clean-r (build-site! root clean)]
      (is (= 1 (:status r)) (:err r))
      (is (some #(str/includes? % "--all-books built every book and removed .haselnuss/chosen") (info-lines (:err r)))
          (:err r))
      (is (not (chosen/partial-site? dir)))
      (is (= (site-tree clean) (site-tree dir)) (str (vec (tree-diff (site-tree clean) (site-tree dir)))))
      (is (= (problems (summary (:err clean-r))) (problems (summary (:err r)))))
      (is (str/starts-with? (summary (:err r)) "5 of 11 books rebuilt, 4 up to date")
          "the chosen books' pages are kept; the other books' are built")
      (testing "and it stays full: the next plain build rebuilds nothing and removes nothing"
        (let [again (build-site! root dir)]
          (is (= {} (rebuilt-ids (:err again))) (:err again))
          (is (not (re-find #"(?m)^removed " (:err again))))
          (is (= (site-tree clean) (site-tree dir)))))))
  (testing "AC #8: on a full site it changes nothing"
    (let [root (fixture-copy)
          dir (io/file (temp-dir) "site")
          _ (build-site! root dir)
          _ (build-site! root dir)
          before (mtimes dir)
          r (build-site! root dir "--all-books")]
      (is (= {} (rebuilt-ids (:err r))) (:err r))
      (is (= before (mtimes dir)))
      (is (empty? (info-lines (:err r)))))))

;; --- AC #4: the record of an unchanged partial site ---------------------------------

(deftest unchanged-partial-site-test
  (with-redefs [digest/racy-window-ms 0]
    (let [[root dir] (partial-site chosen-four)
          hit? (fn [& args]
                 (let [hit (atom false)
                       examine unchanged/examine]
                   (with-redefs [unchanged/examine (fn [& xs] (let [r (apply examine xs)] (when (:hit r) (reset! hit true)) r))]
                     (let [r (apply build-site! root dir args)]
                       (assoc r :hit @hit)))))
          settled (loop [i 0] (let [r (hit?)] (if (or (:hit r) (= i 3)) r (recur (inc i)))))]
      (testing "AC #4: a no-change run on a partial site answers from the record"
        (is (:hit settled) (:err settled))
        (is (= "0 of 4 books rebuilt, 4 up to date; 1 warning in 1 book" (summary (:err settled)))))
      (testing "a chosen list changed by hand is not taken for unchanged"
        (let [f (chosen/chosen-file dir)
              saved (slurp f)]
          (spit f (str saved "reformed:catkins\n"))
          (let [r (hit?)]
            (is (not (:hit r)))
            (is (contains? (pages dir) "reformed:catkins") (:err r))
            (same-as-clean-partial? root dir r "a chosen list edited"))))
      (testing "--all-books is never answered from a partial site's record"
        (let [_ (loop [i 0] (let [r (hit?)] (when-not (or (:hit r) (= i 3)) (recur (inc i)))))
              r (hit? "--all-books")]
          (is (not (:hit r)))
          (is (not (chosen/partial-site? dir))))))))
