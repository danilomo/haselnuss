(ns haselnuss.library.graph.refresh-test
  "`refresh` (TASK-244, doc-11 \"refresh, for changed files\"): a graph
  brought up to date from changed files equals a fresh `scan` of the same
  files, only the owning books are scanned again, `:rebuild` is
  `affected-by` on the old graph merged with `affected-by` on the new one,
  and `check-report`/`backlinks-report` cannot tell the two graphs apart.
  Also the hash race noted on TASK-240: a record's `:hash` is the hash of
  the bytes its load read, not of the files as they are after it.

  Scans are counted through the context's `:on-scan-book` seam, never
  `with-redefs`. Every case works on a temp copy of the fixture libraries
  (`test/fixtures/libraries`, its own `libraries.yaml` included) plus a
  `lab` library written here, whose books link and quote each other."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.book-ref :as book-ref]
            [haselnuss.library :as library]
            [haselnuss.library.digest :as digest]
            [haselnuss.library.filesystem :as fs]
            [haselnuss.library.graph :as graph]
            [haselnuss.library.graph.backlinks :as backlinks]
            [haselnuss.library.graph.check :as check]
            [haselnuss.library.graph.rebuild :as rebuild]
            [haselnuss.library.graph.scan :as scan]
            [haselnuss.parser :as parser])
  (:import (java.io File)
           (java.nio.file Files LinkOption)
           (java.nio.file.attribute FileAttribute)
           (java.util Random)))

;; --- helpers -----------------------------------------------------------------

(defn- temp-dir ^File []
  (.toFile (Files/createTempDirectory "haselnuss-refresh" (make-array FileAttribute 0))))

(defn- write!
  ^File [^File dir rel text]
  (let [file (io/file dir rel)]
    (io/make-parents file)
    (spit file text)
    file))

(defn- copy-tree!
  "Copy every regular file under `from` to the same place under `to`."
  [^File from ^File to]
  (doseq [^File f (file-seq from)
          :when (Files/isRegularFile (.toPath f) (make-array LinkOption 0))]
    (let [rel (str (.relativize (.toPath from) (.toPath f)))]
      (io/make-parents (io/file to rel))
      (io/copy f (io/file to rel)))))

(def ^:private lab-books
  "The `lab` library: links and quotes between its books, into the
  generated Bible shelf and into `reformed`, a dangling link to a book
  that does not exist yet, a stale shelf path, and a shared chapter."
  {"a.hdoc" (str "# A {#sec:a}\n\nA links @{lab:b#sec:b} and @{lab:gone}.\n\n"
                 "!quote lab:c#sec:c\n\n!include _parts/ch.hdoc\n")
   "s1/b.hdoc" "# B {#sec:b}\n\nB links @{lab:a}, @{lab:s2/c} and @{bible:hebreus#2:14}.\n"
   "s1/c.hdoc" "# C {#sec:c}\n\nC quotes:\n\n!quote bible:romanos#14:8\n"
   "d.hdoc" "# D {#sec:d}\n\nD links @{lab:new} and @{bible:hebreus}.\n\n!quote lab:a#sec:a\n"
   "_parts/ch.hdoc" "# Ch {#sec:ch}\n\nChapter citing @{reformed:apostles#sec:creed}.\n"})

(defn- fixture-copy
  "`{:root File :yaml path}`: a temp copy of the fixture libraries, with the
  `lab` library and a `later` library whose folder does not exist yet
  added to its directory file."
  []
  (let [root (temp-dir)]
    (copy-tree! (io/file "test/fixtures/libraries") root)
    (doseq [[rel text] lab-books] (write! root (str "lab/" rel) text))
    (spit (io/file root "libraries.yaml")
          (str "- name: lab\n  type: filesystem\n  path: lab\n"
               "- name: later\n  type: filesystem\n  path: later\n")
          :append true)
    {:root root :yaml (str (io/file root "libraries.yaml"))}))

