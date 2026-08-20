(ns haselnuss.lint-config-test
  "Fixtures for what `bin/lint` covers (TASK-50.2).

  `bin/lint` passes an explicit path list to both clj-kondo and cljfmt,
  because `lein cljfmt check` with no arguments reads
  `:source-paths`/`:test-paths` and so can never see `project.clj` --
  the hole this task closed. The cost of spelling the paths out is that
  they no longer follow `project.clj`: a source path added there would
  silently drop out of both checks (TASK-50.2 review). This namespace is
  that drift guard, and it is a text comparison on purpose -- running
  `bin/lint` from a test would run the linter against the tree twice per
  suite.

  `haselnuss.repl-config-test` is the in-repo precedent for testing
  configuration that no other gate sees; this is the same idea applied
  to the gate itself."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private lint-script
  (delay (slurp "bin/lint")))

(def ^:private project-args
  "project.clj's `defproject` argument map, read as data (mirrors
  `haselnuss.repl-config-test`'s own convention)."
  (delay (apply hash-map (drop 3 (read-string (slurp "project.clj"))))))

(defn- covered?
  "True when `path` appears in one of `bin/lint`'s own `lint_paths`
  assignments -- the array literal or the conditional append."
  [path]
  (some (fn [line]
          (and (re-find #"lint_paths[+]?=" line) (str/includes? line path)))
        (str/split-lines @lint-script)))

(deftest lint-covers-every-clojure-path-test
  (testing "every source and test path project.clj declares is in
            bin/lint's own path list, so adding one cannot silently drop
            it out of both the linter and the formatter"
    (doseq [path (concat (:source-paths @project-args ["src"])
                         (:test-paths @project-args ["test"]))]
      (is (covered? path) (str path " is a project path bin/lint does not lint"))))
  (testing "and project.clj itself is in that list -- the whole point of
            TASK-50.2, and the one path lein cljfmt cannot reach on its
            own"
    (is (covered? "project.clj")))
  (testing "the formatter is given the same list as the linter, rather
            than its own default paths, or the two cover different files"
    (is (re-find #"lein cljfmt check \"\$\{lint_paths\[@\]\}\"" @lint-script))))
