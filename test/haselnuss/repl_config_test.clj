(ns haselnuss.repl-config-test
  "Fixtures for the pinned agent-facing nREPL (TASK-45).

  This namespace exists because the code it covers lives in `project.clj`
  and `bin/repl`, which no *static* gate can settle. Since TASK-50.2
  `bin/lint` does read both -- clj-kondo and cljfmt over `project.clj`,
  `bash -n` over the scripts -- but a linter cannot tell whether
  Leiningen evaluates the guard before it binds a socket, and clj-kondo
  leaves `:unresolved-symbol` off inside a file named `project.clj`
  regardless of config. `haselnuss.uberjar-test` is the in-repo
  precedent -- a build-configuration claim is only honestly checked by
  running the build.

  Like that namespace it shells out, and is slow for the same reason:
  five nested `lein` invocations, each paying a fresh JVM start (~19s for
  the namespace). The alternative -- asserting the
  guard form is *present* in project.clj -- would prove nothing about
  whether Leiningen actually evaluates it before binding, which is the
  entire claim."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.io IOException)
           (java.net BindException InetAddress ServerSocket)))

(def ^:private project-args
  "project.clj's `defproject` argument map. Read as data rather than
  through Leiningen so the test asserts what the file says, not what some
  merged profile stack resolved to."
  (delay (apply hash-map (drop 3 (read-string (slurp "project.clj"))))))

(defn- sh-with-env
  "Runs `args`, adding `env` on top of the inherited environment, under a
  hard `timeout` so a guard that fails to fire cannot hang the suite on a
  headless server that never returns."
  [env & args]
  (apply shell/sh (concat ["timeout" "120"] args
                          [:env (merge (into {} (System/getenv)) env)])))

(deftest pinned-connection-is-single-source-of-truth
  (testing "project.clj pins both host and port, so the server binds the
            same place every run instead of taking a random port a stale
            client could later find occupied by an unrelated REPL"
    (let [{:keys [host port]} (:repl-options @project-args)]
      (is (= "127.0.0.1" host))
      (is (integer? port))
      (testing "and bin/repl passes those same values as CLI arguments --
                the highest-precedence source, which is how it beats a
                user-level ~/.lein/profiles.clj the in-JVM guard cannot
                see. Drift here would have bin/repl start a server on a
                port nothing else in the project expects."
        (let [script (slurp "bin/repl")]
          (is (str/includes? script (str "HOST=" host)))
          (is (str/includes? script (str "PORT=" port))))))))

(deftest repl-refuses-connection-overrides
  (testing "an environment override is refused before anything binds --
            :repl-options alone would lose to it, since Leiningen resolves
            LEIN_REPL_* ahead of the project map"
    ;; Each value has to be one Leiningen itself accepts -- a non-numeric
    ;; LEIN_REPL_PORT dies on Integer/valueOf in Leiningen's own JVM
    ;; (leiningen.repl/configured-repl-connection) before the project JVM
    ;; exists, which would prove nothing about this guard.
    (doseq [[variable value] [["LEIN_REPL_HOST" "0.0.0.0"]
                              ["LEIN_REPL_PORT" "9999"]
                              ["LEIN_REPL_SOCKET" "/tmp/haselnuss-test.sock"]]]
      (let [{:keys [exit out err]} (sh-with-env {variable value} "lein" "repl" ":headless")
            text (str out err)]
        (is (not (zero? exit)) (str variable " must abort the repl task"))
        (is (str/includes? text variable)
            (str "the failure must name " variable " rather than being a bare timeout"))
        (is (not (str/includes? text "nREPL server started"))
            "nothing may bind before the guard runs"))))
  (testing "the guard is scoped to the implicitly-activated :repl profile,
            so an override sitting in the ambient environment cannot fail
            a build that was never going to open a socket"
    (let [{:keys [exit out]} (sh-with-env {"LEIN_REPL_HOST" "0.0.0.0"} "lein" "run" "--" "--help")]
      (is (zero? exit) "lein run must be unaffected by the repl guard")
      (is (str/includes? out "Usage: haselnuss")))))

(defn- occupy
  "Holds `port` on loopback, returning the socket, or nil if something
  else already holds it -- a developer's own REPL, most likely. Either
  way the port is occupied, which is all the caller needs."
  [port]
  (try
    (ServerSocket. port 1 (InetAddress/getByName "127.0.0.1"))
    (catch BindException _ nil)
    (catch IOException _ nil)))

(deftest port-collision-is-loud
  (testing "with the pinned port already taken, starting a server fails on
            the bind instead of quietly moving to another port. That is
            what makes a pinned port worth having: a client holding a
            previously-read port reaches the intended REPL or nothing, and
            never a later unrelated one."
    (let [port (:port (:repl-options @project-args))
          held (occupy port)]
      (try
        (let [{:keys [exit out err]} (sh-with-env {} "./bin/repl")
              text (str out err)]
          (is (not (zero? exit)))
          (is (str/includes? text "Address already in use")
              "the pinned port must be the one it tried, and the clash must surface")
          (is (not (str/includes? text "nREPL server started"))))
        (finally
          (when held (.close ^ServerSocket held)))))))
