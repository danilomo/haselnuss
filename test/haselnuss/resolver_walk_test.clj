(ns haselnuss.resolver-walk-test
  "Regression coverage for TASK-86's port of `haselnuss.resolver`'s
  `resolve-*` (CrossRef, `resolve-cross-refs`) and `cite-resolve-*`
  (citation, `resolve-citations`) families onto `haselnuss.walk/rewrite`:
  a test that would actually fail if either ported traversal's own
  document order changed, rather than one that merely happens to still
  pass under a reordering.

  `resolver-test`'s own order-sensitive fixtures do not quite do that for
  this task (clojure-reviewer, TASK-86 review): `resolve-citations-
  returns-ordered-keys-and-bibliography-id-test` and `multiple-dangling-
  citations-all-produce-warnings-test` exercise `cite-keys-in-blocks`/
  `cite-keys-in-inlines`/`resolve-cite-node`'s own unchanged `keep` loop
  over one Cite's `:items` -- code TASK-86 does not touch, and which
  TASK-87 will later port onto `haselnuss.walk/fold` separately. And the
  cross-ref analogue, `multiple-dangling-cross-refs-all-produce-warnings-
  test`, uses a label sequence (`a b a`) symmetric enough that a simple
  reversal of traversal order would reproduce the same sequence and the
  test would not notice. `resolver-test` itself has to stay unmodified
  (TASK-86 AC #4), so this is a new, separate file, mirroring TASK-85's
  own `lower-walk-test` precedent for the identical situation.

  Two concerns, each with its own non-palindromic fixture (so ANY
  transposition of two elements, not just an exact reversal, breaks the
  assertion):

  - document order across distinct schema FIELDS -- `meta.title` before a
    Section's own `:heading` before its `:blocks` before a following
    sibling top-level block, mirroring `haselnuss.walk/derive-table`'s own
    declaration-order guarantee (AC #2, pinned independently by
    `haselnuss.walk-test`) as it is actually observed at the pass level,
    through `resolve-cross-refs-node`/`resolve-citations-node`
    (`cross-ref-scope`/`citation-scope`), not assumed from the schema
    alone;
  - the one ordering `resolve-citations-node` adds BY HAND, and which is
    not generic to `haselnuss.walk` at all (see `citation-scope`'s own
    docstring): a Cite nested inside another Cite's own CiteItem
    `:prefix`/`:suffix` must resolve, and warn, strictly BEFORE the
    enclosing Cite -- the one piece of ordering a naive per-node `:cite`
    dispatch would get backwards, and the one place a regression could
    silently double-count a warning by letting `rewrite`'s own automatic
    recursion re-visit an already-resolved CiteItem.

  TASK-87 (the sibling port of `cite-keys-in-*`/`ids-in-*`/`id-locations-
  in-*` onto `haselnuss.walk/fold`) adds three more tests here, for the
  same underlying reason: `resolver-test.clj` cannot be touched, and its
  own fixtures do not exercise the specific order/path-shape guarantees
  this port has to keep --

  - `cite-keys-batch-own-keys-before-nested-cite-keys-test` -- a Cite's
    own item keys must be collected as one batch, ahead of any key
    nested inside an item's own `:prefix`/`:suffix`, mirroring
    `cite-keys-visit`'s own two-pass order exactly. Citation numbers are
    first-appearance order (`build-citation-table`), so a naive per-item
    interleaved dispatch -- which `::cite-item` being excluded from
    `cite-keys-scope` is what prevents -- would reorder which key is
    \"[1]\"/\"[2]\"/\"[3]\" in rendered output, not merely read
    differently in this test;
  - `id-locations-order-across-title-heading-blocks-and-siblings-test` --
    a deliberately non-palindromic 4-way id collision spanning
    `meta.title`, a Section's own `:heading`, its `:blocks`, and a
    following sibling block, pinning both the unchanged cross-field
    document order (title, then each top-level block in turn) and the
    one deliberate, otherwise-untested order choice this port makes
    (a block's own heading is reported before its nested Block
    descendants -- see `id-locations-block-visit`'s own docstring);
  - `id-locations-more-precise-path-for-figure-content-and-list-items-
    test` -- pins the three positions no `resolver-test.clj` fixture
    reaches at all: a Figure's own `:content` (a synthesized `0`, via
    `compact-block-suffix`, matching `block-children`'s own pre-TASK-87
    single-element-vector convention for that ONE position), a List
    item's own nested Block (now `[item-index block-index]`, strictly
    MORE precise than the pre-TASK-87 `block-children`/`mapcat identity`
    scheme, which flattened every item's own Blocks into one shared,
    item-blind index space), and a Table cell's own nested Block (now
    `[table-index row-index cell-index block-index]`, added per
    clojure-reviewer's TASK-87 review finding #4 after the first two
    shipped without it -- strictly MORE precise than the pre-TASK-87
    `block-children`/`table-cells`' own `mapcat`-across-every-cell
    scheme, which flattened every cell's own Blocks, head row and body
    rows alike, into one shared, row/cell-blind index space)."
  (:require [clojure.test :refer [deftest is testing]]
            [haselnuss.resolver :as resolver]))

