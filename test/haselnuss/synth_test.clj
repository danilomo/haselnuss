(ns haselnuss.synth-test
  "The synthetic collection generator (TASK-264, `dev/haselnuss/synth.clj`)
  at a small size, so the suite stays fast: 300 books, scaled-down hub and
  chains. The 50,000-book default was run by hand; its time and size are in
  the task notes."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.cli :as cli]
            [haselnuss.synth :as synth])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-dir ^File []
  (.toFile (Files/createTempDirectory "haselnuss-synth" (make-array FileAttribute 0))))

(defn- main-run
  [& args]
  (let [err (java.io.StringWriter.)
        out (java.io.StringWriter.)
        status (binding [*err* err *out* out]
                 (cli/main* args {:library-env {} :library-home "/nonexistent/haselnuss-test-home"}))]
    {:status status :out (str out) :err (str err)}))

(defn- hdoc-files
  "Every `.hdoc` under `dir`, as path relative to it -> content."
  [^File dir]
  (into (sorted-map)
        (for [^File f (file-seq dir)
              :when (and (.isFile f) (str/ends-with? (.getName f) ".hdoc"))]
          [(str (.relativize (.toPath dir) (.toPath f))) (slurp f)])))

(deftest defaults-test
  (testing "50,000 books and a 3,000-linker hub unless asked otherwise"
    (is (= {:books 50000 :seed 264 :hub-linkers 3000 :chains 200 :chain-length 5}
           (synth/options {}))))
  (testing "a small collection scales the hub and the chains down"
    (is (= {:books 300 :seed 264 :hub-linkers 30 :chains 3 :chain-length 5}
           (synth/options {:books 300})))))

(defn- run-quietly
  "`synth/run` of `args` as `{:status :out :err}`."
  [args]
  (let [out (java.io.StringWriter.)
        err (java.io.StringWriter.)
        status (binding [*out* out *err* err] (synth/run args))]
    {:status status :out (str out) :err (str err)}))

(deftest main-arguments-test
  (testing "a malformed command line prints the usage and exits 2, writing nothing"
    (doseq [args [[] ["a" "b" "c" "d"] ["DIR" "abc"] ["DIR" "300" "x"] ["DIR" "0"] ["DIR" "-5"] ["DIR" "1e3"]]]
      (let [dir (io/file (temp-dir) "out")
            args (if (seq args) (assoc args 0 (str dir)) args)
            {:keys [status out err]} (run-quietly args)]
        (is (= 2 status) (pr-str args))
        (is (= "" out))
        (is (str/starts-with? err "usage: ") err)
        (is (not (.exists dir)) (pr-str args)))))
  (testing "a refusal is a message and exit 1, not a stack trace"
    (let [dir (temp-dir)
          _ (spit (io/file dir "keep.txt") "x")
          {:keys [status err]} (run-quietly [(str dir) "10"])]
      (is (= 1 status))
      (is (= (str "haselnuss.synth: " dir " is not empty\n") err))))
  (testing "a good command line writes the collection and prints its summary"
    (let [dir (io/file (temp-dir) "out")
          {:keys [status out]} (run-quietly [(str dir) "120" "7"])]
      (is (= 0 status))
      (is (= 120 (:books (read-string out))))
      (is (= 120 (count (hdoc-files dir)))))))

(deftest small-collection-test
  (let [dir (temp-dir)
        yaml (str (io/file dir "libraries.yaml"))
        result (synth/generate! dir {:books 300})
        files (hdoc-files dir)]
    (testing "the requested number of books, across the libraries"
      (is (= 300 (count files)))
      (is (= {:books 300 :hub-linkers 30 :chains 3 :chain-length 5 :prose 254}
             (select-keys result [:books :hub-linkers :chains :chain-length :prose])))
      (is (= #{"notes" "quotes" "essays" "history" "letters" "manuals"}
             (set (map #(first (str/split % #"/")) (keys files))))))
    (testing "deep shelves: some prose book sits five shelves down"
      (is (some #(= 7 (count (str/split % #"/"))) (keys files))))
    (testing "every reference resolves: --check finds nothing"
      (let [{:keys [status out]} (main-run "--libraries" yaml "--check")]
        (is (= 0 status) out)
        (is (= "0 problems (0 errors, 0 warnings, 0 info) in 300 books\n" out))))
    (testing "the hub note: every linker links to it"
      (let [{:strs [edges summary]} (json/read-str (:out (main-run "--libraries" yaml "--backlinks" "notes:hub"
                                                                   "--format" "json")))]
        (is (= {"references" 30 "books" 30 "self" 0} summary))
        (is (= #{"link"} (set (map #(get % "kind") edges))))
        (is (= #{"resolved"} (set (map #(get % "status") edges))))))
    (testing "quote chains: each link quotes the next, resolved"
      (let [{:strs [edges]} (json/read-str (:out (main-run "--libraries" yaml "--backlinks"
                                                           "quotes:chain-0002/q-0002-5" "--format" "json")))]
        (is (= [["quotes:chain-0002/q-0002-4" "quote" "resolved"]]
               (map (fn [e] [(get-in e ["from" "id"]) (get e "kind") (get e "status")]) edges)))))
    (testing "the same options write the same collection"
      (let [again (temp-dir)]
        (synth/generate! again {:books 300})
        (is (= files (hdoc-files again)))))
    (testing "it refuses a folder that is not empty"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"is not empty" (synth/generate! dir {:books 300}))))
    (testing "and a size too small for the hub and the chains it was asked for"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot hold"
                            (synth/generate! (temp-dir) {:books 10 :hub-linkers 20}))))))
