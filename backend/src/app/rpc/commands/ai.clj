;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.rpc.commands.ai
  "Authenticated AI Design Agent RPC entry points. The first iteration only
  exposes a provider connection test; generation endpoints will be added once
  structured response compilation is connected."
  (:require
   [app.ai.providers.openai-compatible :as openai-compatible]
   [app.ai.providers.protocol :as provider]
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.config :as cf]
   [app.rpc.doc :as-alias doc]
   [app.util.services :as sv]))

(def ^:private schema:test-ai-provider
  [:map {:title "test-ai-provider" :closed true}
   [:provider [:enum "openai-compatible" "openai"]]
   [:base-url [:string {:min 8 :max 2048}]]
   [:api-key [:string {:min 1 :max 4096}]]
   [:model [:string {:min 1 :max 256}]]])

(sv/defmethod ::test-ai-provider
  {::doc/added "2.10"
   ::sm/params schema:test-ai-provider}
  [cfg {:keys [provider] :as params}]
  (when (contains? cf/flags :disable-ai-design-agent)
    (ex/raise :type :restriction
              :code :ai-design-agent-disabled
              :hint "AI Design Agent is disabled"))

  (case provider
    ("openai" "openai-compatible")
    (provider/test-connection! openai-compatible/provider cfg params)

    (ex/raise :type :validation
              :code :unsupported-ai-provider
              :hint "AI provider is not supported")))
