(ns haselnuss.library.url
  "URL templates (doc-10, \"Resolver\": URLs; TASK-155). A library
  directory entry may carry one,

      - name: reformed
        type: filesystem
        path: ~/books/reformed
        url: \"https://example.org/reformed/{slug}.html#{label}\"

  and a link or quote into one of that library's books gets the URL it
  fills in. Without a template there is no URL: a book's URL is never
  guessed.

  ## Placeholders

  - `{library}` -- the library name;
  - `{shelf}` -- the book's CURRENT shelf path (never the one a reference
    wrote, which may be stale), its segments joined by `/`; empty for a
    book at the library's root;
  - `{slug}` -- the book's slug;
  - `{label}` -- a label passage's label, or a range's FIRST label (the
    place a link to the range lands); empty otherwise;
  - `{locator}` -- a locator passage's locator; empty otherwise.

  Anything else in braces, and a brace that opens or closes nothing, makes
  the template invalid (`template-problem`): a typo such as `{slgu}` must
  not quietly print itself into every link.

  ## Encoding

  Every value is percent-encoded as UTF-8, except the RFC 3986 unreserved
  characters (`A-Z a-z 0-9 - . _ ~`) and `:`. A `:` is legal unencoded in
  a path segment, a query and a fragment alike, and keeping it means the
  fragment of `#{label}` is `#sec:q1`, exactly the id the HTML emitter
  writes. Everything else a locator may hold (`/`, `?`, `&`, `=`, `+`,
  `%`, non-ASCII) is encoded, so a value can never change the URL's
  structure, wherever in it the placeholder sits. `{shelf}` encodes each
  segment and keeps the `/` between them, since a shelf path is a path.
  The template's own text is the library owner's and is left as written.

  A filled value may never set the URL's scheme. A `:` before any `/`,
  `?` or `#` ends a scheme, so `{label}.html` would make `sec:q1.html` a
  URL of scheme `sec:` (and a label `javascript:alert(1)` worse). So a
  value's `:` is percent-encoded too wherever the scheme is not yet
  settled: no `/`, `?` or `#` has appeared in the URL so far, and the
  template's literal text so far has no `:`. Everywhere else -- after
  `https://host/`, in `?v=`, in `#` -- it stays readable. (In
  `{slug}:{label}` the template's own `:` sets the scheme, by its owner's
  choice, so the label's `:` after it stays readable.)

  An empty `{shelf}` (a book at the library's root) takes the `/` that
  follows it with it, so `https://e.org/{shelf}/{slug}` is
  `https://e.org/heidelberg` rather than `https://e.org//heidelberg` --
  which, at the start of a relative template (`/{shelf}/{slug}`), would
  even be a protocol-relative link to a host named after the slug.

  A URL that would end in an empty fragment -- `...#{label}` for a
  reference with no label -- drops the trailing `#`."
  (:require [clojure.string :as str])
  (:import (java.nio.charset StandardCharsets)
           (java.util.regex Matcher)))

(def placeholders
  "The placeholder names a template may use."
  #{"library" "shelf" "slug" "label" "locator"})

(def ^:private token-re
  "A placeholder, or a lone brace."
  #"\{([^{}]*)\}|[{}]")

(defn template-problem
  "Why `template` cannot be a URL template, or nil when it can."
  [template]
  (cond
    (not (string? template))
    (str "a url template must be a string, got "
         (binding [*print-length* 8 *print-level* 4] (pr-str template)))
    (str/blank? template) "a url template must not be blank"
    :else
    (some (fn [[token placeholder]]
            (cond
              (nil? placeholder)
              (str "url template " (pr-str template) " has a " (pr-str token)
                   " that is not part of a placeholder")
              (not (contains? placeholders placeholder))
              (str "url template " (pr-str template) " has an unknown placeholder " token
                   " (known: " (str/join ", " (map #(str "{" % "}") (sort placeholders))) ")")
              :else nil))
          (re-seq token-re template))))

(defn- unreserved?
  [b keep-colon?]
  (or (<= (int \a) b (int \z)) (<= (int \A) b (int \Z)) (<= (int \0) b (int \9))
      (contains? #{\- \. \_ \~} (char b))
      (and keep-colon? (= b (int \:)))))

(defn encode
  "`s` percent-encoded as UTF-8, leaving only the unreserved characters
  -- and `:`, unless `keep-colon?` is false -- as they are (see the
  namespace docstring)."
  ([s] (encode s true))
  ([^String s keep-colon?]
   (let [sb (StringBuilder.)]
     (doseq [b (.getBytes s StandardCharsets/UTF_8)
             :let [b (bit-and (int b) 0xff)]]
       (if (unreserved? b keep-colon?)
         (.append sb (char b))
         (.append sb (format "%%%02X" b))))
     (str sb))))

(defn- raw-values
  "Each placeholder's value, not yet encoded, for the book `id` and
  `passage`; `{shelf}` is its segments."
  [{:keys [library shelf slug]} passage]
  {"library" library
   "shelf" shelf
   "slug" slug
   "label" (case (:form passage)
             :label (:label passage)
             :range (:from passage)
             "")
   "locator" (if (= :locator (:form passage)) (:locator passage) "")})

(defn- scheme-settled?
  "True when, after `out` (the URL so far) of which `literal` was the
  template's own text, a `:` can no longer end a scheme."
  [out literal]
  (or (some #(str/includes? out %) ["/" "?" "#"])
      (str/includes? literal ":")))

(defn fill
  "The URL `template` names for `passage` (a parsed passage, or nil for
  the whole book) of the book whose CURRENT BookId is `id` (see the
  namespace docstring). `template` must have no `template-problem`:
  anything else throws `ex-info`."
  [template id passage]
  (when-let [problem (template-problem template)]
    (throw (ex-info problem {:template template})))
  (let [vs (raw-values id passage)
        ^Matcher m (re-matcher token-re template)
        ;; the template's text from `pos` to `end`, less the `/` an empty
        ;; {shelf} just before `pos` takes with it
        literal-at (fn [pos end]
                     (let [lit (subs template pos end)]
                       (if (and (empty? (get vs "shelf"))
                                (str/starts-with? lit "/")
                                (str/ends-with? (subs template 0 pos) "{shelf}"))
                         (subs lit 1)
                         lit)))
        url (loop [pos 0 out "" literal ""]
              (if (.find m)
                (let [lit (literal-at pos (.start m))
                      out (str out lit)
                      literal (str literal lit)
                      placeholder (.group m 1)
                      value (get vs placeholder)
                      filled (if (= "shelf" placeholder)
                               (str/join "/" (map encode value))
                               (encode value (scheme-settled? out literal)))]
                  (recur (.end m) (str out filled) literal))
                (str out (literal-at pos (count template)))))]
    (if (str/ends-with? url "#") (subs url 0 (dec (count url))) url)))
