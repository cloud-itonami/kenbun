#!/usr/bin/env nbb
;; The suite on nbb — the same three `.cljc` namespaces the JVM runs, on the
;; other runtime, so a ClojureScript-half defect in `kenbun.*` cannot hide
;; behind a green JVM gate (ADR-2608190100).
;;
;;   nbb --classpath "src:test:$(clojure -Spath -M:test)" run-tests.cljs
;;
;; The classpath needs the git dep (kotoba-issue); nbb does not read
;; deps.edn. On the fleet that is what `:ship-git-deps true` supplies.
;;
;; `src-kotobase` / `test-kotobase` are NOT here and must not be added: that
;; adapter is JVM-only on purpose — `kotobase.core` returns Promises on
;; ClojureScript while IssueStore is synchronous (see deps.edn).
;;
;; Every deftest-bearing namespace is named BOTH in the require and in the
;; `run-tests` call: requiring registers the vars, only `run-tests` runs
;; them, and a runner naming a subset prints the same `Ran N tests` shape as
;; one naming all of them.
(ns run-tests
  (:require [cljs.test :as t]
            [kenbun.evidence-test]
            [kenbun.http-test]
            [kenbun.intake-test]))

(defmethod t/report [:cljs.test/default :end-run-tests] [m]
  (println (str "\nnbb: " (:test m) " tests, " (:pass m) " passed, "
                (:fail m) " failed, " (:error m) " errors"))
  (when (pos? (+ (or (:fail m) 0) (or (:error m) 0)))
    (set! (.-exitCode js/process) 1)))

(t/run-tests 'kenbun.evidence-test
             'kenbun.http-test
             'kenbun.intake-test)
