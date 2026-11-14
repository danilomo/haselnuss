(ns haselnuss.native-registry-test
  "Every source namespace has its `__init` class registered for the native
  image (TASK-251).

  The shipped binary is AOT-compiled, and requiring a namespace there means
  `clojure.lang.RT/load` asking for the class `<ns>__init` by name. A
  closed-world GraalVM image only answers that lookup for classes listed in
  `resources/META-INF/native-image/haselnuss/haselnuss/reflect-config.json`,
  so a namespace added under `src/` without its entry makes `dist/haselnuss`
  die at startup. `haselnuss.native-test` does catch that, but only after a
  ~2.5 min `bin/native` rebuild, and it skips entirely when no binary is
  present (TASK-244 hit exactly this with `haselnuss.library.digest`). This
  is the seconds-long JVM-side check for the one class of entry that is
  mechanical to derive; the TASK-176 `javap` scan of the uberjar remains the
  way to find the rest (imported classes, protocol interfaces, ...).

  Scope, deliberately:
  - Namespaces are enumerated from the `ns` forms of the files under `src/`
    (`.clj` and `.cljc`), not from what happens to be loaded in this JVM, so
    a namespace nothing in the test suite requires is still covered.
  - Every namespace under `src/` is expected to be registered: `src/` is
    exactly what `:aot :all` compiles into the shipped uberjar, which the
    native image is built from. There are no exceptions today; if one is
    ever needed, add it to `intentionally-unregistered` with the reason.
  - `dev/` is not checked. `haselnuss.soak` lives there precisely so that
    the shipped image never contains it (TASK-245); the soak image carries
    its own config under `dev/META-INF/native-image/`."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.io File PushbackReader)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:private reflect-config
  "The shipped image's reflection registry, relative to the repository root
  (the working directory `lein test` runs in)."
  "resources/META-INF/native-image/haselnuss/haselnuss/reflect-config.json")

(def ^:private intentionally-unregistered
  "Namespaces under `src/` that are deliberately absent from the native
  image, each with the reason. Empty: everything in `src/` ships."
  #{})

(defn- clojure-source? [^File f]
  (and (.isFile f)
       (let [n (.getName f)]
         (or (str/ends-with? n ".clj") (str/ends-with? n ".cljc")))))

(defn- file-ns
  "The namespace symbol a source file declares with its leading `ns` form.
  Throws when the first form is not one, so a file that cannot be
  attributed fails the test loudly instead of being skipped silently."
  [^File f]
  (with-open [r (PushbackReader. (io/reader f))]
    (let [form (read {:read-cond :allow :features #{:clj}} r)]
      (if (and (seq? form) (= 'ns (first form)) (symbol? (second form)))
        (second form)
        (throw (ex-info (str f " does not start with an (ns ...) form")
                        {:file (str f) :form form}))))))

(defn- source-namespaces
  "Every namespace declared by a `.clj`/`.cljc` file under `src-dir`."
  [src-dir]
  (->> (file-seq (io/file src-dir))
       (filter clojure-source?)
       (map file-ns)
       set))

(defn- init-class
  "The class `RT/load` resolves by name for namespace `ns-sym`.

  `clojure.core/root-resource` turns the namespace into a resource path by
  replacing `-` with `_` and `.` with `/`, and `RT/load` turns that path
  back into a class name by replacing `/` with `.` and appending `__init`.
  Net effect: hyphens become underscores, nothing else changes --
  `haselnuss.library.graph.memory-index` ->
  `haselnuss.library.graph.memory_index__init`."
  [ns-sym]
  (str (str/replace (name ns-sym) \- \_) "__init"))

(defn- registered-classes [config-file]
  (with-open [r (io/reader config-file)]
    (set (keep #(get % "name") (json/read r)))))

(defn- registry-gaps
  "The namespaces under `src-dir`, sorted, whose `__init` class is not named
  in the reflect-config at `config-file`."
  [src-dir config-file]
  (let [registered (registered-classes config-file)]
    (->> (source-namespaces src-dir)
         (remove intentionally-unregistered)
         (remove #(contains? registered (init-class %)))
         sort
         vec)))

(deftest init-class-munging-test
  (is (= "haselnuss.cli__init" (init-class 'haselnuss.cli)))
  (is (= "haselnuss.library.graph.memory_index__init"
         (init-class 'haselnuss.library.graph.memory-index)))
  (is (= "haselnuss.extensions.small_collapsable__init"
         (init-class 'haselnuss.extensions.small-collapsable))))

(deftest every-source-namespace-is-registered-test
  (let [namespaces (source-namespaces "src")]
    (testing "the enumeration is not vacuous"
      ;; 43 namespaces when this test was written; a broken walk that finds
      ;; nothing must not pass as "nothing is missing".
      (is (<= 43 (count namespaces)))
      (is (contains? namespaces 'haselnuss.cli))
      (is (contains? namespaces 'haselnuss.library.graph.memory-index)))
    (testing "every namespace's __init class is in reflect-config.json"
      (let [gaps (registry-gaps "src" reflect-config)]
        (is (empty? gaps)
            (str "Namespaces under src/ with no __init entry in "
                 reflect-config ": "
                 (str/join ", " (map #(str % " (" (init-class %) ")") gaps))
                 ". Add each as a bare {\"name\": ...} entry in sorted order,"
                 " or the native binary dies when it loads them."))))))

(deftest a-missing-entry-is-reported-test
  ;; The check above, against a copy of the real config with one namespace's
  ;; entry removed: it must report exactly that namespace.
  (let [dropped "haselnuss.library.graph.memory_index__init"
        entries (with-open [r (io/reader reflect-config)] (json/read r))
        tmp (.toFile (Files/createTempFile "reflect-config" ".json"
                                           (make-array FileAttribute 0)))]
    (try
      (is (some #(= dropped (get % "name")) entries)
          "precondition: the entry being dropped is registered")
      (spit tmp (json/write-str (remove #(= dropped (get % "name")) entries)))
      (is (= ['haselnuss.library.graph.memory-index]
             (registry-gaps "src" tmp)))
      (finally (.delete tmp)))))
