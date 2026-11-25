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
            [haselnuss.ast :as ast]
            [haselnuss.sandbox :as sandbox]
            [haselnuss.select :as select]
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

(defn- holds-var?
  "True when `x` holds a sci var anywhere in it."
  [x]
  (boolean (some #(instance? sci.lang.Var %) (tree-seq coll? seq x))))

(defn- show
  "`x` printed for a refusal message -- unless it holds a sci var, whose
  printing reads metadata the script controls (TASK-214)."
  [x]
  (if (holds-var? x) "a var" (pr-str x)))

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
  ;; TASK-214: a sci var prints through its own metadata, which the script
  ;; can set to anything (`alter-meta!`), and `haselnuss.transform` prints
  ;; `:select` to name an unnamed rule -- refused here, before anything
  ;; prints it, rather than copied, since a function says the same thing.
  (when (holds-var? (:select entry))
    (malformed! path (str label "'s :select holds a var (#'...); use the function itself") {}))
  (when-not (selector-shape? (:select entry))
    (malformed! path (str label "'s :select must be a keyword, a map, a vector or a function, got "
                          (show (:select entry)))
                {:value entry}))
  (when-not (contains? entry :transform)
    (malformed! path (str label " has no :transform") {:value entry}))
  (when-not (ifn? (:transform entry))
    (malformed! path (str label "'s :transform is not a function: " (show (:transform entry)))
                {:value entry}))
  (when (and (contains? entry :name) (not (string? (:name entry))))
    (malformed! path (str label "'s :name must be a string, got " (show (:name entry)))
                {:value entry}))
  (when (and (contains? entry :scope) (not (set? (:scope entry))))
    (malformed! path (str label "'s :scope must be a set, got " (show (:scope entry)))
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

(defn- script-code?
  "True when selector `select` holds script code anywhere in it -- a
  function or a regex (a var is refused by `validate-rule!`), at the top or nested in a map value, a
  `:prop` map or a vector -- which `haselnuss.select/matches?` would call
  or match (a regex can backtrack catastrophically) on the calling thread.
  A selector of plain data alone compares only plain data the load
  already copied (`haselnuss.sandbox/plain-copy`), so it runs host-side."
  [select]
  (boolean (some (fn [x] (or (fn? x) (instance? java.util.regex.Pattern x)))
                 (tree-seq coll? seq select))))

(defn- sandboxed-matcher
  "The `haselnuss.transform` `:matcher` for selector `select`, or nil when
  it holds no script code and so runs host-side as it is. `sandboxed`
  turns a selector into a host predicate that runs `select/matches?` on it
  in the sandbox.

  Each sandboxed match costs a worker thread (about 70us; see the TASK-214
  notes), so a map or vector selector is split, and the sandbox runs only
  when the host-side part leaves the answer open:

  - a map is an AND: its plain-data entries are matched host-side, its
    script entries in the sandbox only when those all matched, so
    `{:t :list :text #\"^TODO:\"}` runs its regex only on lists. A map's
    entries have no order an author controls, so this gives the same
    match as the whole map; only which of two failing entries raises
    first can differ (a malformed plain entry now always raises before a
    throwing script one), as it already could between hash orders.
  - a vector is an OR that `select/matches?` evaluates left to right,
    short-circuiting (its docstring): only the LEADING run of plain-data
    elements is matched host-side, and everything from the first element
    holding script code on is matched in the sandbox, in order -- so
    `[(fn [_] true) {:prop 3}]` still matches without reaching the
    malformed element, and `[(fn [_] (throw ...)) :para]` still throws on
    a paragraph, exactly as before."
  [select sandboxed]
  (when (script-code? select)
    (cond
      (map? select)
      (let [plain (into {} (remove script-code?) select)
            script (sandboxed (into {} (filter script-code?) select))]
        (fn [node] (and (select/matches? plain node) (script node))))

      (vector? select)
      (let [[prefix rest-of] (split-with (complement script-code?) select)
            prefix (vec prefix)
            script (sandboxed (vec rest-of))]
        (fn [node] (or (select/matches? prefix node) (script node))))

      :else (sandboxed select))))

;; Source positions on a sandboxed `:transform`'s result (TASK-309).
;;
;; The sandbox's `plain-copy` drops all metadata from what a script
;; returns, so that a script cannot forge a source position -- and with it
;; every `::ast/source` the rule's own input carried, so even a no-op rule
;; left backlinks and positions without a line. `keep-positions` puts them
;; back HOST-SIDE, from the rule's own input node only: nothing the script
;; returns is ever read as metadata, and only `::ast/source` is copied.
;;
;; No position beats a wrong one (an editor jump to the wrong paragraph),
;; so the matching is deliberately conservative:
;;
;; - The rule's input node keeps its position when the result has the
;;   same `:t` (it is the node the rule was called on); its map-valued
;;   keys are treated the same way, its vector-valued keys element-wise.
;; - A vector element is never recursed into. It gets an old element --
;;   that element itself, with every position inside it -- only when it is
;;   `=` to it and nothing else could claim it (`match-vector`): same
;;   index in a vector of unchanged length, a value occurring once in each
;;   vector wherever it moved, or a repeated value occurring as often in
;;   the old stretch as in the new between the matched elements around it.
;;   Every other element (rewritten, new, or ambiguous) has no position,
;;   and no old element is given to two new ones. So a position always
;;   describes content equal to the node's own and is held by no other
;;   node of the result.
;;
;; This runs after the sandboxed call has returned, outside its timeout,
;; so the work is bounded by the result's size, which the sandbox caps
;; (`sandbox/default-output-limit` values): equality is looked up through
;; hash buckets (`hash-buckets`), runs only between elements whose hashes
;; agree, and gives up past `bucket-cap` -- a document can hold any number
;; of distinct paragraphs with one hash (Clojure hashes a string by
;; `String.hashCode`, which is easy to collide), and a deep `=` against
;; each of them is what made an earlier version quadratic. Each element of
;; the result is compared against a bounded number of others, at its own
;; level only, so the `=` work is at most a constant times the result's
;; size; the rest is linear in the lengths of the input's vectors (plus
;; hashing the input once: Clojure caches a collection's hash).

(def ^:private bucket-cap
  "The most elements of one hash a stretch of a vector may hold for any of
  them to be matched by value (`match-vector`, steps 2 and 3). Real
  documents repeat a block a few times at most (an empty paragraph, a
  separator); a bigger bucket is either many equal elements, which a value
  match could not tell apart anyway, or a hash collision."
  8)

(defn- same-value?
  "`=`, checking the (cached) hashes first."
  [a b]
  (and (== (hash a) (hash b)) (= a b)))

(defn- hash-buckets
  "`v`'s indices grouped by the hash of the element at each, each group in
  ascending order."
  ^java.util.HashMap [v]
  (let [buckets (java.util.HashMap.)]
    (dotimes [i (count v)]
      (let [h (hash (nth v i))
            ^java.util.ArrayList bucket (.get buckets h)]
        (if bucket
          (.add bucket i)
          (.put buckets h (doto (java.util.ArrayList.) (.add i))))))
    buckets))

(defn- first-above
  "The position in ascending index list `bucket` of its first index above
  `i` (its size when there is none)."
  ^long [^java.util.ArrayList bucket ^long i]
  (let [r (java.util.Collections/binarySearch bucket (Long/valueOf (inc i)))]
    (if (neg? r) (- (inc r)) r)))

(defn- equal-indices
  "The indices i of `v`, `lo` < i < `hi` and `(ok? i)`, whose element is
  `=` to `x`, found through `buckets` (`v`'s `hash-buckets`); nil when more
  than `bucket-cap` indices in that range share `x`'s hash."
  [v ^java.util.HashMap buckets x lo hi ok?]
  (if-let [^java.util.ArrayList bucket (.get buckets (hash x))]
    (let [from (first-above bucket lo)
          to (first-above bucket (dec hi))]
      (when (<= (- to from) (long bucket-cap))
        (into [] (filter #(and (ok? %) (= x (nth v %)))) (.subList bucket from to))))
    []))

(defn- match-vector
  "`new-v`, a rewritten copy of vector `old-v`, with each element that is
  `=` to an element of `old-v` replaced by that element itself (so it
  keeps every source position in it), in three steps:

  1. when the two are the same length, the element at the same index (an
     unchanged vector, or one edited in place, whatever it repeats);
  2. else the one equal element, when the value occurs exactly once in
     each vector -- wherever it moved, so a reordering keeps positions;
  3. else, for a value that repeats: between the nearest elements before
     and after it that steps 1-2 matched (the vector's ends counting as
     matched), when their old elements are in order, the value's
     occurrences in that stretch of `new-v` take its free occurrences in
     the same stretch of `old-v` in order -- only when there are as many
     of each. So dropping the first of [alpha beta alpha] leaves the
     second alpha its own line, but dropping one of two adjacent equal
     blocks leaves the survivor none: which of them went is a guess.

  Each old element is matched at most once; everything else is left as it
  is, without a position."
  [old-v new-v]
  (let [n (count old-v)
        m (count new-v)
        match (long-array m -1)
        taken (boolean-array n)
        take! (fn [j i]
                (when-not (aget taken (int i))
                  (aset match (int j) (long i))
                  (aset taken (int i) true)))
        old-buckets (hash-buckets old-v)
        new-buckets (hash-buckets new-v)]
    (when (== n m)
      (dotimes [j m]
        (when (same-value? (nth new-v j) (nth old-v j))
          (take! j j))))
    (dotimes [j m]
      (when (neg? (aget match j))
        (let [x (nth new-v j)
              olds (equal-indices old-v old-buckets x -1 n any?)
              news (equal-indices new-v new-buckets x -1 m any?)]
          (when (and (= 1 (count olds)) (= 1 (count news)))
            (take! j (first olds))))))
    (let [anchors (aclone match)
          taken-before (aclone taken)
          free? (fn [i] (not (aget taken-before (int i))))
          before (long-array m)
          after (long-array m)]
      (loop [j 0 p -1]
        (when (< j m)
          (aset before j p)
          (recur (inc j) (if (neg? (aget anchors j)) p j))))
      (loop [j (dec m) q m]
        (when (>= j 0)
          (aset after j q)
          (recur (dec j) (if (neg? (aget anchors j)) q j))))
      (dotimes [j m]
        (when (neg? (aget anchors j))
          (let [p (aget before j)
                q (aget after j)
                lo (if (neg? p) -1 (aget anchors p))
                hi (if (== q m) n (aget anchors q))
                x (nth new-v j)]
            (when (< lo hi)
              (let [olds (equal-indices old-v old-buckets x lo hi free?)
                    news (equal-indices new-v new-buckets x p q any?)]
                (when (and (seq olds) (= (count olds) (count news)))
                  (take! j (nth olds (.indexOf ^java.util.List news j))))))))))
    (into []
          (map-indexed (fn [j x]
                         (let [i (aget match j)]
                           (if (neg? i) x (nth old-v i)))))
          new-v)))

(declare reattach-value)

(defn- reattach-map
  "`new-m`, a rewritten copy of map `old-m`: when both have the same `:t`
  (nil included), with `old-m`'s source position and each of its values
  reattached against `old-m`'s value under the same key
  (`reattach-value`); otherwise `new-m` as it is."
  [old-m new-m]
  (if (= (:t old-m) (:t new-m))
    (cond-> (reduce-kv (fn [acc k v]
                         (if-let [[_ old-v] (find old-m k)]
                           (assoc acc k (reattach-value old-v v))
                           acc))
                       new-m new-m)
      (ast/source old-m) (with-meta {::ast/source (ast/source old-m)}))
    new-m))

(defn- reattach-value
  "`new-v`, rewritten from `old-v`, with source positions put back from
  `old-v`: a map by `reattach-map`, a vector by `match-vector`."
  [old-v new-v]
  (cond
    (and (map? old-v) (map? new-v)) (reattach-map old-v new-v)
    (and (vector? old-v) (vector? new-v)) (match-vector old-v new-v)
    :else new-v))

(defn- reattach-result
  "A sandboxed `:transform`'s answer (a node, nil, or a sequential of
  nodes) with source positions put back from the `node` it was called on:
  a node, or the only node of a sequential, as its rewrite
  (`reattach-value`); several nodes as a vector rewritten from `[node]`
  (`match-vector`), so the one equal to `node`, if exactly one is, is
  `node` itself."
  [node result]
  (cond
    (map? result) (reattach-value node result)
    (and (sequential? result) (= 1 (count result))) [(reattach-value node (first result))]
    (sequential? result) (match-vector [node] (vec result))
    :else result))

(defn- keep-positions
  "`transform-fn` (sandboxed) as a function that restores the input node's
  source positions on whatever it returns (`reattach-result`; the comment
  above `bucket-cap` has the rules)."
  [transform-fn]
  (fn [node ctx]
    (reattach-result node (transform-fn node ctx))))

(defn- normalize-rule
  "Validated rule map `entry` (from a sidecar file, `path`'s own source,
  named `label` -- `rule-entries`' own, so a later `sci-call!` failure
  names the identical rule `validate-rule!` would have), turned into a
  `haselnuss.transform/transform-document`-ready rule: `:transform`
  wrapped via `haselnuss.sandbox/wrap-fn` so a LATER call into it -- once
  per matched node, not merely once at load, which is exactly why
  `wrap-fn`'s own call-time re-binding matters here at all
  (`haselnuss.sandbox`'s own docstring) -- goes through the same bound
  `sandbox/sci-call!` applies to loading the file itself.

  `:select` stays as the script wrote it, which is what names the rule in
  `haselnuss.transform`'s messages, but when it holds script code
  (`script-code?`) the rule also gets a `:matcher`
  (`sandboxed-matcher`): the `select/matches?` call on that code, run
  through `wrap-fn` too, so a looping or
  throwing selector function, or a catastrophically backtracking regex,
  hits the same timeout, runs with the script's sci context, and fails as
  a sandbox error naming the rule rather than hanging the build or
  handing the script's own exception to the host (TASK-214). Any
  `:matcher` the script itself supplied is dropped first.
  `:name`/`:scope` are inert data `haselnuss.transform` reads directly.
  `ctx` is `wrap-fn`'s own -- the forked sci context this entry's
  functions were extracted from, re-established around every later call."
  [label entry path ctx opts]
  (let [matcher (sandboxed-matcher (:select entry)
                                   (fn [selector]
                                     (sandbox/wrap-fn (fn [node] (select/matches? selector node))
                                                      ctx (str label " (" path ")'s :select") opts)))]
    (cond-> (-> entry
                (dissoc :matcher)
                (update :transform (fn [f]
                                     (keep-positions
                                      (sandbox/wrap-fn f ctx (str label " (" path ")'s :transform") opts)))))
      matcher (assoc :matcher matcher))))

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
                                                      (str "transform file " path) opts :spec)]
                         (map (fn [[label entry]] (normalize-rule label entry path @ctx opts))
                              (rule-entries value path))))))
           paths))))
