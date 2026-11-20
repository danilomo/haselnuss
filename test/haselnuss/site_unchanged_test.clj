(ns haselnuss.site-unchanged-test
  "The record of an unchanged site (TASK-296, `haselnuss.site.unchanged`)
  at the unit level: its text format brings back any path, a cut or
  garbled record is no record, a folder's stamp changes with what a
  listing of it would see, and a listing's walk record is complete only
  when the next listing must list the same. The builds that use the
  record -- and every change that must send a build the long way -- are
  in `haselnuss.site-incremental-test`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.library :as library]
            [haselnuss.library.digest :as digest]
            [haselnuss.library.filesystem :as fs]
            [haselnuss.site.unchanged :as unchanged]
            [haselnuss.version :as version])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute FileTime PosixFilePermissions)
           (java.util Random)))

(defn- temp-dir ^File []
  (.toFile (Files/createTempDirectory "haselnuss-unchanged" (make-array FileAttribute 0))))

(defn- write! ^File [^File root rel text]
  (let [f (io/file root rel)]
    (io/make-parents f)
    (spit f text)
    ;; an old modification time: the file's stamp is trusted
    (Files/setLastModifiedTime (.toPath f) (FileTime/fromMillis (- (System/currentTimeMillis) 3600000)))
    f))

;; --- the format ------------------------------------------------------------------------

(def ^:private alphabet
  "What the fuzzed paths are made of: plain path characters, every
  character the format escapes, an escape's own text written plainly,
  non-ASCII, and two emoji, whose surrogate pairs share their first half
  -- so front coding meets a prefix that would end inside a pair."
  ["a" "b" "/" "." "-" " " "é" "\\" "\t" "\n" "\r" "\\t" "\\n" "\\\\" "😀" "😁"])

