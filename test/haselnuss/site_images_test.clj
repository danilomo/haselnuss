(ns haselnuss.site-images-test
  "The images a book shows in a `--build-site` site (TASK-270; doc-12,
  \"Output folder\" and \"Left to the builder\"): each relative image is
  copied from the book's folder to the same relative path below the
  book's page, the page's `src` points at the copy, and nothing outside
  the book's folder is read for it nor anything outside the page's folder
  written. A URL is left as it is; any other src is dropped to its alt
  text with a warning. The saved state records each copied image's
  digest (`site/changed-image-books`, for TASK-272), and the next build
  removes the copies no page uses any more."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.book-ref :as book-ref]
            [haselnuss.cli :as cli]
            [haselnuss.library.state :as state]
            [haselnuss.site :as site])
  (:import (java.io File)
           (java.net URI)
           (java.nio.file Files Path)
           (java.nio.file.attribute FileAttribute)
           (java.util Arrays Random)))

;; --- helpers -----------------------------------------------------------------------

(defn- temp-dir ^File []
  (.toFile (Files/createTempDirectory "haselnuss-site-images" (make-array FileAttribute 0))))

(defn- write! ^File [^File dir rel content]
  (let [f (io/file dir rel)]
    (io/make-parents f)
    (if (string? content)
      (spit f content)
      (Files/write (.toPath f) ^bytes content
                   ^"[Ljava.nio.file.OpenOption;" (make-array java.nio.file.OpenOption 0)))
    f))

(defn- link! [^File link ^File target]
  (io/make-parents link)
  (Files/createSymbolicLink (.toPath link) (.toPath target) (make-array FileAttribute 0)))

(defn- build!
  "Build the libraries of `yaml` into the site `dir`."
  [yaml dir]
  (let [err (java.io.StringWriter.)
        status (binding [*err* err *out* (java.io.StringWriter.)]
                 (cli/main* ["--libraries" (str yaml) "--build-site" (str dir)]
                            {:library-env {} :library-home "/nonexistent/haselnuss-test-home"}))]
    {:status status :err (str err)}))

