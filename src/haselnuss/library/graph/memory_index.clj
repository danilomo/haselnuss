(ns haselnuss.library.graph.memory-index
  "The `:memory-index` graph backend (doc-11, \"Technology choice\";
  TASK-238): the default, the fallback, and the oracle every other backend
  is compared with. It is three immutable maps beside the records, so a
  graph is a value and every write is an `assoc`/`dissoc` -- no new
  dependency, nothing for the native image to reflect on.

      :records  {BookId record}                       ; canonical id, by =
      :targets  {book-key {source-BookId [Edge]}}     ; target -> edges
      :files    {path #{BookId}}                      ; file -> books

  source -> edges is the record's own `:edges`. `:targets` is nested by
  source so that `with-book` can drop exactly the edges its book wrote
  without scanning anyone else's, and empty inner maps and sets are
  removed so a graph's indexes never outgrow its records (a long-running
  daemon writes the same books over and over).

  The value is a `reify` over one plain map rather than a `defrecord`:
  nothing is looked up by class name, so the native image needs no
  reflect-config entry for it."
  (:require [haselnuss.library.graph :as graph]))

(defn- dissoc-in-set
  "`m` with `v` removed from the set under `k`, and `k` removed when its
  set becomes empty."
  [m k v]
  (let [s (disj (get m k #{}) v)]
    (if (seq s) (assoc m k s) (dissoc m k))))

(defn- unindex
  "`state` without any fact whose source is the book `id`."
  [{:keys [records] :as state} id]
  (if-let [{:keys [edges files]} (get records id)]
    (-> state
        (update :records dissoc id)
        (update :targets
                (fn [targets]
                  (reduce (fn [t k]
                            (let [inner (dissoc (get t k) id)]
                              (if (seq inner) (assoc t k inner) (dissoc t k))))
                          targets
                          (distinct (keep graph/target-key edges)))))
        (update :files
                (fn [file-index]
                  (reduce #(dissoc-in-set %1 %2 id) file-index files))))
    state))

(defn- index
  "`state` with the (valid) `record` added; its book must not be indexed."
  [state {:keys [id edges files] :as record}]
  (-> state
      (assoc-in [:records id] record)
      (update :targets
              (fn [targets]
                (reduce (fn [t edge]
                          (if-let [k (graph/target-key edge)]
                            (update-in t [k id] (fnil conj []) edge)
                            t))
                        targets
                        edges)))
      (update :files
              (fn [file-index]
                (reduce #(update %1 %2 (fnil conj #{}) id) file-index files)))))

(defn internal-state
  "The plain map behind the `:memory-index` graph `g`, or nil for any other
  value. For tests only, and not part of any graph contract: TASK-245's
  test that `without-book` leaves the indexes as they were before the
  book's `with-book` -- index shrinkage, which no protocol read can
  observe."
  [g]
  (::state (meta g)))

(defn- ->graph
  [state]
  (with-meta
    (reify graph/ReferenceGraph
      (with-book [_ record]
        (graph/check-book-record! record)
        (->graph (-> state (unindex (:id record)) (index record))))
      (without-book [_ id]
        (->graph (unindex state id)))
      (indexed-books [_]
        (update-vals (:records state) #(dissoc % :id :edges)))
      (edges-from [_ id]
        (get-in state [:records id :edges] []))
      (edges-to [_ target]
        (let [parsed (graph/parse-target target)]
          (into []
                (comp cat (filter #(graph/edge-matches-target? parsed %)))
                (vals (get-in state [:targets (:key parsed)])))))
      (books-for-file [_ path]
        (get-in state [:files path] #{})))
    ;; for `internal-state`, i.e. for tests; no protocol read sees it
    {::state state}))

(def ^:private empty-state {:records {} :targets {} :files {}})

(defn memory-index
  "An empty `:memory-index` graph. `config` is accepted for the registry's
  `(fn [config] graph)` shape and ignored: the backend has no options."
  ([] (memory-index nil))
  ([_config] (->graph empty-state)))
