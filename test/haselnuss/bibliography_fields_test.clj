(ns haselnuss.bibliography-fields-test
  "TASK-132: the resolver-generated reference list (what HTML and
  `--computed-numbers` LaTeX print) carries a reference's container
  title, volume, issue, pages, publisher and URL/DOI, not only author,
  year and title -- so a reader can find the work, and the two outputs
  agree with the native BibTeX path about what a citation refers to."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.emit.html :as html]
            [haselnuss.emit.latex :as latex]
            [haselnuss.resolver :as resolver]))

(def bibliography
  "An article, a book and a URL-only webpage, as CSL-JSON."
  {"art" {:id "art" :type "article-journal"
          :author [{:family "Smith" :given "John"}]
          :issued {:date-parts [[2020]]}
          :title "Hazel yields"
          :container-title "J. Nut Res."
          :volume 4 :issue "2" :page "1-9"
          :DOI "10.1000/xyz"}
   "bk" {:id "bk" :type "book"
         :author [{:family "Jones" :given "Bob"}]
         :issued {:date-parts [[2019]]}
         :title "A Book"
         :publisher "Nut Press"}
   "web" {:id "web" :type "webpage"
          :title "A page with no year"
          :URL "https://example.org/page"}})

(defn- cite-para [k]
  {:t :para
   :inlines [{:t :cite :items [{:key k :mode :normal}]}]})

(def resolved
  "The document citing all three, after citation resolution."
  (let [doc {:meta {} :blocks (mapv cite-para ["art" "bk" "web"])}
        {:keys [document]} (resolver/resolve-citations doc bibliography)]
    document))

(defn- entries []
  (->> resolved :blocks last :blocks first :items
       (mapv (fn [item]
               (:inlines (first item))))))

(defn- plain [inlines]
  (apply str (map (fn [i] (if (= :link (:t i))
                            (apply str (map :text (:inlines i)))
                            (if (= :space (:t i)) " " (:text i))))
                  inlines)))

(deftest reference-text-test
  (let [[art bk web] (entries)]
    (testing "article: container, volume(issue), pages and the DOI link"
      (is (= "Smith, J. (2020). Hazel yields. J. Nut Res., 4(2), 1-9. https://doi.org/10.1000/xyz"
             (plain art)))
      (is (= [{:t :link :target "https://doi.org/10.1000/xyz"}]
             (mapv #(select-keys % [:t :target]) (filter #(= :link (:t %)) art)))))
    (testing "book: publisher"
      (is (= "Jones, B. (2019). A Book. Nut Press." (plain bk))))
    (testing "webpage with only a title and URL prints the URL, linked"
      (is (= "n.a. (n.d.). A page with no year. https://example.org/page" (plain web)))
      (is (= "https://example.org/page"
             (:target (first (filter #(= :link (:t %)) web))))))
    (testing "the author-date style prints the same fields"
      (let [doc {:meta {:csl-style "apa"} :blocks [(cite-para "art")]}
            {:keys [document]} (resolver/resolve-citations doc bibliography)
            item (-> document :blocks last :blocks first :items first)]
        (is (str/includes? (plain (:inlines (first item))) "J. Nut Res., 4(2), 1-9."))))))

(deftest html-and-computed-latex-agree-test
  (let [htm (html/emit-document resolved)
        tex (latex/emit-document resolved {:computed-numbers true})]
    (doseq [s ["J. Nut Res., 4(2), 1-9." "Nut Press." "https://example.org/page"
               "https://doi.org/10.1000/xyz"]]
      (is (str/includes? htm s) (str "HTML shows " s))
      (is (str/includes? tex s)
          (str "LaTeX shows " s)))
    (is (str/includes? htm "href=\"https://example.org/page\""))))

(defn- reference-plain
  "The plain text of the one reference `entry` prints."
  [entry]
  (let [doc {:meta {} :blocks [(cite-para "k")]}
        {:keys [document]} (resolver/resolve-citations doc {"k" (assoc entry :id "k")})]
    (-> document :blocks last :blocks first :items first first :inlines plain)))

(deftest punctuation-and-field-combinations-test
  (testing "no doubled full stop after a title, container or publisher
            that already ends in terminal punctuation"
    (is (= "n.a. (n.d.). Why nuts? Nut Press Inc."
           (reference-plain {:title "Why nuts?" :publisher "Nut Press Inc."})))
    (is (= "n.a. (n.d.). Wow! J. Nuts."
           (reference-plain {:title "Wow!" :container-title "J. Nuts"}))))
  (testing "volume without an issue prints no parentheses"
    (is (= "n.a. (n.d.). T. J. Nuts, 7, 3-4."
           (reference-plain {:title "T" :container-title "J. Nuts" :volume 7 :page "3-4"}))))
  (testing "DOI and URL both present print both, linked"
    (let [entry {:id "k" :title "T" :DOI "10.1/a" :URL "https://example.org/a"}
          doc {:meta {} :blocks [(cite-para "k")]}
          {:keys [document]} (resolver/resolve-citations doc {"k" entry})
          inlines (-> document :blocks last :blocks first :items first first :inlines)]
      (is (= ["https://doi.org/10.1/a" "https://example.org/a"]
             (mapv :target (filter #(= :link (:t %)) inlines))))))
  (testing "a URL equal to the DOI link prints once"
    (let [entry {:id "k" :title "T" :DOI "10.1/a" :URL "https://doi.org/10.1/a"}
          doc {:meta {} :blocks [(cite-para "k")]}
          {:keys [document]} (resolver/resolve-citations doc {"k" entry})
          inlines (-> document :blocks last :blocks first :items first first :inlines)]
      (is (= 1 (count (filter #(= :link (:t %)) inlines)))))))

(deftest special-characters-compile-test
  (testing "& _ % # in a container, publisher and URL compile in
            computed-numbers mode under a real pdflatex"
    (let [entry {:id "k" :title "T & U_V" :container-title "A & B_C 100% #1"
                 :volume 1 :page "1-2" :publisher "P&Q_R %S #T"
                 :URL "https://example.org/a_b?x=1&y=2#frag%20z"}
          doc {:meta {} :blocks [(cite-para "k")]}
          {:keys [document]} (resolver/resolve-citations doc {"k" entry})
          tex (latex/emit-document document {:computed-numbers true})
          dir (java.nio.file.Files/createTempDirectory
               "haselnuss-bibfields" (make-array java.nio.file.attribute.FileAttribute 0))
          _ (spit (java.io.File. (str dir) "doc.tex") tex)
          result (shell/sh "pdflatex" "-interaction=nonstopmode" "-halt-on-error" "doc.tex"
                           :dir (str dir))]
      (is (zero? (:exit result)) (str (:out result) (:err result))))))
