;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns backend-tests.ai-harness-contract-test
  (:require
   [app.ai.harness.commands :as commands]
   [app.ai.harness.coordinator :as coordinator]
   [app.ai.harness.registry :as registry]
   [app.common.ai.harness :as harness]
   [app.common.ai.tools :as tools]
   [clojure.test :as t]))

(t/deftest exposes-the-requested-harness-modules
  (let [ids (set (map :id registry/modules))]
    (doseq [id [:tools :commands :services :utils :context :coordinator
                :assistant :buddy :remote :plugins :skills :voice :vim]]
      (t/is (contains? ids id)))))

(t/deftest keeps-native-commit-internal
  (t/is (tools/allowed? :native.commit :internal))
  (t/is (not (tools/allowed? :native.commit :mcp)))
  (t/is (not (tools/allowed? :native.commit :plugin))))

(t/deftest coordinator-specialists-do-not-gain-write-authority
  (let [plan
        (coordinator/deterministic-plan
         {:prompt "audit and improve"
          :mode :modify
          :scope {:type :selection}
          :base-revision 10
          :skills harness/builtin-skills})
        writers (filter :write-authority (:tasks plan))]
    (t/is (= 1 (count writers)))
    (t/is (= :executor (:role (first writers))))
    (t/is (false? (get-in plan [:constraints :capability-escalation])))))

(t/deftest supports-vim-and-slash-command-registries
  (t/is (= :request-apply
           (get-in commands/vim-commands ["w" :action])))
  (t/is (some #(= "skills" (:name %)) harness/commands)))
