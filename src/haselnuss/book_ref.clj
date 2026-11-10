(ns haselnuss.book-ref
  "The book-reference grammar (doc-10, \"Book identifier & reference
  syntax\" and \"Values\"; TASK-148) as plain data:

      book-ref   = book-id [ \"#\" passage ]
      book-id    = library \":\" [ shelf-path \"/\" ] slug
      shelf-path = name *( \"/\" name )
      name       = [a-z0-9] *[a-z0-9-]
      passage    = label [ \"..\" label ] | locator
      label      = kind \":\" label-rest
      kind       = [a-z] *[a-z0-9-]

  Every later library unit -- the parser's `@{…}`/`!quote` nodes, passage
  resolution, the CLI's book-ID input -- reads a reference through
  `parse-book-ref` and prints one through `format-book-ref`, so there is
  exactly one implementation of the grammar. Nothing here knows about
  libraries, books or the AST, and the namespace requires nothing but
  `clojure.string`.

  ## Values

  A BookId is `{:library \"bible\" :shelf [\"scripture\" \"nt\"] :slug
  \"romanos\"}`. `:shelf` is always a vector: the shelf path as WRITTEN,
  which is `[]` for the short form `bible:romanos`. A book that sits at its
  library's root has an empty shelf too, so its canonical form and its
  short form are the same string.

  A parsed reference is `{:id BookId}` plus `:passage` when a `#` part was
  written. A passage is tagged by `:form`:

      {:form :label   :label \"sec:q1\"}
      {:form :range   :from \"sec:q1\" :to \"sec:q3\"}
      {:form :locator :locator \"14:8-9\"}

  (`:form` rather than `:kind`, because the grammar already uses \"kind\"
  for a label's `sec`/`fig` prefix; and rather than `:type`, because
  `:type` is what marks a diagnostic map.)

  ## Label or locator

  A passage that starts with `kind:` is a label, or a `label..label` range
  whose two ends must both be labels. Anything else is a locator, opaque
  here and interpreted by the book (`14:8-9`). That is why a locator can
  never start with `[a-z][a-z0-9-]*:` -- it would be read as a label.

  A locator is otherwise free-form, with three exclusions, each because
  the text has to survive the surface syntax around it: no whitespace (a
  reference is one token, and `!quote`'s line would otherwise be
  ambiguous), no `#` (only the first `#` separates the passage, so a
  second one is almost certainly a typo), and no `{`/`}` (the reference is
  written inside `@{…}`). A passage that starts or ends with `..` is a
  dangling range and malformed whatever its form.

  ## Identity

  Plain maps cannot override `=`, and a BookId compared with `=` still
  sees `:shelf`. Resolution uses only library + slug (doc-10,
  \"Classification scheme\": a moved book must not break a reference), so
  resolution compares `book-key`s, or asks `same-book?`, and never compares
  whole BookIds.

  ## Failure

  `parse-book-ref` never throws. Malformed text -- including a non-string
  -- returns `{:type :malformed-book-ref :ref <as written> :message …}`, the
  resolver's existing diagnostic shape, with a message naming which part
  is wrong. `malformed?` tells the two results apart."
  (:require [clojure.string :as str]))

;; --- character classes ------------------------------------------------------

(def ^:private name-re
  "A library name, shelf segment or slug."
  #"[a-z0-9][a-z0-9-]*")

(def ^:private label-start-re
  "The `kind:` prefix that makes a passage a label rather than a locator."
  #"^[a-z][a-z0-9-]*:")

(def ^:private label-re
  "A whole label: `kind:label-rest`. The rest is today's id alphabet as the
  parser scans a bare `@prefix:label` (word characters, `-`, `:`), plus
  `.` so a dotted id such as `sec:2.1` can be cited. The rest must start
  with a word character, so `sec:.` and `sec:-` are not labels. `..`
  inside a label is rejected separately, because that is the range
  separator."
  #"[a-z][a-z0-9-]*:\w[\w.:-]*")

(def ^:private locator-re
  "A locator: anything non-empty except whitespace, `#` and braces (see
  the namespace docstring). Whitespace is Unicode `White_Space`, not Java's
  ASCII-only `\\s`, so a no-break or em space is refused as well -- the
  one that is invisible in an editor is exactly the one to catch."
  #"[^\p{IsWhite_Space}#{}]+")

;; --- diagnostics ------------------------------------------------------------

(defn- malformed
  [written reason]
  {:type :malformed-book-ref
   :ref written
   :message (str "malformed book reference " (pr-str written) ": " reason)})

(defn malformed?
  "True when `x` is the diagnostic `parse-book-ref` returns for malformed
  text, rather than a parsed reference."
  [x]
  (= :malformed-book-ref (:type x)))

;; --- values -----------------------------------------------------------------

(defn book-id
  "A BookId. The two-argument form has no shelf, i.e. the short form."
  ([library slug] (book-id library [] slug))
  ([library shelf slug]
   {:library library :shelf (vec shelf) :slug slug}))

(defn book-key
  "The part of a BookId that resolution uses: `[library slug]`. `:shelf` is
  left out on purpose, so two ids that differ only in shelf have the same
  key (doc-10, \"Classification scheme\")."
  [{:keys [library slug]}]
  [library slug])

(defn same-book?
  "True when BookIds `a` and `b` name the same book for resolution
  purposes: same library and slug, whatever shelf either one writes."
  [a b]
  (= (book-key a) (book-key b)))

;; --- parsing ----------------------------------------------------------------

(defn- valid-name? [s] (boolean (re-matches name-re s)))

(defn- valid-label? [s]
  (and (boolean (re-matches label-re s))
       (not (str/includes? s ".."))))

(defn- parse-id
  "The BookId in `id-text` (everything before the `#`), or a reason string."
  [id-text]
  (let [colon (str/index-of id-text ":")]
    (if (nil? colon)
      "expected <library>:<slug>, but there is no \":\""
      (let [library (subs id-text 0 colon)
            path (subs id-text (inc colon))
            ;; -1 keeps trailing empty strings, so `a:b/` is caught as an
            ;; empty slug rather than silently read as `a:b`.
            segments (str/split path #"/" -1)
            shelf (pop (vec segments))
            slug (peek (vec segments))]
        (cond
          (= "" library) "the library name is empty"
          (not (valid-name? library))
          (str "library name " (pr-str library)
               " must match [a-z0-9][a-z0-9-]*")
          (= "" slug) "the slug is empty"
          (some #{""} shelf) "the shelf path has an empty segment"
          :else
          (if-let [bad (first (remove valid-name? (conj shelf slug)))]
            (str (if (= bad slug) "slug " "shelf segment ") (pr-str bad)
                 " must match [a-z0-9][a-z0-9-]*")
            (book-id library shelf slug)))))))

(defn- parse-passage
  "The Passage in `text` (everything after the `#`), or a reason string."
  [text]
  (cond
    (= "" text) "a \"#\" must be followed by a passage"
    (or (str/starts-with? text "..") (str/ends-with? text ".."))
    (str "passage " (pr-str text) " has a dangling \"..\"")

    (re-find label-start-re text)
    (let [[from to & more] (str/split text #"\.\." -1)]
      (cond
        (seq more)
        (str "label range " (pr-str text) " has more than one \"..\"")
        (not (valid-label? from))
        (str "label " (pr-str from) " must be kind:label")
        (nil? to) {:form :label :label from}
        (not (valid-label? to))
        (str "range end " (pr-str to)
             " must be a label (kind:label), like the start "
             (pr-str from))
        :else {:form :range :from from :to to}))

    (re-matches locator-re text) {:form :locator :locator text}
    :else
    (str "locator " (pr-str text)
         " must not contain whitespace, \"#\", \"{\" or \"}\"")))

(defn parse-book-ref
  "Parse `text` as `<library>:[<shelf>/…/]<slug>[#<passage>]`.

  Returns `{:id BookId}`, plus `:passage` when a `#` part was written, or a
  `:malformed-book-ref` diagnostic map (see `malformed?`). Never throws."
  [text]
  (if-not (string? text)
    (malformed text (str "expected a string, got "
                         (if (nil? text) "nil" (.getName (class text)))))
    (let [sep (str/index-of text "#")
          id-text (if sep (subs text 0 sep) text)
          passage-text (when sep (subs text (inc sep)))
          id (parse-id id-text)]
      (cond
        (string? id) (malformed text id)
        (nil? sep) {:id id}
        (str/includes? passage-text "#")
        (malformed text "only one \"#\" may separate the book from its passage")
        :else
        (let [passage (parse-passage passage-text)]
          (if (string? passage)
            (malformed text passage)
            {:id id :passage passage}))))))

;; --- formatting -------------------------------------------------------------

(defn format-book-id
  "A BookId as text: the canonical form when `:shelf` is non-empty, the
  short form `<library>:<slug>` otherwise."
  [{:keys [library shelf slug]}]
  (str library ":" (str/join (map #(str % "/") shelf)) slug))

(defn format-passage
  "A Passage as the text that follows `#`."
  [{:keys [form] :as passage}]
  (case form
    :label (:label passage)
    :range (str (:from passage) ".." (:to passage))
    :locator (:locator passage)))

(defn format-book-ref
  "A parsed reference `{:id BookId :passage? Passage}` as text, the inverse
  of `parse-book-ref`. A bare BookId (a map with `:slug`) is also
  accepted and formats as the whole book.

  It expects a VALID value. Anything else -- notably a
  `:malformed-book-ref` diagnostic, which has no `:id` to print -- is a
  caller bug, not user input, so it throws `ex-info` rather than printing
  a misleading string."
  [book-ref]
  (cond
    (and (map? book-ref) (contains? book-ref :slug)) (format-book-id book-ref)
    (and (map? book-ref) (map? (:id book-ref)))
    (let [{:keys [id passage]} book-ref]
      (cond-> (format-book-id id)
        passage (str "#" (format-passage passage))))
    :else
    (throw (ex-info (str "format-book-ref expects a parsed book reference or"
                         " a BookId, got " (pr-str book-ref))
                    {:value book-ref}))))
