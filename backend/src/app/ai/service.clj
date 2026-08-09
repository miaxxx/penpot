;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.ai.service
  "Schema-first model orchestration for design proposals.

  The service returns plans and data-only Document/Patch DSL. It never receives
  a native Penpot Change commit capability and performs at most one constrained
  repair request when provider output is structurally invalid."
  (:require
   [app.ai.providers.protocol :as provider]
   [app.common.ai.validation :as validation]
   [app.common.exceptions :as ex]
   [app.common.json :as json]
   [clojure.string :as str]))

(def max-context-characters 300000)
(def max-prompt-characters 12000)
(def max-invalid-output-characters 30000)

(def system-prompt
  "You are the Penpot AI Design Agent. Return one JSON object and no markdown.

Your output contract is:
{
  \"plan\": {\"title\": \"short title\", \"steps\": [\"step\"]},
  \"dslType\": \"document\" or \"patch\",
  \"dsl\": <Document DSL or Patch DSL>
}

Document DSL creates new UI. It has dslVersion=1.0 and one document root. Patch
DSL minimally edits existing UI and has dslVersion=1.0, baseRevision, scope and
operations. Prefer Patch DSL for modify/refactor/adapt requests. Never rebuild a
whole page when a minimal patch can satisfy the request.

Supported semantic nodes: page, section, frame, stack, grid, text, image, icon,
shape, divider, heading, paragraph, button, card, badge, input, textarea,
select, tabs, navigation, modal, list, table, form, component,
componentInstance, slot, variant and sectionHeading.

Supported patch operations: create, insert, set, unset, move, remove, duplicate,
replace, bindToken, setVariant, replaceComponent and batch.

Useful semantic paths include name, geometry.x, geometry.y, geometry.width,
geometry.height, geometry.rotation, style.fill, style.stroke,
style.strokeWidth, style.opacity, style.radius, layout.type, layout.direction,
layout.gap, layout.padding, layout.align, layout.justify, layout.width,
layout.height, visible, locked, hidden, interactions and tokens. Advanced native
properties may use penpot.<attribute> but identity/tree properties id, type,
parent-id, frame-id and shapes must only be changed through explicit operations.

Only reference components present in the supplied registry/context. Never invent
a component, token, node id or parent id. Stay inside the declared scope. Do not
return JavaScript, Clojure, executable expressions, network instructions,
filesystem instructions, plugin calls or direct canvas commit commands.")

(defn- truncate
  [value max-length]
  (if (> (count value) max-length)
    (subs value 0 max-length)
    value))

(defn- strip-code-fence
  [content]
  (let [content (str/trim content)]
    (if (str/starts-with? content "```")
      (-> content
          (str/replace-first #"^```(?:json)?\s*" "")
          (str/replace #"\s*```$" "")
          str/trim)
      content)))

(defn- parse-output
  [content]
  (try
    (json/decode (strip-code-fence content) :key-fn keyword)
    (catch Throwable _ nil)))

(defn- normalize-keyword
  [value]
  (cond
    (keyword? value) value
    (string? value) (keyword value)
    :else nil))

(defn- get-either
  [value camel kebab]
  (if (contains? value camel)
    (get value camel)
    (get value kebab)))

(defn- context-scope
  [request]
  (or (get-in request [:context :scope])
      (get-in request [:context "scope"])
      {}))

(defn- boundary-errors
  [request dsl-type dsl]
  (let [requested-mode (:mode request)
        requested-scope (:scope request)
        dsl-scope (:scope dsl)
        actual-scope (normalize-keyword (:type dsl-scope))
        expected-context-scope (context-scope request)
        expected-root (or (get-either expected-context-scope :rootId :root-id)
                          (get-either expected-context-scope "rootId" "root-id"))
        actual-root (get-either dsl-scope :rootId :root-id)
        expected-revision (or (get-in request [:context :revision])
                              (get-in request [:context "revision"]))
        actual-revision (get-either dsl :baseRevision :base-revision)]
    (cond-> []
      (and (not= requested-mode :generate)
           (not= dsl-type :patch))
      (conj {:code :patch-required
             :message "Modify, refactor and adapt requests must return Patch DSL"})

      (and (= dsl-type :patch)
           (not= requested-scope actual-scope))
      (conj {:code :scope-escalation
             :message "Patch scope does not match the user-declared scope"
             :expected requested-scope
             :actual actual-scope})

      (and (= dsl-type :patch)
           (contains? #{:selection :component} requested-scope)
           expected-root
           (not= (str expected-root) (str actual-root)))
      (conj {:code :scope-root-mismatch
             :message "Patch root does not match the active canvas scope"})

      (and (= dsl-type :patch)
           (number? expected-revision)
           (not= expected-revision actual-revision))
      (conj {:code :base-revision-mismatch
             :message "Patch baseRevision does not match the supplied canvas revision"}))))

(defn- validate-output
  [request output]
  (let [dsl-type (normalize-keyword (:dslType output))
        dsl (:dsl output)
        plan (:plan output)
        plan-errors (cond-> []
                      (not (map? plan))
                      (conj {:code :missing-plan :message "Output plan is missing"})
                      (not (string? (:title plan)))
                      (conj {:code :missing-plan-title :message "Plan title is missing"})
                      (not (vector? (:steps plan)))
                      (conj {:code :missing-plan-steps :message "Plan steps are missing"}))
        validation-result
        (case dsl-type
          :document (validation/validate-document dsl)
          :patch (validation/validate-patch dsl)
          {:valid? false
           :errors [{:code :invalid-dsl-type
                     :message "dslType must be document or patch"}]})
        errors (vec (concat plan-errors
                            (:errors validation-result)
                            (boundary-errors request dsl-type dsl)))]
    {:valid? (empty? errors)
     :errors errors
     :warnings (:warnings validation-result)
     :dsl-type dsl-type
     :dsl dsl
     :plan plan}))

(defn- user-message
  [{:keys [prompt mode scope context]}]
  (let [context-json (-> (json/encode context :key-fn json/write-camel-key)
                         (truncate max-context-characters))]
    (str "User request:\n" (truncate (str prompt) max-prompt-characters)
         "\n\nRequested mode: " (name mode)
         "\nDeclared scope: " (name scope)
         "\n\nScoped Penpot canvas context:\n" context-json)))

(defn- request-once
  [provider-instance cfg request messages]
  (->> (assoc request :messages messages)
       (provider/generate-design! provider-instance cfg)
       parse-output
       (validate-output request)))

(defn generate-proposal!
  [provider-instance cfg request]
  (let [messages [{:role :system :content system-prompt}
                  {:role :user :content (user-message request)}]
        first-result (request-once provider-instance cfg request messages)]
    (if (:valid? first-result)
      (select-keys first-result [:valid? :plan :dsl-type :dsl :warnings])
      (let [repair-message
            (str "Your previous JSON did not pass validation. Return a corrected JSON object only.\n"
                 "Validation errors:\n"
                 (json/encode (:errors first-result) :key-fn json/write-camel-key)
                 "\nDo not broaden the scope, change baseRevision or change the user's intent.")
            previous-output
            (truncate
             (json/encode
              {:plan (:plan first-result)
               :dslType (some-> (:dsl-type first-result) name)
               :dsl (:dsl first-result)}
              :key-fn json/write-camel-key)
             max-invalid-output-characters)
            repaired
            (request-once provider-instance cfg request
                          (conj messages
                                {:role :assistant :content previous-output}
                                {:role :user :content repair-message}))]
        (if (:valid? repaired)
          (assoc (select-keys repaired [:valid? :plan :dsl-type :dsl :warnings])
                 :repaired? true)
          (ex/raise :type :validation
                    :code :invalid-ai-design-proposal
                    :hint "AI provider did not return a valid design proposal"
                    :errors (:errors repaired)))))))
