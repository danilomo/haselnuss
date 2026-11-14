(ns haselnuss.library.graph.libraries
  "Library records for any graph backend (TASK-241): `library-indexed`
  wraps a `ReferenceGraph` so that it also satisfies
  `haselnuss.library.graph/LibraryIndex`, keeping the library records
  beside it in the same value.

  Why a wrapper rather than a seventh `ReferenceGraph` operation or a
  pseudo book record: the six portable operations stay exactly as doc-11
  defines them, so a backend (`:memory-index`, a future DataScript one)
  needs nothing new; a library record can never be mistaken for a book
  by `indexed-books`, `edges-to`, `books-for-file` or `affected-by`, and
  no BookId has to be reserved for one -- the grammar leaves none free.
  The graph and its library records still travel as ONE value, so a
  daemon that keeps the graph in an atom gets a consistent snapshot of
  both, and `check-report` (TASK-242) needs nothing but the graph.

  The wrapper delegates the six operations to the wrapped graph and
  re-wraps every write (`with-book`, `without-book`), so the library
  records survive book updates; `with-library`/`without-library` replace
  or drop one record. The internal, unstable `GraphQuery` is delegated to
  the wrapped backend; when that backend does not implement it, `query`
  throws `ex-info` `::query-unsupported` (so `satisfies?` on the wrapper
  says nothing about the backend: ask `backend-graph`).

  Like `:memory-index`, the value is a `reify`, so nothing is looked up
  by class name and the native image needs no reflect-config entry."
  (:require [haselnuss.library.graph :as graph]))

(defprotocol ^:private Wrapped
  (unwrap [g]))

(defn- wrap
  [inner libraries]
  (reify
    graph/ReferenceGraph
    (with-book [_ record] (wrap (graph/with-book inner record) libraries))
    (without-book [_ id] (wrap (graph/without-book inner id) libraries))
    (indexed-books [_] (graph/indexed-books inner))
    (edges-from [_ id] (graph/edges-from inner id))
    (edges-to [_ target] (graph/edges-to inner target))
    (books-for-file [_ path] (graph/books-for-file inner path))

    graph/LibraryIndex
    (with-library [_ record]
      (graph/check-library-record! record)
      (wrap inner (assoc libraries (:name record) record)))
    (without-library [_ lib-name] (wrap inner (dissoc libraries lib-name)))
    (indexed-libraries [_] libraries)

    graph/GraphQuery
    (query [_ q inputs]
      (if (satisfies? graph/GraphQuery inner)
        (graph/query inner q inputs)
        (throw (ex-info "this graph's backend does not support GraphQuery"
                        {:type ::query-unsupported}))))

    Wrapped
    (unwrap [_] inner)))

(defn library-indexed
  "`g`, a ReferenceGraph, as one that also satisfies `LibraryIndex`: `g`
  itself when it already does, else `g` wrapped with no library records."
  [g]
  (when-not (satisfies? graph/ReferenceGraph g)
    (throw (ex-info "library-indexed expects a ReferenceGraph"
                    {:type ::not-a-graph})))
  (if (satisfies? graph/LibraryIndex g) g (wrap g {})))

(defn backend-graph
  "The backend graph inside `g` -- what `library-indexed` wrapped -- or
  `g` itself when it is not a wrapper."
  [g]
  (if (satisfies? Wrapped g) (unwrap g) g))
