(ns haselnuss.site-modes-test
  "The permissions of what `--build-site` writes (TASK-290): the files a
  web server shows get the mode the umask gives an ordinary new file, not
  0600; the build's own state stays owner-only. No existing file's mode is
  changed in place: a build with `--full` or no valid saved state replaces
  the outputs it produces with new files, and nothing else is touched --
  not a hand-placed private file, a link's target or a file hard-linked
  from outside the site. The JVM cannot change its umask, so the in-process
  tests read the one in force (a probe file) and the umask 077 / 002 ones
  run a real subprocess."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.cli :as cli]
            [haselnuss.library.digest :as digest]
            [haselnuss.library.state :as state]
            [haselnuss.site :as site]
            [haselnuss.site.unchanged :as unchanged])
  (:import (java.io File IOException OutputStream)
           (java.nio.file Files LinkOption)
           (java.nio.file.attribute BasicFileAttributes FileAttribute PosixFilePermissions)))

(def ^:private site-yaml "test/fixtures/site/libraries.yaml")

(def ^:private no-follow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(defn- posix? [] (.contains (.supportedFileAttributeViews (java.nio.file.FileSystems/getDefault)) "posix"))

(defn- temp-dir ^File []
  (.toFile (Files/createTempDirectory "haselnuss-site-modes" (make-array FileAttribute 0))))

(defn- mode
  "The permission string (`rw-r--r--`) of `f`, links not followed."
  [^File f]
  (PosixFilePermissions/toString (Files/getPosixFilePermissions (.toPath f) no-follow)))

(defn- set-mode! [^File f ^String m]
  (Files/setPosixFilePermissions (.toPath f) (PosixFilePermissions/fromString m)))

(defn- file-id
  "What identifies the file at `f` itself: a replaced file has a new one."
  [^File f]
  (.fileKey (Files/readAttributes (.toPath f) BasicFileAttributes
                                  ^"[Ljava.nio.file.LinkOption;" no-follow)))

(defn- ordinary-modes
  "`[file-mode dir-mode]` the umask in force gives an ordinary new file and
  folder, found by making one of each with no permissions asked for."
  [^File scratch]
  (let [f (Files/createFile (.toPath (io/file scratch "probe-file")) (make-array FileAttribute 0))
        d (Files/createDirectory (.toPath (io/file scratch "probe-dir")) (make-array FileAttribute 0))]
    [(mode (.toFile f)) (mode (.toFile d))]))

(defn- build!
  "Build the fixture set into `dir`, `flags` added."
  [dir & flags]
  (let [err (java.io.StringWriter.)
        out (java.io.StringWriter.)
        status (binding [*err* err *out* out]
                 (cli/main* (into ["--libraries" site-yaml "--build-site" (str dir)] flags)
                            {:library-env {} :library-home "/nonexistent/haselnuss-test-home"
                             :haselnuss.cli/check-base-dir (System/getProperty "user.dir")
                             :haselnuss.cli/site-threads 2}))]
    ;; 1: the fixture set has warnings and a duplicate id
    (is (= 1 status) (str err))))

(defn- rel [^File root ^File f] (str/replace (str (.relativize (.toPath root) (.toPath f))) File/separator "/"))

(defn- classify
  "`{:public [files] :private [files] :dirs [folders]}` below `root`: the
  private ones are under `.haselnuss/` (the lock file excepted -- a
  pre-existing, umask-made file), `readers/` and the like."
  [^File root]
  (let [all (remove #(= root %) (file-seq root))
        rels (map (juxt identity #(rel root %)) all)
        private? (fn [r] (str/starts-with? r ".haselnuss"))]
    {:public (for [[^File f r] rels :when (and (.isFile f) (not (private? r)))] [f r])
     :private (for [[^File f r] rels :when (and (.isFile f) (private? r) (not= r ".haselnuss/lock"))] [f r])
     :dirs (for [[^File f r] rels :when (.isDirectory f)] [f r])}))

(defn- group-other-bits? [^String m] (not= "------" (subs m 3)))

(defn- narrow!
  "Make every public file of the site `dir` 0600, as a build before
  TASK-290 left it."
  [dir]
  (doseq [[f] (:public (classify dir))] (set-mode! f "rw-------")))

(defn- edit-saved-header!
  "Apply `f` to the header of the saved state of the site `dir`."
  [dir f]
  (let [header (io/file (state/state-dir dir) state/header-name)]
    (spit header (pr-str (f (edn/read-string (slurp header)))))))

(defn- as-previous-format!
  "Make the saved state of the site `dir` one of the format before this
  build's, as a site an earlier Haselnuss built has."
  [dir]
  (edit-saved-header! dir #(assoc % :format (dec state/format-version))))

(defn- as-other-version!
  "Make the saved state of the site `dir` one another Haselnuss version
  saved, in this build's format."
  [dir]
  (edit-saved-header! dir #(assoc % :haselnuss "0.0.0-other")))

(defn- answered-from-record?
  "Build `dir`; true when the run answered from the record of an unchanged
  site (`haselnuss.site.unchanged/examine`, the no-change path)."
  [dir]
  (let [hit (atom false)
        examine unchanged/examine]
    (with-redefs [unchanged/examine (fn [& args]
                                      (let [r (apply examine args)]
                                        (when (:hit r) (reset! hit true))
                                        r))]
      (build! dir))
    @hit))

(defn- planted-file!
  "A file of `text` at `f`, with the permissions `m`."
  ^File [^File f text m]
  (io/make-parents f)
  (spit f text)
  (set-mode! f m)
  f)

(defn- snapshot
  "The mode and content of each of `files`."
  [files]
  (into {} (map (fn [^File f] [(str f) [(mode f) (slurp f)]])) files))

(defn- plant!
  "Plant in the built site `dir` what the adversarial review used, with
  `outside` a folder outside the site. Answers `:hand` (private files put
  there by hand), `:outside` (files outside the site that must not change),
  `:symlink`, `:hard-link` and `:outside-secret` (links at names the build
  does not produce) and `:linked-outputs` (outputs that are now a link to
  an outside file holding the same bytes, so only a forced write replaces
  them)."
  [^File dir ^File outside]
  (let [hand (into [(planted-file! (io/file dir "key.pem") "a read-only key" "r--------")]
                   (map #(planted-file! (io/file dir %) (str "private " %) "rw-------"))
                   [".env" ".htpasswd" "id_ed25519" "backup.sql" "credentials.json"
                    "books/id_ed25519" "books/backup.sql" "catalog/backup.sql" "catalog/credentials.json"])
        secret (planted-file! (io/file outside "secret") "outside secret" "rw-------")
        link-target (planted-file! (io/file outside "link-target") "link target" "rw-------")
        symlink (io/file dir "books" "symlink")
        hard-link (io/file dir "books" "hardlink")
        hard-output (io/file dir "app.js")
        hard-outside (planted-file! (io/file outside "app-copy") (slurp hard-output) "rw-------")
        sym-output (io/file dir "catalog" "index.json")
        sym-outside (planted-file! (io/file outside "catalog-copy") (slurp sym-output) "rw-------")]
    (Files/createSymbolicLink (.toPath symlink) (.toPath link-target) (make-array FileAttribute 0))
    (Files/createLink (.toPath hard-link) (.toPath secret))
    (Files/delete (.toPath hard-output))
    (Files/createLink (.toPath hard-output) (.toPath hard-outside))
    (Files/delete (.toPath sym-output))
    (Files/createSymbolicLink (.toPath sym-output) (.toPath sym-outside) (make-array FileAttribute 0))
    {:hand hand
     :outside [secret link-target hard-outside sym-outside]
     :symlink symlink
     :hard-link hard-link
     :outside-secret secret
     :linked-outputs [[hard-output hard-outside] [sym-output sym-outside]]}))

(defn- check-planted
  "Everything `plant!` put there, against `before` (`snapshot`)."
  [{:keys [hand outside symlink hard-link outside-secret linked-outputs]} before]
  (testing "hand-placed files keep their mode and content"
    (is (= 10 (count hand)) "not vacuous")
    (is (= (select-keys before (map str hand)) (snapshot hand))))
  (testing "files outside the site keep their mode and content"
    (is (= (select-keys before (map str outside)) (snapshot outside))))
  (testing "links at names the build does not produce are left as they are"
    (is (Files/isSymbolicLink (.toPath ^File symlink)))
    (is (Files/isSameFile (.toPath ^File hard-link) (.toPath ^File outside-secret))))
  (testing "an output that was a link is now a file of its own"
    (doseq [[^File output ^File target] linked-outputs]
      (is (Files/isRegularFile (.toPath output) no-follow) (str output))
      (is (not (Files/isSameFile (.toPath output) (.toPath target))) (str output)))))

(deftest build-writes-public-files-with-the-umask-mode-test
  (when (posix?)
    (let [dir (temp-dir)
          [file-mode dir-mode] (ordinary-modes (temp-dir))]
      (build! dir)
      (let [{:keys [public private dirs]} (classify dir)
            rels (set (map second public))]
        (testing "every kind of public output was written"
          (is (contains? rels "index.html"))
          (is (contains? rels "app.js"))
          (is (contains? rels "sw.js"))
          (is (some #(str/starts-with? % "catalog/") rels))
          (is (some #(str/ends-with? % "/backlinks.json") rels))
          (is (some #(str/ends-with? % "/previews.json") rels))
          (is (some #(str/ends-with? % "catkins.png") rels))
          (is (some #(str/ends-with? % "/index.html") rels)))
        (testing "public files have the umask's mode"
          (doseq [[f r] public]
            (is (= file-mode (mode f)) r)))
        (testing "folders the build made follow the umask"
          (doseq [[f r] dirs]
            (is (= dir-mode (mode f)) r)))
        (testing "the build's state is as private as before"
          (is (seq private))
          (doseq [[f r] private]
            (is (not (group-other-bits? (mode f))) (str r " " (mode f)))))))))

(deftest upgrade-replaces-only-the-outputs-of-a-0600-site-test
  (when (posix?)
    (let [dir (temp-dir)
          outside (temp-dir)
          [file-mode] (ordinary-modes (temp-dir))]
      (build! dir)
      (narrow! dir)
      (as-previous-format! dir)
      (let [outputs (mapv first (:public (classify dir)))
            private-before (into {} (map (fn [[f r]] [r (mode f)])) (:private (classify dir)))
            ids-before (into {} (map (juxt str file-id)) outputs)
            planted (plant! dir outside)
            before (snapshot (concat (:hand planted) (:outside planted)))]
        (is (< 20 (count outputs)) "not vacuous")
        (build! dir)
        (testing "every output is a new file with the umask's mode"
          (doseq [^File f outputs]
            (is (= file-mode (mode f)) (str f))
            (is (not= (get ids-before (str f)) (file-id f)) (str f " was replaced"))))
        (check-planted planted before)
        (testing "the build's own files stay private"
          (doseq [[f r] (:private (classify dir))
                  :let [was (get private-before r)]]
            (is (not (group-other-bits? (mode f))) r)
            (when was (is (= was (mode f)) r))))))))

(deftest full-replaces-outputs-and-nothing-else-test
  (when (posix?)
    (let [dir (temp-dir)
          outside (temp-dir)
          [file-mode] (ordinary-modes (temp-dir))]
      (build! dir)
      (narrow! dir)
      (let [outputs (mapv first (:public (classify dir)))
            ids-before (into {} (map (juxt str file-id)) outputs)
            planted (plant! dir outside)
            before (snapshot (concat (:hand planted) (:outside planted)))]
        (build! dir "--full")
        (doseq [^File f outputs]
          (is (= file-mode (mode f)) (str f))
          (is (not= (get ids-before (str f)) (file-id f)) (str f " was replaced")))
        (check-planted planted before)))))

(deftest incremental-build-writes-nothing-unchanged-test
  (when (posix?)
    (let [dir (temp-dir)]
      (build! dir)
      (build! dir)
      (narrow! dir)
      (let [outputs (mapv first (:public (classify dir)))
            ids (into {} (map (juxt str file-id)) outputs)
            times (into {} (map (fn [^File f] [(str f) (.lastModified f)])) outputs)]
        (is (< 20 (count outputs)) "not vacuous")
        (build! dir)
        (doseq [^File f outputs]
          (testing (str f)
            (is (= "rw-------" (mode f)) "no mode is changed in place")
            (is (= (get ids (str f)) (file-id f)) "the file is not replaced")
            (is (= (get times (str f)) (.lastModified f)))))))))

(deftest no-change-path-misses-after-the-format-bump-test
  (when (posix?)
    (with-redefs [digest/racy-window-ms 0]
      (let [dir (temp-dir)
            [file-mode] (ordinary-modes (temp-dir))
            settled? (fn [] (some identity (repeatedly 4 #(answered-from-record? dir))))]
        (build! dir)
        (narrow! dir)
        (is (settled?) "the no-change path answers before the format changes")
        (testing "the no-change path changes no mode"
          (doseq [[f r] (:public (classify dir))]
            (is (= "rw-------" (mode f)) r)))
        (let [outputs (mapv first (:public (classify dir)))
              ids (into {} (map (juxt str file-id)) outputs)]
          (as-previous-format! dir)
          (is (not (answered-from-record? dir)) "a state of the previous format is not answered from")
          (doseq [^File f outputs]
            (is (= file-mode (mode f)) (str f))
            (is (not= (get ids (str f)) (file-id f)) (str f " was replaced"))))))))

(deftest version-change-alone-keeps-unchanged-outputs-test
  (when (posix?)
    (let [[file-mode] (ordinary-modes (temp-dir))
          ;; what a full build emits again: pages and copied images
          emitted? (fn [^String r] (and (str/starts-with? r "books/")
                                        (not (str/ends-with? r "/backlinks.json"))
                                        (not (str/ends-with? r "/previews.json"))))]
      (testing "another Haselnuss version, same format"
        (let [dir (temp-dir)
              _ (build! dir)
              _ (narrow! dir)
              _ (as-other-version! dir)
              public (vec (:public (classify dir)))
              kept (filterv (comp not emitted? second) public)
              ids (into {} (map (fn [[f r]] [r (file-id f)])) public)
              times (into {} (map (fn [[^File f r]] [r (.lastModified f)])) public)]
          (is (< 20 (count kept)) "not vacuous")
          (is (< (count kept) (count public)) "not vacuous")
          (build! dir)
          (testing "app files, catalog and data files with the same bytes are not written"
            (doseq [[^File f r] kept]
              (is (= (get ids r) (file-id f)) r)
              (is (= (get times r) (.lastModified f)) r)
              (is (= "rw-------" (mode f)) r)))
          (testing "the pages and images it emitted are new files"
            (doseq [[f r] (remove (comp not emitted? second) public)]
              (is (not= (get ids r) (file-id f)) r)
              (is (= file-mode (mode f)) r)))
          (testing "--full still replaces everything"
            (as-other-version! dir)
            (build! dir "--full")
            (doseq [[f r] kept]
              (is (not= (get ids r) (file-id f)) r)
              (is (= file-mode (mode f)) r)))))
      (testing "a format-9-style migration from another version still replaces everything"
        (let [dir (temp-dir)
              _ (build! dir)
              _ (narrow! dir)
              _ (edit-saved-header! dir #(assoc % :format (dec state/format-version)
                                                :haselnuss "0.0.0-other"))
              public (vec (:public (classify dir)))
              ids (into {} (map (fn [[f r]] [r (file-id f)])) public)]
          (is (< 20 (count public)) "not vacuous")
          (build! dir)
          (doseq [[f r] public]
            (is (not= (get ids r) (file-id f)) r)
            (is (= file-mode (mode f)) r)))))))

(deftest folder-holding-only-a-state-folder-test
  (when (posix?)
    (let [dir (temp-dir)
          [file-mode] (ordinary-modes (temp-dir))
          _ (.mkdirs (state/state-dir dir))
          hand (mapv #(planted-file! (io/file dir %) (str "private " %) "rw-------")
                     [".env" "id_ed25519" "backup.sql"])
          before (snapshot hand)]
      (build! dir)
      (is (= before (snapshot hand)) "what was in the folder is not touched")
      (let [outputs (remove (set (map str hand)) (map (comp str first) (:public (classify dir))))]
        (is (seq outputs) "not vacuous")
        (doseq [f outputs]
          (is (= file-mode (mode (io/file f))) f))))))

(defn- temp-of
  "The one temporary file of `target` in its folder."
  ^File [^File target]
  (let [prefix (str "." (.getName target) ".")
        found (filterv #(and (str/starts-with? (.getName ^File %) prefix)
                             (str/ends-with? (.getName ^File %) ".tmp"))
                       (.listFiles (.getParentFile target)))]
    (assert (= 1 (count found)) (str "temporary files: " found))
    (first found)))

(deftest write-atomically-never-reopens-its-temporary-file-test
  (when (posix?)
    (let [write! @#'site/write-atomically!
          dir (temp-dir)
          outside (planted-file! (io/file (temp-dir) "outside") "outside" "rw-------")
          target (planted-file! (io/file dir "page.html") "old page" "rw-r--r--")
          temps #(filterv (fn [^File f] (str/ends-with? (.getName f) ".tmp")) (.listFiles dir))]
      (testing "a symbolic link swapped in while the file is written"
        (let [swapped (atom false)]
          (is (thrown? IOException
                       (write! target
                               (fn [^OutputStream out]
                                 (let [tmp (temp-of target)]
                                   (Files/delete (.toPath tmp))
                                   (Files/createSymbolicLink (.toPath tmp) (.toPath outside)
                                                             (make-array FileAttribute 0))
                                   (reset! swapped true))
                                 (.write out (.getBytes "the new page" "UTF-8")))
                               true)))
          (is @swapped "not vacuous")
          (is (= ["rw-------" "outside"] [(mode outside) (slurp outside)]) "the outside file is not written")
          (is (Files/isRegularFile (.toPath target) no-follow) "the destination does not become a link")
          (is (= "old page" (slurp target)))
          (is (empty? (temps)) "the temporary name is cleaned up")))
      (testing "a regular file swapped in is not renamed into place"
        (is (thrown? IOException
                     (write! target
                             (fn [^OutputStream out]
                               (let [tmp (temp-of target)]
                                 (Files/delete (.toPath tmp))
                                 (spit tmp "planted"))
                               (.write out (.getBytes "the new page" "UTF-8")))
                             true)))
        (is (= "old page" (slurp target)))
        (is (empty? (temps))))
      (testing "a failed write leaves the target and no temporary file"
        (is (thrown? IllegalStateException
                     (write! target (fn [_] (throw (IllegalStateException. "failed"))) true)))
        (is (= "old page" (slurp target)))
        (is (empty? (temps))))
      (testing "bytes and streamed content are written, public and private"
        (write! target (.getBytes "bytes" "UTF-8") true)
        (is (= "bytes" (slurp target)))
        (write! target (fn [^OutputStream out] (.write out (.getBytes "streamed" "UTF-8"))) false)
        (is (= "streamed" (slurp target)))
        (is (= "rw-------" (mode target)) "private")
        (is (empty? (temps)))))))

(def ^:private subprocess-script
  "(require 'haselnuss.cli)
   (System/exit (haselnuss.cli/main* (into [\"--libraries\" \"test/fixtures/site/libraries.yaml\" \"--build-site\" (System/getProperty \"t.site\")]
                                           (when (System/getProperty \"t.full\") [\"--full\"]))
                                     {:library-env {} :library-home \"/nonexistent/haselnuss-test-home\"}))")

(defn- build-under-umask!
  "Build the fixture set into `dir` in a new JVM started under `umask`,
  with `--full` when `full?`."
  ([umask dir] (build-under-umask! umask dir false))
  ([umask ^File dir full?]
   (let [java (str (System/getProperty "java.home") "/bin/java")
         cmd (into ["sh" "-c" (str "umask " umask " && exec \"$0\" \"$@\"")
                    java "-cp" (System/getProperty "java.class.path") (str "-Dt.site=" dir)]
                   (concat (when full? ["-Dt.full=1"])
                           ["clojure.main" "-e" subprocess-script]))
         p (.start (doto (ProcessBuilder. ^java.util.List cmd) (.redirectErrorStream true)))
         out (future (slurp (.getInputStream p)))]
     (if (.waitFor p 240 java.util.concurrent.TimeUnit/SECONDS)
       (is (= 1 (.exitValue p)) @out)
       (do (.destroyForcibly p) (is false "the build JVM did not finish"))))))

(deftest ^:slow build-under-restrictive-and-permissive-umasks-test
  (when (posix?)
    (doseq [[umask files-want dirs-want] [["077" "rw-------" "rwx------"]
                                          ["002" "rw-rw-r--" "rwxrwxr-x"]
                                          ["022" "rw-r--r--" "rwxr-xr-x"]]]
      (testing (str "umask " umask)
        (let [dir (temp-dir)]
          (build-under-umask! umask dir)
          (let [{:keys [public private dirs]} (classify dir)]
            (is (seq public))
            (doseq [[f r] public] (is (= files-want (mode f)) r))
            (doseq [[f r] dirs] (is (= dirs-want (mode f)) r))
            (doseq [[f r] private] (is (not (group-other-bits? (mode f))) r))))))
    (testing "a build under 077, then one under 022: --full gives the ordinary modes"
      (let [dir (io/file (temp-dir) "site")]
        (build-under-umask! "077" dir)
        (build-under-umask! "022" dir)
        (testing "an ordinary build changes no mode"
          (doseq [[f r] (:public (classify dir))] (is (= "rw-------" (mode f)) r)))
        (build-under-umask! "022" dir true)
        (let [{:keys [public private dirs]} (classify dir)]
          (is (seq public))
          (doseq [[f r] public] (is (= "rw-r--r--" (mode f)) r))
          (doseq [[f r] private] (is (not (group-other-bits? (mode f))) r))
          (testing "folders keep the mode they were made with"
            (doseq [[f r] dirs] (is (= "rwx------" (mode f)) r))))))))
