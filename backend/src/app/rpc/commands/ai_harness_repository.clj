;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.rpc.commands.ai-harness-repository
  "RPC implementation for repository-driven Harness workspaces."
  (:require
   [app.ai.harness.artifacts :as artifacts]
   [app.ai.harness.checks :as checks]
   [app.ai.harness.environment :as environment]
   [app.ai.harness.runs :as runs]
   [app.common.schema :as sm]
   [app.rpc :as-alias rpc]))

(def schema:workspace-ensure
  [:map {:closed true}
   [:file-id ::sm/uuid]
   [:page-id {:optional true} ::sm/uuid]
   [:name {:optional true} [:string {:max 256}]]
   [:settings {:optional true} :map]])

(def schema:workspace-id
  [:map {:closed true}
   [:workspace-id ::sm/uuid]])

(def schema:artifact-get
  [:map {:closed true}
   [:workspace-id ::sm/uuid]
   [:path [:string {:min 1 :max 240}]]])

(def schema:artifact-upsert
  [:map {:closed true}
   [:workspace-id ::sm/uuid]
   [:artifact
    [:map {:closed true}
     [:path [:string {:min 1 :max 240}]]
     [:kind {:optional true} :string]
     [:content-type {:optional true} [:string {:max 128}]]
     [:content [:string {:max 524288}]]
     [:metadata {:optional true} :map]
     [:required {:optional true} ::sm/boolean]
     [:read-order {:optional true} [:int {:min 0 :max 10000}]]]]])

(def schema:artifact-route
  [:map {:closed true}
   [:workspace-id ::sm/uuid]
   [:prompt [:string {:max 12000}]]
   [:mode [:enum "generate" "modify" "refactor" "adapt"]]
   [:input-mode
    [:enum "assistant" "buddy" "voice" "vim" "remote" "mcp" "plugin"]]])

(def schema:package-import
  [:map {:closed true}
   [:workspace-id ::sm/uuid]
   [:package :map]])

(def schema:environment-inspect
  [:map {:closed true}
   [:workspace-id ::sm/uuid]
   [:base-revision [:int {:min 0}]]
   [:workspace-context {:optional true} :map]
   [:provider-config {:optional true} :map]
   [:context-budget {:optional true} [:int {:min 2000 :max 75000}]]])

(def schema:run-id
  [:map {:closed true}
   [:run-id ::sm/uuid]])

(def schema:attach-run
  [:map {:closed true}
   [:run-id ::sm/uuid]
   [:workspace-id ::sm/uuid]
   [:goal [:string {:max 12000}]]])

(def schema:update-progress
  [:map {:closed true}
   [:run-id ::sm/uuid]
   [:goal {:optional true} [:string {:max 12000}]]
   [:completed {:optional true} [:vector [:string {:max 2000}]]]
   [:remaining {:optional true} [:vector [:string {:max 2000}]]]
   [:blockers {:optional true} [:vector [:string {:max 2000}]]]
   [:next-action {:optional true} [:string {:max 4000}]]
   [:status {:optional true}
    [:enum "running" "control" "proposal-created" "blocked" "verifying"
     "paused" "completed" "failed" "cancelled"]]])

(def schema:block-run
  [:map {:closed true}
   [:run-id ::sm/uuid]
   [:blockers [:vector [:string {:max 2000}]]]
   [:next-action [:string {:max 4000}]]])

(def schema:pause-run
  [:map {:closed true}
   [:run-id ::sm/uuid]
   [:next-action [:string {:max 4000}]]])

(def schema:handoff
  [:map {:closed true}
   [:run-id ::sm/uuid]
   [:state {:optional true} :map]])

(def schema:run-check
  [:map {:closed true}
   [:run-id ::sm/uuid]
   [:check-id [:string {:min 1 :max 160}]]
   [:evidence {:optional true} :map]
   [:transport {:optional true} [:enum "internal"]]])

(def schema:run-core-checks
  [:map {:closed true}
   [:run-id ::sm/uuid]
   [:evidence {:optional true} :map]])

