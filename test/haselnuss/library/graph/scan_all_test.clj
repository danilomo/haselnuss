(ns haselnuss.library.graph.scan-all-test
  "`scan` (TASK-241, doc-11 \"Scan pipeline & caching\" and \"Error
  handling & diagnostics\"): every book of every library in scope into one
  graph, library-level problems on library records, each book parsed and
  each generated shelf's script run once per scan, and the same files
  giving the same graph."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.book-ref :as book-ref]
            [haselnuss.library :as library]
            [haselnuss.library.filesystem :as fs]
            [haselnuss.library.graph :as graph]
            [haselnuss.library.graph.libraries :as libraries]
            [haselnuss.library.graph.memory-index :as memory-index]
            [haselnuss.library.graph.registry :as registry]
            [haselnuss.library.graph.scan :as scan]
            [haselnuss.library.memory :as memory]
            [haselnuss.parser :as parser])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

;; --- helpers -----------------------------------------------------------------

(defn- temp-dir ^File []
  (.toFile (Files/createTempDirectory "haselnuss-scan-all" (make-array FileAttribute 0))))

(defn- write!
  ^File [^File dir rel text]
  (let [file (io/file dir rel)]
    (io/make-parents file)
    (spit file text)
    file))

(defn- canonical [^File f] (.getCanonicalPath f))

(defn- fixture-libraries
  "Fresh library values over the committed fixture libraries."
  []
  [(fs/filesystem-library {:name "reformed" :path "test/fixtures/libraries/reformed"})
   (fs/filesystem-library {:name "passages" :path "test/fixtures/libraries/passages"})
   (fs/filesystem-library {:name "shared" :path "test/fixtures/libraries/shared"})
   (fs/filesystem-library {:name "bible" :path "test/fixtures/libraries/generated" :scripts true})])

(defn- fixture-directory [] (library/library-directory (fixture-libraries)))

(defn- view
  "Everything a graph holds, as a plain value: graphs are `reify`
  values, so two are compared through what they answer."
  [g]
  (let [books (graph/indexed-books g)]
    {:books books
     :edges (into {} (map (fn [id] [id (graph/edges-from g id)])) (keys books))
     :libraries (graph/indexed-libraries g)}))

(defn- listed-ids
  [dir lib-names]
  (for [n lib-names
        entry (library/list-books (library/find-library dir n))]
    (:id entry)))

;; --- AC #1: one record per listed book; scope ----------------------------------

