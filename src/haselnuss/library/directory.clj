(ns haselnuss.library.directory
  "Loading the library directory from YAML (doc-10, \"Library directory
  source\" and \"Provider registry\"; TASK-150).

  ## The file

  A library directory file is a plain YAML list of entries:

      - name: reformed
        type: filesystem
        path: ~/books/reformed
      - name: bible
        type: memory

  `find-directory-file` picks the file: an explicit path (the CLI's
  `--libraries`), else `$HASELNUSS_LIBRARIES`, else
  `~/.config/haselnuss/libraries.yaml`. An explicit path must exist -- a
  user who names a file means that file -- while the other two are used
  only when they exist. No file at all is not an error: it means no
  libraries, and `load-library-directory` answers an empty directory.

  The environment and the home folder are options (`:env`, `:home`), so a
  caller -- and every test -- can keep the real ones out.

  ## Entries

  Every entry is a map with a `name` (the book-reference grammar's
  `name`) and a `type`, which selects the provider. The provider is called
  with the whole entry as its config, keys keywordised as clj-yaml reads
  them, with two changes only: `:type` becomes the registry keyword, and a
  string `:path` that is relative resolves against the directory file's
  own folder (a leading `~` against the home folder). Every other key --
  `url`, `scripts`, anything a later provider wants -- is passed through
  untouched.

  `url` is also the directory's own (TASK-155): the entry's URL template
  (`haselnuss.library.url`), which the built directory answers through
  `haselnuss.library/url-template`. It is checked before the provider
  runs; one that is not a valid template makes the entry an
  `:invalid-library` (carrying `:url`), like any other configuration
  problem. A `url` of YAML `null` is no template.

  ## Provider registry

  A registry is an open map from a keyword type to `(fn [entry-config]
  Library)`, in the same style as the extension registry (SPEC.md sec8.2).
  `default-registry` holds `:filesystem` (`haselnuss.library.filesystem`,
  TASK-151) and `:memory`; `register-provider` adds one. A
  provider may answer a diagnostic instead of a Library (as
  `haselnuss.library.memory/memory-library` does on a bad config); that
  entry is then an `:invalid-library`.

  ## Failure

  A file that cannot be read, is not valid YAML (including a recursive
  alias, or a key repeated within one entry), is not a list, or holds an
  entry that is not a map with a valid `name` and a `type`, or whose
  `path` is an empty string, is a hard error: an `ex-info` whose message
  starts `path:line: `, with `:source-file` and `:source-line` in its data
  and the underlying exception, if any, as its cause -- as a malformed
  front matter is (TASK-144). Its `:type` is `::unreadable-library-directory`
  (missing, a folder, or an I/O failure) or `::invalid-library-directory`.

  Everything else costs only the library it concerns (TASK-164): the
  directory still holds every library that did build, and reports each
  failure through its `library-diagnostics`, in entry order, as a
  `{:type :level :message ...}` diagnostic with `:level :error`, naming
  its library under `:library` and located by `:file`, `:entry` (1-based
  position in the list) and `:line`:

  - `:duplicate-library` -- two or more entries share a name; `:entries`
    names every one of them, and the diagnostic is located at the second.
    None of them is built -- keeping the first would silently pick one --
    so a reference to that name fails until the file is fixed.
  - `:unknown-provider` -- an entry's `type` is not in the registry.
  - `:invalid-library` -- the entry's `url` is not a valid template, or the
    provider answered a diagnostic (kept under `:cause`), threw, answered
    something that is not a Library, or built a library under another
    name.

  A failed library is absent from `library-names`, and `find-library` on
  its name answers an `:unavailable-library` warning carrying its
  diagnostic under `:cause`, not nil, so a reference to it is not
  mistaken for one to a library the file never names. None of these
  stops a build."
  (:require [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [haselnuss.library :as library]
            [haselnuss.library.filesystem :as filesystem]
            [haselnuss.library.memory :as memory]
            [haselnuss.library.url :as url]
            [haselnuss.parser :as parser])
  (:import (java.io File IOException)))

;; --- registry ----------------------------------------------------------------

(def default-registry
  "The built-in providers, keyed by entry `type`."
  {:filesystem filesystem/filesystem-library
   :memory memory/memory-library})

(defn register-provider
  "`registry` with `provider`, a `(fn [entry-config] Library)`, registered
  for entries whose `type` is `provider-type` (a keyword or a non-blank
  string). Throws `ex-info` on a type that is neither (`::invalid-provider-type`)
  or a provider that is not a fn (`::invalid-provider`), as the extension
  registry's `register` does for a missing name."
  [registry provider-type provider]
  (when-not (or (keyword? provider-type)
                (and (string? provider-type) (not (str/blank? provider-type))))
    (throw (ex-info "a provider type must be a keyword or a non-blank string"
                    {:type ::invalid-provider-type :provider-type provider-type})))
  (when-not (ifn? provider)
    (throw (ex-info (str "the provider for type " (pr-str provider-type)
                         " must be a (fn [entry-config] Library)")
                    {:type ::invalid-provider :provider-type provider-type})))
  (assoc registry (keyword provider-type) provider))

;; --- finding the file --------------------------------------------------------

(def env-var
  "The environment variable naming a library directory file."
  "HASELNUSS_LIBRARIES")

(defn home-config-file
  "The library directory file under the home folder `home`."
  ^File [home]
  (io/file home ".config" "haselnuss" "libraries.yaml"))

(defn- unreadable
  ([file reason] (unreadable file reason nil))
  ([^File file reason cause]
   (ex-info (str (.getPath file) ":1: cannot read the library directory: " reason)
            {:type ::unreadable-library-directory
             :source-file (.getPath file)
             :source-line 1}
            cause)))

(defn find-directory-file
  "The library directory File to load, or nil when there is none.

  `opts`: `:path` an explicit file (the CLI's `--libraries`), which must
  exist; `:env`, a map of environment variables (default
  `System/getenv`), whose `HASELNUSS_LIBRARIES` is used when set and the
  file exists; `:home`, the home folder (default the `user.home`
  property), whose `.config/haselnuss/libraries.yaml` is used when it
  exists."
  ^File [{:keys [path env home]
          :or {env (System/getenv) home (System/getProperty "user.home")}}]
  (if (some? path)
    (let [file (io/file path)]
      (if (.exists file) file (throw (unreadable file "no such file"))))
    (let [from-env (get env env-var)]
      (first (filter #(.exists ^File %)
                     (cond-> []
                       (not (str/blank? from-env)) (conj (io/file from-env))
                       home (conj (home-config-file home))))))))

;; --- reading the file --------------------------------------------------------

(defn- invalid
  "A hard error for `file` at 1-based `line`, chaining `cause` when given."
  ([file line message] (invalid file line message nil))
  ([^File file line message cause]
   (ex-info (str (.getPath file) ":" line ": " message)
            {:type ::invalid-library-directory
             :source-file (.getPath file)
             :source-line line}
            cause)))

(defn- bounded-str [x] (binding [*print-length* 8 *print-level* 4] (pr-str x)))

(defn- read-text
  [^File file]
  (when-not (.isFile file)
    (throw (unreadable file "not a regular file")))
  (try (slurp file :encoding "UTF-8")
       (catch IOException e
         (throw (unreadable file (or (ex-message e) (.getName (class e))) e)))))

(defn- parse-yaml
  "`text` parsed, or a hard error at the line snakeyaml reports (line 1
  when it reports none). Anything else the parse throws -- clj-yaml's
  marking constructor rejects a recursive alias with a bare
  `IllegalStateException`, and decoding one unmarked overflows the stack
  -- is a hard error at line 1 too."
  [^File file text opts]
  (try (apply yaml/parse-string text (mapcat identity opts))
       (catch org.yaml.snakeyaml.error.MarkedYAMLException e
         (let [mark (or (.getProblemMark e) (.getContextMark e))]
           (throw (invalid file (if mark (inc (.getLine mark)) 1)
                           (str "invalid YAML: " (parser/yaml-error-message e 1)) e))))
       (catch RuntimeException e
         (throw (invalid file 1 (str "invalid YAML: "
                                     (or (ex-message e) (.getName (class e))))
                         e)))
       (catch StackOverflowError e
         (throw (invalid file 1 "invalid YAML: a recursive alias" e)))))

(defn- check-entry
  "Throws a hard error when `entry`, at `line`, is not a map with a valid
  name and a type."
  [file line entry]
  (let [{entry-name :name entry-type :type} (when (map? entry) entry)]
    (cond
      (not (map? entry))
      (throw (invalid file line (str "an entry must be a map with name and type, got "
                                     (bounded-str entry))))
      (not (contains? entry :name))
      (throw (invalid file line "the entry has no name"))
      (not (library/valid-name? entry-name))
      (throw (invalid file line (str "library name " (bounded-str entry-name)
                                     " must match [a-z0-9][a-z0-9-]*")))
      (not (contains? entry :type))
      (throw (invalid file line (str "library " (pr-str entry-name) " has no type")))
      (not (and (string? entry-type) (seq entry-type)))
      (throw (invalid file line (str "library " (pr-str entry-name)
                                     " has a type that is not a name: "
                                     (bounded-str entry-type))))
      (and (contains? entry :path) (string? (:path entry)) (str/blank? (:path entry)))
      (throw (invalid file line (str "library " (pr-str entry-name) " has an empty path")))
      :else nil)))

(defn read-directory-file
  "The entries of the library directory `file`, in order, each
  `{:entry n :line l :config {...}}`: its 1-based position, the line it
  starts on, and the entry as read. An empty file has no entries. Throws
  the hard errors the namespace docstring lists."
  [file]
  ;; The plain parse rejects a key repeated within one map (the marking
  ;; constructor ignores loader options). A recursive alias either
  ;; overflows the stack in the plain parse or decodes to an endlessly
  ;; nested seq, which the marked parse -- run only on a list, since it
  ;; cannot handle an empty document -- then rejects; `parse-yaml` turns
  ;; both into a hard error, and nothing here walks the data before that.
  (let [file (io/file file)
        text (read-text file)
        data (parse-yaml file text {:allow-duplicate-keys false})]
    (cond
      (nil? data) []
      (not (sequential? data))
      (throw (invalid file 1 (str "a library directory must be a YAML list of entries, got "
                                  (bounded-str data))))
      :else
      (let [marked (yaml/unmark (parse-yaml file text {:mark true}))
            lines (mapv #(inc (get-in % [:start :line])) marked)]
        (mapv (fn [i entry line]
                (check-entry file line entry)
                {:entry (inc i) :line line :config entry})
              (range) data lines)))))

;; --- building the directory --------------------------------------------------

(defn- resolve-path
  "`path` resolved against `dir`, with a leading `~` meaning `home`, and
  normalized; anything that is not a string comes back as it was."
  [path ^File dir home]
  (if-not (string? path)
    path
    (let [path (cond
                 (and home (= "~" path)) (str home)
                 (and home (str/starts-with? path "~/")) (str (io/file home (subs path 2)))
                 :else path)
          file (io/file path)
          file (if (.isAbsolute file) file (io/file dir path))]
      (str (.normalize (.toPath ^File file))))))

(defn- provider-config
  [{:keys [config]} dir home]
  (cond-> (assoc config :type (keyword (:type config)))
    (contains? config :path) (update :path resolve-path dir home)))

(defn- located
  "`diagnostic` located at `item` of `file`, its message prefixed
  `path:line: `."
  [diagnostic ^File file {:keys [entry line]}]
  (-> diagnostic
      (assoc :file (.getPath file) :entry entry :line line)
      (update :message #(str (.getPath file) ":" line ": " %))))

(defn- duplicate-libraries
  "One `:duplicate-library` diagnostic per name that more than one of
  `items` holds, naming every such entry and located at the second."
  [file items]
  (for [[lib-name dupes] (group-by (comp :name :config) items)
        :when (< 1 (count dupes))]
    (located {:type :duplicate-library
              :level :error
              :library lib-name
              :entries (mapv #(select-keys % [:entry :line]) dupes)
              :message (str "library " (pr-str lib-name) " is defined more than once: "
                            (str/join ", " (map #(str "entry " (:entry %) " (line " (:line %) ")")
                                                dupes)))}
             file (second dupes))))

(defn- unknown-provider
  "An `:unknown-provider` diagnostic for `item` when its type is not in
  `registry`, else nil."
  [file registry item]
  (when-not (contains? registry (keyword (:type (:config item))))
    (let [{lib-name :name entry-type :type} (:config item)]
      (located {:type :unknown-provider
                :level :error
                :library lib-name
                :provider entry-type
                :message (str "library " (pr-str lib-name) " has type " (pr-str entry-type)
                              ", which is not a registered provider (registered: "
                              (str/join ", " (sort (map name (keys registry)))) ")")}
               file item))))

(defn- build-library
  "The Library `item`'s provider builds, or an `:invalid-library`
  diagnostic located at `item`."
  [file registry item dir home]
  (let [config (provider-config item dir home)
        lib-name (:name config)
        fail (fn [extra reason]
               (located (merge {:type :invalid-library
                                :level :error
                                :library lib-name
                                :message (str "library " (pr-str lib-name) ": " reason)}
                               extra)
                        file item))
        template-problem (when (some? (:url config)) (url/template-problem (:url config)))
        result (when-not template-problem
                 (try ((get registry (:type config)) config)
                      (catch Throwable t {::threw t})))]
    (cond
      template-problem (fail {:url (:url config)} template-problem)
      (::threw result)
      (let [^Throwable t (::threw result)]
        (fail {} (str "its provider threw: " (or (ex-message t) (.getName (class t))))))
      (library/diagnostic? result)
      (fail {:cause result} (:message result))
      (not (satisfies? library/Library result))
      (fail {} (str "its provider returned something that is not a Library: "
                    (bounded-str result)))
      (not= lib-name (library/library-name result))
      (fail {} (str "its provider built a library named "
                    (pr-str (library/library-name result))))
      :else result)))

(defn build-directory
  "The LibraryDirectory of `items` (as `read-directory-file` returns them,
  read from `file`): every entry that builds is a usable library, and
  every one that does not is reported through the directory's
  `library-diagnostics`, as the namespace docstring describes. `opts`:
  `:registry` (default `default-registry`), `:home` (for a `~` path;
  default `user.home`)."
  [file items {:keys [registry home]
               :or {registry default-registry home (System/getProperty "user.home")}}]
  (let [file (io/file file)
        dir (.getParentFile (.getAbsoluteFile file))
        duplicates (duplicate-libraries file items)
        duplicated (set (map :library duplicates))
        built (for [item items
                    :when (not (duplicated (:name (:config item))))]
                [item (or (unknown-provider file registry item)
                          (build-library file registry item dir home))])
        results (map second built)
        failures (->> (concat duplicates (filter library/diagnostic? results))
                      (sort-by (juxt :entry :line)))
        templates (into {} (for [[{:keys [config]} result] built
                                 :when (and (not (library/diagnostic? result)) (some? (:url config)))]
                             [(:name config) (:url config)]))]
    (library/library-directory (remove library/diagnostic? results) failures
                               {:url-templates templates})))

(defn load-library-directory
  "The LibraryDirectory a build uses: the file `find-directory-file` picks
  from `opts`, read and built through `(:registry opts)`. An empty
  directory when there is no file. Entry problems are the directory's
  `library-diagnostics`; problems with the file itself are thrown, as the
  namespace docstring describes."
  ([] (load-library-directory {}))
  ([opts]
   (if-let [file (find-directory-file opts)]
     (build-directory file (read-directory-file file) opts)
     (library/library-directory []))))
