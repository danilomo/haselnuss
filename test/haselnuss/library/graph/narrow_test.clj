(ns haselnuss.library.graph.narrow-test
  "`rebuild/narrowed` (TASK-267; doc-12, \"What gets rebuilt\" step 3 and
  \"Scale\" consequence 4): a refresh's `:rebuild` set narrowed by content.

  - AC #1: one edited verse of the site fixture's shared `blivre.json`
    marks only its book, plus the books quoting it or linking a passage
    whose title or existence changed.
  - AC #2: the synthetic collection's hub note (a small one here; the
    3,000-linker size is measured in the task notes): a body edit marks
    the hub alone, a title change every linker.
  - AC #3: a changed quoted passage reaches its quoters transitively,
    through a quote of a quote of a quote.
  - AC #4: a seeded sequence of random edits to a copy of the site
    fixture -- verses, titles, labels and their numbers, books added and
    removed, quoted passages -- after each of which every book whose page
    differs from the page before is in the narrowed set. A filesystem
    book's page is the normal `html` build of its file (what a site
    build emits); a generated book's is its document as its script
    produced it -- in this fixture they hold no references, so their page
    is made from that alone (the build cannot take a generated book as
    its input).

  TASK-287: Genesis's located passages also hold a link to and a quote
  of the catechism, which a quoting book (host) resolves itself, so it
  records them as derived edges; the property test's edits reach host
  through them.

  Every case works on a temp copy of `test/fixtures/site`, plus
  `reformed:indice`, written here: it links a numbered label of the
  catechism and the catechism as a whole, and quotes the section of
  `reformed:estudos/queda` that quotes the catechism, which quotes Romans."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.book-ref :as book-ref]
            [haselnuss.cli :as cli]
            [haselnuss.library :as library]
            [haselnuss.library.filesystem :as fs]
            [haselnuss.library.generated :as generated]
            [haselnuss.library.graph :as graph]
            [haselnuss.library.graph.backlinks :as backlinks]
            [haselnuss.library.graph.check :as check]
            [haselnuss.library.graph.rebuild :as rebuild]
            [haselnuss.library.graph.scan :as scan]
            [haselnuss.resolver :as resolver]
            [haselnuss.synth :as synth])
  (:import (java.io File)
           (java.nio.file Files LinkOption)
           (java.nio.file.attribute FileAttribute)
           (java.util Random)))

;; --- helpers -----------------------------------------------------------------

(defn- temp-dir ^File []
  (.toFile (Files/createTempDirectory "haselnuss-narrow" (make-array FileAttribute 0))))

(def ^:private indice
  "`reformed:indice`: links a numbered label and the whole catechism, and
  quotes queda's section that quotes the catechism's question 1."
  (str "---\ntitle: Índice\nlang: pt-BR\n---\n\n# Índice {#sec:indice}\n\n"
       "A segunda pergunta, @{reformed:catecismo-heidelberg#sec:q2}, do"
       " @{reformed:catecismo-heidelberg}.\n\n"
       "!quote reformed:estudos/queda#sec:fundamento\n"))

(def ^:private host
  "`reformed:host`: quotes two single verses, whose blocks (with the
  `shelf.clj` change `site-copy` makes) end in a cross-reference to the
  verse's chapter that the Bible book never resolved."
  (str "---\ntitle: Anfitrião\nlang: pt-BR\n---\n\n# Citações {#sec:citacoes}\n\n"
       "!quote bible:genesis#3:15\n\n!quote bible:romanos#8:28\n"))

(def ^:private one-verse-blocks
  "The one-verse branch of the fixture shelf's `:locate`, as written."
  "[(verse-para (nth (first found) 2))]")

(def ^:private genesis-extra-blocks
  "What the one-verse branch of `:locate` adds for Genesis alone
  (TASK-287): a link to the catechism's question 2 and a `!quote` of its
  edition note, references to a THIRD book that only the quoting book
  resolves -- Genesis's own document holds neither, so no edge of its
  record does."
  (str "[{:t :para :inlines [{:t :book-ref :ref \"reformed:catecismo-heidelberg#sec:q2\"}]}"
       " {:t :book-quote :ref \"reformed:catecismo-heidelberg#sec:edicao\"}]"))

