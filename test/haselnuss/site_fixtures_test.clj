(ns haselnuss.site-fixtures-test
  "The site fixture set (TASK-264; doc-12, \"Left to the builder\"):
  `test/fixtures/site/`, the small library directory the library-server
  tasks build against. These tests pin what it is, so a later task can
  rely on it: `--check` reports exactly the two problems it plants (a
  dangling reference and a duplicate canonical id); the catechism copy
  quotes and links the generated Bible shelf across libraries; the image
  book's image sits beside it. Its README says what each book is for."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.cli :as cli])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:private site-yaml "test/fixtures/site/libraries.yaml")

(def ^:private ^File site-dir (io/file "test/fixtures/site"))

(def ^:private cwd (System/getProperty "user.dir"))

(def ^:private no-env
  "No real `$HASELNUSS_LIBRARIES` or home config."
  {:library-env {} :library-home "/nonexistent/haselnuss-test-home"})

(defn- main-run
  [& args]
  (let [err (java.io.StringWriter.)
        out (java.io.StringWriter.)
        status (binding [*err* err *out* out]
                 (cli/main* args (assoc no-env :haselnuss.cli/check-base-dir cwd)))]
    {:status status :out (str out) :err (str err)}))

(defn- temp-dir ^File []
  (.toFile (Files/createTempDirectory "haselnuss-site-fixtures" (make-array FileAttribute 0))))

(defn- build-html
  "`haselnuss.cli/build` of the fixture book at `rel` (under the fixture
  folder) to HTML. Returns `{:html :diagnostics}`."
  [rel]
  (let [out (io/file (temp-dir) "book.html")
        result (cli/build (merge no-env {:libraries site-yaml :input (str (io/file site-dir rel))
                                         :target "html" :output (str out)}))]
    {:html (slurp out) :diagnostics (:diagnostics result)}))

;; --- AC #2: --check reports exactly the planted problems -----------------------

(deftest check-reports-exactly-the-planted-warnings-test
  (let [{:keys [status out err]} (main-run "--libraries" site-yaml "--check")
        site (.getCanonicalPath site-dir)]
    (is (= (str "test/fixtures/site/reformed/dangling.hdoc:9:1: warning: [unknown-book] bible:tobias#1:1: "
                "library \"bible\" has no book \"tobias\"\n"
                "test/fixtures/site/reformed/notas: warning: [duplicate-slug] library \"reformed\" has more "
                "than one book with slug \"notas\": reformed:notas (" site "/reformed/notas), reformed:notas ("
                site "/reformed/notas.hdoc)\n"
                ;; 11 books are checked; the two notas books count once (TASK-285).
                "2 problems (0 errors, 2 warnings, 0 info) in 10 books\n")
           out))
    (is (= 1 status) "warnings found")
    (is (= "" err))))

;; --- AC #1: what each fixture covers -------------------------------------------

(def ^:private blivre
  "The generated shelf's shared :data file, read here independently of
  shelf.clj: slug -> {[chapter verse] text}."
  (delay (into {}
               (map (fn [{:keys [slug verses]}]
                      [slug (into {} (map (fn [[c v text]] [[c v] text])) verses)]))
               (json/read-str (slurp (io/file site-dir "bible/blivre/blivre.json")) :key-fn keyword))))

