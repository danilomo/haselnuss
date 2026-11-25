(ns haselnuss.site-data-test
  "The data files `--build-site` writes beside the pages (TASK-271; doc-12,
  \"Output folder\", \"Scale\" 4-5): the paged catalog, each book's
  `backlinks.json` and `previews.json` -- built from the site fixture set
  (`test/fixtures/site/`) and checked against the reference graph the
  build saved -- written only when their bytes change, and swept when
  stale; and `site/cut-passage`, which cuts a preview on the AST."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [haselnuss.ast :as ast]
            [haselnuss.book-ref :as book-ref]
            [haselnuss.cli :as cli]
            [haselnuss.emit.html :as html]
            [haselnuss.library.graph :as graph]
            [haselnuss.library.graph.backlinks :as backlinks]
            [haselnuss.library.state :as state]
            [haselnuss.parser :as parser]
            [haselnuss.site :as site])
  (:import (java.io File)
           (java.nio.file Files LinkOption)
           (java.nio.file.attribute FileAttribute FileTime)))

;; --- helpers -----------------------------------------------------------------

(def ^:private site-yaml "test/fixtures/site/libraries.yaml")

(def ^:private fixture-root (.getCanonicalPath (io/file "test/fixtures/site")))

(def ^:private overrides
  {:library-env {} :library-home "/nonexistent/haselnuss-test-home"
   :haselnuss.cli/check-base-dir (System/getProperty "user.dir")})

(defn- temp-dir ^File []
  (.toFile (Files/createTempDirectory "haselnuss-site-data" (make-array FileAttribute 0))))

(defn- build!
  "Build the fixture set into `dir`, `flags` added; answers `{:status :err}`."
  [dir & flags]
  (let [err (java.io.StringWriter.)
        status (binding [*err* err *out* (java.io.StringWriter.)]
                 (cli/main* (into ["--libraries" site-yaml "--build-site" (str dir)] flags)
                            (assoc overrides :haselnuss.cli/site-threads 3)))]
    {:status status :err (str err)}))

(defn- read-json [^File f] (json/read-str (slurp f)))

(defn- write! ^File [dir rel text]
  (let [f (io/file dir rel)]
    (io/make-parents f)
    (spit f text)
    f))

(defn- png!
  "A few bytes at `rel` below `dir`, named like an image."
  ^File [dir rel]
  (let [f (io/file dir rel)]
    (io/make-parents f)
    (with-open [out (io/output-stream f)]
      (.write out (byte-array [(unchecked-byte 0x89) 80 78 71])))
    f))

