(ns haselnuss.site-incremental-test
  "An incremental `--build-site` (TASK-272; doc-12, \"What gets rebuilt\"):
  a second run rebuilds only the books whose page could have changed, and
  the site it leaves is byte for byte the one a clean build into another
  folder writes -- the anti-goal is an incremental build that differs. The
  central test drives a seeded sequence of edits over a copy of the site
  fixture set (`test/fixtures/site/`) and compares after every step.

  Every file the tests write gets its modification time from a fake clock
  in the past (`touch!`), one second further each time: every edit then
  changes its file's stamp, and no stamp is racy, so the builds take the
  path a real collection takes (stat, no read) rather than reading every
  file because it was just modified."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.cli :as cli]
            [haselnuss.library.digest :as digest]
            [haselnuss.library.graph :as graph]
            [haselnuss.library.graph.scan :as scan]
            [haselnuss.library.state :as state]
            [haselnuss.sandbox :as sandbox]
            [haselnuss.site :as site]
            [haselnuss.site.incremental :as incremental]
            [haselnuss.site.unchanged :as unchanged]
            [haselnuss.test-sandbox :as test-sandbox]
            [haselnuss.version :as version])
  (:import (java.io File StringWriter)
           (java.nio.file CopyOption Files LinkOption)
           (java.nio.file.attribute FileAttribute FileTime)
           (java.util Random)))

;; --- helpers -----------------------------------------------------------------------

(def ^:private ^File fixture-dir (io/file "test/fixtures/site"))

(defn- temp-dir ^File []
  (.toFile (Files/createTempDirectory "haselnuss-site-incremental" (make-array FileAttribute 0))))

