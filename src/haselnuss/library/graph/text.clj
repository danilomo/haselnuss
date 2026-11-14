(ns haselnuss.library.graph.text
  "What the library reports' text renderers share (`--check`, TASK-242;
  `--backlinks`, TASK-243): `escape-text` for every library-supplied
  string they print, `display-path` for every path, and `plural`. Pure
  functions over strings; nothing here touches a file."
  (:import (java.io File)))

(defn display-path
  "`path` relative to `base-dir` when both are absolute and `path` lies
  inside it, else `path` as given. Pure: no file is touched."
  [path base-dir]
  (if (and (string? path) (string? base-dir))
    (let [p (.normalize (.toPath (File. ^String path)))
          base (.normalize (.toPath (File. ^String base-dir)))]
      (if (and (.isAbsolute p) (.isAbsolute base) (.startsWith p base) (not= p base))
        (str (.relativize base p))
        path))
    path))

(defn plural
  "`n word`, with an `s` unless `n` is 1."
  [n word]
  (str n " " word (when (not= 1 n) "s")))

(defn- escaped-code-point?
  "True for a code point `escape-text` writes out: a C0 or C1 control
  character, DEL, the line and paragraph separators, or a format
  character (Unicode category Cf: bidi controls U+202A-202E and
  U+2066-2069, zero-width U+200B-200F, U+FEFF, tag characters, ...)."
  [^long cp]
  (or (< cp 0x20) (= cp 0x7f) (<= 0x80 cp 0x9f) (= cp 0x2028) (= cp 0x2029)
      (= Character/FORMAT (Character/getType (int cp)))))

(defn- escape*
  "`escape-text` (`backslash?` true) or `escape-controls` (false)."
  [s backslash?]
  (let [s (str s)
        backslash (if backslash? 92 -1)]
    (if (some #(or (= backslash %) (escaped-code-point? %)) (iterator-seq (.iterator (.codePoints ^String s))))
      (let [sb (StringBuilder.)]
        (doseq [cp (iterator-seq (.iterator (.codePoints ^String s)))]
          (let [cp (long cp)]
            (cond
              (= cp backslash) (.append sb "\\\\")
              (= cp 10) (.append sb "\\n")
              (= cp 13) (.append sb "\\r")
              (= cp 9) (.append sb "\\t")
              (escaped-code-point? cp) (.append sb (if (< cp 0x10000)
                                                     (format "\\u%04x" cp)
                                                     (format "\\U+%06x" cp)))
              :else (.appendCodePoint sb (int cp)))))
        (str sb))
      s)))

(defn escape-text
  "`s` with every character that could change how a line reads written
  out visibly, so text from a library -- a path, a reference as written,
  a message carrying a script's exception text -- can neither end the
  line it is printed on, nor reach the terminal as an escape sequence,
  nor display as something other than what it is (a bidi override making
  a file name read differently from the one an editor opens): `\\n`,
  `\\r` and `\\t` for those three, `\\uXXXX` (`\\U+XXXXXX` above the BMP)
  for every other code point `escaped-code-point?` names, and `\\\\` for
  a backslash, so a literal `\\n` in the text stays distinguishable from
  an escaped newline. The JSON renderers carry the exact text."
  [s]
  (escape* s true))

(defn escape-controls
  "`s` with exactly the characters `escape-text` writes out escaped the
  same way, but a backslash left as it is (TASK-247): what every line the
  CLI prints on stderr goes through. A diagnostic there is already
  written for a person, quoting with `pr-str` where it quotes at all, so
  doubling its backslashes would change every message that holds one --
  an `\\emph` in a parser error, a quoted `\\\"` -- for no gain in safety:
  a backslash can neither end a line nor drive the terminal. A message
  without a control or format character prints byte for byte as given."
  [s]
  (escape* s false))
