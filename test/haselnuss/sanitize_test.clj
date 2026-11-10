(ns haselnuss.sanitize-test
  "TASK-136: executable URL schemes and on* attributes never reach output."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.cli :as cli]
            [haselnuss.emit.latex :as latex]
            [haselnuss.parser :as parser]
            [haselnuss.sanitize :as sanitize])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- build [src target]
  (cli/run src {:target target}))

(deftest unsafe-url-test
  (testing "scheme allow-list"
    (is (every? sanitize/unsafe-url?
                ["javascript:alert(1)" "JaVaScRiPt:x" " javascript:x" "java\tscript:x"
                 "data:text/html,x" "vbscript:x"]))
    (is (not-any? sanitize/unsafe-url?
                  ["http://a.b" "https://a.b" "mailto:a@b.c" "#frag" "img/a.png"
                   "../a.png" "C:\\a.png" "a:b" "?q=a:b"])
        "a one-letter scheme and scheme-less strings are not schemes")))

(deftest link-and-image-test
  (doseq [target ["html" "latex"]]
    (let [{:keys [output diagnostics]}
          (build "[click](javascript:alert(1)) and ![pic](data:image/svg+xml,x) ok [fine](https://e.org)\n" target)]
      (is (not (re-find #"javascript:|data:image" output)) target)
      (is (str/includes? output "click") "link text survives")
      (is (str/includes? output "https://e.org"))
      (is (= #{:unsafe-url} (set (map :type diagnostics))))
      (is (some #(str/includes? (:message %) "javascript:alert(1)") diagnostics))
      (is (some #(str/includes? (:message %) "data:image/svg+xml,x") diagnostics)))))

(deftest attribute-test
  (let [{:keys [output diagnostics]}
        (build "A [span]{onclick=\"alert(4)\" lang=en} here.\n\n![x](a.png){onerror=\"alert(3)\"}\n" "html")]
    (is (not (re-find #"onclick|onerror|alert" output)))
    (is (str/includes? output "lang=\"en\""))
    (is (= 2 (count (filter #(= :event-handler-attribute (:type %)) diagnostics))))
    (is (some #(= "onclick" (:name %)) diagnostics))))

(deftest describe-prop-test
  (let [overlay (java.io.File/createTempFile "sanitize" ".hdoc")]
    (try
      (spit overlay "!describe{target=sec:a onmouseover=\"alert(1)\" title=ok}\n")
      (let [{:keys [output diagnostics]}
            (cli/run "# Intro {#sec:a}\n\nText.\n" {:target "html" :overlay [(str overlay)]})]
        (is (str/includes? output "title=\"ok\""))
        (is (not (str/includes? output "onmouseover")))
        (is (some #(= "onmouseover" (:name %)) diagnostics)))
      (finally (.delete overlay)))))

(deftest clean-document-test
  (let [{:keys [diagnostics]} (build "# Hi\n\nA [link](http://a.b) and ![i](pic.png).\n" "html")]
    (is (empty? diagnostics))))

(defn- props-para [props]
  {:t :para
   :inlines [{:t :span :inlines [{:t :str :text "x"}]
              :attr {:classes [] :props props}}]})

(deftest url-bearing-prop-test
  (testing "TASK-145: srcset, xlink:href and the rest of the closed list are scheme-checked"
    (let [props {"srcset" "a.png 1x, javascript:alert(1) 2x"
                 "xlink:href" "javascript:alert(2)"
                 "ping" "https://ok.example vbscript:x"
                 "data" "data:text/html,x"
                 "cite" "https://ok.example"
                 "title" "javascript:not-a-url-prop"}
          {:keys [document diagnostics]}
          (sanitize/sanitize-document {:meta {} :blocks [(props-para props)]})
          kept (get-in document [:blocks 0 :inlines 0 :attr :props])]
      (is (= {"cite" "https://ok.example" "title" "javascript:not-a-url-prop"} kept))
      (is (= 4 (count (filter #(= :unsafe-url (:type %)) diagnostics))))))
  (testing "a clean srcset passes"
    (let [props {"srcset" "a.png 1x, https://e.org/b.png 2x"}
          {:keys [document diagnostics]}
          (sanitize/sanitize-document {:meta {} :blocks [(props-para props)]})]
      (is (empty? diagnostics))
      (is (= props (get-in document [:blocks 0 :inlines 0 :attr :props]))))))

(deftest poster-fallback-test
  (let [d {:t :directive :name "chart" :blocks [] :attr {:classes [] :props {} :id "fig:a"}
           :fallback {:kind :poster :src "javascript:alert(1)"}}
        {:keys [document diagnostics]} (sanitize/sanitize-document {:meta {} :blocks [d]})]
    (is (= {:kind :drop} (:fallback (first (:blocks document)))))
    (is (some #(str/includes? (:message %) "javascript:alert(1)") diagnostics)))
  (let [d {:t :directive :name "chart" :blocks [] :attr {:classes [] :props {}}
           :fallback {:kind :poster :src "chart.png"}}
        {:keys [document diagnostics]} (sanitize/sanitize-document {:meta {} :blocks [d]})]
    (is (= :poster (:kind (:fallback (first (:blocks document))))))
    (is (empty? diagnostics))))

(deftest book-url-test
  (testing "TASK-156: a book link's or quote's :url is scheme-tested like a link target,
            at any depth -- here a link inside the quoted passage and inside its title"
    (let [bad-link {:t :book-ref :ref "b:x" :text [{:t :str :text "X"}] :url "javascript:alert(1)"}
          good-link {:t :book-ref :ref "b:y" :text [{:t :str :text "Y"}] :url "https://e.org/y#sec:q1"}
          quote-block {:t :book-quote :ref "b:x#sec:a" :canonical "b:x#sec:a" :url "JavaScript:alert(2)"
                       :title [{:t :str :text "X, "} (assoc bad-link :url "data:text/html,x")]
                       :blocks [{:t :para :inlines [(assoc bad-link :url "vbscript:x") good-link]}]}
          {:keys [document diagnostics]}
          (sanitize/sanitize-document {:meta {} :blocks [{:t :para :inlines [bad-link good-link]}
                                                         quote-block]})
          [para q] (:blocks document)]
      (is (= [(dissoc bad-link :url) good-link] (:inlines para))
          "the unsafe link keeps its text and loses only its URL; the safe one is untouched")
      (is (= (-> quote-block
                 (dissoc :url)
                 (assoc-in [:title 1] (dissoc bad-link :url))
                 (assoc-in [:blocks 0 :inlines 0] (dissoc bad-link :url)))
             q))
      (is (= 4 (count diagnostics)))
      (is (every? #(= :unsafe-url (:type %)) diagnostics))
      (is (= #{"javascript:alert(1)" "JavaScript:alert(2)" "data:text/html,x" "vbscript:x"}
             (set (map :url diagnostics))))
      (is (some #(str/includes? (:message %) "book quote's source link") diagnostics))))
  (testing "a relative or http(s) book URL passes"
    (let [doc {:meta {} :blocks [{:t :book-quote :ref "b:x" :canonical "b:x" :url "/books/x.html"
                                  :blocks []}
                                 {:t :para :inlines [{:t :book-ref :ref "b:x" :url "http://e.org"}]}]}]
      (is (= {:document doc :diagnostics []} (sanitize/sanitize-document doc))))))

(def ^:private reviewer-bypasses
  "TASK-215 review: TeX that got past the first, deny-list version of the
  check and was executed by pdflatex, each with the token the allow-list
  now refuses it on."
  [["\\ExplSyntaxOn \\tex_input:D {secret} \\ExplSyntaxOff" "\\ExplSyntaxOn"]
   ["\\tex_input:D {secret}" "\\tex"]
   [(str "\\ExplSyntaxOn \\ior_open:Nn \\g_tmpa_ior {secret} \\ior_get:NN \\g_tmpa_ior"
         " \\l_tmpa_tl \\l_tmpa_tl \\ExplSyntaxOff") "\\ExplSyntaxOn"]
   [(str "\\ExplSyntaxOn \\iow_new:N \\g_x_iow \\iow_open:Nn \\g_x_iow {pwned.txt}"
         " \\iow_now:Nn \\g_x_iow {owned} \\ExplSyntaxOff") "\\ExplSyntaxOn"]
   ["\\UseName{input}{secret}" "\\UseName"]
   ["\\NewDocumentCommand\\zz{}{\\zz}\\zz" "\\NewDocumentCommand"]
   ["\\begin{input}{file}" "\\begin{input}{file}"]
   ["\\begin {input}{file}" "\\begin {input}{file}"]
   ["\\begin input" "\\begin input"]
   ["\\alpha:D" "\\alpha:"]
   ["\\alpha@x" "\\alpha@"]])

(deftest tex-problem-test
  (testing "TASK-215: the reviewer's bypasses of the deny-list are refused by the allow-list"
    (doseq [[tex token] reviewer-bypasses]
      (is (= token (sanitize/tex-problem tex true)) tex)))
  (testing "and so is everything the deny-list caught"
    (doseq [[tex token] [["\\input{/etc/passwd}" "\\input"]
                         ["\\input /etc/passwd" "\\input"]
                         ["\\include{x}" "\\include"]
                         ["\\InputIfFileExists{x}{}{}" "\\InputIfFileExists"]
                         ["\\IfFileExists{x}{}{}" "\\IfFileExists"]
                         ["\\includegraphics{/etc/x.png}" "\\includegraphics"]
                         ["\\verbatiminput{x}" "\\verbatiminput"]
                         ["\\@@input x" "\\@"]
                         ["\\immediate\\write18{id}" "\\immediate"]
                         ["\\write18{id}" "\\write"]
                         ["\\openin1=x" "\\openin"]
                         ["\\openout1=x" "\\openout"]
                         ["\\read1 to\\x" "\\read"]
                         ["\\catcode`\\@=0" "\\catcode"]
                         ["\\lccode`a=`b" "\\lccode"]
                         ["\\csname input\\endcsname" "\\csname"]
                         ["\\stop" "\\stop"]
                         ["\\label{x}" "\\label"]
                         ["\\color{red} x" "\\color"]
                         ["\\bbox[red]{x}" "\\bbox"]
                         ["\\unicode{x2211}" "\\unicode"]
                         ["\\é" "\\é"]
                         ["\\" "\\"]
                         ["x}\\(" "an unbalanced }"]
                         ["{x" "an unbalanced {"]
                         ["\\expandafter\\x" "\\expandafter"]
                         ["\\scantokens{x}" "\\scantokens"]
                         ["\\def\\x{y}" "\\def"]
                         ["\\gdef\\x{y}" "\\gdef"]
                         ["\\mathchardef\\x=1" "\\mathchardef"]
                         ["\\let\\x=\\relax" "\\let"]
                         ["\\newcommand{\\x}{y}" "\\newcommand"]
                         ["\\everymath{x}" "\\everymath"]
                         ["\\usepackage{x}" "\\usepackage"]
                         ["\\RequirePackage{x}" "\\RequirePackage"]
                         ["\\special{x}" "\\special"]
                         ["\\directlua{x}" "\\directlua"]
                         ["\\pdffiledump{x}" "\\pdffiledump"]
                         ["\\loop\\iftrue\\repeat" "\\loop"]
                         ["\\makeatletter" "\\makeatletter"]
                         ["\\href{javascript:x}{y}" "\\href"]
                         ["\\url{javascript:x}" "\\url"]
                         ["\\class{x}{y}" "\\class"]
                         ["\\style{x}{y}" "\\style"]
                         ["\\require{x}" "\\require"]
                         ["\\INPUT{x}" "\\INPUT"]
                         ["^^5cinput{x}" "^^"]
                         ["x^^M" "^^"]]]
      (is (= token (sanitize/tex-problem tex true)) tex)))
  (testing "ordinary mathematics passes"
    (doseq [tex ["x^2 + y_1" "\\frac{a}{b}" "\\sqrt[3]{x}" "\\alpha\\beta\\Gamma"
                 "\\mathbf{v} \\cdot \\mathrm{d}x" "\\left( x \\right)" "\\sum_{i=1}^{n} i"
                 "\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}" "\\displaystyle\\int"
                 "\\operatorname{lcm}" "\\textstyle x" "\\text{if } x" "\\alpha_1 + \\beta_{ij}"
                 "\\overline{AB}" "\\hat{x}^{2}" "x^{^2}" "a\\,b\\;c\\!d\\quad e"
                 "\\left\\{ x \\middle| x > 0 \\right\\}" "f\\colon A \\to B"
                 "\\begin{cases} 1 & x \\ge 0 \\\\ 0 & \\text{otherwise} \\end{cases}"
                 "\\lim_{n \\to \\infty} \\frac{1}{n} = 0" "50\\% \\cdot \\#S"]]
      (is (nil? (sanitize/tex-problem tex true)) tex)))
  (testing "TASK-215 re-review: an environment's spec argument is held to a plain shape --
            a *{n}{...} repeat hangs pdflatex, and @{} !{} >{} <{} p{} carry arbitrary TeX"
    (doseq [tex ["\\begin{array}{*{100000000}{c}}x\\end{array}"
                 "\\begin{array}{*{10000}{*{10000}{c}}}x\\end{array}"
                 "\\begin{array}{@{}c@{}}x\\end{array}"
                 "\\begin{array}{!{x}c}x\\end{array}"
                 "\\begin{array}{>{x}c<{y}}x\\end{array}"
                 "\\begin{array}{p{1cm}}x\\end{array}"
                 "\\begin{array}[q]{c}x\\end{array}"
                 "\\begin{array} x\\end{array}"
                 "\\begin{alignedat}{100000}x\\end{alignedat}"
                 "\\begin{alignedat}{*{9}{2}}x\\end{alignedat}"]]
      (is (some-> (sanitize/tex-problem tex true) (str/starts-with? "\\begin{")) tex))
    (doseq [tex ["\\begin{array}{cc|l} a & b & c \\end{array}"
                 "\\begin{array}[t]{ c c } a & b \\end{array}"
                 "\\left( \\begin{array}{c} x \\\\ y \\end{array} \\right)"
                 "\\begin{alignedat}{2} a &= b & c &= d \\end{alignedat}"]]
      (is (nil? (sanitize/tex-problem tex true)) tex)))
  (testing "a string that is not math may hold no control sequence at all"
    (is (nil? (sanitize/tex-problem "pic.png" false)))
    (is (nil? (sanitize/tex-problem "refs{2020}" false)))
    (is (= "\\alpha" (sanitize/tex-problem "\\alpha" false)))
    (is (= "\\input" (sanitize/tex-problem "x\\input{/etc/passwd}" false)))))

(deftest reviewer-bypasses-refused-by-pdflatex-test
  (testing "TASK-215: each reviewer bypass really does reach pdflatex as TeX (so the
            refusal is not of something inert), and an allow-listed formula compiles"
    (let [dir (str (Files/createTempDirectory "haselnuss-tex-problem" (make-array FileAttribute 0)))
          compile! (fn [math]
                     (spit (io/file dir "t.tex")
                           (str "\\documentclass{article}\\usepackage{amsmath,amssymb}"
                                "\\begin{document}\\(" math "\n\\)\\end{document}\n"))
                     (spit (io/file dir "secret.tex") "SECRETCONTENT")
                     (shell/sh "timeout" "20" "pdflatex" "-interaction=nonstopmode"
                               "-halt-on-error" "t.tex" :dir dir))]
      (is (zero? (:exit (compile! "\\begin{cases} 1 & x \\ge 0 \\\\ 0 & \\text{otherwise} \\end{cases}"))))
      (is (zero? (:exit (compile! "\\left( \\begin{array}{cc|l} a & b & c \\\\ d & e & f \\end{array} \\right)"))))
      (testing "\\UseName{input} reads secret.tex"
        (compile! "\\UseName{input}{secret}")
        (is (str/includes? (str/replace (:out (shell/sh "pdftotext" "t.pdf" "-" :dir dir)) #"\s" "")
                           "SECRETCONTENT")))
      (testing "expl3 writes a file"
        (compile! (str "\\ExplSyntaxOn \\iow_new:N \\g_x_iow \\iow_open:Nn \\g_x_iow {pwned.txt}"
                       " \\iow_now:Nn \\g_x_iow {owned} \\ExplSyntaxOff"))
        (is (.exists (io/file dir "pwned.txt")))))))

(deftest untrusted-fragment-test
  (testing "TASK-215: TeX the directive itself carries is the document's own, and exempt"
    (let [math {:t :math-inline :tex "\\def\\x{1}\\x"}
          directive {:t :directive :name "w" :blocks [{:t :para :inlines [math]}]}]
      (is (= {:nodes [math] :diagnostics []}
             (sanitize/untrusted-fragment :inlines [math] directive)))
      (is (= {:tex {:string "\\def\\y{1}" :primitive "\\def"}}
             (sanitize/untrusted-fragment :inlines [{:t :math-inline :tex "\\def\\y{1}"}]
                                          directive)))))
  (testing "but escaped prose the directive holds is not: a code sample's \\input moved
            into a math node is refused (TASK-215 review)"
    (let [directive {:t :directive :name "w"
                     :blocks [{:t :para :inlines [{:t :code :text "\\input{/etc/hostname}"}
                                                  {:t :str :text "\\input{/etc/hostname}"}]}
                              {:t :code-block :text "\\input{/etc/hostname}"}]}]
      (is (= {:tex {:string "\\input{/etc/hostname}" :primitive "\\input"}}
             (sanitize/untrusted-fragment :inlines [{:t :math-inline :tex "\\input{/etc/hostname}"}]
                                          directive)))))
  (testing "TASK-215 re-review: an image path or citation key, spliced into a command
            argument as written, may not hold a character that alters compilation"
    (doseq [[nodes c] [[[{:t :image :src "x%" :alt "" :attr {:classes [] :props {}}}] "%"]
                       [[{:t :image :src "a#b.png" :alt "" :attr {:classes [] :props {}}}] "#"]
                       [[{:t :cite :items [{:key "a&b"}] :text []}] "&"]
                       [[{:t :cite :items [{:key "a~b"}] :text []}] "~"]]]
      (is (= c (get-in (sanitize/untrusted-fragment :inlines nodes {:t :directive})
                       [:tex :primitive]))
          (pr-str nodes)))
    (let [nodes [{:t :image :src "pics/my_pic-2.png" :alt "" :attr {:classes [] :props {}}}
                 {:t :cite :items [{:key "knuth:1984"}] :text []}]]
      (is (nil? (:tex (sanitize/untrusted-fragment :inlines nodes {:t :directive}))))))
  (testing "an image's alt is never TeX: LaTeX does not render it, so neither checked nor
            counted as document-authored TeX"
    (let [img {:t :image :src "p.png" :alt "\\input{x}" :attr {:classes [] :props {}}}]
      (is (nil? (:tex (sanitize/untrusted-fragment :inlines [img] {:t :directive}))))
      (is (= "\\input"
             (get-in (sanitize/untrusted-fragment :inlines [{:t :math-inline :tex "\\input{x}"}]
                                                  {:t :directive :blocks [{:t :para :inlines [img]}]})
                     [:tex :primitive])))))
  (testing "escaped text -- a :str, :code or :code-block's own :text -- is not TeX"
    (let [nodes [{:t :str :text "\\input{x}"} {:t :code :text "\\input{x}"}]]
      (is (= {:nodes nodes :diagnostics []}
             (sanitize/untrusted-fragment :inlines nodes {:t :directive}))))
    (let [nodes [{:t :code-block :text "\\input{x}" :attr {:id nil :classes [] :props {}}}]]
      (is (= {:nodes nodes :diagnostics []}
             (sanitize/untrusted-fragment :blocks nodes {:t :directive})))))
  (testing "the document pass's rules apply to the fragment, image fallback included"
    (let [{:keys [nodes diagnostics]}
          (sanitize/untrusted-fragment :inlines [{:t :image :src "data:x" :alt "pic"
                                                  :attr {:id nil :classes [] :props {}}}]
                                       {:t :directive})]
      (is (= [{:t :str :text "pic"}] nodes))
      (is (= [:unsafe-url] (map :type diagnostics))))))

;; ---------------------------------------------------------------------------
;; TASK-219: the document's own TeX in the LaTeX build

(defn- parse [src]
  (parser/parse src {:source "doc.hdoc"}))

(defn- vet-error
  "The `ex-info` `vet-untrusted-document` throws for `src`, or nil."
  [src]
  (try (sanitize/vet-untrusted-document (parse src)) nil
       (catch clojure.lang.ExceptionInfo e e)))

(deftest untrusted-document-test
  (testing "the TASK-215 bypass set is refused in the document's own math, inline and
            display, naming file:line and the token"
    (doseq [[tex token] (concat reviewer-bypasses
                                [["\\input{/etc/passwd}" "\\input"]
                                 ["\\begin{array}{*{100000000}{c}}x\\end{array}"
                                  "\\begin{array}{*{100000000}{c}}x\\end{a"]])]
      (doseq [src [(str "Intro.\n\nSome $" tex "$ here.\n")
                   (str "Intro.\n\nSome\n\n$$\n" tex "\n$$\n")]]
        (let [e (vet-error src)]
          (is (= ::sanitize/untrusted-document-tex (:type (ex-data e))) src)
          (is (= token (:token (ex-data e))) src)
          (is (= "doc.hdoc" (:source-file (ex-data e))) src)
          (is (str/starts-with? (str (ex-message e)) (str "doc.hdoc:" (:source-line (ex-data e))
                                                          ": document math uses TeX " token))
              src)))))
  (testing "the line is the math's own, inside a paragraph, a list and a directive"
    (doseq [[src line] [["a\n\nfirst\nsecond $\\input{x}$\n" 4]
                        ["a\n\n- one\n- two *em $\\input{x}$*\n" 4]
                        ["a\n\n:::{note}\nb\n\nc $\\input{x}$\n:::\n" 6]]]
      (is (= line (:source-line (ex-data (vet-error src)))) src)))
  (testing "an image path, a citation key and an attribute value are held to the
            non-math rules"
    (is (= "%" (:token (ex-data (vet-error "a\n\n![p](x%.png)\n")))))
    (is (= "\\input" (:token (ex-data (vet-error "a\n\n[x]{.c data-x=\"\\input{y}\"}\n")))))
    (is (str/includes? (ex-message (vet-error "a\n\n![p](x%.png)\n"))
                       "an image path or citation key uses TeX %")))
  (testing "ordinary mathematics, images and attributes pass, and the document comes
            back itself"
    (let [d (parse (str "a $x^2 + \\frac{a}{b}$ and $\\sum_{i=1}^{n} \\alpha_i$\n\n"
                        "$$\n\\begin{aligned} a &= b \\\\ c &\\le d \\end{aligned}\n$$\n\n"
                        "![p](pics/a_b-1.png){width=50%}\n"))]
      (is (nil? (sanitize/untrusted-document-problem d)))
      (is (identical? d (sanitize/vet-untrusted-document d))))))

(deftest neutralise-math-links-test
  (testing "\\href keeps its text, \\url is removed, each with a located warning"
    (let [{:keys [document diagnostics]}
          (sanitize/neutralise-math-links
           (parse "a\n\nx $\\href{javascript:alert(1)}{x}$ y $a\\url{javascript:b}$\n"))]
      (is (= ["{x}" "a"] (keep :tex (get-in document [:blocks 1 :inlines]))))
      (is (= [:math-link :math-link] (map :type diagnostics)))
      (is (= ["\\href{javascript:alert(1)}" "\\url{javascript:b}"] (map :link diagnostics)))
      (is (every? #(str/starts-with? (:message %) "doc.hdoc:3: Dropped the link ") diagnostics))))
  (testing "the spellings of each command's arguments"
    (doseq [[tex expected] [["\\href[pdfnewwindow]{javascript:x}{t}" "{t}"]
                            ["\\href {javascript:x} {t}" " {t}"]
                            ["\\href{a{b}c}{\\mathrm{t}}" "{\\mathrm{t}}"]
                            ["\\href\\x{t}" "{t}"]
                            ["\\href[{]}]{u}{t}" "{t}"]
                            ["\\hyperref{javascript:x//}{}{}{t}" "{t}"]
                            ["a\\hyperref {u} {c} {n} {t}b" "a {t}b"]
                            ["\\hyperref[sec:a]{t}" "\\hyperref[sec:a]{t}"]
                            ["\\url|javascript:{x|+1" "+1"]
                            ["\\alpha\\url{u}b" "\\alpha b"]
                            ["\\text{see \\url{http://e.org/%20}}" "\\text{see }"]
                            ["\\href" ""]]]
      (let [{:keys [document]} (sanitize/neutralise-math-links
                                {:meta {} :blocks [{:t :math-block :tex tex}]})]
        (is (= expected (get-in document [:blocks 0 :tex])) tex))))
  (testing "a longer control word, a line break before url, and a document with no link
            are left alone -- the document itself comes back"
    (let [d (parse "a\n\n$\\hrefx{1} \\\\url{x}$ and $\\alpha$\n\n$$\nb\n$$\n")
          {:keys [document diagnostics]} (sanitize/neutralise-math-links d)]
      (is (identical? d document))
      (is (= [] diagnostics)))))

(defn- pdf-uris
  "Every `/URI` link target in the PDF `pdflatex` makes of `tex`, read
  from the PDF itself after `qpdf` expands it (TASK-210's
  `haselnuss.emit.latex-test/pdf-uris`), or nil when it does not compile."
  [tex]
  (let [dir (str (Files/createTempDirectory "haselnuss-math-link" (make-array FileAttribute 0)))]
    (spit (io/file dir "t.tex") tex)
    (when (zero? (:exit (shell/sh "timeout" "60" "pdflatex" "-interaction=nonstopmode"
                                  "-halt-on-error" "t.tex" :dir dir)))
      (when (zero? (:exit (shell/sh "qpdf" "--qdf" "--decode-level=all" "t.pdf" "x.pdf" :dir dir)))
        (mapv second (re-seq #"/URI \(([^)]*)\)" (slurp (io/file dir "x.pdf"))))))))

(deftest math-link-pdf-test
  (let [linking (str "A $\\href{javascript:alert(1)}{x}$, $\\hyperref{javascript:alert(2)//}{}{}{y}$"
                     " and [ok](https://e.org/).\n")
        src (str linking "\nAnd $\\url{javascript:alert(3)}$.\n")]
    (testing "TASK-219: emitted as written, document math really does make javascript:
              links in the PDF -- \\href and the four-argument \\hyperref alike -- so
              dropping them is not cosmetic (\\url in math does not even compile, so
              the same source with it yields no PDF at all)"
      (let [uris (pdf-uris (latex/emit-document (parser/parse linking)))]
        (is (= 2 (count (filter #(str/starts-with? % "javascript:alert") uris))) (pr-str uris))
        (is (some #{"https://e.org/"} uris) (pr-str uris)))
      (is (nil? (pdf-uris (latex/emit-document (parser/parse src))))))
    (testing "the LaTeX build drops all three, keeps the text, warns, and still links an
              ordinary link"
      (let [{:keys [output diagnostics]} (build src "latex")]
        (is (= ["https://e.org/"] (pdf-uris output)))
        (is (str/includes? output "\\({x}"))
        (is (str/includes? output "\\({y}"))
        (is (= 3 (count (filter #(= :math-link (:type %)) diagnostics))))))
    (testing "html is untouched: MathJax already leaves \\href undefined (TASK-217)"
      (let [{:keys [output diagnostics]} (build src "html")]
        (is (str/includes? output "\\href{javascript:alert(1)}{x}"))
        (is (not-any? #(= :math-link (:type %)) diagnostics))))))

(deftest bibliography-address-pdf-test
  (testing "TASK-219 review: a CSL-JSON URL with an unsafe scheme is left out of the
            native-mode .bib, so BibTeX's \\url makes no javascript: link, with a warning;
            computed mode stays inert (its DOI is prefixed with https://doi.org/)"
    (let [dir (str (Files/createTempDirectory "haselnuss-bib-link" (make-array FileAttribute 0)))
          sh! (fn [& args] (apply shell/sh "timeout" "60" (concat args [:dir dir])))
          uris (fn [base]
                 (sh! "qpdf" "--qdf" "--decode-level=all" (str base ".pdf") "x.pdf")
                 (mapv second (re-seq #"/URI \(([^)]*)\)" (slurp (io/file dir "x.pdf")))))]
      (spit (io/file dir "refs.json")
            (str "[{\"id\":\"evil\",\"type\":\"article-journal\",\"title\":\"Evil\","
                 "\"author\":[{\"family\":\"X\",\"given\":\"Y\"}],\"issued\":{\"date-parts\":[[2020]]},"
                 "\"URL\":\"javascript:alert(9)\",\"DOI\":\"javascript:alert(8)\"},"
                 "{\"id\":\"good\",\"type\":\"article-journal\",\"title\":\"Good\","
                 "\"author\":[{\"family\":\"Z\",\"given\":\"W\"}],\"issued\":{\"date-parts\":[[2021]]},"
                 "\"URL\":\"https://e.org/x\",\"DOI\":\"10.1/abc\"}]"))
      (spit (io/file dir "doc.hdoc") "---\nbibliography: refs.json\n---\n\nSee @evil and @good.\n")
      (let [{:keys [diagnostics]} (cli/build {:input (str (io/file dir "doc.hdoc")) :target "latex"})
            bib (slurp (io/file dir "doc.bib"))]
        (is (not (str/includes? bib "javascript:")) bib)
        (is (str/includes? bib "url = {https://e.org/x}"))
        (is (str/includes? bib "doi = {10.1/abc}"))
        (is (some #(str/includes? (str (:message %)) "Dropped the url \"javascript:alert(9)\" of bibliography entry \"evil\"")
                  diagnostics)
            (pr-str diagnostics))
        (is (zero? (:exit (sh! "pdflatex" "-interaction=nonstopmode" "-halt-on-error" "doc.tex"))))
        (sh! "bibtex" "doc")
        (sh! "pdflatex" "-interaction=nonstopmode" "-halt-on-error" "doc.tex")
        (is (zero? (:exit (sh! "pdflatex" "-interaction=nonstopmode" "-halt-on-error" "doc.tex"))))
        (let [native (uris "doc")]
          (is (some #{"https://e.org/x"} native) (pr-str native))
          (is (not-any? #(str/starts-with? % "javascript:") native) (pr-str native))))
      (cli/build {:input (str (io/file dir "doc.hdoc")) :target "latex" :computed-numbers true
                  :output (str (io/file dir "c.tex"))})
      (is (zero? (:exit (sh! "pdflatex" "-interaction=nonstopmode" "-halt-on-error" "c.tex"))))
      (let [computed (uris "c")]
        (is (some #{"https://e.org/x"} computed) (pr-str computed))
        (is (not-any? #(str/starts-with? % "javascript:") computed) (pr-str computed))))))
