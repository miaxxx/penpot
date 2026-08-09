;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.ai.providers.openai-compatible
  (:require
   [app.ai.providers.protocol :refer [Provider]]
   [app.ai.secrets :as secrets]
   [app.common.exceptions :as ex]
   [app.common.time :as ct]
   [app.http.client :as http]
   [clojure.string :as str]))

(defn- models-uri
  [base-url]
  (str (str/replace base-url #"/+$" "") "/models"))

(defrecord OpenAICompatibleProvider []
  Provider
  (test-connection! [_ cfg {:keys [base-url api-key model]}]
    (when-not (secrets/valid-session-key? api-key)
      (ex/raise :type :validation
                :code :invalid-ai-credential
                :hint "invalid AI provider credential"))

    ;; app.http.client validates the initial URI and every redirect target
    ;; against the SSRF blocklist. Never bypass this for user-provided URLs.
    (let [response
          (http/req-with-redirects
           cfg
           {:method :get
            :uri (models-uri base-url)
            :headers {"accept" "application/json"
                      "authorization" (str "Bearer " api-key)}
            :timeout (ct/duration "15s")}
           {:max-redirects 2})
          status (:status response)]
      (when-not (<= 200 status 299)
        (ex/raise :type :validation
                  :code :ai-provider-connection-failed
                  :status status
                  :hint "AI provider rejected the connection test"))
      {:status :ok
       :provider :openai-compatible
       :model model
       :credential (secrets/last-four api-key)})))

(def provider
  (->OpenAICompatibleProvider))
