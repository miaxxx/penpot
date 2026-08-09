;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.ai.harness.sessions
  "Persistent Harness sessions and execution traces."
  (:require
   [app.ai.policy :as policy]
   [app.common.ai.harness :as harness]
   [app.common.exceptions :as ex]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.db :as db]))

(def session-json-columns [:scope :settings])
(def run-json-columns
  [:selected-skills :coordinator-plan :context-report :trace :result])

(defn- decode-json
  [value]
  (if (db/pgobject? value)
    (db/decode-json-pgobject value)
    value))

(defn- decode-row
  [row columns]
  (when row
    (reduce (fn [row key] (update row key decode-json))
            row
            columns)))

(defn decode-session
  [row]
  (when-let [row (decode-row row session-json-columns)]
    (-> row
        (update :transport keyword)
        (update :input-mode keyword)
        (update :persona keyword)
        (update :mode keyword)
        (update :status keyword)
        (assoc :session-id (:id row))
        (dissoc :profile-id))))

(defn decode-run
  [row]
  (when-let [row (decode-row row run-json-columns)]
    (-> row
        (update :input-mode keyword)
        (update :status keyword)
        (assoc :run-id (:id row))
        (dissoc :profile-id))))

(defn- expired?
  [session]
  (and (contains? #{:active :paused} (:status session))
       (:expires-at session)
       (neg? (compare (:expires-at session) (ct/now)))))

(defn- raw-session!
  [cfg id]
  (some-> (db/get cfg :ai-harness-session {:id id})
          (decode-row session-json-columns)
          (update :transport keyword)
          (update :input-mode keyword)
          (update :persona keyword)
          (update :mode keyword)
          (update :status keyword)))

(defn get-owned!
  [cfg profile-id id & {:keys [access] :or {access :read}}]
  (let [session (raw-session! cfg id)]
    (when-not (and session (= profile-id (:profile-id session)))
      (ex/raise :type :not-found
                :code :object-not-found
                :hint "not found"))
    (case access
      :edit (policy/ensure-edit! cfg profile-id (:file-id session))
      (policy/ensure-read! cfg profile-id (:file-id session)))
    (if (expired? session)
      (let [row (db/update! cfg :ai-harness-session
                            {:status "expired"
                             :modified-at (ct/now)}
                            {:id id
                             :profile-id profile-id}
                            {::db/return-keys true})]
        (-> row
            (decode-row session-json-columns)
            (update :transport keyword)
            (update :input-mode keyword)
            (update :persona keyword)
            (update :mode keyword)
            (update :status keyword)))
      session)))

(defn create!
  [cfg profile-id {:keys [file-id page-id base-revision scope mode
                          transport input-mode persona settings]}]
  (policy/ensure-enabled!)
  (policy/ensure-edit! cfg profile-id file-id)
  (policy/ensure-revision! cfg file-id base-revision)
  (let [scope (policy/ensure-scope! scope)
        transport (keyword (or transport :internal))
        input-mode (keyword (or input-mode :assistant))
        persona (keyword (or persona :assistant))
        mode (policy/ensure-mode! mode)]
    (when-not (harness/valid-transport? transport)
      (ex/raise :type :validation
                :code :invalid-ai-harness-transport
                :hint "invalid Harness transport"))
    (when-not (harness/valid-input-mode? input-mode)
      (ex/raise :type :validation
                :code :invalid-ai-harness-input-mode
                :hint "invalid Harness input mode"))
    (when-not (contains? harness/valid-personas persona)
      (ex/raise :type :validation
                :code :invalid-ai-harness-persona
                :hint "persona must be assistant or buddy"))
    (-> (db/insert! cfg :ai-harness-session
                    {:id (uuid/next)
                     :profile-id profile-id
                     :file-id file-id
                     :page-id page-id
                     :transport (name transport)
                     :input-mode (name input-mode)
                     :persona (name persona)
                     :mode (name mode)
                     :status "active"
                     :base-revision base-revision
                     :scope (db/json scope)
                     :settings
                     (db/json
                      (merge
                       {:context-budget harness/default-context-budget
                        :coordinator false
                        :auto-skills true}
                       settings))})
        decode-session)))

(defn get!
  [cfg profile-id id]
  (decode-session (get-owned! cfg profile-id id)))

(defn list!
  [cfg profile-id file-id page-id]
  (policy/ensure-read! cfg profile-id file-id)
  (let [sql
        (if page-id
          ["SELECT *
              FROM ai_harness_session
             WHERE profile_id = ?
               AND file_id = ?
               AND page_id = ?
               AND status IN ('active', 'paused')
             ORDER BY modified_at DESC
             LIMIT 20"
           profile-id file-id page-id]
          ["SELECT *
              FROM ai_harness_session
             WHERE profile_id = ?
               AND file_id = ?
               AND status IN ('active', 'paused')
             ORDER BY modified_at DESC
             LIMIT 20"
           profile-id file-id])]
    (mapv decode-session (db/exec! cfg sql))))

(defn update-settings!
  [cfg profile-id id settings]
  (let [session (get-owned! cfg profile-id id :access :edit)
        merged (merge (:settings session) settings)
        row (db/update! cfg :ai-harness-session
                        {:settings (db/json merged)
                         :modified-at (ct/now)}
                        {:id id :profile-id profile-id}
                        {::db/return-keys true})]
    (decode-session row)))

(defn close!
  [cfg profile-id id]
  (get-owned! cfg profile-id id :access :edit)
  (-> (db/update! cfg :ai-harness-session
                  {:status "closed"
                   :modified-at (ct/now)}
                  {:id id :profile-id profile-id}
                  {::db/return-keys true})
      decode-session))

(defn create-run!
  [cfg profile-id session-id {:keys [input-mode input-text command
                                     selected-skills coordinator-plan
                                     context-report trace]}]
  (get-owned! cfg profile-id session-id :access :edit)
  (-> (db/insert! cfg :ai-harness-run
                  {:id (uuid/next)
                   :session-id session-id
                   :profile-id profile-id
                   :input-mode (name (keyword input-mode))
                   :input-text (str input-text)
                   :command command
                   :selected-skills (db/json (or selected-skills []))
                   :coordinator-plan
                   (when coordinator-plan (db/json coordinator-plan))
                   :context-report (db/json (or context-report {}))
                   :trace (db/json (or trace []))
                   :status "running"})
      decode-run))

(defn complete-run!
  [cfg profile-id run-id {:keys [status proposal-id result trace
                                 coordinator-plan context-report]}]
  (let [row (db/get cfg :ai-harness-run
                    {:id run-id :profile-id profile-id})]
    (when-not row
      (ex/raise :type :not-found
                :code :object-not-found
                :hint "not found"))
    (let [updates
          (cond-> {:status (name (keyword status))
                   :modified-at (ct/now)
                   :completed-at (ct/now)}
            proposal-id (assoc :proposal-id proposal-id)
            result (assoc :result (db/json result))
            trace (assoc :trace (db/json trace))
            coordinator-plan
            (assoc :coordinator-plan (db/json coordinator-plan))
            context-report
            (assoc :context-report (db/json context-report)))]
      (-> (db/update! cfg :ai-harness-run
                      updates
                      {:id run-id :profile-id profile-id}
                      {::db/return-keys true})
          decode-run))))

(defn recent-runs
  [cfg profile-id session-id limit]
  (get-owned! cfg profile-id session-id)
  (->> (db/exec!
        cfg
        ["SELECT *
            FROM ai_harness_run
           WHERE session_id = ?
             AND profile_id = ?
           ORDER BY created_at DESC
           LIMIT ?"
         session-id profile-id (long (min 20 (max 1 (or limit 8))))])
       (mapv decode-run)
       reverse
       vec))

(defn latest-proposal-id
  [cfg profile-id session-id]
  (get-owned! cfg profile-id session-id)
  (:proposal-id
   (db/exec-one!
    cfg
    ["SELECT proposal_id
        FROM ai_harness_run
       WHERE session_id = ?
         AND profile_id = ?
         AND proposal_id IS NOT NULL
       ORDER BY created_at DESC
       LIMIT 1"
     session-id profile-id])))

(defn set-summary!
  [cfg profile-id session-id summary]
  (get-owned! cfg profile-id session-id :access :edit)
  (-> (db/update! cfg :ai-harness-session
                  {:summary (str summary)
                   :modified-at (ct/now)}
                  {:id session-id :profile-id profile-id}
                  {::db/return-keys true})
      decode-session))
