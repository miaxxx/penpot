;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.ai.harness.checks
  "Evidence-based verification. A Harness run cannot complete until every
  required check has a persisted passing result."
  (:require
   [app.ai.harness.sessions :as sessions]
   [app.ai.policy :as policy]
   [app.ai.proposals :as proposals]
   [app.common.ai.repository-harness :as rh]
   [app.common.ai.tools :as tools]
   [app.common.ai.validation :as validation]
   [app.common.exceptions :as ex]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]))

(def client-evidence-checks
  #{:proposal.preview-compiled
    :proposal.native-shapes-valid
    :proposal.undo-ready
    :registry.references-resolved})

(defn- decode-json
  [value]
  (if (db/pgobject? value)
    (db/decode-json-pgobject value)
    value))

(defn decode-result
  [row]
  (when row
    (-> row
        (update :status keyword)
        (update :check-id keyword)
        (update :evidence decode-json)
        (assoc :check-result-id (:id row))
        (dissoc :profile-id))))

(defn- run-owned!
  [cfg profile-id run-id]
  (let [row (db/get cfg :ai-harness-run {:id run-id :profile-id profile-id})]
    (when-not row
      (ex/raise :type :not-found :code :object-not-found :hint "not found"))
    (sessions/get-owned! cfg profile-id (:session-id row) :access :edit)
    (sessions/decode-run row)))

(defn list-definitions
  []
  {:version rh/version
   :checks rh/default-checks})

(defn list-results!
  [cfg profile-id run-id]
  (run-owned! cfg profile-id run-id)
  (mapv decode-result
        (db/exec!
         cfg
         ["SELECT * FROM ai_harness_check_result
            WHERE run_id = ? AND profile_id = ?
            ORDER BY created_at, check_id"
          run-id profile-id])))

(defn- proposal-for-run!
  [cfg profile-id run]
  (when-let [proposal-id (:proposal-id run)]
    (proposals/get! cfg profile-id proposal-id)))

(defn- server-evaluate
  [cfg profile-id run check-id evidence]
  (let [session (sessions/get-owned! cfg profile-id (:session-id run))
        proposal (delay (proposal-for-run! cfg profile-id run))]
    (case check-id
      :environment.feature-flag
      {:passed? (contains? cf/flags :ai-design-agent)
       :evidence {:flag :ai-design-agent}}

      :environment.file-revision
      (let [current (policy/current-revision cfg (:file-id session))
            expected (:base-revision session)]
        {:passed? (= (long expected) (long current))
         :evidence {:expected expected :current current}})

      :environment.workspace-bridge
      {:passed? (true? (:workspace-bridge? evidence))
       :evidence (select-keys evidence [:workspace-bridge? :snapshot-version])}

      :context.within-budget
      (let [report (:context-report run)
            used (or (:used report) (:used-characters report) 0)
            budget (or (:budget report) (:context-budget report) 0)]
        {:passed? (and (pos? budget) (<= used budget))
         :evidence {:used used :budget budget}})

      :proposal.dsl-valid
      (if-let [proposal @proposal]
        (let [result (case (:dsl-type proposal)
                       :document (validation/validate-document (:dsl proposal))
                       :patch (validation/validate-patch (:dsl proposal))
                       {:valid? false :errors [{:code :invalid-dsl-type}]})]
          {:passed? (:valid? result)
           :evidence {:dsl-type (:dsl-type proposal)
                      :errors (:errors result)}})
        {:passed? false :evidence {:reason :missing-proposal}})

      :proposal.scope-valid
      (if-let [proposal @proposal]
        {:passed? (= (keyword (get-in proposal [:scope :type]))
                     (keyword (get-in session [:scope :type])))
         :evidence {:proposal-scope (:scope proposal)
                    :session-scope (:scope session)}}
        {:passed? false :evidence {:reason :missing-proposal}})

      :mcp.policy-isolated
      (let [mcp-tools (tools/list-tools :mcp)
            commit-tools (filter #(= :canvas/commit (:capability %)) mcp-tools)]
        {:passed? (empty? commit-tools)
         :evidence {:mcp-tool-count (count mcp-tools)
                    :commit-tool-ids (mapv :id commit-tools)}})

      (if (contains? client-evidence-checks check-id)
        {:passed? (true? (:passed? evidence))
         :evidence (dissoc evidence :passed?)}
        {:passed? false
         :evidence {:reason :unsupported-check}}))))

(defn- upsert-result!
  [cfg profile-id run-id check-id required? status evidence duration-ms]
  (-> (db/exec-one!
       cfg
       ["INSERT INTO ai_harness_check_result
           (id, run_id, profile_id, check_id, status, required, evidence,
            duration_ms, completed_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp())
         ON CONFLICT (run_id, check_id)
         DO UPDATE SET
           status = EXCLUDED.status,
           required = EXCLUDED.required,
           evidence = EXCLUDED.evidence,
           duration_ms = EXCLUDED.duration_ms,
           completed_at = clock_timestamp()
         RETURNING *"
        (uuid/next) run-id profile-id (name check-id) (name status)
        (boolean required?) (db/json evidence) duration-ms])
      decode-result))

(defn run-check!
  [cfg profile-id run-id
   {:keys [check-id evidence transport] :or {evidence {} transport :internal}}]
  (let [run (run-owned! cfg profile-id run-id)
        check-id (rh/normalize-check-id check-id)
        definition (rh/check-definition check-id)]
    (when-not definition
      (ex/raise :type :validation
                :code :unknown-ai-harness-check
                :hint "Harness check is not registered"))
    (when (and (contains? client-evidence-checks check-id)
               (not= :internal (keyword transport)))
      (ex/raise :type :restriction
                :code :untrusted-ai-harness-evidence
                :hint "Native compiler evidence must come from the internal Penpot workspace"))
    (let [started (System/nanoTime)
          evaluated (server-evaluate cfg profile-id run check-id evidence)
          status (if (:passed? evaluated) :passed :failed)
          duration-ms (long (/ (- (System/nanoTime) started) 1000000))]
      (upsert-result! cfg profile-id run-id check-id (:required definition)
                      status (:evidence evaluated) duration-ms))))

(defn run-core-checks!
  [cfg profile-id run-id evidence]
  (mapv
   (fn [{:keys [id]}]
     (run-check! cfg profile-id run-id
                 {:check-id id
                  :evidence (get evidence id {})
                  :transport :internal}))
   rh/default-checks))

(defn completion-report!
  [cfg profile-id run-id]
  (rh/completion-report rh/default-checks
                        (list-results! cfg profile-id run-id)))
