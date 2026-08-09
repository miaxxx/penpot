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

(def escalated-page-patch-json
  "{\"plan\":{\"title\":\"Edit selection\",\"steps\":[\"Change fill\"]},\"dslType\":\"patch\",\"dsl\":{\"dslVersion\":\"1.0\",\"baseRevision\":7,\"scope\":{\"type\":\"page\"},\"operations\":[{\"op\":\"set\",\"nodeId\":\"card-1\",\"path\":\"style.fill\",\"value\":\"#0066ff\"}]}}")

(def valid-selection-patch-json
  "{\"plan\":{\"title\":\"Edit selection\",\"steps\":[\"Change fill\"]},\"dslType\":\"patch\",\"dsl\":{\"dslVersion\":\"1.0\",\"baseRevision\":7,\"scope\":{\"type\":\"selection\",\"rootId\":\"card-1\"},\"operations\":[{\"op\":\"set\",\"nodeId\":\"card-1\",\"path\":\"style.fill\",\"value\":\"#0066ff\"}]}}")

(def wrong-revision-patch-json
  "{\"plan\":{\"title\":\"Edit selection\",\"steps\":[\"Change fill\"]},\"dslType\":\"patch\",\"dsl\":{\"dslVersion\":\"1.0\",\"baseRevision\":99,\"scope\":{\"type\":\"selection\",\"rootId\":\"card-1\"},\"operations\":[{\"op\":\"set\",\"nodeId\":\"card-1\",\"path\":\"style.fill\",\"value\":\"#0066ff\"}]}}")

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
   :context {:revision 0
             :scope {:type :page}
             :nodes []}})

(def selection-request
  (assoc request
         :mode :modify
         :scope :selection
         :prompt "Make the selected card blue"
         :context {:revision 7
                   :scope {:type :selection
                           :rootId "card-1"}
                   :nodes [{:id "card-1"}]}))

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

(t/deftest repairs-model-scope-escalation
  (let [result (service/generate-proposal!
                (fake-provider [escalated-page-patch-json
                                valid-selection-patch-json])
                {}
                selection-request)]
    (t/is (:valid? result))
    (t/is (:repaired? result))
    (t/is (= "selection" (get-in result [:dsl :scope :type])))
    (t/is (= "card-1" (get-in result [:dsl :scope :rootId])))))

(t/deftest repairs-base-revision-mismatch
  (let [result (service/generate-proposal!
                (fake-provider [wrong-revision-patch-json
                                valid-selection-patch-json])
                {}
                selection-request)]
    (t/is (:valid? result))
    (t/is (:repaired? result))
    (t/is (= 7 (get-in result [:dsl :baseRevision])))))