(defn- site-copy
  "`{:root File :yaml path}`: a temp copy of the site fixture, plus
  `reformed/indice.hdoc` and `reformed/host.hdoc`, and with the shelf's
  `:locate` answering a one-verse passage with a second paragraph, a
  `:cross-ref` to the verse's chapter (`sec:cap-N`) -- a reference the
  book never resolved, which a quoting book shows as a link to that
  label, numbered as the book numbers it (TASK-267 review) -- and, for
  Genesis, `genesis-extra-blocks` (TASK-287)."
  []
  (let [from (io/file "test/fixtures/site")
        root (temp-dir)
        shelf (io/file root "bible/blivre/shelf.clj")]
    (doseq [^File f (file-seq from)
            :when (Files/isRegularFile (.toPath f) (make-array LinkOption 0))]
      (let [rel (str (.relativize (.toPath from) (.toPath f)))]
        (io/make-parents (io/file root rel))
        (io/copy f (io/file root rel))))
    (spit (io/file root "reformed/indice.hdoc") indice)
    (spit (io/file root "reformed/host.hdoc") host)
    (assert (str/includes? (slurp shelf) one-verse-blocks))
    (spit shelf (str/replace (slurp shelf) one-verse-blocks
                             (str "(into [(verse-para (nth (first found) 2))"
                                  " {:t :para :inlines [{:t :cross-ref :label (str \"sec:cap-\" chapter)}]}]"
                                  " (when (= slug \"genesis\") " genesis-extra-blocks "))")))
    {:root root :yaml (str (io/file root "libraries.yaml"))}))

(defn- context [yaml]
  (scan/context {:directory-opts {:path yaml :env {}}}))

