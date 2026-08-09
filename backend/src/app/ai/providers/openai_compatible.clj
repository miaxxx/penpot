;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.ai.providers.openai-compatible
  (:require
   [app.ai.providers.protocol :refer [Provider]]
   [app.ai.secrets :as secrets]
   [app.common.exceptions :as ex]
   [app.common.json :as json]
   [app.common.time :as ct]
   [app.http.client :as http]
   [clojure.string :as str]))

(def max-provider-response-bytes (* 2 1024 1024))

(defn- endpoint-uri
  [base-url suffix]
  (str (str/replace base-url #"/+$" "") suffix))

(defn- models-uri
  [base-url]
  (endpoint-uri base-url "/models"))

(defn- chat-uri
  [base-url]
  (endpoint-uri base-url "/chat/completions"))

(defn- valid-credential!
  [api-key]
  (when-not (secrets/valid-session-key? api-key)
    (ex/raise :type :validation
              :code :invalid-ai-credential
              :hint "invalid AI provider credential")))

(defn- provider-error!
  [status]
  (ex/raise :type :validation
            :code :ai-provider-request-failed
            :status status
            :hint "AI provider rejected the structured design request"))

(defn- response-content
  [response]
  (let [body (:body response)]
    (when (or (not (string? body))
              (> (count body) max-provider-response-bytes))
      (ex/raise :type :validation
                :code :invalid-ai-provider-response
                :hint "AI provider returned an invalid or oversized response"))
    (let [decoded (try
                    (json/decode body :key-fn keyword)
                    (catch Throwable _
                      (ex/raise :type :validation
                                :code :invalid-ai-provider-response
                                :hint "AI provider response is not valid JSON")))
          content (get-in decoded [:choices 0 :message :content])]
      (when-not (string? content)
        (ex/raise :type :validation
                  :code :missing-ai-provider-content
                  :hint "AI provider response did not contain assistant content"))
      content)))

(defrecord OpenAICompatibleProvider []
  Provider
  (test-connection! [_ cfg {:keys [base-url api-key model]}]
    (valid-credential! api-key)

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
       :credential (secrets/last-four api-key)}))

  (generate-design! [_ cfg {:keys [base-url api-key model temperature
                                   max-tokens messages]}]
    (valid-credential! api-key)
    (let [payload {"model" model
                   "temperature" (double (or temperature 0.2))
                   "max_tokens" (long (or max-tokens 4096))
                   "messages" (mapv (fn [{:keys [role content]}]
                                      {"role" (name role)
                                       "content" content})
                                    messages)}
          response
          (http/req-with-redirects
           cfg
           {:method :post
            :uri (chat-uri base-url)
            :headers {"accept" "application/json"
                      "content-type" "application/json"
                      "authorization" (str "Bearer " api-key)}
            :body (json/encode payload :key-fn str)
            :timeout (ct/duration "60s")}
           {:max-redirects 2})
          status (:status response)]
      (when-not (<= 200 status 299)
        (provider-error! status))
      (response-content response))))

(def provider
  (->OpenAICompatibleProvider))
