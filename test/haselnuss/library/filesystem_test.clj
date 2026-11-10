(ns haselnuss.library.filesystem-test
  "The `:filesystem` provider (TASK-151) against the fixture libraries in
  `test/fixtures/libraries/`:

  - `reformed/` mirrors `contract/sample-expectation` on disk -- a book
    folder whose chapter is `!include`d, single-file books, the nested
    shelf `confessions/reformed` -- plus names to ignore (`.`/`_`, a
    `README.md`) and names to skip with `:invalid-book-name` (AC #1).
  - `shared/` mirrors `contract/collision-expectation`: two books with
    the slug `same`, one a book folder and one a single file.

  Both pass the shared provider contract (AC #5). Listing is shown to
  parse nothing (AC #2) by counting the provider's parse fn, which is
  injected rather than `with-redefs`'d so the count cannot race a
  namespace running concurrently (TASK-147). Fixtures that must not parse
  -- malformed front matter, raw HTML -- are written to a temp folder,
  because `haselnuss.hdoc-round-trip-test` parses every committed `.hdoc`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.ast :as ast]
            [haselnuss.book-ref :as book-ref]
            [haselnuss.library :as library]
            [haselnuss.library.contract :as contract]
            [haselnuss.library.directory :as directory]
            [haselnuss.library.filesystem :as fs]
            [haselnuss.parser :as parser])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:private fixtures (io/file "test/fixtures/libraries"))

(defn- fixture-path [lib-name] (str (.getAbsoluteFile (io/file fixtures lib-name))))

(defn- fm-title
  "The inlines a front-matter `title:` of `s` parses to -- what a listed
  entry's `:title` must be, since it is the document's own meta title."
  [s]
  (:title (parser/front-matter (str "---\ntitle: " s "\n---\n"))))

(defn- with-parsed-titles
  "`expectation` with every book's string `:title` replaced by the inlines
  the parser makes of it."
  [expectation]
  (update expectation :books (partial mapv #(update % :title fm-title))))

(defn- counting-library
  "A filesystem library over fixture `lib-name`, a fn answering how many
  ENTRY files it has parsed (so the contract can prove each book loads
  at most once), and an atom of every source path it has parsed."
  [lib-name]
  (let [parsed (atom [])
        lib (fs/filesystem-library
             {:name lib-name :path (fixture-path lib-name)}
             {:parse (fn [source opts]
                       (swap! parsed conj (:source opts))
                       (parser/parse source opts))})
        ;; An entry file is a book.hdoc, or an .hdoc no folder above which
        ;; (up to the library root) is a book folder; a chapter is neither.
        root (io/file (fixture-path lib-name))
        entry? (fn [path]
                 (let [f (io/file path)]
                   (or (= "book.hdoc" (.getName f))
                       (not-any? #(.isFile (io/file % "book.hdoc"))
                                 (take-while #(not= root %)
                                             (iterate (fn [^File d] (.getParentFile d))
                                                      (.getParentFile f)))))))]
    [lib #(count (filter entry? @parsed)) parsed]))

(defn- temp-dir ^File []
  (.toFile (Files/createTempDirectory "haselnuss-fslib" (make-array FileAttribute 0))))

(defn- write!
  ^File [^File dir rel text]
  (let [file (io/file dir rel)]
    (io/make-parents file)
    (spit file text)
    file))

(defn- by-type [lib t] (filter #(= t (:type %)) (library/library-diagnostics lib)))

;; --- AC #5: the contract -----------------------------------------------------

(def ^:private reformed-expectation
  (assoc (with-parsed-titles contract/sample-expectation)
         :diagnostics [{:type :invalid-book-name
                        :path (str (io/file (fixture-path "reformed") "Bad Name.hdoc"))}
                       {:type :invalid-book-name
                        :path (str (io/file (fixture-path "reformed") "UPPER"))}
                       {:type :invalid-book-name
                        :path (str (io/file (fixture-path "reformed") "creeds" "Nicene.hdoc"))}]))

(deftest provider-contract-test
  (testing "the reformed fixture, mirroring the contract's sample"
    (let [[lib loads] (counting-library "reformed")]
      (contract/check-provider-contract
       {:library lib :expected reformed-expectation :document-loads loads})))
  (testing "the shared fixture, mirroring the contract's slug collision"
    (let [[lib loads] (counting-library "shared")]
      (contract/check-provider-contract
       {:library lib
        :expected (with-parsed-titles contract/collision-expectation)
        :document-loads loads}))))

;; --- AC #1: layout -----------------------------------------------------------

(deftest layout-test
  (let [lib (fs/filesystem-library {:name "reformed" :path (fixture-path "reformed")})
        ids (set (map (comp book-ref/format-book-id :id) (library/list-books lib)))
        root (fixture-path "reformed")]
    (testing "book folders, single-file books and nested shelves give canonical ids"
      (is (= #{"reformed:confessions/reformed/heidelberg"
               "reformed:confessions/reformed/westminster"
               "reformed:creeds/apostles"
               "reformed:notes"}
             ids)))
    (testing "a book folder's path is the folder, a single-file book's is the file"
      (let [westminster (library/find-book lib "westminster")
            heidelberg (library/find-book lib "heidelberg")]
        (is (= (str (io/file root "confessions" "reformed" "westminster"))
               (fs/book-path westminster)))
        (is (= (io/file root "confessions" "reformed" "westminster" "book.hdoc")
               (fs/entry-file westminster)))
        (is (= (str (io/file root "confessions" "reformed" "heidelberg.hdoc"))
               (fs/book-path heidelberg)))))
    (testing "nothing inside a book folder is a book or an invalid name"
      (is (not-any? #(str/includes? (str (:path %)) "westminster")
                    (library/library-diagnostics lib)))
      (is (nil? (library/find-book lib "ch01"))))
    (testing "names starting with . or _ and non-.hdoc files are ignored silently"
      (doseq [slug ["wip" "secret" "skip" "_skip" "dotted" "README" "readme"]]
        (is (nil? (library/find-book lib slug)) slug))
      (is (not-any? #(re-find #"/[._]|README" (:path %))
                    (library/library-diagnostics lib))))
    (testing "every invalid name is an :invalid-book-name naming its path, and is skipped"
      (let [diags (by-type lib :invalid-book-name)]
        (is (= 3 (count diags)))
        (is (every? library/diagnostic? diags))
        (is (every? #(str/includes? (:message %) (:path %)) diags))
        (is (nil? (library/find-book lib "hidden-by-shelf")))
        (is (nil? (library/find-book lib "Nicene")))))
    (testing "the shelf tree is the folders books sit in"
      (is (= {"confessions" {"reformed" {}} "creeds" {}} (library/shelf-tree lib))))))

;; --- AC #2: front matter only ------------------------------------------------

(deftest listing-reads-front-matter-only-test
  (let [[lib _ parsed] (counting-library "reformed")
        entries (into {} (map (juxt (comp :slug :id) identity)) (library/list-books lib))]
    (testing "title, lang and the optional ddc/tags come from front matter"
      (is (= {:id (book-ref/book-id "reformed" ["confessions" "reformed"] "heidelberg")
              :title (fm-title "Heidelberg Catechism")
              :lang "en" :ddc "238.42" :tags ["catechism"]}
             (get entries "heidelberg")))
      (is (= {:id (book-ref/book-id "reformed" ["creeds"] "apostles")
              :title (fm-title "Apostles' Creed")}
             (get entries "apostles"))))
    (testing "listing, the shelf tree, diagnostics and find-book parse no file"
      (library/shelf-tree lib)
      (library/library-diagnostics lib)
      (library/book-entry (library/find-book lib "westminster"))
      (is (= [] @parsed))))
  (testing "a book whose body cannot be parsed still lists, and only document fails"
    (let [dir (temp-dir)
          _ (write! dir "raw-html.hdoc"
                    "---\ntitle: Raw\nlang: en\n---\n\n<div>raw html is not supported</div>\n")
          lib (fs/filesystem-library {:name "broken" :path (str dir)})]
      (is (= [{:id (book-ref/book-id "broken" "raw-html") :title (fm-title "Raw") :lang "en"}]
             (library/list-books lib)))
      (is (= [] (library/library-diagnostics lib)))
      (let [doc (library/document (library/find-book lib "raw-html"))]
        (is (= :book-failed (:type doc)))
        (is (library/diagnostic? doc))
        (is (str/includes? (:message doc) "raw-html.hdoc"))))))

;; --- AC #3: find-book and document ----------------------------------------

(deftest find-book-and-document-test
  (let [[lib loads parsed] (counting-library "reformed")]
    (testing "find-book answers the same Book value every time: the index is built once"
      (is (identical? (library/find-book lib "westminster")
                      (library/find-book lib "westminster"))))
    (testing "document parses the entry file and expands its includes relative to the book"
      (let [doc (library/document (library/find-book lib "westminster"))
            section (first (:blocks doc))]
        (is (ast/valid? ast/Document doc))
        (is (= (fm-title "Westminster Confession") (get-in doc [:meta :title])))
        (is (= :section (:t section)))
        (is (= "sec:ch1" (get-in section [:attr :id])))
        (is (not-any? #(= :include (:t %)) (:blocks doc)))
        (is (= [] (fs/include-warnings (library/find-book lib "westminster"))))
        (is (= 2 (count @parsed)) "book.hdoc and chapters/ch01.hdoc")
        (is (some #(str/ends-with? % (str "westminster" File/separator "chapters"
                                          File/separator "ch01.hdoc"))
                  @parsed))))
    (testing "a second lookup and document call parse nothing more"
      (library/document (library/find-book lib "westminster"))
      (is (= 1 (loads)))
      (is (= 2 (count @parsed)))))
  (testing "a missing chapter is an include warning kept with the book, not a failure"
    (let [dir (temp-dir)
          _ (write! dir "shelf/missing-chapter/book.hdoc"
                    "---\ntitle: Missing\n---\n\n!include chapters/nope.hdoc\n\nStill here.\n")
          lib (fs/filesystem-library {:name "x" :path (str dir)})
          book (library/find-book lib "missing-chapter")]
      (is (ast/valid? ast/Document (library/document book)))
      (is (= [:unreadable-include] (map :type (fs/include-warnings book))))
      (testing "located at the book's own !include line (TASK-163)"
        (is (= [[(str (fs/entry-file book)) 5]]
               (map (juxt :source-file :source-line) (fs/include-warnings book)))))))
  (testing "a CRLF book with a BOM loads as its LF twin, CRLF chapter included (TASK-162)"
    (let [book-text "---\ntitle: Win\n---\n\n!include chapters/one.hdoc\n\n!quote bible:romanos#14:8\n"
          chapter-text "# One {#sec:one}\n\nText.\n"
          crlf #(str/replace % "\n" "\r\n")
          load-doc (fn [book chapter]
                     (let [dir (temp-dir)]
                       (write! dir "s/win/book.hdoc" book)
                       (write! dir "s/win/chapters/one.hdoc" chapter)
                       (let [b (library/find-book (fs/filesystem-library {:name "x" :path (str dir)}) "win")]
                         [(library/document b) (fs/include-warnings b)])))
          [lf-doc] (load-doc book-text chapter-text)
          [win-doc warnings] (load-doc (str "\uFEFF" (crlf book-text)) (str "\uFEFF" (crlf chapter-text)))]
      (is (= [] warnings))
      (is (= [:section :book-quote] (mapv :t (:blocks win-doc))))
      (is (= lf-doc win-doc))))
  (testing "filesystem books are not Locatable in v1"
    (let [lib (fs/filesystem-library {:name "reformed" :path (fixture-path "reformed")})]
      (is (not (satisfies? library/Locatable (library/find-book lib "heidelberg")))))))

;; --- AC #4: invalid names and slug collisions -------------------------------

(deftest collision-test
  (let [lib (fs/filesystem-library {:name "shared" :path (fixture-path "shared")})
        root (fixture-path "shared")
        paths [(str (io/file root "a" "same.hdoc")) (str (io/file root "b" "c" "same"))]]
    (testing "duplicate-slug names the slug, both canonical ids and both paths"
      (let [[d & more] (by-type lib :duplicate-slug)]
        (is (nil? more))
        (is (= "same" (:slug d)))
        (is (= ["shared:a/same" "shared:b/c/same"] (:books d)))
        (is (= paths (:paths d)))
        (is (every? #(str/includes? (:message d) %) paths))))
    (testing "a reference to the slug is ambiguous whatever shelf it writes"
      (doseq [written ["shared:same" "shared:a/same" "shared:b/c/same"]
              :let [{:keys [id]} (book-ref/parse-book-ref written)
                    found (library/find-book lib (:slug id))]]
        (is (= :ambiguous-book (:type found)) written)
        (is (= paths (:paths found)) written)))
    (testing "the colliding books are both still listed"
      (is (= 2 (count (filter #(= "same" (:slug (:id %))) (library/list-books lib))))))))

(deftest scan-diagnostics-test
  (let [dir (temp-dir)
        _ (write! dir "bad-yaml.hdoc" "---\ntitle: [unclosed\n---\n\nBody.\n")
        _ (write! dir "book.hdoc" "A library root is a shelf.\n")
        _ (write! dir "Shelf/x.hdoc" "x\n")
        _ (write! dir "Book-Folder/book.hdoc" "x\n")
        lib (fs/filesystem-library {:name "broken" :path (str dir)})]
    (testing "malformed front matter: listed under its slug, reported, and document fails"
      (is (= [{:id (book-ref/book-id "broken" "bad-yaml") :title (fm-title "bad-yaml")}]
             (library/list-books lib)))
      (let [[d] (by-type lib :invalid-front-matter)]
        (is (library/diagnostic? d))
        (is (= "broken:bad-yaml" (:book d))))
      (is (= :book-failed (:type (library/document (library/find-book lib "bad-yaml"))))))
    (testing "a book.hdoc at the root, an invalid shelf and an invalid book folder"
      (is (= #{"book.hdoc" "Shelf" "Book-Folder"}
             (set (map #(.getName (io/file (:path %))) (by-type lib :invalid-book-name)))))
      (is (str/includes? (:message (first (filter #(str/ends-with? (:path %) "book.hdoc")
                                                  (by-type lib :invalid-book-name))))
                         "library root")))))

(defn- one-book
  "The single entry, diagnostics and document of a library holding only
  `text` as `slug.hdoc`."
  ([slug text] (one-book slug text {}))
  ([slug text opts]
   (let [dir (temp-dir)
         _ (write! dir (str slug ".hdoc") text)
         lib (fs/filesystem-library {:name "l" :path (str dir)} opts)]
     {:entry (first (library/list-books lib))
      :diagnostics (library/library-diagnostics lib)
      :document (delay (library/document (library/find-book lib slug)))})))

(deftest front-matter-agrees-with-the-parser-test
  (doseq [[label text] [["a leading blank line" "\n---\ntitle: Leading\n---\n\nBody.\n"]
                        ["a leading blank CRLF line after a BOM" "\uFEFF\r\n---\r\ntitle: Leading\r\n---\r\n"]
                        ["an unclosed fence" "---\ntitle: Open\n\nBody.\n"]
                        ["an unclosed CRLF fence" "---\r\ntitle: Open\r\n\r\nBody.\r\n"]
                        ["a lone CR after the fence" "---\rtitle: Cr\r---\r\rBody.\r"]
                        ["a fence after a space" " ---\ntitle: Indented\n---\n"]
                        ["a fence after a BOM and a space" "\uFEFF ---\ntitle: Indented\n---\n"]]]
    (testing (str label ": no front matter to the parser, none to the listing")
      (let [{:keys [entry diagnostics document]} (one-book "plain" text)]
        (is (nil? (get-in @document [:meta :title])) "the parser sees no front matter")
        (is (= (fm-title "plain") (:title entry)) "so the listing titles it by slug")
        (is (= [] diagnostics)))))
  (doseq [[label text] [["CRLF fences" "---\r\ntitle: Win\r\n---\r\n\r\nBody.\r\n"]
                        ["a BOM" "\uFEFF---\ntitle: Win\n---\n\nBody.\n"]
                        ["a BOM and CRLF fences" "\uFEFF---\r\ntitle: Win\r\n---\r\n\r\nBody.\r\n"]
                        ["a CRLF ... fence" "---\r\ntitle: Win\r\n...\r\n"]]]
    (testing (str label " (TASK-162): front matter to the parser and the listing alike")
      (let [{:keys [entry diagnostics document]} (one-book "win" text)]
        (is (= (fm-title "Win") (get-in @document [:meta :title]) (:title entry)))
        (is (= [] diagnostics)))))
  (testing "a CRLF field value keeps no \\r in the listing (TASK-162)"
    (is (= {:lang "en" :tags ["a" "b"]}
           (select-keys (:entry (one-book "f" "---\r\nlang: en\r\ntags: [a, b]\r\n---\r\n")) [:lang :tags]))))
  (testing "a closing ... fence and a blank title agree too"
    (let [{:keys [entry document]} (one-book "dots" "---\ntitle: Dotted\n...\n\nBody.\n")]
      (is (= (get-in @document [:meta :title]) (:title entry) (fm-title "Dotted"))))
    (let [{:keys [entry]} (one-book "blank" "---\ntitle:\n---\n")]
      (is (= (fm-title "blank") (:title entry))))))

(deftest metadata-is-listed-as-written-or-reported-test
  (testing "a quoted ddc, a string lang and a scalar tag are listed"
    (let [{:keys [entry diagnostics]}
          (one-book "ok" "---\nlang: en\nddc: \"230.10\"\ntags: solo\n---\n")]
      (is (= {:lang "en" :ddc "230.10" :tags ["solo"]} (select-keys entry [:lang :ddc :tags])))
      (is (= [] diagnostics))))
  (doseq [[label yaml field] [["an unquoted ddc" "ddc: 230.10" :ddc]
                              ["an integer ddc" "ddc: 238" :ddc]
                              ["an empty lang" "lang:" :lang]
                              ["a blank lang" "lang: '  '" :lang]
                              ["a numeric lang" "lang: 1" :lang]
                              ["tags as a map" "tags: {a: 1}" :tags]
                              ["tags with a number" "tags: [a, 2]" :tags]
                              ["an empty ddc" "ddc:" :ddc]]]
    (testing (str label " is left out with an :invalid-front-matter naming the field")
      (let [{:keys [entry diagnostics document]}
            (one-book "meta" (str "---\ntitle: Meta\n" yaml "\n---\n\nBody.\n"))
            [d & more] diagnostics]
        (is (not (contains? entry field)))
        (is (= (fm-title "Meta") (:title entry)) "the rest of the entry is kept")
        (is (nil? more))
        (is (= :invalid-front-matter (:type d)))
        (is (= field (:field d)))
        (is (library/diagnostic? d))
        (is (str/includes? (:message d) (name field)))
        (is (ast/valid? ast/Document @document) "the document itself still loads"))))
  (testing "the ddc message says to quote it"
    (is (str/includes? (:message (first (:diagnostics (one-book "q" "---\nddc: 230.10\n---\n"))))
                       "quoted"))))

(defn- counting-reader
  "A `:open-reader` that counts every character listing reads into `n`,
  over an unbuffered reader so the count is what was consumed."
  [n]
  (fn [file]
    (let [inner (java.io.InputStreamReader. (java.io.FileInputStream. ^File file) "UTF-8")]
      (proxy [java.io.Reader] []
        (read
          ([] (let [c (.read inner)] (when-not (= -1 c) (swap! n inc)) c))
          ([^chars cbuf off len]
           (let [k (.read inner cbuf (int off) (int len))] (when (pos? k) (swap! n + k)) k)))
        (close [] (.close inner))))))

(deftest listing-reads-nothing-past-the-front-matter-test
  (let [front "---\ntitle: Long\n---\n"
        n (atom 0)
        {:keys [entry]} (one-book "long" (str front "\n" (apply str (repeat 100000 "body\n")))
                                  {:open-reader (counting-reader n)})]
    (is (= (fm-title "Long") (:title entry)))
    (is (= (count front) @n) "exactly the front matter and its fences, not one body character"))
  (testing "an opening fence never closed is read only up to the cap, and reported"
    (let [n (atom 0)
          {:keys [entry diagnostics]}
          (one-book "unclosed" (str "---\n" (apply str (repeat 100000 "key: value\n")))
                    {:open-reader (counting-reader n)})]
      (is (< @n 70000) (str "read " @n " characters"))
      (is (= (fm-title "unclosed") (:title entry)))
      (is (= [:invalid-front-matter] (map :type diagnostics)))
      (is (nil? (:field (first diagnostics))))
      (is (str/includes? (:message (first diagnostics)) "not closed"))))
  (testing "a read error is an :invalid-front-matter, not 'no front matter'"
    (let [{:keys [entry diagnostics]}
          (one-book "io" "---\ntitle: Io\n---\n"
                    {:open-reader (fn [_file] (proxy [java.io.Reader] []
                                                (read ([] (throw (java.io.IOException. "disk on fire")))
                                                  ([_ _ _] (throw (java.io.IOException. "disk on fire"))))
                                                (close [])))})]
      (is (= (fm-title "io") (:title entry)))
      (is (= [:invalid-front-matter] (map :type diagnostics)))
      (is (str/includes? (:message (first diagnostics)) "disk on fire")))))

(deftest non-map-front-matter-test
  (testing "front matter that is not a YAML map is an :invalid-front-matter naming file and line (TASK-178)"
    (doseq [[slug text] [["string" "---\nMy Title\n---\n\nBody.\n"]
                         ["list" "---\n- a\n---\n"]
                         ["number" "---\n42\n---\n"]]]
      (let [{:keys [entry diagnostics document]} (one-book slug text)
            [d] diagnostics]
        (is (= (fm-title slug) (:title entry)) slug)
        (is (= [:invalid-front-matter] (map :type diagnostics)) slug)
        (is (nil? (:field d)) slug)
        (is (str/includes? (:message d) (str slug ".hdoc:2: front matter must be a YAML map")) (:message d))
        (is (not (str/includes? (:message d) "contains?")) (:message d))
        (is (= :book-failed (:type @document)) slug))))
  (testing "empty and comment-only front matter still list cleanly"
    (doseq [[slug text] [["empty" "---\n---\n\nBody.\n"] ["comment" "---\n# nothing\n---\n"]]]
      (let [{:keys [entry diagnostics]} (one-book slug text)]
        (is (= (fm-title slug) (:title entry)) slug)
        (is (= [] diagnostics) slug)))))

(deftest non-path-bibliography-test
  (testing "a bibliography that is not a path is a load warning, not a failed listing or document (TASK-209)"
    (doseq [[slug yaml shown] [["list" "[a.json, b.json]" ["a.json" "b.json"]]
                               ["number" "42" 42]
                               ["mapping" "\n  file: a.json" {:file "a.json"}]]]
      (doseq [kinds [nil #{"sec"}]]
        (let [dir (temp-dir)
              _ (write! dir (str slug ".hdoc")
                        (str "---\ntitle: Listed\nbibliography: " yaml "\n---\n\n# A {#sec:a}\n\nx\n"))
              lib (fs/filesystem-library {:name "l" :path (str dir)} (if kinds {:kinds kinds} {}))
              book (library/find-book lib slug)
              document (library/document book)
              [w & more] (fs/load-warnings book)]
          (is (= (fm-title "Listed") (:title (first (library/list-books lib)))) slug)
          (is (= [] (library/library-diagnostics lib)) slug)
          (is (= {:title (fm-title "Listed")} (:meta document)) slug)
          (is (= [:section] (mapv :t (:blocks document))) slug)
          (is (nil? more) slug)
          (is (= :unreadable-bibliography (:type w)) slug)
          (is (= shown (:bibliography w)) slug)
          (is (re-find #"must be a path" (:message w)) slug)
          (is (= {} (fs/bibliography book)) slug))))))

(deftest unreadable-shelf-test
  (let [dir (temp-dir)
        _ (write! dir "ok/one.hdoc" "---\ntitle: One\n---\n")
        _ (write! dir "locked/two.hdoc" "---\ntitle: Two\n---\n")
        locked (io/file dir "locked")]
    (.setReadable locked false)
    (try
      (if (some? (.listFiles locked))
        (is true "running as a user who can list anything; the branch is not reachable here")
        (let [lib (fs/filesystem-library {:name "l" :path (str dir)})
              [d & more] (library/library-diagnostics lib)]
          (is (= ["l:ok/one"] (map (comp book-ref/format-book-id :id) (library/list-books lib))))
          (is (nil? more))
          (is (= :unreadable-shelf (:type d)))
          (is (= (str locked) (:path d)))
          (is (library/diagnostic? d))))
      (finally (.setReadable locked true)))))

(deftest symlink-loop-test
  (let [dir (temp-dir)
        _ (write! dir "s/one.hdoc" "---\ntitle: One\n---\n")
        link (io/file dir "s" "loop")]
    (when (try (Files/createSymbolicLink (.toPath link) (.toPath (io/file dir "s"))
                                         (make-array FileAttribute 0))
               true
               (catch Exception _ false))
      (testing "a folder symlinked back to its parent is walked once"
        (let [lib (fs/filesystem-library {:name "l" :path (str dir)})]
          (is (= ["l:s/one"] (map (comp book-ref/format-book-id :id) (library/list-books lib)))))))))

;; --- config ------------------------------------------------------------------

(deftest config-test
  (testing "a bad config is an :invalid-library diagnostic, not a throw"
    (doseq [[label config fragment]
            [["not a map" 42 "must be a map"]
             ["a bad name" {:name "Bad" :path (fixture-path "reformed")} "must match"]
             ["no path" {:name "a"} "needs a path"]
             ["a non-string path" {:name "a" :path 3} "non-empty string"]
             ["a missing folder" {:name "a" :path (str (io/file (temp-dir) "nope"))} "no such folder"]
             ["a file, not a folder"
              {:name "a" :path (str (io/file (fixture-path "reformed") "notes.hdoc"))}
              "no such folder"]]]
      (let [result (fs/filesystem-library config)]
        (is (= :invalid-library (:type result)) label)
        (is (library/diagnostic? result) label)
        (is (str/includes? (str (:message result)) fragment) label))))
  (testing "the library root is absolute"
    (is (.isAbsolute (io/file (fs/library-root (fs/filesystem-library
                                                {:name "a" :path "test/fixtures/libraries/shared"})))))))

(deftest directory-registry-test
  (testing ":filesystem is registered by default"
    (is (= fs/filesystem-library (:filesystem directory/default-registry))))
  (testing "a YAML entry with a relative path builds a filesystem library"
    (let [dir (temp-dir)
          _ (write! dir "lib/sh/book-one.hdoc" "---\ntitle: One\nlang: en\n---\n\nText.\n")
          file (write! dir "libraries.yaml" "- name: mine\n  type: filesystem\n  path: lib\n")
          libdir (directory/load-library-directory {:path (str file) :env {} :home "/h"})
          lib (library/find-library libdir "mine")]
      (is (= ["mine"] (library/library-names libdir)))
      (is (= (str (io/file dir "lib")) (fs/library-root lib)))
      (is (= ["mine:sh/book-one"] (map (comp book-ref/format-book-id :id) (library/list-books lib))))))
  (testing "a filesystem entry with a missing folder is an :invalid-library diagnostic"
    (let [dir (temp-dir)
          file (write! dir "libraries.yaml" "- name: mine\n  type: filesystem\n  path: nope\n")
          libdir (directory/load-library-directory {:path (str file) :env {} :home "/h"})
          [result & more] (library/library-diagnostics libdir)]
      (is (nil? more))
      (is (= :invalid-library (:type result)))
      (is (= :invalid-library (:type (:cause result))))
      (is (= [] (library/library-names libdir)))
      (is (= :unavailable-library (:type (library/find-library libdir "mine")))))))
