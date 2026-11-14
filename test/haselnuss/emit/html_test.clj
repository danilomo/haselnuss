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
  (:require [clojure.set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.ast :as ast]
            [haselnuss.emit.html :as html]
            [haselnuss.extensions.collapsable :as collapsable]
            [haselnuss.json :as json]
            [haselnuss.parser :as parser]
            [haselnuss.registry :as registry]
            [haselnuss.resolver :as resolver]
            [haselnuss.sanitize :as sanitize])
  (:import (java.io StringReader)
           (javax.xml.parsers DocumentBuilderFactory)
           (org.xml.sax InputSource)))

(deftest parsed-strikethrough-renders-test
  (testing "TASK-137 AC #1: ~~x~~ in source reaches the HTML as <s> (a
            stylistic strike, see the :strike handler)"
    (is (str/includes? (html/emit-document (parser/parse "a ~~gone~~ b\n"))
                       "<s>gone</s>"))))

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

(deftest v2-renderer-contract-test
  (testing "TASK-89 AC #1/#2: a v2 renderer (registry/renderer-v2) returns
            DATA -- hiccup here -- which this namespace serializes and
            escapes itself, and can render its own nested content by
            calling back into the emitter through ctx's own
            :render-blocks -- a <p>, the ordinary core visitor's own
            output, not something the renderer built by hand"
    (let [reg (registry/register {} {:name "widget"
                                     :emit {:html (registry/renderer-v2
                                                   (fn [directive ctx]
                                                     [:div {:class "widget"}
                                                      [:strong (get-in directive [:attr :props "label"])]
                                                      ((:render-blocks ctx) (:blocks directive))]))}})
          d {:t :directive :name "widget"
             :attr {:classes [] :props {"label" "<hi> & bye"}}
             :blocks [(para (str-inline "nested <text>"))]}
          out (body-of (html/emit-document {:meta {} :blocks [d]} {:registry reg}))]
      (is (= (str "<div class=\"widget\"><strong>&lt;hi&gt; &amp; bye</strong>"
                  "<p>nested &lt;text&gt;</p></div>")
             out))))
  (testing "AC #2, continued: a :note reached only through :render-blocks
            still gets a real number in the surrounding document's own
            footnote list -- the renderer itself never touches footnote
            bookkeeping at all, `ctx`'s own callback does it invisibly"
    (let [reg (registry/register {} {:name "widget"
                                     :emit {:html (registry/renderer-v2
                                                   (fn [directive ctx]
                                                     ((:render-blocks ctx) (:blocks directive))))}})
          d {:t :directive :name "widget" :attr empty-attr
             :blocks [(para (str-inline "see") {:t :note :blocks [(para (str-inline "a note"))]})]}
          out (html/emit-document {:meta {} :blocks [d]} {:registry reg})]
      (is (re-find #"<sup id=\"fnref1\"><a href=\"#fn1\">1</a></sup>" out))
      (is (re-find #"<li id=\"fn1\"><p>a note</p>" out))))
  (testing "AC #8: an untrusted registration's [:raw ...] is a loud error
            naming the directive, never a silent downgrade to escaped
            text or a silent verbatim splice"
    (let [reg (registry/register {} {:name "widget"
                                     :emit {:html (registry/renderer-v2
                                                   (fn [_d _ctx] [:raw "<script>evil</script>"]))}})
          d {:t :directive :name "widget" :attr empty-attr :blocks []}]
      (try
        (html/emit-document {:meta {} :blocks [d]} {:registry reg})
        (is false "expected emit-document to throw")
        (catch clojure.lang.ExceptionInfo e
          (is (= ::html/untrusted-raw-output (:type (ex-data e))))
          (is (str/includes? (ex-message e) "widget"))))))
  (testing "AC #8, continued: the SAME registration with :trusted? true
            lets [:raw ...] through verbatim -- the opt-in a renderer's
            own code cannot grant itself, decided only by whoever
            registered it"
    (let [reg (registry/register {} {:name "widget" :trusted? true
                                     :emit {:html (registry/renderer-v2
                                                   (fn [_d _ctx] [:raw "<script>ok</script>"]))}})
          d {:t :directive :name "widget" :attr empty-attr :blocks []}
          out (body-of (html/emit-document {:meta {} :blocks [d]} {:registry reg}))]
      (is (= "<script>ok</script>" out))))
  (testing "AC #7: a legacy (fn [directive target] -> string) renderer,
            registered with no renderer-v2 marker, is called and spliced
            exactly as it was before TASK-89 -- registered alongside a v2
            one in the SAME registry, so this is dispatch actually
            distinguishing the two per-entry, not merely 'the emitter
            still supports the old shape in isolation'"
    (let [reg (-> {}
                  (registry/register {:name "legacy"
                                      :emit {:html (fn [directive _target]
                                                     (str "<b>" (:name directive) "</b>"))}})
                  (registry/register {:name "modern"
                                      :emit {:html (registry/renderer-v2
                                                    (fn [_d _ctx] [:i "modern"]))}}))
          document {:meta {}
                    :blocks [{:t :directive :name "legacy" :attr empty-attr :blocks []}
                             {:t :directive :name "modern" :attr empty-attr :blocks []}]}
          out (body-of (html/emit-document document {:registry reg}))]
      (is (= "<b>legacy</b><i>modern</i>" out))))
  (testing "review finding #1 (TASK-89): a hiccup element's own TAG
            position is validated, independent of :raw/:trusted -- an
            UNTRUSTED v2 renderer cannot inject markup by building a
            keyword from directive-supplied data and using it as a tag,
            since `element-attrs` escapes attribute names/values and
            every child goes back through render-data, but nothing had
            ever validated the tag itself before this fix. Reproduced
            live before the fix: `[(keyword \"div onmouseover=..\") x]`
            spliced that whole string into BOTH the opening and the
            closing tag with no :raw node anywhere"
    (let [evil-tag (keyword "div onmouseover=\"alert(1)\"")
          reg (registry/register {} {:name "evil"
                                     :emit {:html (registry/renderer-v2
                                                   (fn [_d _ctx] [evil-tag "gotcha"]))}})
          d {:t :directive :name "evil" :attr empty-attr :blocks []}]
      (try
        (html/emit-document {:meta {} :blocks [d]} {:registry reg})
        (is false "expected emit-document to throw rather than splice the malformed tag")
        (catch clojure.lang.ExceptionInfo e
          (is (= ::html/invalid-element-tag (:type (ex-data e))))
          (is (str/includes? (ex-message e) "onmouseover"))))))
  (testing "the same validation accepts every real element name this
            codebase's own vocabulary needs, including a hyphenated
            custom element -- the fix rejects a malformed tag, not
            hiccup elements in general. Trusted, since TASK-215 holds an
            untrusted renderer to an element allow-list without custom
            elements (untrusted-extension-output-test)"
    (let [reg (registry/register {} {:name "widget" :trusted? true
                                     :emit {:html (registry/renderer-v2
                                                   (fn [_d _ctx] [:my-widget {:class "x"} "hi"]))}})
          d {:t :directive :name "widget" :attr empty-attr :blocks []}
          out (body-of (html/emit-document {:meta {} :blocks [d]} {:registry reg}))]
      (is (= "<my-widget class=\"x\">hi</my-widget>" out)))))

(deftest forged-rendered-test
  ;; TASK-213: `assoc` on a record keeps its type, so a renderer handed a
  ;; `Rendered` by :render-blocks could rewrite its :html and have it
  ;; spliced verbatim -- [:raw ...] without the trust gate.
  (let [d {:t :directive :name "widget" :attr empty-attr
           :blocks [(para (str-inline "body"))]}
        emit (fn [renderer & {:keys [trusted?]}]
               (html/emit-document {:meta {} :blocks [d d]}
                                   {:registry (registry/register
                                               {} {:name "widget" :trusted? trusted?
                                                   :emit {:html (registry/renderer-v2 renderer)}})}))
        refusal (fn [renderer]
                  (try (emit renderer) [:no-throw]
                       (catch clojure.lang.ExceptionInfo e
                         [(:type (ex-data e)) (:directive-name (ex-data e)) (ex-message e)])))
        stash (atom nil)
        forgeries {"assoc" (fn [_d ctx] (assoc ((:render-blocks ctx) []) :html "<script>x</script>"))
                   "merge, nested in a fragment" (fn [_d ctx]
                                                   (list (merge ((:render-inlines ctx) [])
                                                                {:html "<script>x</script>"})))
                   "into, nested in an element" (fn [_d ctx]
                                                  [:div {} (into ((:render-blocks ctx) [])
                                                                 {:html "<script>x</script>"})])
                   "constructed" (fn [_d _ctx] (html/->Rendered "<script>x</script>"))
                   "kept from another call" (fn [directive ctx]
                                              (or @stash
                                                  (reset! stash ((:render-blocks ctx)
                                                                 (:blocks directive)))))}]
    (doseq [[label renderer] forgeries]
      (testing (str "an untrusted renderer's Rendered that is not the very instance a
                     ctx callback issued in this call is refused, naming the directive: " label)
        (let [[error-type directive-name message] (refusal renderer)]
          (is (= ::html/forged-rendered error-type))
          (is (= "widget" directive-name))
          (is (str/includes? (str message) "\"widget\""))
          (is (str/includes? (str message) "not :trusted")))))
    (testing "the source position the parser recorded is named"
      (let [d' (ast/with-source d "doc.hdoc" 7)]
        (try (html/emit-document {:meta {} :blocks [d']}
                                 {:registry (registry/register
                                             {} {:name "widget"
                                                 :emit {:html (registry/renderer-v2
                                                               (get forgeries "assoc"))}})})
             (is false "expected a refusal")
             (catch clojure.lang.ExceptionInfo e
               (is (= "doc.hdoc:7" (:source (ex-data e))))
               (is (str/includes? (ex-message e) "(doc.hdoc:7)"))))))
    (testing "the callback's own result, unchanged, anywhere in the returned data, still renders"
      (is (= "<section><p>body</p><p>body</p></section><section><p>body</p><p>body</p></section>"
             (body-of (emit (fn [directive ctx]
                              (let [r ((:render-blocks ctx) (:blocks directive))]
                                [:section {} r (list r)])))))))
    (testing "a trusted registration may still return any Rendered, as it may [:raw ...]"
      (is (= "<script>x</script><script>x</script>"
             (body-of (emit (get forgeries "assoc") :trusted? true)))))))

(deftest rendered-first-child-test
  ;; TASK-216: a `Rendered` is a record, so a map; in an element's second
  ;; position it used to be read as the attrs map -- `<div html="&lt;p&gt;...">`.
  (let [d {:t :directive :name "widget" :attr empty-attr
           :blocks [(para (str-inline "body"))]}
        emit (fn [renderer & {:keys [trusted?]}]
               (body-of (html/emit-document
                         {:meta {} :blocks [d]}
                         {:registry (registry/register
                                     {} {:name "widget" :trusted? trusted?
                                         :emit {:html (registry/renderer-v2 renderer)}})})))
        refusal (fn [renderer]
                  (try (emit renderer) [:no-throw]
                       (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))]
    (doseq [trusted? [false true]]
      (testing (str "a callback's Rendered as an element's first child is that child, trusted? " trusted?)
        (is (= "<div><p>body</p></div>"
               (emit (fn [directive ctx] [:div ((:render-blocks ctx) (:blocks directive))])
                     :trusted? trusted?)))
        (is (= "<div><span>body</span>!</div>"
               (emit (fn [directive ctx]
                       [:div [:span ((:render-inlines ctx) (:inlines (first (:blocks directive))))] "!"])
                     :trusted? trusted?)))
        (is (= "<div class=\"w\"><p>body</p></div>"
               (emit (fn [directive ctx] [:div {:class "w"} ((:render-blocks ctx) (:blocks directive))])
                     :trusted? trusted?))
            "a plain attrs map is still the attrs")))
    (testing "a forged Rendered as the first child is refused, as anywhere else"
      (doseq [forge [(fn [_d ctx] [:div (assoc ((:render-blocks ctx) []) :html "<script>x</script>")])
                     (fn [_d ctx] [:div (into ((:render-blocks ctx) []) {:html "<script>x</script>"})])
                     (fn [_d _ctx] [:div (html/->Rendered "<script>x</script>")])]]
        (is (= ::html/forged-rendered (refusal forge)))))
    (testing "and spliced for a trusted registration, as anywhere else"
      (is (= "<div><script>x</script></div>"
             (emit (fn [_d ctx] [:div (assoc ((:render-blocks ctx) []) :html "<script>x</script>")])
                   :trusted? true))))))

(deftest untrusted-extension-output-test
  ;; TASK-215: the TASK-213 reviewer's reproductions. An untrusted
  ;; renderer's hiccup is held to a tag allow-list and the document
  ;; pass's attribute rules, and the AST it hands its ctx callbacks goes
  ;; through haselnuss.sanitize first.
  (let [d (ast/with-source {:t :directive :name "widget" :attr empty-attr
                            :blocks [(para (str-inline "body")
                                           {:t :math-inline :tex "\\def\\x{1}\\x"})]}
            "doc.hdoc" 3)
        emit (fn [renderer & {:keys [trusted?]}]
               (let [warnings (atom [])
                     out (binding [sanitize/*untrusted-diagnostics* warnings]
                           (html/emit-document
                            {:meta {} :blocks [d]}
                            {:registry (registry/register
                                        {} {:name "widget" :trusted? trusted?
                                            :emit {:html (registry/renderer-v2 renderer)}})}))]
                 [(body-of out) @warnings]))
        refusal (fn [renderer]
                  (try (emit renderer) [:no-throw]
                       (catch clojure.lang.ExceptionInfo e [(ex-data e) (ex-message e)])))
        evil-link {:t :link :target "javascript:alert(1)"
                   :attr (assoc empty-attr :props {"onmouseover" "alert(2)"})
                   :inlines [(str-inline "click")]}]
    (testing "an element outside the allow-list is refused, naming the directive, its line and the tag"
      (doseq [tag [:script :SCRIPT :style :iframe :object :embed :link :meta :base :form :input
                   :svg :math :template :my-widget]]
        (let [[data message] (refusal (fn [_d _ctx] [:div [tag "x"]]))]
          (is (= ::html/untrusted-element (:type data)) (str tag))
          (is (= "widget" (:directive-name data)))
          (is (= "doc.hdoc:3" (:source data)))
          (is (str/includes? (str message) (str "<" (name tag) ">"))))))
    (testing "on* handlers, javascript: URLs and malformed attribute names are dropped with a warning each"
      (let [[out warnings] (emit (fn [_d _ctx]
                                   [:div {:class "w" :onClick "alert(0)" :is "x-evil"}
                                    [:img {:src "pic.png" :onerror "alert(1)" :lowsrc "javascript:x"
                                           :srcset "ok.png 1x, javascript:alert(2) 2x"}]
                                    [:a {:href " JaVa\tScript:alert(3)" (keyword "x onmouseover") "y"
                                         :title "fine"} "go"]
                                    [:a {:href "https://example.org/" :style "color: red"} "ok"]]))]
        (is (= (str "<div class=\"w\"><img src=\"pic.png\"></img><a title=\"fine\">go</a>"
                    "<a href=\"https://example.org/\" style=\"color: red\">ok</a></div>")
               out))
        (is (= [:custom-element-attribute :event-handler-attribute :event-handler-attribute
                :malformed-attribute-name :unsafe-url :unsafe-url :unsafe-url]
               (sort-by name (map :type warnings))))
        (is (every? #(str/starts-with? (:message %) "directive \"widget\" (doc.hdoc:3), untrusted")
                    warnings))
        (is (every? #(= "widget" (:directive-name %)) warnings))))
    (testing "a hand-built :link with a javascript: target and an onmouseover prop, via
              :render-blocks or :render-inlines, renders as its text, with warnings"
      (let [[out warnings] (emit (fn [_d ctx] ((:render-blocks ctx) [(para evil-link)])))]
        (is (= "<p>click</p>" out))
        (is (= #{:event-handler-attribute :unsafe-url} (set (map :type warnings)))))
      (let [[out warnings] (emit (fn [_d ctx] [:span {} ((:render-inlines ctx) [evil-link])]))]
        (is (= "<span>click</span>" out))
        (is (= 2 (count warnings)))))
    (testing "TeX using a dangerous primitive the document did not author is refused"
      (let [[data message] (refusal (fn [_d ctx]
                                      ((:render-inlines ctx)
                                       [{:t :math-inline :tex "\\input{/etc/passwd}"}])))]
        (is (= ::sanitize/untrusted-tex (:type data)))
        (is (= "\\input" (:token data)))
        (is (str/includes? (str message) "\"widget\" (doc.hdoc:3)"))))
    (testing "an ordinary renderer is unchanged: allowed tags, its own body rendered through
              :render-blocks -- the document's own math included, which is trusted"
      (let [[out warnings] (emit (fn [directive ctx]
                                   [:details {:class "callout" :id "c1"}
                                    [:summary "Note"]
                                    ((:render-blocks ctx) (:blocks directive))]))]
        (is (= (str "<details id=\"c1\" class=\"callout\"><summary>Note</summary>"
                    "<p>body<span class=\"math inline\">\\(\\def\\x{1}\\x\\)</span></p></details>")
               out))
        (is (empty? warnings))))
    (testing "--trusted-extensions (a :trusted? registration) lifts all of it"
      (let [[out warnings] (emit (fn [_d ctx]
                                   [:div [:script "x"] [:a {:href "javascript:y" :onclick "z"} "a"]
                                    ((:render-inlines ctx) [evil-link
                                                            {:t :math-inline :tex "\\input{f}"}])])
                                 :trusted? true)]
        (is (str/includes? out "<script>x</script>"))
        (is (str/includes? out "href=\"javascript:y\""))
        (is (str/includes? out "onclick=\"z\""))
        (is (str/includes? out "href=\"javascript:alert(1)\""))
        (is (str/includes? out "\\input{f}"))
        (is (empty? warnings))))))

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

(deftest figure-describe-alt-test
  (testing "TASK-131: a describe-d alt lands on the <img>, never on <figure>"
    (let [figure {:t :figure
                  :content {:t :para :inlines [{:t :image :src "pic.png" :alt "a" :attr empty-attr}]}
                  :caption []
                  :attr {:id "fig:tree" :classes [] :props {"alt" "Described." "data-x" "1"}}}
          out (html/emit-document {:meta {} :blocks [figure]})]
      (is (well-formed? out))
      (is (str/includes? out "<figure id=\"fig:tree\" data-x=\"1\">"))
      (is (str/includes? out "<img src=\"pic.png\" alt=\"Described.\"/>"))))
  (testing "a parent's alt does not reach its panels' own images"
    (let [panel {:t :figure
                 :content {:t :para :inlines [{:t :image :src "p.png" :alt "own" :attr empty-attr}]}
                 :caption [] :attr {:id "fig:p" :classes [] :props {}}}
          parent {:t :figure :content {:t :block-quote :blocks [panel]} :caption []
                  :attr {:id "fig:all" :classes [] :props {"alt" "X"}}}
          out (html/emit-document {:meta {} :blocks [parent]})]
      (is (str/includes? out "alt=\"own\""))
      (is (not (str/includes? out "alt=\"X\""))))))

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
  (testing "TASK-130: the MathJax script is emitted only for a document with math"
    (let [script #"<script id=\"MathJax-script\" async=\"async\" src=\"https://cdn\.jsdelivr\.net/npm/mathjax@3\.2\.2/es5/tex-mml-chtml\.js\"></script>"
          plain (html/emit-document {:meta {} :blocks [(para (str-inline "no math here"))]})
          inline (html/emit-document {:meta {} :blocks [(para {:t :math-inline :tex "x"})]})
          block (html/emit-document {:meta {} :blocks [{:t :math-block :tex "x" :attr {:id nil :classes [] :props {}}}]})
          forged (html/emit-document {:meta {} :blocks [(para (str-inline "<span class=\"math inline\">"))]})
          empty-doc (html/emit-document {:meta {} :blocks []})
          opted-out (html/emit-document {:meta {} :blocks [(para {:t :math-inline :tex "x"})]} {:mathjax false})]
      (is (well-formed? plain))
      (is (nil? (re-find script plain)))
      (is (nil? (re-find #"cdn\.jsdelivr" empty-doc)))
      (is (nil? (re-find script forged)))
      (is (re-find script inline))
      (is (re-find script block))
      (is (nil? (re-find script opted-out)))))
  (testing "TASK-217: the MathJax configuration closes every way a
            document's math could make a link or an attribute, and comes
            immediately before the CDN script so MathJax reads it"
    (let [config (str "<script>window.MathJax={"
                      "loader:{load:['ui/safe']},"
                      "tex:{packages:{'[-]':['require']},autoload:{html:[],unicode:[]}},"
                      "options:{safeOptions:{allow:{URLs:'none'}}},"
                      "startup:{ready:function(){"
                      "MathJax.startup.defaultReady();"
                      "var s=MathJax.startup.document.safe;"
                      "s.filterMethods.filterFontToken=function(_,v){return /^[-\\w ,]*$/.test(v)?v:null;};"
                      "['fontfamily','fontweight','fontstyle'].forEach(function(a){s.filterAttributes.set(a,'filterFontToken');});"
                      "}}"
                      "};</script>")
          math (html/emit-document
                {:meta {} :blocks [(para {:t :math-inline :tex "\\href{javascript:alert(1)}{x}"})]})
          plain (html/emit-document {:meta {} :blocks [(para (str-inline "no math here"))]})
          opted-out (html/emit-document {:meta {} :blocks [(para {:t :math-inline :tex "x"})]}
                                        {:mathjax false})]
      (is (well-formed? math))
      (is (str/includes? math (str config "<script id=\"MathJax-script\"")))
      (is (= 1 (count (re-seq #"window\.MathJax=" math))))
      (testing "-- the math itself is left as written: the browser-side
                config is what disarms it"
        (is (str/includes? math "\\(\\href{javascript:alert(1)}{x}\\)")))
      (is (not (str/includes? plain "window.MathJax")))
      (is (not (str/includes? opted-out "window.MathJax"))))))

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
      (is (re-find #"<title>See Figure 1 \(x\^2\) \[1\]</title>" out))))
  (testing "TASK-122: a :highlight in meta.title contributes its own
            words to <title> rather than nothing. The fallback used to
            name the container Inline types by hand, so a highlight --
            the moment `==...==` made one writable -- flattened to the
            empty string in the one place a document's own name is set"
    (let [title [(str-inline "A ")
                 {:t :highlight :inlines [(str-inline "marked")] :attr empty-attr}
                 (str-inline " title")]
          ;; A `:highlight` has no built-in renderer yet (that is
          ;; TASK-120), and `emit-document` renders meta.title into the
          ;; page's own title block as well as into <title>. Supplying
          ;; one through the documented `:inline-handlers` opt keeps this
          ;; test about the <title> FALLBACK -- which is what regressed
          ;; -- rather than about rendering.
          out (html/emit-document
               {:meta {:title title} :blocks []}
               {:inline-handlers
                {:highlight (fn [_ctx inline footnotes]
                              [(str "<mark>" (:text (first (:inlines inline))) "</mark>")
                               footnotes])}})]
      (is (well-formed? out))
      (is (re-find #"<title>A marked title</title>" out)))))

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
    :block-quote :directive :include :book-quote :thematic-break})

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
    :book-quote {:t :book-quote :ref "bible:romanos#14:8"}
    :thematic-break {:t :thematic-break}))

(deftest block-coverage-test
  (testing "TASK-37 review finding #6: no Block variant haselnuss.ast
            defines is ::unsupported-block any more -- asserted
            exhaustively against the schema's own tag set rather than as
            a prose claim in a docstring, so a variant added later
            cannot quietly go unhandled. :include is the one variant
            that still raises, and it raises its own
            ::unresolved-include (TASK-37 AC #3)"
    (is (= 12 (count all-block-tags)))
    (let [reg (registry/register {} collapsable/extension)]
      (doseq [tag all-block-tags]
        (let [thrown (try
                       (html/emit-document {:meta {} :blocks [(block-fixture tag)]} {:registry reg})
                       nil
                       (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))]
          (is (not= ::html/unsupported-block thrown) (str tag))
          (is (= (when (= :include tag) ::html/unresolved-include) thrown) (str tag)))))))

(deftest book-nodes-unresolved-rendering-test
  (testing "TASK-152/156: a book node no resolution ran over (the json
            target's view, or a build with no library directory) shows its
            ref as written instead of throwing"
    (let [out (html/emit-document
               {:meta {} :blocks [(para (str-inline "See ") {:t :book-ref :ref "a & <b>"})
                                  {:t :book-quote :ref "bible:romanos#14:8-9"}]})]
      (is (well-formed? out))
      (is (str/includes? (body-of out) "<p>See <span class=\"book-ref\">a &amp; &lt;b&gt;</span></p>"))
      (is (str/includes? (body-of out)
                         "<blockquote class=\"book-quote\"><p>bible:romanos#14:8-9</p></blockquote>"))))
  (testing "resolved fields, when present, are what is rendered"
    (let [out (html/emit-document
               {:meta {} :blocks [(para {:t :book-ref :ref "bible:romanos#14:8"
                                         :text [(str-inline "Romanos 14:8")]})
                                  {:t :book-quote :ref "bible:romanos#14:8"
                                   :blocks [(para (str-inline "Porque"))]}]})]
      (is (str/includes? (body-of out) "<span class=\"book-ref\">Romanos 14:8</span>"))
      (is (str/includes? (body-of out) "<blockquote class=\"book-quote\"><p>Porque</p></blockquote>"))
      (is (not (str/includes? (body-of out) "bible:romanos"))))))

(def ^:private resolved-book-document
  "A host Section holding both BookRef forms (with and without a URL, the
  URL needing attribute escaping), a failed link, a resolved BookQuote
  whose passage has its own level-1 and level-2 headings and a citation,
  and a failed quote -- the shapes TASK-154/155 resolution produces."
  {:meta {}
   :blocks [{:t :section :level 1 :heading [(str-inline "Host")]
             :attr {:id "sec:host" :classes [] :props {}}
             :blocks [(para (str-inline "See ")
                            {:t :book-ref :ref "reformed:heidelberg#sec:q1"
                             :canonical "reformed:confessions/heidelberg#sec:q1"
                             :text [(str-inline "Heidelberg & Co, Section 1.1")]
                             :url "https://example.org/h.html?a=1&b=\"2\"#sec:q1"}
                            (str-inline ", ")
                            {:t :book-ref :ref "plain:p" :canonical "plain:p"
                             :text [(str-inline "P")]}
                            (str-inline " and ")
                            {:t :book-ref :ref "nolib:x" :text [(str-inline "??")]})
                      {:t :book-quote :ref "reformed:heidelberg#sec:comfort"
                       :canonical "reformed:confessions/heidelberg#sec:comfort"
                       :title [(str-inline "Heidelberg Catechism, Section 1")]
                       :url "https://example.org/h.html#sec:comfort"
                       :blocks [{:t :section :level 1 :heading [(str-inline "Comfort")]
                                 :attr {:classes [] :props {}}
                                 :blocks [{:t :section :level 2 :heading [(str-inline "Q1")]
                                           :attr {:classes [] :props {}}
                                           :blocks [(para (str-inline "Only comfort ")
                                                          {:t :cite :items [{:key "k" :mode :normal}]
                                                           :text [(str-inline "[7]")]})]}]}]}
                      {:t :book-quote :ref "plain:p" :canonical "plain:p"
                       :title [(str-inline "P")]
                       :blocks [(para (str-inline "Unlinked."))]}
                      {:t :book-quote :ref "nolib:x"
                       :blocks [(para (str-inline "?? nolib:x"))]}]}]})

(deftest book-nodes-golden-test
  (testing "TASK-156 AC #1/#4: both BookRef forms, the BookQuote blockquote with
            data-source and cite, its source line, and the failed placeholders"
    (let [out (html/emit-document resolved-book-document
                                  {:bibliography-id "refs" :ordered-keys ["k"]})]
      (is (well-formed? out))
      (is (= (str "<section id=\"sec:host\"><h1>Host</h1>"
                  "<p>See <a class=\"book-ref\" href=\"https://example.org/h.html?a=1&amp;b=&quot;2&quot;#sec:q1\">"
                  "Heidelberg &amp; Co, Section 1.1</a>, "
                  "<span class=\"book-ref\">P</span> and "
                  "<span class=\"book-ref\">??</span></p>"
                  "<figure class=\"book-quote-figure\">"
                  "<blockquote class=\"book-quote\" data-source=\"reformed:confessions/heidelberg#sec:comfort\""
                  " cite=\"https://example.org/h.html#sec:comfort\">"
                  "<section><h2>Comfort</h2><section><h3>Q1</h3><p>Only comfort [7]</p></section></section>"
                  "</blockquote>"
                  "<figcaption>— <cite><a href=\"https://example.org/h.html#sec:comfort\">"
                  "Heidelberg Catechism, Section 1</a></cite></figcaption></figure>"
                  "<figure class=\"book-quote-figure\">"
                  "<blockquote class=\"book-quote\" data-source=\"plain:p\"><p>Unlinked.</p></blockquote>"
                  "<figcaption>— <cite>P</cite></figcaption></figure>"
                  "<blockquote class=\"book-quote\"><p>?? nolib:x</p></blockquote>"
                  "</section>")
             (body-of out)))
      (testing "the quoted citation is not linked to the host's bibliography entry for the same key"
        (is (not (str/includes? out "href=\"#refs-")))))))

(deftest book-quote-heading-levels-test
  (testing "TASK-156: a quote's shallowest heading sits one level below the host
            Section around it, whatever level it had in its book"
    (let [q (fn [level] {:t :book-quote :ref "b:x" :canonical "b:x"
                         :blocks [{:t :section :level level :heading [(str-inline "Q")]
                                   :attr {:classes [] :props {}}
                                   :blocks [{:t :section :level (inc level) :heading [(str-inline "R")]
                                             :attr {:classes [] :props {}} :blocks []}]}]})
          host (fn [& blocks] {:t :section :level 2 :heading [(str-inline "H")]
                               :attr {:classes [] :props {}} :blocks (vec blocks)})
          body (fn [& blocks] (body-of (html/emit-document {:meta {} :blocks (vec blocks)})))]
      (is (str/includes? (body (host (q 1))) "<h3>Q</h3><section><h4>R</h4>"))
      (is (str/includes? (body (host (q 4))) "<h3>Q</h3><section><h4>R</h4>"))
      (is (str/includes? (body (q 2)) "<h1>Q</h1><section><h2>R</h2>")
          "at the top of the host, the quote starts at level 1")
      (testing "a quote inside a quote is placed under the quoted Section around it"
        (is (str/includes? (body (host (assoc-in (q 1) [:blocks 0 :blocks 0 :blocks] [(q 1)])))
                           "<h4>R</h4><blockquote class=\"book-quote\" data-source=\"b:x\"><section><h5>Q</h5>"))))))

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
                              :container :div
                              :attr {:id "thm:main" :classes ["theorem"] :props {"lang" "en"}}
                              :blocks [(para (str-inline "Body."))]}
                             (para {:t :cross-ref :label "thm:main"
                                    :target "thm:main" :text "Theorem 1"})]}
          out (html/emit-document document)]
      (is (well-formed? out))
      (is (re-find #"<div id=\"thm:main\" class=\"theorem\" lang=\"en\">" out))
      (is (not (re-find #"<blockquote" out)))
      (is (re-find #"<a href=\"#thm:main\">Theorem 1</a>" out))))
  (testing "TASK-139: only the in-memory :container marker makes a div --
            an authored quote whose class happens to be .proof or
            .admonition is still a quotation"
    (let [out (html/emit-document
               {:meta {}
                :blocks [{:t :block-quote :attr {:classes ["proof"]}
                          :blocks [(para (str-inline "Said."))]}
                         {:t :block-quote :container :note :attr {:classes ["admonition"]}
                          :blocks [(para (str-inline "Careful."))]}]})]
      (is (str/includes? out "<blockquote class=\"proof\">"))
      (is (str/includes? out "<div class=\"admonition\" role=\"note\">"))))
  (testing "TASK-139: directive-internal props (caption, key, ref) are not
            written as attributes on a block or an inline; real HTML
            attributes still are"
    (let [out (html/emit-document
               {:meta {}
                :blocks [{:t :block-quote :container :div
                          :attr {:classes ["theorem"]
                                 :props {"caption" "T" "key" "k" "lang" "en"}}
                          :blocks [(para {:t :span
                                          :attr {:props {"ref" "r" "title" "x"}}
                                          :inlines [(str-inline "in")]})]}]})]
      (is (str/includes? out "lang=\"en\""))
      (is (str/includes? out "title=\"x\""))
      (is (not (re-find #"caption=|key=|ref=" out))))))

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

(deftest open-dispatch-test
  (testing "TASK-88 AC #4: a caller can render a Block/Inline :t neither
            this namespace nor haselnuss.emit.latex has ever heard of, by
            supplying a handler via emit-document's own :block-handlers/
            :inline-handlers opts -- no edit to either emitter file"
    (let [document {:meta {}
                    :blocks [(para (str-inline "before ")
                                   {:t :redacted :text "secret"}
                                   (str-inline " after"))
                             {:t :callout :label "note"}]}
          out (html/emit-document
               document
               {:inline-handlers
                {:redacted (fn [_ctx inline footnotes]
                             [(str "[REDACTED:" (count (:text inline)) "]") footnotes])}
                :block-handlers
                {:callout (fn [_ctx block footnotes]
                            [(str "<div class=\"callout\">" (:label block) "</div>") footnotes])}})]
      (is (str/includes? out "<p>before [REDACTED:6] after</p>"))
      (is (str/includes? out "<div class=\"callout\">note</div>"))))
  (testing "TASK-88 AC #5: a node type added for one document is not
            visible to a later, unrelated emit-document call in the same
            process -- the handler table is merged fresh per call from
            built-in-block-handlers/built-in-inline-handlers, never a
            mutated shared/global table (the concurrency/leakage property
            a handler-table-in-context design has over defmethod)"
    (let [callout-handler (fn [_ctx block footnotes]
                            [(str "<div class=\"callout\">" (:label block) "</div>") footnotes])
          with-handler (html/emit-document
                        {:meta {} :blocks [{:t :callout :label "note"}]}
                        {:block-handlers {:callout callout-handler}})
          thrown (try
                   (html/emit-document {:meta {} :blocks [{:t :callout :label "note"}]})
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (str/includes? with-handler "<div class=\"callout\">note</div>"))
      (is (not (contains? html/built-in-block-handlers :callout))
          "the built-in table itself must never be mutated by an opt")
      (is (some? thrown)
          "a later call with no :block-handlers opt must not see the earlier call's addition")
      (is (= ::html/unsupported-block (:type (ex-data thrown))))
      (is (str/includes? (ex-message thrown) ":callout")))))

;; --- Annotations: :highlight and :sticky-note (TASK-120, doc-8 sec5) -------

(defn- annotation-attr
  "An Attr with an optional id and the annotation props doc-8 sec2
  recognises."
  [& {:keys [id color author classes]}]
  (cond-> {:classes (vec classes)
           :props (cond-> {} color (assoc "color" color) author (assoc "author" author))}
    id (assoc :id id)))

(defn- highlight
  [attr & inlines]
  {:t :highlight :inlines (vec inlines) :attr attr})

(defn- sticky-note
  [attr & blocks]
  {:t :sticky-note :blocks (vec blocks) :attr attr})

(defn- emit-body
  [& blocks]
  (body-of (html/emit-document {:meta {} :blocks (vec blocks)})))

(deftest highlight-renders-as-mark-test
  (testing "AC #1: a :highlight renders as a <mark> carrying its marked
            inlines. <mark> is the element for exactly this, and it is
            legible with no stylesheet at all -- which is this emitter's
            own self-contained-single-file contract, and the reason a
            highlight is not a <span> with a class"
    (let [body (emit-body (para (str-inline "Hazelnuts ")
                                (highlight (annotation-attr) (str-inline "ripen"))
                                (str-inline " here.")))]
      (is (re-find #"<mark class=\"highlight highlight-yellow\">ripen</mark>" body))))
  (testing "AC #1: nested markup inside a highlight renders normally"
    (let [body (emit-body
                (para (highlight (annotation-attr)
                                 {:t :emph :inlines [(str-inline "emph")]}
                                 {:t :strong :inlines [(str-inline "strong")]}
                                 {:t :link :target "https://example.com"
                                  :inlines [(str-inline "link")] :attr empty-attr}
                                 {:t :code :text "code"}
                                 {:t :math-inline :tex "x^2"})))]
      (is (re-find #"<em>emph</em>" body))
      (is (re-find #"<strong>strong</strong>" body))
      (is (re-find #"<a href=\"https://example.com\">link</a>" body))
      (is (re-find #"<code>code</code>" body))
      (is (re-find #"<span class=\"math inline\">" body))
      (is (re-find #"<mark[^>]*>.*</mark>" body)))))

(def ^:private phrasing-content-elements
  "The HTML elements a `:sticky-note` is allowed to emit.

  Every one of them is PHRASING content, and that is the whole
  constraint: a sticky note is an inline node, so everything it emits
  lands inside a `<p>`, and a `<p>` may contain nothing else. An HTML
  parser does not nest a `<p>`, `<ul>`, `<pre>` or `<details>` there --
  it CLOSES the paragraph and makes the element a sibling, which puts
  the marker on a line of its own and orphans the rest of the sentence.

  Asserted as a name set rather than by re-parsing with an HTML5 tree
  builder: this encodes the rule that was actually broken, needs no new
  dependency, and fails on the offending element by name."
  #{"span" "input" "label" "code" "em" "strong" "s" "sub" "sup" "a" "mark" "br" "img"})

(defn- element-names
  "Every element name appearing in `html`, as a set."
  [html]
  (into #{} (map second) (re-seq #"</?([a-zA-Z][a-zA-Z0-9]*)" html)))

(deftest sticky-note-renders-as-phrasing-content-test
  (testing "AC #2: a :sticky-note renders as an inline disclosure whose
            body holds the rendered blocks, opening and closing on click
            with no script at all.

            NOT the <details>/<summary> AC #2 and doc-8 sec5 name, and
            that is a measured correction rather than a shortcut:
            <details> is flow content, so an HTML parser closes the
            enclosing <p> when it meets one and the balloon ends up a
            SIBLING of the paragraph it was meant to sit inside. A hidden
            checkbox plus its own <label> is phrasing content, so it is
            legal where a sticky note actually occurs -- and it is still
            click-to-open, click-to-dismiss, with no JavaScript"
    (let [body (emit-body (para (str-inline "text")
                                (sticky-note (annotation-attr)
                                             (para (str-inline "A comment."))
                                             (para (str-inline "And another.")))))]
      (is (re-find #"<span class=\"sticky-note sticky-note-yellow\">" body))
      (is (re-find #"<input class=\"sticky-note-toggle\" type=\"checkbox\" id=\"[^\"]+\"/>" body))
      (is (re-find #"<label class=\"sticky-note-marker\" for=\"[^\"]+\">note</label>" body))
      (is (re-find #"<span class=\"sticky-note-body\"><span>A comment.</span><span>And another.</span></span>"
                   body))
      (is (not (re-find #"<script" body)) "no JavaScript is emitted")
      (is (not (re-find #"<details" body))
          "and no <details>, which is what broke the paragraph")))
  (testing "AC #3, the half that MEASUREMENT settles rather than CSS: a
            sticky note in the middle of a paragraph emits only phrasing
            content, so the paragraph survives it. This is the assertion
            the XML-well-formedness proxy is structurally blind to -- a
            <p> inside a <p> is perfectly well-formed XML and a broken
            HTML document"
    (doseq [[label blocks]
            [["paragraphs" [(para (str-inline "one")) (para (str-inline "two"))]]
             ["a list" [{:t :list :ordered false :tight true
                         :items [[(para (str-inline "item"))]] :attr empty-attr}]]
             ["a code block" [{:t :code-block :text "(+ 1 2)" :attr empty-attr}]]
             ["a block quote" [{:t :block-quote :blocks [(para (str-inline "quoted"))]}]]
             ["markup inside" [(para (str-inline "with ")
                                     {:t :emph :inlines [(str-inline "emphasis")]}
                                     {:t :code :text "code"})]]
             ["a block with no phrasing rendering at all"
              [{:t :figure :content (para (str-inline "x"))
                :caption [(str-inline "a caption")] :attr empty-attr}]]]]
      (testing label
        (let [body (emit-body (para (str-inline "before ")
                                    (apply sticky-note (annotation-attr) blocks)
                                    (str-inline " after")))]
          (is (empty? (clojure.set/difference (element-names body)
                                              (conj phrasing-content-elements "p")))
              (str "only phrasing content inside the paragraph, saw "
                   (pr-str (element-names body))))
          (is (= 1 (count (re-seq #"<p>" body)))
              "and exactly one paragraph, not one split into several")))))
  (testing "AC #3, the half CSS settles: the body is positioned OVER the
            text rather than pushing it, and has no box at all while
            closed, so nothing can split the inline box"
    (is (re-find #"span\.sticky-note\{position:relative\}" html/default-stylesheet)
        "the positioning context the body is placed against")
    (is (re-find #"span\.sticky-note-body\{display:none\}" html/default-stylesheet)
        "no box at all while closed")
    (is (re-find #"input\.sticky-note-toggle:checked ~ span\.sticky-note-body\{display:block;position:absolute;"
                 html/default-stylesheet)
        "absolutely positioned when open, so it floats over the text")
    (is (re-find #"background:Canvas" html/default-stylesheet)
        "opaque, since it sits on top of the text it is about"))
  (testing "the toggle is hidden without being removed from the keyboard's
            reach -- display:none would make the disclosure mouse-only"
    (is (re-find #"input\.sticky-note-toggle\{position:absolute;width:1px;height:1px;opacity:0\}"
                 html/default-stylesheet))
    (is (not (re-find #"input\.sticky-note-toggle\{display:none" html/default-stylesheet))))
  (testing "the checkbox's own id is NOT the annotation's id, so a
            cross-reference to the annotation lands on the note rather
            than on its toggle"
    (let [body (emit-body (para (sticky-note (annotation-attr :id "note:1")
                                             (para (str-inline "x")))))]
      (is (re-find #"<span id=\"note:1\"" body))
      (is (not (re-find #"<input[^>]* id=\"note:1\"" body))))))

(deftest annotation-colors-test
  (testing "AC #4: each recognised color= produces a distinct class, and
            each class has a rule in default-stylesheet"
    (doseq [color html/annotation-colors]
      (testing color
        (let [body (emit-body (para (highlight (annotation-attr :color color)
                                               (str-inline "x"))
                                    (sticky-note (annotation-attr :color color)
                                                 (para (str-inline "y")))))]
          (is (re-find (re-pattern (str "class=\"highlight highlight-" color "\"")) body))
          (is (re-find (re-pattern (str "class=\"sticky-note sticky-note-" color "\"")) body))
          (is (re-find (re-pattern (str "mark\\.highlight-" color "\\{background:"))
                       html/default-stylesheet))
          (is (re-find (re-pattern (str "span\\.sticky-note-" color " label\\{background:"))
                       html/default-stylesheet))))))
  (testing "AC #4: four distinct colours, so no two annotations are
            indistinguishable"
    (is (= 4 (count html/annotation-colors)))
    (is (= html/annotation-colors #{"yellow" "green" "blue" "pink"})))
  (testing "AC #4: no color= at all defaults to yellow"
    (is (re-find #"class=\"highlight highlight-yellow\""
                 (emit-body (para (highlight (annotation-attr) (str-inline "x")))))))
  (testing "a color= outside the recognised set falls back to yellow
            rather than to a class no stylesheet defines -- an authored
            one is diagnosed on the directive that wrote it (TASK-119),
            and by the time a Document reaches an emitter it may equally
            have come from JSON or a transform rule"
    (let [body (emit-body (para (highlight (annotation-attr :color "purple") (str-inline "x"))))]
      (is (re-find #"class=\"highlight highlight-yellow\"" body))
      (is (not (re-find #"purple" body)) "and the unrecognised value is not written as markup"))))

(deftest annotation-author-is-a-data-attribute-test
  (testing "AC #5: author= appears as a data attribute and NEVER as
            visible page text -- it is what makes an overlay usable as a
            review pass, not something a reader has to read past"
    (let [body (emit-body (para (highlight (annotation-attr :author "DM") (str-inline "marked"))
                                (sticky-note (annotation-attr :author "DM")
                                             (para (str-inline "A comment.")))))]
      (is (re-find #"<mark[^>]* data-author=\"DM\"" body))
      (is (re-find #"<span[^>]* data-author=\"DM\"" body))
      (is (not (re-find #">[^<]*DM[^<]*<" body))
          "DM occurs only inside a tag, never as text between two of them")
      (is (not (re-find #" author=\"" body))
          "and never as a bare author= attribute, which is not an HTML one")))
  (testing "style= is moved out of :props too, and it is the one that
            would have hurt most: the resolver deliberately keeps it on
            the node for the LaTeX target, and `style` IS a real HTML
            attribute -- so left in :props it emitted style=\"footnote\",
            colliding with an authored CSS style prop into a duplicate"
    (let [body (emit-body (para (sticky-note
                                 {:classes [] :props {"style" "footnote" "color" "blue"}}
                                 (para (str-inline "x")))))]
      (is (not (re-find #"style=\"footnote\"" body)))
      (is (re-find #"class=\"sticky-note sticky-note-blue\"" body)))))

(deftest annotation-id-is-the-element-id-test
  (testing "AC #6: an id on either node becomes the element id, so an
            in-page link to it resolves"
    (let [document {:meta {}
                    :blocks [(para (highlight (annotation-attr :id "hl:a") (str-inline "marked"))
                                   (sticky-note (annotation-attr :id "note:1")
                                                (para (str-inline "A comment."))))
                             (para {:t :cross-ref :label "hl:a" :target "hl:a"
                                    :text "the highlight"})]}
          out (html/emit-document document)
          body (body-of out)]
      (is (well-formed? out))
      (is (re-find #"<mark id=\"hl:a\"" body))
      (is (re-find #"<span id=\"note:1\" class=\"sticky-note" body))
      (is (re-find #"<a href=\"#hl:a\">the highlight</a>" body)
          "a resolved cross-reference lands on the element the id names")))
  (testing "authored classes survive alongside the two the emitter adds"
    (is (re-find #"class=\"highlight highlight-blue important\""
                 (emit-body (para (highlight (annotation-attr :color "blue" :classes ["important"])
                                             (str-inline "x"))))))))

(deftest annotated-document-still-parses-as-xml-test
  (testing "AC #7: the emitted document still parses as XML, with both
            node types in it"
    (let [out (html/emit-document
               {:meta {:title [(str-inline "Annotated")]}
                :blocks [(para (str-inline "Hazelnuts ")
                               (highlight (annotation-attr :id "hl:a" :color "green"
                                                           :author "DM")
                                          (str-inline "ripen ")
                                          {:t :emph :inlines [(str-inline "early")]})
                               (str-inline " and drop.")
                               (sticky-note (annotation-attr :id "note:1" :author "DM")
                                            (para (str-inline "Was this the rerun trial?"))))]})]
      (is (well-formed? out))))
  (testing "AC #7: default-stylesheet still contains no > combinator --
            this namespace's tests parse the output as XML, and an
            unescaped > inside <style> is valid HTML but not valid XML"
    (is (not (re-find #">" html/default-stylesheet)))))

(deftest every-sticky-note-gets-its-own-toggle-test
  (testing "TASK-120 review: the checkbox id was derived from the
            FOOTNOTE count, which sticky notes do not contribute to -- so
            every note in a document got the same id, every <label>
            pointed at the first checkbox, and clicking any balloon
            opened that one. It shipped in this project's own worked
            example, where three notes shared one id, and it falsifies
            the very thing the markup exists to do"
    (let [body (emit-body (para (sticky-note (annotation-attr) (para (str-inline "one")))
                                (sticky-note (annotation-attr) (para (str-inline "two"))))
                          (para (sticky-note (annotation-attr) (para (str-inline "three")))))
          ids (mapv second (re-seq #"<input[^>]* id=\"([^\"]+)\"" body))
          fors (mapv second (re-seq #"<label[^>]* for=\"([^\"]+)\"" body))]
      (is (= 3 (count ids)))
      (is (= ids (distinct ids)) (str "every toggle id distinct: " (pr-str ids)))
      (is (= ids fors) "and each label points at its own checkbox, in order")))
  (testing "and the counter is per DOCUMENT, so a second emit-document
            call starts again from one rather than carrying state over"
    (let [once (emit-body (para (sticky-note (annotation-attr) (para (str-inline "x")))))
          twice (emit-body (para (sticky-note (annotation-attr) (para (str-inline "x")))))]
      (is (= once twice) "two identical documents emit identically"))))

;; --- TASK-258: one long line emits in linear time ----------------------------

(deftest long-line-emit-time-test
  (testing "TASK-258: a one-line paragraph of ~400k characters, emphasis and book
            references throughout, renders in time linear in its length. Each
            Inline copied the paragraph's HTML so far (`(str html inline-html)`),
            so this took ~25 s on JDK 17; now well under a second. No code spans:
            on JDK 17 flexmark's own backtick matching is quadratic (TASK-253)."
    (let [unit "*a* @{b:x} w"
          k (quot 400000 (inc (count unit)))
          doc (parser/parse (str (str/join " " (repeat k unit)) "\n"))
          t (System/nanoTime)
          out (html/emit-document doc)
          secs (/ (- (System/nanoTime) t) 1e9)]
      (is (< secs 5.0) (str (* k (inc (count unit))) " characters on one line took " secs " s to emit"))
      (testing "and every inline is rendered, in order"
        (is (= (str "<p>" (str/join " " (repeat k "<em>a</em> <span class=\"book-ref\">b:x</span> w")) "</p>")
               (str/trim (body-of out))))))))

;; --- TASK-261: very many Blocks emit in linear time ---------------------------

(deftest many-blocks-emit-time-test
  (testing "TASK-261: 20k code blocks (~4.2 MB of source) render in time linear in
            their number. Each Block copied the HTML so far (`(str html
            block-html)`), so this took ~15 s on JDK 17; now well under a second.
            Code blocks, because each is cheap to render and large to copy."
    (let [x200 (apply str (repeat 200 "x"))
          doc (parser/parse (str/join "\n" (repeat 20000 (str "```\n" x200 "\n```\n"))))
          t (System/nanoTime)
          out (html/emit-document doc)
          secs (/ (- (System/nanoTime) t) 1e9)]
      (is (< secs 5.0) (str "20000 code blocks took " secs " s to emit"))
      (testing "and every block is rendered, in order"
        (is (= (apply str (repeat 20000 (str "<pre><code>" x200 "\n</code></pre>")))
               (str/trim (body-of out)))))))
  (testing "TASK-261: 20k footnotes and 20k list items render in linear time too,
            and table rows are joined in order. Each footnote walked every footnote
            before it to fill in its own entry, and the items and rows were joined
            like the Blocks (~17 s for the footnotes alone on JDK 17). Only 2000
            rows: reading a table is itself quadratic in flexmark."
    (let [n 20000
          notes (parser/parse (str/join (map #(str "p[^" % "]\n\n[^" % "]: n" % "\n\n") (range n))))
          items (parser/parse (str/join (map #(str "- i" % "\n") (range n))))
          rows (parser/parse (str "| a |\n|---|\n" (str/join (map #(str "| r" % " |\n") (range 2000)))))
          t (System/nanoTime)
          [notes-out items-out rows-out] (mapv html/emit-document [notes items rows])
          secs (/ (- (System/nanoTime) t) 1e9)]
      (is (< secs 5.0) (str n " footnotes, list items and table rows took " secs " s to emit"))
      (is (= (map #(str "<li id=\"fn" (inc %) "\"><p>n" % "</p>") (range n))
             (re-seq #"<li id=\"fn\d+\"><p>n\d+</p>" (body-of notes-out)))
          "every footnote's own text, in its own numbered entry")
      (is (= (map #(str "<li>i" % "</li>") (range n)) (re-seq #"<li>i\d+</li>" (body-of items-out))))
      (is (= (map #(str "<tr><td><p>r" % "</p></td></tr>") (range 2000))
             (re-seq #"<tr><td><p>r\d+</p></td></tr>" (body-of rows-out)))))))