(def ^:private attr
  {:classes [] :props {}})

(defn- id-attr
  [id]
  (assoc attr :id id))

(defn- cross-ref
  [label]
  {:t :cross-ref :label label})

(defn- cite-item
  "A minimal `:mode :normal` CiteItem for key `k`, with any of `:prefix`/
  `:suffix` merged in via trailing keyword args, e.g. `(cite-item \"x\"
  :prefix [...])`."
  [k & {:as extra}]
  (merge {:key k :mode :normal} extra))

(defn- cite
  [& items]
  {:t :cite :items (vec items)})

(defn- para
  [& inlines]
  {:t :para :inlines (vec inlines)})

(defn- section
  [heading blocks]
  {:t :section :level 1 :heading heading :blocks blocks :attr attr})

(deftest resolve-cross-refs-warnings-follow-document-order-test
  (testing "TASK-86: dangling CrossRef warnings, across meta.title, a
            Section's own :heading, its :blocks, and a following sibling
            block, come out in exactly document order -- a schema field
            visited out of its declared order (Document's :blocks before
            its own :meta, or a Section's :blocks before its own
            :heading) would reorder this same, deliberately
            non-palindromic, 4-element sequence, unlike resolver-test's
            own a/b/a fixture"
    (let [doc {:meta {:title [(cross-ref "missing:title")]}
               :blocks [(section [(cross-ref "missing:heading")]
                                 [(para (cross-ref "missing:body"))])
                        (para (cross-ref "missing:sibling"))]}
          {:keys [warnings]} (resolver/resolve-cross-refs doc)]
      (is (= ["missing:title" "missing:heading" "missing:body" "missing:sibling"]
             (map :label warnings))))))

(deftest resolve-citations-warnings-follow-document-order-test
  (testing "TASK-86: dangling citation warnings, across the same four
            structural positions, come out in exactly document order --
            the citation-resolution analogue of the CrossRef test above,
            through resolve-citations-node/citation-scope rather than
            resolve-cross-refs-node/cross-ref-scope"
    (let [doc {:meta {:title [(cite (cite-item "missing-title"))]}
               :blocks [(section [(cite (cite-item "missing-heading"))]
                                 [(para (cite (cite-item "missing-body")))])
                        (para (cite (cite-item "missing-sibling")))]}
          {:keys [warnings]} (resolver/resolve-citations doc {})]
      (is (= ["missing-title" "missing-heading" "missing-body" "missing-sibling"]
             (map :key warnings))))))

