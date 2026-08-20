(ns haselnuss.emit.html-test
  "Fixtures for `haselnuss.emit.html` (TASK-19, extended by TASK-20): the
  HTML emitter core. TASK-19's AC #1 (every covered block/inline node
  produces valid, well-formed HTML) is checked both by substring
  assertions (mirroring `haselnuss.extensions.collapsable-test`'s own
  established style) and by `well-formed?`, a dependency-free XML-parse
  well-formedness check (this emitter always self-closes void elements
  and escapes entities the same way well-formed XHTML would, so a
  document that parses as XML is a sufficiently strict proxy for \"valid,
  well-formed HTML\" without a dedicated HTML validator on the
  classpath). TASK-19's AC #2 (section nesting) and AC #3 (footnote
  markers/list) each get their own dedicated tests, as do TASK-20's own
  four ACs: Figure/Table numbering+caption (AC #1,
  `figure-table-rendering-test`), inline/display math (AC #2,
  `math-rendering-test`), a resolved/dangling CrossRef (AC #3,
  `cross-ref-rendering-test`), and the generated bibliography section
  plus in-text-citation linking (AC #4, `cite-and-bibliography-rendering-
  test`)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.emit.html :as html]
            [haselnuss.extensions.collapsable :as collapsable]
            [haselnuss.json :as json]
            [haselnuss.registry :as registry]
            [haselnuss.resolver :as resolver])
  (:import (java.io StringReader)
           (javax.xml.parsers DocumentBuilderFactory)
           (org.xml.sax InputSource)))

(defn- body-of
  "The `<body>` content of an emitted document.

  Every \"this markup is absent\" assertion needs it since TASK-43: the
  default stylesheet inlined in `<head>` names the very classes and
  elements those assertions rule out (`section-number`, `figcaption`,
  `footnotes`, `math-number`), so searching the whole document finds
  them in the CSS and says nothing about the markup."
  [html]
  (second (re-find #"(?s)<body>(.*)</body>" html)))

(defn- well-formed?
  "True if document string `html` parses without error as XML, false
  otherwise -- see this namespace's own docstring for why that is a
  sufficient proxy for well-formed HTML here."
  [html]
  (try
    (let [builder (.newDocumentBuilder (DocumentBuilderFactory/newInstance))]
      (.parse builder (InputSource. (StringReader. html)))
      true)
    (catch Exception _e false)))

(defn- str-inline
  [text]
  {:t :str :text text})

(defn- para
  [& inlines]
  {:t :para :inlines (vec inlines)})

(def ^:private empty-attr
  {:classes [] :props {}})

(deftest full-document-test
  (testing "AC #1: a document exercising every covered block/inline node
            emits one complete, valid, well-formed HTML document"
    (let [document
          {:meta {:title [(str-inline "My Document")] :lang "en"}
           :blocks
           [{:t :section :level 1 :heading [(str-inline "Intro")] :attr empty-attr
             :blocks
             [(para (str-inline "Plain text with ")
                    {:t :emph :inlines [(str-inline "emphasis")]}
                    (str-inline ", ")
                    {:t :strong :inlines [(str-inline "strong")]}
                    (str-inline ", ")
                    {:t :strike :inlines [(str-inline "strike")]}
                    (str-inline ", ")
                    {:t :small-caps :inlines [(str-inline "small caps")]}
                    (str-inline ", H")
                    {:t :sub :inlines [(str-inline "2")]}
                    (str-inline "O and E=mc")
                    {:t :sup :inlines [(str-inline "2")]}
                    (str-inline ", ")
                    {:t :code :text "(+ 1 2)"}
                    {:t :line-break}
                    (str-inline "a link:")
                    {:t :link :target "https://example.com" :inlines [(str-inline "example")] :attr empty-attr}
                    (str-inline " and a span: ")
                    {:t :span :inlines [(str-inline "spanned")] :attr {:classes ["hl"] :props {}}}
                    {:t :note :blocks [(para (str-inline "A footnote."))]})
              (para {:t :image :src "pic.png" :alt "a picture" :attr empty-attr})
              {:t :list :ordered false :tight true :attr empty-attr
               :items [[(para (str-inline "item one"))]
                       [(para (str-inline "item two"))]]}
              {:t :code-block :lang "clojure" :text "(+ 1 2)" :attr empty-attr}
              {:t :block-quote :blocks [(para (str-inline "Quoted wisdom."))]}]}]}
          out (html/emit-document document)]
      (is (well-formed? out) (str "expected well-formed HTML, got:\n" out))
      (is (str/starts-with? out "<!DOCTYPE html>"))
      (is (re-find #"<html lang=\"en\">" out))
      (is (re-find #"<title>My Document</title>" out))
      (is (re-find #"<h1>Intro</h1>" out))
      (is (re-find #"<em>emphasis</em>" out))
      (is (re-find #"<strong>strong</strong>" out))
      (is (re-find #"<s>strike</s>" out))
      (is (re-find #"<span style=\"font-variant: small-caps;\">small caps</span>" out))
      (is (re-find #"H<sub>2</sub>O" out))
      (is (re-find #"mc<sup>2</sup>" out))
      (is (re-find #"<code>\(\+ 1 2\)</code>" out))
      (is (re-find #"<br/>" out))
      (is (re-find #"<a href=\"https://example.com\">example</a>" out))
      (is (re-find #"<span class=\"hl\">spanned</span>" out))
      (is (re-find #"<img src=\"pic.png\" alt=\"a picture\"/>" out))
      (is (re-find #"<ul><li>item one</li><li>item two</li></ul>" out))
      (is (re-find #"<pre><code class=\"language-clojure\">\(\+ 1 2\)</code></pre>" out))
      (is (re-find #"<blockquote><p>Quoted wisdom.</p></blockquote>" out))
      (is (re-find #"<sup id=\"fnref1\"><a href=\"#fn1\">1</a></sup>" out))
      (is (re-find #"<section class=\"footnotes\">" out))
      (is (re-find #"<li id=\"fn1\"><p>A footnote.</p><a href=\"#fnref1\">" out)))))

(deftest stylesheet-opt-test
  (let [document {:meta {} :blocks [(para {:t :small-caps :inlines [(str-inline "sc")]})]}]
    (testing "TASK-43: the default stylesheet is inlined in <head>, and
              the document is still well-formed with it there -- which is
              why the sheet uses no > child combinators, valid in HTML
              but not inside an XML-parsed <style>"
      (let [out (html/emit-document document)]
        (is (well-formed? out) (str "expected well-formed HTML, got:\n" out))
        (is (str/includes? out "<style>"))
        (is (str/includes? out "body{max-width:"))))
    (testing "AC #4: :none omits it entirely, for a caller embedding this
              output in a page that brings its own CSS"
      (let [out (html/emit-document document {:stylesheet :none})]
        (is (not (str/includes? out "<style>")))
        (is (well-formed? out))))
    (testing "and a non-blank string replaces it, so a caller can
              substitute a sheet rather than only suppress one"
      (let [out (html/emit-document document {:stylesheet "body{color:red}"})]
        (is (str/includes? out "<style>body{color:red}</style>"))
        (is (well-formed? out))
        (is (not (str/includes? out "max-width")))))
    (testing "anything else throws rather than being used as CSS: a typo
              used to emit <style>:defualt</style>, an unstyled document
              with junk in its head and nothing said about it (found by
              review)"
      (doseq [bad [:defualt "" "   " 42]]
        (let [e (is (thrown? clojure.lang.ExceptionInfo
                             (html/emit-document document {:stylesheet bad}))
                    (pr-str bad))]
          (is (= ::html/invalid-stylesheet (:type (ex-data e))) (pr-str bad))))
      (testing "while false is accepted as a spelling of :none, since a
                caller writing (assoc opts :stylesheet false) means it"
        (is (not (str/includes? (html/emit-document document {:stylesheet false}) "<style>")))))
    (testing "AC #3: small-caps keeps its inline style in every case. It
              is the one inline style that carries MEANING rather than
              presentation -- without it the text is not small-capped,
              it is simply different text -- so opting out of styling
              must not cost it"
      (doseq [opts [{} {:stylesheet :none} {:stylesheet "body{color:red}"}]]
        (is (str/includes? (body-of (html/emit-document document opts))
                           "font-variant: small-caps")
            (pr-str opts))))))

(deftest section-nesting-test
  (testing "AC #2: nested Sections in the AST produce correctly nested
            <section> elements, each with its own <hN> matching the
            Section's own :level"
    (let [document
          {:meta {}
           :blocks
           [{:t :section :level 1 :heading [(str-inline "Chapter")] :attr empty-attr
             :blocks
             [(para (str-inline "top"))
              {:t :section :level 2 :heading [(str-inline "Section")] :attr empty-attr
               :blocks
               [(para (str-inline "middle"))
                {:t :section :level 3 :heading [(str-inline "Subsection")] :attr empty-attr
                 :blocks [(para (str-inline "bottom"))]}]}]}]}
          out (html/emit-document document)]
      (is (well-formed? out))
      (is (re-find #"<section><h1>Chapter</h1><p>top</p><section><h2>Section</h2><p>middle</p><section><h3>Subsection</h3><p>bottom</p></section></section></section>"
                   out))))
  (testing "a Section :level beyond HTML5's own h1-h6 range still renders,
            clamped to h6, rather than producing an invalid tag name"
    (let [document {:meta {} :blocks [{:t :section :level 9 :heading [(str-inline "Deep")]
                                       :attr empty-attr :blocks []}]}
          out (html/emit-document document)]
      (is (well-formed? out))
      (is (re-find #"<h6>Deep</h6>" out)))))

(deftest section-number-test
  (let [document {:meta {}
                  :blocks [{:t :section :level 1 :heading [(str-inline "Why hazel")]
                            :attr {:id "sec:why" :classes [] :props {}}
                            :blocks [{:t :section :level 2 :heading [(str-inline "Aside")]
                                      :attr empty-attr :blocks []}
                                     {:t :section :level 2 :heading [(str-inline "Spacing")]
                                      :attr {:id "sec:spacing" :classes [] :props {}}
                                      :blocks []}]}]}
        labels {"sec:why" {:number "1" :text "Section 1"}
                "sec:spacing" {:number "1.1" :text "Section 1.1"}}
        out (html/emit-document document {:labels labels})]
    (testing "TASK-41 AC #1: a Section the resolver numbered prints that
              number in its own heading. Before this, a document could
              say \"See Section 1\" above a heading reading only \"Why
              hazel\" -- a reference to a number appearing nowhere"
      (is (well-formed? out))
      (is (str/includes? out "<h1><span class=\"section-number\">1</span> Why hazel</h1>"))
      (is (str/includes? out "<h2><span class=\"section-number\">1.1</span> Spacing</h2>")))
    (testing "AC #3: an unnumbered Section prints no number, and needs
              nothing to fill a gap: the number is a prefix, not a
              column, so its absence just starts the heading further left"
      (is (str/includes? out "<h2>Aside</h2>"))
      (is (not (str/includes? (body-of out) "<h2><span class=\"section-number\"></span>")))
      (testing "and \"unnumbered\" really means absent from :labels, so
                an id whose prefix is no recognized kind is unnumbered
                too -- the branch the docstring names, which having only
                an id-less fixture left untested (found by review)"
        (let [document {:meta {} :blocks [{:t :section :level 1
                                           :heading [(str-inline "Appendix")]
                                           :attr {:id "foo:bar" :classes [] :props {}}
                                           :blocks []}]}
              out (html/emit-document document {:labels labels})]
          (is (str/includes? out "<h1>Appendix</h1>"))
          (is (not (str/includes? (body-of out) "section-number"))))))
    (testing "and with no :labels at all -- an emitter called without the
              resolver -- every heading is bare, exactly as before"
      (is (not (str/includes? (body-of (html/emit-document document)) "section-number"))))))

(deftest footnote-test
  (testing "AC #3: footnote markers render as linked footnote references,
            and their content renders in a trailing footnote list, linked
            back to the marker"
    (let [document {:meta {}
                    :blocks [(para (str-inline "See.")
                                   {:t :note :blocks [(para (str-inline "First note."))]}
                                   (str-inline " And.")
                                   {:t :note :blocks [(para (str-inline "Second note."))]})]}
          out (html/emit-document document)]
      (is (well-formed? out))
      (is (re-find #"<sup id=\"fnref1\"><a href=\"#fn1\">1</a></sup>" out))
      (is (re-find #"<sup id=\"fnref2\"><a href=\"#fn2\">2</a></sup>" out))
      (is (re-find #"<li id=\"fn1\"><p>First note\.</p><a href=\"#fnref1\">" out))
      (is (re-find #"<li id=\"fn2\"><p>Second note\.</p><a href=\"#fnref2\">" out))
      (is (< (.indexOf out "<li id=\"fn1\"") (.indexOf out "<li id=\"fn2\"")))))
  (testing "a footnote nested inside another footnote's own content still
            numbers correctly -- strictly after its enclosing footnote,
            in marker-encounter order -- and both appear in the list"
    (let [inner {:t :note :blocks [(para (str-inline "Inner."))]}
          outer {:t :note :blocks [(para (str-inline "Outer.") inner)]}
          document {:meta {} :blocks [(para (str-inline "See.") outer)]}
          out (html/emit-document document)]
      (is (well-formed? out))
      (is (re-find #"<sup id=\"fnref1\">" out))
      (is (re-find #"<li id=\"fn1\"><p>Outer\.<sup id=\"fnref2\"><a href=\"#fn2\">2</a></sup></p>" out))
      (is (re-find #"<li id=\"fn2\"><p>Inner\.</p>" out))))
  (testing "no footnotes at all -> no footnote section rendered"
    (let [out (html/emit-document {:meta {} :blocks [(para (str-inline "Plain."))]})]
      (is (well-formed? out))
      (is (not (re-find #"footnotes" (body-of out)))))))

(deftest list-rendering-test
  (testing "a tight list's single-Para items render without a <p> wrapper"
    (let [document {:meta {}
                    :blocks [{:t :list :ordered true :tight true :attr empty-attr
                              :items [[(para (str-inline "one"))] [(para (str-inline "two"))]]}]}
          out (html/emit-document document)]
      (is (well-formed? out))
      (is (re-find #"<ol><li>one</li><li>two</li></ol>" out))))
  (testing "a loose list's items keep their <p> wrapper"
    (let [document {:meta {}
                    :blocks [{:t :list :ordered false :tight false :attr empty-attr
                              :items [[(para (str-inline "one"))]]}]}
          out (html/emit-document document)]
      (is (well-formed? out))
      (is (re-find #"<ul><li><p>one</p></li></ul>" out))))
  (testing "a tight list item with a Para immediately followed by a
            nested sub-list (the most common way people write nested
            markdown lists) renders the Para without a <p> wrapper too --
            CommonMark's tight-list rule unwraps every direct Para in an
            item, not only single-Para items (reference: flexmark's own
            HtmlRenderer for \"- a\\n  - nested\\n- b\\n\" produces
            <li>a<ul><li>nested</li></ul></li>, never <li><p>a</p>...)"
    (let [nested {:t :list :ordered false :tight true :attr empty-attr
                  :items [[(para (str-inline "nested"))]]}
          document {:meta {}
                    :blocks [{:t :list :ordered false :tight true :attr empty-attr
                              :items [[(para (str-inline "outer")) nested]]}]}
          out (html/emit-document document)]
      (is (well-formed? out))
      (is (re-find #"<li>outer<ul><li>nested</li></ul></li>" out))))
  (testing "a tight item with more than one direct Para (constructible
            directly as AST/JSON data even though the markdown parser
            cannot produce it, since a blank line between two paragraphs
            would make the list loose) unwraps every one of them, not
            just the first"
    (let [document {:meta {}
                    :blocks [{:t :list :ordered false :tight true :attr empty-attr
                              :items [[(para (str-inline "first"))
                                       (para (str-inline "second"))]]}]}
          out (html/emit-document document)]
      (is (well-formed? out))
      (is (re-find #"<li>firstsecond</li>" out))
      (is (not (re-find #"<p>" out))))))

(deftest code-block-test
  (testing "a CodeBlock with no :lang omits the class attribute entirely"
    (let [document {:meta {} :blocks [{:t :code-block :text "plain" :attr empty-attr}]}
          out (html/emit-document document)]
      (is (well-formed? out))
      (is (re-find #"<pre><code>plain</code></pre>" out)))))

(deftest attr-rendering-test
  (testing "Attr id/classes/props render as literal HTML attributes on the
            owning element, props passed through verbatim (e.g. lang=en)"
    (let [document {:meta {}
                    :blocks [{:t :section :level 1 :heading [(str-inline "H")]
                              :attr {:id "sec:intro" :classes ["note" "warning"] :props {"lang" "en"}}
                              :blocks []}]}
          out (html/emit-document document)]
      (is (well-formed? out))
      (is (re-find #"<section id=\"sec:intro\" class=\"note warning\" lang=\"en\">" out))))
  (testing "HTML-significant characters in attribute values are escaped"
    (let [document {:meta {}
                    :blocks [(para {:t :link :target "x\"onclick=\"y" :inlines [(str-inline "l")]
                                    :attr empty-attr})]}
          out (html/emit-document document)]
      (is (well-formed? out))
      (is (re-find #"href=\"x&quot;onclick=&quot;y\"" out))))
  (testing "HTML-significant characters in a props map's KEY are escaped
            too, not just its value -- constructible directly as AST/JSON
            data (haselnuss.ast's own schema places no identifier
            restriction on a props key) even though today's .hdoc parser
            happens to reject a `\"` inside an attribute-group key. A `\"`
            in a key can never produce a well-formed attribute *name* --
            entities are only recognised in attribute/text *values*, never
            in Name position, confirmed empirically against
            javax.xml.parsers -- so this only asserts the actual security
            property `escape-html` on the key restores: the embedded `\"`
            no longer terminates the key and reopens a fresh, attacker-
            named attribute (`onmouseover=\"alert(1)` as a clean,
            executable attribute/value pair), the way it did before this
            fix."
    (let [document {:meta {}
                    :blocks [{:t :section :level 1 :heading [(str-inline "H")]
                              :attr {:classes [] :props {"x\" onmouseover=\"alert(1)" "y"}}
                              :blocks []}]}
          out (html/emit-document document)]
      (is (re-find #"<section x&quot; onmouseover=&quot;alert\(1\)=\"y\">" out))
      (is (not (re-find #"<section x\" onmouseover=\"alert\(1\)" out))))))

(deftest directive-dispatch-test
  (testing "a :directive Block with a native :html renderer registered
            dispatches to it -- proving the registry mechanism established
            by TASK-16/18 reaches all the way through the real emitter"
    (let [reg (registry/register {} collapsable/extension)
          d (collapsable/make "Click" [(para (str-inline "Hidden"))] {:id "collapse:1" :classes [] :props {}})
          document {:meta {} :blocks [d]}
          out (html/emit-document document {:registry reg})]
      (is (well-formed? out))
      (is (re-find #"<details id=\"collapse:1\"" out))
      (is (re-find #"<summary>Click</summary>" out))
      (is (re-find #"<p>Hidden</p>" out))))
  (testing "a :directive with no registry supplied raises a documented
            ex-info instead of silently dropping it"
    (let [d (collapsable/make "Click" [])
          document {:meta {} :blocks [d]}]
      (try
        (html/emit-document document)
        (is false "expected emit-document to throw")
        (catch clojure.lang.ExceptionInfo e
          (is (= ::html/unsupported-block (:type (ex-data e))))))))
  (testing "a :directive whose name has no registry entry at all also
            raises, naming the directive"
    (let [reg (registry/register {} collapsable/extension)
          d {:t :directive :name "not-registered" :blocks [] :attr empty-attr}
          document {:meta {} :blocks [d]}]
      (try
        (html/emit-document document {:registry reg})
        (is false "expected emit-document to throw")
        (catch clojure.lang.ExceptionInfo e
          (is (= ::html/unsupported-block (:type (ex-data e)))))))))

(deftest unsupported-node-test
  (testing "with :thematic-break and :include both claimed by TASK-37
            (see thematic-break-test/include-test), every Block variant
            haselnuss.ast defines is now handled -- so this asserts the
            fallback still exists for a node outside the schema entirely,
            rather than naming a real variant that no longer belongs here"
    (try
      (html/emit-document {:meta {} :blocks [{:t :not-a-real-block}]})
      (is false "expected emit-document to throw for an unknown block type")
      (catch clojure.lang.ExceptionInfo e
        (is (= ::html/unsupported-block (:type (ex-data e))))))))

(deftest thematic-break-test
  (testing "TASK-37 AC #1: a ThematicBreak Block renders as <hr/>"
    (let [document {:meta {} :blocks [(para (str-inline "Before"))
                                      {:t :thematic-break}
                                      (para (str-inline "After"))]}
          out (html/emit-document document)]
      (is (well-formed? out))
      (is (re-find #"<p>Before</p><hr/><p>After</p>" out)))))

(deftest include-test
  (testing "TASK-37 AC #3: an Include Block that reached an emitter
            unexpanded has no target content to render, so it raises a
            dedicated diagnostic naming the src and the real cause, not
            the generic unsupported-block it used to share with
            genuinely out-of-scope node types. Since TASK-38 the cause
            is no longer 'expansion does not exist' but 'this AST did
            not come through a pipeline that runs it' -- an AST built
            through haselnuss.json, or a resolve-document call with no
            :includes loader"
    (try
      (html/emit-document {:meta {} :blocks [{:t :include :src "other.hdoc"}]})
      (is false "expected emit-document to throw")
      (catch clojure.lang.ExceptionInfo e
        (is (= ::html/unresolved-include (:type (ex-data e))))
        (is (str/includes? (ex-message e) "other.hdoc"))
        (is (str/includes? (ex-message e) "it was never expanded"))
        (is (str/includes? (ex-message e) "expand-includes"))))))

(deftest figure-table-rendering-test
  (testing "AC #1: a Figure with a resolver-computed number (:labels)
            renders that number ahead of its authored caption"
    (let [figure {:t :figure
                  :content {:t :para :inlines [{:t :image :src "pic.png" :alt "a" :attr empty-attr}]}
                  :caption [(str-inline "A tree")]
                  :attr {:id "fig:tree" :classes [] :props {}}}
          out (html/emit-document {:meta {} :blocks [figure]}
                                  {:labels {"fig:tree" {:text "Figure 2.1"}}})]
      (is (well-formed? out))
      (is (re-find #"<figure id=\"fig:tree\"><p><img src=\"pic.png\" alt=\"a\"/></p><figcaption>Figure 2\.1: A tree</figcaption></figure>"
                   out))))
  (testing "TASK-56: a SUBLABELLED node -- a subfigure panel -- prints its
            parenthesized letter and a space instead of its full label
            and a colon, because that is verbatim what subcaption prints
            for the same panel inside a LaTeX float. The full 'Figure
            2.1a' stays in the references that point at it"
    (let [panel {:t :figure
                 :content {:t :para :inlines []}
                 :caption [(str-inline "Temperature")]
                 :attr {:id "fig:temp" :classes ["subfigure"] :props {}}}
          bare (assoc panel :caption [] :attr {:id "fig:flow" :classes [] :props {}})
          labels {"fig:temp" {:text "Figure 2.1a" :number "2.1a" :sublabel "a"}
                  "fig:flow" {:text "Figure 2.1b" :number "2.1b" :sublabel "b"}}
          out (html/emit-document {:meta {} :blocks [panel bare]} {:labels labels})]
      (is (well-formed? out))
      (is (re-find #"<figcaption>\(a\) Temperature</figcaption>" out))
      (is (re-find #"<figcaption>\(b\)</figcaption>" out)
          "a panel with no caption text still prints its letter")))
  (testing "no :labels entry for the Figure's own id -> the caption still
            renders, with no number prefix"
    (let [figure {:t :figure :content {:t :para :inlines []}
                  :caption [(str-inline "Untitled")]
                  :attr {:id "fig:x" :classes [] :props {}}}
          out (html/emit-document {:meta {} :blocks [figure]})]
      (is (well-formed? out))
      (is (re-find #"<figcaption>Untitled</figcaption>" out))))
  (testing "neither a resolved number nor an authored caption -> no
            <figcaption> at all, rather than an empty one"
    (let [figure {:t :figure :content {:t :para :inlines []} :caption [] :attr empty-attr}
          out (html/emit-document {:meta {} :blocks [figure]})]
      (is (well-formed? out))
      (is (not (re-find #"figcaption" (body-of out))))))
  (testing "AC #1: a Table with a resolver-computed number renders it
            ahead of its own caption, and its colspec's per-column
            width/align, a cell's own :align override, and a cell's
            :span all render correctly"
    (let [table {:t :table
                 :head {:cells [{:blocks [(para (str-inline "H1"))]}
                                {:blocks [(para (str-inline "H2"))]}]}
                 :rows [{:cells [{:blocks [(para (str-inline "A"))] :span 2}]}]
                 :caption [(str-inline "Numbers")]
                 :colspec [{:align :left :width "20%"} {:align :center}]
                 :attr {:id "tbl:x" :classes [] :props {}}}
          out (html/emit-document {:meta {} :blocks [table]}
                                  {:labels {"tbl:x" {:text "Table 1"}}})]
      (is (well-formed? out))
      (is (re-find #"<table id=\"tbl:x\"><caption>Table 1: Numbers</caption>" out))
      (is (re-find #"<colgroup><col style=\"width: 20%;\"/><col/></colgroup>" out))
      (is (re-find #"<th style=\"text-align: left;\"><p>H1</p></th><th style=\"text-align: center;\"><p>H2</p></th>"
                   out))
      (is (re-find #"<td style=\"text-align: left;\" colspan=\"2\"><p>A</p></td>" out))))
  (testing "a Cell's own :align overrides its column's colspec :align"
    (let [table {:t :table
                 :head {:cells [{:blocks [] :align :right}]}
                 :rows []
                 :caption []
                 :colspec [{:align :left}]
                 :attr empty-attr}
          out (html/emit-document {:meta {} :blocks [table]})]
      (is (well-formed? out))
      (is (re-find #"<th style=\"text-align: right;\">" out)))))

(deftest math-rendering-test
  (testing "AC #2: inline math renders between MathJax's own default
            \\(...\\) delimiters, HTML-escaped"
    (let [out (html/emit-document {:meta {} :blocks [(para {:t :math-inline :tex "x < y & z"})]})]
      (is (well-formed? out))
      (is (re-find #"<span class=\"math inline\">\\\(x &lt; y &amp; z\\\)</span>" out))))
  (testing "AC #2: display math renders between \\[...\\], HTML-escaped,
            with the MathBlock's own Attr on the wrapping <div>"
    (let [block {:t :math-block :tex "a^2 + b^2 < c^2"
                 :attr {:id "eq:pyth" :classes ["highlight"] :props {}}}
          out (html/emit-document {:meta {} :blocks [block]})]
      (is (well-formed? out))
      (is (re-find #"<div id=\"eq:pyth\" class=\"math display highlight\">\\\[a\^2 \+ b\^2 &lt; c\^2\\\]</div>"
                   out))))
  (testing "AC #2: emit-document always includes the MathJax CDN script
            tag in <head>, so math renders correctly once opened in a
            browser -- even a math-free document still gets it (see this
            namespace's own docstring for why unconditionally)"
    (let [out (html/emit-document {:meta {} :blocks [(para (str-inline "no math here"))]})]
      (is (well-formed? out))
      (is (re-find #"<script id=\"MathJax-script\" async=\"async\" src=\"https://cdn\.jsdelivr\.net/npm/mathjax@3/es5/tex-mml-chtml\.js\"></script>"
                   out)))))

(deftest cross-ref-rendering-test
  (testing "AC #3: a resolved CrossRef (haselnuss.resolver/resolve-cross-
            refs has already run, so :target/:text are already set)
            renders as a hyperlink to its target showing the computed
            label"
    (let [cross-ref {:t :cross-ref :label "fig:tree" :target "fig:tree" :text "Figure 3"}
          out (html/emit-document {:meta {} :blocks [(para cross-ref)]})]
      (is (well-formed? out))
      (is (re-find #"<a href=\"#fig:tree\">Figure 3</a>" out))))
  (testing "a dangling CrossRef (:target nil, the resolver's own \"??\"
            placeholder) renders as plain text -- nothing valid to link
            to"
    (let [cross-ref {:t :cross-ref :label "fig:missing" :target nil :text "??"}
          out (html/emit-document {:meta {} :blocks [(para cross-ref)]})]
      (is (well-formed? out))
      (is (re-find #"<p>\?\?</p>" out))
      (is (not (re-find #"<a" out))))))

(deftest cite-and-bibliography-rendering-test
  (let [bibliography {"jones2019" {:id "jones2019" :author [{:family "Jones" :given "Bob"}]
                                   :issued {:date-parts [[2019]]} :title "Another Paper"}
                      "smith2020" {:id "smith2020" :author [{:family "Smith" :given "Ann"}]
                                   :issued {:date-parts [[2020]]} :title "A Paper"}}
        doc {:meta {}
             :blocks [(para {:t :cite :items [{:key "jones2019" :mode :normal}
                                              {:key "smith2020" :mode :normal}]})
                      (para {:t :cite :items [{:key "smith2020" :mode :normal}]})]}
        {:keys [document ordered-keys bibliography-id]} (resolver/resolve-citations doc bibliography)
        out (html/emit-document document {:bibliography-id bibliography-id :ordered-keys ordered-keys})]
    (testing "AC #4: the generated bibliography section renders as
              ordinary Section/List markup, one <li> per distinct
              resolvable citation key"
      (is (well-formed? out))
      (is (re-find #"<section id=\"sec:bibliography\" class=\"bibliography\"><h1>Bibliography</h1>" out))
      (is (re-find #"Jones, B\. \(2019\)\. Another Paper\." out))
      (is (re-find #"Smith, A\. \(2020\)\. A Paper\." out)))
    (testing "AC #4: a single-key in-text citation links to its own
              bibliography-list entry"
      (is (re-find #"<a href=\"#sec:bibliography-2\">\[2\]</a>" out))
      (is (re-find #"<li id=\"sec:bibliography-2\">Smith, A\. \(2020\)\. A Paper\." out)))
    (testing "a multi-key citation links to its first resolvable item's
              own entry (documented simplification, see this namespace's
              own docstring on render-cite)"
      (is (re-find #"<a href=\"#sec:bibliography-1\">\[1; 2\]</a>" out))
      (is (re-find #"<li id=\"sec:bibliography-1\">Jones, B\. \(2019\)\. Another Paper\." out)))))

(deftest title-fallback-test
  (testing "no meta.title at all falls back to a non-empty <title>, since
            a valid HTML5 document always carries one"
    (let [out (html/emit-document {:meta {} :blocks []})]
      (is (well-formed? out))
      (is (re-find #"<title>Untitled document</title>" out))))
  (testing "no meta.lang at all omits the lang attribute rather than
            emitting an empty one"
    (let [out (html/emit-document {:meta {} :blocks []})]
      (is (well-formed? out))
      (is (re-find #"^<!DOCTYPE html><html>" out))))
  (testing "TASK-20: a meta.title containing MathInline/CrossRef/Cite
            (types this task adds body-rendering support for) still
            contributes reasonable fallback text, not nothing"
    (let [title [(str-inline "See ")
                 {:t :cross-ref :label "fig:x" :target "fig:x" :text "Figure 1"}
                 (str-inline " (")
                 {:t :math-inline :tex "x^2"}
                 (str-inline ") ")
                 {:t :cite :items [] :text [(str-inline "[1]")]}]
          out (html/emit-document {:meta {:title title} :blocks []})]
      (is (well-formed? out))
      (is (re-find #"<title>See Figure 1 \(x\^2\) \[1\]</title>" out)))))

(deftest title-block-test
  (testing "TASK-68 AC #1: a document declaring title, authors and date
            prints all three in the body, not only in <title>"
    (let [out (html/emit-document
               {:meta {:title [(str-inline "On Hazelnuts")]
                       :authors ["Ada Lovelace" "Alan Turing"]
                       :date "2019"}
                :blocks []})
          body (body-of out)]
      (is (well-formed? out))
      (is (re-find #"<title>On Hazelnuts</title>" out))
      (is (re-find #"<header class=\"title-block\"><h1 class=\"title\">On Hazelnuts</h1>" body))
      (is (re-find #"<p class=\"author\">Ada Lovelace</p><p class=\"author\">Alan Turing</p>" body)
          "each author is its own element, so a consumer never re-splits a joined string")
      (is (re-find #"<p class=\"date\">2019</p></header>" body))))
  (testing "AC #2: a document declaring none of them renders no title
            block at all, the way the LaTeX side omits \\maketitle
            rather than emitting a title-less one"
    (let [body (body-of (html/emit-document {:meta {} :blocks []}))]
      (is (not (str/includes? body "title-block")))))
  (testing "AC #3: any subset -- a title with no author"
    (let [body (body-of (html/emit-document
                         {:meta {:title [(str-inline "Alone")]} :blocks []}))]
      (is (str/includes? body "<h1 class=\"title\">Alone</h1>"))
      (is (not (str/includes? body "class=\"author\"")))
      (is (not (str/includes? body "class=\"date\"")))))
  (testing "AC #3: an author with no date"
    (let [body (body-of (html/emit-document
                         {:meta {:title [(str-inline "Alone")] :authors ["Ada Lovelace"]}
                          :blocks []}))]
      (is (str/includes? body "<p class=\"author\">Ada Lovelace</p>"))
      (is (not (str/includes? body "class=\"date\"")))))
  (testing "AC #2 again, the contestable half: authors and a date with NO
            title get no title block either, because the LaTeX side gates
            \\maketitle on the title alone -- a rule that fired here and
            not there would re-make the very disagreement this fixes"
    (let [body (body-of (html/emit-document
                         {:meta {:authors ["Ada Lovelace"] :date "2019"} :blocks []}))]
      (is (not (str/includes? body "title-block")))
      (is (not (str/includes? body "Ada Lovelace")))
      (is (not (str/includes? body "2019")))))
  (testing "a blank date declares nothing, so it prints nothing rather
            than an empty centred paragraph (found by review)"
    (let [body (body-of (html/emit-document
                         {:meta {:title [(str-inline "Titled")] :date ""} :blocks []}))]
      (is (str/includes? body "<h1 class=\"title\">Titled</h1>"))
      (is (not (str/includes? body "class=\"date\"")))))
  (testing "the title block opens the body, ahead of the front matter it
            titles"
    (let [out (html/emit-document
               {:meta {:title [(str-inline "On Hazelnuts")]}
                ;; A front-matter directive is lifted out by
                ;; `front-matter/extract` and rendered here directly, so
                ;; this needs no registry.
                :blocks [{:t :directive :name "dedication" :attr empty-attr :args {}
                          :blocks [(para (str-inline "For R."))]}]})
          body (body-of out)]
      (is (< (str/index-of body "title-block") (str/index-of body "dedication"))))))

(deftest title-block-markup-test
  (testing "AC #5: a meta.title containing markup renders as markup in
            the body, while <title> keeps taking the flattened text --
            an HTML <title> is character data by definition"
    (let [title [(str-inline "A ")
                 {:t :emph :inlines [(str-inline "Real")]}
                 (str-inline " Thesis: ")
                 {:t :math-inline :tex "x^2"}]
          out (html/emit-document {:meta {:title title} :blocks []})]
      (is (well-formed? out))
      (is (re-find #"<title>A Real Thesis: x\^2</title>" out))
      (is (re-find #"<h1 class=\"title\">A <em>Real</em> Thesis: " (body-of out)))
      (is (str/includes? (body-of out) "<span class=\"math inline\">\\(x^2\\)</span>"))))
  (testing "a title carrying a footnote numbers ahead of the body's own,
            rather than colliding with it"
    (let [out (html/emit-document
               {:meta {:title [(str-inline "Titled")
                               {:t :note :blocks [(para (str-inline "Title note."))]}]}
                :blocks [(para (str-inline "Body")
                               {:t :note :blocks [(para (str-inline "Body note."))]})]})]
      (is (well-formed? out))
      (is (str/includes? (body-of out) "<sup id=\"fnref1\"><a href=\"#fn1\">1</a></sup></h1>"))
      (is (re-find #"<li id=\"fn1\"><p>Title note\.</p>" out))
      (is (re-find #"<li id=\"fn2\"><p>Body note\.</p>" out))))
  (testing "AC #4: the default stylesheet styles the block, and
            --no-stylesheet still leaves semantic markup behind"
    (let [document {:meta {:title [(str-inline "Titled")] :authors ["Ada"] :date "2019"}
                    :blocks []}]
      (is (str/includes? (html/emit-document document) "header.title-block{"))
      (let [bare (html/emit-document document {:stylesheet :none})]
        (is (not (str/includes? bare "<style>")))
        (is (str/includes? (body-of bare)
                           (str "<header class=\"title-block\"><h1 class=\"title\">Titled</h1>"
                                "<p class=\"author\">Ada</p><p class=\"date\">2019</p></header>"))))))
  (testing "an author name and a date carrying HTML metacharacters are
            escaped, being plain strings by schema rather than Inlines"
    (let [out (html/emit-document
               {:meta {:title [(str-inline "Titled")]
                       :authors ["Ada & <Alan>"] :date "2019 <draft>"}
                :blocks []})]
      (is (well-formed? out))
      (is (str/includes? (body-of out) "<p class=\"author\">Ada &amp; &lt;Alan&gt;</p>"))
      (is (str/includes? (body-of out) "<p class=\"date\">2019 &lt;draft&gt;</p>")))))

(def ^:private all-block-tags
  "Every Block variant tag `haselnuss.ast` defines (sec4.3) -- kept in
  the same shape as `haselnuss.json-test`'s own `all-block-tags`, which
  is the existing precedent in this codebase for pinning a schema's
  variant set from a test."
  #{:section :para :list :code-block :math-block :figure :table
    :block-quote :directive :include :thematic-break})

(defn- block-fixture
  "A minimal Block of variant `tag`, for `block-coverage-test`'s own
  exhaustive sweep."
  [tag]
  (case tag
    :section {:t :section :level 1 :heading [(str-inline "H")] :blocks [] :attr empty-attr}
    :para (para (str-inline "x"))
    :list {:t :list :ordered false :tight true :items [[(para (str-inline "x"))]] :attr empty-attr}
    :code-block {:t :code-block :text "x" :attr empty-attr}
    :math-block {:t :math-block :tex "x" :attr empty-attr}
    :figure {:t :figure :content (para (str-inline "x")) :caption [] :attr empty-attr}
    :table {:t :table :head {:cells [{:blocks [(para (str-inline "x"))]}]} :rows []
            :caption [] :colspec [{:align :left}] :attr empty-attr}
    :block-quote {:t :block-quote :blocks [(para (str-inline "x"))] :attr empty-attr}
    :directive {:t :directive :name "collapsable" :blocks [(para (str-inline "x"))]
                :attr {:classes [] :props {"summary" "s"}}}
    :include {:t :include :src "other.hdoc"}
    :thematic-break {:t :thematic-break}))

(deftest block-coverage-test
  (testing "TASK-37 review finding #6: no Block variant haselnuss.ast
            defines is ::unsupported-block any more -- asserted
            exhaustively against the schema's own tag set rather than as
            a prose claim in a docstring, so a variant added later
            cannot quietly go unhandled. :include is the one variant
            that still raises, and it raises its own
            ::unresolved-include (TASK-37 AC #3)"
    (is (= 11 (count all-block-tags)))
    (let [reg (registry/register {} collapsable/extension)]
      (doseq [tag all-block-tags]
        (let [thrown (try
                       (html/emit-document {:meta {} :blocks [(block-fixture tag)]} {:registry reg})
                       nil
                       (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))]
          (is (not= ::html/unsupported-block thrown) (str tag))
          (is (= (when (= :include tag) ::html/unresolved-include) thrown) (str tag)))))))

(deftest include-json-entry-point-test
  (testing "TASK-37 review finding #4: haselnuss.json/json->ast puts an
            :include into an AST without going through the resolver, so
            this exercises a route by which a real document still
            reaches ::unresolved-include after TASK-38 gave the pipeline
            an expansion pass -- rather than only a hand-written node"
    (let [document {:meta {} :blocks [{:t :include :src "chapter-2.hdoc"}]}
          round-tripped (json/json->ast (json/->json document))]
      (is (= document round-tripped))
      (try
        (html/emit-document round-tripped)
        (is false "expected emit-document to throw")
        (catch clojure.lang.ExceptionInfo e
          (is (= ::html/unresolved-include (:type (ex-data e))))
          (is (str/includes? (ex-message e) "chapter-2.hdoc")))))))

(deftest block-quote-attr-test
  (testing "a BlockQuote renders its own Attr, like every other
            attr-bearing Block -- dropping it silently cost the node its
            id, so a resolved cross-reference to one linked to an anchor
            that existed nowhere in the document"
    (let [document {:meta {}
                    :blocks [{:t :block-quote
                              :attr {:id "thm:main" :classes ["theorem"] :props {"lang" "en"}}
                              :blocks [(para (str-inline "Body."))]}
                             (para {:t :cross-ref :label "thm:main"
                                    :target "thm:main" :text "Theorem 1"})]}
          out (html/emit-document document)]
      (is (well-formed? out))
      (is (re-find #"<blockquote id=\"thm:main\" class=\"theorem\" lang=\"en\">" out))
      (is (re-find #"<a href=\"#thm:main\">Theorem 1</a>" out)))))

(deftest math-block-number-test
  (testing "TASK-27: a numbered equation prints its own number, so the
            equation shows what a reference to it says -- LaTeX prints
            one via \\tag or the equation counter, and HTML printing
            none left the two targets visibly disagreeing about the same
            document"
    (let [document {:meta {}
                    :blocks [{:t :math-block :tex "E = mc^2"
                              :attr {:id "eq:mass" :classes [] :props {}}}
                             (para {:t :cross-ref :label "eq:mass"
                                    :target "eq:mass" :text "Eq. (1.1)"})]}
          out (html/emit-document document {:labels {"eq:mass" {:number "1.1" :text "Eq. (1.1)"}}})]
      (is (well-formed? out))
      (is (re-find #"<span class=\"math-number\"[^>]*>\(1\.1\)</span>" out))
      (is (re-find #"<a href=\"#eq:mass\">Eq\. \(1\.1\)</a>" out))))
  (testing "an unnumbered equation -- no :labels entry, which is also
            what an id with no recognized kind prefix gets -- prints no
            number, matching the LaTeX emitter's own unnumbered
            equation* for the same node"
    (let [document {:meta {} :blocks [{:t :math-block :tex "E = mc^2"
                                       :attr {:id "eq:mass" :classes [] :props {}}}]}]
      (is (not (re-find #"math-number" (body-of (html/emit-document document)))))
      (is (not (re-find #"math-number" (body-of (html/emit-document document {:labels {}}))))))))

(deftest chapter-heading-numbers-test
  (testing "TASK-53 AC #3: HTML needs no chapter concept of its own -- a
            chapter is a Section, and it prints the number the resolver
            gave it, which in a chaptered document is the number
            native-mode LaTeX's report class prints for the same node.
            The h1..h6 clamp already covers the extra level chapters add"
    (let [document {:meta {:top-level-division :chapter}
                    :blocks [{:t :section :level 1 :heading [(str-inline "Background")]
                              :attr {:id "ch:bg" :classes [] :props {}}
                              :blocks [{:t :section :level 2 :heading [(str-inline "Models")]
                                        :attr {:id "sec:models" :classes [] :props {}}
                                        :blocks [(para {:t :cross-ref :label "ch:bg"
                                                        :target "ch:bg" :text "Chapter 1"})]}]}]}
          labels (resolver/number-document document)
          out (html/emit-document document {:labels labels})]
      (is (well-formed? out))
      (is (re-find #"<section id=\"ch:bg\"><h1><span class=\"section-number\">1</span> Background</h1>"
                   out))
      (is (re-find #"<h2><span class=\"section-number\">1\.1</span> Models</h2>" out))
      (is (re-find #"<a href=\"#ch:bg\">Chapter 1</a>" out)))))

(deftest image-scale-and-height-test
  (let [img (fn [props]
              (re-find #"<img[^>]*>"
                       (body-of (html/emit-document
                                 {:meta {}
                                  :blocks [(para {:t :image :src "pic.png" :alt "p"
                                                  :attr {:classes [] :props props}})]}))))]
    (testing "TASK-60 AC #2: what scale means in HTML is implemented, not
              approximated. It is a multiple of the image's NATURAL size,
              and `zoom` is the one CSS property that is the same
              operation -- a multiplier on the used size that reflows.
              A percentage width is relative to the CONTAINER and
              transform: scale() does not reflow, so either would make
              the two targets disagree about how large a figure is"
      (is (= "<img src=\"pic.png\" alt=\"p\" style=\"zoom: 0.55\"/>" (img {"scale" "0.55"}))))
    (testing "and height is a real CSS height: HTML's own height
              attribute takes a bare pixel count, so height=4cm as an
              attribute is not merely unhonoured but invalid"
      (is (= "<img src=\"pic.png\" alt=\"p\" style=\"height: 4cm\"/>" (img {"height" "4cm"}))))
    (testing "AC #4: several sizing props at once each reach the output"
      (is (= "<img src=\"pic.png\" alt=\"p\" style=\"height: 2cm; zoom: 0.5\"/>"
             (img {"height" "2cm" "scale" "0.5"}))))
    (testing "AC #3: width is untouched -- a literal attribute since
              TASK-22, which the committed example document and existing
              fixtures depend on"
      (is (= "<img src=\"pic.png\" alt=\"p\" width=\"50%\"/>" (img {"width" "50%"}))))
    (testing "neither prop is left behind as a literal attribute as well,
              which is what moving rather than copying them is for"
      (is (not (str/includes? (img {"scale" "0.5"}) "scale=\"")))
      (is (not (str/includes? (img {"height" "4cm"}) "height=\"4cm\""))))
    (testing "an authored style prop still wins, appended after the ones
              derived from sizing props"
      (is (str/includes? (img {"scale" "0.5" "style" "border: 1px solid"})
                         "style=\"zoom: 0.5; border: 1px solid\"")))
    (testing "and an image with no sizing prop is byte-identical to what
              it has always been"
      (is (= "<img src=\"pic.png\" alt=\"p\"/>" (img {}))))
    (testing "a blank value is an absent one here too, and the prop is
              still removed rather than left to leak as a literal
              attribute -- it is no more an HTML attribute for being
              empty, and the LaTeX side fails the compile on the same
              input, so the two must not disagree about whether the
              document builds"
      (is (= "<img src=\"pic.png\" alt=\"p\"/>" (img {"scale" "" "height" "  "}))))
    (testing "a percentage is converted by each prop's own rule, and the
              two rules genuinely differ: half the text height is 50vh,
              because a vh is already one percent of the viewport, while
              a scale of 50% is the bare multiplier 0.5 -- which is what
              the LaTeX output says for both"
      (is (= "<img src=\"pic.png\" alt=\"p\" style=\"height: 50vh\"/>" (img {"height" "50%"})))
      (is (= "<img src=\"pic.png\" alt=\"p\" style=\"zoom: 0.5\"/>" (img {"scale" "50%"}))))
    (testing "and the shapes the LaTeX side had to widen its pattern for
              -- a sign, a leading dot -- convert here too, so one
              authored value cannot mean 0.5 in print and something else
              on screen"
      (is (= "<img src=\"pic.png\" alt=\"p\" style=\"zoom: -0.5\"/>" (img {"scale" "-50%"})))
      (is (= "<img src=\"pic.png\" alt=\"p\" style=\"zoom: 0.005\"/>" (img {"scale" ".5%"}))))
    (testing "AC #4 again, from the HTML end: CSS zoom multiplies the
              USED size, so it composes with a set height the way the
              LaTeX \\adjustbox wrapper composes with a set width --
              which is why the two targets now describe the same size
              for a multi-prop image. Before the fix LaTeX dropped the
              scale here and HTML did not (found by review)"
      (is (= "<img src=\"pic.png\" alt=\"p\" style=\"height: 2cm; zoom: 0.5\"/>"
             (img {"height" "2cm" "scale" "0.5"}))))))
