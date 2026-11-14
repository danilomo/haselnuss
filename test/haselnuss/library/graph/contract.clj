(ns haselnuss.library.graph.contract
  "The `ReferenceGraph` contract (doc-11, \"Testing strategy\", row
  \"ReferenceGraph contract\"; TASK-238 AC #3): the behaviour every graph
  backend must have, as one function any backend's test namespace runs.

      (deftest contract-test
        (contract/check-graph-contract
         {:new-graph (fn [] (my-backend config))}))   ; an EMPTY graph

  It pins, with hand-written fixtures:

  - `with-book` replaces a book's record entirely (old edges, file claims
    and target index entries gone), and `without-book` removes one;
  - `edges-from`; `edges-to` by book id (canonical or short form) and by
    book id + passage, exact after whitespace normalisation, falling back
    to the passage in `:written` when an edge has no `:to :passage`;
  - slug-sharing books: a canonical id gets only the resolved edges into
    that exact book (dangling ones by slug), a short-form id every
    book's with that slug;
  - `books-for-file`, including a file two books claim;
  - dangling edges are stored and returned, including one that names a
    book by `:written` only;
  - two books sharing a slug on different shelves are two records;
  - a graph value never changes after a later `with-book`/`without-book`;
  - an invalid record or `edges-to` target (an invalid passage included)
    throws `ex-info`.

  `model-observe` shares `graph/edge-matches-target?` with the backends on
  purpose -- it is the one statement of the matching rule -- so the
  pinned cases above, not the model, are what pin that rule itself.

  Then it replays seeded random histories of `with-book`/`without-book`
  against `model-observe`, a deliberately naive linear-scan graph that is correct
  by inspection, comparing every read after every step and, at the end,
  every earlier graph value against what it answered when it was made.
  That is the oracle comparison doc-11 asks of later backends, and it
  also checks `:memory-index` against the model the index must agree
  with. It asserts the histories were not vacuous (replacements,
  passage-filtered hits, dangling edges, shared files all occurred), and
  returns those counts.

  The assertions are plain `clojure.test` `is` forms, so they report
  inside whichever `deftest` calls this."
  (:require [clojure.test :refer [is testing]]
            [haselnuss.book-ref :as book-ref]
            [haselnuss.library.graph :as graph])
  (:import (java.util Random)))

;; --- fixtures ----------------------------------------------------------------

(def heidelberg "A citing book." (book-ref/book-id "reformed" ["confessions" "reformed"] "heidelberg"))
(def apostles "A second citing book." (book-ref/book-id "reformed" ["creeds"] "apostles"))
(def romanos "The most-cited book." (book-ref/book-id "bible" ["scripture" "nt"] "romanos"))
(def salmos "Another cited book." (book-ref/book-id "bible" ["scripture" "ot"] "salmos"))
(def same-a "A book whose slug `same-b` shares." (book-ref/book-id "shared" ["a"] "same"))
(def same-b "A book whose slug `same-a` shares." (book-ref/book-id "shared" ["b" "c"] "same"))

(defn edge
  "A valid edge from `from-id`. `opts`: `:from-passage`, `:to-id`
  (resolved or identified target), `:written` (defaults to `:to-id` plus
  `:passage` formatted), `:passage` (a Passage), `:kind` (`:link`),
  `:problem` (makes it dangling), `:line`."
  [from-id {:keys [from-passage order to-id written passage kind problem line file]
            :or {kind :link line 1}}]
  (cond-> {:from (cond-> {:id from-id}
                   from-passage (assoc :passage from-passage)
                   order (assoc :order order))
           :to (cond-> {:written (or written
                                     (book-ref/format-book-ref
                                      (cond-> {:id to-id} passage (assoc :passage passage))))}
                 to-id (assoc :id to-id)
                 passage (assoc :passage passage))
           :kind kind
           :status (if problem :dangling :resolved)
           :position (cond-> {:line line :column 1} file (assoc :file file))}
    problem (assoc :problem problem)))

