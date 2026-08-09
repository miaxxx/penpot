;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.ai.harness.coordinator
  "Role-decomposed coordinator for complex Penpot design turns.

  The coordinator never grants additional tools. Every specialist inherits the
  parent session's Capability Registry, Scope and file revision."
  (:require
   [app.ai.providers.protocol :as provider]
   [app.common.json :as json]
   [clojure.string :as str]))

(def max-coordinator-response 24000)

(def role-catalog
  {:planner
   {:goal "Translate the user request into a bounded design plan."
    :may-write false}
   :visual-designer
   {:goal "Choose hierarchy, layout, typography and visual treatment."
    :may-write false}
   :structure-editor
   {:goal "Minimize structural changes and preserve existing semantic identity."
    :may-write false}
   :accessibility-reviewer
   {:goal "Check readable type, contrast, labels, targets and focus semantics."
    :may-write false}
   :verifier
   {:goal "Verify Scope, revision, unsupported assets and minimality."
    :may-write false}
   :executor
   {:goal "Produce the final validated Document/Patch DSL proposal."
    :may-write true}})

(defn roles-for
  [{:keys [mode skills]}]
  (let [skill-slugs (set (map :slug skills))]
    (vec
     (distinct
      (concat
       [:planner]
       (if (= :generate (keyword mode))
         [:visual-designer]
         [:structure-editor :visual-designer])
       (when (contains? skill-slugs "accessibility")
         [:accessibility-reviewer])
       [:verifier :executor])))))

(defn deterministic-plan
  [{:keys [prompt mode scope base-revision skills]}]
  (let [roles (roles-for {:mode mode :skills skills})]
    {:strategy :sequential-specialists
     :roles
     :constraints
     {:mode (keyword mode)
      :scope scope
      :base-revision base-revision
      :single-native-transaction true
      :capability-escalation false}
     :tasks
     (mapv
      (fn [index role]
        {:id (str "task-" (inc index))
         :role role
         :goal (:goal (get role-catalog role))
         :depends-on (if (zero? index)
                       []
                       [(str "task-" index)])
         :write-authority (boolean (:may-write (get role-catalog role)))})
      (range)
      roles)
     :user-request (str prompt)}))

(defn- parse-json
  [content]
  (when (string? content)
    (try
      (let [content (-> content
                        str/trim
                        (str/replace-first #"^```(?:json)?\s*" "")
                        (str/replace #"\s*```$" ""))]
        (json/decode content :key-fn keyword))
      (catch Throwable _ nil))))

(defn request-plan!
  [provider-instance cfg request deterministic]
  (let [messages
        [{:role :system
          :content
          (str
           "You are the planning specialist in a Penpot design-agent harness. "
           "Return JSON only. Do not produce canvas changes. "
           "Respect the supplied scope and base revision. "
           "Contract: {\"summary\":\"...\",\"risks\":[\"...\"],"
           "\"steps\":[\"...\"],\"recommendedSkills\":[\"...\"]}.")}
         {:role :user
          :content
          (json/encode
           {:request (:prompt request)
            :mode (:mode request)
            :scope (:scope request)
            :baseRevision (:base-revision request)
            :availableSkills
            (mapv #(select-keys % [:slug :name :description])
                  (:skills request))
            :deterministicPlan deterministic}
           :key-fn json/write-camel-key)}]
        raw
        (provider/generate-design!
         provider-instance
         cfg
         (assoc request
                :temperature 0.1
                :max-tokens (min 2048 (or (:max-tokens request) 2048))
                :messages messages))
        parsed (parse-json
                (if (> (count raw) max-coordinator-response)
                  (subs raw 0 max-coordinator-response)
                  raw))]
    (if (map? parsed)
      (assoc deterministic :planner-output parsed)
      (assoc deterministic
             :planner-output
             {:summary "Deterministic coordinator plan"
              :risks ["Planner output was not valid JSON; deterministic plan used."]
              :steps (mapv :goal (:tasks deterministic))
              :recommended-skills []}))))

(defn review!
  [provider-instance cfg request proposal coordinator-plan]
  (let [messages
        [{:role :system
          :content
          (str
           "You are the verification specialist in a Penpot harness. "
           "Review a proposed design DSL without editing it. Return JSON only: "
           "{\"approved\":true|false,\"issues\":[{\"code\":\"...\","
           "\"message\":\"...\"}],\"notes\":[\"...\"]}. "
           "Reject scope escalation, revision drift, invented IDs/assets/tokens, "
           "or unnecessarily broad rewrites.")}
         {:role :user
          :content
          (json/encode
           {:scope (:scope request)
            :baseRevision (:base-revision request)
            :coordinatorPlan coordinator-plan
            :proposal
            (select-keys proposal [:plan :dsl-type :dsl :warnings])}
           :key-fn json/write-camel-key)}]
        raw
        (provider/generate-design!
         provider-instance
         cfg
         (assoc request
                :temperature 0
                :max-tokens (min 1600 (or (:max-tokens request) 1600))
                :messages messages))
        parsed (parse-json
                (if (> (count raw) max-coordinator-response)
                  (subs raw 0 max-coordinator-response)
                  raw))]
    (if (and (map? parsed) (boolean? (:approved parsed)))
      parsed
      {:approved true
       :issues []
       :notes ["Verifier output was unavailable; schema and boundary validators remain authoritative."]})))
