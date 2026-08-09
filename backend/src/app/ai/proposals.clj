;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.ai.proposals
  "Persistent lifecycle service shared by internal AI, RPC, MCP and plugins."
  (:require
   [app.ai.policy :as policy]
   [app.common.ai.validation :as validation]
   [app.common.exceptions :as ex]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.db :as db]))

(def active-statuses #{:validated :previewed :applying})
(def terminal-statuses #{:applied :discarded :conflicted :expired})

(def allowed-transitions
  {:validated #{:previewed :discarded :expired}
   :previewed #{:previewed :applying :discarded :conflicted :expired}
   :applying #{:applied :conflicted :expired}
   :conflicted #{:discarded}
   :applied #{}
   :discarded #{}
   :expired #{}})

(def ^:private json-columns
  [:scope :plan :dsl :preview :error])

(defn- decode-json
  [value]
  (if (db/pgobject? value)
    (db/decode-json-pgobject value)
    value))

(defn decode-row
  [row]
  (when row
    (-> (reduce (fn [row key]
                  (update row key decode-json))
                row
                json-columns)
        (update :status keyword)
        (update :origin keyword)
        (update :mode keyword)
        (update :dsl-type keyword))))

(defn public-view
  [proposal]
  (when proposal
    (-> proposal
        (assoc :proposal-id (:id proposal)
               :requires-confirmation (not (contains? terminal-statuses (:status proposal))))
        (dissoc :id :profile-id :apply-token))))

(defn- expired?
  [{:keys [expires-at status]}]
  (and (contains? active-statuses status)
       expires-at
       (neg? (compare expires-at (ct/now)))))

(defn- get-row!
  [cfg id]
  (-> (db/get cfg :ai-design-proposal {:id id})
      decode-row))

(defn- expire-row!
  [cfg proposal]
  (if (expired? proposal)
    (-> (db/update! cfg :ai-design-proposal
                    {:status "expired"
                     :modified-at (ct/now)}
                    {:id (:id proposal)
                     :status (name (:status proposal))}
                    {::db/return-keys true})
        decode-row)
    proposal))

(defn get-owned!
  [cfg profile-id id & {:keys [access] :or {access :read}}]
  (policy/ensure-enabled!)
  (let [proposal (-> (get-row! cfg id)
                     (policy/ensure-owner! profile-id)
                     (expire-row! cfg))]
    (case access
      :edit (policy/ensure-edit! cfg profile-id (:file-id proposal))
      (policy/ensure-read! cfg profile-id (:file-id proposal)))
    proposal))

(defn- validate-dsl!
  [dsl-type dsl]
  (let [result (case dsl-type
                 :document (validation/validate-document dsl)
                 :patch (validation/validate-patch dsl)
                 {:valid? false
                  :errors [{:code :invalid-dsl-type
                            :message "dsl type must be document or patch"}]})]
    (when-not (:valid? result)
      (ex/raise :type :validation
                :code :invalid-ai-design-proposal
                :hint "proposal DSL did not pass validation"
                :errors (:errors result)))
    result))

(defn- scope-type
  [scope]
  (some-> (or (:type scope) (get scope "type")) keyword))

(defn- scope-root
  [scope]
  (or (:root-id scope) (:rootId scope)
      (get scope "root-id") (get scope "rootId")))

(defn- ensure-patch-boundary!
  [base-revision scope dsl]
  (let [dsl-revision (or (:baseRevision dsl) (:base-revision dsl))
        dsl-scope (:scope dsl)]
    (when-not (= (long base-revision) (long dsl-revision))
      (ex/raise :type :validation
                :code :base-revision-mismatch
                :hint "Patch DSL base revision differs from proposal revision"))
    (when-not (= (scope-type scope) (scope-type dsl-scope))
      (ex/raise :type :validation
                :code :scope-escalation
                :hint "Patch DSL scope differs from the requested scope"))
    (when (and (contains? #{:selection :component} (scope-type scope))
               (not= (str (scope-root scope)) (str (scope-root dsl-scope))))
      (ex/raise :type :validation
                :code :scope-root-mismatch
                :hint "Patch DSL root differs from the requested scope"))))

(defn create!
  [cfg {:keys [profile-id file-id page-id origin mode dsl-type
               base-revision scope plan dsl]}]
  (policy/ensure-enabled!)
  (policy/ensure-edit! cfg profile-id file-id)
  (policy/ensure-revision! cfg file-id base-revision)
  (let [origin (policy/ensure-origin! origin)
        scope (policy/ensure-scope! scope)
        dsl-type (keyword dsl-type)
        _ (validate-dsl! dsl-type dsl)
        _ (when (= dsl-type :patch)
            (ensure-patch-boundary! base-revision scope dsl))
        row (db/insert! cfg :ai-design-proposal
                        {:id (uuid/next)
                         :profile-id profile-id
                         :file-id file-id
                         :page-id page-id
                         :origin (name origin)
                         :mode (name (keyword mode))
                         :dsl-type (name dsl-type)
                         :status "validated"
                         :base-revision base-revision
                         :scope (db/json scope)
                         :plan (db/json (or plan {}))
                         :dsl (db/json dsl)})]
    (-> row decode-row public-view)))

(defn- transition-row!
  [cfg profile-id id target updates]
  (db/tx-run!
   cfg
   (fn [{:keys [::db/conn] :as tx}]
     (db/xact-lock! conn id)
     (let [proposal (get-owned! tx profile-id id :access :edit)
           current (:status proposal)]
       (when-not (contains? (get allowed-transitions current #{}) target)
         (ex/raise :type :validation
                   :code :invalid-ai-proposal-transition
                   :hint "proposal status transition is not allowed"
                   :current current
                   :target target))
       (-> (db/update! conn :ai-design-proposal
                       (merge {:status (name target)
                               :modified-at (ct/now)}
                              updates)
                       {:id id
                        :profile-id profile-id
                        :status (name current)}
                       {::db/return-keys true})
           decode-row)))))

(defn get!
  [cfg profile-id id]
  (-> (get-owned! cfg profile-id id) public-view))

(defn mark-previewed!
  [cfg profile-id id preview]
  (let [proposal (get-owned! cfg profile-id id :access :edit)]
    (policy/ensure-revision! cfg (:file-id proposal) (:base-revision proposal))
    (-> (transition-row! cfg profile-id id :previewed
                         {:preview (db/json preview)
                          :error nil})
        public-view)))

(defn discard!
  [cfg profile-id id]
  (-> (transition-row! cfg profile-id id :discarded
                       {:discarded-at (ct/now)
                        :apply-token nil})
      public-view))

(defn request-apply!
  [cfg profile-id id]
  (let [proposal (get-owned! cfg profile-id id :access :edit)]
    (when-not (= :previewed (:status proposal))
      (ex/raise :type :validation
                :code :proposal-preview-required
                :hint "proposal must be previewed in Penpot before apply"))
    (assoc (public-view proposal)
           :requires-ui-confirmation true
           :status :previewed)))

(defn begin-apply!
  [cfg profile-id id]
  (let [proposal (get-owned! cfg profile-id id :access :edit)
        _ (policy/ensure-revision! cfg (:file-id proposal) (:base-revision proposal))
        token (uuid/next)
        updated (transition-row! cfg profile-id id :applying
                                 {:apply-token token
                                  :apply-started-at (ct/now)
                                  :error nil})]
    {:proposal-id (:id updated)
     :status (:status updated)
     :apply-token token
     :base-revision (:base-revision updated)
     :file-id (:file-id updated)
     :page-id (:page-id updated)}))

(defn- get-applying-with-token!
  [cfg profile-id id apply-token]
  (let [proposal (get-owned! cfg profile-id id :access :edit)]
    (when-not (and (= :applying (:status proposal))
                   (= apply-token (:apply-token proposal)))
      (ex/raise :type :validation
                :code :invalid-ai-apply-token
                :hint "proposal is not locked by this native transaction"))
    proposal))

(defn complete-apply!
  [cfg profile-id id apply-token transaction-id]
  (get-applying-with-token! cfg profile-id id apply-token)
  (-> (transition-row! cfg profile-id id :applied
                       {:applied-at (ct/now)
                        :apply-token nil
                        :error nil
                        :preview (db/json {:transaction-id transaction-id})})
      public-view))

(defn conflict!
  [cfg profile-id id apply-token error]
  (get-applying-with-token! cfg profile-id id apply-token)
  (-> (transition-row! cfg profile-id id :conflicted
                       {:apply-token nil
                        :error (db/json error)})
      public-view))
