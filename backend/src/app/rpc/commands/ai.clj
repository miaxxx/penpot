;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.rpc.commands.ai
  "AI Design Agent RPC implementation.

  Penpot's RPC registry currently scans an explicit namespace list. The public
  method is registered from a scanned command namespace until this namespace is
  added to that list in the dedicated backend-provider PR."
  (:require
   [app.ai.providers.openai-compatible :as openai-compatible]
   [app.ai.providers.protocol :as provider]
   [app.common.exceptions :as ex]
   [app.config :as cf]))

(def schema:test-ai-provider
  [:map {:title "test-ai-provider" :closed true}
   [:provider [:enum "openai-compatible" "openai"]]
   [:base-url [:string {:min 8 :max 2048}]]
   [:api-key [:string {:min 1 :max 4096}]]
   [:model [:string {:min 1 :max 256}]]])

(defn test-ai-provider
  [cfg {:keys [provider] :as params}]
  (when (contains? cf/flags :disable-ai-design-agent)
    (ex/raise :type :restriction
              :code :ai-design-agent-disabled
              :hint "AI Design Agent is disabled"))

  (if (contains? #{"openai" "openai-compatible"} provider)
    (provider/test-connection! openai-compatible/provider cfg params)
    (ex/raise :type :validation
              :code :unsupported-ai-provider
              :hint "AI provider is not supported")))
