(ns haselnuss.extensions.rules
  "Loads `--transform` rule maps from a sidecar Clojure file (TASK-103):
  an author writes a plain `.clj` file whose last form is one
  `haselnuss.transform`-ready `{:select :transform :name? :scope?}` map,
  or a collection of them, and `haselnuss.cli/run` applies them
  (`haselnuss.transform/transform-document`) to the parsed document,
  immediately after `parser/parse` and before any resolve/lower/emit
  pass, on every target -- see `haselnuss.transform`'s own docstring for
  the rule shape and its return-value contract, and doc-7 decision 1 for
  why the pass sits exactly there.

  This namespace mirrors `haselnuss.extensions.sidecar` -- same sidecar-
  file contract ('a map, or a collection of maps, is the last form'),
  same runtime bounds, same isolation -- because it is the SAME sandbox,
  generalized out into `haselnuss.sandbox` for exactly this reason rather
  than duplicated (doc-7 decision 5): `haselnuss.sandbox`'s own docstring
  covers what is actually shared (`base-context`, `sci-call!`, `wrap-fn`);
  this docstring covers only what is specific to a RULE sidecar file.

  THE SIDECAR CONTRACT: a file's own LAST top-level form must evaluate to
  a rule map, or a COLLECTION of them (a vector, list, lazy seq, or set --
  `haselnuss.sandbox/collection-of-maps?`), each `{:select :transform
  :name? :scope?}` (`haselnuss.transform`'s own docstring has the full
  shape). `validate-rule!` checks only what this LOADER can check before
  the rule is ever run against a document -- `:select` present and one of
  `haselnuss.select`'s own four recognized shapes (a keyword, a map, a
  vector or a function; a malformed INNER shape, e.g. a `:prop` value that
  is not itself a map, still only surfaces later, from
  `haselnuss.select/matches?` itself, the moment a rule actually runs),
  `:transform` present and callable, `:name` a string when given, `:scope`
  a set when given. `haselnuss.transform`'s own `apply-rule` is what
  actually validates a rule's own OUTPUT (a returned node against
  `haselnuss.ast`, an arity violation at a `:one` position, ...) -- this
  loader has no way to know any of that before the rule actually runs.

  NO TRUST FLAG IS INVOLVED, deliberately (AC #10): a rule's `:transform`
  is wrapped via `haselnuss.sandbox/wrap-fn` exactly like an
  `--extensions` renderer, so it inherits the identical timeout/output-
  cap/output-capture bound, but nothing here ever marks a rule
  `:trusted?` the way `haselnuss.extensions.sidecar/normalize-entry`
  does for an `:emit` renderer -- there is no such concept for a rule to
  have. `haselnuss.transform`'s own return-value contract accepts plain
  AST data only (a node, `nil`, or a sequence of nodes, never a
  `[:raw ...]` escape hatch), and `haselnuss.ext/raw` is still reachable
  from inside a `--transform` file's own sandbox (the same shared
  `haselnuss.ext` namespace every sidecar file sees) but produces a node
  `haselnuss.transform/validate-replacement!` simply rejects the moment a
  rule tries to splice it in: `[:raw s]` does not match `haselnuss.ast`'s
  schema for any position (`Block`/`Inline`/... all require a real `:t`),
  so it fails TASK-102's own AC #4 (a returned node validated against
  `haselnuss.ast` before ever being spliced in) rather than reaching an
  emitter as verbatim, unescaped output by another name."
  (:require [clojure.java.io :as io]
            [haselnuss.sandbox :as sandbox]
            [sci.core :as sci]))

(defn- malformed!
  [path reason data]
  (throw (ex-info (str "malformed transform rule in " path ": " reason)
                  (merge {:type ::malformed-transform-rule :path path} data))))

