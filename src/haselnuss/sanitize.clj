(ns haselnuss.sanitize
  "The pass that neutralises the two ways an authored document can put
  script into a converted page without writing raw HTML (TASK-136): a
  link or image whose URL carries an executable scheme, and an attribute
  that is an event handler.

  The parser refuses raw HTML, so a page built from a document is meant to
  be safe to open. But `[x](javascript:alert(1))` and `![x](data:...)`
  would otherwise reach `href`/`src` unchanged, and an attribute group
  (`{onclick=\"...\"}`) or a `describe` prop is copied onto the element as a
  real HTML attribute. This pass closes both, and says so, rather than
  dropping silently:

  - A `:link` or `:image` whose target has a scheme other than `http`,
    `https` or `mailto` is dropped. A link is replaced by its own link text
    (the words survive, the destination does not); an image by its alt text,
    or by nothing when it has none. A target with no scheme at all -- a
    relative path, a `#fragment` -- passes. Browsers ignore tabs, newlines
    and leading control characters inside a scheme (`java\\tscript:`), so
    they are stripped before the test, and the test is case-insensitive.
    A one-letter scheme is a Windows drive letter (`C:\\img.png`), not a
    scheme, and passes.
  - A resolved `:book-ref` or `:book-quote` whose `:url` (filled in from
    its library's URL template, TASK-155) fails the same test loses the
    `:url` (TASK-156): the link keeps its text and the quote its passage
    and source line, neither linked.
  - An Attr prop whose name starts with `on` (case-insensitive) is removed;
    so is a prop named `href`, `src`, `poster`, `action`, `formaction`,
    `xlink:href`, `data`, `srcset`, `ping`, `background`, `cite`,
    `longdesc`, `dynsrc` or `lowsrc` whose value fails the same scheme test (every entry of a
    `srcset` or `ping` list). The same rule covers props `describe`
    copied onto a node, because this pass runs after overlays are applied.

  Each removal yields one `{:type :message ...}` diagnostic, the shape every
  `haselnuss.resolver` warning has, naming the offending URL or attribute.
  `style` is deliberately left alone: it is how authors legitimately size
  and colour things, and CSS cannot run script in a current browser.

  A directive whose `:fallback` is a `:poster` with an unsafe `:src` has
  that fallback replaced by `:drop` (lowering would otherwise build an
  image from it after this pass).

  This is not a general HTML sanitiser; README.md's 'Converting a document
  you do not trust' states what it does and does not promise.

  TASK-215 reuses the same rules for what an UNTRUSTED extension (one not
  loaded with `--trusted-extensions`) emits: `attribute-diagnostic` for its
  hiccup attributes (`haselnuss.emit.html`), and `untrusted-fragment` for
  the AST it hands its ctx's `:render-blocks`/`:render-inlines` or returns
  from its `:lower` rule (`haselnuss.lower`) -- this pass's rules plus
  `tex-problem`, an ALLOW-LIST of the TeX such output may carry. Both
  emitters and the lowering pass report what they drop through
  `*untrusted-diagnostics*`.

  TASK-219 turns the same allow-list on the document's own TeX in the
  LaTeX build under `--untrusted` (`vet-untrusted-document`), and, flag or
  not, drops `\\href`/`\\url` from document math there
  (`neutralise-math-links`)."
  (:require [clojure.string :as str]
            [haselnuss.ast :as ast]
            [haselnuss.walk :as walk]))

(def ^:private allowed-schemes #{"http" "https" "mailto"})

(def ^:private url-props
  "The closed list of attribute names whose value is a URL (or a list of
  them) and is scheme-tested, lower-cased. `srcset` and `ping` hold several.
  `dynsrc`/`lowsrc` are legacy IE image attributes (TASK-215 review)."
  #{"href" "src" "poster" "action" "formaction" "xlink:href" "data"
    "srcset" "ping" "background" "cite" "longdesc" "dynsrc" "lowsrc"})

