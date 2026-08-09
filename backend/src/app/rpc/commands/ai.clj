;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.rpc.commands.ai
  "Unified AI Design Agent RPC implementation.

  Every write entry point creates or advances an app.ai.proposals record. Only
  the authenticated Penpot workspace may begin/complete native apply."
  (:require
   [app.ai.adapters.mcp :as mcp]
   [app.ai.proposals :as proposals]
   [app.ai.providers.openai-compatible :as openai-compatible]
   [app.ai.providers.protocol :as provider]
   [app.ai.service :as service]
   [app.common.ai.tools :as tools]
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.config :as cf]
   [app.rpc :as-alias rpc]))

(def schema:test-ai-provider
  [:map {:title "test-ai-provider" :closed true}
   [:provider [:enum "openai-compatible" "openai"]]
   [:base-url [:string {:min 8 :max 2048}]]
   [:api-key [:string {:min 1 :max 4096}]]
   [:model [:string {:min 1 :max 256}]]])

(def schema:generate-ai-design-proposal
  [:map {:title "generate-ai-design-proposal" :closed true}
   [:file-id ::sm/uuid]
   [:page-id ::sm/uuid]
   [:base-revision [:int {:min 0}]]
   [:provider [:enum "openai-compatible" "openai"]]
   [:base-url [:string {:min 8 :max 2048}]]
   [:api-key [:string {:min 1 :max 4096}]]
   [:model [:string {:min 1 :max 256}]]
   [:temperature [:number {:min 0 :max 2}]]
   [:max-tokens [:int {:min 256 :max 32768}]]
   [:mode [:enum "generate" "modify" "refactor" "adapt"]]
   [:scope [:enum "selection" "page" "component"]]
   [:prompt [:string {:min 1 :max 12000}]]
   [:context :map]])

(def schema:create-ai-design-proposal
  [:map {:title "create-ai-design-proposal" :closed true}
   [:file-id ::sm/uuid]
   [:page-id ::sm/uuid]
   [:base-revision [:int {:min 0}]]
   [:mode [:enum "generate" "modify" "refactor" "adapt"]]
   [:dsl-type [:enum "document" "patch"]]
   [:scope :map]
   [:plan {:optional true} :map]
   [:dsl :map]])

(def schema:proposal-id
  [:map {:closed true}
   [:proposal-id ::sm/uuid]])

(def schema:preview-proposal
  [:map {:closed true}
   [:proposal-id ::sm/uuid]
   [:preview :map]])

(def schema:complete-proposal
  [:map {:closed true}
   [:proposal-id ::sm/uuid]
   [:apply-token ::sm/uuid]
   [:transaction-id [:string {:min 1 :max 256}]]])

(def schema:conflict-proposal
  [:map {:closed true}
   [:proposal-id ::sm/uuid]
   [:apply-token ::sm/uuid]
   [:error :map]])

(def schema:invoke-mcp-tool
  [:map {:closed true}
   [:tool-id [:string {:min 1 :max 160}]]
   [:arguments {:optional true} :map]])

(defn- enabled!
  []
  (when-not (contains? cf/flags :ai-design-agent)
    (ex/raise :type :restriction
              :code :ai-design-agent-disabled
              :hint "AI Design Agent is disabled")))

(defn- mcp-enabled!
  []
  (enabled!)
  (when-not (contains? cf/flags :mcp)
    (ex/raise :type :restriction
              :code :mcp-disabled
              :hint "MCP is disabled")))

(defn- provider-instance
  [provider-name]
  (if (contains? #{"openai" "openai-compatible"} provider-name)
    openai-compatible/provider
    (ex/raise :type :validation
              :code :unsupported-ai-provider
              :hint "AI provider is not supported")))

(defn test-ai-provider
  [cfg {:keys [provider] :as params}]
  (enabled!)
  (provider/test-connection! (provider-instance provider) cfg params))

(defn generate-ai-design-proposal
  [cfg {:keys [::rpc/profile-id provider mode file-id page-id base-revision
               context] :as params}]
  (enabled!)
  (let [generated
        (service/generate-proposal!
         (provider-instance provider)
         cfg
         (assoc params
                :mode (keyword mode)
                :scope (keyword (:scope params))))
        scope (or (:scope context) (get context "scope"))]
    (proposals/create!
     cfg
     {:profile-id profile-id
      :file-id file-id
      :page-id page-id
      :origin :internal
      :mode (keyword mode)
      :dsl-type (:dsl-type generated)
      :base-revision base-revision
      :scope scope
      :plan (:plan generated)
      :dsl (:dsl generated)})))

(defn create-ai-design-proposal
  [cfg {:keys [::rpc/profile-id] :as params}]
  (enabled!)
  (proposals/create!
   cfg
   (-> params
       (assoc :profile-id profile-id :origin :rpc)
       (update :mode keyword)
       (update :dsl-type keyword)
       (dissoc ::rpc/profile-id))))

(defn get-ai-design-proposal
  [cfg {:keys [::rpc/profile-id proposal-id]}]
  (proposals/get! cfg profile-id proposal-id))

(defn preview-ai-design-proposal
  [cfg {:keys [::rpc/profile-id proposal-id preview]}]
  (proposals/mark-previewed! cfg profile-id proposal-id preview))

(defn discard-ai-design-proposal
  [cfg {:keys [::rpc/profile-id proposal-id]}]
  (proposals/discard! cfg profile-id proposal-id))

(defn request-ai-design-proposal-apply
  [cfg {:keys [::rpc/profile-id proposal-id]}]
  (proposals/request-apply! cfg profile-id proposal-id))

(defn begin-ai-design-proposal-apply
  [cfg {:keys [::rpc/profile-id proposal-id]}]
  (proposals/begin-apply! cfg profile-id proposal-id))

(defn complete-ai-design-proposal-apply
  [cfg {:keys [::rpc/profile-id proposal-id apply-token transaction-id]}]
  (proposals/complete-apply! cfg profile-id proposal-id apply-token transaction-id))

(defn conflict-ai-design-proposal
  [cfg {:keys [::rpc/profile-id proposal-id apply-token error]}]
  (proposals/conflict! cfg profile-id proposal-id apply-token error))

(defn list-ai-design-tools
  [_cfg _params]
  (enabled!)
  {:registry-version tools/registry-version
   :tools (mapv mcp/tool-descriptor (tools/list-tools :rpc))})

(defn list-ai-mcp-tools
  [_cfg _params]
  (mcp-enabled!)
  (mcp/list-tools))

(defn invoke-ai-mcp-tool
  [cfg {:keys [::rpc/profile-id tool-id arguments]}]
  (mcp-enabled!)
  (mcp/invoke! cfg
               {:profile-id profile-id
                :transport :mcp}
               tool-id
               (or arguments {})))
