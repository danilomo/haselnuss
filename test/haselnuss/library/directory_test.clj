(ns haselnuss.library.directory-test
  "Loading the library directory from YAML (TASK-150): the lookup order
  and the empty directory when no file is found (AC #1), relative paths
  (AC #2), `:duplicate-library`, `:unknown-provider` and the hard errors
  (AC #3), the open provider registry (AC #4), and entry keys carried
  through untouched (AC #5). Since TASK-164 an entry problem costs only
  its own library: the rest still load, and the failures are the
  directory's `library-diagnostics` (`partial-failure-test`).

  Every test injects `:env` and `:home`, so none of them reads the real
  `$HASELNUSS_LIBRARIES` or `~/.config`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.library :as library]
            [haselnuss.library.directory :as directory]
            [haselnuss.library.memory :as memory])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-dir ^File []
  (.toFile (Files/createTempDirectory "haselnuss-libdir" (make-array FileAttribute 0))))

(defn- write!
  "Write `text` to `dir`/`rel`, creating folders, and answer the File."
  ^File [^File dir rel text]
  (let [file (io/file dir rel)]
    (io/make-parents file)
    (spit file text)
    file))

(defn- lines [& ls] (str (str/join "\n" ls) "\n"))

(defn- names [dir] (library/library-names dir))

(defn- recording-registry
  "A registry whose `:memory` provider also records every config it is
  called with into `seen`."
  [seen]
  (directory/register-provider directory/default-registry :memory
                               (fn [config] (swap! seen conj config) (memory/memory-library config))))

(defn- hard-error
  "The `ex-data` and message of the hard error `(f)` throws, or nil."
  [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e
         (assoc (ex-data e) :message (ex-message e) :cause (ex-cause e)))))

;; --- AC #1: lookup order -----------------------------------------------------

(deftest lookup-order-test
  (let [tmp (temp-dir)
        home (io/file tmp "home")
        explicit (write! tmp "explicit.yaml" (lines "- name: explicit" "  type: memory"))
        from-env (write! tmp "env.yaml" (lines "- name: from-env" "  type: memory"))
        _ (write! home ".config/haselnuss/libraries.yaml"
                  (lines "- name: from-home" "  type: memory"))
        env {"HASELNUSS_LIBRARIES" (str from-env)}]
    (testing "an explicit path wins over the environment and the home config"
      (is (= ["explicit"] (names (directory/load-library-directory
                                  {:path (str explicit) :env env :home (str home)})))))
    (testing "then $HASELNUSS_LIBRARIES"
      (is (= ["from-env"] (names (directory/load-library-directory
                                  {:env env :home (str home)})))))
    (testing "then ~/.config/haselnuss/libraries.yaml"
      (is (= ["from-home"] (names (directory/load-library-directory
                                   {:env {} :home (str home)}))))
      (is (= (directory/home-config-file (str home))
             (directory/find-directory-file {:env {} :home (str home)}))))
    (testing "an environment variable naming a missing file, or blank, falls through"
      (is (= ["from-home"]
             (names (directory/load-library-directory
                     {:env {"HASELNUSS_LIBRARIES" (str (io/file tmp "missing.yaml"))}
                      :home (str home)}))))
      (is (= ["from-home"]
             (names (directory/load-library-directory
                     {:env {"HASELNUSS_LIBRARIES" ""} :home (str home)})))))
    (testing "no file anywhere is an empty directory, not an error"
      (let [dir (directory/load-library-directory {:env {} :home (str (io/file tmp "nobody"))})]
        (is (satisfies? library/LibraryDirectory dir))
        (is (= [] (names dir)))
        (is (nil? (library/find-library dir "explicit"))))
      (is (nil? (directory/find-directory-file {:env {} :home nil}))))
    (testing "an explicit path that does not exist is a hard error"
      (let [err (hard-error #(directory/load-library-directory
                              {:path (str (io/file tmp "nope.yaml")) :env env :home (str home)}))]
        (is (= ::directory/unreadable-library-directory (:type err)))
        (is (str/includes? (:message err) "nope.yaml:1: "))))))

;; --- AC #2: relative paths ---------------------------------------------------

