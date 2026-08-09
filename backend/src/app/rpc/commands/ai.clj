;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.rpc.commands.ai
  "Unified AI Design Agent, Proposal lifecycle and Harness RPC implementation."
  (:require
   [app.ai.adapters.mcp :as mcp]
   [app.ai.harness.plugins :as harness-plugins]
   [app.ai.harness.registry :as harness-registry]
   [app.ai.harness.service :as harness-service]
   [app.ai.harness.sessions :as harness-sessions]
   [app.ai.harness.skills :as harness-skills]
   [app.ai.proposal-queries :as proposal-queries]
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

(def provider-fields
  [[:provider [:enum "openai-compatible" "openai"]]
   [:base-url [:string {:min 8 :max 2048}]]
   [:api-key [:string {:min 1 :max 4096}]]
   [:model [:string {:min 1 :max 256}]]
   [:temperature [:number {:min 0 :max 2}]]
   [:max-tokens [:int {:min 256 :max 32768}]]])

(def schema:generate-ai-design-proposal
  (into
   [:map {:title "generate-ai-design-proposal" :closed true}
    [:file-id ::sm/uuid]
    [:page-id ::sm/uuid]
    [:base-revision [:int {:min 0}]]]
   (concat
    provider-fields
    [[:mode [:enum "generate" "modify" "refactor" "adapt"]]
     [:scope [:enum "selection" "page" "component"]]
     [:prompt [:string {:min 1 :max 12000}]]
     [:context :map]])))

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

(def schema:list-proposals
  [:map {:closed true}
   [:file-id ::sm/uuid]
   [:page-id {:optional true} ::sm/uuid]])

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

(def schema:install-harness-package
  [:map {:closed true}
   [:filename [:string {:min 1 :max 512}]]
   [:content [:string {:min 1 :max 3000000}]]
   [:encoding [:enum "utf-8" "text" "base64"]]
   [:enabled {:optional true} ::sm/boolean]])

(def schema:harness-item-enabled
  [:map {:closed true}
   [:id [:string {:min 1 :max 160}]]
   [:enabled ::sm/boolean]])

(def schema:harness-item-id
  [:map {:closed true}
   [:id [:string {:min 1 :max 160}]]])

(def schema:create-harness-session
  [:map {:closed true}
   [:file-id ::sm/uuid]
   [:page-id ::sm/uuid]
   [:base-revision [:int {:min 0}]]
   [:scope :map]
   [:mode [:enum "generate" "modify" "refactor" "adapt"]]
   [:transport {:optional true}
    [:enum "internal" "rpc" "mcp" "plugin" "remote"]]
   [:input-mode {:optional true}
    [:enum "assistant" "buddy" "voice" "vim" "remote" "mcp" "plugin"]]
   [:persona {:optional true} [:enum "assistant" "buddy"]]
   [:settings {:optional true} :map]])

(def schema:harness-session-id
  [:map {:closed true}
   [:session-id ::sm/uuid]])

(def schema:list-harness-sessions
  [:map {:closed true}
   [:file-id ::sm/uuid]
   [:page-id {:optional true} ::sm/uuid]])

(def schema:update-harness-settings
  [:map {:closed true}
   [:session-id ::sm/uuid]
   [:settings :map]])

(def schema:list-harness-runs
  [:map {:closed true}
   [:session-id ::sm/uuid]
   [:limit {:optional true} [:int {:min 1 :max 20}]]])

(def schema:run-harness-turn
  (into
   [:map {:closed true}
    [:session-id ::sm/uuid]]
   (concat
    provider-fields
    [[:prompt [:string {:min 1 :max 12000}]]
     [:context :map]
     [:selected-skill-ids {:optional true}
      [:vector [:string {:min 1 :max 160}]]]
     [:input {:optional true}
      [:enum "assistant" "buddy" "voice" "vim" "remote" "mcp" "plugin"]]
     [:coordinator {:optional true} ::sm/boolean]
     [:context-budget {:optional true} [:int {:min 2000 :max 75000}]]])))

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

(defn list-ai-design-proposals
  [cfg {:keys [::rpc/profile-id file-id page-id]}]
  (proposal-queries/list-active! cfg profile-id file-id page-id))

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

(defn get-ai-harness
  [cfg {:keys [::rpc/profile-id]}]
  (enabled!)
  (harness-registry/report cfg profile-id))

(defn install-ai-harness-skill
  [cfg {:keys [::rpc/profile-id] :as params}]
  (enabled!)
  (harness-skills/install!
   cfg profile-id (dissoc params ::rpc/profile-id)))

(defn list-ai-harness-skills
  [cfg {:keys [::rpc/profile-id]}]
  (enabled!)
  (harness-skills/list! cfg profile-id))

(defn set-ai-harness-skill-enabled
  [cfg {:keys [::rpc/profile-id id enabled]}]
  (enabled!)
  (harness-skills/set-enabled! cfg profile-id id enabled))

(defn delete-ai-harness-skill
  [cfg {:keys [::rpc/profile-id id]}]
  (enabled!)
  (harness-skills/delete! cfg profile-id id))

(defn install-ai-harness-plugin
  [cfg {:keys [::rpc/profile-id] :as params}]
  (enabled!)
  (harness-plugins/install!
   cfg profile-id (dissoc params ::rpc/profile-id)))

(defn list-ai-harness-plugins
  [cfg {:keys [::rpc/profile-id]}]
  (enabled!)
  (harness-plugins/list! cfg profile-id))

(defn set-ai-harness-plugin-enabled
  [cfg {:keys [::rpc/profile-id id enabled]}]
  (enabled!)
  (harness-plugins/set-enabled! cfg profile-id id enabled))

(defn delete-ai-harness-plugin
  [cfg {:keys [::rpc/profile-id id]}]
  (enabled!)
  (harness-plugins/delete! cfg profile-id id))

(defn create-ai-harness-session
  [cfg {:keys [::rpc/profile-id] :as params}]
  (enabled!)
  (harness-sessions/create!
   cfg profile-id
   (-> params
       (dissoc ::rpc/profile-id)
       (update :mode keyword)
       (update :transport #(some-> % keyword))
       (update :input-mode #(some-> % keyword))
       (update :persona #(some-> % keyword)))))

(defn get-ai-harness-session
  [cfg {:keys [::rpc/profile-id session-id]}]
  (enabled!)
  (harness-sessions/get! cfg profile-id session-id))

(defn list-ai-harness-sessions
  [cfg {:keys [::rpc/profile-id file-id page-id]}]
  (enabled!)
  (harness-sessions/list! cfg profile-id file-id page-id))

(defn update-ai-harness-session
  [cfg {:keys [::rpc/profile-id session-id settings]}]
  (enabled!)
  (harness-sessions/update-settings!
   cfg profile-id session-id settings))

(defn close-ai-harness-session
  [cfg {:keys [::rpc/profile-id session-id]}]
  (enabled!)
  (harness-sessions/close! cfg profile-id session-id))

(defn list-ai-harness-runs
  [cfg {:keys [::rpc/profile-id session-id limit]}]
  (enabled!)
  (harness-sessions/recent-runs
   cfg profile-id session-id limit))

(defn run-ai-harness-turn
  [cfg {:keys [::rpc/profile-id provider] :as params}]
  (enabled!)
  (harness-service/run-turn!
   (provider-instance provider)
   cfg
   profile-id
   (-> params
       (dissoc ::rpc/profile-id)
       (update :input #(some-> % keyword)))))
