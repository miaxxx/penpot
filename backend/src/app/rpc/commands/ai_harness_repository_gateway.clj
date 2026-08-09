;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.rpc.commands.ai-harness-repository-gateway
  "One authenticated RPC gateway for Repository Harness operations."
  (:require
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.ai-harness-repository :as repository]))

(def schema:invoke
  [:map {:closed true}
   [:action
    [:enum
     "workspace.ensure" "workspace.get"
     "artifact.list" "artifact.get" "artifact.upsert" "artifact.route"
     "package.export" "package.import"
     "environment.inspect" "environment.latest"
     "checks.list" "checks.results" "checks.run" "checks.run-core"
     "checks.completion"
     "run.attach" "run.get" "run.update" "run.pause" "run.block"
     "run.resume" "run.handoff" "run.complete" "run.latest"]]
   [:arguments {:optional true} :map]])

(defn invoke
  [cfg {:keys [::rpc/profile-id action arguments]}]
  (let [params (assoc (or arguments {}) ::rpc/profile-id profile-id)]
    (case action
      "workspace.ensure" (repository/ensure-workspace cfg params)
      "workspace.get" (repository/get-workspace cfg params)
      "artifact.list" (repository/list-artifacts cfg params)
      "artifact.get" (repository/get-artifact cfg params)
      "artifact.upsert" (repository/upsert-artifact cfg params)
      "artifact.route" (repository/route-artifacts cfg params)
      "package.export" (repository/export-package cfg params)
      "package.import" (repository/import-package cfg params)
      "environment.inspect" (repository/inspect-environment cfg params)
      "environment.latest" (repository/latest-environment cfg params)
      "checks.list" (repository/list-checks cfg params)
      "checks.results" (repository/list-check-results cfg params)
      "checks.run" (repository/run-check cfg params)
      "checks.run-core" (repository/run-core-checks cfg params)
      "checks.completion" (repository/completion-report cfg params)
      "run.attach" (repository/attach-run cfg params)
      "run.get" (repository/get-run cfg params)
      "run.update" (repository/update-progress cfg params)
      "run.pause" (repository/pause-run cfg params)
      "run.block" (repository/block-run cfg params)
      "run.resume" (repository/resume-run cfg params)
      "run.handoff" (repository/create-handoff cfg params)
      "run.complete" (repository/complete-run cfg params)
      "run.latest" (repository/latest-run cfg params)
      (ex/raise :type :validation
                :code :unknown-ai-harness-repository-action
                :hint "Repository Harness action is not registered"))))
