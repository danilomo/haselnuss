(ns haselnuss.select-test
  "Fixtures for the node-selector vocabulary (`haselnuss.select`,
  TASK-96), one section per acceptance criterion: `matches?` accepts a
  keyword, a map, a vector and a function, with AND across map entries
  and OR across vector elements (AC #1); a map key not otherwise
  special is compared against the node field of the same name (AC #2);
  `:id`/`:class`/`:prop`/`:text` each read the right place in the node,
  including a node carrying no `:attr` at all (AC #3); a set/regex/
  function/plain value is matched by membership/`re-find`/application/
  `=` uniformly for every key (AC #4); selectors match Inline nodes as
  well as Block nodes (AC #5); a malformed selector is refused naming
  what was wrong, never silently matching nothing (AC #6); and the
  namespace depends on nothing but `clojure.core` and `clojure.string`
  (AC #7)."
  (:require [clojure.test :refer [deftest is testing]]
            [haselnuss.select :as select]))

;; --- fixtures ---------------------------------------------------------------

(defn- attr
  "A minimal Attr (sec4.1), `:id` present only when supplied -- mirroring
  `haselnuss.parser`'s own output, which omits an unset `:id` entirely
  rather than storing it as `nil` (see the real parse trees this suite's
  own implementation was checked against over the nREPL)."
  [& {:keys [id classes props] :or {classes [] props {}}}]
  (cond-> {:classes classes :props props}
    id (assoc :id id)))

(defn- str-inline [text] {:t :str :text text})
(defn- space [] {:t :space})
(defn- emph [inlines] {:t :emph :inlines inlines})

(defn- para [inlines] {:t :para :inlines inlines})

(defn- section
  [level heading blocks & attr-kwargs]
  {:t :section :level level :heading heading :blocks blocks :attr (apply attr attr-kwargs)})

(defn- list-node
  [ordered items & attr-kwargs]
  {:t :list :ordered ordered :tight true :items items :attr (apply attr attr-kwargs)})

(defn- image
  [src alt & attr-kwargs]
  {:t :image :src src :alt alt :attr (apply attr attr-kwargs)})

;; --- AC #1: four selector shapes, AND across a map, OR across a vector -----

(deftest matches-selector-shapes-test
  (testing "a keyword is shorthand for {:t <kw>}"
    (is (select/matches? :section (section 1 [(str-inline "Hi")] [])))
    (is (not (select/matches? :para (section 1 [(str-inline "Hi")] [])))))
  (testing "a map matches only when every entry matches (AND)"
    (is (select/matches? {:t :list :ordered true} (list-node true [])))
    (is (not (select/matches? {:t :list :ordered false} (list-node true []))))
    (is (not (select/matches? {:t :para :ordered true} (list-node true [])))
        "a map selector fails as a whole when even one entry disagrees"))
  (testing "a vector matches when any element (itself a selector) matches (OR)"
    (is (select/matches? [:para :list] (list-node true [])))
    (is (select/matches? [:para :list] (para [])))
    (is (not (select/matches? [:para :list] (section 1 [] [])))))
  (testing "a function selector is called directly on the node"
    (is (select/matches? (fn [node] (= :section (:t node))) (section 1 [] [])))
    (is (not (select/matches? (fn [_node] false) (section 1 [] []))))))

;; --- AC #2: an otherwise-unspecial map key reads the same-named field ------

(deftest generic-map-key-test
  (testing "{:t :list :ordered true} needs no special case: both keys read
            straight off the node"
    (is (select/matches? {:t :list :ordered true} (list-node true [])))
    (is (not (select/matches? {:t :list :ordered true} (list-node false [])))))
  (testing "{:t :section :level 2} likewise"
    (is (select/matches? {:t :section :level 2} (section 2 [] [])))
    (is (not (select/matches? {:t :section :level 2} (section 1 [] []))))))

;; --- AC #3: :id, :class, :prop, :text read the right place -----------------

(deftest special-key-location-test
  (testing ":id reads (get-in node [:attr :id])"
    (is (select/matches? {:id "fig:one"} (image "x.png" "" :id "fig:one")))
    (is (not (select/matches? {:id "fig:other"} (image "x.png" "" :id "fig:one")))))
  (testing ":class reads membership in (get-in node [:attr :classes])"
    (is (select/matches? {:class "warn"} (image "x.png" "" :classes ["warn" "big"])))
    (is (not (select/matches? {:class "missing"} (image "x.png" "" :classes ["warn"])))))
  (testing ":prop matches every entry against (get-in node [:attr :props])"
    (is (select/matches? {:prop {"lang" "en"}} (image "x.png" "" :props {"lang" "en" "extra" "x"})))
    (is (not (select/matches? {:prop {"lang" "de"}} (image "x.png" "" :props {"lang" "en"}))))
    (is (not (select/matches? {:prop {"lang" "en" "missing" "x"}}
                              (image "x.png" "" :props {"lang" "en"})))
        "every entry in the :prop map must match, not just one"))
  (testing ":text reads the node's own flattened plain text"
    (is (select/matches? {:text "hi"} (str-inline "hi")))
    (is (select/matches? {:text "Hi there"} (para [(str-inline "Hi") (space) (str-inline "there")]))))
  (testing "a node carrying no :attr at all does not throw, and simply does
            not match an :id/:class/:prop selector"
    (let [no-attr (str-inline "plain")]
      (is (not (select/matches? {:id "anything"} no-attr)))
      (is (not (select/matches? {:class "anything"} no-attr)))
      (is (not (select/matches? {:prop {"k" "v"}} no-attr)))
      (is (select/matches? {:text "plain"} no-attr)
          ":text works with no :attr involved at all"))))

;; --- AC #4: the value-kind rule is uniform across every key ----------------

(deftest value-kind-matching-test
  (testing "a set value matches by membership"
    (is (select/matches? {:t #{:emph :strong}} (emph [])))
    (is (not (select/matches? {:t #{:emph :strong}} (para []))))
    (is (select/matches? {:id #{"a" "b"}} (image "x" "" :id "b"))))
  (testing "a regex value matches by re-find against the string form"
    (is (select/matches? {:id #"^fig:"} (image "x" "" :id "fig:one")))
    (is (not (select/matches? {:id #"^fig:"} (image "x" "" :id "tbl:one"))))
    (is (select/matches? {:text #"heading"} (para [(str-inline "a heading here")]))))
  (testing "a regex value matches a keyword-valued field (e.g. :t) against
            the keyword's own unqualified name, not (str actual)'s leading
            colon -- an anchored pattern such as ^...$ would otherwise
            silently match no node at all, since (str :section) is
            \":section\""
    (is (select/matches? {:t #"^section$"} (section 1 [] []))
        "an anchored regex matches the keyword's plain name")
    (is (select/matches? {:t #"^fig"} {:t :figure})
        "a prefix regex matches too, e.g. against :figure/:figure-like types")
    (is (not (select/matches? {:t #"^section$"} (para [])))
        "a regex that does not match the keyword's name still fails as
         expected")
    (is (not (select/matches? {:t #"^:section$"} (section 1 [] [])))
        "a pattern that itself includes a leading colon, mimicking the old
         (str actual) behaviour, no longer matches -- confirming the fix
         changed the subject string, not just made matching more lenient"))
  (testing "a regex still matches a genuine string-valued field exactly as
            before (no regression from the keyword-name fix)"
    (is (select/matches? {:text #"^Hi there$"} (para [(str-inline "Hi") (space) (str-inline "there")])))
    (is (not (select/matches? {:text #"^Hi there$"} (para [(str-inline "Hi there!")]))))
    (is (select/matches? {:id #"^fig:one$"} (image "x" "" :id "fig:one"))))
  (testing "a regex against a non-keyword, non-string field (a number) still
            matches (str actual) unchanged, e.g. against a Section's :level"
    (is (select/matches? {:level #"^2$"} (section 2 [] [])))
    (is (not (select/matches? {:level #"^2$"} (section 12 [] [])))))
  (testing "a function value is applied to the actual value"
    (is (select/matches? {:level (fn [n] (> n 2))} (section 3 [] [])))
    (is (not (select/matches? {:level (fn [n] (> n 2))} (section 1 [] [])))))
  (testing "anything else is compared with ="
    (is (select/matches? {:ordered true} (list-node true [])))
    (is (not (select/matches? {:ordered true} (list-node false [])))))
  (testing "the same four-kind rule governs :class too, applied per class
            element (a set selector value is membership in EITHER class)"
    (is (select/matches? {:class #{"a" "b"}} (image "x" "" :classes ["b" "c"])))
    (is (not (select/matches? {:class #{"a" "b"}} (image "x" "" :classes ["c"]))))
    (is (select/matches? {:class #"^fig-"} (image "x" "" :classes ["fig-wide"])))))

;; --- AC #5: selectors match Inline nodes as well as Block nodes ------------

(deftest matches-inline-nodes-test
  (is (select/matches? :emph (emph [(str-inline "x")])))
  (is (select/matches? {:t :str :text "hi"} (str-inline "hi")))
  (is (select/matches? [:emph :strong] (emph [])))
  (is (select/matches? (fn [node] (= :space (:t node))) (space))))

(deftest flatten-text-reads-the-annotation-nodes-test
  (testing "TASK-117: `flatten-text`'s docstring claims it is defined for
            EVERY Block and Inline variant, so TASK-116's two annotation
            nodes have to read as prose too rather than silently
            flattening to \"\" -- a `--transform` rule looking for a
            phrase should find it wherever it was written"
    (let [annotation-attr {:classes [] :props {}}]
      (is (= "marked text"
             (select/flatten-text {:t :highlight
                                   :inlines [(str-inline "marked") (space) (str-inline "text")]
                                   :attr annotation-attr}))
          "a :highlight is transparent, exactly as the :span it is shaped like")
      (is (= "check this"
             (select/flatten-text {:t :sticky-note
                                   :blocks [(para [(str-inline "check this")])]
                                   :attr annotation-attr}))
          "a :sticky-note reads its body, exactly as the :note it is shaped like")
      (is (= "Hazelnuts ripen"
             (select/flatten-text
              (para [(str-inline "Hazelnuts") (space)
                     {:t :highlight :inlines [(str-inline "ripen")] :attr annotation-attr}])))
          "and a highlight nested in prose contributes its words in place"))))

;; --- AC #6: a malformed selector is refused, never silently false ---------

(deftest malformed-selector-test
  (testing "a selector that is none of the four shapes throws, naming it"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"keyword, a map, a vector or a function"
                          (select/matches? "not-a-selector" (para []))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"keyword, a map, a vector or a function"
                          (select/matches? #{:para} (para []))))
    (let [e (try (select/matches? 42 (para [])) (catch clojure.lang.ExceptionInfo e e))]
      (is (= ::select/malformed-selector (:type (ex-data e))))
      (is (= 42 (:selector (ex-data e))))))
  (testing "a :prop value that is not itself a map throws, naming :prop and
            the offending value, rather than matching nothing"
    (let [e (try (select/matches? {:prop "not-a-map"} (para []))
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (re-find #":prop needs a map value" (ex-message e)))
      (is (= ::select/malformed-selector (:type (ex-data e))))
      (is (= :prop (:key (ex-data e))))
      (is (= "not-a-map" (:value (ex-data e))))))
  (testing "a malformed nested selector inside a vector's OR is refused too,
            not swallowed into a false -- using a node that does not match
            the first (well-formed) element, so evaluation actually
            reaches the malformed one rather than short-circuiting past it"
    (is (thrown? clojure.lang.ExceptionInfo (select/matches? [:para 42] (section 1 [] []))))))

;; --- AC #7: no dependency beyond clojure.core and clojure.string -----------

(deftest namespace-dependencies-test
  (testing "the only aliased namespace this namespace requires is
            clojure.string -- clojure.core needs no alias to be used"
    (is (= '{str clojure.string}
           (into {}
                 (map (fn [[alias-sym target-ns]] [alias-sym (ns-name target-ns)]))
                 (ns-aliases (find-ns 'haselnuss.select))))))
  (testing "the source's own ns form has exactly one :require clause with
            exactly this one entry -- checking the raw form itself, not
            just the compiled namespace's aliases, so a hypothetical
            un-aliased extra (:require [some.ns]) would be caught too"
    (let [ns-form (read-string (slurp "src/haselnuss/select.clj"))
          require-clauses (filter #(and (seq? %) (= :require (first %))) ns-form)]
      (is (= 1 (count require-clauses))
          "exactly one :require clause in the ns form")
      (is (= '(:require [clojure.string :as str]) (first require-clauses))))))
