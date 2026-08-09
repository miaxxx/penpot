;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns backend-tests.ai-repository-harness-contract-test
  (:require
   [app.common.ai.repository-harness :as rh]
   [app.common.ai.tools :as tools]
   [app.rpc.commands.ai-harness-repository-gateway :as gateway]
   [clojure.test :as t]))

(t/deftest native-commit-remains-a-single-internal-capability
  (let [all-commit-tools
        (filter #(= :canvas/commit (:capability %))
                (tools/list-tools))
        mcp-commit-tools
        (filter #(= :canvas/commit (:capability %))
                (tools/list-tools :mcp))]
    (t/is (= [:native.commit] (mapv :id all-commit-tools)))
    (t/is (empty? mcp-commit-tools))))

(t/deftest transaction-applied-is-required-for-completion
  (t/is (contains? (rh/required-check-ids)
                   :proposal.transaction-applied)))

(t/deftest progress-update-cannot-bypass-completion-gate
  (let [error
        (try
          (gateway/invoke
           nil
           {:app.rpc/profile-id nil
            :action "run.update"
            :arguments {:status "completed"}})
          nil
          (catch clojure.lang.ExceptionInfo cause
            (ex-data cause)))]
    (t/is (= :ai-harness-completion-gate-required (:code error)))))