(defn- verse-text
  "What a reader sees for `slug`'s `C:V` or `C:V-W`: the verse, or each
  verse after its number."
  [slug locator]
  (let [[c from to] (map #(some-> % parse-long) (rest (re-matches #"(\d+):(\d+)(?:-(\d+))?" locator)))
        to (or to from)
        verses (for [v (range from (inc to))] [v (get-in @blivre [slug [c v]])])]
    (assert (every? second verses) (str slug "#" locator " is not in blivre.json"))
    (if (= 1 (count verses))
      (second (first verses))
      (str/join " " (mapcat (fn [[v text]] [(str v) text]) verses)))))

(defn- text-of [html]
  (-> html (str/replace #"<[^>]*>" " ") (str/replace "&quot;" "\"") (str/replace #"\s+" " ") str/trim))

(deftest generated-shelf-with-shared-data-test
  (testing "the shelf script is a generated shelf over one shared :data file"
    (is (.isFile (io/file site-dir "bible/blivre/shelf.clj")))
    (is (str/includes? (slurp (io/file site-dir "bible/blivre/shelf.clj")) "{:data [\"blivre.json\"]")))
  (testing "it runs (scripts: true) and makes one book per data entry"
    (let [{:keys [status out]} (main-run "--libraries" site-yaml "--backlinks" "bible:blivre/mateus")]
      (is (= 0 status) out)
      (is (str/starts-with? out "bible:blivre/mateus\n") out))
    (is (= #{"genesis" "mateus" "joao" "romanos" "1-corintios"} (set (keys @blivre))))))

(deftest catechism-quotes-and-links-across-libraries-test
  (let [source (slurp (io/file site-dir "reformed/catecismo-heidelberg.hdoc"))
        quotes (mapv rest (re-seq #"(?m)^!quote bible:([^#\s]+)#(\S+)$" source))
        links (re-seq #"@\{bible:" source)
        {:keys [html diagnostics]} (build-html "reformed/catecismo-heidelberg.hdoc")
        figures (re-seq #"(?s)<blockquote class=\"book-quote\" data-source=\"bible:blivre/([^#\"]+)#([^\"]+)\">(.*?)</blockquote>"
                        html)]
    (is (= [] diagnostics))
    (testing "every !quote shows the verse the shared data file holds, from the generated shelf"
      (is (= 8 (count quotes)))
      (is (= quotes (map (fn [[_ slug locator]] [slug locator]) figures)))
      (doseq [[_ slug locator inner] figures]
        (is (= (verse-text slug locator) (text-of inner)) (str slug "#" locator))))
    (testing "the @{...} links resolve, each to a book-ref naming the passage"
      (is (= 9 (count links)))
      (is (= 9 (count (re-seq #"<span class=\"book-ref\">" html))))
      (is (str/includes? html "<span class=\"book-ref\">Romanos 8:28</span>")))
    (testing "the reference graph records both kinds of edge into the Bible library"
      (let [{:strs [edges]} (json/read-str (:out (main-run "--libraries" site-yaml "--backlinks" "bible:romanos"
                                                           "--format" "json")))]
        (is (= #{"link" "quote"} (set (map #(get % "kind") edges))))
        (is (every? #(= "reformed:catecismo-heidelberg" (get-in % ["from" "id"])) edges))
        (is (every? #(= "resolved" (get % "status")) edges))))))

(defn- backlinks-json [book]
  (get (json/read-str (:out (main-run "--libraries" site-yaml "--backlinks" book "--format" "json"))) "edges"))

(deftest shelved-book-links-only-and-quotes-a-quote-test
  (let [{:keys [html diagnostics]} (build-html "reformed/estudos/queda.hdoc")
        edge (fn [e] [(get-in e ["from" "id"]) (get-in e ["to" "passage"]) (get e "kind") (get e "status")])]
    (is (= [] diagnostics))
    (testing "a book on a shelf below the library root"
      (is (.isFile (io/file site-dir "reformed/estudos/queda.hdoc"))))
    (testing "link-only edges into a Bible book nothing quotes"
      (is (= [["reformed:estudos/queda" "3:1-24" "link" "resolved"]
              ["reformed:estudos/queda" "3:15" "link" "resolved"]]
             (map edge (backlinks-json "bible:genesis")))))
    (testing "the linked chapter is one passage of more than 2,000 characters"
      (is (< 2000 (count (verse-text "genesis" "3:1-24")))))
    (testing "a quote of a quote: it quotes the catechism's question 1, whose own Bible quotes come along"
      (is (= [["reformed:estudos/queda" "sec:q1" "quote" "resolved"]]
             (map edge (backlinks-json "reformed:catecismo-heidelberg"))))
      (is (str/includes? html "data-source=\"reformed:catecismo-heidelberg#sec:q1\""))
      (is (str/includes? html "data-source=\"bible:blivre/romanos#14:8\""))
      (is (str/includes? html (verse-text "romanos" "14:8"))))))

(deftest image-and-maths-book-test
  (let [{:keys [html diagnostics]} (build-html "reformed/catkins/book.hdoc")]
    (is (= [] diagnostics))
    (is (str/includes? html "<img src=\"catkins.png\""))
    (is (.isFile (io/file site-dir "reformed/catkins/catkins.png")) "the image sits beside the book")
    (testing "inline and display maths"
      (is (str/includes? html "<span class=\"math inline\">\\(m\\)</span>"))
      (is (str/includes? html "\\frac{f}{m + f}")))))

(deftest duplicate-canonical-id-test
  (testing "x.hdoc beside x/book.hdoc"
    (is (.isFile (io/file site-dir "reformed/notas.hdoc")))
    (is (.isFile (io/file site-dir "reformed/notas/book.hdoc")))))
