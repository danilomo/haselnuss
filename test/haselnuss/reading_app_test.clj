(ns ^:slow haselnuss.reading-app-test
  "The reader app's reading screen (TASK-275; doc-12, \"Reading screen\",
  \"Links to other books\", \"The app shell\"): the controls `app.js` adds
  around a book page, driven in a real headless Chrome on a phone-sized
  page (`haselnuss.test-browser`) over a real `serve/start!` of the site
  fixture set, plus a small site of its own for maths, collapsible
  sections and tables. Taps are real mouse events at screen points, so
  what the reader's finger hits is what is tested.

  Without Chrome the browser tests print a note and pass without running;
  the checks on the built files below them always run."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.cli :as cli]
            [haselnuss.emit.html :as html]
            [haselnuss.site :as site]
            [haselnuss.test-browser :as tb :refer [cdp js wait-for]])
  (:import (java.io File StringWriter)))

;; --- helpers -------------------------------------------------------------------------------

(def ^:private ready
  "document.documentElement.getAttribute('data-reader') === 'ready'")

(defn- open-book
  "Navigate to `url` and wait for the reading screen."
  [b url]
  (cdp b "Page.navigate" {"url" url})
  (wait-for b (str url " ready") ready))

(defn- wait-page
  "Wait until the page at a path holding `part` has its reading screen."
  [b part]
  (wait-for b (str "page " part)
            (str "location.pathname.includes(" (json/write-str part) ") && " ready)))

(defn- tap
  "A real tap (mouse press and release) at the screen point `[x y]`."
  [b [x y]]
  (doseq [t ["mousePressed" "mouseReleased"]]
    (cdp b "Input.dispatchMouseEvent" {"type" t "x" x "y" y "button" "left" "clickCount" 1})))