(def ^:private clock
  "The fake modification clock (see the namespace docstring), a day ago."
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

(defn- exists? [^File root rel] (.isFile (io/file root rel)))

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

(defn- earlier-lines
  "The lines of `err` marked as printed from an earlier build (TASK-308)."
  [err]
  (filterv #(str/ends-with? % site/earlier-note) (str/split-lines err)))

(defn- without-earlier
  "`err` without the lines marked as from an earlier build."
  [err]
  (apply str (map #(str % "\n") (remove #(str/ends-with? % site/earlier-note) (str/split-lines err)))))

(defn- build!
  "Build the libraries of `root`'s `libraries.yaml` into the site `dir`:
  `{:status :err}`. `:hook` is the build's `::site-hook`; `:args` more
  command-line arguments, `:opts` more (or other) injected options."
  [^File root dir & {:keys [threads hook full? args opts]}]
  (let [err (StringWriter.)
        status (binding [*err* err *out* (StringWriter.)]
                 (cli/main* (cond-> (into ["--libraries" (str (io/file root "libraries.yaml")) "--build-site" (str dir)]
                                          args)
                              full? (conj "--full"))
                            (merge (cond-> {:library-env {} :library-home "/nonexistent/haselnuss-test-home"
                                            :haselnuss.cli/check-base-dir (.getCanonicalPath root)
                                            :haselnuss.cli/site-threads (or threads 2)}
                                     hook (assoc :haselnuss.cli/site-hook hook))
                                   opts)))]
    {:status status :err (str err)}))

(defn- sha [^bytes bs]
  (apply str (map #(format "%02x" %) (.digest (java.security.MessageDigest/getInstance "SHA-256") bs))))

(defn- site-tree
  "`{relative-path digest}` of the site `dir` below it, folders as `:dir`,
  `.haselnuss/` left out."
  [^File dir]
  (into (sorted-map)
        (for [^File f (file-seq dir)
              :let [rel (rel-path dir f)]
              :when (and (not= "" rel) (not (str/starts-with? rel ".haselnuss")))]
          [rel (if (.isDirectory f) :dir (sha (Files/readAllBytes (.toPath f))))])))

(defn- mtimes
  "`{relative-path modification-time}` of the files of the site `dir`."
  [^File dir]
  (into (sorted-map)
        (for [^File f (file-seq dir)
              :let [rel (rel-path dir f)]
              :when (and (.isFile f) (not (str/starts-with? rel ".haselnuss")))]
          [rel (Files/getLastModifiedTime (.toPath f) (make-array LinkOption 0))])))

(defn- tree-diff
  "The paths where the trees `a` and `b` differ, for a failure message."
  [a b]
  (sort (for [k (set/union (set (keys a)) (set (keys b)))
              :when (not= (get a k) (get b k))]
          k)))

(defn- summary [err] (last (str/split-lines err)))

(defn- summary-counts
  "`[rebuilt total up-to-date]` of a summary line."
  [line]
  (mapv parse-long (rest (re-find #"^(\d+) of (\d+) books? rebuilt, (\d+) up to date" line))))

(defn- problems
  "The part of a summary line after `;`: what it counts as outstanding."
  [line]
  (second (str/split line #"; " 2)))

(defn- clean-build
  "A clean build of `root` into a new folder: `[tree result]`."
  [^File root]
  (let [dir (io/file (temp-dir) "site")
        result (build! root dir)]
    [(site-tree dir) result]))

(defn- same-as-clean?
  "Assert that the site `dir`, just built from `root` with `result`, is
  byte for byte a clean build's, and that its status and the problems its
  summary counts are the clean build's."
  [^File root ^File dir result what]
  (let [[clean clean-result] (clean-build root)
        tree (site-tree dir)]
    (is (= clean tree) (str what ": differs from a clean build at " (vec (tree-diff clean tree))
                            "\n" (:err result)))
    (is (= (:status clean-result) (:status result)) what)
    (is (= (problems (summary (:err clean-result))) (problems (summary (:err result))))
        (str what "\nclean: " (:err clean-result) "\nincremental: " (:err result)))
    (is (= (second (summary-counts (summary (:err clean-result))))
           (second (summary-counts (summary (:err result)))))
        (str what ": the same book count"))))

(defn- rebuilt-ids
  "The ids of the `rebuilt` lines of a build's stderr, as `{id reason}`."
  [err]
  (into {} (for [[_ id reason] (re-seq #"(?m)^rebuilt  (\S+)\s+\((.*)\)$" err)] [id reason])))

;; --- AC #1: nothing changed, nothing rebuilt -------------------------------------

(deftest no-change-rebuilds-nothing-test
  (let [root (fixture-copy)
        dir (io/file (temp-dir) "site")
        first-run (build! root dir)
        before (mtimes dir)
        second-run (build! root dir)
        generation (state/saved-generation dir)
        third-run (build! root dir)]
    (is (= 1 (:status first-run)) (:err first-run))
    (testing "a second run rebuilds no book, keeps the nine pages and still counts every warning"
      (is (= 1 (:status second-run)) (:err second-run))
      (is (= (str "skipped  reformed:notas  (2 books share this id)\n"
                  "0 of 11 books rebuilt, 9 up to date, 2 not built; 2 warnings in 3 books\n")
             (without-earlier (:err second-run))))
      (is (= 2 (count (earlier-lines (:err second-run)))) "each warning it counts is printed, as from earlier")
      (is (= (:err second-run) (:err third-run)) "and so does a third"))
    (testing "and writes no file of the site: every modification time is as it was"
      (is (= before (mtimes dir))))
    (testing "a run that changed nothing the state holds does not save it again"
      (is (some? generation))
      (is (= generation (state/saved-generation dir)))
      (is (not (.exists (io/file dir ".haselnuss/pending"))) "and leaves no pending list"))
    (testing "the site is a clean build's"
      (same-as-clean? root dir third-run "no change"))))

(deftest missing-files-are-made-again-test
  (let [root (fixture-copy)
        dir (io/file (temp-dir) "site")
        _ (build! root dir)]
    (.delete (io/file dir "books/reformed/dangling/index.html"))
    (.delete (io/file dir "books/reformed/catkins/catkins.png"))
    (.delete (io/file dir "books/bible/blivre/romanos/previews.json"))
    (.delete (io/file dir "books/bible/blivre/joao/backlinks.json"))
    (let [{:keys [err] :as result} (build! root dir)]
      (is (= {"reformed:dangling" "its page was missing" "reformed:catkins" "its page was missing"}
             (rebuilt-ids err))
          "a book whose page or copied image is gone is rebuilt; a missing data file is only written again")
      (same-as-clean? root dir result "delete files of the site"))))

;; --- AC #2: a seeded sequence of edits, compared with clean builds ---------------

(def ^:private seed
  "The seed of the edit sequence: the task number."
  272)

(defn- pick [^Random rnd xs]
  (let [v (vec xs)]
    (when (seq v) (nth v (.nextInt rnd (count v))))))

(defn- books-of
  "The `.hdoc` files below `root`'s folder `lib`, relative to `root`, sorted."
  [^File root lib]
  (sort (for [^File f (file-seq (io/file root lib))
              :when (and (.isFile f) (str/ends-with? (.getName f) ".hdoc"))]
          (rel-path root f))))

(defn- bible [root] (json/read-str (read-file root "bible/blivre/blivre.json")))

(defn- bible! [root data]
  (write-file! root "bible/blivre/blivre.json" (json/write-str data :escape-unicode false)))

(def ^:private link-targets
  "What an added book links to: Bible passages (locators, so TASK-269's
  anchors), whole books, labels of other books, a dangling book that an
  edit may add (`tobias`), and the added books themselves."
  ["bible:romanos#14:8" "bible:joao#10:27-30" "bible:genesis#3:15" "bible:genesis" "bible:mateus#10:29-30"
   "reformed:catecismo-heidelberg#sec:q2" "reformed:estudos/queda#sec:queda" "reformed:dangling"
   "bible:tobias#1:1" "reformed:catkins#fig:catkins" "reformed:extra-1#sec:x" "reformed:extra-2"])

(def ^:private quote-targets
  ["bible:mateus#9:12" "reformed:catecismo-heidelberg#sec:q1" "bible:tobias#1:1" "reformed:extra-1#sec:x"
   "reformed:extra-3#sec:x"])

(def ^:private edits
  "The edit kinds, each `(fn [rnd root n] description-or-nil)`: nil when it
  does not apply to the libraries as they are now."
  {:edit-body
   (fn [rnd root n]
     (when-let [rel (pick rnd (books-of root "reformed"))]
       (write-file! root rel (str (read-file root rel) "\nAn added paragraph, edit " n ".\n"))
       (str "edit the body of " rel)))

   :edit-title
   (fn [rnd root n]
     (when-let [rel (pick rnd (books-of root "reformed"))]
       (write-file! root rel (str/replace-first (read-file root rel) #"(?m)^title: (.*)$"
                                                (str "title: $1 (" n ")")))
       (str "retitle " rel)))

   :edit-quoted-passage
   (fn [_ root n]
     (when-let [rel (first (filter #(str/includes? (read-file root %) "{#sec:q1}") (books-of root "reformed")))]
       (write-file! root rel (str/replace-first (read-file root rel) "{#sec:q1}\n\n" (str "{#sec:q1}\n\nNota " n ". ")))
       (str "edit the quoted passage sec:q1 of " rel)))

   :add-book
   (fn [^Random rnd root n]
     (let [slug (str "extra-" (inc (.nextInt rnd 4)))
           rel (str "reformed/" (pick rnd ["" "extras/"]) slug ".hdoc")]
       (when-not (some #(str/ends-with? % (str "/" slug ".hdoc")) (books-of root "reformed"))
         (write-file! root rel (str "---\ntitle: Extra " n "\n---\n\n# Extra {#sec:x}\n\n"
                                    "See @{" (pick rnd link-targets) "} and @{" (pick rnd link-targets) "}.\n\n"
                                    "!quote " (pick rnd quote-targets) "\n"))
         (str "add " rel))))

   :delete-book
   (fn [rnd root _]
     (when-let [rel (pick rnd (books-of root "reformed"))]
       (.delete (io/file root rel))
       (str "delete " rel)))

   :move-book
   (fn [rnd root _]
     (when-let [rel (pick rnd (remove #(str/ends-with? % "/book.hdoc") (books-of root "reformed")))]
       (let [file-name (.getName (io/file rel))
             target (if (= "reformed" (.getParent (io/file rel)))
                      (str "reformed/" (pick rnd ["moved" "estudos"]) "/" file-name)
                      (str "reformed/" file-name))]
         (when-not (.exists (io/file root target))
           (io/make-parents (io/file root target))
           (Files/move (.toPath (io/file root rel)) (.toPath (io/file root target)) (make-array CopyOption 0))
           (str "move " rel " to " target)))))

   :restore-book
   (fn [rnd root _]
     (when-let [rel (pick rnd (remove #(or (exists? root %)
                                           ;; a moved copy of it is still there
                                           (some (fn [b] (= (.getName (io/file b)) (.getName (io/file %))))
                                                 (remove (fn [b] (str/ends-with? b "/book.hdoc"))
                                                         (books-of root "reformed"))))
                                      (books-of fixture-dir "reformed")))]
       (write-file! root rel (slurp (io/file fixture-dir rel)))
       (str "restore " rel)))

   :edit-verse
   (fn [^Random rnd root n]
     (let [data (bible root)
           i (.nextInt rnd (count data))
           j (.nextInt rnd (count (get-in data [i "verses"])))]
       (bible! root (update-in data [i "verses" j 2] str " (" n ")"))
       (str "edit a verse of " (get-in data [i "slug"]))))

   :rename-bible-book
   (fn [^Random rnd root n]
     (let [data (bible root)
           i (.nextInt rnd (count data))]
       (bible! root (update-in data [i "title"] str " " n))
       (str "retitle the Bible book " (get-in data [i "slug"]))))

   :toggle-tobias
   (fn [_ root n]
     (let [data (bible root)]
       (if (some #(= "tobias" (get % "slug")) data)
         (do (bible! root (vec (remove #(= "tobias" (get % "slug")) data))) "remove Tobias from blivre.json")
         (do (bible! root (conj (vec data) {"slug" "tobias" "title" "Tobias"
                                            "verses" [[1 1 (str "Tobias, versículo " n ".")]]}))
             "add Tobias to blivre.json, which reformed:dangling names"))))

   :edit-shelf
   (fn [_ root _]
     (let [text (read-file root "bible/blivre/shelf.clj")
           plain "(str title \" \" chapter)"
           long-form "(str title \", capítulo \" chapter)"]
       (write-file! root "bible/blivre/shelf.clj"
                    (if (str/includes? text plain)
                      (str/replace text plain long-form)
                      (str/replace text long-form plain)))
       "toggle the chapter headings of shelf.clj"))

   :edit-image
   (fn [rnd root _]
     (when (exists? root "reformed/catkins/catkins.png")
       (let [bs (byte-array 64)]
         (.nextBytes ^Random rnd bs)
         (write-file! root "reformed/catkins/catkins.png" bs)
         "edit the image reformed/catkins/catkins.png")))

   ;; `estudos.hdoc` shows `queda/fig.png`, whose copy would land in the
   ;; page folder of `estudos/queda`: present or not, that book decides
   ;; whether the image is copied
   :toggle-nested
   (fn [_ root n]
     (if-not (exists? root "reformed/estudos.hdoc")
       (do (write-file! root "reformed/queda/fig.png" (.getBytes (str "fig " n) "UTF-8"))
           (write-file! root "reformed/estudos.hdoc"
                        "---\ntitle: Estudos\n---\n\n# Estudos {#sec:e}\n\n![A figure](queda/fig.png)\n")
           "add reformed/estudos.hdoc, showing an image below reformed:estudos/queda's page folder")
       (if (exists? root "reformed/estudos/queda.hdoc")
         (do (.delete (io/file root "reformed/estudos/queda.hdoc")) "delete reformed/estudos/queda.hdoc")
         (do (write-file! root "reformed/estudos/queda.hdoc" (slurp (io/file fixture-dir "reformed/estudos/queda.hdoc")))
             "restore reformed/estudos/queda.hdoc"))))

   :toggle-duplicate
   (fn [_ root _]
     (if (exists? root "reformed/notas.hdoc")
       (do (.delete (io/file root "reformed/notas.hdoc")) "delete notas.hdoc: notas/book.hdoc is no longer a duplicate")
       (do (write-file! root "reformed/notas.hdoc" (slurp (io/file fixture-dir "reformed/notas.hdoc")))
           "restore notas.hdoc")))

   ;; an image the page refuses (no such file) that later appears, and
   ;; goes again (TASK-272 review)
   :toggle-refused-image
   (fn [_ root n]
     (cond
       (not (exists? root "reformed/foto.hdoc"))
       (do (write-file! root "reformed/foto.hdoc" "---\ntitle: Foto\n---\n\n![F](foto/p.png)\n")
           "add reformed/foto.hdoc, showing foto/p.png, which is not there")
       (exists? root "reformed/foto/p.png")
       (do (.delete (io/file root "reformed/foto/p.png")) "delete reformed/foto/p.png, which foto.hdoc shows")
       :else
       (do (write-file! root "reformed/foto/p.png" (.getBytes (str "picture " n) "UTF-8"))
           "create reformed/foto/p.png, which foto.hdoc shows")))

   ;; an !include through a symbolic link to a folder, pointed elsewhere
   ;; (TASK-272 review)
   :retarget-include
   (fn [_ root n]
     (let [link (.toPath (io/file root "reformed/inc/parts"))]
       (if-not (Files/isSymbolicLink link)
         (do (write-file! root "reformed/inc-a/a.hdoc" (str "Part A, " n ".\n"))
             (write-file! root "reformed/inc-b/a.hdoc" (str "Part B, " n ".\n"))
             (write-file! root "reformed/inc/book.hdoc" "---\ntitle: Inc\n---\n\n!include parts/a.hdoc\n")
             (Files/createSymbolicLink link (.toPath (io/file "../inc-a")) (make-array FileAttribute 0))
             "add reformed/inc/book.hdoc, including parts/a.hdoc through parts -> inc-a")
         (let [target (if (= "../inc-a" (str (Files/readSymbolicLink link))) "../inc-b" "../inc-a")]
           (Files/delete link)
           (Files/createSymbolicLink link (.toPath (io/file target)) (make-array FileAttribute 0))
           (str "point reformed/inc/parts at " target)))))

   :no-change
   (fn [_ _ _] "change nothing")})

(deftest ^:slow edit-sequence-matches-clean-builds-test
  (let [rnd (Random. seed)
        root (fixture-copy)
        dir (io/file (temp-dir) "site")
        ;; every kind three times, in a seeded order
        kinds (let [l (java.util.ArrayList. ^java.util.Collection (vec (mapcat (fn [_] (sort (keys edits)))
                                                                               (range 3))))]
                (java.util.Collections/shuffle l rnd)
                (vec l))
        stats (atom {:applied {} :kept 0 :rebuilt 0 :partial-steps 0 :reasons {}})]
    (same-as-clean? root dir (build! root dir) "the first build")
    (doseq [[n kind] (map-indexed vector kinds)]
      (when-let [what ((get edits kind) rnd root n)]
        (let [result (build! root dir)
              [rebuilt _ kept] (summary-counts (summary (:err result)))]
          (swap! stats #(-> %
                            (update-in [:applied kind] (fnil inc 0))
                            (update :kept + kept)
                            (update :rebuilt + rebuilt)
                            (update :reasons (partial merge-with +) (frequencies (vals (rebuilt-ids (:err result)))))
                            (cond-> (pos? kept) (update :partial-steps inc))))
          (same-as-clean? root dir result (str "step " n ": " what)))))
    (testing (str "not vacuous: every edit kind ran, most steps kept pages rather than rebuilding all,"
                  " and books were rebuilt for what other books did")
      (is (= (set (keys edits)) (set (keys (:applied @stats)))) (pr-str (:applied @stats)))
      (is (< 30 (:partial-steps @stats)) (pr-str @stats))
      (is (< (* 3 (:rebuilt @stats)) (:kept @stats)) (pr-str @stats))
      (doseq [reason ["source changed" "new book" "a book it refers to changed" "an image it shows changed"
                      "a book was added or removed inside its folder" "not built before"]]
        (is (pos? (get-in @stats [:reasons reason] 0)) (str reason " " (pr-str (:reasons @stats))))))))

;; --- review round: refused images, folder links, the site root --------------------

(deftest refused-image-becomes-valid-test
  (testing "an image the page refused because the file was missing: created, the page is rebuilt"
    (let [root (fixture-copy)
          dir (io/file (temp-dir) "site")
          _ (write-file! root "reformed/foto.hdoc" "---\ntitle: Foto\n---\n\n![F](foto/p.png)\n")
          first-run (build! root dir)
          _ (write-file! root "reformed/foto/p.png" (.getBytes "a picture" "UTF-8"))
          {:keys [err] :as result} (build! root dir)]
      (is (str/includes? (:err first-run) "[site-image]") (:err first-run))
      (is (= {"reformed:foto" "an image it shows changed"} (rebuilt-ids err)) err)
      (is (= "a picture" (slurp (io/file dir "books/reformed/foto/foto/p.png"))))
      (same-as-clean? root dir result "create a missing image")
      (testing "and deleted again, rebuilt again"
        (.delete (io/file root "reformed/foto/p.png"))
        (let [{:keys [err] :as result} (build! root dir)]
          (is (= {"reformed:foto" "an image it shows changed"} (rebuilt-ids err)) err)
          (same-as-clean? root dir result "delete the image again")))))
  (testing "an image refused as a link out of the book's folder, replaced by a regular file"
    (let [root (fixture-copy)
          dir (io/file (temp-dir) "site")
          outside (write-file! (temp-dir) "l.png" "outside")
          link (.toPath (io/file root "reformed/catkins/l.png"))
          book (str (read-file root "reformed/catkins/book.hdoc") "\n![L](l.png)\n")
          _ (write-file! root "reformed/catkins/book.hdoc" book)
          _ (Files/createSymbolicLink link (.toPath outside) (make-array FileAttribute 0))
          _ (build! root dir)
          _ (Files/delete link)
          _ (write-file! root "reformed/catkins/l.png" "inside")
          {:keys [err] :as result} (build! root dir)]
      (is (= {"reformed:catkins" "an image it shows changed"} (rebuilt-ids err)) err)
      (same-as-clean? root dir result "replace a link out of the folder by a file"))))

(deftest retargeted-folder-link-test
  (let [root (fixture-copy)
        dir (io/file (temp-dir) "site")
        link (.toPath (io/file root "reformed/inc/parts"))
        _ (write-file! root "reformed/partes-a/a.txt" "Parte A.\n")
        _ (write-file! root "reformed/partes-b/a.txt" "Parte B.\n")
        _ (write-file! root "reformed/inc/book.hdoc" "---\ntitle: Inc\n---\n\n!include parts/a.txt\n")
        _ (Files/createSymbolicLink link (.toPath (io/file "../partes-a")) (make-array FileAttribute 0))
        _ (build! root dir)
        _ (is (str/includes? (slurp (io/file dir "books/reformed/inc/index.html")) "Parte A."))
        _ (Files/delete link)
        _ (Files/createSymbolicLink link (.toPath (io/file "../partes-b")) (make-array FileAttribute 0))
        {:keys [err] :as result} (build! root dir)]
    (testing "a symbolic link to a folder on an !include path, pointed elsewhere, rebuilds the book"
      (is (= {"reformed:inc" "source changed"} (rebuilt-ids err)) err)
      (is (str/includes? (slurp (io/file dir "books/reformed/inc/index.html")) "Parte B.")))
    (same-as-clean? root dir result "retarget a folder link on an include path")
    (testing "and its stamp records the link's target"
      (is (some #(= "../partes-b" (:link %)) (vals (:files (state/load-state dir))))))))

(deftest site-root-temp-files-test
  (let [root (fixture-copy)
        dir (io/file (temp-dir) "site")
        _ (build! root dir)
        own (write-file! dir ".app.js.123456.tmp" "half an app")
        own-too (write-file! dir ".manifest.webmanifest.9.tmp" "half")
        other (write-file! dir ".app.js.tmp" "mine")
        another (write-file! dir "notes.123.tmp" "mine too")
        result (build! root dir)]
    (testing "a temporary file a killed write of an app file left at the top is removed; others stay"
      (is (not (.exists own)))
      (is (not (.exists own-too)))
      (is (= "mine" (slurp other)))
      (is (= "mine too" (slurp another))))
    (.delete other)
    (.delete another)
    (same-as-clean? root dir result "a leftover app temp file")))

;; --- AC #3: a deleted book --------------------------------------------------------

(deftest deleted-book-test
  (let [root (fixture-copy)
        dir (io/file (temp-dir) "site")
        _ (build! root dir)
        _ (.delete (io/file root "reformed/catecismo-heidelberg.hdoc"))
        {:keys [err] :as result} (build! root dir)
        queda (slurp (io/file dir "books/reformed/estudos/queda/index.html"))]
    (testing "its folder is removed, and the book quoting it is rebuilt with a ?? where the quote was"
      (is (not (.exists (io/file dir "books/reformed/catecismo-heidelberg"))))
      (is (str/includes? err "removed  reformed:catecismo-heidelberg  (no longer in the libraries)\n") err)
      (is (= "a book it refers to changed" (get (rebuilt-ids err) "reformed:estudos/queda")) err)
      (is (str/includes? queda "??"))
      (is (not (str/includes? queda "catecismo-heidelberg/index.html"))))
    (testing "the backlinks of the books it cited no longer list it, the catalog no longer has it"
      (is (not (str/includes? (slurp (io/file dir "books/bible/blivre/romanos/backlinks.json")) "catecismo")))
      (is (not (str/includes? (slurp (io/file dir "catalog/reformed/1.json")) "catecismo"))))
    (testing "exactly as a clean build"
      (same-as-clean? root dir result "delete the catechism"))))

(deftest moved-book-test
  (let [root (fixture-copy)
        dir (io/file (temp-dir) "site")
        _ (build! root dir)
        _ (io/make-parents (io/file root "reformed/confissoes/x"))
        _ (Files/move (.toPath (io/file root "reformed/catecismo-heidelberg.hdoc"))
                      (.toPath (io/file root "reformed/confissoes/catecismo-heidelberg.hdoc"))
                      (make-array CopyOption 0))
        {:keys [err] :as result} (build! root dir)]
    (testing (str "a book moved to another shelf has a new page; the book quoting it by its short id is"
                  " rebuilt, its quote source now pointing at the new page; the old folder goes")
      (is (= {"reformed:confissoes/catecismo-heidelberg" "new book"
              "reformed:estudos/queda" "a book it refers to changed"}
             (rebuilt-ids err))
          err)
      (is (str/includes? err "removed  reformed:catecismo-heidelberg") err)
      (is (str/includes? (slurp (io/file dir "books/reformed/estudos/queda/index.html"))
                         "../../confissoes/catecismo-heidelberg/index.html")))
    (same-as-clean? root dir result "move the catechism to another shelf")))

;; --- AC #4: warnings in books not rebuilt still count -----------------------------

(deftest warnings-of-kept-books-count-test
  (testing "a warning only emitting a page finds is counted while the page is kept, and printed again as from an earlier build"
    (let [root (temp-dir)
          _ (write-file! root "lib/a.hdoc" "---\ntitle: A\n---\n\nFine.\n")
          _ (write-file! root "lib/b.hdoc" "---\ntitle: B\n---\n\nSee [x](javascript:alert(1)).\n")
          _ (write-file! root "libraries.yaml" "- name: lib\n  type: filesystem\n  path: lib\n")
          dir (io/file root "site")
          first-run (build! root dir)
          _ (write-file! root "lib/a.hdoc" "---\ntitle: A\n---\n\nFine, and edited.\n")
          {:keys [status err]} (build! root dir)]
      (is (str/includes? (:err first-run) "[unsafe-url]") (:err first-run))
      (is (= 1 status) err)
      (is (= (str "rebuilt  lib:a  (source changed)\n"
                  "1 of 2 books rebuilt, 1 up to date; 1 warning in 1 book\n")
             (without-earlier err)))
      (is (= [(str "lib/b.hdoc: warning: [unsafe-url] Dropped the link to \"javascript:alert(1)\": "
                   "only http, https, mailto and relative URLs are kept." site/earlier-note)]
             (earlier-lines err))
          "printed again, marked as from an earlier build")))
  (testing "a repeat build of an unchanged library prints the warning its summary counts (TASK-308)"
    (let [root (temp-dir)
          _ (write-file! root "lib/b.hdoc" "---\ntitle: B\n---\n\nSee [x](javascript:alert(1)).\n")
          _ (write-file! root "libraries.yaml" "- name: lib\n  type: filesystem\n  path: lib\n")
          dir (io/file root "site")
          first-run (build! root dir)
          repeat-run (build! root dir)
          third-run (build! root dir)]
      (is (not (str/includes? (:err first-run) site/earlier-note)) "a full build says nothing of earlier")
      (is (= 1 (count (re-seq #"\[unsafe-url\]" (:err first-run)))))
      (is (= (str "lib/b.hdoc: warning: [unsafe-url] Dropped the link to \"javascript:alert(1)\": "
                  "only http, https, mailto and relative URLs are kept." site/earlier-note "\n"
                  "0 of 1 book rebuilt, 1 up to date; 1 warning in 1 book\n")
             (:err repeat-run)))
      (is (= (:err repeat-run) (:err third-run)))
      (is (= 1 (:status repeat-run)))))
  (testing "a preview that could not be built is counted while its previews.json is kept"
    (let [root (fixture-copy)
          dir (io/file (temp-dir) "site")
          romanos {:library "bible" :shelf ["blivre"] :slug "romanos"}
          original @#'cli/site-previewer]
      (with-redefs [cli/site-previewer (fn [opts d]
                                         (let [f (original opts d)]
                                           (fn [id passages copied]
                                             (if (= romanos id)
                                               (mapv (fn [p] {:key (:key p) :failed "boom"}) passages)
                                               (f id passages copied)))))]
        (let [{:keys [err]} (build! root dir)]
          ;; romanos#14:8 and romanos#8:28 are linked to: two more warnings
          (is (str/ends-with? err "; 4 warnings in 4 books\n") err)
          (is (= 2 (count (re-seq #"\[preview-failed\]" err))) err)))
      (write-file! root "reformed/dangling.hdoc" (str (read-file root "reformed/dangling.hdoc") "\nMore.\n"))
      (let [{:keys [err]} (build! root dir)]
        (is (= {"reformed:dangling" "source changed"} (rebuilt-ids err)) err)
        (is (= 2 (count (filter #(str/includes? % "[preview-failed]") (earlier-lines err))))
            "printed again, as from earlier: romanos was not rebuilt")
        (is (str/ends-with? err "1 of 11 books rebuilt, 8 up to date, 2 not built; 4 warnings in 4 books\n")
            err))
      (testing "and while only its backlinks.json is written again (a new quote of it needs no preview)"
        (write-file! root "reformed/cita.hdoc" "---\ntitle: Cita\n---\n\n!quote bible:romanos#14:8\n")
        (let [{:keys [err]} (build! root dir)]
          (is (= {"reformed:cita" "new book"} (rebuilt-ids err)) err)
          (is (str/includes? (slurp (io/file dir "books/bible/blivre/romanos/backlinks.json")) "reformed:cita"))
          (is (str/ends-with? err "1 of 12 books rebuilt, 9 up to date, 2 not built; 4 warnings in 4 books\n")
              err))))))

;; --- AC #5: an interrupted build -----------------------------------------------------

(defn- edit-for-interruption!
  "A few edits of every kind an interrupted build has to redo: a verse, a
  Bible title, the quoted passage, a deleted book and an added one."
  [^File root]
  (let [data (bible root)]
    (bible! root (-> data
                     (update-in [3 "verses" 0 2] str " (editado)")
                     (update-in [2 "title"] str " (revisto)"))))
  (write-file! root "reformed/catecismo-heidelberg.hdoc"
               (str/replace-first (read-file root "reformed/catecismo-heidelberg.hdoc")
                                  "{#sec:q1}\n\n" "{#sec:q1}\n\nNota. "))
  (.delete (io/file root "reformed/dangling.hdoc"))
  (write-file! root "reformed/novo.hdoc" "---\ntitle: Novo\n---\n\nSee @{bible:romanos#14:8}.\n"))

(defn- stop-at
  "A `::site-hook` throwing at the `n`th (from 0) call of `stage`."
  [stage n]
  (let [calls (atom -1)]
    (fn [s _]
      (when (and (= s stage) (= n (swap! calls inc)))
        (throw (ex-info "killed" {:stage s}))))))

(deftest interrupted-build-test
  (doseq [[stage n] [[:page 0] [:page 2] [:page 4] [:data 0] [:data 3] [:catalog 0] [:sweep 0] [:save 0]]]
    (testing (str "stopped at " stage " " n)
      (let [root (fixture-copy)
            dir (io/file (temp-dir) "site")
            _ (build! root dir)
            _ (edit-for-interruption! root)
            stopped (build! root dir :threads 1 :hook (stop-at stage n))]
        (is (= 2 (:status stopped)) (:err stopped))
        (is (str/includes? (:err stopped) "killed") (:err stopped))
        ;; a killed build can also leave a temporary file behind
        (write-file! dir "books/reformed/catkins/.index.html.4242.tmp" "half a page")
        (write-file! dir ".app.js.4242.tmp" "half an app")
        (let [rerun (build! root dir)]
          (is (= 1 (:status rerun)) (:err rerun))
          (same-as-clean? root dir rerun (str "rerun after a stop at " stage " " n))))))
  (testing "an edit undone after the stop: the pending list still rebuilds what the stopped build touched"
    (let [root (fixture-copy)
          dir (io/file (temp-dir) "site")
          _ (build! root dir)
          original (read-file root "reformed/catecismo-heidelberg.hdoc")
          _ (write-file! root "reformed/catecismo-heidelberg.hdoc"
                         (str/replace original "Domingo 1\n" "Domingo Um\n"))
          stopped (build! root dir :threads 1 :hook (stop-at :data 0))
          _ (is (= 2 (:status stopped)))
          _ (is (str/includes? (slurp (io/file dir "books/reformed/catecismo-heidelberg/index.html")) "Domingo Um")
                "the stopped build wrote the new page")
          _ (write-file! root "reformed/catecismo-heidelberg.hdoc" original)
          rerun (build! root dir)]
      (is (= "left pending by an earlier run"
             (get (rebuilt-ids (:err rerun)) "reformed:catecismo-heidelberg"))
          (:err rerun))
      (same-as-clean? root dir rerun "undo the edit after the stop"))))

;; --- AC #6: an edited image ---------------------------------------------------------

(deftest edited-image-test
  (let [root (fixture-copy)
        dir (io/file (temp-dir) "site")
        _ (write-file! root "reformed/galeria.hdoc"
                       "---\ntitle: Galeria\n---\n\n![Catkins](catkins/catkins.png)\n")
        _ (build! root dir)
        _ (write-file! root "reformed/catkins/catkins.png" (.getBytes "not the old picture" "UTF-8"))
        {:keys [err] :as result} (build! root dir)]
    (testing "exactly the books that show the image are rebuilt"
      (is (= {"reformed:catkins" "an image it shows changed"
              "reformed:galeria" "an image it shows changed"}
             (rebuilt-ids err))
          err)
      (is (str/ends-with? err "2 of 12 books rebuilt, 8 up to date, 2 not built; 2 warnings in 3 books\n") err))
    (testing "and both show the new copy"
      (is (= "not the old picture" (slurp (io/file dir "books/reformed/catkins/catkins.png"))))
      (is (= "not the old picture" (slurp (io/file dir "books/reformed/galeria/catkins/catkins.png")))))
    (same-as-clean? root dir result "edit an image two books show")))

;; --- full builds, the reader app -----------------------------------------------------

(deftest full-build-reasons-test
  (let [root (fixture-copy)
        dir (io/file (temp-dir) "site")
        _ (build! root dir)]
    (testing "--full rebuilds every book"
      (let [{:keys [err]} (build! root dir :full? true)]
        (is (= 9 (count (rebuilt-ids err))))
        (is (every? #{"full build: --full"} (vals (rebuilt-ids err))) err)))
    (testing "so does a changed directory file"
      (write-file! root "libraries.yaml" (str (read-file root "libraries.yaml") "# edited\n"))
      (let [{:keys [err] :as result} (build! root dir)]
        (is (every? #{"full build: the directory file, --extensions or --transform changed"}
                    (vals (rebuilt-ids err)))
            err)
        (is (= 9 (count (rebuilt-ids err))))
        (same-as-clean? root dir result "edit the directory file")))
    (testing "and a state another Haselnuss version saved"
      (with-redefs [version/version (constantly "0.0.0-other")]
        (build! root dir))
      (let [{:keys [err]} (build! root dir)]
        (is (every? #{"full build: another Haselnuss version built the site"} (vals (rebuilt-ids err))) err)
        (is (= 9 (count (rebuilt-ids err))))))))

(deftest reader-app-test
  (let [root (fixture-copy)
        dir (io/file (temp-dir) "site")
        _ (build! root dir)
        version-of (fn version-of
                     ([] (version-of dir))
                     ([d] (second (re-find #"const VERSION = \"([0-9a-f]{16})\";" (slurp (io/file d "sw.js"))))))
        old-version (version-of)]
    (testing "sw.js carries the app's version, a digest of the app files, the same every build"
      (is (re-matches #"[0-9a-f]{16}" (str old-version)))
      (let [other (io/file (temp-dir) "other")]
        (build! root other)
        (is (= old-version (version-of other)))))
    (testing "an app file damaged in the site is written again; no book is rebuilt"
      (spit (io/file dir "app.css") "broken")
      (let [{:keys [err]} (build! root dir)]
        (is (= {} (rebuilt-ids err)) err)
        (is (= (String. ^bytes (get (site/app-file-bytes) "app.css") "UTF-8") (slurp (io/file dir "app.css"))))))
    (testing "a new app is copied with a new service-worker version, without rebuilding a book"
      (let [pages (into {} (filter #(str/starts-with? (key %) "books/")) (mtimes dir))
            original @#'site/resource-bytes]
        (with-redefs [site/resource-bytes (fn [file-name]
                                            (if (= "app.js" file-name)
                                              (.getBytes "// a new app\n" "UTF-8")
                                              (original file-name)))]
          (let [{:keys [err]} (build! root dir)]
            (is (= {} (rebuilt-ids err)) err)
            (is (str/starts-with? err "skipped  reformed:notas") err)))
        (is (= "// a new app\n" (slurp (io/file dir "app.js"))))
        (is (not= old-version (version-of)))
        (is (= pages (into {} (filter #(str/starts-with? (key %) "books/")) (mtimes dir)))
            "no file under books/ was written")))))

;; --- file stamps -----------------------------------------------------------------------

(deftest file-stamps-test
  (let [root (temp-dir)
        f (write-file! root "a.txt" "one")
        path (.getCanonicalPath f)
        now (System/currentTimeMillis)
        stamp (incremental/take-stamp path now)]
    (testing "a stamp: the stat, then the digest; an old modification time is trusted"
      (is (= #{:size :mtime :key :digest} (set (keys stamp))))
      (is (some? (:mtime stamp))))
    (testing "a file just modified is racy: its modification time is saved as nil"
      (spit f "two")
      (is (nil? (:mtime (incremental/take-stamp path (System/currentTimeMillis))))))
    (testing "unchanged by stat, unchanged by digest, changed"
      (touch! f)
      (let [s (incremental/take-stamp path now)]
        (is (= [false s] (incremental/check-file path s now)) "the stat matches")
        (let [[changed? s2] (incremental/check-file path (assoc s :mtime 1) now)]
          (is (false? changed?) "the stat differs, the digest is the same")
          (is (not= 1 (:mtime s2))))
        (spit f "three")
        (touch! f)
        (is (true? (first (incremental/check-file path s now))))
        (is (true? (first (incremental/check-file path nil now))) "no saved stamp")))
    (testing "a missing file is unchanged while it stays missing"
      (let [gone (str (io/file root "gone.txt"))
            s (incremental/take-stamp gone now)]
        (is (= #{:digest} (set (keys s))))
        (is (false? (first (incremental/check-file gone s now))))
        (write-file! root "gone.txt" "here")
        (is (true? (first (incremental/check-file gone s now))))))))

(deftest verified-stamps-test
  (let [root (temp-dir)
        _ (write-file! root "lib/a.hdoc" "---\ntitle: A\n---\n\nA.\n")
        _ (write-file! root "lib/b.hdoc" "---\ntitle: B\n---\n\nB.\n")
        _ (write-file! root "libraries.yaml" "- name: lib\n  type: filesystem\n  path: lib\n")
        dir (io/file root "site")
        _ (build! root dir)
        g (:graph (state/load-state dir))
        files (incremental/graph-files g)
        a (some #(when (str/ends-with? % "a.hdoc") %) files)]
    (testing "after a build, a stamp is kept for every file whose digest is the one the scan read"
      (is (= files (set (keys (incremental/verified-stamps g files)))))
      (is (= files (set (keys (:files (state/load-state dir))))) "and the build saved them"))
    (testing "a file edited after the scan read it gets none, so the next run rescans its book"
      (write-file! root "lib/a.hdoc" "---\ntitle: A\n---\n\nA, edited after the scan.\n")
      (is (= (disj files a) (set (keys (incremental/verified-stamps g files))))))
    (testing "record-hash is the record's :hash over the digests of the bytes read"
      (let [record (get (graph/indexed-books g) {:library "lib" :shelf [] :slug "b"})]
        (is (= (:hash record) (scan/record-hash (vec (:files record)) {})))))))

;; --- TASK-296: the record of an unchanged site ---------------------------------------
;;
;; A test's folders are made just now, and a folder's change time cannot be
;; set back as `touch!` sets a file's modification time: within the racy
;; window no build may save the record (haselnuss.site-unchanged-test pins
;; that). These tests close the window (`without-racy-window`), so a build
;; right after a change already saves it; every file still gets an old,
;; distinct modification time from the fake clock.

(defn- without-racy-window [f]
  (with-redefs [digest/racy-window-ms 0] (f)))

(defn- build-seen!
  "`build!` (with the same options), and `:unchanged?`: whether the run
  answered from the record of an unchanged site
  (`haselnuss.site.unchanged/examine` found nothing changed) instead of
  loading the state and building; `:delta`, the libraries it built when
  the record showed only some changed and the build stood for a whole
  one (TASK-297: `write-site!` given `:frozen`), else nil; `:changed`,
  the libraries the record showed changed when it showed only some."
  [root dir & options]
  (let [hit (atom false)
        delta (atom nil)
        changed (atom nil)
        examine unchanged/examine
        write-site! site/write-site!]
    (with-redefs [unchanged/examine (fn [& args]
                                      (let [r (apply examine args)]
                                        (when (:hit r) (reset! hit true))
                                        (when (:delta r) (reset! changed (set (:changed (:delta r)))))
                                        r))
                  site/write-site! (fn [d opts]
                                     (when (:frozen opts) (reset! delta (set (:library-names opts))))
                                     (write-site! d opts))]
      (assoc (apply build! root dir options) :unchanged? @hit :delta @delta :changed @changed))))

(defn- has-record? [dir] (.isFile (io/file dir unchanged/record-rel)))

(defn- settle!
  "Build `root` into `dir` until a run answers from the record, at most
  four runs: the site is settled and its record saved. Answers that run."
  [root dir]
  (loop [i 0]
    (let [r (build-seen! root dir)]
      (if (or (:unchanged? r) (= i 3)) r (recur (inc i))))))

(deftest unchanged-site-test
  (without-racy-window
   (fn []
     (let [root (fixture-copy)
           dir (io/file (temp-dir) "site")
           first-run (build-seen! root dir)
           fast (settle! root dir)
           before (mtimes dir)
           again (build-seen! root dir)
           _ (unchanged/clear! dir)
           slow (build-seen! root dir)
           after (build-seen! root dir)]
       (testing "once a build left the site settled, the next run answers from its record"
         (is (not (:unchanged? first-run)))
         (is (:unchanged? fast) (:err fast))
         (is (:unchanged? again) (:err again)))
       (testing "printing exactly what the long way prints when nothing changed, with its status"
         (is (not (:unchanged? slow)))
         (is (= (:err slow) (:err again)))
         (is (= (:status slow) (:status again)))
         (is (= (str "skipped  reformed:notas  (2 books share this id)\n"
                     "0 of 11 books rebuilt, 9 up to date, 2 not built; 2 warnings in 3 books\n")
                (without-earlier (:err again))))
         (is (= 2 (count (earlier-lines (:err again))))))
       (testing "and writing no file of the site"
         (is (= before (mtimes dir))))
       (testing "the long way saves the record again"
         (is (:unchanged? after) (:err after)))
       (same-as-clean? root dir again "answered from the record")))))

(deftest every-change-defeats-the-record-test
  (without-racy-window
   (fn []
     (let [rnd (Random. 296)
           root (fixture-copy)
           dir (io/file (temp-dir) "site")
           kinds (sort (keys (dissoc edits :no-change)))
           applied (atom #{})]
       (build! root dir)
       (is (:unchanged? (settle! root dir)) "settled before the first change")
       ;; every kind once, and again any that did not apply the first time
       ;; (a book to restore must have been deleted first)
       (doseq [[n kind] (map-indexed vector (concat kinds kinds))
               :when (not (contains? @applied kind))]
         (when-let [what ((get edits kind) rnd root n)]
           (swap! applied conj kind)
           (is (has-record? dir) (str what ": the record is there before the change"))
           (let [changed (build-seen! root dir)]
             (is (not (:unchanged? changed)) (str what ": the record does not answer\n" (:err changed)))
             (same-as-clean? root dir changed what))
           (let [quiet (settle! root dir)]
             (is (:unchanged? quiet) (str what ": a later run answers from the record again"))
             (unchanged/clear! dir)
             (let [slow (build-seen! root dir)]
               (is (not (:unchanged? slow)))
               (is (= (:err slow) (:err quiet)) (str what ": it prints what the long way prints"))
               (is (= (:status slow) (:status quiet)) what)))))
       (testing "not vacuous: every edit kind of the TASK-272 sequence was applied"
         (is (= (set kinds) @applied) (pr-str (sort @applied))))))))

(deftest other-changes-defeat-the-record-test
  (without-racy-window
   (fn []
     (let [root (fixture-copy)
           dir (io/file (temp-dir) "site")
           settled! (fn [what] (is (:unchanged? (settle! root dir)) (str "settled before: " what)))
           long-way (fn [what & options]
                      (let [r (apply build-seen! root dir options)]
                        (is (not (:unchanged? r)) (str what ": the record does not answer\n" (:err r)))
                        r))]
       (build! root dir)
       (testing "the inputs: the directory file, --full, another Haselnuss version"
         (settled! "the directory file")
         (write-file! root "libraries.yaml" (str (read-file root "libraries.yaml") "# edited\n"))
         (let [{:keys [err]} (long-way "edit the directory file")]
           (is (= 9 (count (rebuilt-ids err))) err))
         (settled! "--full")
         (is (= 9 (count (rebuilt-ids (:err (long-way "--full" :full? true))))))
         (settled! "another version")
         (with-redefs [version/version (constantly "0.0.0-other")]
           (is (= 9 (count (rebuilt-ids (:err (long-way "another version"))))))))
       (testing "the home folder, the working directory, --trusted-extensions"
         (settled! "the home folder")
         (long-way "another home folder" :opts {:library-home "/nonexistent/other-home"})
         (settled! "the working directory")
         (long-way "another working directory" :opts {:haselnuss.cli/check-base-dir (.getCanonicalPath (temp-dir))})
         (settled! "--trusted-extensions")
         (long-way "--trusted-extensions" :args ["--trusted-extensions"]))
       (testing "a build that stopped partway: its pending lists"
         (settled! "a pending list")
         (write-file! dir ".haselnuss/pending" "page reformed:catkins\n")
         (is (= {"reformed:catkins" "left pending by an earlier run"}
                (rebuilt-ids (:err (long-way "a pending list")))))
         (settled! "an images pending list")
         (write-file! dir ".haselnuss/images.pending" "books/reformed/catkins/catkins.png\n")
         (long-way "an images pending list"))
       (testing "a file of the site gone or changed"
         (doseq [[rel expected] [["books/reformed/dangling/index.html" {"reformed:dangling" "its page was missing"}]
                                 ["books/reformed/catkins/catkins.png" {"reformed:catkins" "its page was missing"}]
                                 ["books/bible/blivre/romanos/previews.json" {}]
                                 ["books/bible/blivre/joao/backlinks.json" {}]]]
           (settled! rel)
           (.delete (io/file dir rel))
           (is (= expected (rebuilt-ids (:err (long-way (str "delete " rel))))))
           (is (.isFile (io/file dir rel)) (str rel " is written again")))
         (settled! "the catalog")
         (spit (io/file dir "catalog/index.json") "{}")
         (long-way "a damaged catalog file")
         (is (not= "{}" (slurp (io/file dir "catalog/index.json"))))
         ;; TASK-297: a library's own catalog page
         (settled! "a library's catalog page")
         (spit (io/file dir "catalog/reformed/1.json") "{}")
         (long-way "a damaged catalog page of a library")
         (is (not= "{}" (slurp (io/file dir "catalog/reformed/1.json")))))
       (testing "a damaged record"
         (settled! "the record")
         (let [f (io/file dir unchanged/record-rel)
               text (slurp f)]
           (spit f (subs text 0 (quot (count text) 2)))
           (long-way "a cut record")))
       (testing "the record of an earlier state: a build saved the state since and left none"
         (settled! "an earlier state")
         (let [rec (.toPath (io/file dir unchanged/record-rel))
               old-record (Files/readAllBytes rec)
               book (io/file root "reformed/catkins/book.hdoc")
               old-text (slurp book)
               old-mtime (Files/getLastModifiedTime (.toPath book) (make-array LinkOption 0))]
           (spit book (str old-text "\nAn edit only that build saw.\n"))
           (touch! book)
           (build! root dir)
           ;; the edit undone byte for byte in place, with its old time, and
           ;; the old record put back: every stamp in it holds again
           (spit book old-text)
           (Files/setLastModifiedTime (.toPath book) old-mtime)
           (Files/write rec ^bytes old-record ^"[Ljava.nio.file.OpenOption;" (make-array java.nio.file.OpenOption 0))
           (is (= {"reformed:catkins" "source changed"}
                  (rebuilt-ids (:err (long-way "the record of an earlier state")))))))
       (testing "a file touched, its bytes the same: the long way, which rebuilds nothing"
         (settled! "touch")
         (touch! (io/file root "reformed/catkins/book.hdoc"))
         (is (= {} (rebuilt-ids (:err (long-way "touch"))))))
       (same-as-clean? root dir (build! root dir) "after all of them")))))

(deftest symbolic-link-in-a-shelf-test
  (without-racy-window
   (fn []
     (let [root (fixture-copy)
           dir (io/file (temp-dir) "site")
           ;; the target is outside the library: making it changes no
           ;; folder the listing looks into
           elsewhere (temp-dir)
           link (.toPath (io/file root "reformed/ghost.hdoc"))]
       (Files/createSymbolicLink link (.toPath (io/file elsewhere "ghost.hdoc")) (make-array FileAttribute 0))
       (build! root dir)
       (build! root dir)
       (testing "a link the listing passes over (it leads nowhere) leaves no record: it may come to lead to a book"
         (is (not (has-record? dir))))
       (testing "and when it does, the next run lists the book"
         (write-file! elsewhere "ghost.hdoc" "---\ntitle: Ghost\n---\n\nBoo.\n")
         (let [{:keys [err] :as result} (build-seen! root dir)]
           (is (not (:unchanged? result)))
           (is (= "new book" (get (rebuilt-ids err) "reformed:ghost")) err)
           (same-as-clean? root dir result "a link that comes to lead to a book")))))))

(defn- strays-defeat-the-record
  "Settle, add each stray to the site, and check the next run goes the
  long way, removes what the sweep removes, and leaves the site a clean
  build's; then that a run answers from the record again."
  [root dir]
  (doseq [[what add! gone] [["a page folder no book has"
                             #(write-file! dir "books/zzz/index.html" "y") "books/zzz"]
                            ["a stray page in a shelf folder"
                             #(write-file! dir "books/bible/blivre/x/index.html" "y") "books/bible/blivre/x"]
                            ["a temporary file a killed write left"
                             #(write-file! dir "books/reformed/catkins/.index.html.123.tmp" "y")
                             "books/reformed/catkins/.index.html.123.tmp"]
                            ["a stray data file"
                             #(write-file! dir "books/reformed/zz/previews.json" "{}") "books/reformed/zz"]
                            ["an empty folder" #(.mkdirs (io/file dir "books/reformed/catkins/empty"))
                             "books/reformed/catkins/empty"]
                            ;; an image put there by hand stays (no build
                            ;; copied it) -- until the manifest names it
                            ["an image put there by hand that the manifest now names"
                             #(spit (io/file dir ".haselnuss/images") "books/reformed/catkins/extra.png\n"
                                    :append true)
                             "books/reformed/catkins/extra.png"]]]
    (when (= "books/reformed/catkins/extra.png" gone)
      (write-file! dir gone "png")
      (settle! root dir)
      (is (.exists (io/file dir gone)) "a file put there by hand stays"))
    (is (:unchanged? (settle! root dir)) (str "settled before: " what))
    (add!)
    (let [r (build-seen! root dir)]
      (is (not (:unchanged? r)) (str what ": the record does not answer\n" (:err r)))
      (is (not (.exists (io/file dir gone))) (str what ": the long way removed " gone))
      (same-as-clean? root dir r what)))
  (is (:unchanged? (settle! root dir)) "and then the record answers again"))

(deftest stray-content-in-the-site-defeats-the-record-test
  (without-racy-window
   (fn []
     (let [root (fixture-copy)
           dir (io/file (temp-dir) "site")]
       (build! root dir)
       (testing "with every folder of books/ stamped"
         (strays-defeat-the-record root dir)))))
  (testing "with every folder of books/ racy when the record was saved, so listed instead"
    (without-racy-window
     (fn []
       (let [root (fixture-copy)
             dir (io/file (temp-dir) "site")
             entries unchanged/site-folder-entries
             listed (atom 0)]
         (with-redefs [unchanged/site-folder-entries
                       (fn [books-dir folders]
                         (mapv (fn [[kind p obs]]
                                 (if (and (= "S" kind) (not= "none" obs))
                                   (do (swap! listed inc) ["R" p (unchanged/listing-observation p)])
                                   [kind p obs]))
                               (entries books-dir folders)))]
           (build! root dir)
           (strays-defeat-the-record root dir))
         (is (< 10 @listed) "not vacuous: folders were listed")))))
  (testing "a site with no books/ folder yet: one made by hand defeats the record"
    (without-racy-window
     (fn []
       (let [root (temp-dir)
             _ (write-file! root "libraries.yaml" "- name: lib\n  type: filesystem\n  path: lib\n")
             _ (.mkdirs (io/file root "lib"))
             dir (io/file (temp-dir) "site")]
         (build! root dir)
         (is (:unchanged? (settle! root dir)) "settled")
         (is (not (.exists (io/file dir "books"))))
         (write-file! dir "books/x/index.html" "y")
         (is (not (:unchanged? (build-seen! root dir))))
         (is (not (.exists (io/file dir "books/x/index.html")))))))))

(deftest another-build-defeats-the-record-test
  (without-racy-window
   (fn []
     (let [root (fixture-copy)
           dir (io/file (temp-dir) "site")]
       (build! root dir)
       (testing "this JVM knows its build identity"
         (is (string? (version/build-identity))))
       (testing "a run of another build of Haselnuss goes the long way, and prints what it computes"
         (is (:unchanged? (settle! root dir)))
         (let [r (build-seen! root dir :opts {:haselnuss.cli/build-identity "another build"})]
           (is (not (:unchanged? r)))
           (is (str/ends-with? (:err r) "0 of 11 books rebuilt, 9 up to date, 2 not built; 2 warnings in 3 books\n")
               (:err r))))
       (testing "and its record does not answer this build"
         (is (not (:unchanged? (build-seen! root dir)))))
       (testing "a build that cannot tell its identity never answers from a record, and saves none"
         (is (:unchanged? (settle! root dir)))
         (let [r (build-seen! root dir :opts {:haselnuss.cli/build-identity nil})]
           (is (not (:unchanged? r)))
           (is (not (has-record? dir)))))))))

;; --- TASK-299: a timeout is not saved ---------------------------------------------

(deftest timeout-is-retried-by-the-next-build-test
  (testing "a generated book's :document and a passage's :locate that ran out of
            time in one build -- a stalled or starved process, not the script --
            are tried again by the next, though no file changed: it builds what
            met them, leaves the site a clean build's, and only then answers from
            the record of an unchanged site"
    (without-racy-window
     (fn []
       (let [root (fixture-copy)
             dir (io/file (temp-dir) "site")
             timed-out (with-redefs [sandbox/sci-call! (test-sandbox/timing-out
                                                        #":document for bible:blivre/romanos|:locate for bible:blivre/joao")]
                         (build-seen! root dir))]
         (testing "not vacuous: the first build met both timeouts, and failed the page of romanos"
           (is (str/includes? (:err timed-out) ":document for bible:blivre/romanos did not finish") (:err timed-out))
           (is (str/includes? (:err timed-out) ":locate for bible:blivre/joao did not finish") (:err timed-out))
           (is (re-find #"(?m)^failed +bible:blivre/romanos" (:err timed-out)) (:err timed-out)))
         (testing "it leaves no record of an unchanged site, and saves the pages that met one as such"
           (is (not (has-record? dir)))
           (is (some :transient? (vals (:books (state/load-state dir))))))
         (let [retried (build-seen! root dir)]
           (is (not (:unchanged? retried)))
           (is (not (str/includes? (:err retried) "did not finish")) (:err retried))
           (testing "the next build makes the page that failed and the pages that quote it"
             (is (= #{"bible:blivre/romanos" "reformed:catecismo-heidelberg"}
                    (set/intersection #{"bible:blivre/romanos" "reformed:catecismo-heidelberg"}
                                      (set (keys (rebuilt-ids (:err retried))))))
                 (:err retried)))
           (same-as-clean? root dir retried "after a timeout"))
         (testing "and once settled, a later build answers from the record again"
           (is (:unchanged? (settle! root dir)))))))))

;; --- TASK-313: a timed-out :books keeps the shelf's pages -------------------------

(defn- bible-tree
  "`site-tree` of `dir`, only the Bible library's pages and catalog."
  [dir]
  (into (sorted-map)
        (filter #(or (str/starts-with? (key %) "books/bible") (str/starts-with? (key %) "catalog/bible")))
        (site-tree dir)))

(deftest timed-out-books-keeps-the-shelfs-pages-test
  (without-racy-window
   (fn []
     (let [root (fixture-copy)
           dir (io/file (temp-dir) "site")
           _ (is (:unchanged? (do (build! root dir) (settle! root dir))))
           before (site-tree dir)
           bible-before (bible-tree dir)
           saved-before (select-keys (:books (state/load-state dir))
                                     (filter #(= "bible" (:library %)) (keys (:books (state/load-state dir)))))
           generation (state/saved-generation dir)
           ;; the script is edited, and the process cannot run it in time
           _ (write-file! root "bible/blivre/shelf.clj"
                          (str (read-file root "bible/blivre/shelf.clj") "\n;; edited\n"))
           timed-out (with-redefs [sandbox/sci-call! (test-sandbox/timing-out #"shelf.clj :books")]
                       (build-seen! root dir))]
       (testing "not vacuous: the first run met the timeout, and the shelf had books to lose"
         (is (str/includes? (:err timed-out) ":books did not finish") (:err timed-out))
         (is (seq bible-before))
         (is (seq saved-before)))
       (testing "the shelf's pages, data files and catalog stay, and nothing is removed or rebuilt"
         (is (= bible-before (bible-tree dir)) (str (vec (tree-diff bible-before (bible-tree dir)))))
         (is (= before (site-tree dir)) (str (vec (tree-diff before (site-tree dir)))))
         (is (not (re-find #"(?m)^removed " (:err timed-out))) (:err timed-out))
         ;; only the page whose own diagnostic names the failure (a link to a
         ;; book the shelf never had) is made again
         (is (= #{"reformed:dangling"} (set (keys (rebuilt-ids (:err timed-out))))) (:err timed-out)))
       (testing "the books that quote the shelf's books still resolve them"
         (is (not (str/includes? (:err timed-out) "not-found")))
         (is (= saved-before
                (select-keys (:books (state/load-state dir)) (keys saved-before)))
             "the state keeps the shelf's records"))
       (testing "the run is not settled: no record of an unchanged site, and the failure is reported"
         (is (not (has-record? dir)))
         (is (not (:unchanged? timed-out)))
         (is (some? generation)))
       (let [retried (build-seen! root dir)]
         (testing "the next run calls :books again and rebuilds only what changed"
           (is (not (:unchanged? retried)))
           (is (not (str/includes? (:err retried) "did not finish")) (:err retried))
           (is (= #{"reformed:dangling"} (set (keys (rebuilt-ids (:err retried))))) (:err retried))
           (is (= before (site-tree dir)))
           (same-as-clean? root dir retried "after a timed-out :books"))
         (testing "and once settled, a later build answers from the record again"
           (is (:unchanged? (settle! root dir)))))
       (testing "an empty answer is not a failure: the shelf's pages are removed"
         (write-file! root "bible/blivre/blivre.json" "[]")
         (let [r (build! root dir)]
           (is (re-find #"(?m)^removed +bible:blivre/" (:err r)) (:err r))
           (is (empty? (filter #(str/starts-with? % "books/bible/blivre/") (keys (site-tree dir)))))))))))

(deftest two-timed-out-books-in-a-row-keep-the-shelfs-pages-test
  (without-racy-window
   (fn []
     (let [root (fixture-copy)
           dir (io/file (temp-dir) "site")
           _ (build! root dir)
           _ (settle! root dir)
           before (site-tree dir)
           data (bible root)
           i (first (keep-indexed #(when (= "romanos" (get %2 "slug")) %1) data))
           edited (update-in data [i "verses" 0 2] str " (edited)")
           fail! #(with-redefs [sandbox/sci-call! (test-sandbox/timing-out #"shelf.clj :books")]
                    (build-seen! root dir))]
       (bible! root edited)
       (doseq [n [1 2]]
         (let [r (fail!)]
           (testing (str "failure " n ": the shelf's pages stay and nothing is removed")
             (is (str/includes? (:err r) ":books did not finish") (:err r))
             (is (not (re-find #"(?m)^removed " (:err r))) (:err r))
             ;; the edited book's own page is made again (its document does not
             ;; depend on :books); no other page of the shelf changes
             (is (empty? (remove #(str/starts-with? % "books/bible/blivre/romanos/")
                                 (filter #(str/starts-with? % "books/bible/") (tree-diff before (site-tree dir)))))
                 (str (vec (tree-diff before (site-tree dir)))))
             (is (= 5 (count (filter #(re-find #"^books/bible/blivre/[^/]+/index\.html$" %)
                                     (keys (site-tree dir))))))
             (is (not (has-record? dir))))))
       (let [recovered (build-seen! root dir)
             ids (set (keys (rebuilt-ids (:err recovered))))]
         (testing "the run that recovers rebuilds no book of the shelf: none is new"
           (is (not (re-find #"new book" (:err recovered))) (:err recovered))
           (is (not (re-find #"(?m)^removed " (:err recovered))) (:err recovered))
           (is (empty? (filter #(str/starts-with? % "bible:") ids)) (:err recovered))
           (is (str/includes? (read-file dir "books/bible/blivre/romanos/index.html") "(edited)"))
           (same-as-clean? root dir recovered "after two timed-out :books"))
         (testing "and once settled, a later build answers from the record again"
           (is (:unchanged? (settle! root dir)))))))))

(deftest code-replaced-mid-run-test
  (testing "the record names the build that ran, not the code on disk when it was saved:
            a jar or binary replaced during a long run does not get a hit next time"
    (without-racy-window
     (fn []
       (let [root (fixture-copy)
             dir (io/file (temp-dir) "site")
             current (atom "build A")]
         (with-redefs [version/running-build-identity (fn [] @current)]
           (build! root dir)
           (is (:unchanged? (settle! root dir)) "settled under build A")
           (unchanged/clear! dir)
           ;; a long run of build A, during which build B lands on disk
           (build! root dir :hook (fn [stage _] (when (= :save stage) (reset! current "build B"))))
           (is (= "build A" (get-in (unchanged/read-record dir) [:head :build])))
           (is (not (:unchanged? (build-seen! root dir))) "build B goes the long way")
           (is (:unchanged? (build-seen! root dir)) "and then answers from its own record")))))))

(def ^:private evil-extension
  "An extension whose HTML a trusted build emits as it is and an
  untrusted one sanitizes: a `javascript:` link and an event handler."
  (str "{:name \"evil\"\n"
       " :emit {:html (fn [directive ctx]\n"
       "               [:div {:class \"evil\" :onclick \"alert(0)\"}\n"
       "                [:a {:href \"javascript:alert(1)\"} \"go\"]])}}\n"))

(deftest withdrawing-trust-rebuilds-test
  (let [root (fixture-copy)
        dir (io/file (temp-dir) "site")
        ext (str (write-file! root "evil.clj" evil-extension))
        page "books/reformed/estudos/queda/index.html"]
    (write-file! root "reformed/estudos/queda.hdoc"
                 (str (read-file root "reformed/estudos/queda.hdoc") "\n:::{evil}\nx\n:::\n"))
    (build! root dir :args ["--extensions" ext "--trusted-extensions"])
    (testing "a trusted extension's javascript: link is emitted as it is"
      (is (str/includes? (read-file dir page) "javascript:")))
    (testing "withdrawing trust is a full build, and the page is sanitized"
      (let [{:keys [err]} (build! root dir :args ["--extensions" ext])]
        (is (= 9 (count (rebuilt-ids err))) err)
        (is (every? #{"full build: --trusted-extensions was given or withdrawn"} (vals (rebuilt-ids err))) err)
        (is (not (str/includes? (read-file dir page) "javascript:")))))
    (testing "and giving it again is one too"
      (let [{:keys [err]} (build! root dir :args ["--extensions" ext "--trusted-extensions"])]
        (is (every? #{"full build: --trusted-extensions was given or withdrawn"} (vals (rebuilt-ids err))) err)
        (is (str/includes? (read-file dir page) "javascript:"))))
    (testing "the same flags again rebuild nothing"
      (is (= {} (rebuilt-ids (:err (build! root dir :args ["--extensions" ext "--trusted-extensions"]))))))))

;; --- TASK-297: building only the libraries that changed ------------------------------
;;
;; When the record shows that only some libraries changed, a build loads,
;; lists, builds and sweeps only those and the libraries connected to them
;; through references, and carries every other library as it is. Each test
;; below builds one site that way and a twin the long way (its record
;; cleared before every run): the two must print the same, leave the same
;; site and save the same state -- and both must be a clean build's site.

(defn- three-library-copy
  "The site fixture set and a third library, `solo`, which no book of the
  other two refers to and which refers to none of theirs: its books link
  to and quote each other, and one shows an image. And `reformed:away`,
  which links to a library the directory does not have (`nolib`): an
  `:unknown-library` warning, whose message lists the libraries there
  are."
  ^File []
  (let [root (fixture-copy)]
    (write-file! root "libraries.yaml"
                 (str (read-file root "libraries.yaml") "- name: solo\n  type: filesystem\n  path: solo\n"))
    (write-file! root "solo/a.hdoc" "---\ntitle: Solo A\n---\n\n# A {#sec:a}\n\nThe first, see @{solo:b}.\n")
    (write-file! root "solo/b.hdoc" "---\ntitle: Solo B\n---\n\n# B {#sec:b}\n\n!quote solo:a#sec:a\n")
    (write-file! root "solo/pic/book.hdoc" "---\ntitle: Pic\n---\n\n# Pic {#sec:p}\n\n![A picture](pic.png)\n")
    (write-file! root "solo/pic/pic.png" (.getBytes "a picture" "UTF-8"))
    (write-file! root "reformed/away.hdoc" "---\ntitle: Away\n---\n\nSee @{nolib:x}.\n")
    root))

(def ^:private solo-edits
  "Edits of the `solo` library (`three-library-copy`), as `edits`: nil
  when one does not apply (the library's folder is away, say)."
  {:solo-body
   (fn [_ root n]
     (when (exists? root "solo/b.hdoc")
       (write-file! root "solo/b.hdoc" (str (read-file root "solo/b.hdoc") "\nMore, " n ".\n"))
       "edit the body of solo:b"))

   :solo-quoted-passage
   (fn [_ root n]
     (when (exists? root "solo/a.hdoc")
       (write-file! root "solo/a.hdoc"
                    (str/replace (read-file root "solo/a.hdoc") #"The first[^,]*," (str "The first " n ",")))
       "edit solo:a#sec:a, which solo:b quotes"))

   :solo-add-book
   (fn [_ root n]
     (when (.isDirectory (io/file root "solo"))
       (write-file! root (str "solo/c-" n ".hdoc")
                    (str "---\ntitle: C " n "\n---\n\nSee @{solo:a#sec:a} and @{solo:pic}.\n"))
       (str "add solo/c-" n ".hdoc")))

   :solo-toggle-book
   (fn [_ root _]
     (cond
       (exists? root "solo/b.hdoc")
       (do (.delete (io/file root "solo/b.hdoc")) "delete solo/b.hdoc, which solo:a links to")
       (.isDirectory (io/file root "solo"))
       (do (write-file! root "solo/b.hdoc" "---\ntitle: Solo B\n---\n\n# B {#sec:b}\n\n!quote solo:a#sec:a\n")
           "restore solo/b.hdoc")
       :else nil))

   :solo-image
   (fn [_ root n]
     (when (exists? root "solo/pic/pic.png")
       (write-file! root "solo/pic/pic.png" (.getBytes (str "picture " n) "UTF-8"))
       "edit the image solo:pic shows"))

   ;; a book of `solo` that comes to link to `reformed`: the scope cannot
   ;; stand for the whole build (catkins's backlinks change)
   :solo-cross
   (fn [_ root _]
     (cond
       (exists? root "solo/x.hdoc")
       (do (.delete (io/file root "solo/x.hdoc")) "delete solo/x.hdoc, which links to reformed:catkins")
       (.isDirectory (io/file root "solo"))
       (do (write-file! root "solo/x.hdoc" "---\ntitle: X\n---\n\nSee @{reformed:catkins}.\n")
           "add solo/x.hdoc, which links to reformed:catkins")
       :else nil))

   ;; `solo` unavailable and back: `reformed:away`'s warning lists the
   ;; libraries there are
   :solo-away
   (fn [_ root _]
     (let [here (.toPath (io/file root "solo"))
           away (.toPath (io/file root "solo-away"))]
       (if (Files/exists here (make-array LinkOption 0))
         (do (Files/move here away (make-array CopyOption 0)) "move the solo library's folder away")
         (do (Files/move away here (make-array CopyOption 0)) "move the solo library's folder back"))))})

(defn- state-of
  "What the state saved for the site `dir` loads as: the graph's data, the
  pages' data, the listing caches and the file stamps."
  [dir]
  (let [s (state/load-state dir)]
    (is (:usable? s) (pr-str (dissoc s :graph)))
    {:graph (state/graph-data (:graph s)) :books (:books s) :listing (:listing s) :files (:files s)}))

(defn- like-the-long-way?
  "Assert that the site `a`, just built with `ra` (`build-seen!`), is what
  its twin `b`, built the long way with `rb`, is: the same lines, status,
  site and saved state."
  [a ra b rb what]
  (is (= (:err rb) (:err ra)) (str what ": prints what the long way prints"))
  (is (= (:status rb) (:status ra)) what)
  (let [ta (site-tree a) tb (site-tree b)]
    (is (= tb ta) (str what ": leaves the site the long way leaves, but at " (vec (tree-diff tb ta)))))
  (let [sa (state-of a) sb (state-of b)]
    (doseq [k (keys sa)]
      (is (= (get sb k) (get sa k)) (str what ": saves the state's " k " the long way saves")))))

(defn- the-long-way!
  "A run of `root` into `dir` the long way: the record cleared first."
  [root dir]
  (unchanged/clear! dir)
  (build-seen! root dir))

(deftest ^:slow only-the-changed-libraries-are-built-test
  (without-racy-window
   (fn []
     (let [rnd (Random. 297)
           root (three-library-copy)
           a (io/file (temp-dir) "a")
           b (io/file (temp-dir) "b")
           all-edits (merge edits solo-edits)
           shuffled (fn [xs] (let [l (java.util.ArrayList. ^java.util.Collection (vec xs))]
                               (java.util.Collections/shuffle l rnd)
                               (vec l)))
           ;; every kind, then the solo kinds again (each toggle both ways)
           kinds (concat (shuffled (sort (keys all-edits))) (shuffled (sort (keys solo-edits))))
           stats (atom {:applied #{} :partial 0 :whole 0 :fallback 0 :hit 0})]
       (build! root a)
       (build! root b)
       (is (:unchanged? (settle! root a)) "settled before the first change")
       (letfn [(step! [n kind]
                 (when-let [what ((get all-edits kind) rnd root n)]
                   (let [ra (build-seen! root a)
                         rb (the-long-way! root b)]
                     (swap! stats #(cond-> (update % :applied conj kind)
                                     (and (:delta ra) (< (count (:delta ra)) 3)) (update :partial inc)
                                     (and (:delta ra) (= 3 (count (:delta ra)))) (update :whole inc)
                                     (and (:changed ra) (nil? (:delta ra))) (update :fallback inc)))
                     (like-the-long-way? a ra b rb what)
                     (same-as-clean? root a ra what)
                     ;; (no record while a library is unavailable; and a
                     ;; duplicate id restored leaves a stamp unverified,
                     ;; which the next run verifies, TASK-296)
                     (when (.isDirectory (io/file root "solo"))
                       (is (:unchanged? (settle! root a)) (str what ": then the record answers"))
                       (swap! stats update :hit inc)))))]
         (doseq [[n kind] (map-indexed vector kinds)]
           (step! n kind))
         ;; the solo library back, and any kind that found nothing to do
         (when-not (.isDirectory (io/file root "solo"))
           (step! 1000 :solo-away))
         (doseq [[n kind] (map-indexed vector (sort (remove (:applied @stats) (keys all-edits))))]
           (step! (+ 2000 n) kind)))
       (testing (str "not vacuous: " (pr-str @stats))
         (is (= (set (keys all-edits)) (:applied @stats)))
         (is (<= 5 (:partial @stats)) "builds of some libraries, the others carried")
         (is (<= 8 (:whole @stats)) "builds of every library connected to the change")
         (is (<= 2 (:fallback @stats)) "builds that could not stand for a whole one")
         (is (<= 15 (:hit @stats)) "the record answering after each"))))))

(deftest a-change-a-scope-cannot-hold-falls-back-test
  (without-racy-window
   (fn []
     (let [root (three-library-copy)
           a (io/file (temp-dir) "a")
           b (io/file (temp-dir) "b")
           step! (fn [what]
                   (let [ra (build-seen! root a) rb (the-long-way! root b)]
                     (like-the-long-way? a ra b rb what)
                     (same-as-clean? root a ra what)
                     ra))]
       (build! root a)
       (build! root b)
       (testing "a solo book that comes to link to reformed: catkins's backlinks list it"
         (is (:unchanged? (settle! root a)))
         ((get solo-edits :solo-cross) nil root 0)
         (let [ra (step! "a link into a library left as it was")]
           (is (= #{"solo"} (:changed ra)) "the record shows only solo changed")
           (is (nil? (:delta ra)) "but the build of solo alone fell back to the whole one")
           (is (str/includes? (read-file a "books/reformed/catkins/backlinks.json") "solo:x"))))
       (testing "solo becoming unavailable: away's unknown-library warning lists the libraries there are"
         (is (:unchanged? (settle! root a)))
         ((get solo-edits :solo-away) nil root 0)
         (let [ra (step! "a library unavailable")]
           (is (= #{"solo"} (:changed ra)))
           (is (nil? (:delta ra))))
         (testing "(a site with a library unavailable gets no record, TASK-296: the way back is the long way)"
           (is (not (:unchanged? (settle! root a))))
           ((get solo-edits :solo-away) nil root 0)
           (let [ra (step! "the library available again")]
             (is (nil? (:changed ra))))))))))

(deftest a-scope-loads-and-saves-only-its-libraries-test
  (without-racy-window
   (fn []
     (let [root (three-library-copy)
           dir (io/file (temp-dir) "site")
           parts (fn [] (into (sorted-map)
                              (for [^File f (.listFiles (state/state-dir dir))
                                    :when (re-matches #".*\.[0-9]+\.edn" (.getName f))]
                                [(.getName f) (.lastModified f)])))
           of-lib (fn [m lib] (into (sorted-map) (filter #(str/starts-with? (key %) (str lib ".")) m)))
           read-part @#'state/read-part]
       (build! root dir)
       (is (:unchanged? (settle! root dir)))
       (testing "an edit of a solo book: only solo's state is read and written"
         (let [before (parts)
               libraries-read (atom #{})]
           (write-file! root "solo/a.hdoc" (str (read-file root "solo/a.hdoc") "\nMore.\n"))
           (let [r (with-redefs [state/read-part (fn [d h [lib-name :as k]]
                                                   (swap! libraries-read conj lib-name)
                                                   (read-part d h k))]
                     (build-seen! root dir))]
             (is (= #{"solo"} (:delta r)) (:err r))
             (is (= {"solo:a" "source changed" "solo:b" "a book it refers to changed"} (rebuilt-ids (:err r)))
                 (:err r))
             (is (= #{"solo"} @libraries-read) "the state of the other libraries is not read"))
           (let [after (parts)]
             (is (= (of-lib before "bible") (of-lib after "bible")) "nor written")
             (is (= (of-lib before "reformed") (of-lib after "reformed")))
             (is (not= (of-lib before "solo") (of-lib after "solo"))))))
       (testing "the long way writes only the parts that changed, too"
         (is (:unchanged? (settle! root dir)))
         (let [before (parts)]
           (write-file! root "reformed/dangling.hdoc" (str (read-file root "reformed/dangling.hdoc") "\nMore.\n"))
           (let [r (the-long-way! root dir)]
             (is (= {"reformed:dangling" "source changed"} (rebuilt-ids (:err r))) (:err r)))
           (let [after (parts)]
             (is (= (of-lib before "bible") (of-lib after "bible")))
             (is (= (of-lib before "solo") (of-lib after "solo")))
             (is (not= (of-lib before "reformed") (of-lib after "reformed"))))
           (testing "and the state loads as a clean build's"
             (let [clean (io/file (temp-dir) "clean")]
               (build! root clean)
               (let [s (state-of dir) c (state-of clean)]
                 (is (= (:graph c) (:graph s)))
                 (is (= (:books c) (:books s)))
                 (is (= (:listing c) (:listing s))))))))))))

(deftest another-build-between-the-check-and-the-build-test
  (testing "TASK-297 review: a build that saves between the record's check and the build's own load
            leaves a state the record's shares do not stand beside: the build falls back"
    (without-racy-window
     (fn []
       (let [root (three-library-copy)
             dir (io/file (temp-dir) "site")
             original @#'cli/build-site*
             fired (atom false)]
         (build! root dir)
         (is (:unchanged? (settle! root dir)))
         (write-file! root "solo/b.hdoc" (str (read-file root "solo/b.hdoc") "\nMore.\n"))
         (let [r (with-redefs [cli/build-site* (fn [opts]
                                                 (when (and (:haselnuss.cli/delta opts) (not @fired))
                                                   (reset! fired true)
                                                   ;; meanwhile reformed gains a book, and
                                                   ;; another build saves the site
                                                   (write-file! root "reformed/race.hdoc"
                                                                "---\ntitle: Race\n---\n\nRace.\n")
                                                   (build! root dir :full? true))
                                                 (original opts))]
                   (build-seen! root dir))]
           (is @fired "the build of only solo started")
           (is (= #{"solo"} (:changed r)))
           (is (nil? (:delta r)) "and fell back to the whole build")
           (same-as-clean? root dir r "another build saved between the check and the build")))))))

(deftest a-build-that-died-between-the-check-and-the-build-test
  (testing "TASK-297 review: a build that takes the lock between the record's check and this build's,
            writes pages and dies before it saves the state leaves the generation as it was: the
            build of some libraries falls back, and the whole build redoes the pending pages"
    (without-racy-window
     (fn []
       (let [root (three-library-copy)
             dir (io/file (temp-dir) "site")
             original @#'cli/build-site*
             fired (atom false)
             fell-back (atom false)
             book (io/file root "reformed/dangling.hdoc")]
         (build! root dir)
         (is (:unchanged? (settle! root dir)))
         (write-file! root "solo/b.hdoc" (str (read-file root "solo/b.hdoc") "\nMore.\n"))
         (let [r (with-redefs [cli/build-site*
                               (fn [opts]
                                 (if-not (and (:haselnuss.cli/delta opts) (not @fired))
                                   (original opts)
                                   (do
                                     (reset! fired true)
                                   ;; meanwhile: reformed:dangling edited, built by a
                                   ;; build killed before it saved, the edit undone in
                                   ;; place with its old time
                                     (let [text (slurp book)
                                           mtime (Files/getLastModifiedTime (.toPath book) (make-array LinkOption 0))]
                                       (spit book (str/replace text "tobias" "tobit"))
                                       (touch! book)
                                       (let [killed (build! root dir :hook (fn [stage _]
                                                                             (when (= :save stage)
                                                                               (throw (ex-info "killed" {})))))]
                                         (is (= 2 (:status killed)) (:err killed)))
                                       (spit book text)
                                       (Files/setLastModifiedTime (.toPath book) mtime))
                                   ;; (the killed build ran through these redefs too:
                                   ;; whether this one fell back is told here)
                                     (try (original opts)
                                          (catch clojure.lang.ExceptionInfo e
                                            (when (= :haselnuss.cli/delta-fallback (:type (ex-data e)))
                                              (reset! fell-back true))
                                            (throw e))))))]
                   (build-seen! root dir))]
           (is @fired "the build of only solo started")
           (is @fell-back "and fell back to the whole build")
           (is (= "left pending by an earlier run" (get (rebuilt-ids (:err r)) "reformed:dangling"))
               (:err r))
           (same-as-clean? root dir r "a build died between the check and the build")))))))

(defn- part-files-of
  "`{library #{path}}`: the file stamps each library's state parts hold."
  [dir]
  (reduce (fn [m ^File f]
            (if (re-matches #".*\.[0-9]+\.edn" (.getName f))
              (let [part (edn/read-string (slurp f))]
                (update m (:name part) (fnil into #{}) (keys (:files part))))
              m))
          {}
          (.listFiles (state/state-dir dir))))

(deftest a-file-two-libraries-claim-stays-in-its-part-test
  (testing "TASK-297 review: a file books of two libraries claim is saved with the first of them by name,
            whichever of them a build left as it was"
    (without-racy-window
     (fn []
       (let [root (temp-dir)
             a (io/file (temp-dir) "a")
             b (io/file (temp-dir) "b")]
         (write-file! root "libraries.yaml" (str "- name: aa\n  type: filesystem\n  path: aa\n"
                                                 "- name: zz\n  type: filesystem\n  path: zz\n"))
         (write-file! root "aa/one.hdoc" "---\ntitle: One\n---\n\n!include ../zz/shared.hdoc\n")
         (write-file! root "zz/shared.hdoc" "---\ntitle: Shared\n---\n\nShared text.\n")
         (write-file! root "zz/other.hdoc" "---\ntitle: Other\n---\n\nOther.\n")
         (build! root a)
         (build! root b)
         (is (:unchanged? (settle! root a)))
         (let [shared (some #(when (str/ends-with? % "shared.hdoc") %) (get (part-files-of b) "aa"))]
           (is (some? shared) "the whole build saves it with aa")
           (write-file! root "zz/other.hdoc" "---\ntitle: Other\n---\n\nOther, edited.\n")
           (let [ra (build-seen! root a)
                 rb (the-long-way! root b)]
             (is (= #{"zz"} (:delta ra)) "a build of zz alone")
             (like-the-long-way? a ra b rb "edit a book of the library that shares the file")
             (is (= (part-files-of b) (part-files-of a)) "each part holds the stamps the whole build's does")
             (is (not (contains? (get (part-files-of a) "zz") shared))))))))))

;; --- TASK-310: a folder under books/ the sweep cannot read ----------------------------

(defn- chmod! [^File f perms]
  (Files/setPosixFilePermissions (.toPath f) (java.nio.file.attribute.PosixFilePermissions/fromString perms)))

(deftest unreadable-folder-under-books-test
  (let [root (temp-dir)
        dir (io/file root "site")
        locked (io/file dir "books/lib/locked")]
    (write-file! root "libraries.yaml" "- name: lib\n  type: filesystem\n  path: lib\n")
    (write-file! root "lib/a.hdoc" "---\ntitle: A\n---\n\nFirst.\n")
    (write-file! root "lib/b.hdoc" "---\ntitle: B\n---\n\nSecond.\n")
    (build! root dir)
    (write-file! dir "books/lib/locked/stale.txt" "kept")
    (try
      (chmod! locked "---------")
      (if (.canRead locked)
        (println "skipped: permissions do not apply to this user (root?)")
        (do
          (testing "an unreadable folder is reported, the build goes on, the status is non-zero"
            (write-file! root "lib/a.hdoc" "---\ntitle: A\n---\n\nFirst, edited.\n")
            (let [{:keys [status err]} (build! root dir)]
              (is (= 1 status) err)
              (is (str/includes? err "books/lib/locked") err)
              (is (str/includes? err "error") err)
              (is (str/includes? err "1 error") err)
              (is (str/includes? (read-file dir "books/lib/a/index.html") "First, edited.")
                  "the other books are still built")))
          (testing "a repeat build reports it again, as found now"
            (let [{:keys [status err]} (build! root dir)]
              (is (= 1 status) err)
              (is (str/includes? err "books/lib/locked") err)
              (is (not (str/includes? err site/earlier-note)) err)))
          (testing "readable again, nothing is carried over"
            (chmod! locked "rwxr-xr-x")
            (let [{:keys [status err]} (build! root dir)]
              (is (= 0 status) err)
              (is (not (str/includes? err "locked")) err)
              (is (= "kept" (read-file dir "books/lib/locked/stale.txt")))))
          (testing "a folder that cannot be written is reported where a stale page cannot be removed"
            (write-file! dir "books/lib/gone/index.html" "stale")
            (chmod! (io/file dir "books/lib/gone") "r-xr-xr-x")
            (let [{:keys [status err]} (build! root dir)]
              (is (= 1 status) err)
              (is (str/includes? err "books/lib/gone/index.html") err))
            (chmod! (io/file dir "books/lib/gone") "rwxr-xr-x")
            (let [{:keys [status err]} (build! root dir)]
              (is (= 0 status) err)
              (is (not (.exists (io/file dir "books/lib/gone"))))))))
      (finally
        ;; the folders chmodded above by name: `file-seq` cannot list a 000 one
        (doseq [^File f [locked (io/file dir "books/lib/gone")] :when (.exists f)]
          (chmod! f "rwxr-xr-x"))))))

(deftest unreadable-catalog-folder-test
  (let [root (temp-dir)
        dir (io/file root "site")
        catalog-lib (io/file dir "catalog/lib")]
    (write-file! root "libraries.yaml" "- name: lib\n  type: filesystem\n  path: lib\n")
    (write-file! root "lib/a.hdoc" "---\ntitle: A\n---\n\nFirst.\n")
    (build! root dir)
    (try
      (write-file! dir "catalog/lib/9999.json" "[]")
      (chmod! catalog-lib "r-xr-xr-x")
      (if (.canWrite catalog-lib)
        (println "skipped: permissions do not apply to this user (root?)")
        (do
          (testing "a stale catalog page that cannot be removed is reported (the unchanged-site shortcut gives way)"
            (let [{:keys [status err]} (build! root dir)]
              (is (= 1 status) err)
              (is (str/includes? err "catalog/lib/9999.json") err)
              (is (.exists (io/file dir "catalog/lib/9999.json")))))
          (testing "a catalog folder that cannot be listed is answered, not thrown (a build cannot
                    write its catalog pages there either, which is a failure of its own)"
            (chmod! catalog-lib "---------")
            (let [found (site/sweep-catalog! dir #{})]
              (is (= #{"catalog/lib"} (set (map first found))) (pr-str found))))
          (testing "readable and writable again: swept, exit 0"
            (chmod! catalog-lib "rwxr-xr-x")
            (let [{:keys [status err]} (build! root dir)]
              (is (= 0 status) err)
              (is (not (.exists (io/file dir "catalog/lib/9999.json"))))))))
      (finally
        (when (.exists catalog-lib) (chmod! catalog-lib "rwxr-xr-x"))))))
