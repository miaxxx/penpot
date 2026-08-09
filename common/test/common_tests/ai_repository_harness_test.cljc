;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns common-tests.ai-repository-harness-test
  (:require
   [app.common.ai.repository-harness :as rh]
   [clojure.test :as t]))

(t/deftest validates-safe-artifact-paths
  (t/is (rh/safe-artifact-path? "AGENTS.md"))
  (t/is (rh/safe-artifact-path? ".harness/RULES.md"))
  (t/is (false? (rh/safe-artifact-path? "../secret")))
  (t/is (false? (rh/safe-artifact-path? "/absolute/path")))
  (t/is (false? (rh/safe-artifact-path? "rules with spaces.md"))))

(t/deftest routes-rules-progress-and-handoff-progressively
  (let [basic (rh/route-artifacts
               {:prompt "Create a card" :mode :generate :input-mode :assistant})
        continuing (rh/route-artifacts
                    {:prompt "继续并验证修改" :mode :modify :input-mode :remote})]
    (t/is (= "AGENTS.md" (first basic)))
    (t/is (some #{".harness/RULES.md"} basic))
    (t/is (not (some #{"session-handoff.md"} basic)))
    (t/is (some #{"PROGRESS.md"} continuing))
    (t/is (some #{"session-handoff.md"} continuing))
    (t/is (some #{".harness/CHECKS.md"} continuing))
    (t/is (some #{".harness/ENVIRONMENT.md"} continuing))))

(t/deftest completion-requires-every-required-check
  (let [ids (rh/required-check-ids)
        all-pass (mapv (fn [id] {:check-id id :status :passed}) ids)
        without-transaction
        (remove #(= :proposal.transaction-applied (:check-id %)) all-pass)]
    (t/is (:ready? (rh/completion-report all-pass)))
    (t/is (false? (:ready? (rh/completion-report without-transaction))))
    (t/is (= [:proposal.transaction-applied]
             (:missing (rh/completion-report without-transaction))))))

(t/deftest failed-or-skipped-checks-block-completion
  (let [ids (rh/required-check-ids)
        failed (mapv (fn [id]
                       {:check-id id
                        :status (if (= id :proposal.dsl-valid)
                                  :failed
                                  :passed)})
                     ids)
        skipped (mapv (fn [id]
                        {:check-id id
                         :status (if (= id :proposal.undo-ready)
                                   :skipped
                                   :passed)})
                      ids)]
    (t/is (= [:proposal.dsl-valid]
             (:failed (rh/completion-report failed))))
    (t/is (= [:proposal.undo-ready]
             (:pending (rh/completion-report skipped))))))

(t/deftest default-entry-is-a-router
  (let [agents (some #(when (= "AGENTS.md" (:path %)) %)
                     (rh/default-artifacts))]
    (t/is (:required agents))
    (t/is (re-find #"\.harness/GUIDE\.md" (:content agents)))
    (t/is (re-find #"PROGRESS\.md" (:content agents)))
    (t/is (< (count (:content agents)) 1200))))
