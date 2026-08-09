;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.ai.harness.environment
  "Deterministic environment health inspection for repository Harness runs."
  (:require
   [app.ai.harness.artifacts :as artifacts]
   [app.ai.policy :as policy]
   [app.common.ai.harness :as harness]
   [app.common.ai.tools :as tools]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [clojure.string :as str]))

(defn- check [id ok? severity evidence]
  {:id id
   :status (if ok? :passed :failed)
   :severity severity
   :evidence evidence})

(defn inspect!
  [cfg profile-id workspace-id
   {:keys [base-revision workspace-context provider-config context-budget]}]
  (let [workspace (artifacts/get-owned! cfg profile-id workspace-id)
        file-id (:file-id workspace)
        current-revision (policy/current-revision cfg file-id)
        context-budget (harness/clamp-context-budget context-budget)
        ai-enabled? (contains? cf/flags :ai-design-agent)
        mcp-enabled? (contains? cf/flags :mcp)
        workspace-bridge? (map? workspace-context)
        provider-ready?
        (and (map? provider-config)
             (not (str/blank? (:base-url provider-config)))
             (not (str/blank? (:model provider-config))))
        registry-tools (tools/list-tools :internal)
        native-commit-count
        (count (filter #(= :canvas/commit (:capability %)) registry-tools))
        checks
        [(check :environment.feature-flag ai-enabled? :blocking
                {:flag :ai-design-agent})
         (check :environment.file-revision
                (= (long base-revision) (long current-revision))
                :blocking
                {:expected base-revision :current current-revision})
         (check :environment.workspace-bridge workspace-bridge? :blocking
                {:available workspace-bridge?})
         (check :environment.provider-config provider-ready? :warning
                {:configured provider-ready?})
         (check :environment.mcp mcp-enabled? :warning
                {:flag :mcp})
         (check :environment.tool-registry
                (and (seq registry-tools) (= 1 native-commit-count))
                :blocking
                {:tool-count (count registry-tools)
                 :native-commit-count native-commit-count})
         (check :environment.context-budget
                (<= 2000 context-budget harness/max-context-budget)
                :blocking
                {:budget context-budget
                 :maximum harness/max-context-budget})]
        blocking-failed?
        (some #(and (= :blocking (:severity %))
                    (= :failed (:status %)))
              checks)
        warning-failed?
        (some #(and (= :warning (:severity %))
                    (= :failed (:status %)))
              checks)
        status (cond blocking-failed? :blocked
                     warning-failed? :degraded
                     :else :healthy)
        report {:status status
                :checked-at (ct/now)
                :file-id file-id
                :page-id (:page-id workspace)
                :file-revision current-revision
                :base-revision base-revision
                :context-budget context-budget
                :checks checks}]
    (db/insert! cfg :ai-harness-environment-snapshot
                {:id (uuid/next)
                 :workspace-id workspace-id
                 :profile-id profile-id
                 :file-revision current-revision
                 :status (name status)
                 :report (db/json report)})
    report))

(defn latest!
  [cfg profile-id workspace-id]
  (artifacts/get-owned! cfg profile-id workspace-id)
  (some->
   (db/exec-one!
    cfg
    ["SELECT * FROM ai_harness_environment_snapshot
       WHERE workspace_id = ? AND profile_id = ?
       ORDER BY created_at DESC
       LIMIT 1"
     workspace-id profile-id])
   (update :status keyword)
   (update :report #(if (db/pgobject? %)
                      (db/decode-json-pgobject %)
                      %))
   (dissoc :profile-id)))
