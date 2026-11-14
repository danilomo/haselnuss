(ns haselnuss.library.digest
  "File digests taken from the bytes a load actually read (TASK-244).

  A reference-graph record's `:hash` (`haselnuss.library.graph.scan`)
  stands for the content its edges and labels were built from. Hashing
  the files again after the load would pair a NEW hash with OLD edges
  whenever an edit lands between the two reads, and a refresh comparing
  hashes would then never rescan the book. So the loaders
  (`haselnuss.library.filesystem`, `haselnuss.library.generated`) read
  each file's bytes once through `read-text`, keep the digest of exactly
  those bytes, and decode them as `slurp` would have -- the same decoder
  over the same bytes, so the text, and every build, is unchanged.

  A digest is the hex SHA-256 of the bytes; a file that could not be read
  is `unreadable` (the exception's class name); one read twice with
  different bytes in one load is `changed-during-load`, which matches no
  file on disk, so the book is rescanned whatever the file holds now."
  (:require [clojure.java.io :as io])
  (:import (java.io InputStream)
           (java.security MessageDigest)))

(defn hex
  "`bs` as lowercase hex."
  ^String [^bytes bs]
  (apply str (map #(format "%02x" (bit-and % 0xff)) bs)))

(defn sha256
  "The SHA-256 of `bs`."
  ^bytes [^bytes bs]
  (.digest (MessageDigest/getInstance "SHA-256") bs))

(defn bytes-digest
  "The digest of the bytes `bs`."
  ^String [^bytes bs]
  (hex (sha256 bs)))

(defn unreadable
  "The digest of a file whose read threw `e`."
  ^String [^Throwable e]
  (str "unreadable:" (.getName (class e))))

(def changed-during-load
  "The digest of a file one load read twice and found different."
  "changed-during-load")

(defn record
  "`digests` (path -> digest) with `digest` for `path`: `changed-during-load`
  when the path already has a different one."
  [digests path digest]
  (let [old (get digests path)]
    (assoc digests path (if (and (some? old) (not= old digest)) changed-during-load digest))))

(defn read-text
  "`[text digest]` for the file `f` (anything `clojure.java.io/input-stream`
  takes, as `slurp` does): its bytes, read once, decoded with `encoding` as
  `(slurp f :encoding encoding)` decodes them, and the digest of those
  bytes. Throws what `slurp` throws when the file cannot be opened."
  [f ^String encoding]
  (let [bs (with-open [^InputStream in (io/input-stream f)] (.readAllBytes in))]
    [(slurp bs :encoding encoding) (bytes-digest bs)]))

(defn reader
  "A fn `(read f)` -> text, decoding with `encoding` (`read-text`), that
  records each read's digest -- or `unreadable` before rethrowing a failed
  read -- in the volatile `digests` under `(str f)`."
  [digests ^String encoding]
  (fn [f]
    (try
      (let [[text digest] (read-text f encoding)]
        (vswap! digests record (str f) digest)
        text)
      (catch Throwable t
        (vswap! digests record (str f) (unreadable t))
        (throw t)))))
