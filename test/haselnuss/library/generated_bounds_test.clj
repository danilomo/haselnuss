(ns ^:timing haselnuss.library.generated-bounds-test
  "A library's own directory entry raising its generated shelves'
  `:document` bounds (TASK-300): `document-timeout-ms` and
  `document-output-limit`, beside `scripts: true`.

  The shelf here is synthetic: one book `big`, whose document is a
  section `sec:one` of `paras` paragraphs of `chars` characters each and
  whose `:document`, `:books` and `:locate` each first count to a number
  read from the data file -- a deterministic way to make one call slow
  (sci counts a few million a second). Sizes are chosen against the real
  2,000,000-character default; timeouts against a short in-process
  `:timeout-ms` (the provider opts' default), so the suite does not wait
  out the real 5 s more than once.

  `^:timing` (TASK-302): those sizes and timeouts are wall-clock bounds a
  loaded machine misses (a sharded run failed `raised-output-limit-test`
  and `cli-test` on the 5 s default), so `bin/test` runs this namespace in
  its quiet phase, after the shards."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.ast :as ast]
            [haselnuss.cli :as cli]
            [haselnuss.library :as library]
            [haselnuss.library.directory :as directory]
            [haselnuss.library.filesystem :as fs]
            [haselnuss.library.generated :as generated]
            [haselnuss.sandbox :as sandbox])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

;; --- helpers -----------------------------------------------------------------

(defn- temp-dir ^File []
  (.toFile (Files/createTempDirectory "haselnuss-bounds" (make-array FileAttribute 0))))

(defn- write!
  ^File [^File dir rel text]
  (let [file (io/file dir rel)]
    (io/make-parents file)
    (spit file text)
    file))

(def ^:private shelf-script
  "One book `big`; every call first counts to its `*-spin` from
  `size.edn`. `extra` is spliced into the shelf map."
  (str "(defn- spin [n] (loop [i 0] (if (< i n) (recur (inc i)) i)))\n"
       "(defn- para [text] {:t :para :inlines [{:t :str :text text}]})\n"
       "{:data [\"size.edn\"]\n"
       " :books (fn [data] (spin (:books-spin (get data \"size.edn\")))\n"
       "          [{:slug \"big\" :title \"Big\"}])\n"
       " :document\n"
       " (fn [data _]\n"
       "   (let [{:keys [paras chars document-spin]} (get data \"size.edn\")\n"
       "         text (apply str (repeat chars \"x\"))]\n"
       "     (spin document-spin)\n"
       "     {:meta {:title [{:t :str :text \"Big\"}]}\n"
       "      :blocks [{:t :section :level 1 :heading [{:t :str :text \"One\"}]\n"
       "                :attr {:id \"sec:one\" :classes [] :props {}}\n"
       "                :blocks (vec (repeat paras (para text)))}]}))\n"
       " :locate (fn [data _ _] (spin (:locate-spin (get data \"size.edn\")))\n"
       "           {:title \"Big 1\" :blocks [(para \"one\")]})\n"
       " %s}\n"))

(def ^:private large
  "A document of about 3,000,000 printed characters: over the default
  2,000,000-character cap, quick to make."
  {:paras 2000 :chars 1500})

(def ^:private slow
  "Counting this far takes sci about two seconds on the development
  machine: well past a 300 ms timeout, well within a 60 s one."
  5000000)

(defn- shelf-dir
  "A library folder whose shelf `s` is the synthetic shelf, `size` merged
  into its `size.edn` (every spin 0 unless given)."
  ([size] (shelf-dir size ""))
  ([size extra]
   (let [dir (temp-dir)]
     (write! dir "s/shelf.clj" (format shelf-script extra))
     (write! dir "s/size.edn" (pr-str (merge {:paras 1 :chars 10 :document-spin 0
                                              :books-spin 0 :locate-spin 0}
                                             size)))
     dir)))

(defn- library-of
  "A filesystem library `l` over `dir`, scripts on, `entry` merged into
  its entry config, `opts` the provider's."
  ([dir entry] (library-of dir entry {}))
  ([dir entry opts]
   (fs/filesystem-library (merge {:name "l" :path (str dir) :scripts true} entry) opts)))

(defn- document-of [lib] (library/document (library/find-book lib "big")))

(defn- elapsed-ms [start] (/ (- (System/nanoTime) start) 1e6))

(def ^:private no-libraries {:library-env {} :library-home "/nonexistent/haselnuss-test-home"})

(defn- main-run
  "`cli/main*` over `args` with no real library directory, as `{:status
  :out :err}`."
  [& args]
  (let [err (java.io.StringWriter.)
        out (java.io.StringWriter.)
        status (binding [*err* err *out* out] (cli/main* args no-libraries))]
    {:status status :out (str out) :err (str err)}))

(defn- yaml-file
  "A library directory file in a fresh folder: one `name: l` entry over
  `dir` with `extra` YAML lines, then `more` (whole entries)."
  ([dir extra] (yaml-file dir extra ""))
  ([dir extra more]
   (write! (temp-dir) "libraries.yaml"
           (str "- name: l\n  type: filesystem\n  path: " dir "\n  scripts: true\n" extra more))))

;; --- AC #1, #4: the entry raises the :document bounds ---------------------------

(deftest defaults-test
  (testing "the defaults are today's, and the ceilings admit them"
    (is (= 5000 sandbox/default-timeout-ms))
    (is (= 2000000 generated/default-document-output-limit))
    (is (= 120000 generated/max-document-timeout-ms))
    (is (= 32000000 generated/max-document-output-limit))))

(deftest raised-output-limit-test
  (let [dir (shelf-dir large)]
    (testing "without the setting, a ~3,000,000-character :document is over today's cap"
      (let [d (document-of (library-of dir {}))]
        (is (= :script-failed (:type d)))
        (is (= "l:s/big" (:book d)))
        (is (str/includes? (:message d) "2000000-character cap") (:message d))))
    (testing "document-output-limit raises it for this library"
      (let [d (document-of (library-of dir {:document-output-limit 4000000}))]
        (is (ast/valid? ast/Document d) (pr-str (:message d)))
        (is (< 2000000 (count (pr-str d)) 4000000))))
    (testing "a raised cap is still a cap"
      (let [d (document-of (library-of dir {:document-output-limit 2500000}))]
        (is (= :script-failed (:type d)))
        (is (str/includes? (:message d) "2500000-character cap") (:message d))))
    (testing "the entry's value wins over the provider opts' default"
      (let [d (document-of (library-of dir {:document-output-limit 4000000}
                                       {:document-output-limit 1000}))]
        (is (ast/valid? ast/Document d) (pr-str (:message d)))))))

(deftest raised-timeout-test
  (let [dir (shelf-dir {:document-spin slow})]
    (testing "without the setting, a slow :document times out at the build's timeout"
      (let [start (System/nanoTime)
            d (document-of (library-of dir {} {:timeout-ms 300}))]
        (is (= :script-failed (:type d)))
        (is (str/includes? (:message d) "did not finish within 300ms") (:message d))
        (is (< (elapsed-ms start) 10000))))
    (testing "document-timeout-ms raises it for :document"
      (let [d (document-of (library-of dir {:document-timeout-ms 60000} {:timeout-ms 300}))]
        (is (ast/valid? ast/Document d) (pr-str (:message d)))))
    (testing "a raised timeout still stops a looping :document"
      (let [start (System/nanoTime)
            d (document-of (library-of (shelf-dir {:document-spin Long/MAX_VALUE})
                                       {:document-timeout-ms 500} {:timeout-ms 300}))]
        (is (= :script-failed (:type d)))
        (is (str/includes? (:message d) "did not finish within 500ms") (:message d))
        (is (< (elapsed-ms start) 10000))))))

(deftest entry-without-timeout-keeps-five-seconds-test
  (testing "an entry that raises only the cap keeps the sandbox's 5 s for :document"
    (let [start (System/nanoTime)
          d (document-of (library-of (shelf-dir {:document-spin Long/MAX_VALUE})
                                     {:document-output-limit 4000000}))]
      (is (= :script-failed (:type d)))
      (is (str/includes? (:message d) "did not finish within 5000ms") (:message d))
      (is (< 4900 (elapsed-ms start) 20000)))))

;; --- AC #5: :books and :locate keep their bounds -----------------------------

(deftest books-and-locate-keep-their-bounds-test
  (let [raised {:document-timeout-ms 60000 :document-output-limit 32000000}]
    (testing ":books keeps the build's timeout"
      (let [lib (library-of (shelf-dir {:books-spin slow}) raised {:timeout-ms 300})
            [d] (library/library-diagnostics lib)]
        (is (= [] (mapv :id (library/list-books lib))))
        (is (= :script-failed (:type d)))
        (is (str/includes? (:message d) ":books did not finish within 300ms") (:message d))))
    (testing ":locate keeps the build's timeout"
      (let [lib (library-of (shelf-dir {:locate-spin slow}) raised {:timeout-ms 300})
            p (library/locate (library/find-book lib "big") "1")]
        (is (= :script-failed (:type p)))
        (is (str/includes? (:message p) ":locate for l:s/big did not finish within 300ms")
            (:message p))))
    (testing ":locate keeps the sandbox's output cap"
      (let [dir (temp-dir)
            _ (write! dir "s/shelf.clj"
                      (str "{:books (fn [_] [{:slug \"big\"}]) :document (fn [_ _] nil)\n"
                           " :locate (fn [_ _ _] {:blocks [{:t :para :inlines [{:t :str :text"
                           " (apply str (repeat 300000 \"x\"))}]}]})}"))
            p (library/locate (library/find-book (library-of dir raised) "big") "1")]
        (is (= :script-failed (:type p)))
        (is (str/includes? (:message p) (str sandbox/default-output-limit "-character cap"))
            (:message p))))))

;; --- AC #2: only the library entry, and only its own library ---------------------

(deftest only-the-entry-raises-test
  (testing "a shelf map naming the bounds raises nothing"
    (let [dir (shelf-dir large ":document-timeout-ms 60000 :document-output-limit 32000000")
          d (document-of (library-of dir {}))]
      (is (= :script-failed (:type d)))
      (is (str/includes? (:message d) "2000000-character cap") (:message d))))
  (testing "one library's setting does not reach another library's shelves"
    (let [dir (shelf-dir large)
          yaml (yaml-file dir "  document-output-limit: 4000000\n"
                          (str "- name: other\n  type: filesystem\n  path: " dir "\n  scripts: true\n"))
          libdir (directory/load-library-directory {:path (str yaml) :env {} :home "/h"})
          raised (library/document (library/find-book (library/find-library libdir "l") "big"))
          other (library/document (library/find-book (library/find-library libdir "other") "big"))]
      (is (ast/valid? ast/Document raised) (pr-str (:message raised)))
      (is (= :script-failed (:type other)))
      (is (str/includes? (:message other) "2000000-character cap") (:message other)))))

;; --- AC #3: invalid values cost only that entry -------------------------------

(deftest invalid-values-test
  (testing "a value that is not a whole number from 1 to the ceiling makes no library"
    (doseq [[k v] [[:document-timeout-ms 0] [:document-timeout-ms -1] [:document-timeout-ms 1.5]
                   [:document-timeout-ms "60000"] [:document-timeout-ms true]
                   [:document-timeout-ms (inc generated/max-document-timeout-ms)]
                   [:document-output-limit 0] [:document-output-limit 2.5e6]
                   [:document-output-limit (inc generated/max-document-output-limit)]
                   [:document-output-limit 100000000000000000000N]]]
      (let [result (library-of (temp-dir) {k v})]
        (is (= :invalid-library (:type result)) (pr-str k v))
        (is (str/includes? (:message result) (str (name k) " must be a whole number from 1 to "))
            (:message result)))))
  (testing "the ceilings themselves are accepted"
    (is (satisfies? library/Library
                    (library-of (temp-dir) {:document-timeout-ms generated/max-document-timeout-ms
                                            :document-output-limit generated/max-document-output-limit}))))
  (testing "in a directory file, only that entry fails; the others still load"
    (let [dir (shelf-dir {})
          yaml (yaml-file dir "  document-timeout-ms: 600000\n"
                          (str "- name: other\n  type: filesystem\n  path: " dir "\n  scripts: true\n"))
          libdir (directory/load-library-directory {:path (str yaml) :env {} :home "/h"})
          [d & more] (library/library-diagnostics libdir)]
      (is (nil? more))
      (is (= :invalid-library (:type d)))
      (is (= "l" (:library d)))
      (is (str/includes? (:message d) "document-timeout-ms must be a whole number from 1 to 120000, got 600000")
          (:message d))
      (is (= :unavailable-library (:type (library/find-library libdir "l"))))
      (is (some? (library/find-book (library/find-library libdir "other") "big"))))))

;; --- AC #4: --check, --build-site and a single build -----------------------------

(deftest ^:slow cli-test
  (let [dir (shelf-dir large)
        raised (str (yaml-file dir "  document-output-limit: 4000000\n"))
        plain (str (yaml-file dir ""))]
    (testing "--check: the large book checks clean when raised, script-failed when not"
      (let [{:keys [status out err]} (main-run "--libraries" raised "--check")]
        (is (= 0 status) (str out err))
        (is (not (str/includes? out "script-failed")) out))
      (let [{:keys [status out err]} (main-run "--libraries" plain "--check")]
        (is (not= 0 status) (str out err))
        (is (str/includes? out "[script-failed]") out)
        (is (str/includes? out "2000000-character cap") out)))
    (testing "--build-site writes the large book's page when raised, and not when not"
      (let [site (io/file (temp-dir) "site")
            {:keys [status err]} (main-run "--libraries" raised "--build-site" (str site))
            page (io/file site "books/l/s/big/index.html")]
        (is (= 0 status) err)
        (is (.isFile page))
        (is (< 3000000 (.length page)))
        (is (str/includes? (slurp page) "id=\"sec:one\"")))
      (let [site (io/file (temp-dir) "site")
            {:keys [status err]} (main-run "--libraries" plain "--build-site" (str site))]
        (is (not= 0 status) err)
        (is (str/includes? err "[script-failed]") err)
        (is (not (.exists (io/file site "books/l/s/big/index.html"))))))
    (testing "a single build quoting a label passage of the large book"
      (let [work (temp-dir)
            doc (write! work "doc.hdoc" "---\ntitle: Host\n---\n\n!quote l:big#sec:one\n")
            out (io/file work "doc.html")
            {:keys [status err]} (main-run "--libraries" raised (str doc) "--output" (str out))]
        (is (= 0 status) err)
        (is (str/includes? (slurp out) "data-source=\"l:s/big#sec:one\"")))
      (let [work (temp-dir)
            doc (write! work "doc.hdoc" "---\ntitle: Host\n---\n\n!quote l:big#sec:one\n")
            out (io/file work "doc.html")
            {:keys [err]} (main-run "--libraries" plain (str doc) "--output" (str out))]
        (is (str/includes? err "book reference \"l:big#sec:one\"") err)
        (is (str/includes? err ":document for l:s/big's own output is over this build's 2000000-character cap")
            err)))))
