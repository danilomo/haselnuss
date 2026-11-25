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
       :locate   (fn [data slug locator] {:title ... :blocks [...] :anchor? ...})}   ; optional

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
  whose message says why -- both become `:bad-locator`. The answer may
  also carry `:anchor`, the id of the element of the book's `:document`
  the passage starts at (`\"v14-8\"`, on a span around the verse number):
  a `--build-site` link to the passage lands there (TASK-269). An anchor
  the document does not have is ignored, and the link lands on the top of
  the book's page, as it does with no `:anchor`.

  ## The sandbox

  Every call into the script -- loading it, `:books`, each `:document`
  and each `:locate` -- goes through `haselnuss.sandbox/sci-call!`, the
  bound `--extensions` and `--transform` use: the same locked SCI
  context (no interop, no `slurp`, no `require` of a host namespace), the
  same timeout, and the same abandoned-worker handling
  (TASK-147) when a call times out. The timeout is wall-clock time, except
  that a `:books`, `:document` or `:locate` call is not charged a stall of
  the whole process, up to `stall-allowance-ms` (TASK-299): a garbage
  collection of a busy build must not fail a well-behaved script. Each script gets its own fork of that
  context, so nothing one script defines is visible to another or to a
  build's `--extensions`. The output cap is `sandbox/default-output-limit`
  for `:books` and `:locate`, and `default-document-output-limit` (ten
  times larger) for `:document`, since a whole book is far larger than
  any renderer result.

  The library's own directory entry -- the trust marker `scripts: true`
  is, and nothing else -- can raise the two `:document` bounds for its
  generated shelves (TASK-300): `document-timeout-ms` and
  `document-output-limit`, positive integers up to
  `max-document-timeout-ms` and `max-document-output-limit`
  (`haselnuss.library.filesystem` checks them). Loading the script,
  `:books` and `:locate` keep the default bounds: they answer a listing or
  one passage, never a whole book.

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

  Across builds (TASK-266, doc-12 \"Scale\"): given the previous run's
  listing cache entry for the shelf, `load-shelf` lists the shelf from it
  without running the script while its `shelf.clj` and `:data` files are
  unchanged (see `load-shelf`). The cache assumes a script answers
  `:books` from its files alone: one whose listing varies from run to run
  (sci offers `rand`, `rand-int` and `shuffle`) keeps the listing of the
  run that cached it until its `shelf.clj` or a `:data` file changes.

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
    book's `document` answers. One for a call that timed out (TASK-299) or
    ran out of memory on a full heap (TASK-301) carries `:transient true`
    (`haselnuss.library/transient?`): kept for the build, never saved as
    lasting.
  - `:invalid-generated-book` (`:book`) -- a `:document` result that is
    not a valid `haselnuss.ast` Document, or that names a
    `meta.bibliography` (the host would read that file for the script),
    or that holds an `:include` block (only a `.hdoc` file's own include
    expansion resolves one, and an unexpanded one aborts the host's
    emit); a `:locate` result that is not `{:title? :blocks :anchor?}` of
    valid blocks (`:anchor` a non-empty string), or holds an `:include`; or
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
            [haselnuss.library.digest :as digest]
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
  "The `:script-failed` diagnostic for the sandboxed failure `t`. A
  timeout is `:transient` (`haselnuss.library/transient?`, TASK-299): a
  busy process can starve a well-behaved script past its deadline as
  surely as a loop runs into it, so the build keeps it for this run but
  saves nothing that would make a later run trust it. So is running out
  of memory on a full heap (TASK-301): the heap is the whole process's,
  and another thread's allocation can leave the script's call the one
  that finds it full. An out-of-memory error that says the request alone
  was impossible (`:heap-exhausted` false: an array over the VM's size
  limit) fails the same way every run, so it is saved like any other
  failure rather than rescanned on every build. (On HotSpot an array
  larger than the whole heap still reads as a full heap; that one is
  transient, as a timeout a loop always reaches is.)"
  [shelf ^Throwable t & {:as extra}]
  (let [{error-type :type :keys [heap-exhausted]} (ex-data t)]
    (apply diagnostic :script-failed shelf (throwable-message t)
           (mapcat identity (cond-> (assoc extra :line (error-line t))
                              (or (= ::sandbox/timeout error-type)
                                  (and (= ::sandbox/out-of-memory error-type) heap-exhausted))
                              (assoc :transient true))))))