(deftest relative-path-test
  (let [tmp (temp-dir)
        seen (atom [])
        file (write! tmp "conf/libraries.yaml"
                     (lines "- name: rel"
                            "  type: memory"
                            "  path: books/rel"
                            "- name: up"
                            "  type: memory"
                            "  path: ../shared/up"
                            "- name: abs"
                            "  type: memory"
                            "  path: /srv/books/../books/./abs"
                            "- name: tilde"
                            "  type: memory"
                            "  path: ~/books/tilde"
                            "- name: nopath"
                            "  type: memory"))
        dir (directory/load-library-directory {:path (str file) :env {} :home "/home/reader"
                                               :registry (recording-registry seen)})
        by-name (into {} (map (juxt :name identity)) @seen)
        conf (.getAbsolutePath (io/file tmp "conf"))]
    (is (= ["rel" "up" "abs" "tilde" "nopath"] (names dir)))
    (testing "a relative path resolves against the directory file's own folder"
      (is (= (str (io/file conf "books" "rel")) (:path (by-name "rel"))))
      (is (= (str (io/file (.getAbsolutePath tmp) "shared" "up")) (:path (by-name "up")))))
    (testing "an absolute path stays absolute, normalized"
      (is (= "/srv/books/abs" (:path (by-name "abs")))))
    (testing "a leading ~ means the home folder"
      (is (= "/home/reader/books/tilde" (:path (by-name "tilde")))))
    (testing "an entry without a path gets none"
      (is (not (contains? (by-name "nopath") :path))))))

(deftest tilde-path-via-env-and-home-test
  (let [tmp (temp-dir)
        home (io/file tmp "home")
        text (lines "- name: t" "  type: memory" "  path: ~/books/t")
        from-env (write! tmp "env/libraries.yaml" text)
        _ (write! home ".config/haselnuss/libraries.yaml" text)
        path-of (fn [opts]
                  (let [seen (atom [])]
                    (directory/load-library-directory
                     (assoc opts :registry (recording-registry seen)))
                    (:path (first @seen))))
        expected (str (io/file (.getAbsolutePath home) "books" "t"))]
    (testing "a file found through $HASELNUSS_LIBRARIES"
      (is (= expected (path-of {:env {"HASELNUSS_LIBRARIES" (str from-env)}
                                :home (.getAbsolutePath home)}))))
    (testing "a file found under the home folder"
      (is (= expected (path-of {:env {} :home (.getAbsolutePath home)}))))))

;; --- AC #3: failures ---------------------------------------------------------

(defn- diagnostics
  "The load diagnostics of `dir`, a vector."
  [dir]
  (vec (library/library-diagnostics dir)))

(defn- only-diagnostic
  "The single load diagnostic of `dir`, asserting there is exactly one."
  [dir]
  (let [ds (diagnostics dir)]
    (is (= 1 (count ds)) (pr-str ds))
    (first ds)))

(deftest duplicate-library-test
  (let [file (write! (temp-dir) "libraries.yaml"
                     (lines "- name: bible"
                            "  type: memory"
                            "- name: reformed"
                            "  type: memory"
                            "- name: bible"
                            "  type: memory"))
        dir (directory/load-library-directory {:path (str file) :env {} :home "/h"})
        result (only-diagnostic dir)]
    (is (= :duplicate-library (:type result)))
    (is (= :error (:level result)))
    (is (library/diagnostic? result))
    (is (= "bible" (:library result)))
    (testing "both entries are named, by position and line"
      (is (= [{:entry 1 :line 1} {:entry 3 :line 5}] (:entries result)))
      (is (str/includes? (:message result) "entry 1 (line 1)"))
      (is (str/includes? (:message result) "entry 3 (line 5)")))
    (testing "the message is located at the second entry, in the file"
      (is (str/starts-with? (:message result) (str file ":5: ")))
      (is (= (str file) (:file result))))
    (testing "the other library still loads, and neither duplicate is kept"
      (is (= ["reformed"] (names dir)))
      (is (satisfies? library/Library (library/find-library dir "reformed"))))
    (testing "a reference to the duplicated name is unavailable, not unknown"
      (let [found (library/find-library dir "bible")]
        (is (= :unavailable-library (:type found)))
        (is (= :warning (:level found)))
        (is (= "bible" (:library found)))
        (is (= result (:cause found)))
        (is (str/includes? (:message found) "\"bible\""))))))