(defn- tap-on
  "Tap the middle of the first element `selector` matches, scrolled into
  view -- once it has stayed put for 150 ms, so a layout still settling
  (MathJax typesetting the page, the app's own start-up) cannot move it
  out from under the tap."
  [b selector]
  (let [point (fn [scroll?]
                (js b (str "(() => { const e = document.querySelector(" (json/write-str selector) ");"
                           (when scroll? " e.scrollIntoView({block: 'center'});")
                           " const r = e.getClientRects()[0]; return [r.left + r.width / 2, r.top + r.height / 2]; })()")))]
    (loop [tries 0]
      (let [pt (point true)]
        (Thread/sleep 150)
        (if (or (= pt (point false)) (>= tries 20))
          (tap b pt)
          (recur (inc tries)))))))

(defn- tap-on-in-place
  "Tap the middle of the first element `selector` matches where it is now,
  without scrolling (it must be on screen)."
  [b selector]
  (let [pt (js b (str "(() => { const r = document.querySelector(" (json/write-str selector) ").getClientRects()[0];"
                      " return r.top >= 0 && r.bottom <= innerHeight ? [r.left + r.width / 2, r.top + r.height / 2] : null; })()"))]
    (is pt (str selector " is on screen"))
    (when pt (tap b pt))))

(def ^:private plain-text-point
  "JavaScript answering a point in the middle band of the screen that is no
  link, button or other control -- book text where there is some, else the
  page below a short book -- where a reader taps to show the controls."
  (str "(() => { let blank = null;"
       " for (let y = Math.round(innerHeight * 0.4); y < innerHeight * 0.65; y += 7)"
       " for (let x = 30; x < innerWidth - 30; x += 17) { const e = document.elementFromPoint(x, y);"
       " if (!e || e.closest('a, button, input, summary, label, .book-ref, mjx-container, #hz-reader')) continue;"
       " if (e.closest('p, li, blockquote')) return [x, y];"
       " if (!blank && (e === document.body || e === document.documentElement)) blank = [x, y]; }"
       " return blank; })()"))

(defn- tap-text [b]
  (let [pt (js b plain-text-point)]
    (is pt "a point over plain text in the middle of the screen")
    (tap b pt)))

(def ^:private controls-shown?
  "Both bars visible and done sliding in (a tap during the slide would miss)."
  (str "document.documentElement.classList.contains('hz-show')"
       " && getComputedStyle(document.querySelector('.hz-top')).visibility === 'visible'"
       " && document.querySelector('.hz-top').getBoundingClientRect().top >= 0"
       " && document.querySelector('.hz-bottom').getBoundingClientRect().bottom <= innerHeight + 0.5"))

(defn- write! [^File root rel content]
  (let [f (io/file root rel)]
    (io/make-parents f)
    (spit f content)
    f))

;; --- the built pages (no browser) ---------------------------------------------------------------

(deftest site-page-head-test
  (testing "a site page gets viewport-fit=cover and the theme script, before <body>, before the app's stylesheet"
    (let [page (site/with-reader-app
                 (str "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/>"
                      "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>"
                      "<title>T</title></head><body><p>x</p></body></html>")
                 "books/lib/a/index.html")]
      (is (str/includes? page "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\"/>"))
      (is (= 1 (count (re-seq #"name=\"viewport\"" page))) "one viewport meta, not two")
      (is (< (str/index-of page site/theme-head)
             (str/index-of page "<link rel=\"stylesheet\" href=\"../../../app.css\"/>")
             (str/index-of page "</head>")
             (str/index-of page "<body>")))))
  (testing "the theme script is tiny and reads only the app's own two keys"
    (is (< (count site/theme-head) 1000))
    (is (= #{"haselnuss.theme" "haselnuss.fontSize"} (set (re-seq #"haselnuss\.\w+" site/theme-head))))))

(deftest app-mathjax-config-test
  (testing "app.js configures MathJax exactly as the html emitter does (TASK-217), and pins the same release"
    (let [app (slurp (io/resource "haselnuss/site/app.js"))
          config @#'html/mathjax-config
          body (subs config (count "<script>") (- (count config) (count "</script>")))
          script @#'html/mathjax-script
          src (second (re-find #"src=\"([^\"]+)\"" script))]
      (is (str/starts-with? body "window.MathJax={"))
      (is (str/includes? app body) "the emitter's mathjax-config, verbatim")
      (is (= 1 (count (re-seq #"window\.MathJax=\{" app))) "and no other configuration")
      (is (str/includes? app (str "\"" src "\""))))))

;; --- the fixture site in Chrome --------------------------------------------------------------------

(deftest reading-screen-test
  (tb/with-site-dir tb/build-fixture-site
    (fn [dir]
      (testing "every resolved book link on a page says its passage, and the target's previews.json has it"
        (let [page (slurp (io/file dir "books/reformed/catecismo-heidelberg/index.html"))
              links (re-seq #"<a class=\"book-ref\" data-passage=\"([^\"]*)\" href=\"\.\./\.\./([^\"#]+)/index\.html#[^\"]*\">" page)]
          (is (= 9 (count links)))
          (is (= 9 (count (re-seq #"class=\"book-ref\"" page))) "no book link without its passage")
          (doseq [[_ passage target] links
                  :let [previews (get (json/read-str (slurp (io/file dir "books" target "previews.json"))) "previews")]]
            (is (some #(= passage (get % "passage")) previews) (str target " " passage)))))
      (tb/call-with-browser
       "reading-app-test"
       (fn [b]
         (tb/with-server dir
           (fn [srv]
             (let [base (:url srv)
                   catechism (str base "books/reformed/catecismo-heidelberg/index.html")
                   romanos (str base "books/bible/blivre/romanos/index.html")]
               (open-book b catechism)

               (testing "the page fits the phone: a long URL in the text does not zoom it out"
                 (is (= [390 390] (js b "[innerWidth, document.documentElement.scrollWidth]"))))

               (testing "the running header shows the current section and the footer the percentage read"
                 (is (= "0%" (js b "document.querySelector('.hz-pct').textContent")))
                 (is (str/starts-with? (js b "document.querySelector('.hz-running').textContent") "Catecismo de Heidelberg"))
                 (js b "document.getElementById('sec:q2').scrollIntoView()")
                 (wait-for b "running header q2" "document.querySelector('.hz-running').textContent.includes('2. O que você deve saber')")
                 (is (< 0 (js b "parseInt(document.querySelector('.hz-pct').textContent)") 101))
                 (js b "window.scrollTo(0, document.documentElement.scrollHeight)")
                 (wait-for b "100%" "document.querySelector('.hz-pct').textContent === '100%'"))

               (testing "the controls start hidden; a tap in the middle shows them, without moving the text"
                 (js b "window.scrollTo(0, 400)")
                 (Thread/sleep 100)
                 (is (not (js b controls-shown?)))
                 (let [before (js b "[scrollY, document.documentElement.scrollHeight, document.getElementById('sec:q1').getBoundingClientRect().top]")]
                   (tap-text b)
                   (wait-for b "controls shown" controls-shown?)
                   (Thread/sleep 300)
                   (is (= before (js b "[scrollY, document.documentElement.scrollHeight, document.getElementById('sec:q1').getBoundingClientRect().top]"))
                       "no layout shift")
                   (is (js b "document.querySelector('.hz-top').getBoundingClientRect().top >= 0 && document.querySelector('.hz-bottom').getBoundingClientRect().bottom <= innerHeight + 1"))
                   (is (= base (js b "document.querySelector('.hz-library').href")) "‹ Library goes to the home screen")
                   (is (= "‹ Library" (js b "document.querySelector('.hz-library').textContent")))
                   (is (js b "document.querySelector('.hz-back').hidden") "no Back before following a link")
                   (is (= "Catecismo de Heidelberg (1563), Domingo 1" (js b "document.querySelector('.hz-title').textContent")))
                   (is (= "none" (js b "getComputedStyle(document.querySelector('.hz-aa')).webkitUserSelect || getComputedStyle(document.querySelector('.hz-aa')).userSelect"))
                       "no selection on the controls")
                   (tap-text b)
                   (wait-for b "controls hidden" (str "!(" controls-shown? ")"))
                   (is (= before (js b "[scrollY, document.documentElement.scrollHeight, document.getElementById('sec:q1').getBoundingClientRect().top]")))))

               (testing "the slider moves through the book"
                 (tap-text b)
                 (wait-for b "controls" controls-shown?)
                 (js b "(() => { const s = document.querySelector('.hz-slider'); s.value = '500'; s.dispatchEvent(new Event('input')); })()")
                 (let [[y most] (js b "[scrollY, document.documentElement.scrollHeight - innerHeight]")]
                   (is (< (Math/abs (- y (/ most 2.0))) 2) (str y " of " most)))
                 (wait-for b "50%" "document.querySelector('.hz-pct').textContent === '50%'"))

               (testing "Contents lists the book's own headings; one jumps there"
                 (tap-on b ".hz-contents")
                 (wait-for b "contents" "document.querySelectorAll('.hz-toc button').length > 0")
                 (is (= ["1 Sobre esta edição" "2 Domingo 1"
                         "2.1 1. Qual é o seu único fundamento, na vida e na morte?"
                         "2.2 2. O que você deve saber para viver e morrer neste fundamento?"]
                        (js b "Array.from(document.querySelectorAll('.hz-toc button')).map(b => b.textContent)")))
                 (tap-on b ".hz-toc li:nth-child(3) button")
                 (wait-for b "jumped" "document.querySelector('.hz-overlay').hidden")
                 (let [top (js b "document.getElementById('sec:q1').firstElementChild.getBoundingClientRect().top")
                       header (js b "document.querySelector('.hz-running').getBoundingClientRect().bottom")]
                   (is (<= header top (+ header 20)) (str "the heading just below the running header: " top " " header)))
                 (wait-for b "running header q1" "document.querySelector('.hz-running').textContent.includes('1. Qual é')"))

               (testing "Search finds text in this book without case or accents; a result shows it"
                 (tap-text b)
                 (wait-for b "controls" controls-shown?)
                 (tap-on b ".hz-search")
                 (wait-for b "search box" "document.querySelector('.hz-search-input')")
                 (js b "(() => { const i = document.querySelector('.hz-search-input'); i.value = 'MISERIA'; i.dispatchEvent(new Event('input')); })()")
                 (wait-for b "results" "document.querySelectorAll('.hz-hit').length === 2")
                 (is (= ["miséria" "miséria"] (js b "Array.from(document.querySelectorAll('.hz-hit mark')).map(m => m.textContent)")))
                 (tap-on b ".hz-hit")
                 (wait-for b "shown" "document.querySelector('.hz-overlay').hidden")
                 (is (= "miséria" (js b "String(getSelection())")))
                 (is (js b "(() => { const r = getSelection().getRangeAt(0).getBoundingClientRect(); return r.top > 0 && r.bottom < innerHeight; })()")
                     "on screen"))

               (testing "Aa: font size and theme, kept on this device and applied before first paint"
                 (tap-text b)
                 (wait-for b "controls" controls-shown?)
                 (tap-on b ".hz-aa")
                 (wait-for b "aa" "document.querySelector('.hz-theme-dark')")
                 (tap-on b ".hz-theme-dark")
                 (tap-on b ".hz-larger")
                 (is (= "dark" (js b "document.documentElement.dataset.theme")))
                 (is (= "rgb(18, 18, 18)" (js b "getComputedStyle(document.documentElement).backgroundColor")))
                 (is (= "110%" (js b "document.documentElement.style.fontSize")))
                 (is (= ["dark" "110"] (js b "[localStorage.getItem('haselnuss.theme'), localStorage.getItem('haselnuss.fontSize')]")))
                 (tap-on b ".hz-theme-sepia")
                 (is (= "rgb(244, 236, 216)" (js b "getComputedStyle(document.documentElement).backgroundColor")))
                 (tap-on b ".hz-theme-dark")
                 (js b "document.querySelector('.hz-sheet .hz-close').click()"))

               (testing "night theme never flashes white: dark from the moment <body> exists (AC #4)"
                 (cdp b "Page.enable" {})
                 (let [{id "identifier"}
                       (cdp b "Page.addScriptToEvaluateOnNewDocument"
                            {"source" (str "window.__hz = {};"
                                           "const look = () => ({theme: document.documentElement.getAttribute('data-theme'),"
                                           " bg: getComputedStyle(document.documentElement).backgroundColor,"
                                           " font: document.documentElement.style.fontSize});"
                                           "new MutationObserver((ms, o) => { if (document.body) { window.__hz.body = look(); o.disconnect(); } })"
                                           ".observe(document, {childList: true, subtree: true});"
                                           "document.addEventListener('DOMContentLoaded', () => { window.__hz.dcl = look(); });")})]
                   (try
                     (doseq [url [catechism romanos (str base "books/reformed/catkins/index.html")]]
                       (open-book b url)
                       (is (= {"theme" "dark" "bg" "rgb(18, 18, 18)" "font" "110%"} (js b "window.__hz.body")) url)
                       (is (= {"theme" "dark" "bg" "rgb(18, 18, 18)" "font" "110%"} (js b "window.__hz.dcl")) url))
                     (testing "with nothing stored, the system's dark preference is followed"
                       (js b "localStorage.clear()")
                       (cdp b "Emulation.setEmulatedMedia" {"features" [{"name" "prefers-color-scheme" "value" "dark"}]})
                       (open-book b catechism)
                       (is (= {"theme" "dark" "bg" "rgb(18, 18, 18)" "font" ""} (js b "window.__hz.body")))
                       (cdp b "Emulation.setEmulatedMedia" {"features" [{"name" "prefers-color-scheme" "value" "light"}]})
                       (open-book b catechism)
                       (is (= {"theme" "light" "bg" "rgb(255, 255, 255)" "font" ""} (js b "window.__hz.body"))))
                     (finally
                       (cdp b "Page.removeScriptToEvaluateOnNewDocument" {"identifier" id})
                       (cdp b "Emulation.setEmulatedMedia" {"features" []})))))

               (testing "tapping @{bible:romanos#14:8} shows the verse in a sheet; Open then Back returns to the same spot (AC #2)"
                 (open-book b catechism)
                 (js b "document.querySelector('a.book-ref[data-passage=\"14:8\"]').scrollIntoView({block: 'center'})")
                 (js b "window.scrollBy(0, 37)")
                 (Thread/sleep 100)
                 (let [y (js b "scrollY")]
                   (tap-on-in-place b "a.book-ref[data-passage=\"14:8\"]")
                   (wait-for b "sheet" "document.querySelector('.hz-preview blockquote')")
                   (is (= y (js b "scrollY")) "the page did not move")
                   (is (str/starts-with? (js b "document.querySelector('.hz-preview blockquote').textContent")
                                         "Porque, se vivemos, para o Senhor vivemos"))
                   (is (= "— Romanos 14:8 · bible" (js b "document.querySelector('.hz-source').textContent")))
                   (wait-for b "open label" "document.querySelector('.hz-open').textContent === 'Open Romanos'")
                   (is (= "Close" (js b "document.querySelector('.hz-close-preview').textContent")))
                   (is (str/ends-with? (js b "document.querySelector('.hz-preview figcaption a').href")
                                       "/books/bible/blivre/romanos/index.html#v14-8")
                       "the preview's links are made absolute")
                   (testing "Close closes; the preview is cached for the session"
                     (tap-on b ".hz-close-preview")
                     (wait-for b "closed" "document.querySelector('.hz-overlay').hidden")
                     (js b (str "window.scrollTo(0, " y ")"))
                     (tap-on-in-place b "a.book-ref[data-passage=\"14:8\"]")
                     (wait-for b "sheet again" "document.querySelector('.hz-preview blockquote')")
                     (is (= 1 (js b "performance.getEntriesByType('resource').filter(r => r.name.endsWith('/romanos/previews.json')).length"))))
                   (tap-on b ".hz-open")
                   (wait-page b "/romanos/")
                   (is (str/ends-with? (js b "location.href") "romanos/index.html#v14-8"))
                   (is (= "‹ Back to Catecismo de Heidelberg (1563), Domingo 1" (js b "document.querySelector('.hz-back').textContent")))
                   (is (not (js b "document.querySelector('.hz-back').hidden")))
                   (tap-text b)
                   (wait-for b "controls" controls-shown?)
                   (tap-on b ".hz-back")
                   (wait-page b "/catecismo-heidelberg/")
                   (Thread/sleep 300)
                   (is (= y (js b "scrollY")) "back at the exact spot")
                   (is (js b "document.querySelector('.hz-back').hidden"))
                   (testing "the browser's own Back also returns, and spends that Back"
                     (tap-on-in-place b "a.book-ref[data-passage=\"14:8\"]")
                     (wait-for b "sheet" "document.querySelector('.hz-open')")
                     (tap-on b ".hz-open")
                     (wait-page b "/romanos/")
                     (js b "history.back()")
                     (wait-page b "/catecismo-heidelberg/")
                     (wait-for b "restored by the browser" (str "Math.abs(scrollY - " y ") <= 2"))
                     (is (js b "document.querySelector('.hz-back').hidden"))
                     (is (= [] (js b "JSON.parse(sessionStorage.getItem('haselnuss.back'))"))))))

               (testing "Referenced by on Romanos lists the catechism's quoting passages, grouped by passage (AC #3)"
                 (open-book b romanos)
                 (tap-text b)
                 (wait-for b "controls" controls-shown?)
                 (tap-on b ".hz-refs")
                 (wait-for b "refs" "document.querySelectorAll('.hz-ref').length === 4")
                 (wait-for b "titles" "document.querySelector('.hz-ref-title').textContent.startsWith('Catecismo')")
                 (is (= ["Romanos 3:10" "Romanos 8:28" "Romanos 14:8"]
                        (js b "Array.from(document.querySelectorAll('.hz-group')).map(h => h.textContent)")))
                 (is (= [["Catecismo de Heidelberg (1563), Domingo 1" "sec:q2 · quotes"]
                         ["Catecismo de Heidelberg (1563), Domingo 1" "sec:edicao · links"]
                         ["Catecismo de Heidelberg (1563), Domingo 1" "sec:q1 · links and quotes"]
                         ["Catecismo de Heidelberg (1563), Domingo 1" "sec:q1 · links and quotes"]]
                        (js b "Array.from(document.querySelectorAll('.hz-ref')).map(b => [b.querySelector('.hz-ref-title').textContent, b.querySelector('.hz-sub').textContent])")))
                 (tap-on b ".hz-ref")
                 (wait-page b "/catecismo-heidelberg/")
                 (is (str/ends-with? (js b "location.href") "catecismo-heidelberg/index.html#sec:q2"))
                 (is (<= 0 (js b "document.getElementById('sec:q2').getBoundingClientRect().top") 60) "at the citing passage")
                 (is (= "‹ Back to Romanos" (js b "document.querySelector('.hz-back').textContent"))))

               (testing "a dangling ?? link opens nothing"
                 (open-book b (str base "books/reformed/dangling/index.html"))
                 (is (= "??" (js b "document.querySelector('span.book-ref').textContent")))
                 (let [href (js b "location.href")]
                   (tap-on b "span.book-ref")
                   (Thread/sleep 400)
                   (is (js b "document.querySelector('.hz-overlay').hidden"))
                   (is (not (js b controls-shown?)))
                   (is (= href (js b "location.href")))))

               (testing "‹ Library opens the home screen"
                 (tap-text b)
                 (wait-for b "controls" controls-shown?)
                 (tap-on b ".hz-library")
                 (wait-for b "home" "document.documentElement.getAttribute('data-home') === 'ready'"))))))))))

;; --- maths, collapsible sections, tables: as in a plain html build -----------------------------------

(def ^:private book-a
  (str "---\ntitle: Alpha\n---\n\n"
       "# Maths {#sec:x}\n\n"
       "The share $s = \\frac{f}{m+f}$ and $\\href{javascript:alert(1)}{x}$ stay maths.\n\n"
       "$$e^{i\\pi} + 1 = 0$$\n\n"
       "# Things {#sec:things}\n\n"
       ":::{collapsable summary=\"A note on squirrels\"}\n"
       "Netting does not work.\n"
       ":::\n\n"
       "| Nut | Weight |\n|-----|--------|\n| Hazel | 2 g |\n| Walnut | 10 g |\n\n"
       "> A quoted line.\n\n"
       (str/join "\n\n" (repeat 40 "Filler text so the page scrolls, with no link in it at all."))
       "\n"))

(def ^:private book-b
  (str "---\ntitle: Beta\n---\n\n# Links {#sec:l}\n\n"
       "See @{lib:a#sec:x} for the maths, @{lib:c#sec:y} for a hostile passage"
       " and @{lib:b#sec:end} for the end of this book.\n\n"
       (str/join "\n\n" (repeat 30 "More filler text, plain."))
       "\n\n# The end {#sec:end}\n\nDone.\n"))

(def ^:private book-c
  "A cited passage whose markup tries to reach outside the sheet: a style that
  would cover the page, and names that would shadow `document.createElement`
  and `window.MathJax` (an `<img name>` is a named property of both)."
  (str "---\ntitle: Gamma\n---\n\n# Hostile {#sec:y}\n\n"
       "[cover]{style=\"position:fixed;top:0;left:0;width:100vw;height:100vh;z-index:99999;background:red\"}"
       " ![a](https://127.0.0.1:1/a.png){name=\"createElement\"}"
       " ![b](https://127.0.0.1:1/b.png){name=\"MathJax\"}\n"))

(defn- small-site []
  (let [root (tb/temp-dir)]
    (write! root "src/lib/a.hdoc" book-a)
    (write! root "src/lib/b.hdoc" book-b)
    (write! root "src/lib/c.hdoc" book-c)
    (let [yaml (write! root "src/libraries.yaml" "- name: lib\n  type: filesystem\n  path: lib\n")]
      (tb/build-site yaml (io/file root "src") (io/file root "site")))))

(defn- body-of [^String page]
  (subs page (str/index-of page "<body>") (+ (str/last-index-of page "</body>") 7)))

(deftest plain-html-behaviour-test
  (tb/with-site-dir small-site
    (fn [dir]
      (let [root (.getParentFile ^File dir)
            plain (io/file root "plain.html")
            _ (binding [*err* (StringWriter.)]
                (cli/main* [(str (io/file root "src/lib/a.hdoc")) "--target" "html" "--output" (str plain)]
                           {:library-env {} :library-home "/nonexistent/haselnuss-test-home"}))
            page (slurp (io/file dir "books/lib/a/index.html"))
            previews (json/read-str (slurp (io/file dir "books/lib/a/previews.json")))]
        (testing "a page's body is exactly the plain html build's: the app adds controls, not a renderer (AC #5)"
          (is (= (body-of (slurp plain)) (body-of page)))
          (is (str/includes? page "<details class=\"collapsable\""))
          (is (str/includes? page "<table"))
          (is (str/includes? page "class=\"math inline\"")))
        (testing "previews.json says which previews hold maths"
          (is (= [["sec:x" true]] (map (juxt #(get % "passage") #(get % "math")) (get previews "previews")))))
        (tb/call-with-browser
         "reading-app-test"
         (fn [b]
           (tb/with-server dir
             (fn [srv]
               (let [base (:url srv)]
                 (open-book b (str base "books/lib/a/index.html"))
                 (testing "a collapsible section opens and closes on its summary, and the controls stay put"
                   (is (not (js b "document.querySelector('details.collapsable').open")))
                   (tap-on b "details.collapsable summary")
                   (wait-for b "opened" "document.querySelector('details.collapsable').open")
                   (is (not (js b controls-shown?)))
                   (Thread/sleep 700)   ; two taps closer than this are a double tap
                   (tap-on b "details.collapsable summary")
                   (wait-for b "closed" "!document.querySelector('details.collapsable').open"))
                 (testing "a table and a quote lay out as the stylesheet says"
                   (is (= "collapse" (js b "getComputedStyle(document.querySelector('table')).borderCollapse")))
                   (is (< 300 (js b "document.querySelector('table').getBoundingClientRect().width")))
                   (is (= "3px" (js b "getComputedStyle(document.querySelector('blockquote')).borderLeftWidth"))))
                 (testing "the page's own MathJax typesets its maths, when the CDN can be reached"
                   (if (js b "new Promise(res => { const t0 = Date.now(); (function poll() { if (document.querySelector('mjx-container')) res(true); else if (Date.now() - t0 > 12000) res(false); else setTimeout(poll, 100); })(); })")
                     (is (zero? (js b "document.querySelectorAll('a[href^=\"javascript\" i]').length")) "no \\href link")
                     (println "reading-app-test: MathJax CDN unreachable; page typesetting not checked")))

                 (open-book b (str base "books/lib/b/index.html"))
                 (testing "a cited passage's markup stays inside the sheet: no covering style, no shadowed names"
                   (tap-on b "a.book-ref[data-passage=\"sec:y\"]")
                   (wait-for b "hostile sheet" "document.querySelector('.hz-preview img')")
                   (is (= ["hz-pv-createElement" "hz-pv-MathJax"]
                          (js b "Array.from(document.querySelectorAll('.hz-preview img')).map(i => i.getAttribute('name'))")))
                   (is (js b "document.createElement === Document.prototype.createElement && typeof document.createElement === 'function'"))
                   (is (not (js b "window.MathJax instanceof HTMLElement")))
                   (is (js b (str "(() => { const p = document.querySelector('.hz-preview').getBoundingClientRect();"
                                  " const c = document.querySelector('.hz-preview span[style]').getBoundingClientRect();"
                                  " return c.left >= p.left - 0.5 && c.top >= p.top - 0.5; })()"))
                       "the fixed element is placed in the preview, not on the screen")
                   (is (not (js b "!!document.elementFromPoint(5, 5).closest('.hz-preview')"))
                       "the top corner of the screen is not covered")
                   (tap-on b ".hz-close-preview")
                   (wait-for b "closed" "document.querySelector('.hz-overlay').hidden"))

                 (testing "Open on a link to the same book moves within it, with no Back to itself"
                   (tap-on b "a.book-ref[data-passage=\"sec:end\"]")
                   (wait-for b "self sheet" "document.querySelector('.hz-open') && document.querySelector('.hz-sheet[data-panel=preview] .hz-status')")
                   (tap-on b ".hz-open")
                   (wait-for b "moved" "location.hash === '#sec:end' && document.querySelector('.hz-overlay').hidden")
                   (is (= [] (js b "JSON.parse(sessionStorage.getItem('haselnuss.back') || '[]')")))
                   (is (js b "document.querySelector('.hz-back').hidden"))
                   (open-book b (str base "books/lib/b/index.html"))
                   (is (js b "document.querySelector('.hz-back').hidden")))

                 (testing "a preview with maths is typeset only through the emitter's safe configuration"
                   (is (not (js b "document.getElementById('MathJax-script')")) "a page without maths loads no MathJax")
                   (tap-on b "a.book-ref[data-passage=\"sec:x\"]")
                   (wait-for b "sheet" "document.querySelector('.hz-preview[data-math]')")
                   (is (= "https://cdn.jsdelivr.net/npm/mathjax@3.2.2/es5/tex-mml-chtml.js"
                          (js b "document.getElementById('MathJax-script').src")))
                   ;; the app gives up after 20 s, so data-math always settles; a CDN
                   ;; that drops the request silently is a skip, not a failure
                   (let [state (js b (str "new Promise(res => { const t0 = Date.now(); (function poll() {"
                                          " const m = document.querySelector('.hz-preview').dataset.math;"
                                          " if (m !== 'pending') res(m); else if (Date.now() - t0 > 25000) res('pending');"
                                          " else setTimeout(poll, 100); })(); })"))]
                     (is (contains? #{"done" "failed"} state) "data-math settles")
                     (if (= "done" state)
                       (do
                         (is (pos? (js b "document.querySelectorAll('.hz-preview mjx-container').length")))
                         (is (zero? (js b "document.querySelectorAll('a[href^=\"javascript\" i]').length")) "\\href stays undefined")
                         (is (js b "MathJax.config.options.safeOptions.allow.URLs === 'none'"))
                         (is (zero? (js b "Array.from(document.querySelectorAll('p')).filter(p => !p.closest('#hz-reader') && p.querySelector('mjx-container')).length"))
                             "the rest of the page is left alone"))
                       (println "reading-app-test: MathJax CDN unreachable; preview typesetting not checked")))))))))))))
