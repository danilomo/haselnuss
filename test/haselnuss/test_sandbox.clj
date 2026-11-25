(ns haselnuss.test-sandbox
  "Test helpers for what a busy machine does to a sandboxed call
  (TASK-299): a real stall of the whole process, and stand-ins for a
  call that ran out of time or (TASK-301) of memory."
  (:require [haselnuss.sandbox :as sandbox]))

(defn stall-process!
  "Stops this whole JVM for `ms` milliseconds -- every thread, as a
  stop-the-world garbage collection does -- and returns once it runs
  again: a child shell sends it SIGSTOP, sleeps, then sends SIGCONT. With
  `delay-ms`, the child waits that long before it stops the JVM, and this
  returns the child `Process` at once, leaving the stall to land on
  whatever runs then.

  The child sends SIGCONT on every way out -- done, or ended by SIGINT
  (a Ctrl-C to the foreground group), SIGTERM or SIGHUP -- so a test run
  interrupted mid-stall does not leave this JVM stopped for good. Only a
  SIGKILL of the child itself in that window would."
  ([ms] (stall-process! ms 0))
  ([ms delay-ms]
   (let [pid (.pid (java.lang.ProcessHandle/current))
         seconds (fn [n] (format "%.3f" (/ n 1000.0)))
         ;; each sleep is waited for, not run in the foreground: a trapped
         ;; signal ends `wait` at once, but only after a foreground command
         script (str "trap 'kill -CONT " pid "' EXIT; trap 'exit 1' INT TERM HUP; "
                     "sleep " (seconds delay-ms) " & wait $!; kill -STOP " pid "; "
                     "sleep " (seconds ms) " & wait $!")
         p (.start (ProcessBuilder. ^"[Ljava.lang.String;" (into-array String ["sh" "-c" script])))]
     (when (zero? delay-ms) (.waitFor p))
     p)))

(defn timing-out
  "A stand-in for `haselnuss.sandbox/sci-call!` (for `with-redefs`) that
  answers every call whose description matches `pattern` as the sandbox
  answers one that ran out of time, and runs every other one: what a
  stalled or starved process does to a well-behaved script, without
  waiting for it."
  [pattern]
  (let [real sandbox/sci-call!]
    (fn
      ([f describe opts] (real f describe opts))
      ([f describe opts kind]
       (if (re-find pattern describe)
         (throw (ex-info (str describe " did not finish within 5000ms")
                         {:type ::sandbox/timeout :describe describe :timeout-ms 5000}))
         (real f describe opts kind))))))

(defn running-out-of-memory
  "Like `timing-out`, but answering a matching call as the sandbox answers
  one that ran out of memory on a full heap (TASK-301)."
  [pattern]
  (let [real sandbox/sci-call!]
    (fn
      ([f describe opts] (real f describe opts))
      ([f describe opts kind]
       (if (re-find pattern describe)
         (throw (ex-info (str describe " ran out of memory (java.lang.OutOfMemoryError: Java heap space)")
                         {:type ::sandbox/out-of-memory :describe describe :heap-exhausted true}))
         (real f describe opts kind))))))
