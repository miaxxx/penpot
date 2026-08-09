;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.ai.adapters.mcp
  "Transport-neutral MCP adapter over the unified AI operation kernel.

  An HTTP/SSE MCP transport must authenticate the actor and inject profile-id.
  Live canvas context must come from an authenticated Penpot workspace bridge,
  never from untrusted tool arguments. MCP can create and manage proposals but
  cannot invoke the native commit capability."
  (:require
   [app.ai.proposals :as proposals]
   [app.common.ai.tools :as tools]
   [app.common.ai.validation :as validation]
   [app.common.exceptions :as ex]))

(def transport :mcp)

(defn- getv
  [value & keys]
  (some (fn [key]
          (when (contains? value key)
            (get value key)))
        keys))

(defn- ensure-tool!
  [tool-id]
  (let [tool-id (keyword tool-id)
        tool (tools/get-tool tool-id)]
    (when-not tool
      (ex/raise :type :validation
                :code :unknown-ai-tool
                :hint "AI tool is not registered"))
    (when-not (tools/allowed? tool-id transport)
      (ex/raise :type :restriction
                :code :ai-tool-not-available
                :hint "AI tool is not available through MCP"
                :tool tool-id))
    tool))

(defn tool-descriptor
  [tool]
  (select-keys tool
               [:id :version :description :access :capability
                :confirmation :result :input-schema]))

(defn list-tools
  []
  {:registry-version tools/registry-version
   :tools (mapv tool-descriptor (tools/list-tools transport))})

(defn- workspace-context!
  [actor]
  (or (:workspace-context actor)
      (ex/raise :type :validation
                :code :workspace-bridge-required
                :hint "canvas read tools require an authenticated live Penpot workspace bridge")))

(defn- dsl-type
  [arguments]
  (some-> (getv arguments :dsl-type :dslType "dslType" "dsl-type") keyword))

(defn- proposal-id
  [arguments]
  (getv arguments :proposal-id :proposalId "proposalId" "proposal-id"))

(defn- validate-dsl
  [arguments]
  (let [dsl-type (dsl-type arguments)
        dsl (getv arguments :dsl "dsl")
        result (case dsl-type
                 :document (validation/validate-document dsl)
                 :patch (validation/validate-patch dsl)
                 {:valid? false
                  :errors [{:code :invalid-dsl-type
                            :message "dslType must be document or patch"}]})]
    (select-keys result [:valid? :errors :warnings :ir])))

(defn- canonical-proposal-arguments
  [arguments]
  {:file-id (getv arguments :file-id :fileId "fileId" "file-id")
   :page-id (getv arguments :page-id :pageId "pageId" "page-id")
   :base-revision (getv arguments :base-revision :baseRevision
                        "baseRevision" "base-revision")
   :mode (getv arguments :mode "mode")
   :scope (getv arguments :scope "scope")
   :plan (or (getv arguments :plan "plan") {})
   :dsl (getv arguments :dsl "dsl")})

(defn- create-proposal!
  [cfg actor requested-dsl-type arguments]
  (let [profile-id (:profile-id actor)]
    (when-not profile-id
      (ex/raise :type :authentication
                :code :authentication-required
                :hint "MCP transport did not provide an authenticated profile"))
    (proposals/create!
     cfg
     (assoc (canonical-proposal-arguments arguments)
            :profile-id profile-id
            :origin :mcp
            :dsl-type requested-dsl-type))))

(defn invoke!
  "Invokes a registered MCP tool. Returns proposalId for write proposals; it
  never returns direct canvas-write success because MCP has no native commit
  capability."
  [cfg actor tool-id arguments]
  (let [tool-id (keyword tool-id)
        _ (ensure-tool! tool-id)]
    (case tool-id
      :tools.list
      (list-tools)

      :canvas.summary
      (let [context (workspace-context! actor)]
        (or (:summary context)
            (select-keys context [:snapshot-version :scope :revision :summary])))

      :canvas.read
      (workspace-context! actor)

      :dsl.validate
      (validate-dsl arguments)

      :proposal.create-document
      (create-proposal! cfg actor :document arguments)

      :proposal.create-patch
      (create-proposal! cfg actor :patch arguments)

      :proposal.get
      (proposals/get! cfg (:profile-id actor) (proposal-id arguments))

      :proposal.discard
      (proposals/discard! cfg (:profile-id actor) (proposal-id arguments))

      :proposal.request-apply
      (proposals/request-apply! cfg (:profile-id actor) (proposal-id arguments))

      (ex/raise :type :restriction
                :code :ai-tool-not-available
                :hint "MCP cannot invoke this internal operation"
                :tool tool-id))))