(defn- data-files
  "Every catalog and book data file below `dir`, relative paths, sorted."
  [dir]
  (let [root (io/file dir)]
    (vec (sort (for [^File f (file-seq root)
                     :let [rel (str (.relativize (.toPath root) (.toPath f)))]
                     :when (and (.isFile f)
                                (or (str/starts-with? rel "catalog/")
                                    (re-find #"/(backlinks|previews)\.json$" rel)))]
                 rel)))))

(def ^:private void-elements #{"br" "hr" "img" "input" "meta" "link" "wbr" "source" "col" "area"})

(defn- balanced?
  "True when every element `html` opens is closed, in order (void elements
  aside) -- what a cut that broke the markup would not be."
  [html]
  (let [stack (reduce (fn [stack [_ close tag self-closing]]
                        (let [tag (str/lower-case tag)]
                          (cond
                            (= :broken stack) stack
                            (or (contains? void-elements tag) (seq self-closing)) stack
                            (seq close) (if (= tag (peek stack)) (pop stack) :broken)
                            :else (conj stack tag))))
                      []
                      (re-seq #"<(/?)([A-Za-z][A-Za-z0-9-]*)(?:\s[^>]*?)?(/?)>" html))]
    (= [] stack)))

(defn- text-of-html [html]
  (-> html (str/replace #"<[^>]*>" "") (str/replace "&amp;" "&") (str/replace "&lt;" "<")
      (str/replace "&gt;" ">") (str/replace "&quot;" "\"")))

;; --- AC #1, #2, #3: the fixture site's data files ----------------------------------

(def ^:private built
  (delay (let [dir (temp-dir)
               result (build! dir)]
           (assoc result :dir dir :graph (:graph (state/load-state dir))))))

(deftest catalog-test
  (let [{:keys [dir status err]} @built
        index (read-json (io/file dir "catalog/index.json"))]
    (is (= 1 status) err)
    (testing "the index lists the libraries by name with their counts"
      (is (= {"version" 1
              "page-size" 500
              "libraries" [{"name" "bible" "colour" (site/library-colour "bible") "books" 5 "pages" 1}
                           {"name" "reformed" "colour" (site/library-colour "reformed") "books" 4 "pages" 1}]}
             index)))
    (testing (str "a page per library, sorted shelf, title, id; the two books sharing an id are"
                  " left out, like their pages")
      (let [bible (read-json (io/file dir "catalog/bible/1.json"))
            reformed (read-json (io/file dir "catalog/reformed/1.json"))]
        (is (str/starts-with? (slurp (io/file dir "catalog/bible/1.json"))
                              "{\"version\":1,\"library\":\"bible\",\"page\":1,\"pages\":1,\"books\":[{\"id\":")
            "keys in a fixed order, one line")
        (is (= ["1 Coríntios" "Gênesis" "João" "Mateus" "Romanos"] (map #(get % "title") (get bible "books"))))
        (is (= ["reformed:dangling" "reformed:catecismo-heidelberg" "reformed:catkins" "reformed:estudos/queda"]
               (map #(get % "id") (get reformed "books")))
            "no shelf before estudos/, then by title: A Dangling..., Catecismo..., Catkins")
        (is (= {"id" "reformed:catecismo-heidelberg"
                "title" "Catecismo de Heidelberg (1563), Domingo 1"
                "authors" ["Zacarias Ursino" "Gaspar Oleviano"]
                "library" "reformed"
                "shelf" ""
                "path" "books/reformed/catecismo-heidelberg/index.html"
                "colour" (site/library-colour "reformed")}
               (second (get reformed "books"))))
        (is (= "blivre" (get-in bible ["books" 0 "shelf"])))
        (doseq [book (concat (get bible "books") (get reformed "books"))]
          (is (.isFile (io/file dir (get book "path"))) (get book "path")))
        (is (not-any? #(= "reformed:notas" (get % "id")) (get reformed "books")))))))

(deftest backlinks-json-test
  (let [{:keys [dir graph]} @built]
    (testing "each book's backlinks.json is its --backlinks JSON report, positions shown as
              <library>/<path below its root> (AC #1)"
      (is graph)
      (doseq [[id {:keys [path]}] (:books (state/load-state dir))
              :let [f (io/file (.getParentFile (io/file dir path)) "backlinks.json")
                    report (backlinks/backlinks-report graph id {:base-dir fixture-root})]]
        (is (= (backlinks/render-json report) (slurp f))
            (str (book-ref/format-book-id id) ": the fixture's libraries sit in their library-named folders,"
                 " so it reads as the report relative to the directory file's folder"))
        (is (= (backlinks/render-json (site/site-backlinks-report graph id)) (slurp f)))))
    (testing "Romanos' lists the catechism's quoting passages (AC #2)"
      (let [report (read-json (io/file dir "books/bible/blivre/romanos/backlinks.json"))
            quotes (filter #(= "quote" (get % "kind")) (get report "edges"))]
        (is (= [["reformed:catecismo-heidelberg" "sec:q2" "bible:romanos#3:10"]
                ["reformed:catecismo-heidelberg" "sec:q1" "bible:romanos#8:28"]
                ["reformed:catecismo-heidelberg" "sec:q1" "bible:romanos#14:8"]]
               (map (fn [e] [(get-in e ["from" "id"]) (get-in e ["from" "passage"]) (get-in e ["to" "written"])])
                    quotes)))
        (is (= "reformed/catecismo-heidelberg.hdoc" (get-in (first quotes) ["position" "file"])))))))

(deftest previews-json-test
  (let [{:keys [dir graph]} @built
        previews-of (fn [page-dir] (read-json (io/file dir page-dir "previews.json")))]
    (testing "previews.json holds one entry per passage other books link to, as the graph says (AC #1)"
      (doseq [[id {:keys [path]}] (:books (state/load-state dir))
              :let [expected (->> (graph/edges-to graph id)
                                  (filter #(and (= :link (:kind %)) (= :resolved (:status %))
                                                (not= id (:id (:from %)))))
                                  (map graph/edge-passage-key)
                                  set)
                    json (read-json (io/file (.getParentFile (io/file dir path)) "previews.json"))]]
        (is (= expected (set (map #(get % "passage") (get json "previews")))) (book-ref/format-book-id id))
        (is (= (book-ref/format-book-id id) (get json "book")))))
    (testing "non-vacuous: the Bible books are linked, the catechism is quoted, not linked"
      (is (= ["3:1-24" "3:15"] (map #(get % "passage") (get (previews-of "books/bible/blivre/genesis") "previews"))))
      (is (= ["8:28" "14:8"] (map #(get % "passage") (get (previews-of "books/bible/blivre/romanos") "previews"))))
      (is (= [] (get (previews-of "books/reformed/catecismo-heidelberg") "previews"))))
    (testing "a short passage, whole: the quote as !quote renders it, linked within the target's folder"
      (let [verse (first (filter #(= "14:8" (get % "passage"))
                                 (get (previews-of "books/bible/blivre/romanos") "previews")))]
        (is (= {"passage" "14:8"
                "ref" "bible:blivre/romanos#14:8"
                "title" "Romanos 14:8"
                "url" "index.html#v14-8"
                "cut" false
                "math" false}
               (dissoc verse "html")))
        (is (= (str "<figure class=\"book-quote-figure\"><blockquote class=\"book-quote\""
                    " data-source=\"bible:blivre/romanos#14:8\" cite=\"index.html#v14-8\"><p>Porque, se"
                    " vivemos, para o Senhor vivemos; e se morremos, para o Senhor morremos. Portanto, quer"
                    " vivamos, quer morramos, do Senhor somos.</p></blockquote><figcaption>— <cite><a"
                    " href=\"index.html#v14-8\">Romanos 14:8</a></cite></figcaption></figure>")
               (get verse "html")))
        (is (str/includes? (slurp (io/file dir "books/bible/blivre/romanos/index.html")) "id=\"v14-8\"")
            "the anchor is on the page beside previews.json")))
    (testing "Gênesis 3:1-24 (about 2,960 characters) is cut at about 2,000 and flagged (AC #3)"
      (let [[long-one short-one] (get (previews-of "books/bible/blivre/genesis") "previews")
            html (get long-one "html")
            quoted (second (re-find #"(?s)<blockquote[^>]*>(.*)</blockquote>" html))
            text (text-of-html quoted)]
        (is (true? (get long-one "cut")))
        (is (false? (get short-one "cut")))
        (is (balanced? html) html)
        (is (str/ends-with? quoted "…</p>") "cut inside a verse, at a word, with an ellipsis")
        (is (<= 1900 (count text) 2001) (str (count text)))
        (is (str/includes? html "<figcaption>— <cite><a href=\"index.html#v3-1\">Gênesis 3:1-24</a>")
            "the source line is kept")))))

(deftest previews-are-sanitized-test
  (testing "a preview goes through the sanitizer as a page does, and shows images as the page does"
    (let [root (temp-dir)
          _ (write! root "lib/a.hdoc" (str "---\ntitle: A\n---\n\n# Alvo {#sec:x}\n\n"
                                           "[bad](javascript:alert(1)) [ok](https://example.org/x)"
                                           " ![fig](fig.png) ![away](../secret.png)\n"))
          _ (png! root "lib/fig.png")
          _ (write! root "lib/b.hdoc" "---\ntitle: B\n---\n\nSee @{lib:a#sec:x} and @{lib:a}.\n")
          yaml (write! root "libraries.yaml" "- name: lib\n  type: filesystem\n  path: lib\n")
          dir (io/file root "site")
          err (java.io.StringWriter.)
          _ (binding [*err* err] (cli/main* ["--libraries" (str yaml) "--build-site" (str dir)] overrides))
          previews (get (read-json (io/file dir "books/lib/a/previews.json")) "previews")
          [whole section] previews
          html (get section "html")]
      (is (= [nil "sec:x"] (map #(get % "passage") previews)) (str err))
      (is (= {"ref" "lib:a" "title" "A" "url" "index.html" "cut" false} (select-keys whole ["ref" "title" "url" "cut"])))
      (is (= "index.html#sec:x" (get section "url")))
      (is (not (str/includes? html "javascript:")) html)
      (is (str/includes? html "href=\"https://example.org/x\"") html)
      (is (str/includes? html "src=\"fig.png\"") html)
      (is (.isFile (io/file dir "books/lib/a/fig.png")) "the page's copy the preview's src finds")
      (is (not (str/includes? html "secret")) "an image a page would refuse is its alt text")
      (is (str/includes? html "away") html))))

(defn- build-lib!
  "Build the site of the directory file `yaml` into `root`/site; answers
  `{:dir :err :status}`."
  [root yaml]
  (let [dir (io/file root "site")
        err (java.io.StringWriter.)
        status (binding [*err* err] (cli/main* ["--libraries" (str yaml) "--build-site" (str dir)] overrides))]
    {:dir dir :err (str err) :status status}))

(deftest preview-images-are-the-pages-copies-test
  (testing "a preview shows only the images its page copied; a missing one, or one whose copy is
            another book's, is its alt text, as on the page"
    (let [root (temp-dir)
          _ (write! root "lib/a.hdoc" (str "---\ntitle: A\n---\n\n# X {#sec:x}\n\n"
                                           "![own](own.png) ![m](missing.png) ![o](b/fig.png)\n"))
          _ (png! root "lib/own.png")
          _ (write! root "lib/a/b.hdoc" "---\ntitle: AB\n---\n\n![f](fig.png)\n")
          _ (png! root "lib/a/fig.png")
          _ (png! root "lib/b/fig.png")
          _ (write! root "lib/c.hdoc" "---\ntitle: C\n---\n\nSee @{lib:a#sec:x}.\n")
          yaml (write! root "libraries.yaml" "- name: lib\n  type: filesystem\n  path: lib\n")
          {:keys [dir err]} (build-lib! root yaml)
          page (slurp (io/file dir "books/lib/a/index.html"))
          html (get-in (read-json (io/file dir "books/lib/a/previews.json")) ["previews" 0 "html"])]
      (is (str/includes? page "src=\"own.png\"") err)
      (is (not (str/includes? page "missing.png")) "the page drops the missing image")
      (is (not (str/includes? page "b/fig.png")) "and the one that would land in lib:a/b's folder")
      (is (str/includes? html "src=\"own.png\"") html)
      (is (not (str/includes? html "missing.png")) html)
      (is (not (str/includes? html "b/fig.png")) html)
      (is (re-find #"</?p>.*\bm\b.*\bo\b" html) "both are their alt text"))))

(deftest backlinks-never-show-absolute-paths-test
  (testing "a library outside the directory file's folder: positions are <library>/<path>"
    (let [elsewhere (temp-dir)
          root (temp-dir)
          _ (write! elsewhere "far/t.hdoc" "---\ntitle: T\n---\n\n# Alvo {#sec:x}\n\nTexto.\n")
          _ (write! elsewhere "far/sub/c.hdoc" "---\ntitle: C\n---\n\nVer @{far:t#sec:x}.\n")
          yaml (write! root "conf/libraries.yaml"
                       (str "- name: far\n  type: filesystem\n  path: "
                            (.getCanonicalPath (io/file elsewhere "far")) "\n"))
          {:keys [dir err]} (build-lib! root yaml)
          text (slurp (io/file dir "books/far/t/backlinks.json"))
          report (json/read-str text)]
      (is (= "far/sub/c.hdoc" (get-in report ["edges" 0 "position" "file"])) err)
      (is (= 5 (get-in report ["edges" 0 "position" "line"])))
      (is (not (str/includes? text (.getCanonicalPath elsewhere))) text)))
  (testing "a file no library root holds is shown as no file"
    (is (nil? (site/site-position-file {"far" (.toPath (io/file "/srv/far"))} "far" "/home/x/y.hdoc")))
    (is (= "far/a/b.hdoc" (site/site-position-file {"far" (.toPath (io/file "/srv/far"))} "far"
                                                   "/srv/far/a/b.hdoc")))))

(defn- symlink! [link target]
  (io/make-parents link)
  (Files/createSymbolicLink (.toPath (io/file link)) (.toPath (io/file target))
                            (make-array FileAttribute 0)))

(deftest backlinks-through-symlinked-roots-test
  (let [root (temp-dir)
        real (io/file root "real/far")
        _ (write! root "real/far/t.hdoc" "---\ntitle: T\n---\n\n# Alvo {#sec:x}\n\nTexto.\n")
        _ (write! root "real/far/sub/c.hdoc" "---\ntitle: C\n---\n\nVer @{far:t#sec:x}.\n")
        _ (write! root "outside/o.hdoc" "---\ntitle: O\n---\n\nVer @{far:t#sec:x}.\n")
        file-of (fn [yaml-path]
                  (let [yaml (write! root "conf/libraries.yaml"
                                     (str "- name: far\n  type: filesystem\n  path: " yaml-path "\n"))
                        {:keys [dir err]} (build-lib! root yaml)
                        text (slurp (io/file dir "books/far/t/backlinks.json"))]
                    {:files (into (sorted-set) (keep #(get-in % ["position" "file"]))
                                  (get (json/read-str text) "edges"))
                     :text text :err err}))
        leaks? (fn [text] (or (str/includes? text (.getCanonicalPath root))
                              (str/includes? text (.getAbsolutePath root))))]
    (testing "a root that is a symbolic link"
      (symlink! (io/file root "link") real)
      (let [{:keys [files text err]} (file-of (str (io/file root "link")))]
        (is (= #{"far/sub/c.hdoc"} files) err)
        (is (not (leaks? text)) text)))
    (testing "a root below a symbolic link to its parent folder"
      (symlink! (io/file root "parent-link") (io/file root "real"))
      (let [{:keys [files text err]} (file-of (str (io/file root "parent-link/far")))]
        (is (= #{"far/sub/c.hdoc"} files) err)
        (is (not (leaks? text)) text)))
    (testing "a book reached through a symbolic link out of the root shows no absolute path"
      (symlink! (io/file real "escape.hdoc") (io/file root "outside/o.hdoc"))
      (let [{:keys [files text err]} (file-of (str real))]
        (is (not (leaks? text)) text)
        (is (every? #(not (str/starts-with? % "/")) files) err)
        (is (contains? files "far/sub/c.hdoc"))))
    (testing "site-position-file: a missing root, equal roots, nested roots"
      (let [path #(.toPath (io/file %))
            file (str (io/file real "sub/c.hdoc"))]
        (is (nil? (site/site-position-file {"gone" (path (io/file root "gone"))} "gone" file)))
        (is (= "b/sub/c.hdoc" (site/site-position-file {"a" (path real) "b" (path real)} "b" file)))
        (is (= "a/sub/c.hdoc" (site/site-position-file {"a" (path real) "b" (path real)} "x" file)))
        (is (= "in/c.hdoc" (site/site-position-file {"out" (path real) "in" (path (io/file real "sub"))}
                                                    "x" file)))
        (is (= "far/sub/c.hdoc"
               (site/site-position-file {"far" (path (.getCanonicalFile (io/file root "link")))} "far"
                                        (str (io/file root "link/sub/c.hdoc")))))))))

(deftest preview-failures-are-warnings-test
  (testing "a preview that cannot be built is a preview-failed warning; the others are written"
    (let [dir (temp-dir)
          real-cut site/cut-passage
          {:keys [status err]} (with-redefs [site/cut-passage
                                             (fn [blocks limit]
                                               (if (str/includes? (site/node-text blocks) "Porque, se vivemos")
                                                 (throw (ex-info "boom" {}))
                                                 (real-cut blocks limit)))]
                                 (build! dir))
          romanos (read-json (io/file dir "books/bible/blivre/romanos/previews.json"))]
      (is (= 1 status))
      (is (str/includes? err (str "warning: [preview-failed] the preview of bible:blivre/romanos#14:8"
                                  " was not written to previews.json: boom"))
          err)
      (is (str/ends-with? err "; 3 warnings in 4 books\n") err)
      (is (= ["8:28"] (map #(get % "passage") (get romanos "previews")))))))

;; --- AC #4: only changed bytes are written -----------------------------------------

(deftest unchanged-data-files-keep-their-mtime-test
  (let [dir (temp-dir)
        _ (build! dir)
        files (data-files dir)
        old (FileTime/fromMillis 1000000)
        mtime (fn [rel] (Files/getLastModifiedTime (.toPath (io/file dir rel)) (make-array LinkOption 0)))]
    (is (= 21 (count files)) "9 books' two files, the index and a page per library")
    (doseq [rel files]
      (Files/setLastModifiedTime (.toPath (io/file dir rel)) old))
    (testing "a rebuild with nothing changed rewrites no data file"
      (spit (io/file dir "books/bible/blivre/genesis/previews.json") "{\"edited\":true}\n")
      ;; the saved inputs made to differ: an incremental build (TASK-272)
      ;; trusts the files it wrote and would not even read this one, and
      ;; --full replaces them all (TASK-290); a full build from changed
      ;; inputs writes every file whose bytes differ
      (let [header (io/file (state/state-dir dir) state/header-name)
            _ (spit header (pr-str (assoc-in (edn/read-string (slurp header)) [:inputs "edited"] "digest")))
            {:keys [status err]} (build! dir)]
        (is (= 1 status) err)
        (is (str/includes? err "full build: the directory file, --extensions or --transform changed") err))
      (doseq [rel (remove #{"books/bible/blivre/genesis/previews.json"} files)]
        (is (= old (mtime rel)) rel)))
    (testing "and one whose bytes differ is written again"
      (is (not= old (mtime "books/bible/blivre/genesis/previews.json")))
      (is (str/includes? (slurp (io/file dir "books/bible/blivre/genesis/previews.json")) "3:1-24")))))

(deftest stale-data-files-are-swept-test
  (let [dir (temp-dir)
        _ (build! dir)
        stale [(write! dir "books/reformed/gone/backlinks.json" "{}")
               (write! dir "books/reformed/gone/previews.json" "{}")
               (write! dir "books/reformed/catkins/.previews.json.123456.tmp" "half")
               (write! dir "catalog/old/1.json" "{}")
               (write! dir "catalog/bible/7.json" "{}")
               (write! dir "catalog/bible/.2.json.99.tmp" "half")
               (write! dir "catalog/.index.json.42.tmp" "half")]
        kept [(write! dir "books/reformed/catkins/notes.txt" "mine")
              (write! dir "catalog/notes.txt" "mine")
              (write! dir "catalog/bible/cover.json" "mine")
              (write! dir "catalog/mine/index.json" "mine")]
        {:keys [err]} (build! dir)]
    (doseq [^File f stale]
      (is (not (.exists f)) (str f)))
    (is (not (.exists (io/file dir "books/reformed/gone"))) "the folder the stale files left empty goes")
    (is (not (.exists (io/file dir "catalog/old"))))
    (doseq [^File f kept]
      (is (= "mine" (slurp f)) (str f)))
    (is (not (str/includes? err "gone")) "data files alone are no page: nothing reported removed")
    (is (.isFile (io/file dir "catalog/bible/1.json")))))

;; --- cutting a passage --------------------------------------------------------------

(defn- para [& inlines] {:t :para :inlines (vec inlines)})
(defn- s [text] {:t :str :text text})
(def ^:private no-attr {:classes [] :props {}})

(deftest cut-passage-test
  (testing "a passage within the limit is whole and not cut"
    (is (= {:blocks [(para (s "abc"))] :cut? false} (site/cut-passage [(para (s "abc"))] 3))))
  (testing "a long string is cut at a word, with an ellipsis"
    (is (= {:blocks [(para (s "one two…"))] :cut? true}
           (site/cut-passage [(para (s "one two three"))] 9))))
  (testing "a word longer than the limit is cut inside it"
    (is (= [(para (s "abcd…"))] (:blocks (site/cut-passage [(para (s "abcdefgh"))] 4)))))
  (testing "never inside a surrogate pair"
    (is (= [(para (s "ab…"))] (:blocks (site/cut-passage [(para (s "ab😀cd"))] 3)))))
  (testing "a block that does not fit and cannot be cut is dropped, and what follows"
    (is (= {:blocks [(para (s "ab…"))] :cut? true}
           (site/cut-passage [(para (s "ab")) {:t :code-block :text "0123456789" :attr no-attr} (para (s "z"))] 6))))
  (testing "cut inside nested inlines, sections, quotes and lists"
    (is (= [(para {:t :strong :inlines [(s "one")]} (s "…"))]
           (:blocks (site/cut-passage [(para {:t :strong :inlines [(s "one two")]} (s " tail"))] 5))))
    (is (= [{:t :section :level 1 :heading [(s "H")] :attr no-attr :blocks [(para (s "one…"))]}]
           (:blocks (site/cut-passage [{:t :section :level 1 :heading [(s "H")] :attr no-attr
                                        :blocks [(para (s "one two"))]}] 5))))
    (is (= [{:t :list :ordered false :tight true :attr no-attr :items [[(para (s "aa…"))]]}]
           (:blocks (site/cut-passage [{:t :list :ordered false :tight true :attr no-attr
                                        :items [[(para (s "aa"))] [(para (s "bb bb"))] [(para (s "c"))]]}]
                                      3)))))
  (testing "maths counts its TeX and is never split"
    (is (= [(para (s "x…"))]
           (:blocks (site/cut-passage [(para (s "x ") {:t :math-inline :tex "a+b"})] 4))))))

(deftest cut-parsed-text-test
  (let [cut (fn [source limit] (site/cut-passage (:blocks (parser/parse source)) limit))
        text (fn [{:keys [blocks]}] (site/node-text blocks))]
    (testing "parsed prose (a :str per word, :space between) is cut before the word that does not fit"
      (is (= "alpha…" (text (cut "alpha beta gamma delta epsilon\n" 9))))
      (is (= "alpha beta…" (text (cut "alpha beta gamma delta epsilon\n" 10))))
      (is (= "alpha beta…" (text (cut "alpha beta gamma delta epsilon\n" 11)))
          "a cut on the space after a word still gets its ellipsis"))
    (testing "inside emphasis, the ellipsis follows the emphasised words"
      (let [{:keys [blocks cut?]} (cut "*uma palavra longa* depois\n" 12)]
        (is cut?)
        (is (= "uma palavra…" (site/node-text blocks)))
        (is (= "<p><em>uma palavra</em>…</p>"
               (site/page-body (html/emit-document {:meta {} :blocks blocks}))))))
    (testing "a single word longer than the limit is cut inside it, never inside a character"
      (is (= "abcd…" (text (cut "abcdefgh\n" 4))))
      (is (= "caf…" (text (site/cut-passage [(para (s (str "cafe" (char 0x301) "s")))] 4)))
          "the e keeps its accent or goes")
      (is (= "ab…" (text (site/cut-passage [(para (s (str "ab" (String. (Character/toChars 0x1F44D))
                                                          (char 0x200D) (char 0x2642) "x")))]
                                           4)))))
    (testing "any Unicode space is a break point"
      (is (= "um…" (text (site/cut-passage [(para (s (str "um" (char 0x2003) "dois tres")))] 5)))))))

(deftest cut-never-empty-test
  (let [big-table {:t :code-block :text (apply str (repeat 50 "x")) :attr no-attr}
        directive {:t :directive :name "note" :attr no-attr
                   :blocks [(para (s (str/join " " (repeat 10 "palavra"))))]}]
    (testing "a first block that cannot be cut and is over the limit is kept whole, with an ellipsis after"
      (is (= {:blocks [big-table (para (s "…"))] :cut? true}
             (site/cut-passage [big-table (para (s "after"))] 30)))
      (is (= [directive (para (s "…"))] (:blocks (site/cut-passage [directive] 30)))))
    (testing "under a heading, too: the heading alone is no preview"
      (is (= [{:t :section :level 1 :heading [(s "Heading")] :attr no-attr
               :blocks [directive (para (s "…"))]}]
             (:blocks (site/cut-passage [{:t :section :level 1 :heading [(s "Heading")] :attr no-attr
                                          :blocks [directive]}]
                                        30)))))
    (testing "after other content it is dropped"
      (is (= [(para (s "intro…"))] (:blocks (site/cut-passage [(para (s "intro")) directive] 10)))))))

(deftest whole-block-previews-are-capped-test
  (let [code (fn [n] {:t :code-block :text (apply str (repeat n "x")) :attr no-attr})
        section (fn [& blocks] {:t :section :level 1 :heading [(s "Heading")] :attr no-attr :blocks (vec blocks)})
        quote' (fn [& blocks] {:t :block-quote :blocks (vec blocks)})
        item-list (fn [& blocks] {:t :list :ordered false :tight true :attr no-attr :items [(vec blocks)]})
        cut (fn [blocks] (site/cut-passage blocks 10))]
    (testing "exactly four times the limit is kept whole, one more is not"
      (is (= {:blocks [(code 40) (para (s "…"))] :cut? true} (cut [(code 40)])))
      (is (= {:blocks [(para (s "…"))] :cut? true} (cut [(code 41)]))))
    (testing "without a heading the preview is the ellipsis alone: non-empty, cut, ending in one"
      (let [{:keys [blocks cut?]} (cut [(code 500) (para (s "after"))])]
        (is cut?)
        (is (= [(para (s "…"))] blocks))))
    (testing "with a heading it is the heading and the ellipsis"
      (is (= [(assoc (section) :heading [(s "Heading…")])] (:blocks (cut [(section (code 500))])))))
    (testing "an uncuttable block inside a quote or a list is capped the same way"
      (is (= [(quote' (code 40) (para (s "…")))] (:blocks (cut [(quote' (code 40))]))))
      (is (= [(para (s "…"))] (:blocks (cut [(quote' (code 41))]))))
      (is (= [(para (s "…"))] (:blocks (cut [(item-list (code 41))])))))
    (testing "a maths inline over the cap in a first word is dropped; one under it is kept"
      (is (= [(para {:t :math-inline :tex (apply str (repeat 40 "x"))} (s "…"))]
             (:blocks (cut [(para {:t :math-inline :tex (apply str (repeat 40 "x"))} (s " tail"))]))))
      (is (= [(para (s "…"))]
             (:blocks (cut [(para {:t :math-inline :tex (apply str (repeat 41 "x"))} (s " tail"))])))))
    (testing "a block that is not first is dropped as before, whatever its size"
      (is (= [(para (s "intro…"))] (:blocks (cut [(para (s "intro")) (code 20)])))))
    (testing "a huge block puts none of its text in the preview"
      (let [{:keys [blocks cut?]} (site/cut-passage [(code 200000)] site/preview-limit)]
        (is cut?)
        (is (< (count (site/node-text blocks)) 10))))))

(deftest preview-size-is-bounded-test
  (let [limit site/preview-limit
        size @#'site/serialized-size
        budget @#'site/size-budget
        big (apply str (repeat 300000 "a"))
        words (para (s (str/join " " (repeat 600 "palavra"))))
        cell (fn [t] {:blocks [(para (s t))]})
        table {:t :table :head {:cells [(cell "a") (cell "b")]}
               :rows (vec (repeat 3900 {:cells [(cell "c") (cell "d")]})) :caption [] :colspec [{} {}]
               :attr no-attr}
        section (fn [heading & blocks]
                  {:t :section :level 1 :heading [(s heading)] :attr no-attr :blocks (vec blocks)})
        link-para (fn [] (para {:t :link :target (str "http://x/" big) :inlines [(s "l")] :attr no-attr}))
        cases {"code block with a huge language" [{:t :code-block :lang big :text "a" :attr no-attr} words]
               "directive with a huge attribute" [{:t :directive :name "note" :blocks []
                                                   :attr {:classes [] :props {"k" big}}} words]
               "quote holding an image with a huge URL"
               [{:t :block-quote :blocks [(para {:t :image :src big :alt "i" :attr no-attr})]} words]
               "table of thousands of rows" [table words]
               "paragraph with a huge link URL" [(link-para) words]
               "huge heading" [(section big (para (s "x"))) words]
               "huge heading over an uncuttable block" [(section big {:t :code-block :text big :attr no-attr})]
               "many paragraphs each with a large URL" (vec (repeatedly 40 link-para))
               "huge URL in a short passage" [(link-para)]
               "huge footnote attribute" [(para (s "x") {:t :note :blocks [(para (s "n"))]
                                                         :attr {:classes [] :props {"k" big}}}
                                                (s (str/join " " (repeat 600 "palavra"))))]}]
    (doseq [[label blocks] cases
            :let [{kept :blocks cut? :cut?} (site/cut-passage blocks limit)]]
      (testing label
        (is (<= (size kept) (budget limit)) (str (size kept)))
        (is cut?)
        (is (seq kept))
        (is (str/ends-with? (site/node-text kept) "…") (site/node-text kept))
        (is (not (str/blank? (site/node-text kept))))))
    (testing "the short form is the heading and the ellipsis when the heading is small"
      (is (= [(assoc (section "Heading") :heading [(s "Heading…")])]
             (:blocks (site/cut-passage [(section "Heading" (link-para)) words] limit)))))
    (testing "otherwise it is the ellipsis alone"
      (is (= [(para (s "…"))] (:blocks (site/cut-passage [(link-para)] limit)))))
    (testing "an ordinary preview is far below the budget and is untouched"
      (let [blocks [(section "Heading" (para (s (str/join " " (repeat 600 "palavra")))))]
            {kept :blocks} (site/cut-passage blocks limit)]
        (is (< (* 4 (size kept)) (budget limit)))
        (is (= kept (:blocks (#'site/cut-passage* blocks limit))))))))

(deftest footnotes-count-after-the-body-test
  (let [note (fn [text] {:t :note :blocks [(para (s text))]})
        long-note (str/join " " (repeat 400 "nota"))]
    (testing "a long footnote does not end the preview at its marker"
      (let [{:keys [blocks cut?]} (site/cut-passage [(para (s "short") (note long-note) (s " after."))] 30)
            [p] blocks]
        (is cut?)
        (is (= [(s "short") (s " after.…")] (remove #(= :note (:t %)) (:inlines p)))
            "the whole body is kept, and ends with the ellipsis a cut preview always has")
        (is (= "nota nota nota…" (site/node-text (:blocks (second (:inlines p)))))
            "the footnote gets what is left, cut with an ellipsis")))
    (testing "a footnote nothing is left for is dropped, marker and all"
      (let [{:keys [blocks]} (site/cut-passage [(para (s "0123456789") (note "abc") (note long-note))] 13)]
        (is (= [(s "0123456789") (note "abc") (s "…")] (:inlines (first blocks))))))
    (testing "the body cut first; footnotes of the kept part after it"
      (let [{:keys [blocks]} (site/cut-passage [(para (s "one") (note "n1") (s " two three"))] 9)]
        (is (= "onen1 two…" (site/node-text blocks))))
      (let [{:keys [blocks]} (site/cut-passage [(para (s "one") (note "n1") (s " two three"))] 7)]
        (is (= "one two…" (site/node-text blocks)) "nothing left for the footnote: it goes")))))

;; seeded fuzz over the node kinds the cut handles, seed = task number

(def ^:private words ["a" "be" "três" "naïve" "😀x" "longerword" "é" "Ω" "word"
                      (str "cafe" (char 0x301)) (str "um" (char 0x2003) "dois")])

(defn- gen-inline [^java.util.Random r depth]
  (let [k (.nextInt r (if (pos? depth) 9 4))]
    (case k
      7 {:t :space}
      8 {:t :note :blocks [{:t :para :inlines (vec (repeatedly (inc (.nextInt r 3)) #(gen-inline r 0)))}]}
      0 (s (str/join (repeatedly (inc (.nextInt r 6)) #(str (nth words (.nextInt r (count words)))
                                                            (if (.nextBoolean r) " " "")))))
      1 {:t :space}
      2 {:t :code :text (nth words (.nextInt r (count words)))}
      3 {:t :math-inline :tex "x^2"}
      4 {:t :strong :inlines (vec (repeatedly (inc (.nextInt r 3)) #(gen-inline r (dec depth))))}
      5 {:t :emph :inlines (vec (repeatedly (inc (.nextInt r 3)) #(gen-inline r (dec depth))))}
      {:t :span :attr no-attr :inlines (vec (repeatedly (inc (.nextInt r 3)) #(gen-inline r (dec depth))))})))

(defn- gen-block [^java.util.Random r depth]
  (let [k (.nextInt r (if (pos? depth) 7 3))
        inlines #(vec (repeatedly (inc (.nextInt r 4)) (fn [] (gen-inline r 2))))
        blocks #(vec (repeatedly (inc (.nextInt r 3)) (fn [] (gen-block r (dec depth)))))]
    (case k
      0 {:t :para :inlines (inlines)}
      1 {:t :code-block :text "code text" :attr no-attr}
      2 {:t :math-block :tex "\\int x" :attr no-attr}
      3 {:t :section :level (inc (.nextInt r 3)) :heading (inlines) :attr no-attr :blocks (blocks)}
      4 {:t :block-quote :blocks (blocks)}
      5 {:t :list :ordered (.nextBoolean r) :tight true :attr no-attr
         :items (vec (repeatedly (inc (.nextInt r 3)) blocks))}
      {:t :para :inlines (inlines)})))

(defn- body-text
  "`site/node-text` without what notes hold."
  [x]
  (site/node-text (walk/postwalk #(if (and (map? %) (= :note (:t %))) {:t :note :blocks []} %) x)))

(defn- has-uncuttable?
  "True when `x` holds a node a cut keeps whole or drops: code, maths."
  [x]
  (boolean (some #(and (map? %) (contains? #{:code-block :math-block :code :math-inline} (:t %)))
                 (tree-seq coll? seq x))))

(deftest cut-passage-fuzz-test
  (let [r (java.util.Random. 271)
        stats (atom {:cases 0 :cut 0 :mid-text 0 :nested 0 :notes-cut 0})]
    (dotimes [_ 600]
      (let [blocks (vec (repeatedly (inc (.nextInt r 4)) #(gen-block r 2)))
            total (count (site/node-text blocks))
            limit (.nextInt r (+ 2 total))
            {kept :blocks cut? :cut?} (site/cut-passage blocks limit)
            text (site/node-text kept)
            body (body-text kept)
            stripped (if (str/ends-with? body "…") (subs body 0 (dec (count body))) body)
            emitted (html/emit-document {:meta {} :blocks kept})]
        (swap! stats update :cases inc)
        (is (= (> total limit) cut?) (pr-str limit blocks))
        (when-not cut? (is (= blocks kept)))
        (when cut?
          (swap! stats update :cut inc)
          (when (and (str/ends-with? body "…") (re-find #"[^\s…]…$" body)) (swap! stats update :mid-text inc))
          (when (some #(contains? #{:section :block-quote :list} (:t %)) kept)
            (swap! stats update :nested inc))
          (when (= (str/trimr (body-text blocks)) (str/replace body #"…$" ""))
            (swap! stats update :notes-cut inc))
          (is (str/includes? text "…") (str "a cut is always marked: " (pr-str limit kept))))
        (is (or (<= (count body) (inc limit)) (has-uncuttable? kept)) (pr-str limit body))
        (is (or (<= (count text) (+ 2 limit)) (has-uncuttable? kept)) (pr-str limit text))
        (is (str/starts-with? (body-text blocks) stripped) (pr-str limit body))
        (when (and (<= 10 limit) (not (str/blank? (body-text blocks))))
          (is (not (str/blank? (str/replace stripped "…" ""))) (str "never empty: " (pr-str limit kept))))
        (is (ast/valid? ast/Document {:meta {} :blocks kept}) (pr-str limit kept))
        (is (balanced? emitted) emitted)
        (is (not (re-find #"[\uD800-\uDBFF](?![\uDC00-\uDFFF])" text)) "no lone high surrogate")
        (is (not (re-find #"\p{M}" (str/replace text #"[\p{L}]\p{M}" ""))) "no mark without its letter")))
    (testing "not vacuous"
      (is (= 600 (:cases @stats)))
      (is (< 200 (:cut @stats)) (pr-str @stats))
      (is (< 50 (:mid-text @stats)) (pr-str @stats))
      (is (< 30 (:nested @stats)) (pr-str @stats))
      (is (< 5 (:notes-cut @stats)) (pr-str @stats)))))

(deftest node-text-test
  (is (= "Gênesis, Seção 2" (site/node-text [(s "Gênesis") (s ", Seção 2")])))
  (is (= "a b x^2 Sec 3 alt" (site/node-text [(s "a") {:t :space} (s "b ") {:t :math-inline :tex "x^2"}
                                              {:t :space} {:t :cross-ref :label "sec:x" :text "Sec 3"}
                                              {:t :space} {:t :image :src "a.png" :alt "alt" :attr no-attr}])))
  (is (= "Hbody" (site/node-text {:t :section :level 1 :heading [(s "H")] :attr no-attr
                                  :blocks [(para (s "body"))]}))))

(deftest catalog-files-test
  (testing "pages of catalog-page-size, the same bytes whatever the order the books come in"
    (let [books (for [i (range 1203)]
                  {:id {:library "lib" :shelf (if (even? i) ["b"] []) :slug (format "s%04d" i)}
                   :title (nth ["Êxodo" "exodo" "Zacarias" "ana" "Ana"] (mod i 5))
                   :authors []
                   :page (format "books/lib/s%04d/index.html" i)})
          files (site/catalog-files books ["lib" "empty"])
          shuffled (site/catalog-files (shuffle books) ["empty" "lib"])]
      (is (= (keys files) (keys shuffled)))
      (is (every? (fn [k] (java.util.Arrays/equals ^bytes (get files k) ^bytes (get shuffled k))) (keys files)))
      (is (= ["catalog/index.json" "catalog/lib/1.json" "catalog/lib/2.json" "catalog/lib/3.json"] (keys files)))
      (let [index (json/read-str (String. ^bytes (get files "catalog/index.json") "UTF-8"))
            page1 (json/read-str (String. ^bytes (get files "catalog/lib/1.json") "UTF-8"))
            page3 (json/read-str (String. ^bytes (get files "catalog/lib/3.json") "UTF-8"))]
        (is (= [["empty" 0 0] ["lib" 1203 3]]
               (map (juxt #(get % "name") #(get % "books") #(get % "pages")) (get index "libraries"))))
        (is (= 500 (count (get page1 "books"))))
        (is (= 203 (count (get page3 "books"))))
        (is (= ["" "Ana" "ana"] [(get-in page1 ["books" 0 "shelf"])
                                 (get-in page1 ["books" 0 "title"]) (get-in page1 ["books" 200 "title"])])
            "no shelf first; Ana before ana (ties broken by the text itself, code point order)")
        (is (= ["Ana" "ana" "exodo" "Êxodo" "Zacarias"]
               (distinct (map #(get % "title") (get page1 "books"))))
            "accents and case do not decide the order")))))
