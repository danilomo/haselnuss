(ns ^:slow haselnuss.html-mobile-test
  "The plain `--target html` build on a phone (TASK-293): a long unbroken
  URL in body text used to widen the layout viewport (504 px on a 390 px
  screen for the site fixture's catechism), so Chrome zoomed the whole page
  out. Driven in a real headless Chrome (`haselnuss.test-browser`); without
  one the browser tests print a note and pass without running."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.cli :as cli]
            [haselnuss.test-browser :as tb :refer [js]])
  (:import (java.io File StringWriter)))

(def ^:private long-url
  (str "https://example.org/" (apply str (repeat 12 "segment-of-a-very-long-path/")) "end.html"))

(def ^:private stress-doc
  "Every block kind whose width could still be forced out by a long word
  or a wide child, with a long URL in each place text can hold one."
  (str "---\ntitle: Wide\nlang: en\n---\n\n"
       "# Heading " long-url "\n\n"
       "Plain paragraph " long-url " and more.\n\n"
       "Inline `" (apply str (repeat 30 "codeword")) "` here.\n\n"
       "> Quote " long-url "\n\n"
       "- Item " long-url "\n- Second\n\n"
       "1. Numbered " long-url "\n\n"
       "Note.[^n]\n\n[^n]: Footnote " long-url "\n\n"
       "![Caption " long-url "](missing.png){#fig:wide}\n\n"
       "```\n" (apply str (repeat 40 "preformatted-line-")) "\n```\n\n"
       "$$" (str/join "+" (repeat 60 "xxxxxx")) "$$\n"))