;; --- calling the script ------------------------------------------------------

(def default-document-output-limit
  "The output cap, in printed characters, on one `:document` call: a whole
  generated book is far larger than any renderer or transform result (a
  Psalms in this fixture's AST shape prints to about half a megabyte), so
  it gets its own cap, ten times `haselnuss.sandbox/default-output-limit`.
  `:books` and `:locate` keep the sandbox's. A library's own directory
  entry can raise it, up to `max-document-output-limit` (TASK-300)."
  2000000)

(def max-document-timeout-ms
  "The most a library's directory entry may set its generated shelves'
  `:document` timeout to (`document-timeout-ms`, TASK-300), in wall-clock
  milliseconds. The whole Bíblia Livre as one book -- 66 books, 31,100
  verses -- takes 12-15 s to generate on the development machine (a
  12-core desktop), against the sandbox's 5 s default; two minutes is
  eight times that, room for a machine several times slower or a busy
  build, and still a bound: a script that loops costs that library's
  build two minutes of the process running per book it is asked for,
  never a hang. Wall-clock, that is two minutes plus whatever stalls of
  the process are excused (`stall-allowance-ms`, TASK-299) -- up to 30 s
  more, which the script itself can cause by allocating against a large
  live heap."
  120000)

(def max-document-output-limit
  "The most a library's directory entry may set its generated shelves'
  `:document` output cap to (`document-output-limit`, TASK-300), in printed
  characters. The whole Bíblia Livre as one book prints to 9,303,380
  characters; 32,000,000 is over three times that -- a Bible
  with notes, or a larger collection -- while bounding what one script can
  make the host hold, copy and emit for a single book (the copy, the
  resolved document and its page are each of that order in memory)."
  32000000)

(defn- located-scope
  "A `sandbox/sci-call!` `:scope` binding sci's location marker (see the
  namespace docstring) and, when given, re-establishing the script's
  context `ctx` (as `sandbox/wrap-fn` does), around both the call and the
  sandbox's settling of its result, on the worker."
  [ctx]
  (fn [g]
    (binding [sci-utils/*in-try* :sci/error]
      (if ctx (ctx-store/with-ctx ctx (g)) (g)))))

(def stall-allowance-ms
  "How much of a stall of the whole process -- a stop-the-world garbage
  collection, ~9 s at worst seen in the native image under a parallel
  site scan -- one `:books`, `:document` or `:locate` call is not charged
  against its timeout (`sandbox/sci-call!`'s `:stall-allowance-ms`,
  TASK-299), in milliseconds: several times that pause, and a bound. The
  script can cause such stalls itself, by allocating against a large live
  heap, and so make one call last up to its timeout plus this; the
  sandbox's other callers (`--extensions`, `--transform`) get none."
  30000)

(defn- call!
  "`(f & args)`, a fn the script defined, called through the sandbox:
  `sandbox/sci-call!`'s timeout, with `stall-allowance-ms` of a stalled
  process not charged, the script's own context re-established,
  and -- on the worker, under that timeout -- the result copied as plain
  data and measured against `limit` (`sci-call!`'s `:data` kind). `describe`
  names the call in a failure. Throws `sci-call!`'s `ex-info`."
  [{:keys [ctx sandbox-opts]} limit describe f & args]
  (sandbox/sci-call! #(apply f args) describe
                     (assoc sandbox-opts :output-limit limit :scope (located-scope ctx)
                            :stall-allowance-ms stall-allowance-ms)
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
  "`{:spec map :digest d}` for the shelf's script (`d` the digest of the
  bytes read, `haselnuss.library.digest`), or `{:failure diagnostic}`."
  [{:keys [script] :as shelf}]
  (let [path (str script)]
    (try
      (let [[source script-digest] (digest/read-text script "UTF-8")
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
          {:spec (::spec checked) :digest script-digest}))
      (catch java.io.IOException e
        {:failure (diagnostic :script-failed shelf
                              (str "cannot read the script: " (throwable-message e)))})
      (catch Throwable t
        {:failure (script-failed shelf t)}))))

;; --- data ----------------------------------------------------------------------

(defn- parse-data
  "`[value digest]`: the value of the data file `file`, written `written`,
  parsed by its extension, and the digest of the bytes read
  (`haselnuss.library.digest`). Throws on an unsupported extension or a
  parse error."
  [^File file ^String written]
  (let [[text file-digest] (digest/read-text file "UTF-8")
        lower (str/lower-case written)]
    [(cond
       (str/ends-with? lower ".json") (json/read-str text :key-fn keyword)
       (or (str/ends-with? lower ".yaml") (str/ends-with? lower ".yml")) (yaml/parse-string text)
       (str/ends-with? lower ".edn") (edn/read-string text)
       :else (throw (ex-info "the host reads only .json, .yaml, .yml and .edn data files" {})))
     file-digest]))

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
  "`{:data {written value} :files [canonical-path] :digests {canonical-path
  digest} :stamps [{:written :path :stamp}]}` for the spec's `:data`, or
  `{:failure diagnostic}` for the first path that is refused or cannot be
  read. Each `:stamp` (`haselnuss.library.digest/stamp`) is taken before
  the file is read, for the listing cache (TASK-266) -- only when the
  shelf keeps one (`:cache?`); nil otherwise, so an uncached listing
  stats nothing extra."
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
                (try (let [file-stamp (when (:cache? shelf) (digest/stamp found))
                           [value file-digest] (parse-data found written)]
                       (-> acc
                           (assoc-in [:data written] value)
                           (update :files conj (str found))
                           (update :digests digest/record (str found) file-digest)
                           (update :stamps conj {:written written :path (str found) :stamp file-stamp})))
                     (catch Throwable t (fail (str "cannot read it: " (throwable-message t))))))))
          {:data {} :files [] :digests {} :stamps []}
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
  (let [outcome (try {:value (call! (update shelf :sandbox-opts merge (:document-sandbox-opts shelf))
                                    (:document-limit shelf)
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

(defn data-files
  "The canonical paths (strings) of the `:data` files the script that
  generated `book` was handed, in its `:data` order -- with
  `script-path`, every file the book's document depends on (the reference
  graph's `:files`, TASK-240). nil for any other value."
  [book]
  (when (generated-book? book) (::data-files book)))

(defn file-digests
  "path -> digest (`haselnuss.library.digest`) of the bytes the shelf load
  read for `book`'s `script-path` and `data-files`, keyed by those same
  path strings: what the reference graph's `:hash` is taken from
  (TASK-244), so an edit landing after the shelf was loaded never pairs
  its hash with the content the books were generated from. `{}` for any
  other value."
  [book]
  (if (generated-book? book)
    (let [d (::digests book {})] (if (fn? d) (d) d))
    {}))

(defn- book-of
  "`{:book Book}` for the `:books` item `item`, or `{:diagnostic ...}`.
  `source` is a 0-argument fn answering the loaded shelf (`load-source`:
  `{:shelf-ctx ctx}` holding the script's spec and data, or `{:failure
  diagnostic}`), which the book's `:document` and `:locate` calls go
  through; `locate?` says whether the script has a `:locate`;
  `data-files` and `digests` are what `data-files` and `file-digests`
  answer (`digests` a map, or a 0-argument fn answering one)."
  [{:keys [lib-name shelf script] :as shelf-ctx}
   {:keys [source locate? digests] book-data-files :data-files} item]
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
                loaded (delay (let [{ctx :shelf-ctx :keys [failure]} (source)]
                                (or failure (generate-document ctx id slug))))]
            {:book (assoc (if locate?
                            (->LocatableGeneratedBook entry (str script) loaded
                                                      #(let [{ctx :shelf-ctx :keys [failure]} (source)]
                                                         (or failure (locate-passage ctx id slug %))))
                            (->GeneratedBook entry (str script) loaded))
                          ::data-files (vec book-data-files)
                          ::digests (or digests {}))}))))))

(defn- call-books
  "`{:items [map ...]}`, what the script's `:books` answered, or
  `{:failure ...}`."
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
      :else {:items result})))

(defn- books-from-items
  "`{:books :diagnostics}` for the `:books` items `items` (`book-of`, with
  `book-opts`)."
  [shelf-ctx book-opts items]
  (reduce (fn [acc item]
            (let [made (book-of shelf-ctx book-opts item)]
              (if-let [book (:book made)]
                (update acc :books conj book)
                (update acc :diagnostics conj (:diagnostic made)))))
          {:books [] :diagnostics []}
          items))

(defn- load-source
  "The shelf's script loaded and its `:data` read: `{:shelf-ctx ctx
  :script-stamp stamp :data-stamps [...]}`, `ctx` being `shelf-ctx` with
  the script's `:ctx`, `:spec`, `:data`, `:data-files` and `:digests`,
  and each stamp taken before its file was read (`read-data`) -- or
  `{:failure diagnostic}`."
  [shelf-ctx]
  (let [script-stamp (when (:cache? shelf-ctx) (digest/stamp (:script shelf-ctx)))
        shelf-ctx (assoc shelf-ctx :ctx (sandbox/fork))
        {:keys [spec failure] script-digest :digest} (load-script shelf-ctx)]
    (if failure
      {:failure failure}
      (let [{:keys [data files failure digests stamps]} (read-data shelf-ctx (:data spec))]
        (if failure
          {:failure failure}
          {:shelf-ctx (assoc shelf-ctx :spec spec :data data :data-files files
                             :digests (digest/record digests (str (:script shelf-ctx)) script-digest))
           :script-stamp script-stamp
           :data-stamps stamps})))))

;; --- the listing cache (TASK-266) -----------------------------------------------

(def ^:private item-keys
  "The keys of a `:books` item listing reads (`book-of`)."
  [:slug :title :lang :ddc :tags])

(defn- cache-limits
  "The sandbox bounds that can change what `:books` answers."
  [shelf-ctx]
  {:timeout-ms (:timeout-ms (:sandbox-opts shelf-ctx))
   :output-limit (:output-limit shelf-ctx)})

(defn- edn-round-trips?
  "True when `x` prints to EDN and reads back equal, and its strings
  survive the UTF-8 the state is written in (`digest/utf8-safe?`), so a
  cache entry holding it lists exactly what it was built from after the
  state is saved and loaded."
  [x]
  (try
    (and (digest/utf8-safe? x)
         (= x (edn/read-string (binding [*print-length* nil *print-level* nil *print-meta* false
                                         *print-namespace-maps* false *print-readably* true]
                                 (pr-str x)))))
    (catch Exception _ false)))

(defn- cache-entry
  "The listing cache entry for a shelf that listed `items` from `loaded`
  (`load-source`), whose files were stamped after the wall clock read
  `now-ms`; nil when a stamp is not trusted
  (`haselnuss.library.digest/trusted-stamp`) or the items would not come
  back from the saved state as they are."
  [{:keys [lib-name shelf script] :as shelf-ctx} loaded now-ms items]
  (let [{ctx :shelf-ctx :keys [script-stamp data-stamps]} loaded
        script-stamp (digest/trusted-stamp script-stamp now-ms)
        data (mapv #(update % :stamp digest/trusted-stamp now-ms) data-stamps)
        items (mapv #(select-keys % item-keys) items)]
    (when (and script-stamp (every? :stamp data) (edn-round-trips? items))
      {:library lib-name
       :shelf shelf
       :limits (cache-limits shelf-ctx)
       :script {:path (str script) :stamp script-stamp}
       :data data
       :data-files (vec (:data-files ctx))
       :digests (:digests ctx)
       :locate? (some? (:locate (:spec ctx)))
       :items items})))

(defn cache-files-unchanged?
  "True when the files of the listing cache entry `entry` of the
  generated shelf in the folder `dir` are as it stamped them: its
  `shelf.clj`, and each `:data` path as written still naming the same
  file. Stats files; reads none. What `load-shelf` checks of the files
  before it lists the shelf from the entry, so a caller that knows the
  library, shelf and bounds are the same (the directory file is) can
  tell from it that the shelf lists as before (TASK-296)."
  [dir entry]
  (let [dir (io/file dir)
        script (script-file dir)]
    (boolean
     (and (map? entry) (vector? (:data entry))
          (let [{:keys [path stamp]} (:script entry)]
            (and (some? stamp) (= path (str script)) (= stamp (digest/stamp script))))
          (every? (fn [{:keys [written path stamp]}]
                    (and (string? written) (some? stamp)
                         (let [found (data-file dir written)]
                           (and (instance? File found)
                                (= path (str found))
                                (= stamp (digest/stamp found))))))
                  (:data entry))))))

(defn- cache-hit?
  "True when the cache entry `entry` still lists the shelf `shelf-ctx`:
  same library, shelf and sandbox bounds, its `shelf.clj` stamped as
  before, and each `:data` path as written still naming the same file,
  stamped as before (`cache-files-unchanged?`). Stats files; reads none."
  [{:keys [lib-name shelf dir] :as shelf-ctx} entry]
  (boolean
   (and (map? entry)
        (= lib-name (:library entry))
        (= shelf (:shelf entry))
        (= (cache-limits shelf-ctx) (:limits entry))
        (vector? (:items entry)) (every? map? (:items entry))
        (vector? (:data entry)) (vector? (:data-files entry)) (map? (:digests entry))
        (boolean? (:locate? entry))
        (cache-files-unchanged? dir entry))))

(defn- stale-listing?
  "True when the previous run's cache entry `entry` is a listing of this
  shelf (`shelf-ctx`: the same library and shelf, items that are maps) --
  whether or not its files and bounds still stand."
  [{:keys [lib-name shelf]} entry]
  (boolean
   (and (map? entry)
        (= lib-name (:library entry))
        (= shelf (:shelf entry))
        (vector? (:items entry)) (every? map? (:items entry)) (seq (:items entry))
        (vector? (:data-files entry)) (map? (:digests entry))
        (boolean? (:locate? entry)))))

(defn- stale-listing
  "`load-shelf`'s answer when listing the shelf met the transient `failure`
  (TASK-313): the books of the previous run's cache entry `entry`, as they
  were then, with the failure reported as ever -- a timeout says nothing of
  the books, so the shelf's pages and records stand for this run. No
  `:cache` is answered, so the next run calls `:books` again; the books
  carry the digests of the run that listed them, so only what changed
  then is built. The entry itself is answered as `:carried`, for the
  caller to save as it was, so a second failure in a row still finds it.
  Like the listing, the entry is trusted without `cache-hit?`'s checks of
  the script's stamp and the sandbox bounds: a `:books` edited since still
  lists the old items, which is safe, as their old digests make the
  recovering run rebuild whatever the edit changed."
  [shelf-ctx entry failure]
  (let [loaded (delay (load-source shelf-ctx))]
    (assoc (books-from-items shelf-ctx
                             {:source #(deref loaded)
                              :locate? (:locate? entry)
                              :data-files (:data-files entry)
                              :digests (:digests entry)}
                             (:items entry))
           :diagnostics [failure]
           :failure failure
           :carried entry)))

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
  `:document-timeout-ms`, the timeout of a `:document` call alone, default
  `:timeout-ms` (TASK-300: the library entry's `document-timeout-ms`,
  checked against `max-document-timeout-ms` by the caller);
  `:on-script-call`, a test seam (see `notify!`).

  The listing cache (TASK-266): with `:cache?` true, the answer also
  holds `:cache`, an entry (plain data) for the next run's `:cache`, or
  nil when this listing cannot be cached -- a shelf that listed no books,
  a file modified too recently to trust its stamp
  (`haselnuss.library.digest/trusted-stamp`), items that would not come
  back from EDN as they are. When listing meets a transient failure
  (`haselnuss.library/transient?`: a timeout or a full heap) and `:cache`
  is the previous run's entry for the shelf, the shelf lists that entry's
  books, as they were, with the failure still in `:diagnostics` and
  `:failure` and no `:cache` of its own (TASK-313: its pages stand for the
  run and the next one calls `:books` again). Any other failure -- the
  script throws, answers badly -- or no previous entry lists no books, as
  before. Given `:cache`, the previous run's entry,
  that is still good (its `shelf.clj` and every `:data` file stamped as
  before, same library, shelf and sandbox bounds; stats only), the
  script is NOT run to list: the books come from the cached items, and
  the script loads, and its data is read, only when a book's document or
  a passage is asked for -- once for the whole shelf. Never throws."
  [lib-name shelf ^File dir {:keys [scripts? timeout-ms output-limit document-output-limit
                                    document-timeout-ms on-script-call cache? cache]}]
  (let [shelf-ctx {:lib-name lib-name
                   :shelf shelf
                   :dir dir
                   :script (script-file dir)
                   :on-script-call on-script-call
                   :cache? (boolean cache?)
                   :output-limit (or output-limit sandbox/default-output-limit)
                   :document-limit (or document-output-limit default-document-output-limit)
                   :sandbox-opts (cond-> {}
                                   timeout-ms (assoc :timeout-ms timeout-ms))
                   ;; :document only (TASK-300); load, :books and :locate
                   ;; keep :sandbox-opts
                   :document-sandbox-opts (cond-> {}
                                            document-timeout-ms
                                            (assoc :timeout-ms document-timeout-ms))}
        fail (fn [d] {:books [] :diagnostics [d] :failure d})]
    (if-not scripts?
      (fail (diagnostic :scripts-disabled shelf-ctx
                        (str "shelf " (pr-str (str/join "/" shelf)) " is generated by its "
                             script-file-name ", which is not run: the library's entry in the"
                             " library directory does not say scripts: true")))
      (if (and cache? (cache-hit? shelf-ctx cache))
        (let [loaded (delay (load-source shelf-ctx))
              cached-digests (:digests cache)]
          (assoc (books-from-items shelf-ctx
                                   {:source #(deref loaded)
                                    :locate? (:locate? cache)
                                    :data-files (:data-files cache)
                                    ;; the bytes the lazy load read, once it ran
                                    :digests (fn []
                                               (or (when (realized? loaded)
                                                     (:digests (:shelf-ctx @loaded)))
                                                   cached-digests))}
                                   (:items cache))
                 :failure nil
                 :cache cache))
        (let [now-ms (System/currentTimeMillis)
              loaded (load-source shelf-ctx)
              ctx (:shelf-ctx loaded)
              called (when ctx (call-books ctx))]
          (if-let [failure (or (:failure loaded) (:failure called))]
            (if (and (library/transient? failure) (stale-listing? shelf-ctx cache))
              (stale-listing shelf-ctx cache failure)
              (fail failure))
            (cond-> (assoc (books-from-items ctx
                                             {:source (constantly loaded)
                                              :locate? (some? (:locate (:spec ctx)))
                                              :data-files (:data-files ctx)
                                              :digests (:digests ctx)}
                                             (:items called))
                           :failure nil)
              cache? (assoc :cache (cache-entry shelf-ctx loaded now-ms (:items called))))))))))
