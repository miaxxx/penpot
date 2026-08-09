;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.ai.harness.repository-service
  "Repository-first wrapper around the existing Harness turn service."
  (:require
   [app.ai.harness.artifacts :as artifacts]
   [app.ai.harness.environment :as environment]
   [app.ai.harness.runs :as runs]
   [app.ai.harness.service :as service]
   [app.ai.harness.sessions :as sessions]
   [app.ai.providers.openai-compatible :as openai-compatible]
   [app.common.exceptions :as ex]))

(defn- provider-instance
  [provider]
  (if (contains? #{"openai" "openai-compatible"} provider)
    openai-compatible/provider
    (ex/raise :type :validation
              :code :unsupported-ai-provider
              :hint "AI provider is not supported")))

(defn run-turn!
  [cfg profile-id {:keys [session-id prompt input provider context
                          base-url model context-budget]
                   :as params}]
  (let [session (sessions/get-owned! cfg profile-id session-id :access :edit)
        workspace
        (artifacts/ensure-workspace!
         cfg profile-id
         {:file-id (:file-id session)
          :page-id (:page-id session)
          :settings {:source :repository-harness
                     :version "2.0"}})
        workspace-id (:workspace-id workspace)
        routed
        (artifacts/routed-artifacts!
         cfg profile-id workspace-id
         {:prompt prompt
          :mode (:mode session)
          :input-mode (keyword (or input (:input-mode session)))})
        environment
        (environment/inspect!
         cfg profile-id workspace-id
         {:base-revision (:base-revision session)
          :workspace-context context
          :provider-config {:base-url base-url :model model}
          :context-budget context-budget})
        _ (when (= :blocked (:status environment))
            (ex/raise :type :validation
                      :code :ai-harness-environment-blocked
                      :hint "Harness environment checks failed"
                      :environment environment))
        repository-context
        {:version "2.0"
         :workspace-id workspace-id
         :artifacts
         (mapv #(select-keys % [:path :kind :content :content-hash]) routed)
         :environment environment
         :completion-policy
         {:verification-required true
          :direct-completion-forbidden true
          :native-commit-internal-only true}}
        result
        (service/run-turn!
         (provider-instance provider)
         cfg profile-id
         (assoc params
                :context
                (assoc (or context {})
                       :repository-harness repository-context)))
        run-id (get-in result [:harness :run-id])]
    (when run-id
      (runs/attach-workspace! cfg profile-id run-id workspace-id prompt)
      (runs/update-progress!
       cfg profile-id run-id
       {:status (if (= :proposal (:kind result)) :verifying :running)
        :completed ["Loaded repository Harness rules"
                    "Inspected the Penpot environment"
                    "Assembled bounded context"]
        :remaining
        (if (= :proposal (:kind result))
          ["Compile the Proposal preview"
           "Run required verification checks"
           "Apply through Penpot UI confirmation"
           "Record handoff or completion"]
          ["Continue the current scoped goal"])
        :blockers []
        :next-action
        (if (= :proposal (:kind result))
          "Compile Preview and submit internal verification evidence."
          "Continue the scoped Harness run.")}))
    (assoc result
           :repository-harness
           {:workspace-id workspace-id
            :environment (:status environment)
            :artifacts (mapv :path routed)
            :completion-gate :required})))
