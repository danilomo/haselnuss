(ns haselnuss.extensions.sidecar
  "Loads author-defined directives from a sidecar Clojure file (TASK-90,
  doc-6's own payoff milestone): an author writes a plain `.clj` file
  defining how a directive renders to HTML and LaTeX, points `--extensions`
  at it, and the build picks it up with no recompile. `haselnuss.cli`'s own
  job is naming the CLI flags; this namespace owns validating the file's
  own result and turning it into `haselnuss.registry/register`-ready
  extension maps -- `haselnuss.sandbox` (TASK-103) owns actually
  evaluating it safely, generalized out from underneath this namespace
  once `haselnuss.extensions.rules` needed the identical machinery for a
  differently-shaped sidecar file (`--transform`'s own rule maps) rather
  than registry entries: 'two independent sandboxes with two independent
  policies is how a sandbox stops being one' (doc-7 decision 5). See that
  namespace's own docstring for the sandbox itself -- what `:classes`/
  `:deny`/`:load-fn` are set to and why, the timeout/output-cap/output-
  capture bound `sci-call!` applies to every call, and `wrap-fn`'s own
  call-time context re-establishment; this namespace's docstring below
  covers only what is specific to an EXTENSION sidecar file.

  THE SIDECAR CONTRACT: a file's own LAST top-level form must evaluate to
  a map, or a COLLECTION of maps (a vector, list, lazy seq -- what
  `for`/`map`/`filter` return, and the natural way to define several
  directives at once -- or a set; `haselnuss.sandbox/collection-of-maps?`
  matches `haselnuss.registry/register-all`'s own genuine tolerance rather
  than being narrower than it), each in `haselnuss.registry/register`'s
  own `{:name :kind? :emit? :lower?}` shape (the same shape
  `haselnuss.extensions.collapsable/extension` already is) -- an author
  who has read that namespace's docstring, or copied that extension map,
  already knows this shape. `:emit`'s per-target values and `:lower`
  (when present) are plain functions; see `load-extensions` for the exact
  validation and wrapping.

  ISOLATION AND SHARING (TASK-103): `load-extensions` forks its own fresh
  `haselnuss.sandbox/fork` per call, `delay`-wrapped, UNLESS a caller
  passes one of its own via `opts`' `:ctx` (also a delay of a forked
  context) -- `haselnuss.cli` does exactly that when a single build passes
  both `--extensions` and `--transform`, so the two loaders share ONE
  fork rather than each getting its own (`haselnuss.sandbox`'s own
  docstring, ISOLATION): an `--extensions` file and a `--transform` file
  in the same build may then reference each other's top-level `def`s, the
  same way two `--extensions` files already can when loaded together.
  Never reused across a LATER, separate call regardless -- verified live,
  when this all still lived directly in this namespace: a `def` made in
  one fork is invisible both to a sibling fork of the same base and to a
  later, fresh fork of it, so nothing one document's sidecar file(s)
  define outlives or leaks into a later, unrelated conversion in the same
  process."
  (:require [clojure.java.io :as io]
            [haselnuss.registry :as registry]
            [haselnuss.sandbox :as sandbox]
            [sci.core :as sci]))

(defn- blank-name?
  [x]
  (not (and (string? x) (seq x))))

(defn- malformed!
  [path reason data]
  (throw (ex-info (str "malformed extension in " path ": " reason)
                  (merge {:type ::malformed-extension :path path} data))))

