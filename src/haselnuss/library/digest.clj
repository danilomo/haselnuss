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
           (java.nio.file Files LinkOption)
           (java.nio.file.attribute BasicFileAttributes)
           (java.security MessageDigest)
           (java.util.concurrent TimeUnit)))

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

;; --- stamps (TASK-266) -----------------------------------------------------------

(def racy-window-ms
  "How close to the moment it was stamped a file's modification time may
  be before the stamp is not trusted (`trusted-stamp`): two seconds, the
  coarsest modification-time granularity in common use (FAT; HFS+ and
  some network file systems keep whole seconds, ext4 a kernel tick)."
  2000)

(def ^:private follow-links (make-array LinkOption 0))

(defn stamp
  "`{:size bytes :mtime nanoseconds :key file-key}` for the regular file
  `f` (followed through symbolic links) from one `stat`, reading none of
  its content -- `:key` the file system's identity of the file (device
  and inode on Linux), or nil where it has none -- or nil when `f` is
  not a regular file or cannot be stat'ed. Equal stamps mean \"the same
  file, not modified\" up to the file system's timestamp granularity;
  see `trusted-stamp`."
  [f]
  (try
    (let [attrs (Files/readAttributes (.toPath (io/file f)) BasicFileAttributes
                                      ^"[Ljava.nio.file.LinkOption;" follow-links)]
      (when (.isRegularFile attrs)
        {:size (.size attrs)
         :mtime (.to (.lastModifiedTime attrs) TimeUnit/NANOSECONDS)
         :key (some-> (.fileKey attrs) str)}))
    (catch Exception _ nil)))

(defn trusted-stamp
  "`stamp`, taken BEFORE the file is read, when it may stand for what that
  read gets on a later run; nil when it may not. A stamp whose
  modification time lies within `racy-window-ms` of `now-ms` (the wall
  clock when it was taken), or after it, is \"racy\" (git's term): an
  edit that lands after the read in the same timestamp tick, keeping the
  size, would leave the stamp unchanged and hide the edit for good. Such
  a file is simply read again next time. (A file whose clock runs more
  than the window behind ours -- a skewed network file system -- is not
  caught.)"
  [stamp-value now-ms]
  (when (and (map? stamp-value)
             (< (quot (long (:mtime stamp-value)) 1000000) (- (long now-ms) racy-window-ms)))
    stamp-value))

(defn folder-stamp
  "`[observation newest-ms]` for the folder `f` (followed through symbolic
  links) from one `stat`, or nil when it is not a folder, cannot be
  stat'ed, or the file system keeps no change time (no `unix` attribute
  view). `observation` is a string of its modification time, change time
  and identity (device and inode): equal observations mean \"the same
  folder, holding the same names\", since adding, removing or renaming an
  entry changes the modification time, and a permission change -- which
  can make the folder unlistable -- the change time (TASK-296).
  `newest-ms`, the later of the two times in milliseconds, is what
  `trusted-stamp`'s racy rule applies to: the caller trusts the
  observation only when `newest-ms` lies more than `racy-window-ms`
  before the moment it was taken."
  [f]
  (try
    (let [attrs (Files/readAttributes (.toPath (io/file f)) "unix:lastModifiedTime,ctime,dev,ino,mode"
                                      ^"[Ljava.nio.file.LinkOption;" follow-links)
          ^java.nio.file.attribute.FileTime mtime (.get attrs "lastModifiedTime")
          ^java.nio.file.attribute.FileTime ctime (.get attrs "ctime")
          mode (.get attrs "mode")]
      (when (and mtime ctime (integer? mode)
                 ;; S_IFDIR: a folder, not a file the link now reaches
                 (= 0040000 (bit-and (long mode) 0170000)))
        [(str (.to mtime TimeUnit/NANOSECONDS) "/" (.to ctime TimeUnit/NANOSECONDS) "/"
              (.get attrs "dev") "/" (.get attrs "ino"))
         (max (.toMillis mtime) (.toMillis ctime))]))
    (catch Exception _ nil)))

(defn utf8-safe?
  "True when every string in `x` -- and the name of every keyword and
  symbol, nested through maps and collections -- survives a UTF-8 encode
  and decode unchanged, as a value in the saved state must: the state is
  written as UTF-8, which turns an unpaired surrogate (YAML's
  `\"\\uD800\"`) into `?`. A cache entry failing this is not kept, so a
  warm listing never differs from a cold one (TASK-266)."
  [x]
  (letfn [(ok? [^String s]
            (= s (String. (.getBytes s java.nio.charset.StandardCharsets/UTF_8)
                          java.nio.charset.StandardCharsets/UTF_8)))
          (walk [x]
            (cond
              (string? x) (ok? x)
              (or (keyword? x) (symbol? x)) (and (ok? (name x)) (ok? (str (namespace x))))
              (map? x) (every? (fn [[k v]] (and (walk k) (walk v))) x)
              (coll? x) (every? walk x)
              :else true))]
    (walk x)))

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
