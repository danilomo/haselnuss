(ns haselnuss.transform-test
  "Fixtures for `haselnuss.transform` (TASK-102), one section per
  acceptance criterion: a `:transform` return value of a node, `nil`, or a
  sequence replaces/deletes/splices (AC #1); a rule runs as one post-order
  pass, rules applying in declaration order with a later rule seeing an
  earlier rule's own output (AC #2); a rule that wraps its own match
  terminates rather than re-visiting its own output (AC #3); a returned
  node that does not fit its position is refused, `ast/explain-summary`'s
  own readable account in the message, naming the rule, with the raw
  `ast/explain` data still on ex-data's own `:explanation` for a
  programmatic caller (AC #4, tightened by TASK-111 -- a typo'd `:t` used
  to inline the whole raw malli explanation, ~1500 characters, into the
  message); deleting or splicing at a `:one` position (Figure's
  `:content`) is refused naming the rule and the field (AC #5); a rule
  whose selector matches nothing produces a `:transform-rule-unmatched`
  diagnostic naming it (AC #6); `:transform` receives `(node ctx)` with
  `ctx` carrying `:path`, `:ref-kind` and the document's own `:meta` (AC
  #7); and this namespace requires nothing beyond
  `haselnuss.ast`/`haselnuss.select`/`haselnuss.walk` (AC #8). Two further
  sections, past AC #8, regress the two findings TASK-102's own code
  review raised: a rule whose `:scope` includes `::ast/document` and
  matches the document root cannot delete or splice it away with no
  error, exactly like every other `:one` position (AC #5's own reasoning,
  just with no parent node for the walker to enforce it from outside);
  and a rule with a malformed `:select`/`:transform` fails with an error
  naming the rule, consistent with this namespace's other three
  purpose-built failure modes. A third, `partial-document-diagnostic-test`
  (TASK-106), covers `:transform-partial-document`: at least one rule
  combined with a document still carrying an unexpanded `:include` Block
  reports it, naming every skipped `:src`, ahead of any per-rule
  diagnostic; no `:include` Block, or no rules at all, produces none of
  it, and the pre-existing `:transform-rule-unmatched` diagnostic is
  unaffected."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.ast :as ast]
            [haselnuss.transform :as transform]))

;; --- fixtures, mirroring haselnuss.select-test/haselnuss.walk-test's own --
;; idiom -----------------------------------------------------------------

(def attr
  "A minimal Attr with no `:id` set."
  {:classes [] :props {}})

(defn- str-inline [text] {:t :str :text text})
(defn- para [& inlines] {:t :para :inlines (vec inlines)})
(defn- section
  [level heading blocks]
  {:t :section :level level :heading heading :blocks blocks :attr attr})
(defn- figure
  [content caption]
  {:t :figure :content content :caption caption :attr attr})

(defn- document
  ([blocks] (document {} blocks))
  ([doc-meta blocks] {:meta doc-meta :blocks blocks}))

;; --- AC #1: a node replaces, nil deletes, a sequence splices ---------------

(deftest transform-replaces-deletes-and-splices-test
  (testing "a node map returned from :transform replaces the matched node"
    (let [rules [{:select :para
                  :transform (fn [_node _ctx] (para (str-inline "replaced")))}]
          result (transform/transform-document (document [(para (str-inline "orig"))]) rules)]
      (is (= ["replaced"] (map :text (:inlines (first (:blocks (:document result)))))))))
  (testing "nil returned from :transform deletes the matched node"
    (let [rules [{:select {:t :para :text "drop"}
                  :transform (fn [_node _ctx] nil)}]
          result (transform/transform-document
                  (document [(para (str-inline "keep")) (para (str-inline "drop"))])
                  rules)]
      (is (= [["keep"]] (map (fn [b] (map :text (:inlines b))) (:blocks (:document result)))))))
  (testing "a sequence returned from :transform splices those nodes in, in order"
    (let [rules [{:select {:t :para :text "dup"}
                  :transform (fn [node _ctx] [node node])}]
          result (transform/transform-document (document [(para (str-inline "dup"))]) rules)]
      (is (= 2 (count (:blocks (:document result)))))
      (is (every? #(= "dup" (:text (first (:inlines %)))) (:blocks (:document result)))))))

;; --- AC #2: one post-order pass per rule, in declaration order -------------

(deftest rules-apply-in-declaration-order-test
  (testing "a later rule sees an earlier rule's own output, not the original
            document -- 'uppercase every heading, THEN drop every draft
            block' means what it says"
    (let [uppercase-text (fn [node _ctx]
                           (assoc node :inlines
                                  (mapv (fn [inline] (update inline :text str/upper-case))
                                        (:inlines node))))
          drop-shouted (fn [node _ctx]
                         (when-not (= "SHOUT" (:text (first (:inlines node)))) node))
          rules [{:name "uppercase" :select :para :transform uppercase-text}
                 {:name "drop-shouted" :select :para :transform drop-shouted}]
          result (transform/transform-document (document [(para (str-inline "shout"))]) rules)]
      (is (= [] (:blocks (:document result)))
          "the second rule only ever sees the already-uppercased text the
           first rule produced"))))

;; --- AC #3: a transform result is never re-visited by the same rule --------

(deftest a-rule-does-not-re-visit-its-own-output-test
  (testing "a rule that wraps every :para in a :block-quote does not loop --
            rewrite-post's own post-order contract (TASK-94) means the
            freshly-wrapped :para is never offered back to THIS rule's own
            pass"
    (let [wrap (fn [node _ctx] {:t :block-quote :blocks [node] :attr attr})
          rules [{:select :para :transform wrap}]
          result (transform/transform-document (document [(para (str-inline "a"))]) rules)
          wrapped (first (:blocks (:document result)))]
      (is (= [:block-quote] (map :t (:blocks (:document result)))))
      (is (= 1 (count (:blocks wrapped))) "wrapped exactly once, not forever")
      (is (= :para (:t (first (:blocks wrapped))))))))

;; --- AC #4: a returned node that does not fit its position is refused ------

(deftest invalid-replacement-node-is-refused-test
  (testing "a node missing a required field for its own position raises,
            naming the rule and folding ast/explain-summary's own readable
            account into the message, with the raw ast/explain data still
            available on ex-data's own :explanation (AC #4, TASK-111) --
            matching haselnuss.lower/haselnuss.parser's own convention for
            a programmatic caller that wants the full malli explain data"
    (let [rules [{:name "corrupt" :select :para
                  :transform (fn [_node _ctx] {:t :para})}]]
      (try
        (transform/transform-document (document [(para (str-inline "a"))]) rules)
        (is false "expected an exception")
        (catch clojure.lang.ExceptionInfo e
          (is (re-find #"corrupt" (.getMessage e)))
          (is (re-find #":inlines" (.getMessage e))
              "ast/explain-summary's own report of the missing key is in the message")
          (let [data (ex-data e)]
            (is (= ::transform/invalid-transform-result (:type data)))
            (is (= "corrupt" (:rule data)))
            (is (some? (:explanation data))
                "the raw ast/explain data is still on ex-data for a programmatic caller")
            (is (= (:explanation data) (ast/explain ast/Block {:t :para}))))))))
  (testing "a typo'd :t (TASK-111's own dogfooding example) names the bad
            value and every value that would have matched, without
            inlining the whole block schema or a Java object hash --
            unlike (pr-str (ast/explain ...)), which the message used to
            inline directly"
    (let [rules [{:name "returns-garbage" :select :section
                  :transform (fn [_node _ctx] {:t :not-a-real-node-type :nonsense true})}]
          section-node (section 1 [] [])]
      (try
        (transform/transform-document (document [section-node]) rules)
        (is false "expected an exception")
        (catch clojure.lang.ExceptionInfo e
          (let [message (.getMessage e)]
            (is (re-find #"returns-garbage" message))
            (is (re-find #"block position" message))
            (is (re-find #":not-a-real-node-type" message))
            (is (re-find #":thematic-break" message)
                "names a valid block type an author could have used instead")
            (is (not (re-find #"\[:multi" message)) "never inlines the raw schema data")
            (is (not (re-find #"@" message)) "never inlines a Java object identity hash")
            (is (< (count message) 400)
                "far short of the ~1500 characters (pr-str explanation) produced")))))))

;; --- AC #5: delete/splice at a :one position is refused, naming the rule --
;; and the field --------------------------------------------------------

(deftest one-position-arity-violation-is-refused-test
  (testing "deleting a Figure's :content (a :one position) raises, naming
            both the rule and the field -- 'position :content of a :figure
            node rewrote to 0 nodes' does not say WHICH rule did it on its
            own"
    (let [rules [{:name "drop-figure-content" :select :para
                  :transform (fn [_node _ctx] nil)}]
          fig (figure (para (str-inline "x")) [])]
      (try
        (transform/transform-document (document [fig]) rules)
        (is false "expected an exception")
        (catch clojure.lang.ExceptionInfo e
          (is (re-find #"drop-figure-content" (.getMessage e)))
          (is (re-find #":content" (.getMessage e)))
          (is (re-find #":figure" (.getMessage e)))
          (let [data (ex-data e)]
            (is (= :content (:field data)))
            (is (= "drop-figure-content" (:rule data))))))))
  (testing "splicing two nodes into a Figure's :content raises the same way"
    (let [rules [{:name "double-figure-content" :select :para
                  :transform (fn [node _ctx] [node node])}]
          fig (figure (para (str-inline "x")) [])]
      (try
        (transform/transform-document (document [fig]) rules)
        (is false "expected an exception")
        (catch clojure.lang.ExceptionInfo e
          (is (re-find #"double-figure-content" (.getMessage e)))
          (is (re-find #":content" (.getMessage e))))))))

;; --- AC #6: a rule matching no node produces a diagnostic -------------------

(deftest unmatched-rule-produces-a-diagnostic-test
  (testing "a selector matching no node in the whole document is a
            :transform-rule-unmatched diagnostic naming the rule, rather
            than silence"
    (let [rules [{:name "never-matches" :select :thematic-break
                  :transform (fn [node _ctx] node)}]
          result (transform/transform-document (document [(para (str-inline "a"))]) rules)]
      (is (= (:document result) (document [(para (str-inline "a"))])))
      (is (= [{:type :transform-rule-unmatched :name "never-matches"
               :message "never-matches's selector matched no node"}]
             (:diagnostics result)))))
  (testing "a rule with no :name gets a label built from its own position
            and its selector, so the diagnostic is never anonymous"
    (let [rules [{:select :thematic-break :transform (fn [node _ctx] node)}]
          result (transform/transform-document (document [(para (str-inline "a"))]) rules)]
      (is (= 1 (count (:diagnostics result))))
      (is (re-find #"rule #1" (:name (first (:diagnostics result)))))))
  (testing "a rule that matches at least one node contributes no diagnostic"
    (let [rules [{:name "matches" :select :para :transform (fn [node _ctx] node)}]
          result (transform/transform-document (document [(para (str-inline "a"))]) rules)]
      (is (= [] (:diagnostics result))))))

;; --- AC #7: the transform function receives (node ctx) ----------------------

(deftest transform-ctx-shape-test
  (testing ":path is the fold/rewrite-post path to the matched node, :ref-kind
            is its own reference kind, and :meta is the document's own,
            unchanged by the rule's own edits"
    (let [seen (atom nil)
          rules [{:select :para
                  :transform (fn [node ctx] (reset! seen ctx) node)}]
          doc-meta {:title [(str-inline "Title")]}]
      (transform/transform-document (document doc-meta [(section 1 [] [(para (str-inline "a"))])]) rules)
      (is (= [:blocks 0 :blocks 0] (:path @seen)))
      (is (= :haselnuss.ast/block (:ref-kind @seen)))
      (is (= doc-meta (:meta @seen)))))
  (testing ":ref-kind is :haselnuss.ast/inline for a matched Inline node"
    (let [seen (atom nil)
          rules [{:select :str
                  :transform (fn [node ctx] (reset! seen ctx) node)}]]
      (transform/transform-document (document [(para (str-inline "a"))]) rules)
      (is (= :haselnuss.ast/inline (:ref-kind @seen))))))

;; --- AC #8: no I/O, no sandbox, no CLI dependency ---------------------------

(deftest namespace-has-no-sandbox-or-cli-dependency-test
  (testing "haselnuss.transform depends on nothing but haselnuss.ast,
            haselnuss.select and haselnuss.walk -- no sci, no sidecar, no cli"
    (let [imported-namespaces (into #{}
                                    (map (comp str ns-name val))
                                    (ns-aliases 'haselnuss.transform))]
      (is (= #{"haselnuss.ast" "haselnuss.select" "haselnuss.walk"} imported-namespaces)))))

;; --- regression: the document root is a :one position too, exactly like a --
;; Figure's :content, just with no parent node for the walker itself to
;; arity-check it from the outside (code review finding #1 on TASK-102) ------

(deftest document-root-arity-violation-is-refused-test
  (testing "a rule whose own :scope includes ::ast/document, and whose
            selector matches the bare document root (a function selector
            needs no :t field, so it can match anything), cannot delete the
            whole document with no error -- the root has nowhere to put
            zero replacement nodes, exactly like a Figure's :content"
    (let [rules [{:name "delete-doc"
                  :scope #{::ast/document}
                  :select (fn [_node] true)
                  :transform (fn [_node _ctx] nil)}]]
      (try
        (transform/transform-document (document [(para (str-inline "a"))]) rules)
        (is false "expected an exception")
        (catch clojure.lang.ExceptionInfo e
          (is (re-find #"delete-doc" (.getMessage e))
              "the rule that deleted the document is named in the message")
          (let [data (ex-data e)]
            (is (= :haselnuss.walk/arity-error (:type data)))
            (is (= :document (:field data)))
            (is (= "delete-doc" (:rule data)))
            (is (= 0 (:count data))))))))
  (testing "splicing two copies of the document root in its own place is
            refused the same way -- there is nowhere to put a second
            document either"
    (let [rules [{:name "double-doc"
                  :scope #{::ast/document}
                  :select (fn [_node] true)
                  :transform (fn [node _ctx] [node node])}]]
      (try
        (transform/transform-document (document [(para (str-inline "a"))]) rules)
        (is false "expected an exception")
        (catch clojure.lang.ExceptionInfo e
          (is (re-find #"double-doc" (.getMessage e)))
          (let [data (ex-data e)]
            (is (= :document (:field data)))
            (is (= "double-doc" (:rule data)))
            (is (= 2 (:count data)))))))))

;; --- regression: a malformed :select/:transform names its own rule, ------
;; consistent with this namespace's other three purpose-built failure modes
;; (code review finding #2 on TASK-102) -------------------------------------

(deftest malformed-selector-names-the-rule-test
  (testing "a rule with a :select value shaped like none of haselnuss.select's
            four recognized kinds fails with an error naming the rule, not
            haselnuss.select's own bare ::malformed-selector"
    (let [rules [{:name "bad-select" :select #{:para}
                  :transform (fn [node _ctx] node)}]]
      (try
        (transform/transform-document (document [(para (str-inline "a"))]) rules)
        (is false "expected an exception")
        (catch clojure.lang.ExceptionInfo e
          (is (re-find #"bad-select" (.getMessage e)))
          (let [data (ex-data e)]
            (is (= :haselnuss.select/malformed-selector (:type data))
                "the underlying selector failure's own :type still passes through")
            (is (= "bad-select" (:rule data))))))))
  (testing "a rule with no :select key at all (a common typo) fails the same
            way, naming the rule rather than propagating haselnuss.select's
            own unlabeled message"
    (let [rules [{:name "missing-select" :transform (fn [node _ctx] node)}]]
      (try
        (transform/transform-document (document [(para (str-inline "a"))]) rules)
        (is false "expected an exception")
        (catch clojure.lang.ExceptionInfo e
          (is (re-find #"missing-select" (.getMessage e)))
          (is (= "missing-select" (:rule (ex-data e)))))))))

;; --- TASK-106: running over a document with unexpanded :include Blocks -----
;; is a diagnostic, not silence -------------------------------------------

(defn- include [src] {:t :include :src src})

(deftest partial-document-diagnostic-test
  (testing "at least one rule combined with a document still holding an
            unexpanded :include Block (--transform always runs before
            resolve/expand-includes) produces a :transform-partial-document
            diagnostic naming every skipped src concretely, ahead of any
            per-rule diagnostic"
    (let [rules [{:name "retitle" :select :section
                  :transform (fn [node _ctx] node)}]
          result (transform/transform-document
                  (document [(section 1 [] [(para (str-inline "a"))])
                             (include "chapters/intro.hdoc")
                             (include "chapters/methods.hdoc")])
                  rules)]
      (is (= [{:type :transform-partial-document
               :includes ["chapters/intro.hdoc" "chapters/methods.hdoc"]
               :message (str "--transform ran before 2 !includes were "
                             "expanded, so these rules never saw: "
                             "chapters/intro.hdoc, chapters/methods.hdoc")}]
             (:diagnostics result))
          "names both skipped files by path, not merely that includes exist")))
  (testing "one skipped include gets singular grammar in the message"
    (let [rules [{:name "retitle" :select :para :transform (fn [node _ctx] node)}]
          result (transform/transform-document
                  (document [(para (str-inline "a")) (include "chapters/intro.hdoc")]) rules)]
      (is (= [{:type :transform-partial-document
               :includes ["chapters/intro.hdoc"]
               :message (str "--transform ran before 1 !include was expanded, "
                             "so these rules never saw: chapters/intro.hdoc")}]
             (:diagnostics result)))))
  (testing "a document with no :include Block anywhere produces no new
            diagnostic -- the existing unmatched-rule warning is the only one
            that can still fire"
    (let [rules [{:name "matches" :select :para :transform (fn [node _ctx] node)}]
          result (transform/transform-document
                  (document [(para (str-inline "a"))]) rules)]
      (is (= [] (:diagnostics result))))
    (let [rules [{:name "never-matches" :select :thematic-break
                  :transform (fn [node _ctx] node)}]
          result (transform/transform-document
                  (document [(para (str-inline "a"))]) rules)]
      (is (= [{:type :transform-rule-unmatched :name "never-matches"
               :message "never-matches's selector matched no node"}]
             (:diagnostics result))
          "the pre-existing diagnostic is unaffected by this task's change")))
  (testing "an :include Block reachable only through a Table Cell's own
            :blocks -- ::ast/cell is not in default-scope, so a rule with no
            :scope of its own never descends there -- is still found and
            reported, since whether the document is partial does not depend
            on any one rule's :scope"
    (let [cell (fn [blocks] {:blocks blocks})
          row (fn [cells] {:cells cells})
          table {:t :table
                 :head (row [(cell [(para (str-inline "h"))])])
                 :rows [(row [(cell [(include "chapters/aside.hdoc")])])]
                 :caption []
                 :colspec []
                 :attr attr}
          rules [{:name "matches" :select :para :transform (fn [node _ctx] node)}]
          result (transform/transform-document (document [table]) rules)]
      (is (= ["chapters/aside.hdoc"] (:includes (first (:diagnostics result)))))))
  (testing "no --transform rules at all (an empty rules seq) skips the scan
            entirely -- no diagnostic even though the document has an
            unexpanded :include Block, matching the no-op TASK-103 already
            gives an empty --transform"
    (let [result (transform/transform-document
                  (document [(include "chapters/intro.hdoc")]) [])]
      (is (= [] (:diagnostics result)))
      (is (= (document [(include "chapters/intro.hdoc")]) (:document result))))))