(deftest scan-fixture-libraries-test
  (let [dir (fixture-directory)
        g (scan/scan (scan/context {:directory dir}) nil)
        names ["reformed" "passages" "shared" "bible"]
        listed (listed-ids dir names)]
    (testing "one record per listed book, every library of the directory"
      (is (= 14 (count listed)) "the fixtures list fourteen books")
      (is (= (count listed) (count (set listed))))
      (is (= (set listed) (set (keys (graph/indexed-books g))))))
    (testing "two books sharing a slug are both scanned (shared:a/same, shared:b/c/same)"
      (is (= 2 (count (filter #(= "same" (:slug %)) (keys (graph/indexed-books g)))))))
    (testing "one library record per library, carrying its library-level diagnostics"
      (is (= (set names) (set (keys (graph/indexed-libraries g)))))
      (is (every? #(= :available (:status %)) (vals (graph/indexed-libraries g))))
      (is (= [:duplicate-slug] (mapv :type (get-in (graph/indexed-libraries g) ["shared" :diagnostics]))))
      (is (= #{:invalid-book-name}
             (set (map :type (get-in (graph/indexed-libraries g) ["reformed" :diagnostics])))))
      (is (= (canonical (io/file "test/fixtures/libraries/reformed"))
             (get-in (graph/indexed-libraries g) ["reformed" :root]))))
    (testing "scan's graph is the registry's default backend, wrapped"
      (is (satisfies? graph/LibraryIndex g))
      (is (= (count listed)
             (count (graph/indexed-books (libraries/backend-graph g))))))))

(defn- cross-library-env
  "Library `a` on disk citing the in-memory library `b`."
  []
  (let [root (temp-dir)]
    (write! root "citing.hdoc"
            "# C {#sec:c}\n\nSee @{b:target#sec:t}, @{b:target#sec:nope} and @{b:ghost}.\n\n!quote b:target#sec:t\n")
    (write! root "plain.hdoc" "Plain.\n")
    {:root root
     :dir (library/library-directory
           [(fs/filesystem-library {:name "a" :path (str root)})
            (memory/memory-library
             {:name "b"
              :books [{:id (book-ref/book-id "b" ["s"] "target")
                       :entry {:title "Target"}
                       :document (fn [] (parser/parse "# T {#sec:t}\n\nT cites @{a:plain}.\n"))}]})])}))

(deftest scope-test
  (let [{:keys [dir]} (cross-library-env)
        target (book-ref/book-id "b" ["s"] "target")
        citing (book-ref/book-id "a" "citing")]
    (testing "scoping to a library scans only its books"
      (let [g (scan/scan (scan/context {:directory dir}) #{"a"})]
        (is (= #{citing (book-ref/book-id "a" "plain")} (set (keys (graph/indexed-books g)))))
        (is (= #{"a"} (set (keys (graph/indexed-libraries g)))))
        (testing "while references into the other library still resolve"
          (is (= [[:link :resolved target] [:link :dangling target] [:link :dangling nil]
                  [:quote :resolved target]]
                 (mapv (juxt :kind :status (comp :id :to)) (graph/edges-from g citing))))
          (is (= [nil :unknown-label :unknown-book nil]
                 (mapv :problem (graph/edges-from g citing))))
          (is (= 3 (count (graph/edges-to g target)))
              "backlinks into an unscanned book are answered from the citing books"))))
    (testing "scope nil and scope naming every library are the same scan"
      (is (= (view (scan/scan (scan/context {:directory dir}) nil))
             (view (scan/scan (scan/context {:directory dir}) ["b" "a"])))))
    (testing "the other library alone"
      (let [g (scan/scan (scan/context {:directory dir}) ["b"])]
        (is (= #{target} (set (keys (graph/indexed-books g)))))
        (is (= [:resolved] (mapv :status (graph/edges-from g target))))))
    (testing "an empty scope scans nothing"
      (let [g (scan/scan (scan/context {:directory dir}) #{})]
        (is (= {} (graph/indexed-books g)))
        (is (= {} (graph/indexed-libraries g)))))
    (testing "an unknown library name throws before anything is scanned (doc-11: exit 2)"
      (let [e (try (scan/scan (scan/context {:directory dir}) #{"a" "zz"}) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= ::scan/unknown-library (:type (ex-data e))))
        (is (= "unknown library \"zz\"; known: a, b" (ex-message e)))))
    (testing "a scope that is not a collection of names is refused, not read as characters"
      (doseq [bad ["a" :a {"a" true} [:a] 3]]
        (let [e (try (scan/scan (scan/context {:directory dir}) bad) nil
                     (catch clojure.lang.ExceptionInfo e e))]
          (is (= ::scan/invalid-scope (:type (ex-data e))) (pr-str bad))
          (is (str/includes? (ex-message e) "collection of library names")))))
    (testing "no directory: nothing in scope, and any name is unknown"
      (is (= {} (graph/indexed-books (scan/scan (scan/context {}) nil))))
      (is (= "unknown library \"a\"; known: (none)"
             (try (scan/scan (scan/context {}) ["a"]) nil
                  (catch clojure.lang.ExceptionInfo e (ex-message e))))))))

(deftest backend-option-test
  (let [{:keys [dir]} (cross-library-env)
        built (atom [])
        reg (registry/register-backend registry/default-registry :counting
                                       (fn [config]
                                         (swap! built conj config)
                                         (memory-index/memory-index config)))
        g (scan/scan (scan/context {:directory dir}) nil {:registry reg :backend :counting :config {:x 1}})]
    (is (= [{:x 1}] @built))
    (is (= (view (scan/scan (scan/context {:directory dir}) nil)) (view g)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown graph backend"
                          (scan/scan (scan/context {:directory dir}) nil {:backend :nope})))))

;; --- AC #2: parsed once, scripts run once ---------------------------------------

(def ^:private shelf-script
  "{:books (fn [_] [{:slug \"gen\" :title \"Gen\"} {:slug \"gen2\" :title \"Gen 2\"}])
 :document (fn [_ slug]
             {:meta {}
              :blocks [{:t :section :level 1 :heading [{:t :str :text \"C\"}]
                        :attr {:id \"cap:1\" :classes [] :props {}}
                        :blocks [{:t :para :inlines [{:t :book-ref :ref \"lib:target\"}]}]}]})
 :locate (fn [_ slug loc] {:title (str slug \" \" loc) :blocks [{:t :para :inlines [{:t :str :text loc}]}]})}
")

(defn- host-source
  [n]
  (str "# Host " n " {#sec:h}\n\n"
       "Links: @{lib:target}, @{lib:target#sec:a}, @{lib:s/gen#1:1}, @{lib:gen#2:" n "}.\n\n"
       "!quote lib:target#sec:a\n\n"
       "!quote lib:gen#cap:1\n\n"
       "!quote lib:gen#1:1\n\n"
       "!include _parts/shared-ch.hdoc\n"))

(deftest each-book-parsed-once-and-each-script-run-once-test
  (let [root (temp-dir)
        _ (do (write! root "s/shelf.clj" shelf-script)
              (write! root "target.hdoc" "# A {#sec:a}\n\nA quotes @{lib:gen}.\n\n!quote lib:gen2\n")
              (write! root "_parts/shared-ch.hdoc" "Shared chapter, citing @{lib:target#sec:a}.\n")
              (doseq [n (range 1 6)] (write! root (str "h" n ".hdoc") (host-source n))))
        parses (atom [])
        calls (atom [])
        lib (fs/filesystem-library {:name "lib" :path (str root) :scripts true}
                                   {:parse (fn [text opts]
                                             (swap! parses conj (canonical (io/file (:source opts))))
                                             (parser/parse text opts))
                                    :on-script-call (fn [kind _script & args]
                                                      (swap! calls conj (into [kind] args)))})
        g (scan/scan (scan/context {:directory (library/library-directory [lib])}) nil)
        books (graph/indexed-books g)
        edges (mapcat #(graph/edges-from g %) (keys books))]
    (testing "the scan is not vacuous: every book quoted or linked many times"
      (is (= 8 (count books)) "five hosts, the target, two generated books")
      (is (every? #(= :resolved (:status %)) edges) (pr-str (remove #(= :resolved (:status %)) edges)))
      (is (<= 15 (count (filter #(= (book-ref/book-id "lib" "target") (:id (:to %))) edges))))
      (is (<= 15 (count (filter #(= "gen" (:slug (:id (:to %)))) edges)))))
    (testing "each book's entry file is parsed exactly once, however often it is quoted or linked"
      (is (= (into {} (map (fn [f] [(canonical (io/file root f)) 1]))
                   (into ["target.hdoc"] (map #(str "h" % ".hdoc")) (range 1 6)))
             (dissoc (frequencies @parses) (canonical (io/file root "_parts/shared-ch.hdoc"))))))
    (testing "a chapter is part of each book that includes it, so it is parsed once per including book"
      (is (= 5 (get (frequencies @parses) (canonical (io/file root "_parts/shared-ch.hdoc"))))))
    (testing "the shelf script loads and lists once, each :document runs once"
      (is (= {[:load] 1 [:books] 1} (select-keys (frequencies @calls) [[:load] [:books]])))
      (is (= {"gen" 1 "gen2" 1}
             (frequencies (keep (fn [[kind slug]] (when (= :document kind) slug)) @calls)))))
    (testing "each reference as written is located once, however many books cite it"
      ;; 1:1 is written two ways (lib:s/gen#1:1 and lib:gen#1:1), five times each
      (is (= (into {["gen" "1:1"] 2} (map (fn [n] [["gen" (str "2:" n)] 1])) (range 1 6))
             (frequencies (keep (fn [[kind slug loc]] (when (= :locate kind) [slug loc])) @calls)))))))

(deftest duplicate-slugs-keep-their-own-documents-test
  (let [root (temp-dir)
        _ (do (write! root "a/same.hdoc" "# A {#sec:a}\n\nA.\n")
              (write! root "b/same.hdoc" "# B {#sec:b}\n\nB links @{lib:other}.\n")
              (write! root "other.hdoc" "Other.\n"))
        g (scan/scan (scan/context {:directory (library/library-directory
                                                [(fs/filesystem-library {:name "lib" :path (str root)})])})
                     nil)
        a (book-ref/book-id "lib" ["a"] "same")
        b (book-ref/book-id "lib" ["b"] "same")]
    (is (= #{"sec:a"} (set (keys (:labels (get (graph/indexed-books g) a))))))
    (is (= #{"sec:b"} (set (keys (:labels (get (graph/indexed-books g) b)))))
        "the second book is not served the first one's resolved document")
    (is (= [] (graph/edges-from g a)))
    (is (= ["lib:other"] (mapv (comp :written :to) (graph/edges-from g b))))
    (is (= [:duplicate-slug] (mapv :type (get-in (graph/indexed-libraries g) ["lib" :diagnostics]))))))

(deftest duplicate-canonical-id-test
  (testing "TASK-250: x.hdoc beside x/book.hdoc share even the canonical id; the one
            record under it holds both books, each from its own file, never one
            file's :files beside the other's labels"
    (let [root (temp-dir)
          one (write! root "x.hdoc" "# One {#sec:one}\n\nSee @{p:nosuch1}.\n")
          two (write! root "x/book.hdoc" "# Two {#sec:two}\n\nAnd    @{p:nosuch2}.\n")
          g (scan/scan (scan/context {:directory (library/library-directory
                                                  [(fs/filesystem-library {:name "p" :path (str root)})])})
                       nil)
          x (book-ref/book-id "p" "x")
          record (get (graph/indexed-books g) x)]
      (is (= [x] (keys (graph/indexed-books g))))
      (is (= #{(canonical one) (canonical two)} (:files record)))
      (is (= {} (:labels record)) "neither book's labels: no reference can reach them")
      (is (= {} (:label-spans record)))
      (testing "every reference of both books, each placed in its own file"
        (is (= #{["p:nosuch1" (canonical one) 3 5] ["p:nosuch2" (canonical two) 3 8]}
               (into #{} (map (juxt (comp :written :to) (comp :file :position)
                                    (comp :line :position) (comp :column :position)))
                     (graph/edges-from g x)))))
      (testing "so --check reports the problems inside both, each at its own file:line:col"
        (is (= #{[:unknown-book (canonical one) 3 5] [:unknown-book (canonical two) 3 8]}
               (into #{} (map (juxt :type :source-file :source-line :source-column)) (:diagnostics record)))))
      (is (= [:duplicate-slug] (mapv :type (get-in (graph/indexed-libraries g) ["p" :diagnostics]))))
      (testing "the record is valid, and both files claim it"
        (is (graph/valid-book-record? (assoc record :id x :edges (graph/edges-from g x))))
        (is (= #{x} (graph/books-for-file g (canonical one)) (graph/books-for-file g (canonical two))))))))

;; --- AC #3: library and book failures are diagnostics ----------------------------

(defn- directory-file
  "A temp library directory with: `ok` (a good book, one that fails to
  parse, one citing the unavailable library), `noscripts` (a generated
  shelf, scripts off), `failing` (a shelf whose script throws), and
  `gone` (a path that does not exist)."
  []
  (let [root (temp-dir)]
    (write! root "ok/good.hdoc" "# G {#sec:g}\n\nGood, citing @{gone:x} and @{noscripts:s/gen}.\n")
    (write! root "ok/bad.hdoc" "---\ntitle: [unclosed\n---\n\nBad.\n")
    (write! root "noscripts/s/shelf.clj" shelf-script)
    (write! root "noscripts/plain.hdoc" "Plain.\n")
    (write! root "failing/s/shelf.clj" (str "{:books (fn [_] (throw (ex-info \"no books today\" {})))\n"
                                            " :document (fn [_ _] {:meta {} :blocks []})}\n"))
    (write! root "libraries.yaml"
            (str "- name: ok\n  type: filesystem\n  path: ok\n"
                 "- name: noscripts\n  type: filesystem\n  path: noscripts\n"
                 "- name: failing\n  type: filesystem\n  path: failing\n  scripts: true\n"
                 "- name: gone\n  type: filesystem\n  path: nowhere\n"))
    root))

(deftest library-and-book-failures-test
  (let [root (directory-file)
        yaml (str (io/file root "libraries.yaml"))
        g (scan/scan (scan/context {:directory-opts {:path yaml :env {} :home (str root)}}) nil)
        libs (graph/indexed-libraries g)
        books (graph/indexed-books g)
        types (fn [lib-name] (mapv :type (get-in libs [lib-name :diagnostics])))]
    (testing "the scan completes with a record for every library the directory names"
      (is (= #{"ok" "noscripts" "failing" "gone"} (set (keys libs)))))
    (testing "an unavailable library: located at its directory entry, its books skipped"
      (let [[d] (get-in libs ["gone" :diagnostics])]
        (is (= :unavailable (get-in libs ["gone" :status])))
        (is (= [:unavailable-library] (types "gone")))
        (is (= :invalid-library (:type (:cause d))))
        (is (= yaml (:source-file d)))
        (is (= 11 (:source-line d)) "the fourth entry starts on line 11"))
      (is (not-any? #(= "gone" (:library %)) (keys books)))
      (testing "and an edge into it from another library dangles"
        (is (= :unavailable-library
               (:problem (first (graph/edges-from g (book-ref/book-id "ok" "good"))))))))
    (testing "disabled scripts: a diagnostic, and the shelf's books are not scanned"
      (is (= [:scripts-disabled] (types "noscripts")))
      (is (= #{(book-ref/book-id "noscripts" "plain")}
             (set (filter #(= "noscripts" (:library %)) (keys books)))))
      (is (= :scripts-disabled
             (:problem (second (graph/edges-from g (book-ref/book-id "ok" "good")))))))
    (testing "a failing shelf script"
      (is (= [:script-failed] (types "failing")))
      (is (str/includes? (:message (first (get-in libs ["failing" :diagnostics]))) "no books today")))
    (testing "a book that fails to parse: its own record, edge-less, with the diagnostic"
      (let [r (get books (book-ref/book-id "ok" "bad"))]
        (is (some #(= :book-failed (:type %)) (:diagnostics r)))
        (is (= [] (graph/edges-from g (book-ref/book-id "ok" "bad")))))
      (is (= [:invalid-front-matter] (types "ok")) "listing it is the library's problem"))
    (testing "the directory's load problem is reported once, on the failed library's record"
      (is (= 1 (count (filter #(= :unavailable-library (:type %))
                              (mapcat :diagnostics (vals libs))))))
      (is (not-any? #(= :invalid-library (:type %))
                    (mapcat :diagnostics (vals books)))))))

(deftest scope-of-a-failed-library-test
  (let [root (directory-file)
        ctx (scan/context {:directory-opts {:path (str (io/file root "libraries.yaml")) :env {}}})]
    (testing "a library that failed to load is in scope by name"
      (let [g (scan/scan ctx ["gone"])]
        (is (= {} (graph/indexed-books g)))
        (is (= [:unavailable] (mapv :status (vals (graph/indexed-libraries g)))))))
    (testing "a scope without it does not report it"
      (let [g (scan/scan ctx ["noscripts"])]
        (is (= #{"noscripts"} (set (keys (graph/indexed-libraries g)))))))))

(deftest invalid-url-template-test
  (let [{:keys [dir]} (cross-library-env)
        ;; haselnuss.library/library-directory refuses an invalid template,
        ;; but another UrlTemplates directory may answer one
        odd (reify
              library/LibraryDirectory
              (library-names [_] (library/library-names dir))
              (find-library [_ n] (library/find-library dir n))
              library/UrlTemplates
              (url-template [_ n] (when (= "b" n) "https://x/{nope}")))
        g (scan/scan (scan/context {:directory odd}) nil)
        [d] (get-in (graph/indexed-libraries g) ["b" :diagnostics])]
    (is (= :invalid-url-template (:type d)))
    (is (= "https://x/{nope}" (:url d)))
    (is (= [] (get-in (graph/indexed-libraries g) ["a" :diagnostics])))
    (testing "reported once, on the library's record, not by the books citing it"
      (is (not-any? #(= :invalid-url-template (:type %))
                    (mapcat :diagnostics (vals (graph/indexed-books g))))))))

(deftest library-that-throws-test
  (let [throwing (reify library/Library
                   (library-name [_] "t")
                   (shelf-tree [_] {})
                   (list-books [_] (throw (ex-info "disk on fire" {})))
                   (find-book [_ _] nil))
        dir (library/library-directory [throwing])
        g (scan/scan (scan/context {:directory dir}) nil)
        rec (get (graph/indexed-libraries g) "t")]
    (is (= :unavailable (:status rec)))
    (is (= [:unavailable-library] (mapv :type (:diagnostics rec))))
    (is (str/includes? (:message (first (:diagnostics rec))) "disk on fire"))))

(deftest directory-naming-a-library-it-does-not-find-test
  (let [{:keys [dir]} (cross-library-env)
        odd (reify library/LibraryDirectory
              (library-names [_] (conj (vec (library/library-names dir)) "ghost"))
              (find-library [_ n] (library/find-library dir n)))
        g (scan/scan (scan/context {:directory odd}) nil)
        rec (get (graph/indexed-libraries g) "ghost")]
    (is (= :unavailable (:status rec)))
    (is (= [:unavailable-library] (mapv :type (:diagnostics rec))))
    (is (= 3 (count (graph/indexed-books g))) "the other libraries are still scanned")))

(deftest library-without-listed-books-test
  (testing "a library without ListedBooks is scanned through find-book; a slug collision
            leaves each colliding book an edge-less record with :ambiguous-book"
    (let [mem (memory/memory-library
               {:name "m"
                :books [{:id (book-ref/book-id "m" ["x"] "same") :entry {:title "X"}
                         :document (fn [] (parser/parse "X.\n"))}
                        {:id (book-ref/book-id "m" ["y"] "same") :entry {:title "Y"}
                         :document (fn [] (parser/parse "Y.\n"))}
                        {:id (book-ref/book-id "m" "one") :entry {:title "One"}
                         :document (fn [] (parser/parse "One links @{m:one}.\n"))}]})
          plain (reify library/Library
                  (library-name [_] "m")
                  (shelf-tree [_] (library/shelf-tree mem))
                  (list-books [_] (library/list-books mem))
                  (find-book [_ slug] (library/find-book mem slug)))
          g (scan/scan (scan/context {:directory (library/library-directory [plain])}) nil)
          books (graph/indexed-books g)]
      (is (= 3 (count books)))
      (is (= [:ambiguous-book] (mapv :type (:diagnostics (get books (book-ref/book-id "m" ["x"] "same"))))))
      (is (= [:resolved] (mapv :status (graph/edges-from g (book-ref/book-id "m" "one"))))))))

;; --- AC #4: the same files give the same graph ------------------------------------

(deftest two-scans-are-equal-test
  (let [a (view (scan/scan (scan/context {:directory (fixture-directory)}) nil))
        b (view (scan/scan (scan/context {:directory (fixture-directory)}) nil))]
    (is (= a b))
    (testing "and not vacuously: the graph holds edges and diagnostics"
      (is (<= 2 (count (mapcat val (:edges a)))))
      (is (seq (mapcat :diagnostics (vals (:books a)))))
      (is (seq (mapcat :diagnostics (vals (:libraries a)))))))
  (testing "over a directory file, loaded afresh by each context"
    (let [root (directory-file)
          scan-it #(view (scan/scan (scan/context {:directory-opts {:path (str (io/file root "libraries.yaml"))
                                                                    :env {}}})
                                    nil))]
      (is (= (scan-it) (scan-it)))))
  (testing "a change on disk shows: the contexts really reload"
    (let [root (temp-dir)
          _ (write! root "one.hdoc" "One.\n")
          scan-it #(view (scan/scan (scan/context {:directory
                                                   (library/library-directory
                                                    [(fs/filesystem-library {:name "l" :path (str root)})])})
                                    nil))
          before (scan-it)]
      (write! root "one.hdoc" "One, citing @{l:one}.\n")
      (is (not= before (scan-it))))))

;; --- build parity seam --------------------------------------------------------------

(deftest kinds-reach-the-filesystem-provider-test
  (let [root (temp-dir)
        _ (write! root "lib/k.hdoc" "# K {#sec:k}\n\nSee @fig:x.\n")
        _ (write! root "libraries.yaml" "- name: l\n  type: filesystem\n  path: lib\n")
        record (fn [opts]
                 (get (graph/indexed-books
                       (scan/scan (scan/context (merge {:directory-opts {:path (str (io/file root "libraries.yaml"))
                                                                         :env {}}}
                                                       opts))
                                  nil))
                      (book-ref/book-id "l" "k")))]
    (testing "the lenient parse (no kinds) reads @fig:x as a cross-reference, which dangles"
      (is (some #(= :dangling-cross-ref (:type %)) (:diagnostics (record {})))))
    (testing "with the build's kinds, which have no fig, it is no cross-reference"
      (is (not-any? #(= :dangling-cross-ref (:type %)) (:diagnostics (record {:kinds #{"sec"}})))))))