(deftest resolve-citations-nested-cite-item-resolves-before-outer-test
  (testing "a Cite nested inside another CiteItem's own :prefix resolves,
            and warns, BEFORE the enclosing Cite -- the one ordering
            resolve-citations-node adds by hand rather than getting for
            free from haselnuss.walk/rewrite's own generic recursion (see
            citation-scope's own docstring: a naive per-node :cite
            dispatch would compute the OUTER Cite's :text from its still-
            unresolved :items, which is what this order prevents)"
    (let [inner (cite (cite-item "missing-inner"))
          outer (cite (cite-item "missing-outer" :prefix [inner]))
          doc {:meta {} :blocks [(para outer)]}
          {:keys [warnings document]} (resolver/resolve-citations doc {})
          resolved-outer (-> document :blocks first :inlines first)
          resolved-inner (-> resolved-outer :items first :prefix first)]
      (testing "warning order: inner before outer"
        (is (= ["missing-inner" "missing-outer"] (map :key warnings))))
      (testing "the nested Cite's own :text is set at its actual :items
                tree position, not only inside the outer Cite's spliced
                :text -- direct evidence :items was walked before
                resolve-cite-node ran on the outer node, not after"
        (is (some? (:text resolved-inner)))
        (is (not= (:text resolved-outer) (:text resolved-inner))))))
  (testing "three levels deep, with only the innermost key dangling (the
            other two resolve): still exactly ONE warning, for the
            innermost key alone -- confirms haselnuss.walk/rewrite's own
            automatic recursion into the (already-resolved) :items never
            re-visits it a second time. Excluding ::ast/cite-item from
            citation-scope is what this depends on: were it in scope,
            the innermost Cite would be resolved twice (once by
            resolve-citations-node's own manual :items walk, once more by
            rewrite's later automatic descent into the same, by-then-
            already-resolved :items), and this would see TWO
            \"missing-only\" warnings instead of one"
    (let [bibliography {"mid" {} "top" {}}
          innermost (cite (cite-item "missing-only"))
          middle (cite (cite-item "mid" :prefix [innermost]))
          outer (cite (cite-item "top" :suffix [middle]))
          doc {:meta {} :blocks [(para outer)]}
          {:keys [warnings]} (resolver/resolve-citations doc bibliography)]
      (is (= ["missing-only"] (map :key warnings))))))

(deftest resolve-citations-nested-prefix-before-suffix-before-outer-test
  (testing "within ONE CiteItem carrying both a nested Cite in :prefix and
            a nested Cite in :suffix (both dangling, and the enclosing
            Cite too), the warnings arrive prefix-nested, then
            suffix-nested, then the enclosing Cite's own -- matching
            resolve-citations-node's own `cond->` order (:prefix updated
            before :suffix), both of which run to completion before
            resolve-cite-node is ever called on the outer node"
    (let [item (cite-item "outer4"
                          :prefix [(cite (cite-item "prefix-nested"))]
                          :suffix [(cite (cite-item "suffix-nested"))])
          doc {:meta {} :blocks [(para (cite item))]}
          {:keys [warnings]} (resolver/resolve-citations doc {})]
      (is (= ["prefix-nested" "suffix-nested" "outer4"] (map :key warnings))))))

(deftest resolve-citations-sibling-cite-precedes-nested-suffix-in-next-cite-test
  (testing "two sibling Cite nodes in one Para: the first's own warning
            comes before anything from the second, and the second's own
            SUFFIX-nested Cite still resolves and warns before the second
            Cite's own -- document order across siblings composes
            correctly with the prefix/suffix-before-outer order within a
            single Cite, not just in isolation"
    (let [first-cite (cite (cite-item "sib"))
          second-cite (cite (cite-item "outer2" :suffix [(cite (cite-item "suf-nested"))]))
          doc {:meta {} :blocks [(para first-cite second-cite)]}
          {:keys [warnings]} (resolver/resolve-citations doc {})]
      (is (= ["sib" "suf-nested" "outer2"] (map :key warnings))))))

;; TASK-87: cite-keys-in-*/ids-in-*/id-locations-in-* ported onto
;; `haselnuss.walk/fold` -- see this namespace's own docstring for why
;; these three tests exist here rather than in `resolver-test.clj`.

(deftest cite-keys-batch-own-keys-before-nested-cite-keys-test
  (testing "TASK-87: a Cite's own item keys are collected as ONE BATCH,
            ahead of any key nested inside an item's own :prefix/:suffix
            -- not interleaved per item, which is the only cite-keys-in-*
            order sensitive to whether an item's own nested content is
            walked before or after the NEXT item's own key. Citation
            numbers are first-appearance order (build-citation-table), so
            getting this wrong reorders which key is \"[1]\"/\"[2]\"/
            \"[3]\" in rendered output, not merely a cosmetic difference"
    (let [inner (cite (cite-item "c"))
          doc {:meta {} :blocks [(para (cite (cite-item "a") (cite-item "b" :prefix [inner])))]}
          bibliography {"a" {} "b" {} "c" {}}
          {:keys [ordered-keys]} (resolver/resolve-citations doc bibliography)]
      (is (= ["a" "b" "c"] ordered-keys)))))

