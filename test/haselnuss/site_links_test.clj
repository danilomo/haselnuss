(ns haselnuss.site-links-test
  "Links between books in a `--build-site` site (TASK-269; doc-12, \"Output
  folder\"): every book link and quote source is a path relative to the
  citing page, whatever `url:` template the directory has, and a locator
  lands on the anchor its book's `locate` answers (`:anchor`) when the
  book's page has it, else on the top of the page. The plain `html` build
  is not touched: its equivalence was checked over the corpus, see the
  task notes."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.book-ref :as book-ref]
            [haselnuss.cli :as cli]
            [haselnuss.library :as library]
            [haselnuss.library.memory :as memory]
            [haselnuss.library.passage :as passage]
            [haselnuss.library.references :as references]
            [haselnuss.site :as site])
  (:import (java.io File)
           (java.net URI)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

;; --- site/book-url -----------------------------------------------------------------

(defn- id [text] (:id (book-ref/parse-book-ref text)))

(defn- passage-of [text] (:passage (book-ref/parse-book-ref text)))

(deftest book-url-test
  (let [catechism (id "reformed:catecismo-heidelberg")
        queda (id "reformed:estudos/queda")
        romanos (id "bible:blivre/romanos")]
    (testing "a locator lands on its anchor, from books at different shelf depths"
      (is (= "../../bible/blivre/romanos/index.html#v14-8"
             (site/book-url catechism romanos (passage-of "bible:romanos#14:8") "v14-8")))
      (is (= "../../../bible/blivre/romanos/index.html#v14-8"
             (site/book-url queda romanos (passage-of "bible:romanos#14:8") "v14-8"))))
    (testing "a locator with no anchor, and a whole book, land on the top of the page"
      (is (= "../../bible/blivre/romanos/index.html"
             (site/book-url catechism romanos (passage-of "bible:romanos#14:8") nil)))
      (is (= "../../bible/blivre/romanos/index.html" (site/book-url catechism romanos nil nil))))
    (testing "a label lands on its id, a range on its first label; an anchor is a locator's only"
      (is (= "../../catecismo-heidelberg/index.html#sec:q1"
             (site/book-url queda catechism (passage-of "reformed:catecismo-heidelberg#sec:q1") nil)))
      (is (= "../../catecismo-heidelberg/index.html#sec:q1"
             (site/book-url queda catechism
                            (passage-of "reformed:catecismo-heidelberg#sec:q1..sec:q2") "ignored"))))
    (testing "down a shelf, and a book's link to itself"
      (is (= "../estudos/queda/index.html" (site/book-url catechism queda nil nil)))
      (is (= "index.html#sec:q2"
             (site/book-url catechism catechism (passage-of "reformed:catecismo-heidelberg#sec:q2") nil))))
    (testing "an id is percent-encoded as a URL template's values are, keeping `:`"
      (is (= "../../bible/blivre/romanos/index.html#a%20b%22c:d"
             (site/book-url catechism romanos (passage-of "bible:romanos#1") "a b\"c:d"))))
    (testing "no URL for a book with no site path"
      (is (nil? (site/book-url catechism {:library "bible" :shelf [] :slug "Bad"} nil nil))))))

;; --- a locator's :anchor ---------------------------------------------------------

(def ^:private verses-document
  {:meta {}
   :blocks [{:t :para
             :inlines [{:t :span :attr {:id "v1-1" :classes [] :props {}}
                        :inlines [{:t :str :text "1"}]}
                       {:t :str :text " No princípio."}]}]})

(defn- verses-book
  "A Locatable book whose page has an anchor for `1:1` only: `locate`
  answers `v1-1` for it, `v1-2` (an anchor the page lacks) for `1:2`, no
  anchor for `1:3`, and `anchor` itself for `bad`."
  [anchor]
  {:id (book-ref/book-id "bib" "gen")
   :entry {:title "Gen"}
   :document (fn [] verses-document)
   :locate (fn [locator]
             (cond-> {:title locator :blocks [{:t :para :inlines [{:t :str :text locator}]}]}
               (= "1:1" locator) (assoc :anchor "v1-1")
               (= "1:2" locator) (assoc :anchor "v1-2")
               (= "bad" locator) (assoc :anchor anchor)))})

(defn- locate* [locator]
  (passage/resolve-passage (library/virtual-book (verses-book 5))
                           (passage-of (str "bib:gen#" locator))
                           (passage/context)))

(deftest locator-anchor-test
  (testing "an anchor the book's document has is kept"
    (is (= "v1-1" (:anchor (locate* "1:1")))))
  (testing "one it lacks is dropped, so a link lands on the top of the page"
    (let [p (locate* "1:2")]
      (is (contains? p :blocks))
      (is (not (contains? p :anchor)))))
  (testing "no anchor, none"
    (is (not (contains? (locate* "1:3") :anchor))))
  (testing "an anchor that is not a non-empty string makes the passage invalid"
    (is (= :invalid-book-document (:type (locate* "bad"))))
    (is (re-find #":anchor must be a non-empty string" (:message (locate* "bad"))))))

(deftest link-url-hook-test
  (let [dir (library/library-directory [(memory/memory-library {:name "bib" :books [(verses-book "")]})])
        from (id "notes:here")
        site-books (references/books-option
                    {:directory dir
                     :link-url (fn [to p anchor] (site/book-url from to p anchor))})
        plain-books (references/books-option {:directory dir})
        url-of (fn [books written]
                 (:url (:passage ((:resolve books) written (book-ref/parse-book-ref written)))))]
    (testing "a site page's :link-url makes every URL, with the checked anchor"
      (is (= "../../bib/gen/index.html#v1-1" (url-of site-books "bib:gen#1:1")))
      (is (= "../../bib/gen/index.html" (url-of site-books "bib:gen#1:2")))
      (is (= "../../bib/gen/index.html" (url-of site-books "bib:gen")))
      (is (= "../../bib/gen/index.html" (:url ((:url site-books) {:id (id "bib:gen")})))))
    (testing "without it, there is no URL with no template, as before"
      (is (nil? (url-of plain-books "bib:gen#1:1")))
      (is (nil? (:url ((:url plain-books) {:id (id "bib:gen")})))))))

;; --- the built fixture site ------------------------------------------------------

(def ^:private ^File fixture-dir (io/file "test/fixtures/site"))

(defn- temp-dir ^File []
  (.toFile (Files/createTempDirectory "haselnuss-site-links" (make-array FileAttribute 0))))

(defn- templated-yaml
  "The fixture set's directory file, with a `url:` template on both
  libraries, written to `dir`: a site build must ignore them."
  ^File [^File dir]
  (let [root (.getCanonicalPath fixture-dir)
        f (io/file dir "libraries.yaml")]
    (spit f (str "- name: bible\n  type: filesystem\n  path: " root "/bible\n  scripts: true\n"
                 "  url: \"https://example.org/{slug}.html#{label}{locator}\"\n"
                 "- name: reformed\n  type: filesystem\n  path: " root "/reformed\n"
                 "  url: \"/reformed/{shelf}/{slug}#{label}\"\n"))
    f))

(defn- build!
  "Build the fixture set, with `yaml` as its directory file, into `dir`."
  [yaml dir]
  (let [err (java.io.StringWriter.)
        status (binding [*err* err *out* (java.io.StringWriter.)]
                 (cli/main* ["--libraries" (str yaml) "--build-site" (str dir)]
                            {:library-env {} :library-home "/nonexistent/haselnuss-test-home"}))]
    {:status status :err (str err)}))

(defn- unescape
  "Attribute text as the browser reads it: the five entities
  `haselnuss.emit.html` writes."
  [s]
  (-> s
      (str/replace "&quot;" "\"") (str/replace "&#39;" "'")
      (str/replace "&lt;" "<") (str/replace "&gt;" ">") (str/replace "&amp;" "&")))

(defn- attr-values [html attr]
  (map (comp unescape second) (re-seq (re-pattern (str "\\s" attr "=\"([^\"]*)\"")) html)))

(defn- pages
  "`{relative-path html}` of every book page under the site `dir`."
  [^File dir]
  (into (sorted-map)
        (for [^File f (file-seq (io/file dir "books"))
              :when (= "index.html" (.getName f))]
          [(str/replace (str (.relativize (.toPath dir) (.toPath f))) File/separator "/") (slurp f)])))

(defn- book-links
  "Every inter-book link of the site `dir`'s pages: `{:page :href :target
  :fragment}`, one per `href` or `cite` of an `<a class=\"book-ref\">`, a
  quote's `<blockquote cite>` and its source line's link -- every `href`
  and `cite` but the reader app's own and in-page `#` links. `:target` is
  the file the href names, resolved against the page, relative to `dir`."
  [^File dir]
  (let [root (.toURI dir)]
    (for [[page html] (pages dir)
          href (concat (attr-values html "href") (attr-values html "cite"))
          :when (not (re-find #"(^|/)app\.(css|js)$" href))
          :when (not (str/starts-with? href "#"))
          :let [uri (URI. href)
                resolved (.resolve (.resolve root (URI. nil nil page nil)) uri)]]
      {:page page
       :href href
       :absolute? (.isAbsolute uri)
       :target (str (.relativize root resolved))
       :path (.getPath resolved)
       :fragment (.getFragment uri)})))

(defn- ids
  "The element ids of the page at `path`."
  [path]
  (set (attr-values (slurp path) "id")))

(deftest fixture-site-links-test
  (let [work (temp-dir)
        dir (io/file work "site")
        {:keys [status err]} (build! (templated-yaml work) dir)
        links (vec (book-links dir))
        by-page (group-by :page links)]
    ;; the fixture set's two planted warnings
    (is (= 1 status) err)
    (testing "AC #1: every inter-book link and quote source names an existing page and anchor"
      (is (<= 30 (count links)) "not vacuous: the catechism and queda alone hold dozens")
      (doseq [{:keys [page href absolute? target path fragment]} links]
        (is (not absolute?) (str page ": " href " is not a relative site path (a url: template?)"))
        (is (str/starts-with? target "books/") (str page ": " href " leaves books/"))
        (is (.isFile (io/file path)) (str page ": " href " names no file"))
        (when (and fragment (.isFile (io/file path)))
          (is (contains? (ids path) fragment) (str page ": " href " names no element with that id"))))
      (testing "-- and every one carries an anchor, the fixture's locators all having one"
        (is (every? :fragment links))))
    (testing "AC #2: links work from books at different shelf depths"
      (let [catechism (get by-page "books/reformed/catecismo-heidelberg/index.html")
            queda (get by-page "books/reformed/estudos/queda/index.html")]
        (is (seq catechism))
        (is (seq queda))
        (is (some #(= "../../bible/blivre/romanos/index.html#v14-8" (:href %)) catechism))
        (is (some #(= "../../../bible/blivre/romanos/index.html#v14-8" (:href %)) queda)
            "queda quotes the catechism's question 1, whose links follow queda's depth")
        (is (some #(= "../../../bible/blivre/genesis/index.html#v3-15" (:href %)) queda))
        (is (some #(= "../../catecismo-heidelberg/index.html#sec:q1" (:href %)) queda)
            "the quote source")))
    (testing "AC #3: romanos#14:8 lands on the verse"
      (let [romanos (slurp (io/file dir "books/bible/blivre/romanos/index.html"))]
        (is (re-find #"<span id=\"v14-8\" class=\"verse\"><strong>8</strong></span> Porque" romanos))
        (is (< (str/index-of romanos "id=\"sec:cap-14\"") (str/index-of romanos "id=\"v14-8\"")))))
    (testing "a book that is not built is not linked: the dangling reference stays text"
      (is (empty? (get by-page "books/reformed/dangling/index.html"))))))
