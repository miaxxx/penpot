;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns backend-tests.ai-service-test
  (:require
   [app.ai.providers.protocol :as provider]
   [app.ai.service :as service]
   [clojure.test :as t]))

(def valid-document-json
  "{\"plan\":{\"title\":\"Create cards\",\"steps\":[\"Create section\"]},\"dslType\":\"document\",\"dsl\":{\"dslVersion\":\"1.0\",\"document\":{\"id\":\"section\",\"kind\":\"section\",\"children\":[]}}}")

(def invalid-json
  "{\"plan\":{\"title\":\"Broken\",\"steps\":[]},\"dslType\":\"document\",\"dsl\":{\"dslVersion\":\"1.0\"}}")

(defn- fake-provider
  [responses]
  (let [responses (atom responses)]
    (reify provider/Provider
      (test-connection! [_ _ _] {:status :ok})
      (generate-design! [_ _ _]
        (let [response (first @responses)]
          (swap! responses rest)
          response)))))

(def request
  {:base-url "https://example.com/v1"
   :api-key "secret"
   :model "model"
   :temperature 0.2
   :max-tokens 1024
   :mode :generate
   :scope :page
   :prompt "Create cards"
   :context {:nodes []}})

(t/deftest accepts-schema-valid-provider-output
  (let [result (service/generate-proposal!
                (fake-provider [valid-document-json])
                {}
                request)]
    (t/is (:valid? result))
    (t/is (= :document (:dsl-type result)))
    (t/is (= "Create cards" (get-in result [:plan :title])))))

(t/deftest performs-one-constrained-repair
  (let [result (service/generate-proposal!
                (fake-provider [invalid-json valid-document-json])
                {}
                request)]
    (t/is (:valid? result))
    (t/is (:repaired? result))))