(deftest unknown-provider-test
  (let [file (write! (temp-dir) "libraries.yaml"
                     (lines "- name: bible"
                            "  type: memory"
                            "- name: reformed"
                            "  type: carrier-pigeon"))
        dir (directory/load-library-directory {:path (str file) :env {} :home "/h"})
        result (only-diagnostic dir)]
    (is (= :unknown-provider (:type result)))
    (is (= :error (:level result)))
    (is (library/diagnostic? result))
    (is (= "reformed" (:library result)))
    (is (= "carrier-pigeon" (:provider result)))
    (is (= {:entry 2 :line 3} (select-keys result [:entry :line])))
    (is (str/starts-with? (:message result) (str file ":3: ")))
    (is (str/includes? (:message result) "registered: filesystem, memory"))
    (is (= ["bible"] (names dir)))
    (is (= :unavailable-library (:type (library/find-library dir "reformed"))))))

(deftest partial-failure-test
  (let [file (write! (temp-dir) "libraries.yaml"
                     (lines "- name: good"                ; 1
                            "  type: memory"
                            "- name: twice"               ; 3
                            "  type: memory"
                            "- name: pigeon"              ; 5
                            "  type: carrier-pigeon"
                            "- name: broken"              ; 7
                            "  type: memory"
                            "  books: 3"
                            "- name: twice"               ; 10
                            "  type: carrier-pigeon"
                            "- name: also-good"           ; 12
                            "  type: memory"))
        dir (directory/load-library-directory {:path (str file) :env {} :home "/h"})
        ds (diagnostics dir)]
    (testing "every working entry is a usable library, in directory order"
      (is (= ["good" "also-good"] (names dir)))
      (doseq [n ["good" "also-good"]]
        (is (satisfies? library/Library (library/find-library dir n)) n)))
    (testing "every failure is reported, not just the first, in entry order"
      (is (= [[:unknown-provider "pigeon" 3]
              [:invalid-library "broken" 4]
              [:duplicate-library "twice" 5]]
             (mapv (juxt :type :library :entry) ds))))
    (testing "each carries doc-10's level and its location"
      (is (every? #(= :error (:level %)) ds))
      (is (every? #(= (str file) (:file %)) ds))
      (is (every? library/diagnostic? ds)))
    (testing "a failed library is unavailable, naming it and its cause; an unnamed one is nil"
      (doseq [[n d] (map (juxt :library identity) ds)]
        (let [found (library/find-library dir n)]
          (is (= :unavailable-library (:type found)) n)
          (is (= n (:library found)))
          (is (= d (:cause found)))))
      (is (nil? (library/find-library dir "never-named")))))
  (testing "a directory with no failures has no diagnostics"
    (let [file (write! (temp-dir) "libraries.yaml" (lines "- name: a" "  type: memory"))]
      (is (= [] (diagnostics (directory/load-library-directory
                              {:path (str file) :env {} :home "/h"}))))))
  (testing "the empty directory (no file) has no diagnostics"
    (is (= [] (diagnostics (directory/load-library-directory
                            {:env {} :home (str (io/file (temp-dir) "nobody"))}))))))

(deftest hard-error-test
  (let [tmp (temp-dir)
        load-text (fn [text]
                    (let [file (write! tmp "libraries.yaml" text)]
                      [file (hard-error #(directory/load-library-directory
                                          {:path (str file) :env {} :home "/h"}))]))]
    (doseq [[label text line fragment]
            [["invalid YAML" (lines "- name: a" "  type: memory" "- name: [b" "  type: x") 4
              "invalid YAML"]
             ["a mapping, not a list" (lines "name: a" "type: memory") 1 "must be a YAML list"]
             ["a scalar" (lines "hello") 1 "must be a YAML list"]
             ["an entry that is not a map" (lines "- name: a" "  type: memory" "- just-a-name") 3
              "must be a map"]
             ["an entry with no name" (lines "- type: memory") 1 "has no name"]
             ["an invalid name" (lines "- name: Bad Name" "  type: memory") 1 "must match"]
             ["an entry with no type" (lines "- name: a" "- name: b" "  type: memory") 1
              "has no type"]
             ["a type that is not a string" (lines "- name: a" "  type: [x]") 1
              "type that is not a name"]
             ["an unsafe tag" (lines "- name: a" "  type: !!java.io.File x") 2 "invalid YAML"]
             ["a recursive alias inside an entry"
              (lines "- &a" "  name: a" "  type: memory" "  self: *a") 1 "invalid YAML"]
             ["a recursive alias at the top level" (lines "&x [*x]") 1 "invalid YAML"]
             ["a recursive list inside an entry"
              (lines "- name: a" "  type: memory" "  x: &b [*b]") 1 "invalid YAML"]
             ["a recursive map at the top level" (lines "&a {x: *a}") 1 "invalid YAML"]             ["a repeated key" (lines "- name: a" "  type: memory" "  name: b") 3 "duplicate key"]
             ["an empty path" (lines "- name: a" "  type: memory" "  path: ''") 1
              "has an empty path"]]]
      (testing label
        (let [[file err] (load-text text)]
          (is (= ::directory/invalid-library-directory (:type err)))
          (is (= line (:source-line err)))
          (is (= (str file) (:source-file err)))
          (is (str/starts-with? (:message err) (str file ":" line ": ")))
          (is (str/includes? (:message err) fragment))))))
  (testing "an unreadable file is a hard error"
    (let [tmp (temp-dir)
          err (hard-error #(directory/load-library-directory {:path (str tmp) :env {} :home "/h"}))]
      (is (= ::directory/unreadable-library-directory (:type err)))
      (is (= (str tmp) (:source-file err)))))
  (testing "a file that cannot be read is a hard error chaining the IOException"
    (let [file (write! (temp-dir) "libraries.yaml" (lines "- name: a" "  type: memory"))]
      (.setReadable file false)
      (if (.canRead file)
        (is true "running as a user who can read anything; the branch is not reachable here")
        (let [err (hard-error #(directory/load-library-directory
                                {:path (str file) :env {} :home "/h"}))]
          (is (= ::directory/unreadable-library-directory (:type err)))
          (is (instance? java.io.IOException (:cause err)))))))
  (testing "a YAML error chains snakeyaml's exception"
    (let [file (write! (temp-dir) "libraries.yaml" (lines "- name: [a"))
          err (hard-error #(directory/load-library-directory {:path (str file) :env {} :home "/h"}))]
      (is (instance? org.yaml.snakeyaml.error.YAMLException (:cause err)))))
  (testing "an empty or comment-only file is an empty directory"
    (doseq [text ["" (lines "# no libraries yet")]]
      (let [file (write! (temp-dir) "libraries.yaml" text)]
        (is (= [] (names (directory/load-library-directory
                          {:path (str file) :env {} :home "/h"}))))))))

(deftest provider-failure-test
  (let [tmp (temp-dir)
        run (fn [registry text]
              (let [dir (directory/load-library-directory
                         {:path (str (write! tmp "libraries.yaml" text)) :env {} :home "/h"
                          :registry registry})
                    result (only-diagnostic dir)]
                (is (= :error (:level result)))
                (is (= :unavailable-library
                       (:type (library/find-library dir (:library result)))))
                result))]
    (testing "a provider's own diagnostic is kept under :cause, located at its entry"
      (let [result (run directory/default-registry
                        (lines "- name: a" "  type: memory" "- name: b" "  type: memory" "  books: 3"))]
        (is (= :invalid-library (:type result)))
        (is (= :invalid-library (:type (:cause result))))
        (is (= {:entry 2 :line 3 :library "b"} (select-keys result [:entry :line :library])))
        (is (str/includes? (:message result) ":books must be a sequence"))))
    (testing "a provider that throws"
      (let [result (run {:boom (fn [_] (throw (ex-info "kaboom" {})))}
                        (lines "- name: a" "  type: boom"))]
        (is (= :invalid-library (:type result)))
        (is (str/includes? (:message result) "kaboom"))))
    (testing "a provider that answers something that is not a Library"
      (let [result (run {:odd (fn [_] 42)} (lines "- name: a" "  type: odd"))]
        (is (= :invalid-library (:type result)))
        (is (str/includes? (:message result) "not a Library"))))
    (testing "a provider that builds a library under another name"
      (let [result (run {:other (fn [_] (memory/memory-library {:name "other"}))}
                        (lines "- name: a" "  type: other"))]
        (is (= :invalid-library (:type result)))
        (is (str/includes? (:message result) "\"other\""))))))

;; --- AC #4: the registry -----------------------------------------------------

(deftest registry-test
  (testing ":memory is registered by default"
    (is (= memory/memory-library (:memory directory/default-registry))))
  (testing "registering a bad type or a non-fn provider throws"
    (doseq [bad-type [nil "" "  " 42 ["x"]]]
      (is (= ::directory/invalid-provider-type
             (:type (hard-error #(directory/register-provider {} bad-type memory/memory-library))))
          (pr-str bad-type)))
    (is (= ::directory/invalid-provider
           (:type (hard-error #(directory/register-provider {} :x "not a fn"))))))
  (testing "a custom type is registered and used"
    (let [seen (atom [])
          registry (directory/register-provider
                    directory/default-registry "shelfie"
                    (fn [config]
                      (swap! seen conj config)
                      (memory/memory-library
                       {:name (:name config)
                        :books [{:id {:library (:name config) :shelf ["s"] :slug "one"}
                                 :document (constantly {:meta {} :blocks []})}]})))
          file (write! (temp-dir) "libraries.yaml"
                       (lines "- name: mine" "  type: shelfie" "- name: plain" "  type: memory"))
          dir (directory/load-library-directory {:path (str file) :env {} :home "/h"
                                                 :registry registry})]
      (is (contains? registry :shelfie))
      (is (= ["mine" "plain"] (names dir)))
      (is (= [{:name "mine" :type :shelfie}] @seen))
      (is (some? (library/find-book (library/find-library dir "mine") "one"))))))

;; --- AC #5: keys carried through ---------------------------------------------

(deftest keys-carried-through-test
  (let [seen (atom [])
        file (write! (temp-dir) "libraries.yaml"
                     (lines "- name: bible"
                            "  type: memory"
                            "  url: \"https://example.org/{library}/{slug}.html#{label}\""
                            "  scripts: true"
                            "  extra:"
                            "    nested: [1, two]"))
        _ (directory/load-library-directory {:path (str file) :env {} :home "/h"
                                             :registry (recording-registry seen)})]
    (is (= [{:name "bible"
             :type :memory
             :url "https://example.org/{library}/{slug}.html#{label}"
             :scripts true
             :extra {:nested [1 "two"]}}]
           @seen))))

;; --- TASK-155: url templates -------------------------------------------------

(deftest url-template-test
  (let [tmp (temp-dir)
        load! (fn [text]
                (directory/load-library-directory
                 {:path (str (write! tmp "libraries.yaml" text)) :env {} :home "/h"}))]
    (testing "the directory answers each entry's template, and nil for one without"
      (let [dir (load! (lines "- name: bible"
                              "  type: memory"
                              "  url: \"https://example.org/{library}/{slug}.html#{label}\""
                              "- name: plain"
                              "  type: memory"
                              "- name: nulled"
                              "  type: memory"
                              "  url: null"))]
        (is (= [] (diagnostics dir)))
        (is (= ["bible" "plain" "nulled"] (names dir)))
        (is (= "https://example.org/{library}/{slug}.html#{label}" (library/url-template dir "bible")))
        (is (nil? (library/url-template dir "plain")))
        (is (nil? (library/url-template dir "nulled")) "a null url is no template")
        (is (nil? (library/url-template dir "nowhere")))))
    (testing "an invalid template costs only its own library, an invalid-library at its entry"
      (doseq [[url-yaml why] [["\"https://example.org/{slgu}\"" "unknown placeholder {slgu}"]
                              ["\"https://example.org/{slug\"" "not part of a placeholder"]
                              ["42" "must be a string"]
                              ["\"\"" "blank"]]]
        (let [dir (load! (lines "- name: good"
                                "  type: memory"
                                "  url: \"https://g.example/{slug}\""
                                "- name: bad"
                                "  type: memory"
                                (str "  url: " url-yaml)))
              d (only-diagnostic dir)]
          (is (= {:type :invalid-library :level :error :library "bad" :entry 2 :line 4}
                 (select-keys d [:type :level :library :entry :line])))
          (is (str/includes? (:message d) why))
          (is (str/starts-with? (:message d) (str (io/file tmp "libraries.yaml") ":4: ")))
          (is (= ["good"] (names dir)))
          (is (= "https://g.example/{slug}" (library/url-template dir "good")))
          (is (= :unavailable-library (:type (library/find-library dir "bad")))))))
    (testing "the provider is not even called for an entry whose template is invalid"
      (let [seen (atom [])]
        (directory/load-library-directory
         {:path (str (write! tmp "libraries.yaml"
                             (lines "- name: bad" "  type: memory" "  url: \"{nope}\"")))
          :env {} :home "/h" :registry (recording-registry seen)})
        (is (= [] @seen))))))