(deftest id-locations-order-across-title-heading-blocks-and-siblings-test
  (testing "TASK-87: id-locations-in-* reaches meta.title, a Section's own
            :heading, its :blocks, and a following sibling block, in
            exactly that order, through the fold-driven walkers
            (id-locations-inline-walker/id-locations-block-walker) -- a
            deliberately non-palindromic 4-way id collision, so ANY
            transposition of two locations (not just an exact reversal)
            would be caught, mirroring resolve-cross-refs-warnings-
            follow-document-order-test's own fixture shape one task
            later. The middle transposition this pins that no
            resolver-test.clj fixture does -- every :heading/:caption
            fixture there is empty -- is deliberate and documented (see
            id-locations-block-visit's own docstring): a block's own
            heading is now reported BEFORE its nested Block descendants,
            not after"
    (let [title-span {:t :span :inlines [] :attr (id-attr "dup")}
          heading-span {:t :span :inlines [] :attr (id-attr "dup")}
          nested-figure {:t :figure :content {:t :code-block :text "x" :attr attr}
                         :caption [] :attr (id-attr "dup")}
          sibling-image {:t :image :src "a.png" :alt "" :attr (id-attr "dup")}
          doc {:meta {:title [title-span]}
               :blocks [(section [heading-span] [nested-figure])
                        (para sibling-image)]}
          [diagnostic] (resolver/structural-diagnostics doc)]
      (is (= :duplicate-id (:type diagnostic)))
      (is (= [{:node-type :span :path [:title 0]}
              {:node-type :span :path [0 :heading 0]}
              {:node-type :figure :path [0 0]}
              {:node-type :image :path [1 :inlines 0]}]
             (:locations diagnostic))))))

(deftest id-locations-more-precise-path-for-figure-content-and-list-items-test
  (testing "a Figure's own :content -- a :one position `block-children`
            never gave more than one bare index to either (TASK-15's own
            single-element-vector convention for that position) --
            carries the SAME path shape after TASK-87 as before: a
            synthesized 0, via compact-block-suffix"
    (let [figure {:t :figure :content {:t :code-block :text "x" :attr (id-attr "in-content")}
                  :caption [] :attr attr}
          collision {:t :math-block :tex "x" :attr (id-attr "in-content")}
          doc {:meta {} :blocks [figure collision]}
          [diagnostic] (resolver/structural-diagnostics doc)]
      (is (= {:node-type :code-block :path [0 0]} (first (:locations diagnostic))))))
  (testing "a List item's own nested Block, by contrast, carries a MORE
            precise path than before TASK-87 -- [item-index block-index]
            rather than block-children's own pre-TASK-87 mapcat-identity
            scheme, which flattened every item's own Blocks into one
            shared, item-blind index space (untested before this port:
            no resolver-test.clj fixture reaches a List item's own id at
            all)"
    (let [item0 [{:t :code-block :text "x" :attr attr}]
          item1 [{:t :code-block :text "y" :attr (id-attr "in-list")}]
          lst {:t :list :ordered false :tight true :items [item0 item1] :attr attr}
          collision {:t :math-block :tex "x" :attr (id-attr "in-list")}
          doc {:meta {} :blocks [lst collision]}
          [diagnostic] (resolver/structural-diagnostics doc)]
      (is (= {:node-type :code-block :path [0 1 0]} (first (:locations diagnostic))))))
  (testing "a Table cell's own nested Block, the third and last untested-
            before-TASK-87 shape (clojure-reviewer, TASK-87 review finding
            #4 -- the List-items case above had a regression test, this
            one did not): [table-index row-index cell-index block-index],
            preserving exactly which row and cell the id came from, unlike
            block-children/table-cells' own pre-TASK-87 mapcat-everything
            scheme, which flattened every cell's own Blocks (across BOTH
            the head row and every body row) into one shared, row/cell-
            blind index space"
    (let [cell0 {:blocks []}
          cell1 {:blocks [{:t :code-block :text "y" :attr (id-attr "in-table-cell")}]}
          table {:t :table :head {:cells []} :rows [{:cells [cell0 cell1]}]
                 :caption [] :colspec [] :attr attr}
          collision {:t :math-block :tex "x" :attr (id-attr "in-table-cell")}
          doc {:meta {} :blocks [table collision]}
          [diagnostic] (resolver/structural-diagnostics doc)]
      (is (= {:node-type :code-block :path [0 0 1 0]} (first (:locations diagnostic)))))))