(defn- scheme
  "The lower-cased URL scheme of `url` as a browser would read it, or nil
  when it has none (or a one-letter one, a drive letter)."
  [url]
  (let [cleaned (-> url
                    (str/replace #"[\t\n\r]" "")
                    (str/replace #"^[\u0000-\u0020]+" ""))]
    (when-let [[_ s] (re-find #"^([A-Za-z][A-Za-z0-9+.\-]*):" cleaned)]
      (when (> (count s) 1)
        (str/lower-case s)))))

(defn unsafe-url?
  "True when `url` has a scheme that is not on the allow-list."
  [url]
  (boolean (when-let [s (scheme url)] (not (contains? allowed-schemes s)))))

(defn- unsafe-prop-value?
  "True when the URL-bearing attribute `k` (lower-cased) holds an unsafe
  URL. `srcset` is a comma-separated list of `url [descriptor]` candidates
  and `ping` a space-separated list of URLs; every entry is tested."
  [k v]
  (let [v (str v)]
    (case k
      "srcset" (boolean (some #(unsafe-url? (first (str/split (str/triml %) #"\s+")))
                              (str/split v #",")))
      "ping" (boolean (some unsafe-url? (str/split (str/triml v) #"\s+")))
      (unsafe-url? v))))

(defn- event-handler-name? [k]
  (str/starts-with? (str/lower-case k) "on"))

(defn attribute-diagnostic
  "The diagnostic for an HTML attribute named `k` (a string) holding `v`
  when it may not be copied into output -- an event handler, or a
  URL-bearing attribute whose URL fails the scheme test -- else nil. The
  one rule both this pass's Attr props and an untrusted extension's own
  hiccup attributes (`haselnuss.emit.html`, TASK-215) are held to."
  [k v]
  (cond
    (event-handler-name? k)
    {:type :event-handler-attribute
     :name k
     :message (str "Dropped the event-handler attribute " k "=\"" v
                   "\": an attribute that runs script is not copied into output.")}

    (and (url-props (str/lower-case k)) (unsafe-prop-value? (str/lower-case k) v))
    {:type :unsafe-url
     :url v
     :message (str "Dropped the attribute " k "=\"" v
                   "\": a URL with this scheme is not copied into output.")}

    :else nil))

(defn- sanitize-attr
  "`[attr' diagnostics]` for `attr`, with every offending prop removed."
  [attr]
  (reduce-kv
   (fn [[attr' diags] k v]
     (if-let [d (attribute-diagnostic k v)]
       [(update attr' :props dissoc k) (conj diags d)]
       [attr' diags]))
   [attr []]
   (:props attr)))

(defn- unsafe-url-diagnostic [what url]
  {:type :unsafe-url
   :url url
   :message (str "Dropped the " what " to \"" url
                 "\": only http, https, mailto and relative URLs are kept.")})

(defn sanitize-document
  "Runs the pass over `document`. Returns `{:document :diagnostics}`."
  [document]
  (let [diagnostics (volatile! [])
        note! (fn [ds] (vswap! diagnostics into ds))
        rewrite-fn
        (fn [_path ref-kind node]
          (let [fb (when (map? node) (:fallback node))
                node (if (and (= :poster (some-> (:kind fb) keyword)) (unsafe-url? (:src fb)))
                       (do (note! [(unsafe-url-diagnostic "poster fallback image" (:src fb))])
                           (assoc node :fallback {:kind :drop}))
                       node)
                node (if (and (map? node) (map? (:attr node)) (seq (get-in node [:attr :props])))
                       (let [[attr' ds] (sanitize-attr (:attr node))]
                         (note! ds)
                         (assoc node :attr attr'))
                       node)
                ;; TASK-156: a resolved book link or quote carries the
                ;; `:url` its library's template filled in, which the
                ;; HTML emitter writes into `href`/`cite`. The template is
                ;; the library owner's, not this document's, but it is
                ;; still not trusted: an unsafe one loses its URL, so the
                ;; link renders as text and the quote without a `cite`.
                node (if (and (map? node) (#{:book-ref :book-quote} (:t node))
                              (string? (:url node)) (unsafe-url? (:url node)))
                       (do (note! [(unsafe-url-diagnostic
                                    (if (= :book-ref (:t node)) "book link" "book quote's source link")
                                    (:url node))])
                           (dissoc node :url))
                       node)]
            (if (= ref-kind ::ast/inline)
              (case (:t node)
                :link (if (unsafe-url? (:target node))
                        (do (note! [(unsafe-url-diagnostic "link" (:target node))])
                            (vec (:inlines node)))
                        [node])
                :image (if (unsafe-url? (:src node))
                         (do (note! [(unsafe-url-diagnostic "image" (:src node))])
                             (if (str/blank? (:alt node))
                               []
                               [{:t :str :text (:alt node)}]))
                         [node])
                [node])
              [node])))
        walker {:table walk/table :scope walk/every-ref-kind :rewrite-fn rewrite-fn}
        result (walk/rewrite-post walker ::ast/document document)]
    {:document (if (vector? result) (first result) result)
     :diagnostics @diagnostics}))

;; ---------------------------------------------------------------------------
;; Untrusted extension output (TASK-215)

(def ^:dynamic *untrusted-diagnostics*
  "An atom of a vector the emitters add each untrusted-extension-output
  diagnostic to (TASK-215), or nil to collect none. `haselnuss.cli` binds
  it around emission and appends what it holds to the build's warnings; a
  caller of `emit-document` that wants them binds it the same way. A
  dynamic var rather than a ctx key so a renderer, which is handed that
  ctx, has no way to reach the collector and erase what it caused. Read
  once, on the thread that calls the renderer (`untrusted-reporter`), so a
  ctx callback a sandboxed renderer runs on its worker thread still
  reports to it."
  nil)

(defn untrusted-reporter
  "A function of a seq of diagnostics that adds them to the
  `*untrusted-diagnostics*` collector bound when this was called, or does
  nothing when none was."
  []
  (let [collector *untrusted-diagnostics*]
    (fn [diagnostics]
      (when (and collector (seq diagnostics))
        (swap! collector into diagnostics)))))

(defn extension-diagnostic
  "`diagnostic`, about the output of the untrusted renderer for directive
  `directive-name` (whose source position is `where`, or nil), with its
  message prefixed by both and the two recorded on it."
  [directive-name where diagnostic]
  (-> diagnostic
      (assoc :directive-name directive-name)
      (cond-> where (assoc :source where))
      (update :message #(str "directive " (pr-str directive-name)
                             (when where (str " (" where ")"))
                             ", untrusted extension output: " %))))

(def ^:private tex-math-words
  "The control words (case-sensitive, without the backslash) untrusted
  math may use (TASK-215): ordinary mathematics and nothing that reads or
  writes files, changes catcodes, builds a command name from text,
  defines anything, loads a package, loops, links or labels. `\\begin` and
  `\\end` are here but are further checked against `tex-math-environments`."
  (set (str/split
        (str
         ;; Greek
         "alpha beta gamma delta epsilon varepsilon zeta eta theta vartheta iota kappa "
         "varkappa lambda mu nu xi pi varpi rho varrho sigma varsigma tau upsilon phi "
         "varphi chi psi omega Gamma Delta Theta Lambda Xi Pi Sigma Upsilon Phi Psi Omega "
         "varGamma varDelta varTheta varLambda varXi varPi varSigma varUpsilon varPhi "
         "varPsi varOmega digamma "
         ;; large operators and named functions
         "sum prod coprod int iint iiint oint bigcup bigcap bigoplus bigotimes bigodot "
         "biguplus bigsqcup bigvee bigwedge lim limsup liminf sup inf max min arg det dim "
         "exp gcd hom ker lg ln log Pr sin cos tan cot sec csc arcsin arccos arctan sinh "
         "cosh tanh coth deg "
         ;; binary operators
         "pm mp times div cdot ast star circ bullet oplus ominus otimes oslash odot cap "
         "cup uplus sqcap sqcup vee wedge land lor lnot wr setminus smallsetminus amalg "
         "dagger ddagger diamond bigtriangleup bigtriangledown triangleleft triangleright "
         "lhd rhd unlhd unrhd "
         ;; relations
         "le leq ge geq ne neq approx equiv sim simeq cong propto prec succ preceq succeq "
         "ll gg subset supset subseteq supseteq subsetneq supsetneq sqsubset sqsupset "
         "sqsubseteq sqsupseteq in ni notin mid nmid parallel nparallel perp vdash dashv "
         "models asymp doteq bowtie smile frown lesssim gtrsim leqslant geqslant approxeq "
         "lessgtr gtrless ncong nsim "
         ;; arrows
         "to gets leftarrow rightarrow leftrightarrow Leftarrow Rightarrow Leftrightarrow "
         "longleftarrow longrightarrow longleftrightarrow Longleftarrow Longrightarrow "
         "Longleftrightarrow mapsto longmapsto uparrow downarrow updownarrow Uparrow "
         "Downarrow Updownarrow nearrow searrow swarrow nwarrow hookleftarrow "
         "hookrightarrow implies impliedby iff xrightarrow xleftarrow leftharpoonup "
         "rightharpoonup leftharpoondown rightharpoondown rightleftharpoons "
         "leftrightarrows rightleftarrows twoheadrightarrow rightsquigarrow "
         ;; symbols
         "infty partial nabla forall exists nexists neg emptyset varnothing aleph beth "
         "hbar ell wp Re Im angle measuredangle triangle backslash prime top bot surd "
         "flat natural sharp clubsuit diamondsuit heartsuit spadesuit ldots cdots vdots "
         "ddots dots dotsc dotsb dotsm dotsi colon therefore because square blacksquare "
         "Box imath jmath complement mho "
         ;; delimiters and sizing
         "left right middle big Big bigg Bigg bigl bigr Bigl Bigr biggl biggr Biggl Biggr "
         "bigm Bigm langle rangle lfloor rfloor lceil rceil lvert rvert lVert rVert vert "
         "Vert lbrace rbrace lbrack rbrack "
         ;; accents and over/under constructions
         "hat bar vec dot ddot dddot tilde acute grave breve check mathring widehat "
         "widetilde overline underline overbrace underbrace overrightarrow overleftarrow "
         "overleftrightarrow overset underset stackrel "
         ;; fonts and text
         "mathrm mathbf mathit mathsf mathtt mathcal mathbb mathfrak mathnormal boldsymbol "
         "text textrm textbf textit textsf texttt textup textnormal mbox operatorname "
         ;; fractions, roots, binomials, boxes
         "frac dfrac tfrac cfrac sqrt binom dbinom tbinom substack boxed phantom hphantom "
         "vphantom mathstrut smash "
         ;; spacing and style
         "quad qquad enspace thinspace medspace thickspace negthinspace displaystyle "
         "textstyle scriptstyle scriptscriptstyle limits nolimits not mathop mathrel "
         "mathbin mathord mathpunct mathopen mathclose "
         ;; modular arithmetic
         "bmod pmod mod pod "
         ;; alignments and amsmath tags
         "begin end hline tag notag nonumber")
        #"\s+")))

(def ^:private tex-math-symbols
  "The control symbols (a backslash and one non-letter) untrusted math may
  use: thin/medium/thick/negative space, a control space, a line break, and
  the escaped specials."
  #{\, \; \! \: \> \space \\ \{ \} \| \# \$ \% \& \_})

(def ^:private tex-math-environments
  "The environments `\\begin`/`\\end` in untrusted math may name -- amsmath's
  matrices and alignments. Any other name is refused, since `\\begin{x}`
  runs `\\x` (so `\\begin{input}` reaches `\\input`)."
  #{"matrix" "pmatrix" "bmatrix" "Bmatrix" "vmatrix" "Vmatrix" "smallmatrix" "cases"
    "aligned" "alignedat" "gathered" "split" "array"})

(defn- environment-arguments
  "The text of the arguments `\\begin{env}` takes at the start of `tail` --
  `\"\"` for an environment taking none -- or `::bad-arguments` when they
  are not the plain shape untrusted math may use (TASK-215 review): an
  `array` column spec of `l`, `c`, `r` and `|` only (after an
  optional `[t]`/`[b]`/`[c]`), and `alignedat`'s column count, digits
  only. A `*{100000000}{c}` repeat hangs pdflatex, and `@{}`, `!{}`,
  `>{}`, `<{}` and `p{}` carry arbitrary TeX, so a spec is never read
  any further than that."
  [env ^String tail]
  (let [shape (case env
                "array" #"^(\s*(?:\[[tbc]\])?\s*\{[lcr|\s]+\})"
                "alignedat" #"^(\s*\{\s*[0-9]{1,2}\s*\})"
                nil)]
    (if shape
      (or (second (re-find shape tail)) ::bad-arguments)
      "")))

(defn- ascii-letter? [c]
  (or (<= (int \a) (int c) (int \z)) (<= (int \A) (int c) (int \Z))))

(defn- letters-end
  "The index just past the run of ASCII letters in `s` starting at `i`."
  [^String s i]
  (let [n (count s)]
    (loop [j i]
      (if (and (< j n) (ascii-letter? (.charAt s j))) (recur (inc j)) j))))

(defn tex-problem
  "What makes TeX string `s` unacceptable from an untrusted extension
  (TASK-215) -- the offending token, as a string -- or nil when every
  control sequence in it is allowed. `math?` true (math `:tex`) allows
  `tex-math-words`, `tex-math-symbols` and `\\begin`/`\\end` of a
  `tex-math-environments` name; false (any other string an emitter writes
  unescaped: an image path, a citation key, a label) allows no control
  sequence at all.

  An ALLOW-LIST, after a deny-list proved leaky (TASK-215 review: expl3's
  `\\tex_input:D`/`\\ior_open:Nn`/`\\iow_now:Nn`, `\\UseName{input}`,
  `\\NewDocumentCommand`, `\\begin{input}` all got past one). Strict but
  best-effort: it tokenises the way TeX does under the document's normal
  catcodes, which nothing allowed here can change. On top of that it
  refuses:

  - any `^^`, TeX's character-code notation (`^^5cinput` is `\\input`);
  - an allowed control word immediately followed by `@` or `:` -- under
    `\\makeatletter` or expl3 syntax those are letters and the name would
    be a different, longer command (`\\tex_input:D`). `_` is NOT refused
    after a control word, because `\\sum_{i}` and `\\alpha_1` are ordinary
    subscripts, and an expl3 name needs a `:` signature or a function
    taking one to do anything;
  - an unbalanced brace, which would close a group the emitter opened."
  [^String s math?]
  (let [n (count s)
        words (if math? tex-math-words #{})
        symbols (if math? tex-math-symbols #{})]
    (if (str/includes? s "^^")
      "^^"
      (loop [i 0 depth 0]
        (if (>= i n)
          (when-not (zero? depth) "an unbalanced {")
          (let [c (.charAt s i)]
            (cond
              (= c \{) (recur (inc i) (inc depth))
              (= c \}) (if (zero? depth) "an unbalanced }" (recur (inc i) (dec depth)))
              (not= c \\) (recur (inc i) depth)
              (>= (inc i) n) "\\"
              :else
              (let [c2 (.charAt s (inc i))]
                (if (ascii-letter? c2)
                  (let [end (letters-end s (inc i))
                        word (subs s (inc i) end)
                        token (str "\\" word)
                        next-c (when (< end n) (.charAt s end))]
                    (cond
                      (not (contains? words word)) token
                      (contains? #{\@ \:} next-c) (str token next-c)
                      (contains? #{"begin" "end"} word)
                      (let [[whole env] (re-find #"^\s*\{([a-zA-Z]+\*?)\}" (subs s end))
                            after (+ end (count whole))
                            spec (when (and env (= "begin" word))
                                   (environment-arguments env (subs s after)))]
                        (cond
                          (not (contains? tex-math-environments env))
                          (str token (subs s end (min n (+ end 16))))

                          (= spec ::bad-arguments)
                          (str token (subs s end (min n (+ after 24))))

                          :else (recur (+ after (count spec)) depth)))
                      :else (recur end depth)))
                  (if (contains? symbols c2)
                    (recur (+ i 2) depth)
                    (str "\\" c2)))))))))))

(defn- escaped-text?
  "True for a field both emitters escape (or set verbatim, or, for LaTeX,
  never write) rather than splicing as TeX: a `:str`, `:code` or
  `:code-block`'s own `:text`, and an image's `:alt` (escaped in HTML, not
  rendered by LaTeX, and an escaped `:str` when sanitising drops the image)."
  [node k]
  (or (and (= :text k) (contains? #{:str :code :code-block} (:t node)))
      (and (= :alt k) (= :image (:t node)))))

(defn- raw-argument?
  "True for a field the LaTeX emitter splices into a command argument
  as written, with no escaping at all: an image's `:src`
  (`\\includegraphics`) and a citation item's `:key` (`\\citep`)."
  [node k]
  (or (and (= :src k) (= :image (:t node)))
      (and (= :key k) (nil? (:t node)))))

(defn- raw-argument-problem
  "`tex-problem` for a non-math string, plus (TASK-215 review) the
  characters that alter or break compilation in a raw command argument:
  `%` comments out the rest of the line, `#` is a parameter, `&` an
  alignment tab, `$` and `^` math, `~` an active character."
  [s]
  (or (tex-problem s false)
      (some (fn [c] (when (str/includes? s c) c)) ["%" "#" "&" "$" "^" "~"])))

(defn- tex-reachable
  "Every `[string kind source]` in AST fragment `x` that an emitter may
  splice as TeX or hand MathJax -- every string leaf except `escaped-text?`
  ones. `kind` is `:math` for a `:math-inline`/`:math-block`'s own `:tex`,
  `:raw` for a `raw-argument?`, else `:other`. `source` is the position
  (`haselnuss.ast/source`) of the nearest node enclosing the string that
  has one, or `src` (TASK-219: what a refusal of the document's own TeX
  is located by)."
  ([x] (tex-reachable x nil))
  ([x src]
   (cond
     (string? x) [[x :other src]]
     (map? x) (let [src (or (ast/source x) src)]
                (mapcat (fn [[k v]]
                          (cond
                            (and (string? v) (escaped-text? x k)) nil
                            (and (string? v) (= :tex k) (contains? #{:math-inline :math-block} (:t x)))
                            [[v :math src]]
                            (and (string? v) (raw-argument? x k)) [[v :raw src]]
                            :else (tex-reachable v src)))
                        x))
     (coll? x) (mapcat #(tex-reachable % src) x)
     :else nil)))

(defn- reachable-problem
  "The `tex-problem` (math or not) or `raw-argument-problem` for a string
  `tex-reachable` reported as `kind`, or nil when it passes."
  [s kind]
  (case kind
    :math (tex-problem s true)
    :raw (raw-argument-problem s)
    (tex-problem s false)))

(defn- authored-tex
  "The strings `directive` itself holds where an emitter would splice them
  as TeX (`tex-reachable`) -- what the document authored AS TeX, and so
  trusted, as far as a renderer handed `directive` is concerned. Escaped
  prose (a `:str` or `:code`'s `:text`) is not in it: moving a code
  sample's `\\input{...}` into a math node is the extension's doing."
  [directive]
  (into #{} (map first) (tex-reachable directive)))

(defn- sanitize-fragment
  "Runs `sanitize-document` over `nodes`, a Block (`kind` `:blocks`) or
  Inline (`:inlines`) vector. Returns `{:nodes :diagnostics}`."
  [kind nodes]
  (case kind
    :blocks (let [{:keys [document diagnostics]} (sanitize-document {:meta {} :blocks (vec nodes)})]
              {:nodes (:blocks document) :diagnostics diagnostics})
    :inlines (let [{:keys [document diagnostics]}
                   (sanitize-document {:meta {} :blocks [{:t :para :inlines (vec nodes)}]})]
               {:nodes (:inlines (first (:blocks document))) :diagnostics diagnostics})))

(defn untrusted-fragment
  "Vets `nodes` -- what an untrusted extension (TASK-215) handed its ctx's
  `:render-blocks` (`kind` `:blocks`) or `:render-inlines` (`:inlines`)
  callback while rendering `directive`, or returned from its `:lower` rule
  for it (`:blocks`) -- before it is rendered.

  Returns `{:nodes :diagnostics :tex}`:

  - `:tex` -- when any string an emitter would splice as TeX or hand
    MathJax (`tex-reachable`: math `:tex`, an image `:src`, a cite key,
    ...; never a `:str`/`:code`/`:code-block`'s escaped `:text`) fails
    `tex-problem`, `{:string :primitive}` for the first, and the caller
    refuses the fragment. A string `directive` itself already holds in such
    a position (`authored-tex`) is exempt: the document authored it as TeX,
    and the document's own math is trusted -- a renderer passing the
    directive's own body through is unaffected.
  - otherwise `:nodes` are `nodes` after `sanitize-document`'s rules (an
    unsafe link or image dropped to its text, an on* or unsafe URL prop
    removed), with `:diagnostics` its warnings."
  [kind nodes directive]
  (let [authored (delay (authored-tex directive))
        hit (some (fn [[s kind]] (when-let [p (reachable-problem s kind)]
                                   (when-not (contains? @authored s)
                                     {:string s :primitive p})))
                  (tex-reachable nodes))]
    (if hit
      {:tex hit}
      (sanitize-fragment kind nodes))))

(defn- untrusted-tex-refusal
  "The `ex-info` (`:type :haselnuss.sanitize/untrusted-tex`) thrown for
  `untrusted-fragment`'s `:tex` result `hit`, naming the directive, its
  source position `where` (or nil) and the primitive."
  [directive-name where {:keys [string primitive]}]
  (ex-info (str "directive " (pr-str directive-name) (when where (str " (" where ")"))
                ": an untrusted extension's output (AST its renderer handed :render-blocks/"
                ":render-inlines, or its :lower rule returned) uses TeX " primitive
                ", which is not on the allow-list untrusted TeX is held to -- "
                "only --trusted-extensions lifts this")
           (cond-> {:type ::untrusted-tex :directive-name directive-name
                    :token primitive :text string}
             where (assoc :source where))))

(defn fragment-vetter
  "The function `(fn [kind nodes] -> nodes)` an emitter's
  `render-directive` runs what a renderer for `directive` hands its ctx's
  `:render-blocks` (`kind` `:blocks`) or `:render-inlines` (`:inlines`)
  through before rendering it, and `haselnuss.lower` runs a `:lower`
  rule's Blocks through (TASK-215): identity when the extension's
  registry entry is `trusted?`; otherwise `untrusted-fragment`, throwing
  its TeX refusal (`::untrusted-tex`) or returning the sanitised nodes
  with their diagnostics reported to the `*untrusted-diagnostics*`
  collector bound when this was built. Build it on the thread that calls
  the renderer: a sandboxed renderer calls the callbacks from a worker."
  [directive trusted?]
  (if trusted?
    (fn [_kind nodes] nodes)
    (let [report! (untrusted-reporter)
          directive-name (:name directive)
          where (ast/source-label directive)]
      (fn [kind nodes]
        (let [{:keys [nodes diagnostics tex]} (untrusted-fragment kind nodes directive)]
          (when tex
            (throw (untrusted-tex-refusal directive-name where tex)))
          (report! (map #(extension-diagnostic directive-name where %) diagnostics))
          nodes)))))

;; ---------------------------------------------------------------------------
;; The document's own TeX in the LaTeX build (TASK-219)

(defn- location-prefix
  "`\"path:line: \"` for source position `src`, `\"line N: \"` without a
  file, or `\"\"` without a line."
  [{:keys [file line]}]
  (cond
    (and file line) (str file ":" line ": ")
    line (str "line " line ": ")
    :else ""))

(defn- located
  "`m` carrying `src`'s `:source-line`/`:source-file`, when it has them."
  [m src]
  (cond-> m
    (:line src) (assoc :source-line (:line src))
    (:file src) (assoc :source-file (:file src))))

(defn untrusted-document-problem
  "The first string in `document` the LaTeX build would splice as TeX
  (`tex-reachable`: math `:tex`, an image path, a citation key, an
  attribute value, ...) that fails TASK-215's allow-list (`tex-problem`,
  and `raw-argument-problem` for an image path or citation key), as
  `{:string :primitive :kind :source}`, or nil when every one passes.
  `:source` is the position of the nearest node enclosing the string that
  has one. What `--untrusted` holds a document to (TASK-219): the same
  allow-list an untrusted extension's output is, without the exemption a
  directive's own authored TeX gets there."
  [document]
  (some (fn [[s kind src]]
          (when-let [p (reachable-problem s kind)]
            {:string s :primitive p :kind kind :source src}))
        (tex-reachable document)))

(defn vet-untrusted-document
  "`document`, unchanged, when `untrusted-document-problem` finds nothing;
  otherwise throws an `ex-info` (`:type ::untrusted-document-tex`) whose
  message starts `path:line: ` and names the offending token, carrying
  `:token`, `:text` and `:source-line`/`:source-file`."
  [document]
  (if-let [{:keys [string primitive kind source]} (untrusted-document-problem document)]
    (throw (ex-info (str (location-prefix source)
                         (case kind
                           :math "document math"
                           :raw "an image path or citation key"
                           "a document string the LaTeX build writes as TeX")
                         " uses TeX " primitive ", which is not on the allow-list"
                         " --untrusted holds a document's TeX to")
                    (located {:type ::untrusted-document-tex :token primitive :text string}
                             source)))
    document))

(defn- skip-spaces [^String s i]
  (let [n (count s)]
    (loop [i i]
      (if (and (< i n) (Character/isWhitespace (.charAt s (int i)))) (recur (inc i)) i))))

(defn- group-end
  "The index just past the brace group opening at `i` in `s`, or the end
  of `s` when it never closes."
  [^String s i]
  (let [n (count s)]
    (loop [j (inc i) depth 1]
      (if (>= j n)
        n
        (case (.charAt s (int j))
          \\ (recur (+ j 2) depth)
          \{ (recur (inc j) (inc depth))
          \} (if (= 1 depth) (inc j) (recur (inc j) (dec depth)))
          (recur (inc j) depth))))))

(defn- argument-end
  "The index just past the one undelimited TeX argument at `i` in `s`
  (after spaces): a brace group, a control sequence or one character."
  [^String s i]
  (let [n (count s)
        i (skip-spaces s i)]
    (cond
      (>= i n) n
      (= \{ (.charAt s (int i))) (group-end s i)
      (= \\ (.charAt s (int i))) (if (and (< (inc i) n) (ascii-letter? (.charAt s (int (inc i)))))
                                   (letters-end s (inc i))
                                   (min n (+ i 2)))
      :else (inc i))))

(defn- optional-end
  "The index just past the optional argument `[...]` opening at `i` in
  `s`: the first `]` outside any brace group, as TeX reads it, or the end
  of `s` when there is none."
  [^String s i]
  (let [n (count s)]
    (loop [j (inc i)]
      (if (>= j n)
        n
        (case (.charAt s (int j))
          \\ (recur (+ j 2))
          \{ (recur (long (group-end s j)))
          \] (inc j)
          (recur (inc j)))))))

(defn- link-end
  "The index just past the arguments that make control word `word`
  (`href`, `url` or `hyperref`), whose name ends at `i` in `s`, a link,
  or nil when this use makes none. For `\\href` its optional `[...]` and
  its URL (the text argument is left to be typeset); for `\\url` its URL,
  braced or between two copies of one delimiter character
  (`\\url|...|`); for the four-argument `\\hyperref{url}{category}{name}`
  the three that make the address. `\\hyperref[label]{text}` links within
  the document only, and is left alone (nil)."
  [word ^String s i]
  (let [n (count s)
        j (skip-spaces s i)
        bracket? (and (< j n) (= \[ (.charAt s (int j))))]
    (case word
      "href" (argument-end s (if bracket? (optional-end s j) j))
      "hyperref" (when-not bracket?
                   (->> j (argument-end s) (argument-end s) (argument-end s)))
      (cond
        (>= j n) n
        (contains? #{\{ \\} (.charAt s (int j))) (argument-end s j)
        :else (if-let [close (str/index-of s (str (.charAt s (int j))) (inc j))]
                (inc close)
                n)))))

(defn- strip-math-links
  "`[tex' removed]`: math TeX `tex` with every `\\href` and four-argument
  `\\hyperref` reduced to its text argument and every `\\url` removed,
  and the source text of each link removed (`\\href{u}`, `\\url{u}`,
  `\\hyperref{u}{c}{n}`), in order."
  [^String tex]
  (let [n (count tex)
        out (StringBuilder.)]
    (loop [i 0 removed []]
      (if (>= i n)
        [(str out) removed]
        (let [c (.charAt tex (int i))]
          (cond
            (not= c \\) (do (.append out c) (recur (inc i) removed))
            (>= (inc i) n) (do (.append out c) (recur (inc i) removed))
            (not (ascii-letter? (.charAt tex (int (inc i)))))
            (do (.append out (subs tex i (+ i 2))) (recur (+ i 2) removed))
            :else
            (let [end (letters-end tex (inc i))
                  word (subs tex (inc i) end)]
              (if-let [stop (when (contains? #{"href" "url" "hyperref"} word)
                              (link-end word tex end))]
                (do
                  ;; Keep a control word just before from running on into
                  ;; a letter just after (`\alpha\url{u}b`); a space is
                  ;; otherwise invisible in math.
                  (when (and (< stop n) (ascii-letter? (.charAt tex (int stop)))
                             (re-find #"\\[A-Za-z]+$" (str out)))
                    (.append out " "))
                  (recur (long stop) (conj removed (subs tex i stop))))
                (do (.append out (subs tex i end)) (recur (long end) removed))))))))))

(defn- math-link-diagnostic [src link]
  (located {:type :math-link
            :link link
            :message (str (location-prefix src) "Dropped the link " link
                          " from math: document math makes no link in the LaTeX build"
                          (if (str/starts-with? link "\\url")
                            " (the URL is not typeset)."
                            " (its text is kept)."))}
           src))

(defn- rewrite-math-links
  "`x` with `strip-math-links` applied to every math node's `:tex`,
  reporting each removal to `note!` located at the nearest enclosing
  position (`src`). A collection nothing in which changed is returned
  itself, so a document with no link in its math is identical."
  [x src note!]
  (cond
    (map? x)
    (let [src (or (ast/source x) src)
          x (if (and (contains? #{:math-inline :math-block} (:t x)) (string? (:tex x)))
              (let [[tex removed] (strip-math-links (:tex x))]
                (if (seq removed)
                  (do (note! (map #(math-link-diagnostic src %) removed))
                      (assoc x :tex tex))
                  x))
              x)]
      (reduce-kv (fn [m k v]
                   (let [v' (rewrite-math-links v src note!)]
                     (if (identical? v v') m (assoc m k v'))))
                 x x))

    (vector? x)
    (let [v' (mapv #(rewrite-math-links % src note!) x)]
      (if (every? true? (map identical? x v')) x (with-meta v' (meta x))))

    (seq? x)
    (let [v' (doall (map #(rewrite-math-links % src note!) x))]
      (if (every? true? (map identical? x v')) x (with-meta (apply list v') (meta x))))

    :else x))

(defn neutralise-math-links
  "Runs over `document` before it is emitted as LaTeX (TASK-219, flag or
  not): in every `:math-inline`/`:math-block`'s `:tex`, `\\href[...]{url}{text}`
  is reduced to `{text}`, so is `\\hyperref{url}{category}{name}{text}`,
  and `\\url{url}` (or `\\url|url|`) is removed -- no real document needs
  a link in its math, and hyperref would otherwise turn
  `$\\href{javascript:...}{x}$` into a `javascript:` link. Each removal is
  a warning naming the link and, when known, `path:line`. Returns
  `{:document :diagnostics}`; a document with none of the three in its
  math comes back identical.

  A convenience, not a boundary: it catches these three commands as
  written, and TeX has other direct ways to make a link -- the pdfTeX
  primitives `\\pdfstartlink`/`\\pdfannot`, `\\special{pdf:...}`,
  `\\csname href\\endcsname`. Only `--untrusted`
  (`vet-untrusted-document`) stops every route (TASK-219 review)."
  [document]
  (let [diagnostics (volatile! [])
        result (rewrite-math-links document nil #(vswap! diagnostics into %))]
    {:document result :diagnostics @diagnostics}))
