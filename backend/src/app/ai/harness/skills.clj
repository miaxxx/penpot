;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.ai.harness.skills
  "Persistent, bounded and declarative skill packages for the Penpot AI Harness."
  (:require
   [app.ai.harness.archive :as archive]
   [app.common.ai.harness :as harness]
   [app.common.ai.tools :as tools]
   [app.common.exceptions :as ex]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [clojure.set :as set]
   [clojure.string :as str]))

(defn- decode-json
  [value]
  (if (db/pgobject? value)
    (db/decode-json-pgobject value)
    value))

(defn- decode-row
  [row]
  (when row
    (-> row
        (update :manifest decode-json)
        (update :files decode-json)
        (update :source keyword)
        (assoc :skill-id (str (:id row)))
        (dissoc :profile-id))))

(defn- builtin-public
  [skill]
  (assoc skill
         :source :builtin
         :enabled true
         :manifest
         (select-keys skill
                      [:name :slug :version :description :keywords
                       :tools :capabilities])))

(defn- unknown-tools
  [manifest]
  (->> (:tools manifest)
       (remove tools/tool-exists?)
       vec))

(defn- ensure-manifest!
  [manifest]
  (when (str/blank? (:name manifest))
    (ex/raise :type :validation
              :code :invalid-ai-skill-name
              :hint "skill name is required"))
  (when (> (count (:name manifest)) 160)
    (ex/raise :type :validation
              :code :invalid-ai-skill-name
              :hint "skill name is too long"))
  (when-let [unknown (seq (unknown-tools manifest))]
    (ex/raise :type :validation
              :code :unknown-ai-skill-tools
              :hint "skill requests tools that are not registered"
              :tools (vec unknown)))
  manifest)

(def sql:upsert-skill
  "INSERT INTO ai_harness_skill
     (id, profile_id, name, slug, version, description, source, enabled,
      manifest, files, instructions, content_hash, created_at, modified_at)
   VALUES (?, ?, ?, ?, ?, ?, 'upload', ?, ?, ?, ?, ?, ?, ?)
   ON CONFLICT (profile_id, slug)
   DO UPDATE SET
      name = EXCLUDED.name,
      version = EXCLUDED.version,
      description = EXCLUDED.description,
      enabled = EXCLUDED.enabled,
      manifest = EXCLUDED.manifest,
      files = EXCLUDED.files,
      instructions = EXCLUDED.instructions,
      content_hash = EXCLUDED.content_hash,
      modified_at = EXCLUDED.modified_at
   RETURNING *")

(defn install!
  [cfg profile-id {:keys [enabled] :as payload}]
  (let [{:keys [manifest files instructions content-hash]}
        (archive/parse-package payload)
        manifest (-> manifest
                     harness/normalize-skill-manifest
                     ensure-manifest!)
        _ (when (str/blank? instructions)
            (ex/raise :type :validation
                      :code :missing-ai-skill-instructions
                      :hint "skill package must contain instructions"))
        now (ct/now)
        row (db/exec-one!
             cfg
             [sql:upsert-skill
              (uuid/next)
              profile-id
              (:name manifest)
              (:slug manifest)
              (:version manifest)
              (:description manifest)
              (not= false enabled)
              (db/json manifest)
              (db/json files)
              instructions
              content-hash
              now
              now])]
    (decode-row row)))

(defn- list-uploaded-raw!
  [cfg profile-id]
  (->> (db/exec!
        cfg
        ["SELECT *
            FROM ai_harness_skill
           WHERE profile_id = ?
           ORDER BY enabled DESC, modified_at DESC"
         profile-id])
       (mapv decode-row)))

(defn list-uploaded!
  [cfg profile-id]
  (mapv #(dissoc % :files :instructions)
        (list-uploaded-raw! cfg profile-id)))

(defn list!
  [cfg profile-id]
  (vec (concat (map builtin-public harness/builtin-skills)
               (list-uploaded! cfg profile-id))))

(defn- list-internal!
  [cfg profile-id]
  (vec (concat (map builtin-public harness/builtin-skills)
               (list-uploaded-raw! cfg profile-id))))

(defn- builtin-by-id
  [id]
  (some #(when (= id (:skill-id %)) (builtin-public %))
        harness/builtin-skills))

(defn- parse-upload-id!
  [id]
  (or (uuid/parse* (str id))
      (ex/raise :type :validation
                :code :invalid-ai-skill-id
                :hint "skill id is invalid")))

(defn get!
  [cfg profile-id id]
  (or (builtin-by-id (str id))
      (some-> (db/get cfg :ai-harness-skill
                      {:id (parse-upload-id! id)
                       :profile-id profile-id})
              decode-row)
      (ex/raise :type :not-found
                :code :object-not-found
                :hint "not found")))

(defn set-enabled!
  [cfg profile-id id enabled]
  (when (str/starts-with? (str id) "builtin:")
    (ex/raise :type :restriction
              :code :builtin-ai-skill-immutable
              :hint "built-in skills cannot be disabled globally"))
  (let [id (parse-upload-id! id)
        row (db/update! cfg :ai-harness-skill
                        {:enabled (boolean enabled)
                         :modified-at (ct/now)}
                        {:id id :profile-id profile-id}
                        {::db/return-keys true})]
    (or (some-> row decode-row)
        (ex/raise :type :not-found
                  :code :object-not-found
                  :hint "not found"))))

(defn delete!
  [cfg profile-id id]
  (when (str/starts-with? (str id) "builtin:")
    (ex/raise :type :restriction
              :code :builtin-ai-skill-immutable
              :hint "built-in skills cannot be deleted"))
  (let [id (parse-upload-id! id)
        result (db/delete! cfg :ai-harness-skill
                           {:id id :profile-id profile-id})]
    {:deleted (= 1 (db/get-update-count result))
     :skill-id (str id)}))

(defn- words
  [value]
  (->> (str/lower-case (str value))
       (re-seq #"[\p{L}\p{N}_-]{2,}")
       set))

(defn- score-skill
  [prompt skill]
  (let [prompt-words (words prompt)
        skill-words (set/union
                     (words (:name skill))
                     (words (:description skill))
                     (set (mapcat words (:keywords skill))))]
    (count (set/intersection prompt-words skill-words))))

(defn- selected-by-id
  [all-skills selected-ids]
  (let [selected (set (map str selected-ids))]
    (filterv #(contains? selected (str (:skill-id %))) all-skills)))

(defn select!
  ([cfg profile-id prompt selected-ids]
   (select! cfg profile-id prompt selected-ids []))
  ([cfg profile-id prompt selected-ids additional-skills]
   (let [all-skills
         (->> (concat (list-internal! cfg profile-id)
                      additional-skills)
              (filter :enabled)
              vec)
         explicit (selected-by-id all-skills selected-ids)]
     (if (seq explicit)
       explicit
       (->> all-skills
            (map #(assoc % ::score (score-skill prompt %)))
            (filter #(or (pos? (::score %))
                         (get-in % [:manifest :auto-activate])
                         (:auto-activate %)))
            (sort-by (juxt (comp - ::score) :name))
            (take 4)
            (mapv #(dissoc % ::score)))))))

(defn prompt-context
  [skills]
  (mapv
   (fn [skill]
     {:skill-id (:skill-id skill)
      :name (:name skill)
      :version (:version skill)
      :description (:description skill)
      :tools (:tools skill)
      :capabilities (:capabilities skill)
      :instructions (:instructions skill)})
   skills))