(defn record
  "A valid book record for `id`."
  [id {content-hash :hash :keys [files labels label-spans edges diagnostics]
       :or {content-hash "h0" files #{} labels {} edges [] diagnostics []}}]
  (cond-> {:id id :hash content-hash :files files :labels labels :edges edges :diagnostics diagnostics}
    label-spans (assoc :label-spans label-spans)))

(defn- locator [s] {:form :locator :locator s})

(def ^:private h1-quote-14-8
  (edge heidelberg {:from-passage "sec:q1" :order 3 :to-id romanos :passage (locator "14:8")
                    :kind :quote :line 42}))
(def ^:private h1-link-romanos (edge heidelberg {:to-id romanos :line 43}))
(def ^:private h1-dangling-label
  (edge heidelberg {:to-id romanos :passage {:form :label :label "sec:x"}
                    :problem :unknown-label :line 44}))
(def ^:private h1-dangling-book
  (edge heidelberg {:written "bible:nope#1:1" :passage (locator "1:1")
                    :problem :unknown-book :line 45}))
(def ^:private h1-malformed
  (edge heidelberg {:written "bible:" :problem :malformed-book-ref :line 46}))
(def ^:private h1-link-salmos (edge heidelberg {:to-id salmos :line 47}))
(def ^:private h1-dangling-written-only
  ;; No :to :passage: the passage exists only in what was written.
  (edge heidelberg {:written "bible:nope#2:3" :problem :unknown-book :line 48}))

(def heidelberg-v1
  "heidelberg, first scan: seven edges, two files (one shared with apostles)."
  (record heidelberg
          {:hash "h1" :files #{"heidelberg.hdoc" "shared/ch1.hdoc"}
           :labels {"sec:q1" "lh1"}
           ;; TASK-242: optional document-order spans, kept like any field
           :label-spans {"sec:q1" [1 9]}
           :edges [h1-quote-14-8 h1-link-romanos h1-dangling-label
                   h1-dangling-book h1-malformed h1-link-salmos
                   h1-dangling-written-only]
           :diagnostics [{:type :unknown-label :level :warning :message "no sec:x"}]}))

(def ^:private a-link-14-8-short
  ;; Written in the short form; resolved to the canonical id.
  (edge apostles {:to-id romanos :written "bible:romanos#14:8"
                  :passage (locator "14:8") :line 9}))
(def ^:private a-link-14-8-9
  (edge apostles {:to-id romanos :passage (locator "14:8-9") :line 10}))

(def apostles-v1
  "apostles: two edges into romanos (14:8 and the overlapping 14:8-9)."
  (record apostles {:hash "a1" :files #{"apostles.hdoc" "shared/ch1.hdoc"}
                    :edges [a-link-14-8-short a-link-14-8-9]}))

(def ^:private h2-link-salmos (edge heidelberg {:to-id salmos :line 3}))

(def heidelberg-v2
  "heidelberg, rescanned: one edge, its shared chapter no longer included."
  (record heidelberg {:hash "h2" :files #{"heidelberg.hdoc"}
                      :edges [h2-link-salmos]}))

;; --- observation -------------------------------------------------------------

(defn- bag [edges] (frequencies edges))

(defn- probes
  "Targets to ask `edges-to` about: every target id (canonical and short
  form), with no passage and with each passage, padded or not."
  [ids passages]
  (vec (for [id ids
             tid [id (book-ref/book-id (:library id) (:slug id))]
             p (concat [nil] passages (map #(str " " (graph/passage-key %) "\t") passages))]
         (if p {:id tid :passage p} tid))))

(defn observe
  "Every read `g` answers for the given ids, `edges-to` probes and files,
  as plain data (edges-to as a bag, since its order is unspecified)."
  [g {:keys [ids targets files]}]
  {:indexed (graph/indexed-books g)
   :from (into {} (map (juxt identity #(graph/edges-from g %))) ids)
   :to (into {} (map (juxt identity #(bag (graph/edges-to g %)))) targets)
   :files (into {} (map (juxt identity #(graph/books-for-file g %))) files)})

;; --- the naive model ---------------------------------------------------------

(defn model-observe
  "What a correct graph holding exactly `records` (canonical id ->
  record) answers, computed by linear scans: the oracle."
  [records {:keys [ids targets files]}]
  (let [all-edges (mapcat :edges (vals records))]
    {:indexed (update-vals records #(dissoc % :id :edges))
     :from (into {} (map (fn [id] [id (get-in records [id :edges] [])])) ids)
     :to (into {}
               (map (fn [t]
                      (let [parsed (graph/parse-target t)]
                        [t (bag (filter #(graph/edge-matches-target? parsed %)
                                        all-edges))])))
               targets)
     :files (into {}
                  (map (fn [f] [f (set (keep (fn [[id r]] (when (contains? (:files r) f) id))
                                             records))]))
                  files)}))

;; --- pinned cases ------------------------------------------------------------

(def ^:private pinned-probes
  {:ids [heidelberg apostles romanos salmos]
   :targets (probes [romanos salmos (book-ref/book-id "bible" "nope")]
                    [(locator "14:8") (locator "14:8-9") {:form :label :label "sec:x"}
                     (locator "1:1")])
   :files ["heidelberg.hdoc" "apostles.hdoc" "shared/ch1.hdoc" "unclaimed.hdoc"]})

(defn- check-pinned
  [new-graph]
  (let [g0 (new-graph)
        g0-seen (observe g0 pinned-probes)
        g1 (-> g0 (graph/with-book heidelberg-v1) (graph/with-book apostles-v1))
        g1-seen (observe g1 pinned-probes)
        g2 (graph/with-book g1 heidelberg-v2)
        g2-seen (observe g2 pinned-probes)
        g3 (graph/without-book g2 heidelberg)
        romanos-short (book-ref/book-id "bible" "romanos")]

    (testing "an empty graph answers nothing"
      (is (= {} (graph/indexed-books g0)))
      (is (= [] (vec (graph/edges-from g0 heidelberg))))
      (is (empty? (graph/edges-to g0 romanos)))
      (is (= #{} (graph/books-for-file g0 "heidelberg.hdoc"))))

    (testing "indexed-books holds each record without :id and :edges"
      (is (= {heidelberg (dissoc heidelberg-v1 :id :edges)
              apostles (dissoc apostles-v1 :id :edges)}
             (graph/indexed-books g1))))

    (testing "edges-from answers the record's edges in order"
      (is (= (:edges heidelberg-v1) (vec (graph/edges-from g1 heidelberg))))
      (is (= (:edges apostles-v1) (vec (graph/edges-from g1 apostles))))
      (is (= [] (vec (graph/edges-from g1 romanos)))))

    (testing "edges-to by book id: every edge into the book, dangling included"
      (let [expected (bag [h1-quote-14-8 h1-link-romanos h1-dangling-label
                           a-link-14-8-short a-link-14-8-9])]
        (is (= expected (bag (graph/edges-to g1 romanos))))
        (testing "a short-form id finds the same edges (matched by book-key)"
          (is (= expected (bag (graph/edges-to g1 romanos-short))))))
      (testing "a dangling edge with no resolved id is found by the book it names"
        (is (= (bag [h1-dangling-book h1-dangling-written-only])
               (bag (graph/edges-to g1 (book-ref/book-id "bible" "nope"))))))
      (testing "a malformed reference is reachable only from its source"
        (is (some #{h1-malformed} (graph/edges-from g1 heidelberg)))
        (is (not-any? #{h1-malformed}
                      (mapcat #(graph/edges-to g1 %) (:targets pinned-probes))))))

    (testing "edges-to by book id + passage: exact as written, whitespace normalised"
      (let [at-14-8 (bag [h1-quote-14-8 a-link-14-8-short])]
        (is (= at-14-8 (bag (graph/edges-to g1 {:id romanos :passage (locator "14:8")}))))
        (is (= at-14-8 (bag (graph/edges-to g1 {:id romanos-short :passage "14:8"}))))
        (is (= at-14-8 (bag (graph/edges-to g1 {:id romanos :passage " 14:8 \n"}))))
        (testing "an overlapping locator does not match"
          (is (= (bag [a-link-14-8-9])
                 (bag (graph/edges-to g1 {:id romanos :passage (locator "14:8-9")}))))))
      (is (= (bag [h1-dangling-label])
             (bag (graph/edges-to g1 {:id romanos :passage "sec:x"}))))
      (is (empty? (graph/edges-to g1 {:id romanos :passage "14:9"})))
      (testing "an edge without :to :passage matches the passage it wrote"
        (let [nope (book-ref/book-id "bible" "nope")]
          (is (= (bag [h1-dangling-written-only])
                 (bag (graph/edges-to g1 {:id nope :passage "2:3"}))))
          (is (= (bag [h1-dangling-written-only])
                 (bag (graph/edges-to g1 {:id nope :passage (locator "2:3")}))))
          (is (= (bag [h1-dangling-book])
                 (bag (graph/edges-to g1 {:id nope :passage "1:1"}))))))
      (testing "{:id …} without :passage is the whole book"
        (is (= (bag (graph/edges-to g1 romanos))
               (bag (graph/edges-to g1 {:id romanos}))))))

    (testing "books-for-file, including a file two books claim"
      (is (= #{heidelberg apostles} (graph/books-for-file g1 "shared/ch1.hdoc")))
      (is (= #{heidelberg} (graph/books-for-file g1 "heidelberg.hdoc")))
      (is (= #{} (graph/books-for-file g1 "unclaimed.hdoc"))))

    (testing "with-book replaces the book's record entirely"
      (is (= (dissoc heidelberg-v2 :id :edges) (get (graph/indexed-books g2) heidelberg)))
      (is (= [h2-link-salmos] (vec (graph/edges-from g2 heidelberg))))
      (is (= (bag [a-link-14-8-short a-link-14-8-9]) (bag (graph/edges-to g2 romanos)))
          "heidelberg's old edges into romanos are gone")
      (is (empty? (graph/edges-to g2 (book-ref/book-id "bible" "nope"))))
      (is (= (bag [h2-link-salmos]) (bag (graph/edges-to g2 salmos))))
      (is (= #{apostles} (graph/books-for-file g2 "shared/ch1.hdoc"))
          "a file the new record no longer lists is no longer claimed")
      (is (= (dissoc apostles-v1 :id :edges) (get (graph/indexed-books g2) apostles))
          "another book's record is untouched"))

    (testing "without-book drops the book and every fact it is the source of"
      (is (= #{apostles} (set (keys (graph/indexed-books g3)))))
      (is (= [] (vec (graph/edges-from g3 heidelberg))))
      (is (empty? (graph/edges-to g3 salmos)))
      (is (= #{} (graph/books-for-file g3 "heidelberg.hdoc")))
      (is (= (observe g3 pinned-probes)
             (observe (graph/without-book g3 heidelberg) pinned-probes))
          "dropping a book that is not indexed changes nothing"))

    (testing "a graph value is unchanged by later writes on it"
      (is (= g0-seen (observe g0 pinned-probes)))
      (is (= g1-seen (observe g1 pinned-probes)))
      (is (= g2-seen (observe g2 pinned-probes))))

    (testing "two books sharing a slug on different shelves are two records"
      (let [g (-> (new-graph)
                  (graph/with-book (record same-a {:hash "a" :files #{"f"}}))
                  (graph/with-book (record same-b {:hash "b" :files #{"f"}})))]
        (is (= #{same-a same-b} (set (keys (graph/indexed-books g)))))
        (is (= #{same-a same-b} (graph/books-for-file g "f")))
        (is (= #{same-b} (graph/books-for-file (graph/without-book g same-a) "f")))))

    (testing "slug-sharing books do not get each other's resolved backlinks"
      (let [to-a (edge heidelberg {:to-id same-a :line 1})
            to-b (edge heidelberg {:to-id same-b :line 2})
            ambiguous (edge heidelberg {:written "shared:same" :problem :ambiguous-book :line 3})
            label-in-a (edge heidelberg {:to-id same-a :passage {:form :label :label "sec:z"}
                                         :problem :unknown-label :line 4})
            g (graph/with-book (new-graph)
                (record heidelberg {:edges [to-a to-b ambiguous label-in-a]}))]
        (testing "a canonical id: resolved edges by exact id, dangling ones by slug"
          (is (= (bag [to-a ambiguous label-in-a]) (bag (graph/edges-to g same-a))))
          (is (= (bag [to-b ambiguous label-in-a]) (bag (graph/edges-to g same-b))))
          (is (= (bag [label-in-a])
                 (bag (graph/edges-to g {:id same-b :passage "sec:z"})))))
        (testing "a short-form id reaches every book with that slug"
          (is (= (bag [to-a to-b ambiguous label-in-a])
                 (bag (graph/edges-to g (book-ref/book-id "shared" "same"))))))
        (testing "a stale shelf finds no resolved edges"
          (is (= (bag [ambiguous label-in-a])
                 (bag (graph/edges-to g (book-ref/book-id "shared" ["old"] "same")))))))
      (testing "a short-form id still reaches the canonical book's resolved edges"
        (is (= (bag (graph/edges-to g1 romanos))
               (bag (graph/edges-to g1 romanos-short))))))

    (testing "misuse throws"
      (is (thrown? clojure.lang.ExceptionInfo
                   (graph/with-book g0 (dissoc heidelberg-v1 :hash))))
      (is (thrown? clojure.lang.ExceptionInfo
                   (graph/with-book g0 (assoc heidelberg-v1 :edges [h2-link-salmos
                                                                    (edge apostles {:to-id romanos})])))
          "an edge whose source is another book")
      (is (thrown? clojure.lang.ExceptionInfo (graph/edges-to g1 "bible:romanos")))
      (doseq [bad [{:form :label} {:form :range :from "sec:a"} {:form :locator :locator 1}
                   {:form :verse :locator "1"} 42]]
        (is (= ::graph/invalid-target
               (try (graph/edges-to g1 {:id romanos :passage bad}) nil
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))
            (str "an invalid passage is ::invalid-target: " (pr-str bad))))
      (is (= g0-seen (observe g0 pinned-probes)) "a rejected write changes nothing"))))

;; --- seeded random histories -------------------------------------------------

(def ^:private universe-ids [heidelberg apostles romanos salmos same-a same-b])
(def ^:private universe-files ["a.hdoc" "b.hdoc" "c.hdoc" "d.hdoc"])
(def ^:private universe-passages
  [nil (locator "1:1") (locator "1:1-2") {:form :label :label "sec:a"}
   {:form :range :from "sec:a" :to "sec:b"}])
(def ^:private random-probes
  {:ids universe-ids
   :targets (probes (conj universe-ids (book-ref/book-id "bible" "nope"))
                    (remove nil? universe-passages))
   :files (conj universe-files "unclaimed.hdoc")})

(defn- pick [^Random rnd xs] (nth xs (.nextInt rnd (count xs))))

(defn- random-edge [^Random rnd from-id line]
  (let [passage (pick rnd universe-passages)
        kind (pick rnd [:link :quote])]
    (case (.nextInt rnd 4)
      ;; resolved, written canonical or short
      (0 1) (let [to (pick rnd universe-ids)
                  written-id (if (.nextBoolean rnd) to (book-ref/book-id (:library to) (:slug to)))]
              (edge from-id {:to-id to :passage passage :kind kind :line line
                             :written (book-ref/format-book-ref
                                       (cond-> {:id written-id} passage (assoc :passage passage)))}))
      ;; dangling, but the book is identified
      2 (edge from-id {:to-id (pick rnd universe-ids) :passage passage :kind kind :line line
                       :problem :unknown-label})
      ;; dangling by :written only (sometimes malformed)
      3 (if (.nextBoolean rnd)
          (edge from-id {:written (book-ref/format-book-ref
                                   (cond-> {:id (book-ref/book-id "bible" "nope")}
                                     passage (assoc :passage passage)))
                         ;; Sometimes the passage is only in :written.
                         :passage (when (.nextBoolean rnd) passage)
                         :kind kind :line line :problem :unknown-book})
          (edge from-id {:written "nope" :kind kind :line line :problem :malformed-book-ref})))))

(defn- random-record [^Random rnd id]
  (record id {:hash (str "h" (.nextInt rnd 1000))
              :files (set (filter (fn [_] (< (.nextInt rnd 3) 1)) universe-files))
              :labels (if (.nextBoolean rnd) {"sec:a" (str (.nextInt rnd 9))} {})
              :edges (vec (for [line (range 1 (inc (.nextInt rnd 5)))]
                            (random-edge rnd id line)))}))

(defn- note-coverage
  "Counts of the interesting situations `seen` (an observation) shows."
  [counts seen]
  (cond-> counts
    (some (fn [[t es]] (and (map? t) (some? (:passage t)) (seq es))) (:to seen))
    (update :passage-hits inc)
    (some (fn [[_ es]] (some #(= :dangling (:status (key %))) es)) (:to seen))
    (update :dangling-hits inc)
    (some #(< 1 (count (val %))) (:files seen))
    (update :shared-files inc)))

(defn check-random-histories
  "Replay `histories` seeded random histories of `steps` writes each
  against both `new-graph` and the naive model, comparing every read after
  every write, then every earlier graph value against what it answered
  when it was made. Returns the coverage counts it asserts on."
  [new-graph {:keys [seed histories steps] :or {seed 238 histories 60 steps 25}}]
  (let [rnd (Random. seed)
        counts (atom {:replacements 0 :removals 0 :passage-hits 0 :dangling-hits 0
                      :shared-files 0 :mismatches 0})]
    (dotimes [_ histories]
      (loop [i 0 g (new-graph) records {} past []]
        (if (= i steps)
          (doseq [[old-g old-seen] past]
            (when-not (is (= old-seen (observe old-g random-probes))
                          "an earlier graph value changed after later writes")
              (swap! counts update :mismatches inc)))
          (let [id (pick rnd universe-ids)
                [g' records'] (if (< (.nextInt rnd 10) 7)
                                (let [r (random-record rnd id)]
                                  (when (contains? records id) (swap! counts update :replacements inc))
                                  [(graph/with-book g r) (assoc records id r)])
                                (do (when (contains? records id) (swap! counts update :removals inc))
                                    [(graph/without-book g id) (dissoc records id)]))
                seen (observe g' random-probes)]
            (when-not (is (= (model-observe records' random-probes) seen)
                          "the backend disagrees with the naive model")
              (swap! counts update :mismatches inc))
            (swap! counts note-coverage seen)
            (recur (inc i) g' records' (conj past [g' seen]))))))
    (let [c @counts]
      (testing "the random histories were not vacuous"
        (doseq [k [:replacements :removals :passage-hits :dangling-hits :shared-files]]
          (is (pos? (get c k)) (str k " never happened: " (pr-str c)))))
      c)))

;; --- the contract ------------------------------------------------------------

(defn check-graph-contract
  "Run the whole contract against the backend `new-graph` (a no-argument
  fn answering an EMPTY graph). `:random` is passed to
  `check-random-histories` (`{:seed :histories :steps}`). Returns the
  random histories' coverage counts."
  [{:keys [new-graph random]}]
  (testing "ReferenceGraph contract: pinned cases"
    (check-pinned new-graph))
  (testing "ReferenceGraph contract: seeded random histories against the naive model"
    (check-random-histories new-graph random)))
