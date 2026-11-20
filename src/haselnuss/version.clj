(ns haselnuss.version
  "This build's own version (TASK-46; moved here from `haselnuss.cli` by
  TASK-265, so the saved build state can record it without depending on
  the CLI). Never a literal in source: the point is telling *which
  build* a jar on disk is, and a duplicated string would drift from
  `project.clj` the first time the version changed.

  Two sources, in order:

  - `META-INF/maven/haselnuss/haselnuss/pom.properties`, which
    Leiningen generates from `project.clj` and packs into the jar. This
    is the one that matters, since `--version` exists for the packaged
    artifact (`:uberjar-name` deliberately carries no version, so the
    filename cannot answer the question either). The full
    groupId/artifactId path is spelled out because an uberjar contains
    a `pom.properties` for every dependency. The native image carries
    it too (its `resource-config.json`).
  - `project.clj` in the working directory, for a source checkout,
    where the jar resource does not exist. Guarded on the file really
    being *this* project's `defproject` form, so running from an
    unrelated project directory reports `unknown` rather than someone
    else's version.

  That second branch reads a file chosen by the *caller's* working
  directory, so it is read defensively (found by review):
  `*read-eval*` is bound off, since it is on by default and a
  `#=(...)` form in a stray `project.clj` would otherwise be evaluated
  before the `defproject` guard ever sees the form; and the whole read,
  including pulling the version out of the form, is inside the `try`,
  so a truncated `(defproject haselnuss)` yields `unknown` rather than
  an IndexOutOfBoundsException surfacing as a build error.
  `clojure.edn/read-string` would be the obvious alternative and is
  not usable: it rejects the `~` unquote forms a `project.clj` may
  legitimately contain.

  Delayed only so a tool that mostly does not print its version does
  not pay the I/O on every startup. It is *not* an AOT workaround: a
  top-level initializer is re-run when the class loads, not frozen at
  compile time."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def unknown
  "The version answered when neither source names one."
  "unknown")

(def ^:private version*
  (delay
    (or (when-let [resource (io/resource "META-INF/maven/haselnuss/haselnuss/pom.properties")]
          (with-open [in (io/input-stream resource)]
            (.getProperty (doto (java.util.Properties.) (.load in)) "version")))
        (let [file (io/file "project.clj")]
          (when (.isFile file)
            (try
              (let [form (binding [*read-eval* false] (read-string (slurp file)))]
                (when (and (seq? form)
                           (= 'defproject (first form))
                           (= 'haselnuss (second form)))
                  (some-> (nth form 2 nil) str)))
              (catch Exception _ nil))))
        unknown)))

(defn version
  "This build's version string, or `unknown` (see the namespace
  docstring)."
  ^String []
  @version*)

;; --- build identity (TASK-296) -----------------------------------------------------

(def ^:private ^"[Ljava.nio.file.LinkOption;" no-options
  (make-array java.nio.file.LinkOption 0))

(defn- file-stamp-line
  "`path size mtime-ns file-key` of the regular file `p` (followed through
  links, by its real path), or nil when it is none."
  [^java.nio.file.Path p]
  (try
    (let [real (.toRealPath p no-options)
          attrs (java.nio.file.Files/readAttributes real java.nio.file.attribute.BasicFileAttributes no-options)]
      (when (.isRegularFile attrs)
        (str real " " (.size attrs) " "
             (.to (.lastModifiedTime attrs) java.util.concurrent.TimeUnit/NANOSECONDS) " "
             (.fileKey attrs))))
    (catch Exception _ nil)))

(defn- tree-lines
  "`file-stamp-line` of every regular file below the folder `dir`, sorted."
  [^java.io.File dir]
  (sort (keep (fn [^java.io.File f] (when (.isFile f) (file-stamp-line (.toPath f))))
              (file-seq dir))))

(defn- running-executable
  "The path of the running executable (`/proc/self/exe`, else what the
  process handle says), or nil."
  []
  (or (try (str (.toRealPath (.toPath (io/file "/proc/self/exe")) no-options))
           (catch Exception _ nil))
      (try (let [^java.util.Optional c (.command (.info (java.lang.ProcessHandle/current)))]
             (when (.isPresent c) (str (.get c))))
           (catch Exception _ nil))))

(defn build-identity
  "What tells this build of Haselnuss from any other with the same
  `version` -- every development build is `0.1.0-SNAPSHOT` -- as a hex
  digest, or nil when it cannot be told. A site build saves it in the
  record of an unchanged site (`haselnuss.site.unchanged`), which a run
  of another build must not trust: its check report, summary or pages
  may differ.

  It is the stamp (path, size, modification time, file identity; no
  read) of the code that runs: in the native image the executable;
  on the JVM each classpath entry -- a jar or other file (the uberjar,
  every dependency), and for a folder (`lein run`, `lein test`, a REPL)
  every file below its `haselnuss/`, so editing a source changes it.
  `opts` (for tests): `:native?`, `:class-path`, `:executable`; by
  default this process's (`org.graalvm.nativeimage.imagecode` set means
  the native image)."
  ([] (build-identity {:native? (some? (System/getProperty "org.graalvm.nativeimage.imagecode"))
                       :class-path (System/getProperty "java.class.path")
                       :executable (running-executable)}))
  ([{:keys [native? class-path executable]}]
   (let [lines (if native?
                 (when executable
                   (some-> (file-stamp-line (.toPath (io/file executable))) (vector)))
                 (into []
                       (mapcat (fn [^String entry]
                                 (let [f (io/file entry)]
                                   (cond
                                     (.isDirectory f) (tree-lines (io/file f "haselnuss"))
                                     (.isFile f) (keep identity [(file-stamp-line (.toPath f))])
                                     :else [(str entry " missing")]))))
                       (remove str/blank? (str/split (str class-path) (re-pattern (java.util.regex.Pattern/quote java.io.File/pathSeparator))))))]
     (when (seq lines)
       (let [md (java.security.MessageDigest/getInstance "SHA-256")]
         (doseq [^String l lines] (.update md (.getBytes (str l "\n") "UTF-8")))
         (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md))))))))

(def ^:private running-identity
  (delay (build-identity)))

(defn running-build-identity
  "`build-identity` of this process, taken once, on first use: the code
  that runs does not change while it runs, whatever is written over its
  jar or executable meanwhile -- a record must name the build that made
  it, not the one on disk when it was saved (TASK-296 review)."
  []
  @running-identity)
