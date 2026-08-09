;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.rpc.commands.ai
  "AI Design Agent RPC implementation.

  Penpot's RPC registry currently scans an explicit namespace list. Public
  methods are registered from a scanned command namespace until this namespace
  receives a dedicated scanner entry."
  (:require
   [app.ai.providers.openai-compatible :as openai-compatible]
   [app.ai.providers.protocol :as provider]
   [app.ai.service :as service]
   [app.common.exceptions :as ex]
   [app.config :as cf]))

(def schema:test-ai-provider
  [:map {:title "test-ai-provider" :closed true}
   [:provider [:enum "openai-compatible" "openai"]]
   [:base-url [:string {:min 8 :max 2048}]]
   [:api-key [:string {:min 1 :max 4096}]]
   [:model [:string {:min 1 :max 256}]]])

(def schema:generate-ai-design-proposal
  [:map {:title "generate-ai-design-proposal" :closed true}
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

(defn- enabled!
  []
  (when-not (contains? cf/flags :ai-design-agent)
    (ex/raise :type :restriction
              :code :ai-design-agent-disabled
              :hint "AI Design Agent is disabled")))

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
  [cfg {:keys [provider mode scope] :as params}]
  (enabled!)
  (service/generate-proposal!
   (provider-instance provider)
   cfg
   (assoc params
          :mode (keyword mode)
          :scope (keyword scope))))