(defn- files-under
  "The `/`-separated paths of the regular files below `dir`, sorted."
  [^File dir]
  (let [root (.toPath dir)]
    (->> (file-seq dir)
         (filter #(.isFile ^File %))
         (map #(str/replace (str (.relativize root (.toPath ^File %))) File/separator "/"))
         sort
         vec)))

(defn- page-files
  "`files-under` the site `dir`'s `books/`, without each page's data files
  (`backlinks.json`, `previews.json`; TASK-271): the pages and images."
  [^File dir]
  (filterv #(and (str/starts-with? % "books/") (not (re-find #"/(?:backlinks|previews)\.json$" %)))
           (files-under dir)))

(defn- unescape [s]
  (-> s
      (str/replace "&quot;" "\"") (str/replace "&#39;" "'")
      (str/replace "&lt;" "<") (str/replace "&gt;" ">") (str/replace "&amp;" "&")))

(defn- img-srcs [html]
  (mapv (comp unescape second) (re-seq #"<img src=\"([^\"]*)\"" html)))

(defn- served-file
  "The file of the site `dir` a browser fetches for `src` on the page
  `page` (a site path), as the server maps a URL path to the folder; nil
  when `src` is not a path into the site."
  ^File [^File dir page src]
  (let [base (URI. "http" "site.test" (str "/" page) nil)
        resolved (.resolve base (URI. src))]
    (when (and (= "site.test" (.getHost resolved)) (str/starts-with? (.getPath resolved) "/"))
      (io/file dir (subs (.getPath resolved) 1)))))

(defn- bytes= [^File a ^File b]
  (Arrays/equals (Files/readAllBytes (.toPath a)) (Files/readAllBytes (.toPath b))))

;; --- AC #1: the fixture book's image, served from the site ---------------------------

(deftest fixture-image-is-served-test
  (let [dir (io/file (temp-dir) "site")
        {:keys [status err]} (build! "test/fixtures/site/libraries.yaml" dir)
        page "books/reformed/catkins/index.html"
        html (slurp (io/file dir page))
        [src :as srcs] (img-srcs html)
        source (io/file "test/fixtures/site/reformed/catkins/catkins.png")]
    (is (= 1 status) err)
    (is (not (str/includes? err "Dropped the image")) err)
    (testing "the page's <img src> is a relative path the site serves as the copied image"
      (is (= ["catkins.png"] srcs))
      (let [served (served-file dir page src)]
        (is (= (io/file dir "books/reformed/catkins/catkins.png") served))
        (is (.isFile served))
        (is (bytes= source served) "the image's own bytes")))
    (testing "the saved state records it with its digest"
      (let [saved (:books (state/load-state dir))]
        (is (= {"books/reformed/catkins/catkins.png"
                {:dir (.getCanonicalPath (.getParentFile source))
                 :path "catkins.png"
                 :digest (:digest (state/file-stamp source))}}
               (:images (get saved (:id (book-ref/parse-book-ref "reformed:catkins"))))))
        (is (= [] (site/changed-image-books saved)) "nothing changed since")))))

;; --- a library that tries every way out -----------------------------------------------

(def ^:private png-a (.getBytes "not really a png: ok" "UTF-8"))
(def ^:private png-b (.getBytes "upper case A" "UTF-8"))
(def ^:private secret (.getBytes "SECRET outside the book" "UTF-8"))

(defn- lab!
  "A library `lab` under a fresh folder: `pics/book.hdoc`, a folder book
  whose images try every way out of its folder; `plain.hdoc`, a single-
  file book showing an image of the folder it sits in; and a generated
  shelf `gen` whose book shows images. `outside/` holds files no book may
  read. Answers `{:root :yaml :outside :site}`."
  []
  (let [root (temp-dir)
        lab (io/file root "lab")
        outside (io/file root "outside")
        secret-file (write! outside "secret.png" secret)]
    (write! lab "pics/sub/ok.png" png-a)
    (write! lab "pics/A.png" png-b)
    (write! lab "pics/a.png" png-a)
    (write! lab "pics/notes.txt" "not an image")
    (write! lab "pics/.hidden.png" png-a)
    (link! (io/file lab "pics/link.png") secret-file)
    (link! (io/file lab "pics/linkdir") outside)
    (link! (io/file lab "pics/inner.png") (io/file lab "pics/sub/ok.png"))
    (write! lab "pics/book.hdoc"
            (str "---\ntitle: Pics\n---\n\n"
                 "![ok](sub/ok.png) ![dot](./sub/ok.png) ![enc](sub/%6Fk.png) ![query](sub/ok.png?v=1)\n"
                 "![up](../secret.png) ![abs](" (.getCanonicalPath secret-file) ")"
                 " ![encoded up](%2e%2e/%2E%2E/outside/secret.png) ![link](link.png)"
                 " ![linkdir](linkdir/secret.png)\n"
                 "![inner](inner.png) ![txt](notes.txt) ![hidden](.hidden.png)"
                 " ![url](https://example.org/x.png) ![A](A.png) ![a](a.png) ![missing](missing.png)\n"))
    (write! lab "plain.hdoc" "---\ntitle: Plain\n---\n\n![p](pics/sub/ok.png)\n")
    (write! lab "gen/x.png" png-a)
    (write! lab "gen/shelf.clj"
            (str "{:data []\n"
                 " :books (fn [_] [{:slug \"g\" :title \"G\"}])\n"
                 " :document (fn [_ _] {:meta {:title [{:t :str :text \"G\"}]}\n"
                 "   :blocks [{:t :para :inlines [{:t :image :src \"x.png\" :alt \"gx\" :attr {:classes [] :props {}}}\n"
                 "                                {:t :image :src \"../pics/sub/ok.png\" :alt \"gup\" :attr {:classes [] :props {}}}\n"
                 "                                {:t :image :src \"https://example.org/g.png\" :alt \"gurl\" :attr {:classes [] :props {}}}]}]})}\n"))
    {:root root
     :outside outside
     :site (io/file root "site")
     :yaml (write! root "libraries.yaml"
                   (str "- name: lab\n  type: filesystem\n  path: " (.getCanonicalPath lab)
                        "\n  scripts: true\n"))}))

(deftest image-paths-cannot-escape-test
  (let [{:keys [yaml site outside]} (lab!)
        outside-before (files-under outside)
        {:keys [status err]} (build! yaml site)
        html (slurp (io/file site "books/lab/pics/index.html"))]
    (is (= 1 status) err)
    (testing "only the images inside the book's folder are copied, below the page's folder"
      (is (= ["books/lab/gen/g/index.html"
              "books/lab/pics/A.png"
              "books/lab/pics/index.html"
              "books/lab/pics/inner.png"
              "books/lab/pics/sub/ok.png"
              "books/lab/plain/index.html"
              "books/lab/plain/pics/sub/ok.png"]
             (page-files site)))
      (is (bytes= (io/file site "books/lab/pics/sub/ok.png") (io/file site "books/lab/pics/inner.png"))
          "a link to a file inside the folder is copied as that file")
      (is (= outside-before (files-under outside)) "nothing written outside the site")
      (is (not-any? #(str/includes? (slurp %) "SECRET") (filter #(.isFile ^File %) (file-seq site)))
          "nothing read from outside the book's folder"))
    (testing "each src points at its copy, or is a URL left as it is"
      (is (= ["sub/ok.png" "sub/ok.png" "sub/ok.png" "sub/ok.png?v=1" "inner.png"
              "https://example.org/x.png" "A.png"]
             (img-srcs html)))
      (doseq [src (img-srcs html)
              :let [served (served-file site "books/lab/pics/index.html" src)]
              :when served]
        (is (.isFile served) src)
        (is (str/starts-with? (.getPath served) (.getPath (io/file site "books/lab/pics/"))) src)))
    (testing "every other image is dropped to its alt text, with a warning naming it"
      (is (str/includes? (str/replace html #"<img [^>]*/>" "|")
                         "<p>| | | |\nup abs encoded up link linkdir\n| txt hidden | | a missing</p>"))
      (doseq [[src why] [["../secret.png" "(`..`)"]
                         ["%2e%2e/%2E%2E/outside/secret.png" "(`..`)"]
                         ["link.png" "a symbolic link"]
                         ["linkdir/secret.png" "a symbolic link"]
                         ["notes.txt" "not an image"]
                         [".hidden.png" "hidden"]
                         ["a.png" "differs only in case from \"A.png\""]
                         ["missing.png" "no such file"]]]
        (is (str/includes? err (str "Dropped the image \"" src "\" from the site page: ")) src)
        (is (re-find (re-pattern (str "Dropped the image \"" (java.util.regex.Pattern/quote src)
                                      "\"[^\n]*" (java.util.regex.Pattern/quote why)))
                     err)
            src))
      (is (re-find #"Dropped the image \"/[^\"]*secret.png\" from the site page: it is an absolute path" err)))
    (testing "a generated book has no folder: its relative images are dropped, its URLs kept"
      (let [g (slurp (io/file site "books/lab/gen/g/index.html"))]
        (is (= ["https://example.org/g.png"] (img-srcs g)))
        (is (str/includes? g "gx"))
        (is (str/includes? g "gup"))
        (is (str/includes? err "Dropped the image \"x.png\" from the site page: the book has no folder"))
        (is (str/includes? err "Dropped the image \"../pics/sub/ok.png\" from the site page: it leaves"))
        (is (not (.exists (io/file site "books/lab/gen/g/x.png"))))))
    (testing "the plain html build of the book is unchanged: every src as written"
      (let [out (io/file (temp-dir) "pics.html")]
        (cli/build {:libraries (str yaml) :input "lab:pics" :target "html" :output (str out)
                    :library-env {} :library-home "/nonexistent/haselnuss-test-home"})
        (is (some #{"../secret.png"} (img-srcs (slurp out))))
        (is (= 16 (count (img-srcs (slurp out)))))))))

;; --- the sweep and the saved digests ---------------------------------------------------

(deftest stale-images-are-swept-test
  (let [{:keys [yaml site root]} (lab!)
        _ (build! yaml site)
        mine (write! site "books/lab/pics/mine.png" "put here by hand")
        temp (write! site "books/lab/pics/.inner.png.8123456789.tmp" "half an image")]
    (write! root "lab/pics/book.hdoc" "---\ntitle: Pics\n---\n\n![inner](inner.png)\n")
    (let [{:keys [status err]} (build! yaml site)]
      (is (= 1 status) "the generated book's dropped images are still warnings")
      (is (not (str/includes? err "Dropped the image \"inner.png\"")) err))
    (is (= ["books/lab/gen/g/index.html"
            "books/lab/pics/index.html"
            "books/lab/pics/inner.png"
            "books/lab/pics/mine.png"
            "books/lab/plain/index.html"
            "books/lab/plain/pics/sub/ok.png"]
           (page-files site))
        "the copies no page shows any more are gone, with the folder they leave empty")
    (is (= "put here by hand" (slurp mine)) "an image no build copied is kept")
    (is (not (.exists temp)) "a copy's leftover temporary file goes")))

(deftest images-manifest-test
  (let [{:keys [yaml site root]} (lab!)]
    (build! yaml site)
    (testing "the manifest lists this build's copies, and no pending list is left"
      (is (= ["books/lab/pics/A.png" "books/lab/pics/inner.png" "books/lab/pics/sub/ok.png"
              "books/lab/plain/pics/sub/ok.png"]
             (str/split-lines (slurp (io/file site ".haselnuss/images")))))
      (is (not (.exists (io/file site ".haselnuss/images.pending")))))
    (testing "copies are swept even when the saved state is unusable (another format, or none)"
      (doseq [^File f (.listFiles (io/file site ".haselnuss/state"))] (.delete f))
      (is (not (:usable? (state/load-state site))))
      (write! root "lab/pics/book.hdoc" "---\ntitle: Pics\n---\n\n![inner](inner.png)\n")
      (build! yaml site)
      (is (not (.exists (io/file site "books/lab/pics/A.png"))))
      (is (not (.exists (io/file site "books/lab/pics/sub/ok.png"))))
      (is (.isFile (io/file site "books/lab/pics/inner.png"))))
    (testing "a killed build's copies, noted in the pending list, are swept by the next one"
      (let [orphan (write! site "books/lab/pics/orphan.png" "copied, then killed")
            mine (write! site "books/lab/pics/mine.png" "put here by hand")]
        (spit (io/file site ".haselnuss/images.pending") "books/lab/pics/orphan.png\n")
        (build! yaml site)
        (is (not (.exists orphan)))
        (is (= "put here by hand" (slurp mine)))))))

(deftest stale-image-differing-only-in-case-test
  (let [site (temp-dir)
        page "books/l/b/index.html"
        _ (write! site page "a page")
        fresh (write! site "books/l/b/a.png" "fresh copy")
        sweep #(site/sweep! site #{page} {:images #{"books/l/b/a.png"} :previous-images ["books/l/b/A.png"]})]
    (testing "renamed A.png -> a.png on a case-insensitive file system: the stale path IS the
              fresh copy (simulated by a hard link), so it is not deleted"
      (Files/createLink (.toPath (io/file site "books/l/b/A.png")) (.toPath fresh))
      (sweep)
      (is (= "fresh copy" (slurp fresh)))
      (is (.isFile (io/file site "books/l/b/A.png"))))
    (testing "on a case-sensitive one they are two files, and the stale one goes"
      (.delete (io/file site "books/l/b/A.png"))
      (write! site "books/l/b/A.png" "stale copy")
      (sweep)
      (is (not (.exists (io/file site "books/l/b/A.png"))))
      (is (= "fresh copy" (slurp fresh))))))

(deftest stale-images-with-a-tampered-manifest-test
  (testing "a path out of the manifest that could not be a copy is never deleted"
    (let [{:keys [yaml site root]} (lab!)
          _ (build! yaml site)
          keep-me (write! root "keep.png" "outside the site")
          inside (write! site "readers/x.png" "a reader's file")
          elsewhere (temp-dir)
          victim (write! elsewhere "v.png" "behind a link")]
      (link! (io/file site "books/lab/linked") elsewhere)
      (spit (io/file site ".haselnuss/images")
            (str/join "\n" ["books/../../keep.png" "readers/x.png" (.getPath keep-me)
                            "books/lab/pics/index.html" "books/lab/linked/v.png"
                            "books/lab/./pics/inner.png" "garbage \u0000 line" ""]))
      (build! yaml site)
      (is (.isFile keep-me))
      (is (.isFile inside))
      (is (.isFile victim) "never deleted through a symbolic link")
      (is (.isFile (io/file site "books/lab/pics/index.html")))
      (is (.isFile (io/file site "books/lab/pics/inner.png")))))
  (testing "an unreadable manifest (a folder, a symbolic link) reads as empty"
    (let [{:keys [yaml site]} (lab!)
          _ (build! yaml site)
          elsewhere (temp-dir)
          listed (write! elsewhere "list" "books/lab/pics/A.png\n")]
      (.delete (io/file site ".haselnuss/images"))
      (link! (io/file site ".haselnuss/images.pending") listed)
      (is (= #{} (site/read-image-manifest site)))
      (.delete (io/file site ".haselnuss/images.pending"))
      (.mkdirs (io/file site ".haselnuss/images"))
      (is (= #{} (site/read-image-manifest site))))))

;; --- one site path, one book ---------------------------------------------------------------

(deftest image-in-another-books-page-folder-test
  (testing (str "s.hdoc showing x/fig.png would copy it into the page folder of the book s/x;"
                " it is dropped, whatever the thread count, and s/x shows its own fig.png")
    (let [root (temp-dir)
          lib (io/file root "lib")
          yaml (write! root "libraries.yaml"
                       (str "- name: lib\n  type: filesystem\n  path: " (.getCanonicalPath lib) "\n"))]
      (write! lib "s.hdoc" "---\ntitle: S\n---\n\n![red](x/fig.png) ![upper](X/fig.png) ![own](own.png)\n")
      (write! lib "x/fig.png" "RED")
      ;; books/lib/s/X/ is books/lib/s/x/ on a case-insensitive file system
      (write! lib "X/fig.png" "UPPER")
      (write! lib "own.png" "OWN")
      (write! lib "s/x.hdoc" "---\ntitle: X\n---\n\n![blue](fig.png)\n")
      (write! lib "s/fig.png" "BLUE")
      (let [runs (for [threads [1 2 8]]
                   (let [site (io/file (temp-dir) "site")
                         err (java.io.StringWriter.)
                         status (binding [*err* err *out* (java.io.StringWriter.)]
                                  (cli/main* ["--libraries" (str yaml) "--build-site" (str site)]
                                             {:library-env {} :library-home "/nonexistent/haselnuss-test-home"
                                              :haselnuss.cli/site-threads threads}))]
                     {:status status :err (str err) :site site
                      :files (into {} (for [rel (files-under site)
                                            :when (str/starts-with? rel "books/")]
                                        [rel (slurp (io/file site rel))]))}))]
        (doseq [{:keys [status err site files]} runs]
          (is (= 1 status) err)
          (is (str/includes? err (str "Dropped the image \"x/fig.png\" from the site page: its copy would"
                                      " land in another book's page folder"))
              err)
          (is (= "BLUE" (get files "books/lib/s/x/fig.png")))
          (is (= "OWN" (get files "books/lib/s/own.png")) "not vacuous: s copies what is its own")
          (is (str/includes? err (str "Dropped the image \"X/fig.png\" from the site page: its copy would"
                                      " land in another book's page folder"))
              err)
          (is (not (contains? files "books/lib/s/X/fig.png")))
          (is (str/includes? (slurp (io/file site "books/lib/s/index.html")) "red"))
          (is (not (str/includes? err "image-conflict"))))
        (is (apply = (map :files runs)))))))

(deftest image-conflicts-test
  (let [a {:library "l" :shelf [] :slug "a"}
        b {:library "l" :shelf [] :slug "b"}]
    (is (= [] (site/image-conflicts [{:id a :images {"books/l/a/x.png" {}}}
                                     {:id b :images {"books/l/b/x.png" {}}}])))
    (is (= [["l:a" "the image books/l/x.png is copied by l:a and l:b; the site shows one of them"]
            ["l:b" "the image books/l/x.png is copied by l:a and l:b; the site shows one of them"]]
           (map (juxt :book :message)
                (site/image-conflicts [{:id b :images {"books/l/x.png" {}}}
                                       {:id a :images {"books/l/x.png" {}}}]))))
    (is (= ["l:a" "l:b"]
           (map :book (site/image-conflicts [{:id a :images {"books/l/X/f.png" {}}}
                                             {:id b :images {"books/l/x/F.png" {}}}])))
        "paths that differ only in case are one file on a case-insensitive file system")
    (is (every? #(= :error (:level %)) (site/image-conflicts [{:id b :images {"p.png" {}}}
                                                              {:id a :images {"p.png" {}}}])))))

;; --- size -----------------------------------------------------------------------------------

(deftest oversized-image-test
  (testing "an image above max-image-bytes is dropped with a warning, never read"
    (let [{:keys [yaml site root]} (lab!)
          big (io/file root "lab/plain-big.png")]
      (with-open [f (java.io.RandomAccessFile. big "rw")]
        (.setLength f (inc site/max-image-bytes)))
      (write! root "lab/plain.hdoc" "---\ntitle: Plain\n---\n\n![big](plain-big.png) ![p](pics/sub/ok.png)\n")
      (let [{:keys [status err]} (build! yaml site)]
        (is (= 1 status))
        (is (str/includes? err "Dropped the image \"plain-big.png\" from the site page: it is larger than 50 MiB")
            err)
        (is (not (.exists (io/file site "books/lab/plain/plain-big.png"))))
        (is (.isFile (io/file site "books/lab/plain/pics/sub/ok.png"))))))
  (testing "a copy is streamed with its digest, and stops at the limit if the file grew"
    (let [dir (temp-dir)
          src (write! dir "a.png" png-a)
          out (.toPath (io/file dir "out.png"))]
      (is (= (:digest (state/file-stamp src)) (site/copy-image! (.toPath src) out)))
      (is (bytes= src (.toFile out)))
      (with-redefs [site/max-image-bytes 5]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"grew past 5 bytes"
                              (site/copy-image! (.toPath src) out)))
        (is (nil? (site/image-digest dir ["a.png"])) "and is not digested")))))

(deftest changed-image-books-test
  (let [{:keys [yaml site root]} (lab!)
        _ (build! yaml site)
        saved (:books (state/load-state site))
        pics (:id (book-ref/parse-book-ref "lab:pics"))
        plain (:id (book-ref/parse-book-ref "lab:plain"))]
    (is (= [] (site/changed-image-books saved)) "nothing changed since the build")
    (is (= 3 (count (:images (get saved pics)))) "not vacuous")
    (testing "editing only an image names exactly the books that show it"
      (write! root "lab/pics/sub/ok.png" "edited")
      (is (= [pics plain] (site/changed-image-books saved)))
      (write! root "lab/pics/sub/ok.png" png-a)
      (is (= [] (site/changed-image-books saved)) "and none once it is back"))
    (testing "an image that is gone, or replaced by a link out of the folder"
      (.delete (io/file root "lab/pics/A.png"))
      (is (= [pics] (site/changed-image-books saved)))
      (link! (io/file root "lab/pics/A.png") (io/file root "outside/secret.png"))
      (is (= [pics] (site/changed-image-books saved))))
    (testing "a saved path that could not have been copied counts as changed, and is not read"
      (is (= [pics] (site/changed-image-books
                     {pics {:images {"x" {:dir (str root) :path "../outside/secret.png"
                                          :digest (:digest (state/file-stamp (io/file root "outside/secret.png")))}}}}))))))

;; --- image-src --------------------------------------------------------------------------

(deftest image-src-test
  (testing "URLs, fragments and blanks are left as they are"
    (doseq [src ["https://example.org/a.png" "http://x/y.png" "data:image/png;base64,AA" "mailto:a@b"
                 "//cdn.example.org/a.png" "#top" "" "  "
                 ;; read as a browser and the sanitizer read a scheme
                 " https://x/a.png" "\t\nhttps://x/a.png" "ht\ttps://x/a.png" "\u0001//x/a.png"]]
      (is (= {:as-is true} (site/image-src src)) (pr-str src))))
  (testing "a relative path to a web image, decoded, without . and empty segments"
    (is (= {:path ["catkins.png"] :suffix ""} (site/image-src "catkins.png")))
    (is (= {:path ["catkins.png"] :suffix ""} (site/image-src "  \tcatkins.png")) "leading space stripped")
    (is (= {:path ["a" "b c.GIF"] :suffix "#x"} (site/image-src "./a//b%20c.GIF#x")))
    (is (= {:path ["ç.webp"] :suffix "?v=2"} (site/image-src "%C3%A7.webp?v=2"))))
  (testing "everything else is refused"
    (doseq [[src why] [["../a.png" "`..`"] ["a/../../b.png" "`..`"] ["%2e%2e/a.png" "`..`"]
                       ["/etc/a.png" "absolute"] ["%2Fetc/a.png" "absolute"] [" /etc/a.png" "absolute"]
                       ["C:/a.png" "one-letter scheme or a drive letter"]
                       ["a:b.png" "one-letter scheme"] ["x.svg" "not an image"] ["x.SVG" "not an image"]
                       ["a\\b.png" "backslash"] ["a%5Cb.png" "backslash"]
                       ["\\\\server\\a.png" "backslash"] ["a\tb.png" "control"] ["a%00.png" "control"]
                       [".git/x.png" "hidden"] ["a/.b.png" "hidden"] ["book.hdoc" "not an image"]
                       ["a.png.txt" "not an image"] ["png" "not an image"] ["./" "no file"]]]
      (is (str/includes? (:refused (site/image-src src) "") why) src))))

(def ^:private fuzz-pieces
  ["ok.png" "sub" "sub/ok.png" "." ".." "%2e" "%2E%2e" "%2f" "%2F" "/" "//" "\\" "%5c" "a" "b.PNG"
   ".hidden.png" "C:" "c:" "http:" "data:" "#" "?" "%00" "\t" " " "%20" "x.svg" "A.png" "~" ":"
   "link.png" "linkdir" "inner.png" "%" "%zz" "ç" "%C3%A7" "notes.txt" "outside" "secret.png"])

(defn- fuzz-src [^Random rnd]
  (apply str (repeatedly (inc (.nextInt rnd 6)) #(nth fuzz-pieces (.nextInt rnd (count fuzz-pieces))))))

(deftest image-src-fuzz-test
  (let [rnd (Random. 270)
        root (temp-dir)
        book (io/file root "book")
        outside (io/file root "outside")
        _ (do (write! outside "secret.png" secret)
              (write! book "ok.png" png-a)
              (write! book "sub/ok.png" png-a)
              (write! book "A.png" png-b)
              (write! book ".hidden.png" png-a)
              (link! (io/file book "link.png") (io/file outside "secret.png"))
              (link! (io/file book "linkdir") outside)
              (link! (io/file book "inner.png") (io/file book "ok.png")))
        real-book (.toRealPath (.toPath book) (make-array java.nio.file.LinkOption 0))
        page "books/l/s/b/index.html"
        counts (atom {})]
    (dotimes [_ 5000]
      (let [src (fuzz-src rnd)
            {:keys [as-is refused path suffix]} (site/image-src src)
            collector (site/image-collector book)
            answer ((:src collector) src)
            {:keys [images]} ((:result collector))]
        (swap! counts update (cond as-is :as-is refused :refused :else :path) (fnil inc 0))
        (when path
          (testing (pr-str src)
            (is (not-any? #{"" "." ".."} path))
            (is (not-any? #(str/starts-with? % ".") path))
            (is (.startsWith (.normalize (.resolve real-book (str/join "/" path))) real-book))))
        (when (seq images)
          (swap! counts update :copied (fnil inc 0))
          (testing (pr-str src)
            (is (= 1 (count images)))
            (let [{rel :path} (first images)
                  real (.toRealPath ^Path (reduce #(.resolve ^Path %1 ^String %2) real-book (str/split rel #"/"))
                                    (make-array java.nio.file.LinkOption 0))]
              (is (.startsWith real real-book) "read inside the book's folder")
              (is (= real (:source (first images))) "copied from that real path")
              (is (not (str/includes? (slurp (.toFile ^Path (:source (first images)))) "SECRET")))
              (testing "the browser asks for exactly the copy, below the page's folder"
                (is (str/ends-with? answer suffix) "the query or fragment is kept")
                (let [path-part (subs answer 0 (- (count answer) (count suffix)))
                      resolved (.resolve (URI. "http" "site.test" (str "/" page) nil) (URI. path-part))]
                  (is (= (str "/books/l/s/b/" rel) (.getPath resolved))))))))
        (when (and (not as-is) (empty? images))
          (is (nil? answer) (pr-str src)))))
    (testing "not vacuous: every outcome is hit many times"
      (is (< 1000 (:refused @counts 0)) (pr-str @counts))
      (is (< 100 (:as-is @counts 0)) (pr-str @counts))
      (is (< 100 (:path @counts 0)) (pr-str @counts))
      (is (< 20 (:copied @counts 0)) (pr-str @counts)))))