(def ^:private table-doc
  "A table with one unbroken word in a cell. Tables keep their own layout
  (AC #2): the word is not broken, so what a wide table does to the page
  width is not changed by TASK-293."
  (str "---\ntitle: Table\nlang: en\n---\n\n"
       "| A | B |\n|---|---|\n"
       "| " (apply str (repeat 45 "widecell")) " | short |\n"))

(def ^:private narrow-table-doc
  "A table of ordinary words with several columns: at 390 px every cell
  wraps between words, never inside one (TASK-312)."
  (str "---\ntitle: Narrow\nlang: en\n---\n\n"
       "| One | Two | Three | Four |\n|---|---|---|---|\n"
       "| alpha beta gamma delta | epsilon zeta eta theta | iota kappa lambda mu | nu xi omicron pi |\n"))

(def ^:private subfigure-doc
  "A two-column figure whose panel caption is one very long word."
  (str "---\ntitle: Panels\nlang: en\n---\n\n"
       "::::{figure #fig:sens caption=\"Sensitivity\" columns=2}\n\n"
       ":::{subfigure #fig:a caption=\"" (apply str (repeat 45 "panelcaption")) "\"}\n![](pic.png)\n:::\n\n"
       ":::{subfigure #fig:b caption=\"Short\"}\n![](pic.png)\n:::\n\n"
       "::::\n"))

(defn- build-plain
  "Compile the hdoc `src` text to plain html under `dir`; the file."
  ^File [^File dir base src]
  (let [in (io/file dir (str base ".hdoc"))
        out (io/file dir (str base ".html"))]
    (spit in src)
    (binding [*err* (StringWriter.) *out* (StringWriter.)]
      (cli/main* [(str in) "--target" "html" "--output" (str out)]
                 {:library-env {} :library-home "/nonexistent/haselnuss-test-home"}))
    out))

(defn- open
  "Navigate to the file `f` and wait until it has loaded. The network is
  blocked first: a page with maths asks the MathJax CDN for a script, and
  `readyState` stays `interactive` until that fetch settles, which made the
  wait time out under load. Blocked, the page settles at once and MathJax
  never typesets, so the raw TeX is what the widths are measured on."
  [b ^File f]
  (tb/cdp b "Network.enable" {})
  (tb/cdp b "Network.setBlockedURLs" {"urls" ["http://*" "https://*"]})
  (tb/cdp b "Page.navigate" {"url" (str "file://" (.getPath f))})
  (tb/wait-for b "the page" (str "location.href === " (pr-str (str "file://" (.getPath f)))
                                 " && document.readyState === 'complete'")))

(def ^:private fits
  "JavaScript: the layout viewport's width and the page's scroll width."
  "[innerWidth, document.documentElement.scrollWidth]")

(deftest long-urls-do-not-zoom-the-page-out-test
  (let [dir (tb/temp-dir)]
    (try
      (let [fixture (build-plain dir "catechism"
                                 (slurp "test/fixtures/site/reformed/catecismo-heidelberg.hdoc"))
            stress (build-plain dir "stress" stress-doc)]
        (tb/call-with-browser
         "html-mobile-test"
         (fn [b]
           (testing "the site fixture's catechism fits a 390 px phone (AC #1)"
             (open b fixture)
             (is (= [390 390] (js b fits))))
           (testing "a long word in every kind of block still fits"
             (open b stress)
             (is (= [] (js b "['figcaption', 'blockquote', 'li', 'h1', '.math.display', 'pre']
                                .filter(s => !document.querySelector(s))"))
                 "the stress document really has each block kind")
             (is (= [390 390] (js b fits))))
           (testing "pre keeps its own scroll box and does not wrap (AC #2)"
             (is (= "pre" (js b "getComputedStyle(document.querySelector('pre')).whiteSpace")))
             (is (js b "(() => { const p = document.querySelector('pre');
                                 return p.scrollWidth > p.clientWidth
                                        && getComputedStyle(p).overflowX === 'auto'; })()"))
             (is (js b "document.querySelector('pre').getBoundingClientRect().right <= innerWidth")))
           (testing "display maths scrolls in its own box, not wrapped (AC #2)"
             (is (js b "(() => { const m = document.querySelector('.math.display');
                                 return m.scrollWidth > m.clientWidth
                                        && getComputedStyle(m).overflowX === 'auto'
                                        && getComputedStyle(m).overflowWrap === 'normal'; })()")))
           (testing "a table keeps its own layout: an unbroken word in a cell stays on one line, whole (AC #2)"
             (open b (build-plain dir "table" table-doc))
             (is (= [1 true]
                    (js b "(() => { const td = document.querySelector('td');
                                    const r = document.createRange();
                                    const w = document.createTreeWalker(td, NodeFilter.SHOW_TEXT);
                                    r.selectNodeContents(w.nextNode());
                                    return [r.getClientRects().length,
                                            td.getBoundingClientRect().width >= r.getBoundingClientRect().width]; })()"))))
           (testing "a wide table scrolls in its own box and the page keeps its width (TASK-312)"
             (open b (build-plain dir "table" table-doc))
             (is (= [390 390] (js b fits)))
             (is (js b "(() => { const w = document.querySelector('div.table-scroll');
                                 return w.scrollWidth > w.clientWidth
                                        && getComputedStyle(w).overflowX === 'auto'
                                        && w.querySelector('table') !== null; })()")))
           (testing "ordinary words in a narrow table are not broken (TASK-312)"
             (open b (build-plain dir "narrow" narrow-table-doc))
             (is (= [390 390] (js b fits)))
             (is (= [] (js b "(() => [...document.querySelectorAll('td')].flatMap(td => {
                                const out = [];
                                const w = document.createTreeWalker(td, NodeFilter.SHOW_TEXT);
                                const n = w.nextNode();
                                for (const word of n.textContent.split(' ')) {
                                  const i = n.textContent.indexOf(word);
                                  const r = document.createRange();
                                  r.setStart(n, i); r.setEnd(n, i + word.length);
                                  if (r.getClientRects().length !== 1) out.push(word);
                                }
                                return out; }))()"))))
           (testing "the scroll wrapper keeps the table's collapsed vertical margins (TASK-312)"
             (open b (build-plain dir "gap" (str "---\ntitle: Gap\nlang: en\n---\n\nBefore.\n\n"
                                                 "| A | B |\n|---|---|\n| x | y |\n\nAfter.\n")))
             (is (= [24 24]
                    (js b "(() => { const w = document.querySelector('table').parentElement;
                                    const p = w.previousElementSibling, a = w.nextElementSibling;
                                    const t = document.querySelector('table').getBoundingClientRect();
                                    return [Math.round(t.top - p.getBoundingClientRect().bottom),
                                            Math.round(a.getBoundingClientRect().top - t.bottom)]; })()"))
                 "1.5rem, collapsed with the paragraph margins, as before the wrapper"))
           (testing "a subfigure caption of one long word does not widen the page (TASK-312)"
             (open b (build-plain dir "panels" subfigure-doc))
             (is (js b "!!document.querySelector('div.subfigure-row figure figcaption')"))
             (is (= [390 390] (js b fits))))
           (testing "a normal desktop width keeps the stylesheet's measure"
             (tb/cdp b "Emulation.setDeviceMetricsOverride"
                     {"width" 1200 "height" 800 "deviceScaleFactor" 1 "mobile" false})
             (open b stress)
             (is (= 1200 (js b "innerWidth")))
             (is (<= (js b "document.body.getBoundingClientRect().width") 704.5))))))
      (finally (tb/delete-tree dir)))))
