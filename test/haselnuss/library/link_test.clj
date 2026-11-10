(ns haselnuss.library.link-test
  "Book links (TASK-155, doc-10 \"Resolver\" step 2): every `@{…}`
  resolved by `haselnuss.resolver/resolve-document` through the `:books`
  option `haselnuss.library.references/books-option` builds -- its
  passage's title as link text, its canonical reference, and a URL when
  its library entry has a template -- over `:memory` books written as
  `.hdoc` source, and over the filesystem fixture library
  `test/fixtures/libraries/passages/` reached through a library directory
  file."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.ast :as ast]
            [haselnuss.book-ref :as book-ref]
            [haselnuss.emit.html :as html]
            [haselnuss.emit.latex :as latex]
            [haselnuss.library :as library]
            [haselnuss.library.directory :as directory]
            [haselnuss.library.filesystem :as fs]
            [haselnuss.library.memory :as memory]
            [haselnuss.library.references :as references]
            [haselnuss.parser :as parser]
            [haselnuss.resolver :as resolver]
            [haselnuss.sanitize :as sanitize])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

;; --- books -------------------------------------------------------------------

(def ^:private catechism-source
  "---
title: Heidelberg Catechism
lang: en
---

# Comfort {#sec:comfort}

## Lord's Day 1 {#sec:q1}

What is your only comfort?

## Lord's Day 2 {#sec:q2}

How many things? See @sec:q1.

## Lord's Day 3 {#sec:q3}

Whence knowest thou thy misery? See @sec:none.

# Misery {#sec:misery}

Before the questions.
")

(def ^:private romans
  "A Locatable virtual book: `14:8` is a verse, a locator with characters
  a URL must encode is accepted too, `cross` is a passage holding a
  cross-reference the book never resolved, anything else is rejected.
  Its `locate` answers a `:url` of its own, which a link never uses."
  {:id (book-ref/book-id "bible" ["nt"] "romanos")
   :entry {:title "Romanos" :lang "pt-BR"}
   :document (fn [] (parser/parse "---\ntitle: Romanos\n---\n\nTexto.\n"))
   :locate (fn [locator]
             (case locator
               "14:8" {:title "Romanos 14:8"
                       :url "https://book.example/own-url"
                       :blocks [{:t :para :inlines [{:t :str :text "Porque, se vivemos."}]}]}
               "14:8&é" {:title "Romanos 14:8&é" :blocks []}
               "cross" {:title "Cross"
                        :blocks [{:t :para :inlines [{:t :cross-ref :label "sec:q1"}]}]}
               (throw (ex-info (str "no verse " locator) {}))))})

(defn- book
  ([lib slug source] (book lib [] slug source))
  ([lib shelf slug source]
   {:id (book-ref/book-id lib shelf slug)
    :entry {:title (str/capitalize slug)}
    :document (fn [] (parser/parse source))}))

(def ^:private templates
  {"reformed" "https://example.org/{library}/{shelf}/{slug}.html#{label}"
   "bible" "https://bible.example/{slug}?v={locator}"})

(defn- directory
  ([] (directory {}))
  ([{:keys [failures url-templates] :or {url-templates templates}}]
   (library/library-directory
    [(memory/memory-library
      {:name "reformed"
       :books [{:id (book-ref/book-id "reformed" ["confessions"] "heidelberg")
                :entry {:title "Heidelberg Catechism" :lang "en"}
                :document (fn [] (parser/parse catechism-source))}
               (book "reformed" ["a"] "twin" "# A {#sec:a}\n\nA.\n")
               (book "reformed" ["b"] "twin" "# B {#sec:b}\n\nB.\n")]})
     (memory/memory-library {:name "bible" :books [romans]})
     (memory/memory-library {:name "plain" :books [(book "plain" "p" "# P {#sec:p}\n\nP.\n")]})]
    (or failures [])
    {:url-templates url-templates})))

(defn- resolve*
  "`resolve-document` of `source` (parsed as the file `host.hdoc`) with the
  `:books` option over `dir` (default `(directory)`)."
  ([source] (resolve* source {}))
  ([source {:keys [dir resolve-opts]}]
   (resolver/resolve-document
    (parser/parse source {:source "host.hdoc"})
    (assoc resolve-opts :books (references/books-option {:directory (or dir (directory))})))))

(defn- nodes-of
  [t x]
  (filterv #(and (map? %) (= t (:t %))) (tree-seq coll? seq x)))

(defn- links [x] (nodes-of :book-ref x))

(defn- text-of
  [inlines]
  (apply str (map #(case (:t %) :str (:text %) :space " " "") inlines)))

(defn- by-type [diagnostics] (group-by :type diagnostics))

;; --- AC #1: title as text, canonical ref, URL --------------------------------

(deftest resolves-to-title-canonical-and-url-test
  (let [{:keys [document diagnostics]}
        (resolve* (str "See @{reformed:confessions/heidelberg#sec:q1}, "
                       "@{reformed:heidelberg#sec:q1..sec:q3}, @{bible:romanos#14:8} "
                       "and @{reformed:heidelberg}.\n"))
        [label range-link locator whole] (links document)]
    (is (= [] diagnostics))
    (testing "a label: the passage's title, its canonical ref, the parsed passage, a URL"
      (is (= {:t :book-ref
              :ref "reformed:confessions/heidelberg#sec:q1"
              :passage {:form :label :label "sec:q1"}
              :canonical "reformed:confessions/heidelberg#sec:q1"
              :text [{:t :str :text "Heidelberg Catechism"} {:t :str :text ", Section 1.1"}]
              :url "https://example.org/reformed/confessions/heidelberg.html#sec:q1"}
             label)))
    (testing "a range, written in the short form, resolves to the canonical one"
      (is (= "reformed:heidelberg#sec:q1..sec:q3" (:ref range-link)) "the ref stays as written")
      (is (= "reformed:confessions/heidelberg#sec:q1..sec:q3" (:canonical range-link)))
      (is (= {:form :range :from "sec:q1" :to "sec:q3"} (:passage range-link)))
      (is (= "Heidelberg Catechism, Section 1.1–1.3" (text-of (:text range-link))))
      (is (= "https://example.org/reformed/confessions/heidelberg.html#sec:q1" (:url range-link))))
    (testing "a locator takes the title the book's locate answers"
      (is (= "bible:nt/romanos#14:8" (:canonical locator)))
      (is (= "Romanos 14:8" (text-of (:text locator))))
      (is (= "https://bible.example/romanos?v=14:8" (:url locator))
          "the template's URL, not the one the book's locate offered"))
    (testing "the whole book: its title, no passage, and no empty fragment"
      (is (= "reformed:confessions/heidelberg" (:canonical whole)))
      (is (not (contains? whole :passage)))
      (is (= "Heidelberg Catechism" (text-of (:text whole))))
      (is (= "https://example.org/reformed/confessions/heidelberg.html" (:url whole))))
    (testing "the result is still a valid document"
      (is (ast/valid? ast/Document document))))
  (testing "a link anywhere an inline can sit: a heading, a footnote, a table cell"
    (let [{:keys [document diagnostics]}
          (resolve* (str "# About @{plain:p}\n\n"
                         "Text.[^n]\n\n[^n]: See @{plain:p#sec:p}.\n\n"
                         "| a |\n|---|\n| @{plain:p} |\n"))]
      (is (= [] diagnostics))
      (is (= 3 (count (links document))))
      (is (every? :canonical (links document)))))
  (testing "the host's own cross-references are untouched"
    (let [{:keys [document]} (resolve* "# H {#sec:h}\n\nSee @sec:h and @{plain:p}.\n")]
      (is (= ["Section 1"] (mapv :text (nodes-of :cross-ref document))))))
  (testing "a book's quoted-in @{…} resolves too, from the host"
    (let [dir (library/library-directory
               [(memory/memory-library
                 {:name "c" :books [(book "c" "a" "# A {#sec:a}\n\nSee @{c:b#sec:b}.\n")
                                    (book "c" "b" "# B {#sec:b}\n\nB.\n")]})])
          {:keys [document diagnostics]} (resolve* "!quote c:a#sec:a\n" {:dir dir})]
      (is (= [] diagnostics))
      (is (= [["c:b#sec:b" "B, Section 1"]]
             (mapv (juxt :canonical (comp text-of :text)) (links document)))))))

;; --- AC #2: templates --------------------------------------------------------

(deftest url-templates-test
  (testing "every placeholder is filled from the book's CURRENT id, and encoded"
    (let [dir (directory {:url-templates
                          {"bible" "https://b.example/{library}/{shelf}/{slug}?l={locator}#{label}"
                           "reformed" "https://r.example/{library}/{shelf}/{slug}?l={locator}#{label}"}})
          {:keys [document diagnostics]}
          (resolve* "@{bible:old/romanos#14:8&é} @{reformed:heidelberg#sec:q2}\n" {:dir dir})
          [locator label] (links document)]
      (is (= [:stale-shelf] (mapv :type diagnostics)) "the written shelf is stale, not used")
      (is (= "https://b.example/bible/nt/romanos?l=14:8%26%C3%A9" (:url locator)))
      (is (= "https://r.example/reformed/confessions/heidelberg?l=#sec:q2" (:url label)))))
  (testing "an entry without a template yields no URL, whatever the book offers"
    (let [{:keys [document]} (resolve* "@{plain:p#sec:p} @{bible:romanos#14:8}\n"
                                       {:dir (directory {:url-templates {}})})]
      (is (= [nil nil] (mapv :url (links document))))
      (is (every? :canonical (links document)) "the link still resolves, as text only")))
  (testing "a directory that does not answer UrlTemplates has no templates"
    (let [plain-dir (reify library/LibraryDirectory
                      (library-names [_] ["plain"])
                      (find-library [_ n] (library/find-library (directory) n)))
          {:keys [document diagnostics]} (resolve* "@{plain:p}\n" {:dir plain-dir})]
      (is (= [] diagnostics))
      (is (= "plain:p" (:canonical (first (links document)))))
      (is (nil? (:url (first (links document)))))))
  (testing "a quote's :url comes from the same template"
    (let [{:keys [document]} (resolve* "!quote reformed:heidelberg#sec:q1\n\n!quote bible:romanos#14:8\n\n!quote plain:p\n")]
      (is (= ["https://example.org/reformed/confessions/heidelberg.html#sec:q1"
              "https://bible.example/romanos?v=14:8"
              nil]
             (mapv :url (nodes-of :book-quote document))))))
  (testing "a library mixing root and shelved books never yields //"
    (let [dir (library/library-directory
               [(memory/memory-library
                 {:name "m" :books [(book "m" "root" "# R {#sec:r}\n\nR.\n")
                                    (book "m" ["s" "t"] "deep" "# D {#sec:d}\n\nD.\n")]})]
               [] {:url-templates {"m" "/{shelf}/{slug}.html#{label}"}})
          {:keys [document diagnostics]} (resolve* "@{m:root#sec:r} @{m:deep#sec:d} @{m:root}\n"
                                                   {:dir dir})]
      (is (= [] diagnostics))
      (is (= ["/root.html#sec:r" "/s/t/deep.html#sec:d" "/root.html"]
             (mapv :url (links document))))))
  (testing "an invalid template from another UrlTemplates directory is no template, reported once"
    (let [base (directory)
          odd (reify
                library/LibraryDirectory
                (library-names [_] (library/library-names base))
                (find-library [_ n] (library/find-library base n))
                library/UrlTemplates
                (url-template [_ _] "https://e.org/{slgu}"))
          {:keys [document diagnostics]}
          (resolve* "@{plain:p} @{plain:p#sec:p}\n\n!quote plain:p\n" {:dir odd})]
      (is (= [nil nil] (mapv :url (links document))))
      (is (every? :canonical (links document)) "the links still resolve, as text")
      (is (nil? (:url (first (nodes-of :book-quote document)))))
      (is (= [{:type :invalid-url-template :level :error :library "plain"
               :url "https://e.org/{slgu}"}]
             (mapv #(dissoc % :message) diagnostics)))
      (is (str/includes? (:message (first diagnostics)) "unknown placeholder {slgu}"))))
  (testing "an invalid template is refused when the directory is built"
    (is (= :invalid-library-directory
           (:type (directory {:url-templates {"bible" "https://b.example/{verse}"}}))))))

;; --- AC #3: malformed and unresolvable refs read ?? -------------------------

(def ^:private failing-host
  "@{nolib:x}

@{reformed:nobook}

@{reformed:twin}

@{broken:x}

@{reformed:heidelberg#sec:none}

@{reformed:heidelberg#sec:q1..sec:misery}

@{plain:p#14:8}

@{bible:romanos#99:1}

@{Not-A-Ref}

@{reformed:heidelberg#sec:q1}
")

(deftest unresolvable-refs-read-placeholders-test
  (let [dir (directory {:failures [{:type :invalid-library :level :error :library "broken"
                                    :message "libraries.yaml:3: library \"broken\": bad path"}]})
        {:keys [document] [load-problem & diagnostics] :diagnostics}
        (resolve* failing-host {:dir dir})
        ls (links document)
        failed (butlast ls)]
    (testing "the directory's own load problem is reported once, ahead of the rest"
      (is (= :invalid-library (:type load-problem)))
      (is (not-any? #(= :invalid-library (:type %)) diagnostics)))
    (is (= [:unknown-library :unknown-book :ambiguous-book :unavailable-library :unknown-label
            :bad-label-range :locator-unsupported :bad-locator :malformed-book-ref]
           (mapv :type diagnostics)))
    (testing "each one reads ??, with no canonical ref and no URL"
      (is (every? #(= [{:t :str :text "??"}] (:text %)) failed))
      (is (every? #(nil? (:canonical %)) failed))
      (is (every? #(nil? (:url %)) failed)))
    (testing "the passage as written is still recorded when the ref parsed"
      (is (= {:form :label :label "sec:none"} (:passage (nth ls 4))))
      (is (not (contains? (nth ls 8) :passage)) "a malformed ref has none"))
    (testing "each diagnostic carries the reference as written and its file and line"
      (is (= (mapv :ref failed) (mapv :ref diagnostics)))
      (is (= [1 3 5 7 9 11 13 15 17] (mapv :source-line diagnostics)))
      (is (every? #(= "host.hdoc" (:source-file %)) diagnostics))
      (is (every? #(str/starts-with? (:message %) (str "host.hdoc:" (:source-line %) ": "))
                  diagnostics))
      (is (every? #(str/includes? (:message %) (:ref %)) diagnostics))
      (is (every? #(= :warning (:level %)) diagnostics)))
    (testing "the details survive"
      (let [{:keys [unavailable-library ambiguous-book unknown-label]} (by-type diagnostics)]
        (is (= :invalid-library (:type (:cause (first unavailable-library)))))
        (is (= ["reformed:a/twin" "reformed:b/twin"] (:books (first ambiguous-book))))
        (is (= "reformed:confessions/heidelberg" (:canonical (first unknown-label))))))
    (testing "the build went on: the last link resolved"
      (is (= "reformed:confessions/heidelberg#sec:q1" (:canonical (last ls)))))
    (testing "both targets print ?? for every failed link"
      (let [out (html/emit-document document {})]
        (is (= 9 (count (re-seq #"<span class=\"book-ref\">\?\?</span>" out)))))
      (let [out (latex/emit-document document {})]
        (is (= 9 (count (re-seq #"\?\?" out)))))))
  (testing "mirroring a dangling @fig:x: a ?? placeholder and one located warning"
    (let [{:keys [document diagnostics]} (resolve* "See @fig:x and @{plain:p#fig:x}.\n")
          [cross] (nodes-of :cross-ref document)
          [link] (links document)]
      (is (= "??" (:text cross)))
      (is (= "??" (text-of (:text link))))
      (is (= [[:dangling-cross-ref 1 "host.hdoc"] [:unknown-label 1 "host.hdoc"]]
             (mapv (juxt :type :source-line :source-file) diagnostics)))))
  (testing "no library directory at all"
    (let [{:keys [document diagnostics]} (resolve* "\n@{reformed:heidelberg}\n"
                                                   {:dir (constantly nil)})]
      (is (= [[:no-library-directory 2]] (mapv (juxt :type :source-line) diagnostics)))
      (is (= "??" (text-of (:text (first (links document))))))))
  (testing "a stale shelf resolves, with an info at the link's line"
    (let [{:keys [document diagnostics]} (resolve* "\n\n@{reformed:creeds/heidelberg#sec:q1}\n")]
      (is (= [{:type :stale-shelf :level :info :ref "reformed:creeds/heidelberg#sec:q1"
               :written "creeds" :current "confessions"
               :canonical "reformed:confessions/heidelberg"
               :source-file "host.hdoc" :source-line 3}]
             (mapv #(dissoc % :message) diagnostics)))
      (is (= "reformed:confessions/heidelberg#sec:q1" (:canonical (first (links document))))))))

;; --- AC #4: refs rewritten inside a quote --------------------------------------

(deftest quoted-refs-test
  (testing "a quoted ref the book resolved keeps the book's wording and gains the template's URL"
    (let [{:keys [document diagnostics]} (resolve* "!quote reformed:heidelberg#sec:q2\n")]
      (is (= [] diagnostics))
      (is (= [{:t :book-ref
               :ref "reformed:confessions/heidelberg#sec:q1"
               :passage {:form :label :label "sec:q1"}
               :canonical "reformed:confessions/heidelberg#sec:q1"
               :text [{:t :str :text "Section 1.1"}]
               :url "https://example.org/reformed/confessions/heidelberg.html#sec:q1"}]
             (links document)))))
  (testing "without a template it stays text only"
    (let [{:keys [document]} (resolve* "!quote reformed:heidelberg#sec:q2\n"
                                       {:dir (directory {:url-templates {}})})]
      (is (= [nil] (mapv :url (links document))))
      (is (= ["Section 1.1"] (mapv (comp text-of :text) (links document))))))
  (testing "one that dangled in the book is left alone: reported once, at the !quote"
    (let [{:keys [document diagnostics]} (resolve* "\n!quote reformed:heidelberg#sec:q3\n")]
      (is (= [[:unknown-label 2 "reformed:heidelberg#sec:q3"]]
             (mapv (juxt :type :source-line :ref) diagnostics)))
      (is (= [{:t :book-ref :ref "reformed:confessions/heidelberg#sec:none"
               :passage {:form :label :label "sec:none"} :text [{:t :str :text "??"}]}]
             (links document)))))
  (testing "one the book never resolved (a locator's blocks) is resolved here, as any link"
    (let [{:keys [document diagnostics]} (resolve* "!quote bible:romanos#cross\n")
          [link] (links document)]
      (is (= "bible:nt/romanos#sec:q1" (:ref link)))
      (is (= :unknown-label (:type (first diagnostics))) "Romanos has no sec:q1")
      (is (= "bible:nt/romanos#sec:q1" (:ref (first diagnostics))))
      (is (= ["host.hdoc" 1] ((juxt :source-file :source-line) (first diagnostics)))
          "reported at the !quote that brought it in")
      (is (= "??" (text-of (:text link))))))
  (testing "and resolves to its passage's title when the label exists"
    (let [dir (library/library-directory
               [(memory/memory-library
                 {:name "c"
                  :books [{:id (book-ref/book-id "c" "v")
                           :entry {:title "V"}
                           :document (fn [] (parser/parse "# X {#sec:x}\n\nX.\n"))
                           :locate (fn [_] {:title "V"
                                            :blocks [{:t :para
                                                      :inlines [{:t :cross-ref :label "sec:x"}]}]})}]})]
               [] {:url-templates {"c" "https://c.example/{slug}#{label}"}})
          {:keys [document diagnostics]} (resolve* "!quote c:v#1\n" {:dir dir})]
      (is (= [] diagnostics))
      (is (= [{:t :book-ref :ref "c:v#sec:x" :passage {:form :label :label "sec:x"}
               :canonical "c:v#sec:x" :text [{:t :str :text "V"} {:t :str :text ", Section 1"}]
               :url "https://c.example/v#sec:x"}]
             (links document))))))

;; --- without :books, and laziness ---------------------------------------------

(deftest without-books-test
  (testing "without :books a link is left as parsed and nothing is reported"
    (let [doc (parser/parse "See @{reformed:heidelberg#sec:q1} and @{Bad}.\n")
          {:keys [document diagnostics]} (resolver/resolve-document doc)]
      (is (= (:blocks doc) (:blocks document)))
      (is (= [] diagnostics))))
  (testing "a document without a book reference never loads the directory"
    (let [loads (atom 0)]
      (resolve* "# Plain {#sec:plain}\n\nNo links, @sec:plain.\n"
                {:dir (fn [] (swap! loads inc) (directory))})
      (is (= 0 @loads))))
  (testing "one with several loads it once"
    (let [loads (atom 0)]
      (resolve* "@{plain:p} @{bible:romanos#14:8}\n\n!quote plain:p\n"
                {:dir (fn [] (swap! loads inc) (directory))})
      (is (= 1 @loads)))))

;; --- the filesystem fixture, through a library directory file -----------------

(defn- temp-dir ^File []
  (.toFile (Files/createTempDirectory "haselnuss-link" (make-array FileAttribute 0))))

(def ^:private kinds (into #{} (map name) (keys resolver/default-lexicon)))

(defn- fs-resolve
  [yaml source]
  (let [file (io/file (temp-dir) "libraries.yaml")]
    (spit file (str/replace yaml "PASSAGES"
                            (.getAbsolutePath (io/file "test/fixtures/libraries/passages"))))
    (resolver/resolve-document
     (parser/parse source {:source "host.hdoc"})
     {:books (references/books-option
              {:directory (references/directory-loader
                           {:path (str file)
                            :registry (directory/register-provider
                                       directory/default-registry :filesystem
                                       (fs/provider {:kinds kinds}))})})})))

(deftest filesystem-library-test
  (testing "a link into a filesystem book, its URL from the entry's url key"
    (let [{:keys [document diagnostics]}
          (fs-resolve (str "- name: pass\n  type: filesystem\n  path: PASSAGES\n"
                           "  url: \"https://pass.example/{shelf}/{slug}/#{label}\"\n")
                      "See @{pass:heidelberg#sec:q3} and @{pass:nicene}.\n")
          [q3 nicene] (links document)]
      (is (= [:unreadable-include :unreadable-bibliography] (mapv :type diagnostics))
          "only the fixture books' own load warnings, each once")
      (is (= "pass:catechisms/heidelberg#sec:q3" (:canonical q3)))
      (is (= "Heidelberg Catechism, Section 1.3" (text-of (:text q3))))
      (is (= "https://pass.example/catechisms/heidelberg/#sec:q3" (:url q3)))
      (is (= "Nicene Creed" (text-of (:text nicene))))
      (is (= "https://pass.example/creeds/nicene/" (:url nicene)))))
  (testing "an entry without url: text only"
    (let [{:keys [document]} (fs-resolve "- name: pass\n  type: filesystem\n  path: PASSAGES\n"
                                         "See @{pass:nicene}.\n")]
      (is (= "Nicene Creed" (text-of (:text (first (links document))))))
      (is (nil? (:url (first (links document)))))))
  (testing "an entry whose url is not a valid template costs its library, and says why"
    (let [{:keys [document diagnostics]}
          (fs-resolve (str "- name: pass\n  type: filesystem\n  path: PASSAGES\n"
                           "  url: \"https://pass.example/{slgu}\"\n")
                      "See @{pass:nicene}.\n")
          [load-problem link-problem] diagnostics]
      (is (= [:invalid-library :unavailable-library] (mapv :type diagnostics)))
      (is (= "https://pass.example/{slgu}" (:url load-problem)))
      (is (str/includes? (:message load-problem) "unknown placeholder {slgu}"))
      (is (= "pass:nicene" (:ref link-problem)))
      (is (= "??" (text-of (:text (first (links document)))))))))

;; --- TASK-156: rendering, end to end -----------------------------------------

(def ^:private rendered-host
  "A host using the filesystem fixture library: a linked link, a failed
  link, a quote with a URL, and a failed quote."
  (str "# Host {#sec:host}\n\n"
       "See @{pass:heidelberg#sec:q3} and @{pass:nowhere}.\n\n"
       "!quote pass:nicene#sec:creed\n\n"
       "!quote pass:nowhere\n"))

(defn- rendered
  "`rendered-host` resolved against the fixture library under `url`, then
  sanitized, as the CLI does before emitting."
  [url]
  (let [{:keys [document labels]}
        (fs-resolve (str "- name: pass\n  type: filesystem\n  path: PASSAGES\n"
                         "  url: \"" url "\"\n")
                    rendered-host)
        {:keys [document diagnostics]} (sanitize/sanitize-document document)]
    {:document document :labels labels :sanitize-diagnostics diagnostics}))

(deftest renders-links-and-quotes-test
  (let [{:keys [document labels sanitize-diagnostics]}
        (rendered "https://pass.example/{shelf}/{slug}.html#{label}")]
    (is (= [] sanitize-diagnostics))
    (testing "HTML: an <a> for the linked link, ?? for the failed one, the quote's
              blockquote with data-source and cite and a linked source line, and
              the failed quote's visible placeholder"
      (let [out (html/emit-document document {:labels labels})]
        (is (str/includes? out (str "See <a class=\"book-ref\""
                                    " href=\"https://pass.example/catechisms/heidelberg.html#sec:q3\">"
                                    "Heidelberg Catechism, Section 1.3</a> and "
                                    "<span class=\"book-ref\">??</span>.")))
        (is (str/includes? out (str "<figure class=\"book-quote-figure\"><blockquote class=\"book-quote\""
                                    " data-source=\"pass:creeds/nicene#sec:creed\""
                                    " cite=\"https://pass.example/creeds/nicene.html#sec:creed\">"
                                    "<section><h2>The Creed</h2>")))
        (is (str/includes? out (str "<figcaption>\u2014 <cite>"
                                    "<a href=\"https://pass.example/creeds/nicene.html#sec:creed\">")))
        (is (str/includes? out "<blockquote class=\"book-quote\"><p>?? pass:nowhere</p></blockquote>"))))
    (testing "LaTeX: \\href links and source line, and it compiles under pdflatex"
      (let [tex (latex/emit-document document {:labels labels})
            dir (str (temp-dir))]
        (is (str/includes? tex (str "See \\href{https://pass.example/catechisms/heidelberg.html\\#sec:q3}"
                                    "{Heidelberg Catechism, Section 1.3} and ??.")))
        (is (str/includes? tex (str "\\par\\nobreak{\\raggedleft --- "
                                    "\\href{https://pass.example/creeds/nicene.html\\#sec:creed}")))
        (is (str/includes? tex "\\begin{quote}\n?? pass:nowhere\n\\end{quote}"))
        (spit (io/file dir "doc.tex") tex)
        (let [result (shell/sh "pdflatex" "-interaction=nonstopmode" "-halt-on-error" "doc.tex"
                               :dir dir)]
          (is (zero? (:exit result)) (:out result))
          (when (zero? (:exit result))
            (let [text (:out (shell/sh "pdftotext" (str (io/file dir "doc.pdf")) "-"))]
              (is (str/includes? text "We believe in one God."))
              (is (str/includes? text "?? pass:nowhere"))))))))
  (testing "an unsafe URL template is neutralised by the sanitize pass, links and quotes alike"
    (let [{:keys [document sanitize-diagnostics]} (rendered "javascript:alert('{slug}')")
          out (html/emit-document document {})]
      (is (= 2 (count sanitize-diagnostics)))
      (is (every? #(= :unsafe-url (:type %)) sanitize-diagnostics))
      (is (not (str/includes? out "javascript:")))
      (is (str/includes? out "<span class=\"book-ref\">Heidelberg Catechism, Section 1.3</span>"))
      (is (str/includes? out "<blockquote class=\"book-quote\" data-source=\"pass:creeds/nicene#sec:creed\">")))))
