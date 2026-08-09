;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.ai.harness.lifecycle
  "Bridges Proposal lifecycle events into Repository Harness verification."
  (:require
   [app.ai.harness.checks :as checks]
   [app.ai.harness.runs :as runs]
   [app.db :as db]))

(defn- run-id-for-proposal
  [cfg profile-id proposal-id]
  (:id
   (db/exec-one!
    cfg
    ["SELECT id FROM ai_harness_run
       WHERE profile_id = ? AND proposal_id = ?
       ORDER BY created_at DESC
       LIMIT 1"
     profile-id proposal-id])))

(defn previewed!
  [cfg profile-id proposal-id preview]
  (when-let [run-id (run-id-for-proposal cfg profile-id proposal-id)]
    (checks/run-core-checks!
     cfg profile-id run-id
     {:environment.workspace-bridge
      {:workspace-bridge? true
       :snapshot-version (or (:snapshot-version preview) "penpot-live")}
      :proposal.preview-compiled
      {:passed? true
       :compiler (:compiler preview)
       :counts (:counts preview)
       :affected-ids (:affected-ids preview)}
      :proposal.native-shapes-valid
      {:passed? true
       :validator "app.common.types.shape/schema:shape"}
      :proposal.undo-ready
      {:passed? true
       :transaction-model "native-redo-undo"}
      :registry.references-resolved
      {:passed? (zero? (long (or (:registry-error-count preview) 0)))
       :registry-error-count (or (:registry-error-count preview) 0)}})
    (runs/update-progress!
     cfg profile-id run-id
     {:status :verifying
      :completed ["Loaded repository Harness rules"
                  "Inspected the Penpot environment"
                  "Generated a validated DSL Proposal"
                  "Compiled the native Penpot Preview"
                  "Recorded pre-apply verification evidence"]
      :remaining ["Confirm and apply the Proposal in Penpot"
                  "Verify the recorded transaction ID"
                  "Complete the Harness run"]
      :blockers []
      :next-action "Review the Diff and apply the Proposal in Penpot."})
    {:run-id run-id
     :verification (checks/completion-report! cfg profile-id run-id)}))

(defn applied!
  [cfg profile-id proposal-id]
  (when-let [run-id (run-id-for-proposal cfg profile-id proposal-id)]
    (checks/run-check!
     cfg profile-id run-id
     {:check-id :proposal.transaction-applied
      :transport :internal})
    (runs/update-progress!
     cfg profile-id run-id
     {:status :verifying
      :completed ["Loaded repository Harness rules"
                  "Inspected the Penpot environment"
                  "Generated and previewed the Proposal"
                  "Applied one native Penpot Change/Undo transaction"
                  "Verified the transaction record"]
      :remaining []
      :blockers []
      :next-action "Evaluate the Completion Gate."})
    (runs/complete! cfg profile-id run-id)))

(defn conflicted!
  [cfg profile-id proposal-id error]
  (when-let [run-id (run-id-for-proposal cfg profile-id proposal-id)]
    (runs/block!
     cfg profile-id run-id
     [(or (:message error) "The Proposal conflicted with the current canvas.")]
     "Rebuild the Proposal from the current file Revision and rerun verification.")
    (runs/create-handoff!
     cfg profile-id run-id
     {:status :blocked
      :blockers [(or (:message error) "Canvas conflict")]
      :next-action "Refresh canvas context and create a new scoped Proposal."})))