(defn- validate-entry!
  "Throws a `::malformed-extension` `ex-info` naming both `path` and what
  is wrong with extension-spec map `entry` (AC #10), or returns `entry`
  unchanged. Deliberately as lenient as `haselnuss.registry/register`
  itself about anything beyond `:name`/`:emit`/`:lower` -- an unknown key
  passes through untouched, exactly as `register` already tolerates,
  rather than this namespace maintaining a second, stricter schema for
  the same shape."
  [path entry]
  (when-not (map? entry)
    (malformed! path (str "expected an extension map, got " (pr-str entry)) {:value entry}))
  (when (blank-name? (:name entry))
    (malformed! path (str "extension is missing a non-blank :name: " (pr-str entry)) {:value entry}))
  (let [emit (:emit entry)]
    (when (some? emit)
      (when-not (map? emit)
        (malformed! path (str "extension " (pr-str (:name entry)) "'s :emit must be a map "
                              "of target keyword to a rendering function, got " (pr-str emit))
                    {:name (:name entry) :value emit}))
      (doseq [[target f] emit]
        (when-not (ifn? f)
          (malformed! path (str "extension " (pr-str (:name entry)) "'s :emit " (pr-str target)
                                " is not a function: " (pr-str f))
                      {:name (:name entry) :target target :value f})))))
  (when (and (some? (:lower entry)) (not (ifn? (:lower entry))))
    (malformed! path (str "extension " (pr-str (:name entry)) "'s :lower is not a function: "
                          (pr-str (:lower entry)))
                {:name (:name entry) :value (:lower entry)}))
  entry)

(defn- normalize-entry
  "Validated extension-spec map `entry` (from a sidecar file, `path`'s own
  source), turned into a `haselnuss.registry/register`-ready extension map:
  every `:emit` target function and the `:lower` function (when present)
  wrapped via `haselnuss.sandbox/wrap-fn` so a LATER call into either goes
  through the same bound `sandbox/sci-call!` applies to loading the file
  itself; every `:emit` function additionally marked
  `registry/renderer-v2` (every sidecar-authored renderer speaks TASK-89's
  v2 contract only -- there is no legacy string-returning renderer a
  brand-new sidecar file could have predated); `:trusted`/`:trusted?`, if
  the entry carried either, is DROPPED and replaced with `trusted?` --
  this call's own, CLI-decided value, never the sidecar file's own --
  which is what keeps a sidecar from marking its own output trusted (AC
  #11). `:kind`, if present, passes through untouched: it is plain data
  (a `LabelLexicon` merge-fragment, `register`'s own docstring), nothing
  to wrap. `ctx` is `wrap-fn`'s own -- the forked sci context this entry's
  functions were extracted from, re-established around every later call."
  [entry path ctx trusted? opts]
  (let [describe-for (fn [suffix] (str "extension " (pr-str (:name entry)) " (" path ")" suffix))]
    (-> entry
        (dissoc :trusted :trusted?)
        (assoc :trusted? trusted?)
        (cond->
         (:emit entry)
          (assoc :emit (into {}
                             (map (fn [[target f]]
                                    [target (registry/renderer-v2
                                             (sandbox/wrap-fn f ctx
                                                              (describe-for (str "'s :" (name target)
                                                                                 " renderer"))
                                                              opts))]))
                             (:emit entry)))
          (:lower entry)
          (assoc :lower (sandbox/wrap-fn (:lower entry) ctx (describe-for "'s :lower rule") opts))))))

(defn- extension-entries
  "The extension-spec maps `value` -- a sidecar file's own final top-level
  form -- must be: `value` itself if it is a map, `value` (realized into a
  vector) if it is a `haselnuss.sandbox/collection-of-maps?` collection,
  else a `::malformed-extension` naming `path` (AC #10). A single map is
  the common case (one directive per file); a collection is for a file
  defining several at once.

  `value` reaching here has already been settled by `sandbox/sci-call!`
  (`:spec`), on the worker and under its timeout: copied into plain data
  and functions, every lazy seq in it realised, anything else rejected --
  so nothing below, the `pr-str`s in its error messages included, can run
  script code on the calling thread (TASK-211)."
  [value path]
  (cond
    (map? value) [value]
    (sandbox/collection-of-maps? value) (vec value)
    :else (malformed! path
                      (str "a sidecar file's last form must evaluate to an extension map "
                           "or a collection of them (a vector, list, lazy seq, or set), got "
                           (pr-str value))
                      {:value value})))

(defn load-extensions
  "Loads every sidecar file named in `paths` (in order), returning a
  vector of `haselnuss.registry/register`-ready extension maps --
  `haselnuss.cli`'s own `register-all` is what actually installs them.

  `opts`: `:trusted?` (default false -- AC #11, forced onto EVERY
  extension every one of `paths` defines, regardless of what the file's
  own map says; see `normalize-entry`), `:timeout-ms`/`:output-limit`
  (`haselnuss.sandbox/sci-call!`'s own bounds, both defaulting to that
  namespace's own constants), `:ctx` (a `delay` of an already-forked sci
  context -- `haselnuss.sandbox/fork` -- to use instead of forking a fresh
  one; `haselnuss.cli` passes one shared between this and
  `haselnuss.extensions.rules/load-transforms` when a build uses both
  `--extensions` and `--transform`, per this namespace's own docstring,
  ISOLATION AND SHARING).

  All of `paths` share ONE fork for this one call (whether freshly built
  here or supplied via `:ctx`) -- so files loaded together may reference
  each other's `def`s, and so this call's own isolation guarantee (this
  namespace's own docstring, AC #2) holds for the whole set at once, not
  file by file.

  A `path` naming no file is a hard `ex-info` (`::missing-extension-file`)
  naming it, mirroring `haselnuss.cli/load-overlays!`'s own established
  convention for the identical situation. A file whose own evaluation
  times out, errors, or returns something that does not match this
  namespace's own extension-spec shape is `sandbox/sci-call!`/
  `validate-entry!`'s own `ex-info`, already naming `path` (AC #10)."
  ([paths] (load-extensions paths {}))
  ([paths {:keys [trusted? ctx] :or {trusted? false} :as opts}]
   (let [ctx (or ctx (delay (sandbox/fork)))]
     (into []
           (mapcat (fn [path]
                     (let [file (io/file path)]
                       (when-not (.isFile file)
                         (throw (ex-info (str "extension file not found: " path)
                                         {:type ::missing-extension-file :path path})))
                       (let [source (slurp file)
                             value (sandbox/sci-call! #(sci/eval-string* @ctx source)
                                                      (str "extension file " path) opts :spec)]
                         (->> (extension-entries value path)
                              (map (fn [entry]
                                     (normalize-entry (validate-entry! path entry)
                                                      path @ctx trusted? opts))))))))
           paths))))
