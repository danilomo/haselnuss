(ns haselnuss.library.listing-cache-test
  "The listing cache (TASK-266; doc-12, \"Scale\" consequence 1): a
  re-list with a warm cache reads no book file and runs no shelf script
  while nothing changed (AC #1), sees every edit, addition, removal and
  move of a book (AC #2), reruns a generated shelf's script exactly when
  its `shelf.clj` or a `:data` file changed (AC #3), and gives what a
  cold listing gives (AC #4) -- every cache here makes the round trip
  through the saved state (`haselnuss.library.state`) first, as a build's
  does.

  Reads are counted through the provider's `:open-reader` seam and script
  calls through its `:on-script-call` seam, both injected; only
  `uncached-listing-stats-nothing-test` redefines a fn, since a stat has
  no seam. Every file a test writes is given a modification time
  well in the past (`age!`), each write a different one: a file modified
  within the last two seconds is deliberately not cached
  (`haselnuss.library.digest/trusted-stamp`), which `racy-file-test` pins."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.cli :as cli]
            [haselnuss.library :as library]
            [haselnuss.library.digest :as digest]
            [haselnuss.library.directory :as directory]
            [haselnuss.library.filesystem :as fs]
            [haselnuss.library.generated :as generated]
            [haselnuss.library.graph.libraries :as libraries]
            [haselnuss.library.graph.registry :as registry]
            [haselnuss.library.graph.scan :as scan]
            [haselnuss.library.state :as state]
            [haselnuss.synth :as synth])
  (:import (java.io File)
           (java.nio.file Files LinkOption)
           (java.nio.file.attribute FileAttribute FileTime)
           (java.util Random)))

;; --- helpers -------------------------------------------------------------------

(defn- temp-dir ^File []
  (.toFile (Files/createTempDirectory "haselnuss-listing" (make-array FileAttribute 0))))

(def ^:private past
  "The next modification time `age!` hands out: two hours ago, then one
  second later each time, so no two writes ever share one."
  (atom (- (System/currentTimeMillis) (* 2 3600 1000))))

(defn- age!
  "Give every regular file under `root` modified in the last ten minutes
  -- the ones this test just wrote -- the next time from `past`."
  [^File root]
  (let [t (swap! past + 1000)
        recent (- (System/currentTimeMillis) (* 10 60 1000))]
    (doseq [^File f (file-seq root)
            :when (and (.isFile f) (< recent (.lastModified f)))]
      (Files/setLastModifiedTime (.toPath f) (FileTime/fromMillis t)))))

(defn- copy-tree!
  [^File from ^File to]
  (doseq [^File f (file-seq from)
          :when (Files/isRegularFile (.toPath f) (make-array LinkOption 0))]
    (let [target (io/file to (str (.relativize (.toPath from) (.toPath f))))]
      (io/make-parents target)
      (io/copy f target))))

(defn- write! ^File [^File root rel text]
  (let [f (io/file root rel)]
    (io/make-parents f)
    (spit f text)
    f))

(defn- site-copy
  "A copy of the site fixture set (TASK-264) in a temp folder, aged."
  ^File []
  (let [root (temp-dir)]
    (copy-tree! (io/file "test/fixtures/site") root)
    (age! root)
    root))

(defn- yaml [^File root] (str (io/file root "libraries.yaml")))

(defn- directory-of
  "The library directory of `root`'s `libraries.yaml`, its filesystem
  libraries built with `listing-cache` (nil: none, the uncached listing),
  counting every entry file listing opens into the atom `reads` and every
  call into a shelf script, by kind, into the atom `calls`."
  [^File root listing-cache {:keys [reads calls]}]
  (let [provider (fs/provider (cond-> {:open-reader (fn [f]
                                                      (when reads (swap! reads conj (str f)))
                                                      (io/reader f :encoding "UTF-8"))
                                       :on-script-call (fn [kind & _]
                                                         (when calls (swap! calls conj kind)))}
                                (some? listing-cache) (assoc :listing-cache listing-cache)))
        file (yaml root)]
    (directory/build-directory file (directory/read-directory-file file)
                               {:registry (directory/register-provider directory/default-registry
                                                                       :filesystem provider)})))

(defn- listing
  "Everything listing a directory answers, as a plain value: per library,
  its entries, diagnostics, shelf tree, and every listed book's kind,
  entry and files."
  [dir]
  (into {}
        (for [lib-name (library/library-names dir)
              :let [lib (library/find-library dir lib-name)]]
          [lib-name {:entries (library/list-books lib)
                     :diagnostics (library/library-diagnostics lib)
                     :tree (library/shelf-tree lib)
                     :books (mapv (fn [b]
                                    {:entry (library/book-entry b)
                                     :path (or (fs/book-path b) (generated/script-path b))
                                     :data (generated/data-files b)
                                     :locatable? (satisfies? library/Locatable b)})
                                  (library/listed-books lib))}])))

(defn- saved-and-loaded
  "`caches` after the round trip through a saved state."
  [caches]
  (let [site (temp-dir)]
    (state/save-state! site {:graph (libraries/library-indexed (registry/new-graph))
                             :listing caches})
    (let [loaded (state/load-state site)]
      (is (:usable? loaded) (:message loaded))
      (:listing loaded))))

(defn- relist
  "List `root` with the cache `caches` (after its round trip through a
  saved state): `{:listing :caches :reads :calls :dir}`."
  [root caches]
  (let [reads (atom [])
        calls (atom [])
        dir (directory-of root (saved-and-loaded caches) {:reads reads :calls calls})
        listed (listing dir)]
    {:dir dir
     :listing listed
     :caches (scan/listing-caches (scan/context {:directory dir}))
     :reads @reads
     :calls @calls}))

(defn- cold
  "The uncached listing of `root`."
  [root]
  (listing (directory-of root nil {})))

;; --- AC #1: a warm re-list reads no book ---------------------------------------

(deftest warm-listing-reads-no-book-test
  (let [root (temp-dir)
        _ (synth/generate! root {:books 300})
        _ (age! root)
        books (count (filter #(str/ends-with? (.getName ^File %) ".hdoc") (file-seq root)))
        first-run (relist root {})
        second-run (relist root (:caches first-run))]
    (testing "the cold run reads every entry file once, and caches every one"
      (is (= 300 books))
      (is (= books (count (:reads first-run)) (count (distinct (:reads first-run)))))
      (is (= books (reduce + (map (comp count :files) (vals (:caches first-run)))))))
    (testing "the warm run, from the saved state, opens no book file"
      (is (= [] (:reads second-run))))
    (testing "and lists exactly what the cold and the uncached listings list"
      (is (= (:listing first-run) (:listing second-run)))
      (is (= (cold root) (:listing second-run))))
    (testing "the cache it leaves is the one it was given"
      (is (= (:caches first-run) (:caches second-run))))))

;; --- AC #2: edits, additions, removals and moves are seen --------------------------

(deftest book-changes-are-seen-test
  (let [root (temp-dir)
        _ (doseq [[rel title] [["a.hdoc" "Alpha"] ["s/b.hdoc" "Beta"] ["s/c.hdoc" "Gamma"]
                               ["d/book.hdoc" "Delta"] ["e.hdoc" "Epsilon"]]]
            (write! root (str "lib/" rel) (str "---\ntitle: " title "\n---\n\n# " title "\n")))
        _ (spit (io/file root "libraries.yaml") "- name: lib\n  type: filesystem\n  path: lib\n")
        _ (age! root)
        step (fn [caches change! expected-reads]
               (change!)
               (age! root)
               (let [run (relist root caches)]
                 (is (= (cold root) (:listing run)) "the warm listing equals a cold one")
                 (is (= (set (map #(str (io/file root "lib" %)) expected-reads)) (set (:reads run)))
                     "and opened only the changed files")
                 (:caches run)))
        titles (fn [] (into {} (map (fn [e] [(:slug (:id e)) (:title e)]))
                            (library/list-books (library/find-library (directory-of root nil {}) "lib"))))
        c0 (:caches (relist root {}))
        c1 (testing "an edit that keeps the size is seen through the new modification time"
             (let [before (.length (io/file root "lib/a.hdoc"))
                   c (step c0 #(write! root "lib/a.hdoc" "---\ntitle: Aleph\n---\n\n# Alpha\n") ["a.hdoc"])]
               (is (= before (.length (io/file root "lib/a.hdoc"))))
               (is (= [{:t :str :text "Aleph"}] (get (titles) "a")))
               c))
        c2 (testing "a new book"
             (step c1 #(write! root "lib/s/f.hdoc" "---\ntitle: Phi\n---\n") ["s/f.hdoc"]))
        c3 (testing "a removed book is gone, and nothing is read"
             (let [c (step c2 #(io/delete-file (io/file root "lib/e.hdoc")) [])]
               (is (not (contains? (titles) "e")))
               (is (not (contains? (:files (get c "lib")) (str (io/file root "lib/e.hdoc"))))
                   "nor kept in the cache")
               c))
        c4 (testing "a book moved to another shelf is read at its new place"
             (step c3 #(do (io/make-parents (io/file root "lib/t/c.hdoc"))
                           (.renameTo (io/file root "lib/s/c.hdoc") (io/file root "lib/t/c.hdoc")))
                   ["t/c.hdoc"]))
        _ (testing "a single-file book moved into a book folder"
            (step c4 #(do (io/make-parents (io/file root "lib/a/book.hdoc"))
                          (.renameTo (io/file root "lib/a.hdoc") (io/file root "lib/a/book.hdoc")))
                  ["a/book.hdoc"]))]
    (is (= ["t"] (:shelf (:id (first (filter #(= "c" (:slug (:id %)))
                                             (library/list-books (library/find-library (directory-of root nil {}) "lib"))))))))))

(deftest file-swapped-under-a-name-test
  (testing "a book file replaced by another of the same size and time (mv) is a different file"
    (let [root (temp-dir)
          a (write! root "lib/a.hdoc" "---\ntitle: One\n---\n")
          b (write! root "lib/_b.hdoc" "---\ntitle: Two\n---\n")
          _ (spit (io/file root "libraries.yaml") "- name: lib\n  type: filesystem\n  path: lib\n")
          _ (age! root)
          t (.lastModified a)
          _ (Files/setLastModifiedTime (.toPath b) (FileTime/fromMillis t))
          c0 (:caches (relist root {}))
          _ (.renameTo b a)
          run (relist root c0)]
      ;; file systems without a file identity cannot tell; Linux ones can
      (when (some? (Files/getAttribute (.toPath a) "basic:fileKey" (make-array LinkOption 0)))
        (is (= [(str a)] (:reads run)) "the file identity changed, so it is read")
        (is (= (cold root) (:listing run)))))))

(deftest racy-file-test
  (testing "a file modified within the racy window is not cached, so a same-size edit right after the read is seen"
    (let [root (temp-dir)
          f (write! root "lib/a.hdoc" "---\ntitle: One\n---\n")
          _ (spit (io/file root "libraries.yaml") "- name: lib\n  type: filesystem\n  path: lib\n")
          first-run (relist root {})
          stamp (digest/stamp f)]
      (is (= {} (:files (get (:caches first-run) "lib"))) "just written: not cached")
      ;; the same size and, put back, the same modification time: only
      ;; not caching the racy stamp lets the next run see this edit
      (spit f "---\ntitle: Two\n---\n")
      (Files/setLastModifiedTime (.toPath f) (FileTime/from (long (:mtime stamp)) java.util.concurrent.TimeUnit/NANOSECONDS))
      (is (= (dissoc stamp :key) (dissoc (digest/stamp f) :key)))
      (let [run (relist root (:caches first-run))]
        (is (= [(str f)] (:reads run)))
        (is (= [{:t :str :text "Two"}] (:title (first (:entries (get (:listing run) "lib"))))))))))

(deftest unreadable-file-is-not-cached-test
  (let [root (temp-dir)
        f (write! root "lib/a.hdoc" "---\ntitle: One\n---\n")
        _ (spit (io/file root "libraries.yaml") "- name: lib\n  type: filesystem\n  path: lib\n")
        _ (age! root)
        failing (fs/filesystem-library {:name "lib" :path (str (io/file root "lib"))}
                                       {:listing-cache {}
                                        :open-reader (fn [_] (throw (java.io.IOException. "disk on fire")))})]
    (is (= [:invalid-front-matter] (map :type (library/library-diagnostics failing))))
    (is (= {} (:files (fs/listing-cache failing))) "a read that failed is not cached")
    (is (some? (digest/stamp f)))))

;; --- AC #3: a generated shelf's script reruns only when its files change ---------------

(deftest generated-shelf-reruns-only-on-change-test
  (let [root (site-copy)
        shelf (io/file root "bible/blivre")
        first-run (relist root {})
        warm (relist root (:caches first-run))]
    (testing "the cold run loads the script and calls :books once"
      (is (= [:load :books] (:calls first-run)))
      (is (seq (:shelves (get (:caches first-run) "bible")))))
    (testing "unchanged: the warm listing does not run the script at all"
      (is (= [] (:calls warm)))
      (is (= (:listing first-run) (:listing warm))))
    (testing "a book's document from the cached listing loads the script then, once for the shelf"
      (let [calls (atom [])
            dir (directory-of root (saved-and-loaded (:caches first-run)) {:calls calls})
            lib (library/find-library dir "bible")
            books (library/listed-books lib)
            fresh (library/listed-books (library/find-library (directory-of root nil {}) "bible"))]
        (is (= [] @calls))
        (is (= (map library/document fresh) (map library/document books)))
        (is (= (into [:load] (repeat (count books) :document)) @calls))
        (is (= (map generated/file-digests fresh) (map generated/file-digests books))
            "the digests are the ones the lazy load read")
        (is (= (library/locate (first fresh) "3:1") (library/locate (first books) "3:1")))))
    (testing "another file in the shelf folder changing does not rerun it"
      (write! shelf "notes.txt" "not data")
      (age! root)
      (let [run (relist root (:caches warm))]
        (is (= [] (:calls run)))
        (is (= (cold root) (:listing run)))))
    (let [c1 (testing "an edited shelf.clj reruns it"
               (spit (io/file shelf "shelf.clj")
                     (str/replace (slurp (io/file shelf "shelf.clj"))
                                  "{:slug slug :title title :lang \"pt-BR\"}"
                                  "{:slug slug :title title :lang \"pt\"}"))
               (age! root)
               (let [run (relist root (:caches warm))]
                 (is (= [:load :books] (:calls run)))
                 (is (= (cold root) (:listing run)))
                 (is (every? #(= "pt" (:lang %)) (:entries (get (:listing run) "bible"))) "not vacuous")
                 (:caches run)))
          c2 (testing "an edited :data file reruns it"
               (spit (io/file shelf "blivre.json")
                     (str/replace (slurp (io/file shelf "blivre.json")) "\"slug\": \"mateus\"" "\"slug\": \"mt\""))
               (age! root)
               (let [run (relist root c1)]
                 (is (= [:load :books] (:calls run)))
                 (is (= (cold root) (:listing run)))
                 (is (some #(= "mt" (:slug (:id %))) (:entries (get (:listing run) "bible"))) "not vacuous")
                 (:caches run)))]
      (testing "and after the rerun, unchanged again: no run"
        (is (= [] (:calls (relist root c2)))))
      (testing "a failing script is not cached: it reruns until it lists"
        (spit (io/file shelf "shelf.clj") "{:books (fn [_] (throw (ex-info \"no\" {})))}")
        (age! root)
        (let [r1 (relist root c2)
              r2 (relist root (:caches r1))]
          (is (= [:load] (:calls r1)) "the script is not a shelf map: it fails to load")
          (is (= {} (:shelves (get (:caches r1) "bible"))))
          (is (= [:load] (:calls r2)))
          (is (= (cold root) (:listing r2)))))
      (testing "with scripts disabled nothing runs, cache or not"
        (spit (io/file root "libraries.yaml")
              (str/replace (slurp (io/file root "libraries.yaml")) "scripts: true" "scripts: false"))
        (age! root)
        (let [run (relist root c2)]
          (is (= [] (:calls run)))
          (is (= (cold root) (:listing run)))
          (is (= [:scripts-disabled] (map :type (:diagnostics (get (:listing run) "bible"))))))))))

(deftest data-symlink-retargeted-test
  (testing "a :data path that is a symbolic link, pointed at another file, reruns the script"
    (let [root (site-copy)
          shelf (io/file root "bible/blivre")
          json (io/file shelf "blivre.json")
          other (io/file shelf "other.json")
          link (io/file shelf "data.json")]
      (spit other (str/replace (slurp json) "\"slug\": \"joao\"" "\"slug\": \"jo\""))
      (spit (io/file shelf "shelf.clj")
            (str/replace (slurp (io/file shelf "shelf.clj")) "blivre.json" "data.json"))
      (Files/createSymbolicLink (.toPath link) (.toPath (io/file "blivre.json")) (make-array FileAttribute 0))
      (age! root)
      (let [c0 (:caches (relist root {}))]
        (Files/delete (.toPath link))
        (Files/createSymbolicLink (.toPath link) (.toPath (io/file "other.json")) (make-array FileAttribute 0))
        (let [run (relist root c0)]
          (is (= [:load :books] (:calls run)))
          (is (some #(= "jo" (:slug (:id %))) (:entries (get (:listing run) "bible"))))
          (is (= (cold root) (:listing run))))))))

;; --- seeded random change sequence --------------------------------------------------

(def ^:private seed "TASK-266" 266)

(deftest seeded-change-sequence-test
  (let [rnd (Random. seed)
        root (site-copy)
        slots ["reformed/r1.hdoc" "reformed/s/r2.hdoc" "reformed/s/t/r3.hdoc" "reformed/r4/book.hdoc"
               "reformed/r5.hdoc" "reformed/s/r6.hdoc"]
        exists? #(.isFile (io/file root %))
        pick (fn [xs] (when (seq xs) (nth xs (.nextInt rnd (count xs)))))
        ;; a two-digit title, so rewriting it keeps the file's size
        book-text (fn [] (str "---\ntitle: T" (format "%02d" (.nextInt rnd 100)) "\n"
                              (when (.nextBoolean rnd) "lang: pt\n") "---\n\nBody.\n"))
        edit! (fn [rel f] (spit (io/file root rel) (f (slurp (io/file root rel)))))
        stats (atom {:quiet 0 :read 0 :rerun 0 :same-size 0})]
    (reduce
     (fn [caches i]
       (let [existing (filterv exists? slots)
             op (.nextInt rnd 8)]
         (case op
           0 (write! root (pick slots) (book-text))
           1 (when-let [p (pick existing)] (io/delete-file (io/file root p)))
           2 (let [from (pick existing) to (pick (vec (remove exists? slots)))]
               (when (and from to)
                 (io/make-parents (io/file root to))
                 (.renameTo (io/file root from) (io/file root to))))
           3 (edit! "bible/blivre/shelf.clj"
                    #(str/replace % #"\{:slug slug :title title :lang \"[^\"]*\"\}"
                                  (str "{:slug slug :title title :lang \"" (pick ["pt-BR" "pt" "en"]) "\"}")))
           4 (edit! "bible/blivre/blivre.json"
                    #(str/replace % #"\"title\": \"Gênesis[^\"]*\""
                                  (str "\"title\": \"Gênesis " (.nextInt rnd 10) "\"")))
           5 (when-let [p (pick existing)]
               (swap! stats update :same-size inc)
               (edit! p #(str/replace % #"title: T\d\d" (str "title: T" (format "%02d" (.nextInt rnd 100))))))
           (6 7) nil)
         (age! root)
         (let [run (relist root caches)]
           (is (= (cold root) (:listing run)) (str "step " i ", op " op))
           (swap! stats update (cond (seq (:calls run)) :rerun (seq (:reads run)) :read :else :quiet) inc)
           (:caches run))))
     (:caches (relist root {}))
     (range 60))
    (testing "not vacuous: quiet re-lists, re-reads, script reruns and same-size edits all happened"
      (is (every? #(<= 5 %) (vals @stats)) (pr-str @stats)))))

;; --- AC #4: cached results equal a cold listing on the fixture set -------------------

(defn- graph-view
  "A full scan of `root` -- with the listing cache `caches` when given --
  as plain data, and the listing caches the scan left."
  [root caches]
  (let [ctx (scan/context (cond-> {:directory-opts {:path (yaml root) :env {}}}
                            (some? caches) (assoc :listing-cache caches)))
        g (scan/scan ctx nil)]
    {:graph (state/graph-data g) :caches (scan/listing-caches ctx)}))

(deftest cached-scan-equals-cold-on-site-fixtures-test
  (let [root (site-copy)
        changes [["unchanged" (fn [])]
                 ["a book's title and body edited"
                  #(spit (io/file root "reformed/catecismo-heidelberg.hdoc")
                         (str/replace (slurp (io/file root "reformed/catecismo-heidelberg.hdoc"))
                                      #"(?m)^title: .*$" "title: Catecismo (editado)"))]
                 ["a book added"
                  #(write! root "reformed/nova.hdoc"
                           "---\ntitle: Nova\n---\n\n# Nova {#sec:nova}\n\nVer @{bible:romanos#14:8}.\n")]
                 ["a book removed" #(io/delete-file (io/file root "reformed/dangling.hdoc"))]
                 ["a book moved to another shelf"
                  #(do (io/make-parents (io/file root "reformed/outros/queda.hdoc"))
                       (.renameTo (io/file root "reformed/estudos/queda.hdoc")
                                  (io/file root "reformed/outros/queda.hdoc")))]
                 ["shelf.clj edited"
                  #(spit (io/file root "bible/blivre/shelf.clj")
                         (str/replace (slurp (io/file root "bible/blivre/shelf.clj"))
                                      "{:slug slug :title title :lang \"pt-BR\"}"
                                      "{:slug slug :title (str title \"!\") :lang \"pt-BR\"}"))]
                 ["blivre.json edited: a book renamed"
                  #(spit (io/file root "bible/blivre/blivre.json")
                         (str/replace (slurp (io/file root "bible/blivre/blivre.json"))
                                      "\"slug\": \"romanos\"" "\"slug\": \"rm\""))]]]
    (reduce (fn [caches [what change!]]
              (testing what
                (change!)
                (age! root)
                (let [cold-view (graph-view root nil)
                      warm (graph-view root (saved-and-loaded caches))]
                  (is (= (:graph cold-view) (:graph warm)))
                  (is (seq (:books (:graph warm))) "not vacuous")
                  (:caches warm))))
            (:caches (graph-view root {}))
            changes)))

;; --- review round: a caller's own provider, UTF-8, validation, stats ------------------

(deftest caller-provider-keeps-its-options-test
  (let [root (site-copy)
        cli-directory-opts @#'cli/directory-opts
        ;; marks every book it rewrites, so its absence would show
        transform (fn [document] {:document document
                                  :diagnostics [{:type :marked :message "transformed"}]})
        marked? (fn [g] (some #(= :marked (:type %)) (mapcat :diagnostics (vals (:books (state/graph-data g))))))
        scan-with (fn [caches]
                    (let [ctx (scan/context
                                {:directory-opts (cli-directory-opts {:libraries (yaml root) :library-env {}}
                                                                     #{"sec" "fig"}
                                                                     {:transform transform
                                                                      :listing-cache caches})})]
                      {:graph (scan/scan ctx nil) :caches (scan/listing-caches ctx)}))]
    (testing "the CLI's directory-opts carry :listing-cache in their own provider: kinds and :transform stay"
      (let [first-run (scan-with {})
            second-run (scan-with (saved-and-loaded (:caches first-run)))]
        (is (marked? (:graph first-run)) "the caller's :transform ran")
        (is (marked? (:graph second-run)))
        (is (seq (:files (get (:caches first-run) "reformed"))) "and the listing kept a cache")
        (is (= (state/graph-data (:graph first-run)) (state/graph-data (:graph second-run))))))
    (testing "scan/context refuses :listing-cache where it would replace or miss the caller's provider"
      (doseq [opts [{:directory-opts (cli-directory-opts {:libraries (yaml root) :library-env {}} nil {})}
                    {:directory (directory-of root nil {})}
                    {:directory-opts {:path (yaml root) :env {}} :own-directory (directory-of root nil {})}
                    {}]]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #":listing-cache"
                              (scan/context (assoc opts :listing-cache {})))
            (pr-str (keys opts))))
      (is (some? (scan/context {:directory-opts {:path (yaml root) :env {}} :listing-cache {}}))
          "with plain directory-opts it is the default provider's"))))

(deftest lone-surrogate-is-not-cached-test
  (testing "a string the UTF-8 state cannot hold is listed, and read again, rather than cached"
    (let [root (temp-dir)
          f (write! root "lib/a.hdoc" "---\ntitle: \"\\uD800\"\n---\n")
          _ (write! root "lib/g/shelf.clj"
                    (str "{:books (fn [_] [{:slug \"x\" :title \"\\uD800\"}])\n"
                         " :document (fn [_ _] {:meta {} :blocks []})}\n"))
          _ (spit (io/file root "libraries.yaml") "- name: lib\n  type: filesystem\n  path: lib\n  scripts: true\n")
          _ (age! root)
          first-run (relist root {})
          second-run (relist root (:caches first-run))
          titles (fn [run] (mapv :title (:entries (get (:listing run) "lib"))))]
      (is (= [[{:t :str :text "\uD800"}] [{:t :str :text "\uD800"}]] (titles first-run))
          "not vacuous: both titles hold the lone surrogate")
      (is (= {} (:files (get (:caches first-run) "lib"))))
      (is (= {} (:shelves (get (:caches first-run) "lib"))))
      (is (= [(str f)] (:reads second-run)))
      (is (= [:load :books] (:calls second-run)))
      (is (= (cold root) (:listing second-run)))
      (is (= (titles first-run) (titles second-run))))))

(deftest invalid-cached-fields-are-not-believed-test
  (testing "a cache entry whose fields cannot make a BookEntry is a miss: the file is read"
    (let [root (temp-dir)
          f (write! root "lib/a.hdoc" "---\ntitle: Real\n---\n")
          _ (spit (io/file root "libraries.yaml") "- name: lib\n  type: filesystem\n  path: lib\n")
          _ (age! root)
          stamp (digest/stamp f)]
      (doseq [bad [{:title 42} {:tags "not-a-list"} {:lang 7}]]
        (let [run (relist root {"lib" {:files {(str f) {:stamp stamp :fields bad :problems []}}
                                       :shelves {}}})]
          (is (= [(str f)] (:reads run)) (pr-str bad))
          (is (= (cold root) (:listing run)))))
      (testing "and a good one is believed (the same stamp, so not vacuous)"
        (let [run (relist root {"lib" {:files {(str f) {:stamp stamp :fields {:title "Cached"} :problems []}}
                                       :shelves {}}})]
          (is (= [] (:reads run)))
          (is (= [{:t :str :text "Cached"}] (:title (first (:entries (get (:listing run) "lib")))))))))))

(deftest uncached-listing-stats-nothing-test
  ;; `with-redefs` only here: there is no seam for a stat, and this
  ;; namespace's tests do not run concurrently with another's
  (testing "without :listing-cache, listing (generated shelves included) takes no stamp"
    (let [root (site-copy)
          stamps (atom 0)
          real digest/stamp]
      (with-redefs [digest/stamp (fn [f] (swap! stamps inc) (real f))]
        (let [listed (cold root)]
          (is (seq (:entries (get listed "bible"))) "not vacuous: the shelf script ran")
          (is (= 0 @stamps))
          (listing (directory-of root {} {}))
          (is (pos? @stamps) "and the cached listing does stamp"))))))