(defn- context
  "A fresh context over the directory file `yaml`, counting each book
  scanned into the atom `scans` when given."
  ([yaml] (context yaml nil))
  ([yaml scans]
   (scan/context (cond-> {:directory-opts {:path yaml :env {}}}
                   scans (assoc :on-scan-book #(swap! scans conj %))))))

(defn- view
  "Everything a graph holds, as a plain value (graphs are `reify` values):
  book records, each book's edges, library records."
  [g]
  (let [books (graph/indexed-books g)]
    {:books books
     :edges (into {} (map (fn [id] [id (graph/edges-from g id)])) (keys books))
     :libraries (graph/indexed-libraries g)}))

(defn- reports
  "`check-report`, and `backlinks-report` for every book in `ids` and every
  passage an edge into it names: the reports a daemon would serve."
  [g ids]
  {:check (check/check-report g)
   :backlinks (into {}
                    (for [id ids
                          target (into [id]
                                       (keep (fn [e] (when-let [p (:passage (:to e))] {:id id :passage p})))
                                       (graph/edges-to g id))]
                      [target (backlinks/backlinks-report g target)]))})

(defn- refresh!
  "`(scan/refresh ...)` of `g` with `paths` (relative to `root`), plus
  `:scanned`, the ids it scanned."
  [{:keys [root yaml]} g paths]
  (let [scans (atom [])
        result (scan/refresh (context yaml scans) g (mapv #(str (io/file root %)) paths))]
    (assoc result :scanned @scans)))

(defn- id
  ([lib slug] (book-ref/book-id lib slug))
  ([lib shelf slug] (book-ref/book-id lib shelf slug)))

(defn- all-ids [& graphs] (into #{} (mapcat (comp keys graph/indexed-books)) graphs))

(defn- check-step
  "Assert that `result`, refreshing `old`, holds what a fresh scan of the
  same files holds, that it scanned exactly `scanned`, and that its
  `:rebuild` is `affected-by` of `touched` on both graphs."
  [env old result scanned touched]
  (let [fresh (scan/scan (context (:yaml env)) nil)
        g (:graph result)
        ids (all-ids old fresh)]
    (is (= (view fresh) (view g)) "the refreshed graph equals a fresh scan")
    (is (= (reports fresh ids) (reports g ids)) "and reports exactly what it reports")
    (is (= (sort-by book-ref/format-book-id scanned)
           (sort-by book-ref/format-book-id (:scanned result)))
        "only the owning (and new) books are scanned")
    (is (= (into (rebuild/affected-by old touched) (rebuild/affected-by g touched))
           (:rebuild result)))
    g))

(defn- record-of [g book] (get (graph/indexed-books g) book))

;; --- AC #1-#4: each kind of change, one after the other -----------------------

(deftest refresh-cases-test
  (let [{:keys [root] :as env} (fixture-copy)
        g0 (scan/scan (context (:yaml env)) nil)
        a (id "lab" "a") b (id "lab" ["s1"] "b") c (id "lab" ["s1"] "c") d (id "lab" "d")
        new-book (id "lab" "new")
        hebreus (id "bible" ["scripture"] "hebreus")
        westminster (id "reformed" ["confessions" "reformed"] "westminster")]
    (testing "the starting graph is not vacuous"
      (is (= :resolved (:status (first (graph/edges-from g0 a)))))
      (is (= #{b d} (into #{} (map (comp :id :from)) (graph/edges-to g0 a))))
      (is (= :unknown-book (:problem (first (graph/edges-from g0 d)))))
      (is (= :unavailable (:status (get (graph/indexed-libraries g0) "later")))))
    (let [g1 (testing "an edited entry file: only its book is scanned; a link into a label it lost dangles"
               (write! root "lab/s1/b.hdoc"
                       "# B {#sec:bb}\n\nB links @{lab:a}, @{lab:s2/c} and @{bible:hebreus#2:14}.\n")
               (let [r (refresh! env g0 ["lab/s1/b.hdoc"])
                     g (check-step env g0 r [b] #{b})]
                 (is (= [:dangling :unknown-label] ((juxt :status :problem) (first (graph/edges-from g a))))
                     "a's edge was resolved again without scanning a")
                 (is (contains? (:rebuild r) a))
                 g))
          g2 (testing "an edited !include'd chapter: only the book including it is scanned"
               (write! root "lab/_parts/ch.hdoc"
                       "# Ch {#sec:ch}\n\nChapter citing @{reformed:apostles#sec:nope}.\n")
               (let [r (refresh! env g1 ["lab/_parts/ch.hdoc"])
                     g (check-step env g1 r [a] #{a})
                     ch01 "reformed/confessions/reformed/westminster/chapters/ch01.hdoc"]
                 (is (some #(= :unknown-label (:problem %)) (graph/edges-from g a)))
                 (write! root ch01 "# Of the Holy Scripture {#sec:ch1}\n\nEdited.\n")
                 (check-step env g (refresh! env g [ch01]) [westminster] #{westminster})))
          g3 (testing "an edited shelf data file: the shelf's books are scanned, a renamed one replaces the old"
               (write! root "generated/scripture/books.edn"
                       (str "{\"jo\" {:slug \"joao\" :title \"João\"}\n"
                            " \"rm\" {:slug \"romanos\" :title \"Romanos\"}\n"
                            " \"hb\" {:slug \"hebreus2\" :title \"Hebreus\"}}\n"))
               (let [joao (id "bible" ["scripture"] "joao")
                     romanos (id "bible" ["scripture"] "romanos")
                     hebreus2 (id "bible" ["scripture"] "hebreus2")
                     r (refresh! env g2 ["generated/scripture/books.edn"])
                     g (check-step env g2 r [joao romanos hebreus2] #{joao romanos hebreus2 hebreus})]
                 (is (nil? (record-of g hebreus)))
                 (is (some? (record-of g hebreus2)))
                 (is (= [:unknown-book]
                        (into [] (comp (filter #(= "hebreus" (:slug (:id (book-ref/parse-book-ref (:written (:to %)))))))
                                       (map :problem))
                              (graph/edges-from g b)))
                     "b's link into the renamed book, resolved again without scanning b")
                 (is (every? #(contains? (:rebuild r) %) [b d]) "the books linking to the renamed one")
                 g))
          g4 (testing "a new book file: only it is scanned; the dangling link naming it resolves"
               (write! root "lab/new.hdoc" "# New {#sec:n}\n\nNew links @{lab:d}.\n")
               (let [r (refresh! env g3 ["lab/new.hdoc"])
                     g (check-step env g3 r [new-book] #{new-book})]
                 (is (= [:resolved new-book] ((juxt :status (comp :id :to)) (first (graph/edges-from g d)))))
                 (is (contains? (:rebuild r) d))
                 g))
          g5 (testing "a deleted book: nothing is scanned; links and quotes into it dangle"
               (io/delete-file (io/file root "lab/s1/c.hdoc"))
               (let [r (refresh! env g4 ["lab/s1/c.hdoc"])
                     g (check-step env g4 r [] #{c})]
                 (is (nil? (record-of g c)))
                 (is (some #(and (= :quote (:kind %)) (= :unknown-book (:problem %))) (graph/edges-from g a)))
                 (is (contains? (:rebuild r) a) "the quoter of a deleted book is rebuilt")
                 g))]
      (testing "a file under a library that was unavailable (its record has no :root)"
        (write! root "later/x.hdoc" "# X\n\nX links @{lab:a}.\n")
        (let [r (refresh! env g5 ["later/x.hdoc"])
              g (check-step env g5 r [(id "later" "x")] #{(id "later" "x")})]
          (is (= :available (:status (get (graph/indexed-libraries g) "later")))))))))

(deftest book-moved-to-a-folder-test
  (testing "a book listed under its old id from a file its record does not hold is scanned,
            even when only the new file is reported"
    (let [{:keys [root] :as env} (fixture-copy)
          g0 (scan/scan (context (:yaml env)) nil)
          d (id "lab" "d")]
      (io/delete-file (io/file root "lab/d.hdoc"))
      (write! root "lab/d/book.hdoc" "# D2 {#sec:d2}\n\nD2 links @{lab:b}.\n")
      (let [r (refresh! env g0 ["lab/d/book.hdoc"])]
        (check-step env g0 r [d] #{d})
        (is (= #{"sec:d2"} (set (keys (:labels (record-of (:graph r) d))))))))))

(deftest dependent-that-cannot-be-re-resolved-is-scanned-test
  (testing "a kept book whose diagnostics do not end in one run per edge is scanned instead"
    (let [{:keys [root] :as env} (fixture-copy)
          a (id "lab" "a") b (id "lab" ["s1"] "b")
          g0 (scan/scan (context (:yaml env)) nil)
          ;; a's record with one diagnostic too many at its end: the
          ;; per-edge split re-resolved-record relies on no longer holds
          planted (graph/with-book g0 (-> (record-of g0 a)
                                          (assoc :id a :edges (graph/edges-from g0 a))
                                          (update :diagnostics conj {:type :book-failed :level :warning
                                                                     :message "planted"})))]
      (is (some #(= "planted" (:message %)) (:diagnostics (record-of planted a))))
      (write! root "lab/s1/b.hdoc" "# B {#sec:bb}\n\nB.\n")
      (let [r (refresh! env planted ["lab/s1/b.hdoc"])]
        (check-step env planted r [b a] #{b})
        (is (not-any? #(= "planted" (:message %)) (:diagnostics (record-of (:graph r) a))))))))

(deftest directory-file-change-scans-afresh-test
  (let [{:keys [yaml] :as env} (fixture-copy)
        g0 (scan/scan (context yaml) nil)
        old-ids (set (keys (graph/indexed-books g0)))]
    ;; drop `shared`, add `extra` (a second name for the lab folder)
    (spit yaml (str (str/replace (slurp yaml) "- name: shared\n  type: filesystem\n  path: shared\n" "")
                    "- name: extra\n  type: filesystem\n  path: lab\n"))
    (testing "the context knows the file it loaded its directory from"
      (is (= (scan/canonical-path yaml) (:directory-file (context yaml)))))
    (testing "default scope: the recorded libraries the new directory still names"
      (let [r (refresh! env g0 ["libraries.yaml"])
            fresh (scan/scan (context yaml) nil)
            expected (scan/scan (context yaml) (remove #{"extra"} (scan/directory-library-names
                                                                   (:directory (context yaml)))))]
        (is (= (view expected) (view (:graph r))))
        (is (not (contains? (graph/indexed-libraries (:graph r)) "shared")))
        (is (not (contains? (graph/indexed-libraries (:graph r)) "extra")))
        (is (= (into old-ids (keys (graph/indexed-books (:graph r)))) (:rebuild r))
            "every book of either graph is rebuilt")
        (is (not= (view fresh) (view (:graph r))) "not vacuous: a nil scope would differ")))
    (testing "scope nil: every library the new directory names"
      (let [r (scan/refresh (context yaml) g0 [yaml] {:scope nil})
            fresh (scan/scan (context yaml) nil)]
        (is (= (view fresh) (view (:graph r))))
        (is (= (reports fresh (all-ids g0 fresh)) (reports (:graph r) (all-ids g0 fresh))))
        (is (= (into old-ids (keys (graph/indexed-books fresh))) (:rebuild r)))))))

;; --- the hash race (TASK-240 review) ------------------------------------------

(deftest hash-is-of-the-bytes-the-load-read-test
  (testing "a filesystem book edited while it loads keeps the hash of what was read"
    (let [root (temp-dir)
          file (write! root "x.hdoc" "# X {#sec:x}\n\nOld.\n")
          lib (fn [opts] (library/library-directory [(fs/filesystem-library {:name "l" :path (str root)} opts)]))
          before (:hash (record-of (scan/scan (scan/context {:directory (lib {})}) nil) (id "l" "x")))
          ;; the parse runs after the entry file was read: edit it then
          racing (lib {:parse (fn [text opts]
                                (when (str/ends-with? (str (:source opts)) "x.hdoc")
                                  (spit file "# X {#sec:y}\n\nNew.\n"))
                                (parser/parse text opts))})
          raced (scan/scan (scan/context {:directory racing}) nil)
          after (scan/scan (scan/context {:directory (lib {})}) nil)]
      (is (= #{"sec:x"} (set (keys (:labels (record-of raced (id "l" "x"))))))
          "the record was built from the old bytes")
      (is (= before (:hash (record-of raced (id "l" "x"))))
          "so its hash is the old bytes' hash")
      (is (not= before (:hash (record-of after (id "l" "x")))))
      (testing "and a refresh for the file brings the record up to date"
        (is (= (view after)
               (view (:graph (scan/refresh (scan/context {:directory (lib {})}) raced [(str file)]))))))))
  (testing "a generated shelf whose data file is edited after it was read"
    (let [root (temp-dir)
          _ (write! root "s/shelf.clj"
                    (str "{:data [\"d.edn\"]\n :books (fn [data] (map (fn [s] {:slug s :title s}) (get data \"d.edn\")))\n"
                         " :document (fn [_ slug] {:meta {} :blocks [{:t :para :inlines [{:t :str :text slug}]}]})}\n"))
          data (write! root "s/d.edn" "[\"one\"]")
          lib (fn [opts] (library/library-directory
                          [(fs/filesystem-library {:name "g" :path (str root) :scripts true} opts)]))
          one (id "g" ["s"] "one")
          before (:hash (record-of (scan/scan (scan/context {:directory (lib {})}) nil) one))
          racing (lib {:on-script-call (fn [kind & _] (when (= :books kind) (spit data "[\"one\" \"two\"]")))})
          raced (scan/scan (scan/context {:directory racing}) nil)]
      (is (= #{one} (set (keys (graph/indexed-books raced)))))
      (is (= before (:hash (record-of raced one))))
      (is (not= before (:hash (record-of (scan/scan (scan/context {:directory (lib {})}) nil) one)))))))

(deftest read-text-decodes-as-slurp-test
  (testing "the loaders' digesting read gives exactly slurp's text, malformed UTF-8 included"
    (let [rnd (Random. 244)
          dir (temp-dir)
          ;; a BOM, valid multi-byte text, lone continuation bytes, a
          ;; truncated sequence, and enough of it to cross slurp's buffer
          piece (byte-array (concat [0xEF 0xBB 0xBF] (.getBytes "João, “aspas” ✓ " "UTF-8")
                                    [0x80 0xBF 0xE2 0x82 0x41 0xF0 0x9F 0x98]))
          content (byte-array (apply concat (repeatedly 900 #(concat piece [(.nextInt rnd 128)]))))
          f (io/file dir "x.hdoc")]
      (io/copy content f)
      (is (< 8192 (alength content)))
      (let [[text d] (digest/read-text f "UTF-8")]
        (is (= (slurp f :encoding "UTF-8") text))
        (is (str/includes? text "�") "the malformed bytes were really there")
        (is (= (digest/bytes-digest (Files/readAllBytes (.toPath f))) d))))))

;; --- seeded random edit sequence ----------------------------------------------

(def ^:private seed "TASK-244" 244)

(def ^:private lab-targets
  ["lab:a" "lab:a#sec:a" "lab:b" "lab:s1/b#sec:b" "lab:s2/b" "lab:c#sec:c" "lab:d" "lab:e#sec:e"
   "lab:f" "lab:gone" "bible:romanos#14:8" "bible:hebreus#2:14" "bible:joao#8:34-35" "bible:atos"
   "reformed:apostles#sec:creed" "lab:a#sec:zz" "lab:bad ref" "later:x" "nolib:x"])

(def ^:private lab-paths
  ["lab/a.hdoc" "lab/s1/b.hdoc" "lab/s1/c.hdoc" "lab/d.hdoc" "lab/e.hdoc" "lab/s2/f.hdoc"
   "lab/s2/b.hdoc"])

(defn- pick [^Random rnd xs] (nth xs (.nextInt rnd (count xs))))

(defn- random-book
  "Random `.hdoc` text: a labelled heading and a few links and quotes."
  [^Random rnd]
  (let [label (pick rnd ["sec:a" "sec:b" "sec:c" "sec:e" "sec:x"])
        refs (repeatedly (.nextInt rnd 4) #(pick rnd lab-targets))]
    (str "# T {#" label "}\n\n"
         (str/join "\n\n" (map (fn [r] (if (.nextBoolean rnd) (str "See @{" r "}.") (str "!quote " r))) refs))
         (when (.nextBoolean rnd) "\n\n!include _parts/ch.hdoc")
         "\n")))

(defn- random-edit!
  "Apply one random change under `root`; answer the paths it touched."
  [^Random rnd ^File root]
  (case (.nextInt rnd 6)
    ;; the `later` library's folder appears with a book, or goes away
    ;; (the library then fails to load: an :unavailable record, no :root)
    5 (let [dir (io/file root "later")]
        (if (.exists dir)
          (do (run! io/delete-file (reverse (file-seq dir))) ["later/x.hdoc"])
          (do (write! root "later/x.hdoc" (random-book rnd)) ["later/x.hdoc"])))
    ;; write (create or edit) a book
    (0 1) (let [p (pick rnd lab-paths)] (write! root p (random-book rnd)) [p])
    ;; delete a book
    2 (let [p (pick rnd lab-paths)] (io/delete-file (io/file root p) true) [p])
    ;; edit the shared chapter
    3 (let [p "lab/_parts/ch.hdoc"]
        (write! root p (str "# Ch {#sec:ch}\n\nChapter citing @{" (pick rnd lab-targets) "}.\n"))
        [p])
    ;; edit the shelf's data: drop, rename or restore a book
    4 (let [p "generated/scripture/books.edn"
            hb (pick rnd ["hebreus" "hebreus2" nil])]
        (write! root p (str "{\"jo\" {:slug \"joao\" :title \"João\"}\n"
                            " \"rm\" {:slug \"romanos\" :title \"Romanos\"}"
                            (when hb (str "\n \"hb\" {:slug \"" hb "\" :title \"Hebreus\"}"))
                            "}\n"))
        [p])))

(deftest seeded-refresh-sequence-test
  (let [{:keys [root] :as env} (fixture-copy)
        rnd (Random. seed)
        scope ["lab" "bible" "bible-off" "reformed" "later"]
        scan-it #(scan/scan (context (:yaml env)) scope)
        steps 60
        outcome (loop [i 0 g (scan-it) acc {:dependents 0 :vanished 0 :new 0 :status-flips 0}]
                  (if (= i steps)
                    acc
                    (let [paths (random-edit! rnd root)
                          r (refresh! env g paths)
                          fresh (scan-it)
                          g2 (:graph r)
                          ids (all-ids g fresh)
                          scanned (set (:scanned r))
                          old-books (graph/indexed-books g)
                          new-books (graph/indexed-books g2)
                          ;; books kept (not scanned) whose edges changed
                          re-resolved (filter #(and (contains? old-books %) (not (contains? scanned %))
                                                    (not= (graph/edges-from g %) (graph/edges-from g2 %)))
                                              (keys new-books))]
                      (is (= (view fresh) (view g2)) (str "step " i ": " paths))
                      (is (= (reports fresh ids) (reports g2 ids)) (str "step " i ": " paths))
                      (is (every? #(or (not (contains? old-books %))
                                       (some (fn [p] (contains? (:files (get old-books %))
                                                                (scan/canonical-path (io/file root p))))
                                             paths))
                                  scanned)
                          (str "step " i ": only books claiming a changed path, or new ones, are scanned"))
                      (recur (inc i) g2
                             (-> acc
                                 (update :dependents + (count re-resolved))
                                 (update :vanished + (count (remove new-books (keys old-books))))
                                 (update :new + (count (remove old-books (keys new-books))))
                                 (update :status-flips + (if (= (:status (get (graph/indexed-libraries g) "later"))
                                                                (:status (get (graph/indexed-libraries g2) "later")))
                                                           0 1)))))))]
    (testing "the sequence is not vacuous"
      (is (<= 2 (:status-flips outcome)) (pr-str outcome))
      (is (<= 5 (:dependents outcome)) (pr-str outcome))
      (is (<= 3 (:vanished outcome)) (pr-str outcome))
      (is (<= 3 (:new outcome)) (pr-str outcome)))))

;; --- TASK-250: symbolic links, and two files under one id ------------------------

(defn- link-env
  "`{:root File :yaml path :lib File}`: a temp folder whose `libraries.yaml`
  names one library `p` at `lib/`, its books `books` (path under `lib/`
  -> source) written. Files outside `lib/` (`out/...`) are written with
  `write!` on `:root`."
  [books]
  (let [root (temp-dir)]
    (doseq [[rel text] books] (write! root (str "lib/" rel) text))
    (.mkdirs (io/file root "lib"))
    (spit (io/file root "libraries.yaml") "- name: p\n  type: filesystem\n  path: lib\n")
    {:root root :yaml (str (io/file root "libraries.yaml")) :lib (io/file root "lib")}))

(defn- symlink!
  "Make (or retarget) the symbolic link `rel` under `root` to point at
  `target` (written as given, so relative to the link's folder). False
  when the platform refuses symbolic links."
  [^File root rel ^String target]
  (let [p (.toPath (io/file root rel))]
    (io/make-parents (.toFile p))
    (try (Files/deleteIfExists p)
         (Files/createSymbolicLink p (.toPath (io/file target)) (make-array FileAttribute 0))
         true
         (catch UnsupportedOperationException _ false)
         (catch java.io.IOException _ false))))

(defn- canonical-path-of [^File f] (.getCanonicalPath f))

(defn- link-step
  "Retarget the link `rel` (under `root`) to `target`, refresh `g` with
  ONLY the link's path, and assert the result is a fresh scan's, that
  every book in `hosts` was scanned and is in `:rebuild`, and that
  `affected-by-files` of the link path on the old graph finds them.
  Answers the refreshed graph."
  [{:keys [root yaml] :as env} g rel target hosts]
  (symlink! root rel target)
  (let [r (refresh! env g [rel])
        link (str (io/file root rel))
        fresh (scan/scan (context yaml) nil)]
    (is (= (view fresh) (view (:graph r))) (str rel " -> " target ": the refreshed graph equals a fresh scan"))
    (is (= hosts (into #{} (filter hosts) (:scanned r))) (str rel " -> " target ": the including books are scanned"))
    (is (= hosts (into #{} (filter hosts) (:rebuild r))))
    (is (= hosts (into #{} (filter hosts) (:books (rebuild/affected-by-files g [link]))))
        (str rel ": affected-by-files finds the books reading through the link"))
    (:graph r)))

(deftest symlinked-include-refresh-test
  (let [{:keys [root yaml] :as env}
        (link-env {"_parts/one.hdoc" "## One {#sec:one}\n"
                   "_parts/two.hdoc" "## Two {#sec:two}\n\nWith @{p:nosuch}.\n"
                   "a/host.hdoc" "# Host\n\n!include ../_parts/link.hdoc\n"
                   "chain.hdoc" "# Chain\n\n!include _parts/l1.hdoc\n"
                   "dir.hdoc" "# Dir\n\n!include _inc/ch.hdoc\n"})
        _ (do (write! root "out/d1/ch.hdoc" "## Outside one {#sec:o1}\n")
              (write! root "out/d2/ch.hdoc" "## Outside two {#sec:o2}\n\nSee @{p:nowhere}.\n")
              (write! root "out/d1/e.hdoc" "# E one {#sec:e1}\n")
              (write! root "out/d2/e.hdoc" "# E two {#sec:e2}\n\n!include ch.hdoc\n"))
        supported? (and (symlink! root "lib/_parts/link.hdoc" "one.hdoc")
                        (symlink! root "lib/_parts/l1.hdoc" "l2.hdoc")
                        (symlink! root "lib/_parts/l2.hdoc" "one.hdoc")
                        (symlink! root "lib/_inc" "../out/d1")
                        (symlink! root "lib/e.hdoc" "../out/d1/e.hdoc"))
        host (id "p" ["a"] "host") chain (id "p" "chain") dir (id "p" "dir") e (id "p" "e")]
    (when supported?
      (let [g0 (scan/scan (context yaml) nil)
            files #(:files (record-of %1 %2))
            lib (canonical-path-of (io/file root "lib"))]
        (is (not-any? #(= :book-failed (:type %)) (mapcat :diagnostics (vals (graph/indexed-books g0))))
            "every book parses")
        (testing ":files holds every link met, as the link's own path"
          (is (contains? (files g0 host) (str lib "/_parts/link.hdoc")))
          (is (contains? (files g0 host) (str lib "/_parts/one.hdoc")))
          (is (every? #(contains? (files g0 chain) (str lib %)) ["/_parts/l1.hdoc" "/_parts/l2.hdoc"]))
          (is (contains? (files g0 dir) (str lib "/_inc")))
          (is (contains? (files g0 e) (str lib "/e.hdoc")))
          (is (= #{host} (graph/books-for-file g0 (str lib "/_parts/link.hdoc")))))
        (let [g1 (testing "a retargeted include link (the task's repro)"
                   (link-step env g0 "lib/_parts/link.hdoc" "two.hdoc" #{host}))
              g2 (testing "the middle link of a chain"
                   (link-step env g1 "lib/_parts/l2.hdoc" "two.hdoc" #{chain}))
              g3 (testing "a symlinked folder on the include path"
                   (link-step env g2 "lib/_inc" "../out/d2" #{dir}))
              g4 (testing "a symlinked entry file"
                   (link-step env g3 "lib/e.hdoc" "../out/d2/e.hdoc" #{e}))]
          (testing "the steps were not vacuous: each record changed"
            (is (not= (record-of g0 host) (record-of g1 host)))
            (is (not= (record-of g1 chain) (record-of g2 chain)))
            (is (not= (record-of g2 dir) (record-of g3 dir)))
            (is (not= (record-of g3 e) (record-of g4 e)))))))))

(deftest symlinked-ancestor-lookup-test
  (testing "TASK-250 review: a raw path through a symlinked folder (alias -> lib) to a
            retargeted link is claimed once mapped through scan/lookup-paths, which
            refresh does itself; affected-by-files stays an exact lookup"
    (let [{:keys [root yaml] :as env}
          (link-env {"_parts/one.hdoc" "## One {#sec:one}\n"
                     "_parts/two.hdoc" "## Two {#sec:two}\n\nWith @{p:nosuch}.\n"
                     "a/host.hdoc" "# Host\n\n!include ../_parts/link.hdoc\n"})
          host (id "p" ["a"] "host")]
      (when (and (symlink! root "lib/_parts/link.hdoc" "one.hdoc") (symlink! root "alias" "lib"))
        (let [g0 (scan/scan (context yaml) nil)
              raw (str (io/file root "alias/_parts/link.hdoc"))]
          (symlink! root "lib/_parts/link.hdoc" "two.hdoc")
          (is (= #{} (:books (rebuild/affected-by-files g0 [raw]))) "the raw path is not what :files holds")
          (is (= #{host} (:books (rebuild/affected-by-files g0 (mapcat scan/lookup-paths [raw])))))
          (is (= [(canonical-path-of (io/file root "lib/_parts/two.hdoc"))
                  (str (canonical-path-of (io/file root "lib/_parts")) "/link.hdoc")]
                 (scan/lookup-paths raw)))
          (let [r (refresh! env g0 ["alias/_parts/link.hdoc"])]
            (is (= (view (scan/scan (context yaml) nil)) (view (:graph r))))
            (is (contains? (set (:scanned r)) host))
            (is (contains? (:rebuild r) host))))))))

(def ^:private link-targets
  "Each link of the fuzzed layout and what it may point at."
  {"lib/_parts/leaf.hdoc" ["c1.hdoc" "c2.hdoc" "chain.hdoc" "../../out/d1/c1.hdoc" "missing.hdoc"]
   "lib/_parts/chain.hdoc" ["c1.hdoc" "c2.hdoc" "../../out/d2/c2.hdoc"]
   "lib/_dir" ["../out/d1" "../out/d2" "_parts"]
   "lib/h4.hdoc" ["../out/d1/e.hdoc" "../out/d2/e.hdoc"]})

(deftest seeded-symlink-refresh-test
  (testing "seeded (250): links retargeted at random, each refresh given only the link's
            path, always equal a fresh scan"
    (let [{:keys [root yaml] :as env}
          (link-env {"_parts/c1.hdoc" "## C1 {#sec:c1}\n\nSee @{p:h2}.\n"
                     "_parts/c2.hdoc" "## C2 {#sec:c2}\n\nSee @{p:none}.\n"
                     "_parts/c1x.hdoc" "unused\n"
                     "h1.hdoc" "# H1\n\n!include _parts/leaf.hdoc\n"
                     "h2.hdoc" "# H2\n\n!include _dir/c1.hdoc\n"
                     "h3.hdoc" "# H3\n\n!include _parts/chain.hdoc\n"})
          _ (doseq [d ["d1" "d2"] c ["c1" "c2"]]
              (write! root (str "out/" d "/" c ".hdoc") (str "## Out " d c " {#sec:" d c "}\n\nSee @{p:h" (subs c 1) "}.\n")))
          _ (write! root "out/d1/e.hdoc" "# E1\n")
          _ (write! root "out/d2/e.hdoc" "# E2\n\n!include c2.hdoc\n")
          rnd (Random. 250)
          supported? (every? (fn [[rel targets]] (symlink! root rel (first targets))) (sort link-targets))]
      (when supported?
        (let [outcome
              (loop [i 0 g (scan/scan (context yaml) nil) acc {:changed 0 :link-only 0}]
                (if (= i 80)
                  acc
                  (let [rel (pick rnd (sort (keys link-targets)))
                        target (pick rnd (get link-targets rel))
                        _ (symlink! root rel target)
                        link (str (io/file root rel))
                        r (refresh! env g [rel])
                        fresh (scan/scan (context yaml) nil)
                        changed (filter #(not= (record-of g %) (record-of fresh %)) (all-ids g fresh))
                        ;; the change could not have been found through the
                        ;; canonical path (the NEW target) alone
                        link-only (filter #(not (contains? (:files (record-of g %)) (scan/canonical-path link)))
                                          changed)]
                    (is (= (view fresh) (view (:graph r))) (str "step " i ": " rel " -> " target))
                    (is (not-any? #(= :book-failed (:type %)) (mapcat :diagnostics (vals (graph/indexed-books fresh))))
                        (str "step " i ": every book parses"))
                    (is (every? (set (:rebuild r)) changed) (str "step " i ": every changed book is rebuilt"))
                    (is (every? (:books (rebuild/affected-by-files g [link])) changed)
                        (str "step " i ": affected-by-files of the link path finds every changed book"))
                    (recur (inc i) (:graph r)
                           (-> acc
                               (update :changed + (count changed))
                               (update :link-only + (count link-only)))))))]
          (testing "the sequence is not vacuous: records changed, most only reachable by the link"
            (is (<= 20 (:changed outcome)) (pr-str outcome))
            (is (<= 15 (:link-only outcome)) (pr-str outcome))))))))

(deftest duplicate-canonical-id-refresh-test
  (testing "TASK-250: x.hdoc beside x/book.hdoc, one record; editing, removing and
            restoring either file refreshes to what a fresh scan gives"
    (let [{:keys [root yaml] :as env} (link-env {"x.hdoc" "# One {#sec:one}\n\nSee @{p:nosuch1}.\n"
                                                 "x/book.hdoc" "# Two {#sec:two}\n\nSee @{p:nosuch2}.\n"
                                                 "y.hdoc" "Links @{p:x}.\n"})
          x (id "p" "x")
          g0 (scan/scan (context yaml) nil)
          written #(into #{} (map (comp :written :to)) (graph/edges-from %1 %2))
          step (fn [g paths]
                 (let [r (refresh! env g paths)]
                   (is (= (view (scan/scan (context yaml) nil)) (view (:graph r))) (pr-str paths))
                   (is (contains? (set (:scanned r)) x))
                   (:graph r)))]
      (is (= #{"p:nosuch1" "p:nosuch2"} (written g0 x)))
      (is (= :ambiguous-book (:problem (first (graph/edges-from g0 (id "p" "y")))))
          "a reference to the id stays ambiguous")
      (let [g1 (do (write! root "lib/x/book.hdoc" "# Two {#sec:two}\n\nSee @{p:nosuch3}.\n")
                   (step g0 ["lib/x/book.hdoc"]))
            _ (is (= #{"p:nosuch1" "p:nosuch3"} (written g1 x)))
            g2 (do (io/delete-file (io/file root "lib/x/book.hdoc"))
                   (step g1 ["lib/x/book.hdoc"]))
            _ (is (= #{"p:nosuch1"} (written g2 x)))
            _ (is (= #{"sec:one"} (set (keys (:labels (record-of g2 x)))))
                  "a book alone under its id again has its own labels")
            g3 (do (write! root "lib/x/book.hdoc" "# Two {#sec:two}\n\nSee @{p:nosuch2}.\n")
                   (step g2 ["lib/x/book.hdoc"]))]
        (is (= (view g0) (view g3)) "back where it started")))))