(defn- selector-shape?
  "True when `select` is one of `haselnuss.select`'s own four recognized
  top-level selector shapes -- a keyword, a map, a vector or a function.
  Deliberately shallow: this catches only \"the rule cannot possibly
  match anything, ever\" (a missing or wrongly-typed `:select` entirely),
  not a malformed shape nested INSIDE an otherwise well-typed one (a
  `:prop` value that is not itself a map, say) -- `haselnuss.select/
  matches?` itself already raises `::select/malformed-selector` for that,
  the moment a rule actually runs, naming the rule via `haselnuss.
  transform`'s own `rewrap-rule-error`. Duplicating `matches?`'s own full
  validation here, before any document exists to run it against, would be
  a second copy of that logic to keep in sync with the first."
  [select]
  (or (keyword? select) (map? select) (vector? select) (fn? select)))

(defn- validate-rule!
  "Throws a `::malformed-transform-rule` `ex-info` naming both `path` and
  what is wrong with rule map `entry` (AC #8), or returns `entry`
  unchanged. `label` (`rule-label`, this file's own position among the
  rules it defines plus, when present, its own `:name`) is what every
  message below names the rule by, mirroring `haselnuss.transform`'s own
  `rule-label` convention -- 'refused naming the file and the rule', not
  the file alone."
  [path label entry]
  (when-not (contains? entry :select)
    (malformed! path (str label " has no :select") {:value entry}))
  (when-not (selector-shape? (:select entry))
    (malformed! path (str label "'s :select must be a keyword, a map, a vector or a function, got "
                          (pr-str (:select entry)))
                {:value entry}))
  (when-not (contains? entry :transform)
    (malformed! path (str label " has no :transform") {:value entry}))
  (when-not (ifn? (:transform entry))
    (malformed! path (str label "'s :transform is not a function: " (pr-str (:transform entry)))
                {:value entry}))
  (when (and (contains? entry :name) (not (string? (:name entry))))
    (malformed! path (str label "'s :name must be a string, got " (pr-str (:name entry)))
                {:value entry}))
  (when (and (contains? entry :scope) (not (set? (:scope entry))))
    (malformed! path (str label "'s :scope must be a set, got " (pr-str (:scope entry)))
                {:value entry}))
  entry)

(defn- rule-label
  "The string `validate-rule!`'s own messages, and `normalize-rule`'s own
  later `sci-call!` `describe` string, name rule `entry` by: its own
  `:name` when the (unvalidated -- this runs BEFORE `validate-rule!`,
  precisely so a bad `:name` can be reported by this same label) entry
  supplies a string one, else `index`'s own position among the rules
  THIS FILE defines (0-based, `rule #<n>`, 1-based for a human reading
  the message) -- mirroring `haselnuss.transform`'s own private
  `rule-label`, one layer earlier, but WITHOUT that function's own
  `:select`-reading fallback: a rule this loader is refusing may have no
  usable `:select` at all yet, which is exactly the case being reported.
  Deliberately carries no `path` of its own -- every caller already
  names `path` alongside this label (`malformed!`'s own message prefix;
  `normalize-rule`'s own `describe` string), so folding it in here too
  would repeat it in every message this label ever reaches."
  [index entry]
  (if (string? (:name entry)) (:name entry) (str "rule #" (inc index))))

(defn- rule-entries
  "`[label entry]` pairs for the rule maps `value` -- a sidecar file's own
  final top-level form -- must be: `value` itself if it is a map, `value`
  (realized into a vector) if it is a `haselnuss.sandbox/collection-of-
  maps?` collection, else a `::malformed-transform-rule` naming `path`
  (AC #8). Each element validated in turn (`validate-rule!`), in file
  order -- the order `haselnuss.cli` threads into `haselnuss.transform/
  transform-document`, which applies every rule from every `--transform`
  file, across every file, in that same order (declaration order, AC #1).
  `label` (`rule-label`) is computed once per entry, here, and threaded
  through to `normalize-rule` rather than recomputed there -- both calls
  need the SAME label for the SAME rule, and only this function has each
  entry's own position among ITS OWN file's rules to compute it from."
  [value path]
  (let [entries (cond
                  (map? value) [value]
                  (sandbox/collection-of-maps? value) (vec value)
                  :else (malformed! path
                                    (str "a transform file's last form must evaluate to a rule map "
                                         "or a collection of them (a vector, list, lazy seq, or set), "
                                         "got " (pr-str value))
                                    {:value value}))]
    (into []
          (map-indexed (fn [index entry]
                         (let [label (rule-label index entry)]
                           [label (validate-rule! path label entry)])))
          entries)))

(defn- normalize-rule
  "Validated rule map `entry` (from a sidecar file, `path`'s own source,
  named `label` -- `rule-entries`' own, so a later `sci-call!` failure
  names the identical rule `validate-rule!` would have), turned into a
  `haselnuss.transform/transform-document`-ready rule: `:transform`
  wrapped via `haselnuss.sandbox/wrap-fn` so a LATER call into it -- once
  per matched node, not merely once at load, which is exactly why
  `wrap-fn`'s own call-time re-binding matters here at all
  (`haselnuss.sandbox`'s own docstring) -- goes through the same bound
  `sandbox/sci-call!` applies to loading the file itself. `:select`,
  `:name` and `:scope` pass through untouched: `:select` is read only by
  `haselnuss.select/matches?`, plain data plus, at most, a sandboxed
  function value already safe to call directly (`fn?` is true of a sci
  function value, verified live); `:name`/`:scope` are inert data
  `haselnuss.transform` reads directly. `ctx` is `wrap-fn`'s own -- the
  forked sci context this entry's `:transform` was extracted from,
  re-established around every later call."
  [label entry path ctx opts]
  (update entry :transform sandbox/wrap-fn ctx (str label " (" path ")'s :transform") opts))

(defn load-transforms
  "Loads every sidecar file named in `paths` (in order), returning a
  vector of `haselnuss.transform/transform-document`-ready rules --
  `haselnuss.cli/run` is what actually applies them, immediately after
  parsing, on every target (doc-7 decision 1).

  `opts`: `:timeout-ms`/`:output-limit` (`haselnuss.sandbox/sci-call!`'s
  own bounds), `:ctx` (a `delay` of an already-forked sci context --
  `haselnuss.sandbox/fork` -- to use instead of forking a fresh one;
  `haselnuss.cli` passes one shared with `haselnuss.extensions.sidecar/
  load-extensions` when a build uses both `--extensions` and
  `--transform`, so the two loaders share ONE fork rather than each
  getting its own -- `haselnuss.sandbox`'s own docstring, ISOLATION).
  There is no `:trusted?` here (this namespace's own docstring, NO TRUST
  FLAG IS INVOLVED) -- `--trusted-extensions` grants a `--transform` rule
  nothing, and this function accepts no option that could change that.

  All of `paths` share ONE fork for this one call (whether freshly built
  here or supplied via `:ctx`) -- so files loaded together may reference
  each other's `def`s, and this call's own isolation guarantee holds for
  the whole set at once, not file by file (mirrors `haselnuss.extensions.
  sidecar/load-extensions`'s own TASK-90 AC #2; this task's own AC #7 is
  the same guarantee, restated for a rule sidecar file rather than an
  extension one).

  A `path` naming no file is a hard `ex-info` (`::missing-transform-
  file`) naming it, mirroring `haselnuss.cli/load-overlays!`'s own
  established convention for `--overlay`. A file whose own evaluation
  times out, errors, or returns something that does not match this
  namespace's own rule shape is `sandbox/sci-call!`/`validate-rule!`'s
  own `ex-info`, already naming `path` and the rule (AC #8)."
  ([paths] (load-transforms paths {}))
  ([paths opts]
   (let [ctx (or (:ctx opts) (delay (sandbox/fork)))]
     (into []
           (mapcat (fn [path]
                     (let [file (io/file path)]
                       (when-not (.isFile file)
                         (throw (ex-info (str "transform file not found: " path)
                                         {:type ::missing-transform-file :path path})))
                       (let [source (slurp file)
                             value (sandbox/sci-call! #(sci/eval-string* @ctx source)
                                                      (str "transform file " path) opts false)]
                         (map (fn [[label entry]] (normalize-rule label entry path @ctx opts))
                              (rule-entries value path))))))
           paths))))
