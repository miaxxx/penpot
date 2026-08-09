;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.ai.harness.runs
  "Long-running Harness state, handoff and completion gates."
  (:require
   [app.ai.harness.artifacts :as artifacts]
   [app.ai.harness.checks :as checks]
   [app.ai.harness.sessions :as sessions]
   [app.common.ai.repository-harness :as rh]
   [app.common.exceptions :as ex]
   [app.common.time :as ct]
   [app.db :as db]
   [clojure.string :as str]))

(def json-columns
  [:selected-skills :coordinator-plan :context-report :trace :result
   :progress :blockers :handoff :completion])

(def terminal-statuses #{:completed :failed :cancelled})

(defn- decode-json [value]
  (if (db/pgobject? value) (db/decode-json-pgobject value) value))

(defn decode-run [row]
  (when row
    (-> (reduce (fn [row key] (update row key decode-json)) row json-columns)
        (update :input-mode keyword)
        (update :status keyword)
        (assoc :run-id (:id row))
        (dissoc :profile-id))))

(defn get-owned!
  [cfg profile-id run-id & {:keys [access] :or {access :read}}]
  (let [row (db/get cfg :ai-harness-run {:id run-id :profile-id profile-id})]
    (when-not row
      (ex/raise :type :not-found :code :object-not-found :hint "not found"))
    (sessions/get-owned! cfg profile-id (:session-id row) :access access)
    row))

(defn get! [cfg profile-id run-id]
  (decode-run (get-owned! cfg profile-id run-id)))

(defn- bullets [items empty-text]
  (if (seq items)
    (str/join "\n" (map #(str "- " %) items))
    (str "- " empty-text)))

(defn- progress-markdown
  [{:keys [goal progress blockers next-action]} verification]
  (str "# Progress\n\n"
       "## Goal\n" (or goal "") "\n\n"
       "## Completed\n" (bullets (:completed progress) "None") "\n\n"
       "## Remaining\n" (bullets (:remaining progress) "None") "\n\n"
       "## Blockers\n" (bullets blockers "None") "\n\n"
       "## Verification\n"
       "- Passed: " (:passed-count verification) "/" (:required-count verification) "\n"
       (bullets (map #(str "Failed: " (name %)) (:failed verification))
                "No failed checks") "\n"
       (bullets (map #(str "Pending: " (name %))
                     (concat (:missing verification) (:pending verification)))
                "No pending checks") "\n\n"
       "## Next action\n" (or next-action "")))

(defn- handoff-markdown
  [{:keys [goal status progress blockers next-action]} verification]
  (str "# Session handoff\n\n"
       "## Current goal\n" (or goal "") "\n\n"
       "## State\n" (name status) "\n\n"
       "## Completed\n" (bullets (:completed progress) "None") "\n\n"
       "## Remaining\n" (bullets (:remaining progress) "None") "\n\n"
       "## Verified evidence\n"
       "- Completion gate: " (if (:ready? verification) "ready" "not ready") "\n"
       "- Passed checks: " (:passed-count verification) "/" (:required-count verification) "\n\n"
       "## Blockers\n" (bullets blockers "None") "\n\n"
       "## Next action\n" (or next-action "")))

(defn- ensure-mutable! [run]
  (when (contains? terminal-statuses (:status run))
    (ex/raise :type :validation
              :code :ai-harness-run-terminal
              :hint "A terminal Harness run cannot be modified"))
  run)

(defn attach-workspace! [cfg profile-id run-id workspace-id goal]
  (let [run (decode-run (get-owned! cfg profile-id run-id :access :edit))]
    (ensure-mutable! run)
    (artifacts/get-owned! cfg profile-id workspace-id :access :edit)
    (-> (db/update! cfg :ai-harness-run
                    {:workspace-id workspace-id
                     :goal (str (or goal ""))
                     :progress (db/json {:completed [] :remaining []})
                     :blockers (db/json [])
                     :next-action "Inspect environment and create a Proposal."
                     :modified-at (ct/now)}
                    {:id run-id :profile-id profile-id}
                    {::db/return-keys true})
        decode-run)))

(defn update-progress!
  [cfg profile-id run-id
   {:keys [goal completed remaining blockers next-action status]}]
  (let [run (-> (get-owned! cfg profile-id run-id :access :edit)
                decode-run
                ensure-mutable!)
        status (keyword (or status (:status run)))]
    (when-not (contains? rh/run-statuses status)
      (ex/raise :type :validation
                :code :invalid-ai-harness-run-status
                :hint "Harness run status is invalid"))
    (when (= :completed status)
      (ex/raise :type :restriction
                :code :ai-harness-completion-gate-required
                :hint "Use complete! so required verification evidence is enforced"))
    (let [progress {:completed (vec (or completed
                                        (get-in run [:progress :completed]) []))
                    :remaining (vec (or remaining
                                        (get-in run [:progress :remaining]) []))}
          blockers (vec (or blockers (:blockers run) []))
          updated
          (-> (db/update! cfg :ai-harness-run
                          {:goal (str (or goal (:goal run) ""))
                           :progress (db/json progress)
                           :blockers (db/json blockers)
                           :next-action (str (or next-action (:next-action run) ""))
                           :status (name status)
                           :modified-at (ct/now)}
                          {:id run-id :profile-id profile-id}
                          {::db/return-keys true})
              decode-run)
          verification (checks/completion-report! cfg profile-id run-id)]
      (when-let [workspace-id (:workspace-id updated)]
        (artifacts/upsert-artifact!
         cfg profile-id workspace-id
         {:path "PROGRESS.md"
          :kind :progress
          :required true
          :read-order 70
          :content (progress-markdown updated verification)}))
      (assoc updated :verification verification))))

(defn pause! [cfg profile-id run-id next-action]
  (update-progress! cfg profile-id run-id
                    {:status :paused :next-action next-action}))

(defn block! [cfg profile-id run-id blockers next-action]
  (update-progress! cfg profile-id run-id
                    {:status :blocked :blockers blockers :next-action next-action}))

(defn resume! [cfg profile-id run-id]
  (let [run (get! cfg profile-id run-id)]
    (ensure-mutable! run)
    (when-not (contains? #{:paused :blocked :proposal-created :verifying}
                         (:status run))
      (ex/raise :type :validation
                :code :ai-harness-run-not-resumable
                :hint "Harness run is not in a resumable state"))
    (update-progress! cfg profile-id run-id {:status :running})))

(defn create-handoff!
  [cfg profile-id run-id {:keys [next-action] :as state}]
  (let [run (get! cfg profile-id run-id)
        verification (checks/completion-report! cfg profile-id run-id)
        handoff
        (rh/handoff-state
         {:goal (:goal run)
          :status (or (:status state) (:status run))
          :completed (or (:completed state) (get-in run [:progress :completed]))
          :remaining (or (:remaining state) (get-in run [:progress :remaining]))
          :blockers (or (:blockers state) (:blockers run))
          :verification verification
          :next-action (or next-action (:next-action run))})
        updated
        (-> (db/update! cfg :ai-harness-run
                        {:handoff (db/json handoff)
                         :next-action (:next-action handoff)
                         :modified-at (ct/now)}
                        {:id run-id :profile-id profile-id}
                        {::db/return-keys true})
            decode-run)]
    (when-let [workspace-id (:workspace-id updated)]
      (artifacts/upsert-artifact!
       cfg profile-id workspace-id
       {:path "session-handoff.md"
        :kind :handoff
        :required true
        :read-order 80
        :content (handoff-markdown updated verification)}))
    (assoc updated :verification verification)))

(defn complete! [cfg profile-id run-id]
  (let [run (-> (get! cfg profile-id run-id) ensure-mutable!)
        verification (checks/completion-report! cfg profile-id run-id)]
    (when (seq (:blockers run))
      (ex/raise :type :validation
                :code :ai-harness-run-blocked
                :hint "Harness run cannot complete while blockers remain"
                :blockers (:blockers run)))
    (when-not (:ready? verification)
      (ex/raise :type :validation
                :code :ai-harness-verification-incomplete
                :hint "Harness run cannot complete without passing evidence"
                :verification verification))
    (let [completion {:completed-at (ct/now)
                      :verification verification
                      :proposal-id (:proposal-id run)}
          updated
          (-> (db/update! cfg :ai-harness-run
                          {:status "completed"
                           :completion (db/json completion)
                           :completed-at (ct/now)
                           :modified-at (ct/now)}
                          {:id run-id :profile-id profile-id
                           :status (name (:status run))}
                          {::db/return-keys true})
              decode-run)]
      (when-not updated
        (ex/raise :type :validation
                  :code :ai-harness-run-transition-conflict
                  :hint "Harness run changed while completing"))
      (create-handoff!
       cfg profile-id run-id
       {:status :completed
        :remaining []
        :blockers []
        :next-action "Review the applied Penpot transaction and start a new scoped run."})
      (assoc updated :verification verification))))

(defn latest-for-workspace! [cfg profile-id workspace-id]
  (artifacts/get-owned! cfg profile-id workspace-id)
  (some->
   (db/exec-one!
    cfg
    ["SELECT * FROM ai_harness_run
       WHERE workspace_id = ? AND profile_id = ?
       ORDER BY modified_at DESC
       LIMIT 1"
     workspace-id profile-id])
   decode-run))