(defn ensure-workspace
  [cfg {:keys [::rpc/profile-id] :as params}]
  (artifacts/ensure-workspace! cfg profile-id (dissoc params ::rpc/profile-id)))

(defn get-workspace
  [cfg {:keys [::rpc/profile-id workspace-id]}]
  (artifacts/get-workspace! cfg profile-id workspace-id))

(defn list-artifacts
  [cfg {:keys [::rpc/profile-id workspace-id]}]
  (artifacts/list-artifacts! cfg profile-id workspace-id))

(defn get-artifact
  [cfg {:keys [::rpc/profile-id workspace-id path]}]
  (artifacts/get-artifact! cfg profile-id workspace-id path))

(defn upsert-artifact
  [cfg {:keys [::rpc/profile-id workspace-id artifact]}]
  (artifacts/upsert-artifact! cfg profile-id workspace-id
                              (update artifact :kind #(some-> % keyword))))

(defn route-artifacts
  [cfg {:keys [::rpc/profile-id workspace-id mode input-mode prompt]}]
  (artifacts/routed-artifacts!
   cfg profile-id workspace-id
   {:mode (keyword mode) :input-mode (keyword input-mode) :prompt prompt}))

(defn export-package
  [cfg {:keys [::rpc/profile-id workspace-id]}]
  (artifacts/export-package! cfg profile-id workspace-id))

(defn import-package
  [cfg {:keys [::rpc/profile-id workspace-id package]}]
  (artifacts/import-package! cfg profile-id workspace-id package))

(defn inspect-environment
  [cfg {:keys [::rpc/profile-id workspace-id] :as params}]
  (environment/inspect! cfg profile-id workspace-id
                        (dissoc params ::rpc/profile-id :workspace-id)))

(defn latest-environment
  [cfg {:keys [::rpc/profile-id workspace-id]}]
  (environment/latest! cfg profile-id workspace-id))

(defn list-checks
  [_cfg _params]
  (checks/list-definitions))

(defn list-check-results
  [cfg {:keys [::rpc/profile-id run-id]}]
  (checks/list-results! cfg profile-id run-id))

(defn run-check
  [cfg {:keys [::rpc/profile-id run-id check-id evidence]}]
  (checks/run-check! cfg profile-id run-id
                     {:check-id check-id
                      :evidence (or evidence {})
                      :transport :internal}))

(defn run-core-checks
  [cfg {:keys [::rpc/profile-id run-id evidence]}]
  (checks/run-core-checks! cfg profile-id run-id (or evidence {})))

(defn completion-report
  [cfg {:keys [::rpc/profile-id run-id]}]
  (checks/completion-report! cfg profile-id run-id))

(defn attach-run
  [cfg {:keys [::rpc/profile-id run-id workspace-id goal]}]
  (runs/attach-workspace! cfg profile-id run-id workspace-id goal))

(defn get-run
  [cfg {:keys [::rpc/profile-id run-id]}]
  (runs/get! cfg profile-id run-id))

(defn update-progress
  [cfg {:keys [::rpc/profile-id run-id] :as params}]
  (runs/update-progress!
   cfg profile-id run-id
   (-> params
       (dissoc ::rpc/profile-id :run-id)
       (update :status #(some-> % keyword)))))

(defn pause-run
  [cfg {:keys [::rpc/profile-id run-id next-action]}]
  (runs/pause! cfg profile-id run-id next-action))

(defn block-run
  [cfg {:keys [::rpc/profile-id run-id blockers next-action]}]
  (runs/block! cfg profile-id run-id blockers next-action))

(defn resume-run
  [cfg {:keys [::rpc/profile-id run-id]}]
  (runs/resume! cfg profile-id run-id))

(defn create-handoff
  [cfg {:keys [::rpc/profile-id run-id state]}]
  (runs/create-handoff! cfg profile-id run-id (or state {})))

(defn complete-run
  [cfg {:keys [::rpc/profile-id run-id]}]
  (runs/complete! cfg profile-id run-id))

(defn latest-run
  [cfg {:keys [::rpc/profile-id workspace-id]}]
  (runs/latest-for-workspace! cfg profile-id workspace-id))
