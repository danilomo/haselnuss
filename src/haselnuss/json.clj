(ns haselnuss.json
  "AST <-> JSON interchange (`SPEC.md` sec11): the JSON
  representation is a faithful, round-trippable encoding of `haselnuss.ast`,
  and its shape (a JSON Schema) is generated from the very same malli
  schemas that validate the in-memory AST, so the two can never drift (per
  doc-2 -- TASK-3).

  Text (de)serialization uses `clojure.data.json`; the AST<->JSON *data*
  boundary (keywords <-> strings for tags/enums, string-keyed `:props`
  maps, etc.) is handled entirely by `malli.transform/json-transformer`,
  driven by the schema -- this file adds no bespoke per-node-type
  conversion logic of its own."
  (:require [clojure.data.json :as json]
            [haselnuss.ast :as ast]
            [malli.core :as m]
            [malli.json-schema :as mjs]
            [malli.transform :as mt]))

(defn ->json-data
  "Encodes `value` (plain AST data, valid against `schema`, defaulting to
  `ast/Document`) into a JSON-ready Clojure data structure (string tags,
  string-keyed maps, keyword map keys) via `malli.transform/json-transformer`.

  Throws `ex-info` (`:type ::invalid-ast`) instead of silently producing a
  malformed result if `value` does not validate against `schema` -- malli's
  encoder otherwise leaves an unrecognized/malformed node's `:t`/`:kind`
  untouched rather than erroring, since a `:multi` schema that can't
  dispatch a node just passes it through as-is."
  ([value] (->json-data ast/Document value))
  ([schema value]
   (when-not (m/validate schema value)
     (throw (ex-info "cannot serialize: value does not conform to the AST schema"
                     {:type ::invalid-ast
                      :schema (m/form schema)
                      :explanation (m/explain schema value)})))
   (m/encode schema value mt/json-transformer)))

(defn ->json
  "Encodes `value` all the way to a JSON string. See `->json-data`."
  ([value] (json/write-str (->json-data value)))
  ([schema value] (json/write-str (->json-data schema value))))

(defn json-data->ast
  "Decodes `data` (a JSON-ready Clojure data structure, e.g. from
  `clojure.data.json/read-str` with `:key-fn keyword`) back into AST data via
  `malli.transform/json-transformer`, validated against `schema` (defaulting
  to `ast/Document`).

  Throws `ex-info` (`:type ::invalid-json`) if the decoded result does not
  validate against `schema`, e.g. an unknown/malformed node type -- decoding
  a node whose dispatch key doesn't match any known variant leaves it
  (silently) undecoded rather than raising on its own, so this checks the
  outcome explicitly instead of trusting the decode step succeeded."
  ([data] (json-data->ast ast/Document data))
  ([schema data]
   (let [decoded (m/decode schema data mt/json-transformer)]
     (when-not (m/validate schema decoded)
       (throw (ex-info "cannot deserialize: JSON does not conform to the AST schema"
                       {:type ::invalid-json
                        :schema (m/form schema)
                        :explanation (m/explain schema decoded)})))
     decoded)))

(defn json->ast
  "Parses `s` (a JSON string) and decodes it into AST data. See
  `json-data->ast`."
  ([s] (json-data->ast (json/read-str s :key-fn keyword)))
  ([schema s] (json-data->ast schema (json/read-str s :key-fn keyword))))

(def document-json-schema
  "The JSON Schema for `ast/Document`, generated via `malli.json-schema` from
  the exact same schema (and shared registry) used to validate the in-memory
  AST -- see the doc-2 decision this satisfies (TASK-3 AC #3)."
  (mjs/transform ast/Document))