(defn- random-path [^Random rnd]
  (apply str (repeatedly (inc (.nextInt rnd 10)) #(nth alphabet (.nextInt rnd (count alphabet))))))

(defn- raw-prefix
  "How many leading chars `a` and `b` share, pairs or not."
  [^String a ^String b]
  (count (take-while true? (map = a b))))

(deftest record-format-round-trip-test
  (let [rnd (Random. 296)
        seen (atom {:records 0 :paths 0 :escaped 0 :inside-a-pair 0 :shared 0 :sections 0})
        library-names ["a" "b-1" "notes" "zz"]]
    (dotimes [_ 300]
      (let [dir (temp-dir)
            prefix (random-path rnd)
            some-paths #(vec (sort (distinct (repeatedly (.nextInt rnd 25) (fn [] (str prefix (random-path rnd)))))))
            section (fn [library]
                      (let [paths (some-paths)]
                        {:library library
                         :stats (mapv (fn [p] [(nth ["F" "E" "D" "R" "S"] (.nextInt rnd 5)) p]) paths)
                         :pages (if library
                                  (mapv (fn [p] [p (vec (repeatedly (.nextInt rnd 3) #(random-path rnd)))]) (some-paths))
                                  [])}))
            ;; TASK-297: a global section, then libraries in name order
            sections (into [(section nil)] (map section) (filter (fn [_] (.nextBoolean rnd)) library-names))
            record {:head {:generation "0123abcd" :output {:lines [(random-path rnd)]}}
                    :sections sections}
            paths (mapcat #(concat (map second (:stats %)) (map first (:pages %))) sections)]
        (io/make-parents (io/file dir unchanged/record-rel))
        (unchanged/write-record! dir record)
        (is (= record (unchanged/read-record dir)) (pr-str record))
        (swap! seen #(-> %
                         (update :records inc)
                         (update :sections + (count sections))
                         (update :paths + (count paths))
                         (update :escaped + (count (filter (fn [p] (some #{\\ \tab \newline \return} p)) paths)))
                         (update :inside-a-pair + (count (filter (fn [[a b]]
                                                                   (let [k (raw-prefix a b)]
                                                                     (and (pos? k) (< k (count b))
                                                                          (Character/isHighSurrogate (.charAt ^String b (dec k))))))
                                                                 (partition 2 1 paths))))
                         (update :shared + (count (filter (fn [[a b]] (pos? (raw-prefix a b))) (partition 2 1 paths))))))))
    (testing (str "not vacuous: " (pr-str @seen))
      (is (< 1000 (:escaped @seen)))
      (is (< 50 (:inside-a-pair @seen)) "prefixes that would have split a surrogate pair")
      (is (< 2000 (:shared @seen)))
      (is (< 600 (:sections @seen)) "records of several sections"))))

(deftest escape-round-trip-test
  (let [rnd (Random. 296)]
    (dotimes [_ 5000]
      (let [s (random-path rnd)
            e (unchanged/escape s)]
        (is (not-any? #{\tab \newline \return} e))
        (is (= s (unchanged/unescape e)))))
    (testing "text no escape makes is refused"
      (is (nil? (unchanged/unescape "a\\")))
      (is (nil? (unchanged/unescape "a\\x"))))))

(deftest cut-or-garbled-record-test
  (let [dir (temp-dir)
        record {:head {:generation "abc"}
                :sections [{:library nil :stats [["S" "/s/books"]] :pages []}
                           {:library "lib" :stats [["D" "/a"] ["F" "/a/b.hdoc"]]
                            :pages [["books/x/index.html" ["books/x/i.png"]]]}
                           {:library "other" :stats [] :pages []}]}
        f (io/file dir unchanged/record-rel)]
    (io/make-parents f)
    (unchanged/write-record! dir record)
    (is (= record (unchanged/read-record dir)))
    (let [text (slurp f)]
      (testing "every cut of it is no record"
        (doseq [n (range (count text))]
          (spit f (subs text 0 n))
          (is (nil? (unchanged/read-record dir)) (pr-str (subs text 0 n)))))
      (testing "nor is one with the counts, a kind, a prefix length, an escape or a section wrong"
        (doseq [[what bad] [["the counts" (str/replace text "end 3 7" "end 3 6")]
                            ["the section count" (str/replace text "end 3 7" "end 2 7")]
                            ["a line more" (str/replace text "end 3 7" "P0\tbooks/y\nend 3 7")]
                            ["a kind" (str/replace text "F2\t" "X2\t")]
                            ["a page among the stats" (str/replace text "F2\t" "P2\t")]
                            ["a stat after the pages" (str/replace text "\nL\tother" "\nF0\t/z\nL\tother")]
                            ["a page in the global section" (str/replace text "S0\t/s/books\n" "S0\t/s/books\nP0\tbooks/q\n")]
                            ["no global section first" (str/replace text "G\n" "L\taaa\n")]
                            ["libraries out of order" (str/replace text "L\tother" "L\tabc")]
                            ["a library twice" (str/replace text "L\tother" "L\tlib")]
                            ["a library that is no name" (str/replace text "L\tother" "L\tOther")]
                            ["a prefix length" (str/replace text "F2\t" "F9\t")]
                            ["an escape" (str/replace text "/b.hdoc" "/b\\q.hdoc")]
                            ["the format line" (str/replace text "haselnuss-unchanged 2" "haselnuss-unchanged 1")]
                            ["the head" (str/replace text "{:generation" "{:generation [")]]]
          (is (not= text bad) (str what ": the replacement applies"))
          (spit f bad)
          (is (nil? (unchanged/read-record dir)) what)))
      (testing "a record that is a folder, or absent"
        (.delete f)
        (is (nil? (unchanged/read-record dir)))
        (.mkdir f)
        (is (nil? (unchanged/read-record dir)))))))

;; --- folder stamps and the walk record ---------------------------------------------------

(defn- pause
  "Long enough for the next change to get a later timestamp than the
  last, on file systems whose clock ticks every few milliseconds."
  []
  (Thread/sleep 30))

(deftest folder-stamp-test
  (let [dir (temp-dir)
        obs #(unchanged/folder-observation (str dir))
        a (obs)]
    (testing "an entry added, renamed or removed changes the stamp of the folder holding it"
      (pause)
      (write! dir "x.hdoc" "x")
      (let [b (obs)]
        (is (not= a b))
        (pause)
        (.renameTo (io/file dir "x.hdoc") (io/file dir "y.hdoc"))
        (let [c (obs)]
          (is (not= b c))
          (pause)
          (.delete (io/file dir "y.hdoc"))
          (is (not= c (obs))))))
    (testing "and so does a permission change, which can make it unlistable"
      (let [before (obs)]
        (pause)
        (Files/setPosixFilePermissions (.toPath dir) (PosixFilePermissions/fromString "-wx------"))
        (try (is (not= before (obs)))
             (finally (Files/setPosixFilePermissions (.toPath dir) (PosixFilePermissions/fromString "rwx------"))))))
    (testing "an edit inside a file of the folder does not: the file's own stamp shows it"
      (write! dir "z.hdoc" "one")
      (let [before (obs)]
        (pause)
        (spit (io/file dir "z.hdoc") "two")
        (is (= before (obs)))))
    (testing "a file, or nothing, is no folder"
      (is (= "none" (unchanged/folder-observation (str (io/file dir "z.hdoc")))))
      (is (= "none" (unchanged/folder-observation (str (io/file dir "nothing"))))))))

(defn- walk-of
  "The walk record of a listing of library `lib` at `root` with a listing
  cache, a `pause` after whatever changed last: with the racy window
  closed (`digest/racy-window-ms` 0), a folder changed in the very
  millisecond of the listing is still racy."
  ([root] (walk-of root {}))
  ([^File root config]
   (pause)
   (let [lib (fs/filesystem-library (merge {:name "lib" :path (str root)} config) {:listing-cache {}})]
     (library/list-books lib)
     (fs/listing-walk lib))))

(deftest listing-walk-test
  (let [root (temp-dir)]
    (write! root "a.hdoc" "---\ntitle: A\n---\n\nA.\n")
    (write! root "s/b.hdoc" "---\ntitle: B\n---\n\nB.\n")
    (write! root "s/c/book.hdoc" "---\ntitle: C\n---\n\nC.\n")
    (write! root "Bad Name/book.hdoc" "---\ntitle: Bad\n---\n")
    (write! root "_drafts/d.hdoc" "---\ntitle: D\n---\n")
    (write! root "notes.txt" "not a book")
    (testing "folders made just now are racy: the walk is not complete"
      (is (false? (:complete? (walk-of root)))))
    (with-redefs [digest/racy-window-ms 0]
      (testing "with nothing racy it is complete, and stamps every folder it looked into"
        (let [w (walk-of root)]
          (is (true? (:complete? w)))
          (is (= #{(str root) (str (io/file root "s")) (str (io/file root "Bad Name"))}
                 (set (keys (:folders w))))
              "the shelves and the invalid folder, whose diagnostic depends on its book.hdoc; not a book folder")))
      (testing "an entry it passes over that is a symbolic link makes it incomplete"
        (let [link (.toPath (io/file root "s/ghost.hdoc"))]
          (Files/createSymbolicLink link (.toPath (io/file root "nowhere.hdoc")) (make-array FileAttribute 0))
          (try (is (false? (:complete? (walk-of root))))
               (finally (Files/delete link))))
        (let [hidden (.toPath (io/file root "_ghost.hdoc"))]
          (Files/createSymbolicLink hidden (.toPath (io/file root "nowhere.hdoc")) (make-array FileAttribute 0))
          (try (is (true? (:complete? (walk-of root))) "an ignored name is ignored, link or not")
               (finally (Files/delete hidden)))))
      (testing "a book whose front matter the cache cannot keep makes it incomplete"
        (let [f (write! root "s/odd.hdoc" "---\ntitle: \"\\uD800\"\n---\n")]
          (try (is (false? (:complete? (walk-of root))))
               (finally (.delete f)))))
      (testing "a folder that cannot be listed makes it incomplete"
        (let [s (.toPath (io/file root "s"))]
          (Files/setPosixFilePermissions s (PosixFilePermissions/fromString "-wx------"))
          (try (when-not (.list (io/file root "s"))
                 (is (false? (:complete? (walk-of root)))))
               (finally (Files/setPosixFilePermissions s (PosixFilePermissions/fromString "rwx------")))))))
    (testing "a generated shelf: complete when listed from its cache or its scripts are disabled, not when its script fails"
      (let [gen (temp-dir)]
        (write! gen "g/shelf.clj" "{:books (fn [_] (throw (ex-info \"no\" {})))}\n")
        (with-redefs [digest/racy-window-ms 0]
          (is (true? (:complete? (walk-of gen))) "scripts disabled: it lists none, whatever the script")
          (is (false? (:complete? (walk-of gen {:scripts true}))) "the script failed: next time it may not")
          (write! gen "g/shelf.clj" "{:books (fn [_] [{:slug \"a\" :title \"A\"}]) :document (fn [_ _] nil)}\n")
          (is (true? (:complete? (walk-of gen {:scripts true}))) "listed by the script, into the cache"))))
    (testing "no listing cache, no walk record"
      (let [lib (fs/filesystem-library {:name "lib" :path (str root)})]
        (library/list-books lib)
        (is (nil? (fs/listing-walk lib)))))))

(deftest site-folder-entries-test
  (let [site (temp-dir)
        books (io/file site "books")
        page (write! site "books/l/b/index.html" "page")
        folders (mapv str [books (io/file books "l") (io/file books "l/b")])]
    (testing "folders written just now are racy: listed, each entry's name and kind"
      (let [entries (unchanged/site-folder-entries books folders)]
        (is (= ["R" "R" "R"] (mapv first entries)))
        (is (= (str "index.html" "\u0001" "f") (nth (last entries) 2)))
        (testing "and a stray file, folder or link changes the listing"
          (doseq [[what add! undo!] [["a file" #(write! site "books/l/b/x.txt" "x") #(.delete (io/file books "l/b/x.txt"))]
                                     ["a folder" #(.mkdir (io/file books "l/b/d")) #(.delete (io/file books "l/b/d"))]
                                     ["a link" #(Files/createSymbolicLink (.toPath (io/file books "l/b/k"))
                                                                          (.toPath page) (make-array FileAttribute 0))
                                      #(Files/delete (.toPath (io/file books "l/b/k")))]]]
            (add!)
            (is (not= (nth (last entries) 2) (unchanged/listing-observation (last folders))) what)
            (undo!)
            (is (= (nth (last entries) 2) (unchanged/listing-observation (last folders))) (str what " undone"))))))
    (with-redefs [digest/racy-window-ms 0]
      (pause)
      (testing "trusted ones are stamped"
        (let [entries (unchanged/site-folder-entries books folders)]
          (is (= ["S" "S" "S"] (mapv first entries)))
          (is (= (nth (last entries) 2) (unchanged/folder-observation (last folders))))
          (pause)
          (write! site "books/l/b/x.txt" "x")
          (is (not= (nth (last entries) 2) (unchanged/folder-observation (last folders))) "a stray file shows"))))
    (testing "no books/ folder: books/ stands as absent"
      (is (= [["S" (str (io/file site "nothing")) "none"]]
             (unchanged/site-folder-entries (io/file site "nothing") [])))
      (is (= "none" (unchanged/folder-observation (str (io/file site "nothing"))))))))

(deftest build-identity-test
  (let [root (temp-dir)
        src (io/file root "src")
        jar (write! root "deps/dep.jar" "a jar")
        cp (str src File/pathSeparator jar)
        id #(version/build-identity {:native? false :class-path cp})]
    (write! src "haselnuss/a.clj" "(ns haselnuss.a)")
    (write! src "other/z.clj" "(ns other.z)")
    (let [before (id)]
      (testing "the stamps of a classpath's jars and of the files below each folder's haselnuss/"
        (is (re-matches #"[0-9a-f]{64}" before))
        (is (= before (id)) "the same while nothing changes"))
      (testing "a source edited, added or removed below haselnuss/ changes it"
        (Files/setLastModifiedTime (.toPath (io/file src "haselnuss/a.clj")) (FileTime/fromMillis 1000))
        (is (not= before (id)))
        (let [edited (id)]
          (write! src "haselnuss/b.clj" "(ns haselnuss.b)")
          (is (not= edited (id)))))
      (testing "a file outside haselnuss/ does not"
        (let [now (id)]
          (write! src "other/y.clj" "(ns other.y)")
          (is (= now (id)))))
      (testing "a jar replaced changes it"
        (let [now (id)]
          (.delete jar)
          (write! root "deps/dep.jar" "another jar")
          (is (not= now (id))))))
    (testing "the native image: the executable's stamp"
      (let [exe (write! root "bin/haselnuss" "binary")
            native #(version/build-identity {:native? true :executable (str exe)})
            before (native)]
        (is (re-matches #"[0-9a-f]{64}" before))
        (.delete exe)
        (write! root "bin/haselnuss" "rebuilt")
        (is (not= before (native)))))
    (testing "unknown: nil"
      (is (nil? (version/build-identity {:native? true :executable nil})))
      (is (nil? (version/build-identity {:native? true :executable (str (io/file root "none"))})))
      (is (nil? (version/build-identity {:native? false :class-path ""}))))
    (testing "this JVM knows its own"
      (is (string? (version/build-identity))))))
