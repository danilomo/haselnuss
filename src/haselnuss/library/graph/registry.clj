(ns haselnuss.library.graph.registry
  "The graph backend registry (doc-11, \"Backend registry\"; TASK-238): an
  open map from a keyword to `(fn [config] ReferenceGraph)` answering an
  EMPTY graph, in the style of the library provider registry
  (`haselnuss.library.directory/default-registry`). `default-registry`
  holds `:memory-index`, which is also `default-backend`;
  `register-backend` adds one (DataScript, once its spike passes).

  `new-graph` builds an empty graph from a registry. An unknown backend
  keyword, or a constructor that answers something that is not a
  `ReferenceGraph`, is an `ex-info` naming the backend: doc-11 treats a
  backend failure as a bug (exit 2), not a document problem."
  (:require [clojure.string :as str]
            [haselnuss.library.graph :as graph]
            [haselnuss.library.graph.memory-index :as memory-index]))

(def default-backend
  "The backend `new-graph` uses when none is named."
  :memory-index)

(def default-registry
  "The built-in graph backends, keyed by name."
  {:memory-index memory-index/memory-index})

(defn register-backend
  "`registry` with `constructor`, a `(fn [config] ReferenceGraph)`,
  registered under `backend`, a keyword -- the name `new-graph` is called
  with. A string is refused rather than coerced, so a name is spelled the
  same way at registration and at lookup. Throws `ex-info` on a name that
  is not a keyword (`::invalid-backend-name`) or a constructor that is not
  a fn (`::invalid-backend`)."
  [registry backend constructor]
  (when-not (keyword? backend)
    (throw (ex-info (str "a graph backend name must be a keyword, got " (pr-str backend))
                    {:type ::invalid-backend-name :backend backend})))
  (when-not (ifn? constructor)
    (throw (ex-info (str "the graph backend " (pr-str backend)
                         " must be a (fn [config] ReferenceGraph)")
                    {:type ::invalid-backend :backend backend})))
  (assoc registry backend constructor))

(defn- known [registry]
  (str/join ", " (sort (map str (keys registry)))))

(defn new-graph
  "An empty graph from `registry`'s `backend` (default `default-backend`),
  built with `config` (default nil). Throws `ex-info`: `::unknown-backend`
  when `registry` has no such backend, `::invalid-backend` when its
  constructor answers something that is not a `ReferenceGraph`."
  ([] (new-graph default-registry default-backend nil))
  ([backend] (new-graph default-registry backend nil))
  ([registry backend config]
   (let [constructor (get registry backend)]
     (when-not constructor
       (throw (ex-info (str "unknown graph backend " (pr-str backend)
                            "; known: " (known registry))
                       {:type ::unknown-backend :backend backend
                        :known (vec (sort (keys registry)))})))
     (let [g (constructor config)]
       (when-not (satisfies? graph/ReferenceGraph g)
         (throw (ex-info (str "graph backend " (pr-str backend)
                              " did not build a ReferenceGraph")
                         {:type ::invalid-backend :backend backend})))
       g))))
