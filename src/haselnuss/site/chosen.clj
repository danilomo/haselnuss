(ns haselnuss.site.chosen
  "The chosen books of a partial site (doc-13, section 5; TASK-330):
  `.haselnuss/chosen`, whose presence is what makes a site partial. A
  partial site has the layout of a full one (doc-12, \"The folder\") but
  pages only for the books on this list: `--build-doc` creates one on an
  empty or absent folder and adds the books it builds, `--build-site`
  keeps those books, and only those, up to date, and `--build-site
  --all-books` makes the site full and removes the file.

  ## Format

  UTF-8 text, written atomically (`haselnuss.library.state/
  write-atomically!`):

      haselnuss-chosen 1
      {:haselnuss \"<version>\" :inputs {<input> <digest> ...}}
      bible:blivre/romanos
      reformed:catecismo-heidelberg

  A format line; the header, one line of EDN: the Haselnuss version and
  the input digests (`haselnuss.cli`'s `site-inputs`, the very map the
  saved state's header holds) the site was last built with -- what
  `--build-doc` compares when the site has no saved state yet; then one
  canonical book id per line, sorted, each once. A line that is not a
  book id is reported by the reader (`:bad`) and left out when the file
  is written again.

  Plain maps only, no records and no reflection: the native image needs
  this namespace's `__init` class and nothing else."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [haselnuss.book-ref :as book-ref]
            [haselnuss.library :as library]
            [haselnuss.library.digest :as digest]
            [haselnuss.library.state :as state]
            [haselnuss.version :as version])
  (:import (java.io File Writer)
           (java.nio.file Files LinkOption)))

(def chosen-rel
  "The chosen list's path relative to the site."
  ".haselnuss/chosen")

(def ^:private format-line "haselnuss-chosen 1")

(def ^:private no-follow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(defn chosen-file
  "The chosen list of the site `dir`."
  ^File [dir]
  (io/file dir chosen-rel))

(defn partial-site?
  "True when the site `dir` is a partial site: something is there under
  the chosen list's name (a symbolic link included, which is never read
  through, so such a site cannot be built until it is fixed)."
  [dir]
  (not (Files/notExists (.toPath (chosen-file dir)) no-follow)))

(defn- valid-id?
  "True when `s` is a canonical book id text: a whole book, written as
  `format-book-id` writes it."
  [^String s]
  (let [parsed (book-ref/parse-book-ref s)]
    (and (not (book-ref/malformed? parsed))
         (nil? (:passage parsed))
         (library/valid-book-id? (:id parsed))
         (= s (book-ref/format-book-id (:id parsed))))))

(defn read-chosen
  "The chosen list of the site `dir`: `{:header {:haselnuss :inputs} :ids
  [BookId ...] :bad [line ...]}` -- `:ids` in the file's order, each once,
  `:bad` the lines that are not a canonical book id -- or `{:unreadable
  message}` when it is not a regular file (a symbolic link is not
  followed), cannot be read, or does not start with this format's line
  and a header; nil when there is none."
  [dir]
  (let [f (chosen-file dir)
        p (.toPath f)]
    (cond
      (Files/notExists p no-follow) nil
      (not (Files/isRegularFile p no-follow))
      {:unreadable (str chosen-rel " is not a regular file")}
      :else
      (try
        (let [[first-line header-line & lines] (str/split-lines (slurp f :encoding "UTF-8"))
              header (when (= format-line first-line)
                       (try (edn/read-string header-line) (catch Exception _ nil)))]
          (if-not (and (map? header) (string? (:haselnuss header)) (map? (:inputs header)))
            {:unreadable (str chosen-rel " does not start with a " (pr-str format-line) " line and a header")}
            (let [lines (remove str/blank? (map str/trim lines))
                  {good true bad false} (group-by valid-id? lines)]
              {:header (select-keys header [:haselnuss :inputs])
               :ids (mapv (comp :id book-ref/parse-book-ref) (distinct good))
               :bad (vec bad)})))
        (catch Exception e
          {:unreadable (str chosen-rel " cannot be read: " (or (ex-message e) (.getName (class e))))})))))

(defn chosen-bytes
  "The bytes of a chosen list holding `ids` (BookIds, any order, repeats
  allowed) with the header of this Haselnuss version and the input
  digests `inputs`."
  ^bytes [inputs ids]
  (let [header (binding [*print-length* nil *print-level* nil *print-meta* false
                         *print-dup* false *print-readably* true *print-namespace-maps* false]
                 (pr-str {:haselnuss (version/version) :inputs (state/plain inputs)}))]
    (.getBytes ^String (str format-line "\n" header "\n"
                            (apply str (map #(str % "\n") (into (sorted-set) (map book-ref/format-book-id) ids))))
               "UTF-8")))

(defn write-chosen!
  "Make the chosen list of the site `dir` (a real path, locked) hold `ids`
  under a header of this Haselnuss version and `inputs`
  (`chosen-bytes`), atomically. Answers the digest of the bytes written
  (`chosen-digest`'s)."
  [dir inputs ids]
  (let [bs (chosen-bytes inputs ids)]
    (state/write-atomically! (chosen-file dir) (fn [^Writer w] (.write w (String. bs "UTF-8"))))
    (digest/bytes-digest bs)))

(declare chosen-digest)

(defn sync-chosen!
  "`write-chosen!`, but only when the list's bytes would change -- a
  `--build-site` writes it again every run, and a file written for
  nothing would only change its modification time. Answers the digest of
  the list as it now is."
  [dir inputs ids]
  (let [d (digest/bytes-digest (chosen-bytes inputs ids))]
    (if (= d (chosen-digest dir))
      d
      (write-chosen! dir inputs ids))))

(defn chosen-digest
  "The digest of the chosen list's bytes in the site `dir` (a regular file,
  no link followed), or nil when there is none or it cannot be read: what
  the record of an unchanged site keeps for a partial site (TASK-296)."
  [dir]
  (let [p (.toPath (chosen-file dir))]
    (when (Files/isRegularFile p no-follow)
      (try (digest/bytes-digest (Files/readAllBytes p)) (catch Exception _ nil)))))

(defn remove-chosen!
  "Remove the chosen list of the site `dir`: the site is full from now on."
  [dir]
  (Files/deleteIfExists (.toPath (chosen-file dir))))

(defn header-check
  "What the chosen list `chosen` (`read-chosen`'s) says of the build the
  site was made with, in the shape of `haselnuss.library.state/
  header-check`'s answer, for `--build-doc`'s comparison: `{:usable? true
  :header {:inputs}}` when this Haselnuss version wrote it, else
  `{:usable? false :reason :version|:unreadable :message}`."
  [chosen]
  (cond
    (or (nil? chosen) (:unreadable chosen))
    {:usable? false :reason :unreadable :message (or (:unreadable chosen) (str "no " chosen-rel))}

    (= version/unknown (version/version))
    {:usable? false :reason :version :message "this build does not know its own Haselnuss version"}

    (not= (version/version) (:haselnuss (:header chosen)))
    {:usable? false :reason :version
     :message (str chosen-rel " was written by Haselnuss " (pr-str (:haselnuss (:header chosen)))
                   ", this is " (version/version))}

    :else {:usable? true :header {:inputs (:inputs (:header chosen))}}))
