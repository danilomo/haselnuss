(ns haselnuss.library.generated
  "Generated shelves (doc-10, \"Generator scripts\"; TASK-158): a shelf
  folder of a filesystem library that holds `shelf.clj` is a generated
  shelf, and its books come from that script rather than from `.hdoc`
  files. `haselnuss.library.filesystem` finds the folder; this namespace
  runs the script and builds its books.

  ## The script

  The script's last form is a map:

      {:data     [\"almeida.json\"]                      ; read by the host
       :books    (fn [data] [{:slug \"romanos\" :title \"Romanos\" :lang \"pt-BR\"} ...])
       :document (fn [data slug] {:meta {...} :blocks [...]})
       :locate   (fn [data slug locator] {:title ... :blocks [...]})}   ; optional

  `:data` is a vector of paths relative to the shelf folder. The host reads
  and parses each one before calling the script -- `.json` (object keys
  keywordised), `.yaml`/`.yml` (as clj-yaml reads it) or `.edn` -- and
  hands the script `data`, a map from each path AS WRITTEN to its parsed
  value: `(get data \"almeida.json\")`. A path that is absolute, or that
  resolves (symlinks followed) outside the shelf folder, is refused, so
  the script reaches no file but the ones beside it.

  `:books` lists the shelf's books: each a map with a `:slug` (the
  book-reference grammar's `name`, as every slug) and the BookEntry
  fields `:title` (a string or inlines), `:lang`, `:ddc` and `:tags`. All
  of them sit on this shelf. `:document` answers one book's document as
  plain Haselnuss AST data. `:locate`, when given, makes every book of the
  shelf Locatable: it answers `{:title :blocks}` for a locator such as
  `14:8-9`; it rejects one by returning nil, or a `{:type :message}` map
  whose message says why -- both become `:bad-locator`.

  ## The sandbox

  Every call into the script -- loading it, `:books`, each `:document`
  and each `:locate` -- goes through `haselnuss.sandbox/sci-call!`, the
  bound `--extensions` and `--transform` use: the same locked SCI
  context (no interop, no `slurp`, no `require` of a host namespace), the
  same wall-clock timeout, and the same abandoned-worker handling
  (TASK-147) when a call times out. Each script gets its own fork of that
  context, so nothing one script defines is visible to another or to a
  build's `--extensions`. The output cap is `sandbox/default-output-limit`
  for `:books` and `:locate`, and `default-document-output-limit` (ten
  times larger) for `:document`, since a whole book is far larger than
  any renderer result.

  sci records an error's line only for code run on the thread that built
  its context, and every sandboxed call runs on a worker. Binding
  `sci.impl.utils/*in-try*` to `:sci/error` -- sci's own \"preserve
  location\" marker -- on the worker keeps the line, so a runtime error is
  reported at its `shelf.clj:line` and not only a parse error. (An
  internal of the pinned sci 0.12.51.)

  Like every sandboxed call's (TASK-211), each result is settled on the
  worker, under the timeout, by `sci-call!` itself (`sandbox/plain-copy`,
  kind `:data`): copied as fresh maps, vectors, lists and sets of strings,
  numbers, keywords, symbols, booleans, characters and nil, without
  metadata, then measured against the cap by printing the copy into a
  writer that keeps nothing and stops past the cap. Anything else -- a
  `reify` with its own `toString` or `deref`, a fn, an atom -- is a
  `:script-failed`, because printing it later (the JSON emitter, a
  diagnostic) would run script code outside the timeout; and dropping
  metadata keeps a script from forging source positions. Lazy seqs are
  realised by the copy, under the timeout, and every value counts against
  the cap, so an endless one stops there. (TASK-158 built this for shelf
  scripts alone; TASK-211 moved it into the sandbox.)

  ## Trust

  Scripts run only when the library's directory entry says
  `scripts: true` (`haselnuss.library.filesystem` reads it from the entry
  config), so the user's own configuration is the trust marker and no
  document can turn execution on. Without it the script is not even
  read: the shelf lists no books and reports `:scripts-disabled`.

  ## Per build

  `load-shelf` runs once per library scan, which a filesystem library
  does once per build: the script loads once and `:books` is called once.
  Each book holds a `delay` of its document, so `:document` runs at most
  once per book per build. `:locate` is not cached, as for every
  Locatable book.

  ## Diagnostics

  All `:level :warning`, carrying `:library`, `:shelf` (the shelf path,
  `/`-joined), `:script` and `:source-file` (the script's path) and,
  when known, `:source-line`; the message starts `path:line: `.

  - `:scripts-disabled` -- the library's entry lacks `scripts: true`.
  - `:script-failed` -- loading the script, `:books`, a `:document` or a
    `:locate` threw, timed out, exceeded the output cap or answered
    something that is not plain data; the script
    could not be read; its last form is not a shelf map; a `:data` path
    is refused or cannot be read or parsed; or `:books` did not answer a
    sequence of maps. When it concerns the whole shelf the shelf lists no
    books, and when it concerns one book's `:document` it is what that
    book's `document` answers.
  - `:invalid-generated-book` (`:book`) -- a `:document` result that is
    not a valid `haselnuss.ast` Document, or that names a
    `meta.bibliography` (the host would read that file for the script),
    or that holds an `:include` block (only a `.hdoc` file's own include
    expansion resolves one, and an unexpanded one aborts the host's
    emit); a `:locate` result that is not `{:title? :blocks}` of valid
    blocks, or holds an `:include`; or
    a `:books` entry whose title, lang, ddc or tags cannot make a
    BookEntry (the book is then not listed).
  - `:invalid-book-name` (`:slug`) -- a `:books` slug that is not a valid
    name; the book is not listed."
  (:require [clj-yaml.core :as yaml]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [haselnuss.ast :as ast]
            [haselnuss.book-ref :as book-ref]
            [haselnuss.library :as library]
            [haselnuss.sandbox :as sandbox]
            [haselnuss.walk :as walk]
            [sci.core :as sci]
            [sci.ctx-store :as ctx-store]
            [sci.impl.utils :as sci-utils])
  (:import (java.io File)))

;; --- the shelf folder --------------------------------------------------------

(def script-file-name
  "The file that makes a shelf folder a generated shelf."
  "shelf.clj")

(defn script-file
  "The `shelf.clj` of the folder `dir`."
  ^File [dir]
  (io/file dir script-file-name))

(defn generated-shelf?
  "True when the folder `dir` holds a `shelf.clj`."
  [dir]
  (.isFile (script-file dir)))

;; --- diagnostics -------------------------------------------------------------

(defn- bounded-pr [x] (binding [*print-length* 8 *print-level* 4] (pr-str x)))

(defn- throwable-message [^Throwable t] (or (ex-message t) (.getName (class t))))

(defn- canonical [id] (book-ref/format-book-id id))

(defn- diagnostic
  "A `diagnostic-type` diagnostic about the shelf `shelf` (see `load-shelf`),
  located at its script and, when given, `line`."
  [diagnostic-type {:keys [lib-name shelf script]} message & {:keys [line] :as extra}]
  (let [path (str script)]
    (merge {:type diagnostic-type
            :level :warning
            :library lib-name
            :shelf (str/join "/" shelf)
            :script path
            :source-file path
            :message (str path (when line (str ":" line)) ": library " (pr-str lib-name) ": "
                          message)}
           (when line {:source-line line})
           (dissoc extra :line))))

(def ^:private sci-error-types
  "The `ex-data` `:type`s sci gives an error it located in the script."
  #{:sci/error :sci.error/parse :edamame/error})

(defn- error-line
  "The script line the sandboxed error `t` happened on, from the first sci
  error in its cause chain, or nil (a timeout, an oversized result)."
  [^Throwable t]
  (some (fn [c]
          (let [{error-type :type line :line} (ex-data c)]
            (when (and (contains? sci-error-types error-type) (int? line)) line)))
        (take-while some? (iterate ex-cause t))))

(defn- script-failed
  "The `:script-failed` diagnostic for the sandboxed failure `t`."
  [shelf ^Throwable t & {:as extra}]
  (apply diagnostic :script-failed shelf (throwable-message t)
         (mapcat identity (assoc extra :line (error-line t)))))

;; --- calling the script ------------------------------------------------------

(def default-document-output-limit
  "The output cap, in printed characters, on one `:document` call: a whole
  generated book is far larger than any renderer or transform result (a
  Psalms in this fixture's AST shape prints to about half a megabyte), so
  it gets its own cap, ten times `haselnuss.sandbox/default-output-limit`.
  `:books` and `:locate` keep the sandbox's."
  2000000)

(defn- located-scope
  "A `sandbox/sci-call!` `:scope` binding sci's location marker (see the
  namespace docstring) and, when given, re-establishing the script's
  context `ctx` (as `sandbox/wrap-fn` does), around both the call and the
  sandbox's settling of its result, on the worker."
  [ctx]
  (fn [g]
    (binding [sci-utils/*in-try* :sci/error]
      (if ctx (ctx-store/with-ctx ctx (g)) (g)))))

(defn- call!
  "`(f & args)`, a fn the script defined, called through the sandbox:
  `sandbox/sci-call!`'s timeout, the script's own context re-established,
  and -- on the worker, under that timeout -- the result copied as plain
  data and measured against `limit` (`sci-call!`'s `:data` kind). `describe`
  names the call in a failure. Throws `sci-call!`'s `ex-info`."
  [{:keys [ctx sandbox-opts]} limit describe f & args]
  (sandbox/sci-call! #(apply f args) describe
                     (assoc sandbox-opts :output-limit limit :scope (located-scope ctx))
                     :data))

(defn- notify!
  "Tells the test seam `:on-script-call`, when given, that the host is
  about to call into the script: `(on-script-call kind script-path &
  args)`, `kind` one of `:load`, `:books`, `:document`, `:locate`."
  [{:keys [on-script-call script]} kind & args]
  (when on-script-call (apply on-script-call kind (str script) args)))

;; --- loading -----------------------------------------------------------------

(defn- kind-of
  "A script value described by its class alone: printing it could run
  script code (a `reify` with its own `toString`)."
  [x]
  (if (nil? x) "nil" (str "a " (.getName (class x)))))

(defn- checked-spec
  "`{::spec {:data :books :document :locate}}` for `value`, the script's
  last form, or `{::problem message}`. Runs on the sandbox worker, under
  the load's timeout: it touches script values only there, describes a
  wrong one by its class rather than printing it, and takes `:data`
  through `sandbox/plain-copy` (bounded by the sandbox's output cap) so a lazy or
  endless `:data` is realised -- or stopped -- under the timeout too. The
  spec it answers is fresh: the fns as extracted, `:data` plain strings."
  [value]
  (if-not (map? value)
    {::problem (str "its last form must be a map {:data [...] :books (fn [data] ...) :document"
                    " (fn [data slug] ...) :locate? (fn [data slug locator] ...)}, got "
                    (kind-of value))}
    (let [books (get value :books)
          document (get value :document)
          locate (get value :locate)
          data (get value :data)
          copied (when (vector? data)
                   (try {:ok (sandbox/plain-copy data sandbox/default-output-limit)}
                        (catch clojure.lang.ExceptionInfo e {:error (ex-message e)})))]
      (cond
        (not (fn? books)) {::problem (str ":books must be a fn of data, got " (kind-of books))}
        (not (fn? document))
        {::problem (str ":document must be a fn of data and a slug, got " (kind-of document))}
        (and (some? locate) (not (fn? locate)))
        {::problem (str ":locate must be a fn of data, a slug and a locator, got " (kind-of locate))}
        (and (some? data) (not (vector? data)))
        {::problem (str ":data must be a vector of file paths (strings), got " (kind-of data))}
        (:error copied) {::problem (str ":data: " (:error copied))}
        (not-every? #(and (string? %) (not (str/blank? %))) (:ok copied))
        {::problem ":data must be a vector of file paths (non-blank strings)"}
        :else {::spec (cond-> {:books books :document document :data (or (:ok copied) [])}
                        locate (assoc :locate locate))}))))

(defn- load-script
  "`{:spec map}` for the shelf's script, or `{:failure diagnostic}`."
  [{:keys [script] :as shelf}]
  (let [path (str script)]
    (try
      (let [source (slurp script :encoding "UTF-8")
            _ (notify! shelf :load)
            ctx (:ctx shelf)
            checked (sandbox/sci-call!
                     #(let [value (sci/binding [sci/file path]
                                    (sci/eval-string* ctx source))]
                        (ctx-store/with-ctx ctx (checked-spec value)))
                     (str script-file-name " (" path ")")
                     (assoc (:sandbox-opts shelf) :scope (located-scope nil))
                     :spec)]
        (if-let [problem (::problem checked)]
          {:failure (diagnostic :script-failed shelf problem)}
          {:spec (::spec checked)}))
      (catch java.io.IOException e
        {:failure (diagnostic :script-failed shelf
                              (str "cannot read the script: " (throwable-message e)))})
      (catch Throwable t
        {:failure (script-failed shelf t)}))))

;; --- data ----------------------------------------------------------------------

(defn- parse-data
  "The value of the data file `file`, written `written`, parsed by its
  extension. Throws on an unsupported extension or a parse error."
  [^File file ^String written]
  (let [text (slurp file :encoding "UTF-8")
        lower (str/lower-case written)]
    (cond
      (str/ends-with? lower ".json") (json/read-str text :key-fn keyword)
      (or (str/ends-with? lower ".yaml") (str/ends-with? lower ".yml")) (yaml/parse-string text)
      (str/ends-with? lower ".edn") (edn/read-string text)
      :else (throw (ex-info "the host reads only .json, .yaml, .yml and .edn data files" {})))))

(defn- data-file
  "The File the `:data` path `written` names in the shelf folder `dir`, or
  a string saying why it is refused. A path the JDK itself rejects (a NUL
  character, say) is refused too, rather than thrown out of the scan,
  which would cost the whole library."
  [^File dir ^String written]
  (try
    (let [f (io/file written)]
      (if (.isAbsolute f)
        "it is absolute; a :data path is relative to the shelf folder"
        (let [root (.getCanonicalFile dir)
              target (.getCanonicalFile ^File (io/file dir written))]
          (cond
            (not (.startsWith (.toPath target) (.toPath root)))
            "it is outside the shelf folder; the script reads only the files beside it"
            (not (.isFile target)) "no such file"
            :else target))))
    (catch Exception e (str "it is not a usable path: " (throwable-message e)))))

(defn- read-data
  "`{:data {written value}}` for the spec's `:data`, or `{:failure
  diagnostic}` for the first path that is refused or cannot be read."
  [{:keys [dir] :as shelf} paths]
  (reduce (fn [acc written]
            (let [found (data-file dir written)
                  fail (fn [reason]
                         (reduced {:failure (diagnostic :script-failed shelf
                                                        (str ":data " (pr-str written) " refused: "
                                                             reason)
                                                        :data written)}))]
              (if (string? found)
                (fail found)
                (try (assoc-in acc [:data written] (parse-data found written))
                     (catch Throwable t (fail (str "cannot read it: " (throwable-message t))))))))
          {:data {}}
          paths))

;; --- books -------------------------------------------------------------------

(defn- include-problem
  "Why the valid Document `document` holds something only the host's
  include expansion could resolve -- an `:include` block, anywhere -- or
  nil. Quotes are spliced after include expansion, so one would reach the
  emitter unexpanded and abort the host's whole build."
  [document]
  (when-let [include (walk/fold {:table walk/table
                                 :scope walk/every-ref-kind
                                 :visit (fn [found _path ref-kind node]
                                          (or found
                                              (when (and (= ::ast/block ref-kind)
                                                         (= :include (:t node)))
                                                node)))}
                                nil ::ast/document document)]
    (str "it holds an :include block (" (bounded-pr (:src include)) "), which only a .hdoc file's"
         " own include expansion resolves; a generated book answers its content instead")))

(defn- document-result
  "The book `id`'s document from the value `result` its `:document` call
  answered, or an `:invalid-generated-book`."
  [shelf id result]
  (let [problem (try (or (library/document-problem result)
                         (when (contains? (:meta result) :bibliography)
                           (str "meta.bibliography names a file for the host to read, which a"
                                " generated document may not do"))
                         (include-problem result))
                     (catch Throwable t (str "checking it threw: " (throwable-message t))))]
    (if problem
      (diagnostic :invalid-generated-book shelf
                  (str "book " (canonical id) ": :document answered an invalid document: " problem)
                  :book (canonical id))
      result)))

(defn- generate-document
  "The book `id`'s document (slug `slug`), or the diagnostic it failed with."
  [{:keys [spec data] :as shelf} id slug]
  (notify! shelf :document slug)
  (let [outcome (try {:value (call! shelf (:document-limit shelf)
                                    (str script-file-name " :document for " (canonical id))
                                    (:document spec) data slug)}
                     (catch Throwable t {:failure (script-failed shelf t :book (canonical id))}))]
    (or (:failure outcome) (document-result shelf id (:value outcome)))))

(defn- bad-locator
  [id locator message]
  {:type :bad-locator
   :book (canonical id)
   :locator locator
   :message (str "book " (canonical id) " " message)})

(defn- locate-passage
  "The Passage for `locator` in the book `id` (slug `slug`), or a
  diagnostic."
  [{:keys [spec data] :as shelf} id slug locator]
  (if-not (and (string? locator) (seq locator))
    (bad-locator id locator (str "was asked for locator " (bounded-pr locator)
                                 ", but a locator is a non-empty string"))
    (let [_ (notify! shelf :locate slug locator)
          outcome (try {:value (call! shelf (:output-limit shelf)
                                      (str script-file-name " :locate for " (canonical id))
                                      (:locate spec) data slug locator)}
                       (catch Throwable t
                         {:failure (script-failed shelf t :book (canonical id) :locator locator)}))
          result (:value outcome)]
      (cond
        (:failure outcome) (:failure outcome)
        (nil? result) (bad-locator id locator (str "has no passage at locator " (pr-str locator)))
        (library/diagnostic? result)
        (bad-locator id locator (str "rejected locator " (pr-str locator) ": " (:message result)))
        :else
        (if-let [problem (try (or (library/passage-problem result)
                                  (include-problem {:meta {} :blocks (:blocks result)}))
                              (catch Throwable t (str "checking it threw: " (throwable-message t))))]
          (diagnostic :invalid-generated-book shelf
                      (str "book " (canonical id) ": :locate answered an invalid passage for "
                           (pr-str locator) ": " problem)
                      :book (canonical id) :locator locator)
          (library/passage-value id locator (dissoc result :url)))))))

(defrecord ^:private GeneratedBook [entry path loaded]
  library/Book
  (book-entry [_] entry)
  (document [_] @loaded))

(defrecord ^:private LocatableGeneratedBook [entry path loaded locate-fn]
  library/Book
  (book-entry [_] entry)
  (document [_] @loaded)
  library/Locatable
  (locate [_ locator] (locate-fn locator)))

(defn generated-book?
  "True when `x` is a book a `shelf.clj` generated."
  [x]
  (or (instance? GeneratedBook x) (instance? LocatableGeneratedBook x)))

(defn script-path
  "The path of the `shelf.clj` that generated `book`, or nil for any other
  value."
  [book]
  (when (generated-book? book) (:path book)))

(defn- book-of
  "`{:book Book}` for the `:books` item `item`, or `{:diagnostic ...}`."
  [{:keys [lib-name shelf script spec] :as shelf-ctx} item]
  (let [slug (:slug item)
        fields (select-keys item [:title :lang :ddc :tags])]
    (if-not (library/valid-name? slug)
      {:diagnostic (diagnostic :invalid-book-name shelf-ctx
                               (str ":books skipped slug " (bounded-pr slug)
                                    ": a slug must be lowercase [a-z0-9-] and start with a"
                                    " letter or digit")
                               :path (str script) :slug slug)}
      (let [id (book-ref/book-id lib-name shelf slug)]
        (if-let [problem (library/entry-problem fields)]
          {:diagnostic (diagnostic :invalid-generated-book shelf-ctx
                                   (str "book " (canonical id) " is not listed: " problem)
                                   :book (canonical id))}
          (let [entry (library/book-entry-value id fields)
                loaded (delay (generate-document shelf-ctx id slug))]
            {:book (if (:locate spec)
                     (->LocatableGeneratedBook entry (str script) loaded
                                               #(locate-passage shelf-ctx id slug %))
                     (->GeneratedBook entry (str script) loaded))}))))))

(defn- list-books
  "`{:books :diagnostics}` from the script's `:books`, or `{:failure ...}`."
  [{:keys [spec data] :as shelf}]
  (notify! shelf :books)
  (let [outcome (try {:value (call! shelf (:output-limit shelf) (str script-file-name " :books")
                                    (:books spec) data)}
                     (catch Throwable t {:failure (script-failed shelf t)}))
        result (:value outcome)]
    (cond
      (:failure outcome) outcome
      (not (and (sequential? result) (every? map? result)))
      {:failure (diagnostic :script-failed shelf
                            (str ":books must answer a sequence of maps {:slug :title ...}, got "
                                 (bounded-pr result)))}
      :else
      (reduce (fn [acc item]
                (let [made (book-of shelf item)]
                  (if-let [book (:book made)]
                    (update acc :books conj book)
                    (update acc :diagnostics conj (:diagnostic made)))))
              {:books [] :diagnostics []}
              result))))

(defn load-shelf
  "The books of the generated shelf in the folder `dir`, on shelf `shelf`
  (a vector of names) of the library `lib-name`: `{:books [Book ...]
  :diagnostics [...] :failure diagnostic-or-nil}`. `:failure` is set, and
  also among `:diagnostics`, when the shelf as a whole could list no books
  -- scripts disabled, or the script failing to load, read its data or
  answer `:books` -- so the library can say why a book on it is missing.

  `opts`: `:scripts?`, true only when the library's directory entry says
  `scripts: true` (otherwise the script is never read);
  `:timeout-ms`, `haselnuss.sandbox/sci-call!`'s, default its default;
  `:output-limit`, the cap on a `:books` or `:locate` result, default
  `haselnuss.sandbox/default-output-limit`; `:document-output-limit`, the
  cap on a `:document` result, default `default-document-output-limit`;
  `:on-script-call`, a test seam (see `notify!`). Never throws."
  [lib-name shelf ^File dir {:keys [scripts? timeout-ms output-limit document-output-limit
                                    on-script-call]}]
  (let [shelf-ctx {:lib-name lib-name
                   :shelf shelf
                   :dir dir
                   :script (script-file dir)
                   :on-script-call on-script-call
                   :output-limit (or output-limit sandbox/default-output-limit)
                   :document-limit (or document-output-limit default-document-output-limit)
                   :sandbox-opts (cond-> {}
                                   timeout-ms (assoc :timeout-ms timeout-ms))}
        fail (fn [d] {:books [] :diagnostics [d] :failure d})]
    (if-not scripts?
      (fail (diagnostic :scripts-disabled shelf-ctx
                        (str "shelf " (pr-str (str/join "/" shelf)) " is generated by its "
                             script-file-name ", which is not run: the library's entry in the"
                             " library directory does not say scripts: true")))
      (let [shelf-ctx (assoc shelf-ctx :ctx (sandbox/fork))
            {:keys [spec failure]} (load-script shelf-ctx)]
        (if failure
          (fail failure)
          (let [{:keys [data failure]} (read-data shelf-ctx (:data spec))]
            (if failure
              (fail failure)
              (let [listed (list-books (assoc shelf-ctx :spec spec :data data))]
                (if-let [failure (:failure listed)]
                  (fail failure)
                  (assoc listed :failure nil))))))))))
