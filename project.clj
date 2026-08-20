(defproject haselnuss "0.1.0-SNAPSHOT"
  :description "Haselnuss: a document toolkit with parser, resolver, and HTML/LaTeX exporters"
  :url "https://github.com/danilo/haselnuss"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [metosin/malli "0.16.4"]
                 [org.clojure/data.json "2.5.1"]
                 [com.vladsch.flexmark/flexmark "0.64.8"]
                 [com.vladsch.flexmark/flexmark-ext-attributes "0.64.8"]
                 [com.vladsch.flexmark/flexmark-ext-tables "0.64.8"]
                 [com.vladsch.flexmark/flexmark-ext-footnotes "0.64.8"]
                 [clj-commons/clj-yaml "1.0.29"]]
  :plugins [[lein-cljfmt "0.9.2"]]
  :main ^:skip-aot haselnuss.cli
  :target-path "target/%s"
  ;; A stable, version-free name (TASK-26): the artifact `lein uberjar`
  ;; produces is the thing users run and scripts reference, so it must
  ;; not change spelling every time the version does. Lands at
  ;; target/uberjar/haselnuss.jar.
  :uberjar-name "haselnuss.jar"
  ;; The agent-facing nREPL is pinned here, and this is the only place in
  ;; project.clj the host and port are written (TASK-45; see doc-1, Agentic
  ;; Clojure Tooling Stack, for why a REPL is in the stack at all).
  ;;
  ;; Pinning the port is not cosmetic: with a random port, a client holding a
  ;; previously-read `.nrepl-port` can silently reach a *later, unrelated*
  ;; REPL that happened to be handed the same number. Pinned, it reaches the
  ;; intended server or fails to connect. The cost, accepted deliberately, is
  ;; that a second server cannot start alongside the first -- it dies on a
  ;; BindException, loudly.
  ;;
  ;; These keys are only the third-highest-precedence source.
  ;; leiningen.repl/configured-repl-connection resolves the connection as
  ;;
  ;;   CLI argument > LEIN_REPL_* env var > :repl-options > nREPL's own
  ;;   nrepl.edn files
  ;;
  ;; so pinning here beats the nrepl.edn `:host`/`:port`/`:bind` keys, but on
  ;; its own would leave LEIN_REPL_HOST in an ambient shell free to move the
  ;; server off loopback. The `:repl` profile below is what makes the
  ;; localhost-only claim actually hold; see its comment.
  :repl-options {:host "127.0.0.1" :port 7888}
  ;; `examples/` on the TEST classpath only (not `:resource-paths`, which
  ;; would bundle it into the uberjar): `haselnuss.example-test` converts
  ;; the committed example document, and reading it off the classpath
  ;; keeps that independent of the runner's working directory.
  :profiles {:uberjar {:aot :all}
             :test {:resource-paths ["examples"]}
             ;; Leiningen implicitly activates a profile named `:repl` for the
             ;; `repl` task and for that task only (leiningen.repl/repl ->
             ;; project/profiles-with-matching-meta, keyed by
             ;; project/default-profile-metadata's {:repl {:repl true}}), so
             ;; the guard below costs `lein test`/`run`/`uberjar` nothing and
             ;; cannot fail a build that was never going to open a socket.
             ;;
             ;; :injections is the hook because leiningen.core.eval/eval-in-project
             ;; splices them into the project JVM *ahead of* the form that
             ;; starts the server -- the only project-supplied hook that runs
             ;; before the bind. A rejected override therefore throws with
             ;; nothing listening on any interface.
             ;;
             ;; The LEIN_REPL_* variables are refused outright rather than
             ;; compared against the pinned values. That keeps the numbers in
             ;; :repl-options above the single source of truth (a guard that
             ;; repeated them could drift into accepting the old port), and it
             ;; avoids having to enumerate every spelling of loopback --
             ;; "127.0.0.1", "localhost", "::1" -- and getting that list wrong.
             ;; Setting these variables is an attempt to override a pinned
             ;; connection whatever the value; the fix is always to unset them.
             ;;
             ;; A socket -- from LEIN_REPL_SOCKET or an nrepl.edn `:socket`
             ;; key -- is refused for a different reason. Neither is a network
             ;; exposure (a filesystem socket is local by construction), but
             ;; both short-circuit the whole {:host :port} branch of
             ;; configured-repl-connection, the nrepl.edn one outranking even a
             ;; CLI argument, so either would quietly retire the pinned port.
             ;;
             ;; Two overrides this deliberately does NOT cover, recorded rather
             ;; than claimed away, because both are resolved in Leiningen's own
             ;; JVM before any project-supplied code exists:
             ;;
             ;;   - an explicit `lein repl :start :host <x>` argument;
             ;;   - `:repl-options` set in a user-level profile
             ;;     (~/.lein/profiles.clj), which is merged over this file.
             ;;
             ;; `bin/repl` closes both for the documented entry point by
             ;; passing :host/:port as CLI arguments, the highest-precedence
             ;; source of all. Typing `lein repl` directly in a shell carrying
             ;; either override is out of scope: this is agent-facing tooling,
             ;; and the bar is that what this file claims is true, not that a
             ;; local user cannot deliberately opt out.
             :repl {:injections [(do
                                   (doseq [v ["LEIN_REPL_HOST" "LEIN_REPL_PORT" "LEIN_REPL_SOCKET"]]
                                     (when-let [value (System/getenv v)]
                                       (throw (ex-info (str v "=" value " would override the nREPL connection "
                                                            "this project pins in project.clj's :repl-options. "
                                                            "Unset it, or pass :host/:port to `lein repl` "
                                                            "explicitly if you really mean to.")
                                                       {:variable v :value value}))))
                                   ;; nREPL's own resolution (~/.nrepl/nrepl.edn merged with
                                   ;; ./.nrepl.edn, NREPL_CONFIG_DIR-aware) rather than a
                                   ;; reimplementation of it. nrepl is on the classpath whenever
                                   ;; this profile is active; if it somehow is not, no nREPL
                                   ;; server can start either, so there is nothing to guard.
                                   (when-let [sock (try
                                                     (:socket @(requiring-resolve 'nrepl.config/config))
                                                     (catch Exception _ nil))]
                                     (throw (ex-info (str "an nrepl.edn :socket key (" sock ") would replace "
                                                          "the port this project pins in project.clj's "
                                                          ":repl-options with a domain socket. Remove it.")
                                                     {:nrepl-edn-socket sock}))))]}})
