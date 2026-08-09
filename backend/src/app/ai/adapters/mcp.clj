;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.ai.adapters.mcp
  "Transport-neutral MCP adapter over the unified AI operation kernel.

  MCP may read Harness state and create Proposals. It cannot submit native
  verification evidence, complete a Harness run or invoke canvas commit."
  (:require
   [app.ai.harness.artifacts :as harness-artifacts]
   [app.ai.harness.checks :as harness-checks]
   [app.ai.harness.runs :as harness-runs]
   [app.ai.proposal-queries :as proposal-queries]
   [app.ai.proposals :as proposals]
   [app.common.ai.repository-tools :as repository-tools]
   [app.common.ai.tools :as tools]
   [app.common.ai.validation :as validation]
   [app.common.exceptions :as ex]
   [app.common.uuid :as uuid]))

(def transport :mcp)

(defn- getv [value & keys]
  (some (fn [key]
          (when (contains? value key) (get value key)))
        keys))

(defn- coerce-uuid! [value field required?]
  (cond
    (uuid? value) value
    (string? value)
    (or (uuid/parse* value)
        (ex/raise :type :validation
                  :code :invalid-ai-tool-uuid
                  :hint "MCP tool UUID argument is invalid"
                  :field field))
    (and (nil? value) (not required?)) nil
    :else
    (ex/raise :type :validation
              :code :missing-ai-tool-uuid
              :hint "MCP tool UUID argument is required"
              :field field)))

(defn- actor-profile-id! [actor]
  (coerce-uuid! (:profile-id actor) :profile-id true))

(defn- registered-tool [tool-id]
  (or (tools/get-tool tool-id)
      (repository-tools/get-tool tool-id)))

(defn- ensure-tool! [tool-id]
  (let [tool-id (keyword tool-id)
        tool (registered-tool tool-id)]
    (when-not tool
      (ex/raise :type :validation
                :code :unknown-ai-tool
                :hint "AI tool is not registered"))
    (when-not (contains? (:transports tool) transport)
      (ex/raise :type :restriction
                :code :ai-tool-not-available
                :hint "AI tool is not available through MCP"
                :tool tool-id))
    tool))

(defn tool-descriptor [tool]
  (let [id (:id tool)
        read-only? (= :read (:access tool))]
    {:id id
     :name (name id)
     :description (:description tool)
     :inputSchema (:input-schema tool)
     :access (:access tool)
     :capability (:capability tool)
     :confirmation (:confirmation tool)
     :result (:result tool)
     :annotations
     {:readOnlyHint read-only?
      :destructiveHint (contains? #{:proposal/discard :canvas/commit}
                                  (:capability tool))
      :idempotentHint (contains? #{:tools/read :canvas/read :dsl/validate
                                  :proposal/read :proposal/request-apply
                                  :harness/read}
                                (:capability tool))}
     :_meta
     {:registryVersion (:version tool)
      :access (name (:access tool))
      :capability (str (:capability tool))
      :confirmation (name (:confirmation tool))
      :result (name (:result tool))}}))

(defn list-tools []
  (let [registered (concat (tools/list-tools transport)
                           (repository-tools/list-tools transport))]
    {:registry-version tools/registry-version
     :harness-version "2.0"
     :tools (mapv tool-descriptor (sort-by (comp name :id) registered))}))

(defn- workspace-context! [actor]
  (or (:workspace-context actor)
      (ex/raise :type :validation
                :code :workspace-bridge-required
                :hint "canvas read tools require an authenticated live Penpot workspace bridge")))

(defn- dsl-type [arguments]
  (some-> (getv arguments :dsl-type :dslType "dslType" "dsl-type") keyword))

(defn- proposal-id [arguments]
  (coerce-uuid! (getv arguments :proposal-id :proposalId
                      "proposalId" "proposal-id")
                :proposal-id true))

(defn- file-id [arguments]
  (coerce-uuid! (getv arguments :file-id :fileId "fileId" "file-id")
                :file-id true))

(defn- page-id
  ([arguments] (page-id arguments false))
  ([arguments required?]
   (coerce-uuid! (getv arguments :page-id :pageId "pageId" "page-id")
                 :page-id required?)))

(defn- workspace-id [arguments]
  (coerce-uuid! (getv arguments :workspace-id :workspaceId
                      "workspaceId" "workspace-id")
                :workspace-id true))

(defn- run-id [arguments]
  (coerce-uuid! (getv arguments :run-id :runId "runId" "run-id")
                :run-id true))

(defn- validate-dsl [arguments]
  (let [type (dsl-type arguments)
        dsl (getv arguments :dsl "dsl")
        result (case type
                 :document (validation/validate-document dsl)
                 :patch (validation/validate-patch dsl)
                 {:valid? false
                  :errors [{:code :invalid-dsl-type
                            :message "dslType must be document or patch"}]})]
    (select-keys result [:valid? :errors :warnings :ir])))

(defn- canonical-proposal-arguments [arguments]
  {:file-id (file-id arguments)
   :page-id (page-id arguments true)
   :base-revision (getv arguments :base-revision :baseRevision
                        "baseRevision" "base-revision")
   :mode (getv arguments :mode "mode")
   :scope (getv arguments :scope "scope")
   :plan (or (getv arguments :plan "plan") {})
   :dsl (getv arguments :dsl "dsl")})

(defn- create-proposal! [cfg actor requested-dsl-type arguments]
  (proposals/create!
   cfg
   (assoc (canonical-proposal-arguments arguments)
          :profile-id (actor-profile-id! actor)
          :origin :mcp
          :dsl-type requested-dsl-type)))

(defn invoke!
  "Invokes a registered MCP tool. Writes return proposalId or Harness metadata;
  MCP never receives native commit or completion authority."
  [cfg actor tool-id arguments]
  (let [tool-id (keyword tool-id)
        profile-id (actor-profile-id! actor)
        _ (ensure-tool! tool-id)]
    (case tool-id
      :tools.list (list-tools)
      :canvas.summary
      (let [context (workspace-context! actor)]
        (or (:summary context)
            (select-keys context [:snapshot-version :scope :revision :summary])))
      :canvas.read (workspace-context! actor)
      :dsl.validate (validate-dsl arguments)
      :proposal.create-document (create-proposal! cfg actor :document arguments)
      :proposal.create-patch (create-proposal! cfg actor :patch arguments)
      :proposal.list
      (proposal-queries/list-active! cfg profile-id
                                     (file-id arguments) (page-id arguments))
      :proposal.get (proposals/get! cfg profile-id (proposal-id arguments))
      :proposal.discard
      (proposals/discard! cfg profile-id (proposal-id arguments))
      :proposal.request-apply
      (proposals/request-apply! cfg profile-id (proposal-id arguments))

      :harness.workspace.ensure
      (harness-artifacts/ensure-workspace!
       cfg profile-id {:file-id (file-id arguments)
                       :page-id (page-id arguments)})
      :harness.artifacts.list
      (harness-artifacts/list-artifacts! cfg profile-id (workspace-id arguments))
      :harness.artifact.get
      (harness-artifacts/get-artifact!
       cfg profile-id (workspace-id arguments)
       (getv arguments :path "path"))
      :harness.run.latest
      (harness-runs/latest-for-workspace! cfg profile-id (workspace-id arguments))
      :harness.checks.list
      (harness-checks/list-definitions)
      :harness.checks.results
      (harness-checks/list-results! cfg profile-id (run-id arguments))

      (ex/raise :type :restriction
                :code :ai-tool-not-available
                :hint "MCP cannot invoke this internal operation"
                :tool tool-id))))