(defn- refresh!
  "`scan/refresh` of `g` with the files `rels` (relative to `root`), and
  the narrowed set: `{:graph :rebuild :narrowed}`."
  [{:keys [root yaml]} g rels]
  (let [result (scan/refresh (context yaml) g (mapv #(str (io/file root %)) rels))]
    (assoc result :narrowed (rebuild/narrowed g result))))

(defn- id
  ([lib slug] (book-ref/book-id lib slug))
  ([lib shelf slug] (book-ref/book-id lib shelf slug)))

(def ^:private genesis (id "bible" ["blivre"] "genesis"))
(def ^:private romanos (id "bible" ["blivre"] "romanos"))
(def ^:private catechism (id "reformed" "catecismo-heidelberg"))
(def ^:private queda (id "reformed" ["estudos"] "queda"))
(def ^:private indice-id (id "reformed" "indice"))
(def ^:private host-id (id "reformed" "host"))

;; --- blivre.json -------------------------------------------------------------------

(defn- read-blivre [root]
  (json/read-str (slurp (io/file root "bible/blivre/blivre.json"))))

(defn- write-blivre!
  "Write `books` back to the shelf's data file; answers its relative path."
  [root books]
  (spit (io/file root "bible/blivre/blivre.json") (json/write-str books :escape-unicode false))
  "bible/blivre/blivre.json")

(defn- update-verse
  "`books` with `f` applied to the verses of `slug` (a vector of [c v text])."
  [books slug f]
  (mapv (fn [b] (if (= slug (get b "slug")) (update b "verses" (comp vec f)) b)) books))

(defn- edit-verse
  "`books` with the text of `slug`'s verse `c:v` replaced by `text`."
  [books slug c v text]
  (update-verse books slug (fn [vs] (map (fn [[c' v' t]] (if (and (= c c') (= v v')) [c' v' text] [c' v' t])) vs))))

;; --- AC #1: one edited verse ----------------------------------------------------

(deftest one-edited-verse-test
  (let [{:keys [root yaml] :as env} (site-copy)
        g0 (scan/scan (context yaml) nil)
        bible-books (into #{} (filter #(= "bible" (:library %))) (keys (graph/indexed-books g0)))]
    (testing "the shelf's books share one :data file, so refresh rescans them all"
      (is (= 5 (count bible-books))))
    (let [g1 (testing "a verse nothing quotes or links on its own: only its book"
               (let [r (refresh! env g0 [(write-blivre! root (edit-verse (read-blivre root) "genesis" 3 10 "Ouvi."))])]
                 (is (every? (:rebuild r) (conj bible-books catechism queda indice-id))
                     "refresh's own set is the whole shelf and everything citing it")
                 (is (= #{genesis} (:narrowed r))
                     "queda links genesis#3:1-24, which holds the verse, but a link shows only its title")
                 (:graph r)))
          g2 (testing "a quoted verse: its book, its quoter, and the quoters of that, transitively"
               (let [r (refresh! env g1 [(write-blivre! root (edit-verse (read-blivre root) "romanos" 14 8 "Editado."))])]
                 ;; host quotes the catechism's edition note through
                 ;; genesis#3:15 (`genesis-extra-blocks`, TASK-287): a
                 ;; quoter of a changed page, rebuilt whatever it quotes
                 (is (= #{romanos catechism queda indice-id host-id} (:narrowed r)))
                 (:graph r)))
          ;; queda's page changes, so indice, which quotes queda, is rebuilt
          ;; too: `narrowed` takes every quoter of such a book, whatever
          ;; passage it quotes (an over-approximation, as `affected-by`'s)
          g3 (testing "a book's title: every link to it shows it"
               (let [books (mapv #(if (= "genesis" (get % "slug")) (assoc % "title" "Genesis") %)
                                 (read-blivre root))
                     r (refresh! env g2 [(write-blivre! root books)])]
                 (is (= #{genesis queda indice-id host-id} (:narrowed r))
                     "host quotes genesis#3:15, whose title names the book")
                 (:graph r)))]
      (testing "a linked and quoted verse removed: its book and the books whose reference now dangles"
        (let [r (refresh! env g3 [(write-blivre! root (update-verse (read-blivre root) "genesis"
                                                                    #(remove (fn [[c v]] (= [3 15] [c v])) %)))])]
          (is (= #{genesis queda indice-id host-id} (:narrowed r)))
          (is (some #(= :dangling (:status %)) (graph/edges-from (:graph r) queda))))))))

;; --- AC #3: quoted passages, transitively ------------------------------------------

(defn- edit!
  "Replace `from` with `to` in the file `rel` under `root` (it must
  occur); answers `rel`."
  [root rel from to]
  (let [f (io/file root rel)
        text (slurp f)]
    (assert (str/includes? text from) (str rel " has no " from))
    (spit f (str/replace-first text from to))
    rel))

(deftest quoted-passage-transitive-test
  (let [{:keys [root yaml] :as env} (site-copy)
        g0 (scan/scan (context yaml) nil)
        q1-text "O meu único fundamento"]
    (testing "a changed passage of the catechism that queda quotes: queda, and indice, which quotes queda"
      (let [r (refresh! env g0 [(edit! root "reformed/catecismo-heidelberg.hdoc" q1-text "Meu único fundamento")])]
        (is (= #{catechism queda indice-id} (:narrowed r)))
        (testing "while a passage nobody quotes changes the catechism alone"
          (let [r2 (refresh! env (:graph r) [(edit! root "reformed/catecismo-heidelberg.hdoc"
                                                    "Primeiro: como" "Primeiro, como")])]
            (is (= #{catechism} (:narrowed r2)))
            (is (contains? (:rebuild r2) indice-id) "refresh's own set is wider")))))))

(deftest not-narrowed-without-changes-test
  (let [{:keys [root yaml] :as env} (site-copy)
        g0 (scan/scan (context yaml) nil)]
    (testing "a changed directory file scans afresh, and nothing is narrowed"
      (spit (io/file root "libraries.yaml") "\n# touched\n" :append true)
      (let [r (refresh! env g0 ["libraries.yaml"])]
        (is (= {:full? true} (:changes r)))
        (is (= (:rebuild r) (:narrowed r)))
        (is (contains? (:narrowed r) indice-id))))
    (testing "a result without :changes is not narrowed either"
      (is (= #{genesis} (rebuild/narrowed g0 {:graph g0 :rebuild #{genesis}}))))))

;; --- AC #2: the synthetic hub ---------------------------------------------------

(deftest synthetic-hub-test
  (let [dir (io/file (temp-dir) "synth")
        _ (synth/generate! (str dir) {:books 300})
        yaml (str (io/file dir "libraries.yaml"))
        env {:root dir :yaml yaml}
        hub (id "notes" "hub")
        g0 (scan/scan (context yaml) ["notes" "quotes"])
        linkers (into #{} (map (comp :id :from)) (graph/edges-to g0 hub))]
    (testing "the hub has its linkers (not vacuous)"
      (is (= 30 (count linkers))))
    (let [g1 (testing "a body edit: the hub alone"
               (let [text (slurp (io/file dir "notes/hub.hdoc"))
                     ;; front matter and heading, then the first paragraph
                     para (nth (str/split text #"\n\n") 2)
                     _ (assert (re-find #"^\p{Lu}" para) para)
                     r (refresh! env g0 [(edit! dir "notes/hub.hdoc" para (str para " Edited."))])]
                 (is (every? (:rebuild r) linkers) "refresh's own set holds every linker")
                 (is (= #{hub} (:narrowed r)))
                 (:graph r)))]
      (testing "a title change: the hub and every linker"
        (let [r (refresh! env g1 [(edit! dir "notes/hub.hdoc" "title: Hub" "title: Hub Note")])]
          (is (= (conj linkers hub) (:narrowed r))))))
    (testing "a quote chain: the end of the chain edited, every link of it rebuilt"
      (let [chain (into #{} (map #(id "quotes" ["chain-0002"] (format "q-0002-%d" %))) (range 1 6))
            text (slurp (io/file dir "quotes/chain-0002/q-0002-5.hdoc"))
            para (first (str/split-lines (second (str/split text #"\{#sec:body\}\n\n"))))
            r (refresh! env g0 [(edit! dir "quotes/chain-0002/q-0002-5.hdoc" para (str para " Edited."))])]
        (is (= chain (:narrowed r)))))))

;; --- AC #4: never omit a book whose page changed -----------------------------------

(def ^:private no-env {:library-env {} :library-home "/nonexistent/haselnuss-test-home"})

(defn- html-of
  "The `html` build of the `.hdoc` file `file`, in the directory `yaml`."
  [yaml ^File file]
  (let [out (io/file (temp-dir) "page.html")]
    (binding [*err* (java.io.StringWriter.)]
      (cli/build (merge no-env {:libraries yaml :input (str file) :target "html" :output (str out)})))
    (let [html (slurp out)]
      (io/delete-file out)
      (io/delete-file (.getParentFile out))
      html)))

(defn- pages
  "BookId -> the page of every book the directory `yaml` lists (several
  books listed under one id: their pages in order of their files)."
  [yaml]
  (let [dir (:directory (context yaml))]
    (reduce (fn [m lib-name]
              (let [lib (library/find-library dir lib-name)]
                (reduce (fn [m book]
                          (let [bid (:id (library/book-entry book))
                                page (cond
                                       (generated/generated-book? book)
                                       (scan/canonical-form [(library/book-entry book) (library/document book)])
                                       (fs/filesystem-book? book)
                                       (html-of yaml (fs/entry-file book)))]
                            (update m bid #(vec (sort (conj (or % []) (str page)))))))
                        m
                        (library/listed-books lib))))
            {}
            (library/library-names dir))))

(defn- random-paragraph-line
  "A random line of `text` that is plain prose (not a heading, a
  directive, front matter or blank), or nil."
  [^Random rnd text]
  (let [body (second (str/split text #"\n---\n" 2))
        lines (filterv #(and (re-find #"^\p{L}" %) (not (str/includes? % ":"))) (str/split-lines (or body "")))]
    (when (seq lines) (nth lines (.nextInt rnd (count lines))))))

(def ^:private hdoc-books
  ["reformed/catecismo-heidelberg.hdoc" "reformed/estudos/queda.hdoc" "reformed/indice.hdoc"
   "reformed/catkins/book.hdoc" "reformed/dangling.hdoc" "reformed/host.hdoc"])

(def ^:private edit-kinds
  "The kinds of edit `random-edit` makes, numbered as in its `case`."
  14)

(defn- random-edit
  "Apply one random edit of `kind` (below `edit-kinds`) under `root`;
  answers `[description changed-rels]`."
  [^Random rnd root kind]
  (let [pick (fn [xs] (nth xs (.nextInt rnd (count xs))))
        books (read-blivre root)
        slugs (mapv #(get % "slug") books)]
    (case (int kind)
      0 (let [slug (pick slugs)
              [c v] (pick (get (some #(when (= slug (get % "slug")) %) books) "verses" [[1 1]]))]
          [(str "verse text " slug " " c ":" v)
           [(write-blivre! root (edit-verse books slug c v (str "Texto " (.nextInt rnd 1000) ".")))]])
      1 (let [slug (pick slugs)
              vs (get (some #(when (= slug (get % "slug")) %) books) "verses")]
          (if (> (count vs) 1)
            (let [[c v] (pick vs)]
              [(str "verse removed " slug " " c ":" v)
               [(write-blivre! root (update-verse books slug #(remove (fn [[c' v']] (= [c v] [c' v'])) %)))]])
            [(str "verse added " slug)
             [(write-blivre! root (update-verse books slug #(conj (vec %) [99 1 "Novo."])))]]))
      2 (let [slug (pick slugs)]
          [(str "title " slug)
           [(write-blivre! root (mapv #(if (= slug (get % "slug")) (update % "title" str "!") %) books))]])
      3 (if (some #{"tobias"} slugs)
          ["book removed tobias"
           [(write-blivre! root (filterv #(not= "tobias" (get % "slug")) books))]]
          ["book added tobias"
           [(write-blivre! root (conj books {"slug" "tobias" "title" "Tobias" "verses" [[1 1 "Eu, Tobias."]]}))]])
      4 (let [slug (pick (vec (remove #{"genesis" "romanos"} slugs)))]
          (if slug
            [(str "book removed " slug)
             [(write-blivre! root (filterv #(not= slug (get % "slug")) books))]]
            ["no-op" []]))
      5 (let [rel (pick hdoc-books)
              f (io/file root rel)]
          (if (.exists f)
            (if-let [line (random-paragraph-line rnd (slurp f))]
              [(str "body " rel) [(edit! root rel line (str line " Mais."))]]
              ["no-op" []])
            ["no-op" []]))
      6 (let [rel (pick hdoc-books)
              f (io/file root rel)]
          (if (.exists f)
            [(str "title " rel)
             [(let [text (slurp f)]
                (spit f (str/replace-first text #"(?m)^title: (.*)$" "title: $1 (2)"))
                rel)]]
            ["no-op" []]))
      7 (let [rel "reformed/catecismo-heidelberg.hdoc"
              text (slurp (io/file root rel))]
          ;; a section before the questions renumbers them
          (if (str/includes? text "# Prefácio")
            ["section removed" [(edit! root rel "# Prefácio {#sec:prefacio}\n\nUm prefácio.\n\n" "")]]
            ["section added" [(edit! root rel "# Domingo 1" "# Prefácio {#sec:prefacio}\n\nUm prefácio.\n\n# Domingo 1")]]))
      8 (let [rel "reformed/catecismo-heidelberg.hdoc"
              text (slurp (io/file root rel))]
          (if (str/includes? text "{#sec:q2x}")
            ["label renamed back" [(edit! root rel "{#sec:q2x}" "{#sec:q2}")]]
            ["label renamed" [(edit! root rel "{#sec:q2}" "{#sec:q2x}")]]))
      9 (let [f (io/file root "reformed/indice.hdoc")]
          (if (.exists f)
            (do (io/delete-file f) ["book file deleted indice" ["reformed/indice.hdoc"]])
            (do (spit f indice) ["book file added indice" ["reformed/indice.hdoc"]])))
      10 (let [rel "reformed/estudos/queda.hdoc"]
           (if (str/includes? (slurp (io/file root rel)) "#sec:q1\n")
             ["quote retargeted" [(edit! root rel "#sec:q1\n" "#sec:q2\n")]]
             ["quote retargeted back" [(edit! root rel "#sec:q2\n" "#sec:q1\n")]]))
      ;; the passage queda quotes, which indice quotes in turn
      ;; a chapter before the others renumbers the book's chapters, which
      ;; host's quotes show through their locator-built cross-references
      12 (let [candidates (filterv #{"genesis" "romanos"} slugs)
               slug (when (seq candidates) (pick candidates))
               verses (get (some #(when (= slug (get % "slug")) %) books) "verses")]
           (cond
             (nil? slug) ["no-op" []]
             (some #(= 1 (first %)) verses)
             [(str "chapter removed " slug)
              [(write-blivre! root (update-verse books slug #(remove (fn [[c]] (= 1 c)) %)))]]
             :else
             [(str "chapter added " slug)
              [(write-blivre! root (update-verse books slug #(cons [1 1 "No princípio."] %)))]]))
      11 ["quoted passage" [(edit! root "reformed/catecismo-heidelberg.hdoc"
                                   "O meu único fundamento"
                                   (str "O meu único fundamento (" (.nextInt rnd 1000) ")"))]]
      ;; TASK-287: the located passage gains or loses its references to
      ;; the catechism, so host's derived edges come and go
      13 (let [rel "bible/blivre/shelf.clj"
               text (slurp (io/file root rel))]
           (if (str/includes? text genesis-extra-blocks)
             ["located references dropped" [(edit! root rel genesis-extra-blocks "[]")]]
             ["located references added" [(edit! root rel "(when (= slug \"genesis\") [])"
                                                 (str "(when (= slug \"genesis\") " genesis-extra-blocks ")"))]])))))

(deftest quoted-locator-cross-ref-test
  ;; TASK-267 review: a cross-reference a book's `locate` built is shown by
  ;; the quoting page as a link to that label of the book, numbered as the
  ;; book numbers it, so a renumbering that leaves the quoted blocks alone
  ;; still changes the quoter's page
  (let [{:keys [root yaml] :as env} (site-copy)
        g0 (scan/scan (context yaml) nil)
        page #(html-of yaml (io/file root "reformed/host.hdoc"))
        before (page)
        r (refresh! env g0 [(write-blivre! root (update-verse (read-blivre root) "genesis"
                                                              #(cons [1 1 "No princípio."] %)))])
        after (page)]
    (testing "not vacuous: the host's page shows genesis's chapter number, which changed"
      (is (str/includes? before "Gênesis, Seção 1"))
      (is (str/includes? after "Gênesis, Seção 2")))
    (is (= #{genesis host-id} (:narrowed r)))))

(deftest third-book-in-a-located-passage-test
  ;; TASK-287: Genesis's `locate` answers a link to the catechism and a
  ;; quote of it (`genesis-extra-blocks`), which host -- quoting
  ;; genesis#3:15 -- resolves itself: the dependency is host's own
  (let [{:keys [root yaml] :as env} (site-copy)
        g0 (scan/scan (context yaml) nil)
        page #(html-of yaml (io/file root "reformed/host.hdoc"))
        derived (fn [g] (into #{} (comp (filter :via) (map (juxt :kind (comp :written :to) :status)))
                              (graph/edges-from g host-id)))]
    (testing "host records the references inside the located passage, through its quote"
      (is (= #{[:link "reformed:catecismo-heidelberg#sec:q2" :resolved]
               [:quote "reformed:catecismo-heidelberg#sec:edicao" :resolved]}
             (derived g0)))
      (is (every? #(= "bible:genesis#3:15" (:via %)) (filter :via (graph/edges-from g0 host-id)))))
    (let [title "title: Catecismo de Heidelberg (1563), Domingo 1"
          before (page)
          r (refresh! env g0 [(edit! root "reformed/catecismo-heidelberg.hdoc" title (str title " (2)"))])
          after (page)]
      (testing "the catechism's title: host's page shows it in the link"
        (is (not (str/includes? before "Domingo 1 (2)")))
        (is (str/includes? after "Domingo 1 (2)"))
        (is (contains? (:rebuild r) host-id))
        (is (contains? (:narrowed r) host-id)))
      (testing "the quoted edition note: host's page shows it"
        (let [r2 (refresh! env (:graph r) [(edit! root "reformed/catecismo-heidelberg.hdoc"
                                                  "Uma cópia do primeiro" "Uma cópia (2) do primeiro")])]
          (is (str/includes? (page) "Uma cópia (2) do primeiro"))
          (is (contains? (:rebuild r2) host-id))
          (is (contains? (:narrowed r2) host-id))
          (testing "a body edit elsewhere in the catechism leaves host alone"
            (let [r3 (refresh! env (:graph r2) [(edit! root "reformed/catecismo-heidelberg.hdoc"
                                                       "Primeiro: como" "Primeiro, como")])]
              (is (not (contains? (:narrowed r3) host-id)))))))
      (testing "the located passage no longer names the catechism: the derived edges go"
        (let [shelf (io/file root "bible/blivre/shelf.clj")
              with-extra (slurp shelf)
              _ (spit shelf (str/replace with-extra genesis-extra-blocks "[]"))
              g (:graph (refresh! env g0 ["bible/blivre/shelf.clj"]))]
          (is (= #{} (derived g)))
          (is (= (graph/edges-from (scan/scan (context yaml) nil) host-id)
                 (graph/edges-from g host-id))
              "refresh agrees with a fresh scan")
          (testing "and come back"
            (spit shelf with-extra)
            (let [g2 (:graph (refresh! env g ["bible/blivre/shelf.clj"]))]
              (is (= 2 (count (derived g2))))
              (is (= (graph/edges-from (scan/scan (context yaml) nil) host-id)
                     (graph/edges-from g2 host-id)))
              (is (= (into #{} (keep #(when (= host-id (:id (:from %))) (:via %)))
                           (graph/edges-to g2 catechism))
                     #{"bible:genesis#3:15"})
                  "edges-to the catechism finds them, as the rebuild functions ask"))))))))

(def ^:private chain-shelf
  "A shelf of books `b1`..`b12` whose `locate` answers, for `bN`, a
  paragraph linking `deep:alvo` and a quote of `b(N+1)`'s passage: a chain
  of located quotes as deep as the books go."
  (str "{:books (fn [_] (for [n (range 1 13)] {:slug (str \"b\" n) :title (str \"B\" n)}))\n"
       " :document (fn [_ slug] {:meta {:title [{:t :str :text slug}]}"
       " :blocks [{:t :para :inlines [{:t :str :text slug}]}]})\n"
       " :locate (fn [_ slug locator]\n"
       "   (let [n (read-string (subs slug 1))]\n"
       "     {:title (str slug \" \" locator)\n"
       "      :blocks [{:t :para :inlines [{:t :str :text (str \"nivel-\" n \" \")}"
       " {:t :book-ref :ref \"deep:alvo\"}]}\n"
       "               {:t :book-quote :ref (str \"deep:b\" (inc n) \"#1\")}]}))}\n"))

(deftest located-quote-depth-test
  ;; TASK-287 review: derived edges follow located quotes exactly as deep
  ;; as a build expands them -- the passage of a quote at
  ;; `resolver/max-quote-depth` is spliced and its link resolved; the
  ;; quote inside it is too deep
  (let [root (temp-dir)
        yaml (io/file root "libraries.yaml")
        host-file (io/file root "deep/host.hdoc")]
    (io/make-parents (io/file root "deep/cadeia/shelf.clj"))
    (spit yaml "- name: deep\n  type: filesystem\n  path: deep\n  scripts: true\n")
    (spit (io/file root "deep/cadeia/shelf.clj") chain-shelf)
    (spit (io/file root "deep/alvo.hdoc") "---\ntitle: Alvo\n---\n\nO alvo.\n")
    (spit host-file "---\ntitle: Host\n---\n\n!quote deep:b1#1\n")
    (let [g (scan/scan (context (str yaml)) nil)
          derived (filter :via (graph/edges-from g (id "deep" "host")))
          html (html-of (str yaml) host-file)
          shown (count (re-seq #"nivel-\d+" html))]
      (testing "not vacuous: the build splices max-quote-depth passages"
        (is (= resolver/max-quote-depth shown))
        (is (str/includes? html (str "nivel-" resolver/max-quote-depth)))
        (is (not (str/includes? html (str "nivel-" (inc resolver/max-quote-depth))))))
      (is (= shown (count (filter #(= :link (:kind %)) derived)))
          "one derived link per spliced passage, the deepest included")
      (is (= shown (count (filter #(= :quote (:kind %)) derived)))
          "the quote too deep to splice is recorded too, and nothing below it"))))

(defn- without-derived
  "`g` with every derived edge (`:via`) taken out of its records."
  [g]
  (reduce-kv (fn [g bid record]
               (graph/with-book g (assoc record :id bid :edges (into [] (remove :via) (graph/edges-from g bid)))))
             g
             (graph/indexed-books g)))

(deftest derived-edges-not-reported-test
  ;; TASK-287 AC #4: derived edges are dependencies only -- `--check` and
  ;; `--backlinks` report exactly what they did without them, a dangling
  ;; one included
  (let [{:keys [root yaml]} (site-copy)
        _ (edit! root "reformed/catecismo-heidelberg.hdoc" "{#sec:q2}" "{#sec:q2x}")
        g (scan/scan (context yaml) nil)
        bare (without-derived g)]
    (testing "not vacuous: host has derived edges into the catechism, one of them dangling"
      (is (some #(and (:via %) (= :dangling (:status %)) (= :unknown-label (:problem %)))
                (graph/edges-from g host-id)))
      (is (not= (set (graph/edges-to g catechism)) (set (graph/edges-to bare catechism)))))
    (is (= (check/check-report bare) (check/check-report g)))
    (is (= (backlinks/backlinks-report bare catechism) (backlinks/backlinks-report g catechism)))
    (is (= (backlinks/backlinks-report bare {:id catechism :passage "sec:edicao"})
           (backlinks/backlinks-report g {:id catechism :passage "sec:edicao"})))))

(deftest ^:slow narrowed-never-omits-a-changed-page-test
  (let [{:keys [root yaml] :as env} (site-copy)
        rnd (Random. 267)
        ;; every kind of edit once, in a seeded order, then kind 13 again --
        ;; it toggles, so the located references are dropped AND added back
        ;; (TASK-287) -- then random ones
        kinds (let [l (java.util.ArrayList. ^java.util.Collection (range edit-kinds))]
                (java.util.Collections/shuffle l rnd)
                (-> (vec l) (conj 13) (into (repeatedly 12 #(.nextInt rnd edit-kinds)))))
        steps (count kinds)
        stats (atom {:steps 0 :changed 0 :narrower 0 :kinds #{} :located #{}})
        ;; every record whole, `:edges` included
        records (fn [g]
                  (into {} (map (fn [[bid record]] [bid (assoc record :edges (graph/edges-from g bid))]))
                        (graph/indexed-books g)))]
    (loop [i 0 g (scan/scan (context yaml) nil) before (pages yaml)]
      (when (< i steps)
        (let [[what rels] (random-edit rnd root (nth kinds i))
              {:keys [narrowed rebuild] g2 :graph} (refresh! env g rels)
              after (pages yaml)
              changed (into #{} (remove #(= (get before %) (get after %))) (into (set (keys before)) (keys after)))]
          (is (empty? (remove narrowed changed))
              (str "step " i " (" what "): pages changed but not rebuilt: "
                   (mapv book-ref/format-book-id (remove narrowed changed))))
          ;; TASK-287: refresh adds and drops derived edges as a scan would,
          ;; and leaves every record -- hash, labels, diagnostics -- as one
          (let [fresh (records (scan/scan (context yaml) nil))
                refreshed (records g2)]
            (is (= (set (keys fresh)) (set (keys refreshed))) (str "step " i " (" what "): books differ"))
            (doseq [bid (keys fresh)]
              (is (= (get fresh bid) (get refreshed bid))
                  (str "step " i " (" what "): the refreshed record of " (book-ref/format-book-id bid)
                       " differs from a scan's"))))
          (swap! stats #(-> %
                            (cond-> (str/starts-with? what "located references")
                              (update :located conj what))
                            (update :steps inc)
                            (cond-> (not= "no-op" what)
                              (update :kinds conj (str/join " " (take 2 (str/split what #" ")))))
                            (update :changed + (count changed))
                            (cond-> (< (count narrowed) (count rebuild)) (update :narrower inc))))
          (recur (inc i) g2 after))))
    (testing "not vacuous: pages changed, the edits were varied, and narrowing narrowed"
      (is (= steps (:steps @stats)))
      (is (<= steps (:changed @stats)) (pr-str @stats))
      (is (<= 12 (count (:kinds @stats))) (pr-str @stats))
      (is (= #{"located references dropped" "located references added"} (:located @stats))
          "the located references went both ways")
      (is (<= (quot steps 3) (:narrower @stats)) (pr-str @stats)))))
