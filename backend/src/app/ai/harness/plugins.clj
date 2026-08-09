;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.ai.harness.plugins
  "Declarative Harness plugins.

  Plugins may contribute skills, prompt hooks, commands and remote MCP tool
  descriptors. They cannot load JVM/JavaScript code or bypass the Capability
  Registry."
  (:require
   [app.ai.harness.archive :as archive]
   [app.common.ai.harness :as harness]
   [app.common.ai.tools :as tools]
   [app.common.exceptions :as ex]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [clojure.string :as str]))

(def allowed-contribution-keys
  #{:skills :commands :hooks :mcp-tools :personas})

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
        (update :contributions decode-json)
        (assoc :plugin-id (str (:id row)))
        (dissoc :profile-id))))

(defn- contribution-map
  [manifest]
  (or (:contributions manifest)
      (get manifest "contributions")
      {}))

(defn- normalize-contributions
  [value]
  (let [value (if (map? value) value {})
        normalized
        (reduce-kv
         (fn [result key item]
           (assoc result (keyword key) item))
         {}
         value)]
    (select-keys normalized allowed-contribution-keys)))

(defn- ensure-tool-references!
  [contributions]
  (let [descriptors (:mcp-tools contributions)
        mcp-referenced
        (keep (fn [descriptor]
                (or (:tool-id descriptor)
                    (:toolId descriptor)
                    (get descriptor "toolId")))
              descriptors)
        skill-referenced
        (mapcat
         (fn [skill]
           (or (:tools skill) (get skill "tools") []))
         (:skills contributions))
        referenced (concat mcp-referenced skill-referenced)
        unknown (remove tools/tool-exists? referenced)]
    (when (seq unknown)
      (ex/raise :type :validation
                :code :unknown-ai-plugin-tools
                :hint "plugin references tools that are not registered"
                :tools (vec unknown))))
  contributions)

(defn- normalize-manifest
  [manifest]
  (let [name (str/trim
              (str (or (:name manifest)
                       (get manifest "name")
                       "Harness Plugin")))
        slug (harness/slugify
              (or (:slug manifest) (get manifest "slug") name))
        version (str (or (:version manifest)
                         (get manifest "version")
                         harness/harness-version))
        description (str (or (:description manifest)
                             (get manifest "description")
                             ""))
        contributions (-> manifest
                          contribution-map
                          normalize-contributions
                          ensure-tool-references!)]
    (when (str/blank? name)
      (ex/raise :type :validation
                :code :invalid-ai-plugin-name
                :hint "plugin name is required"))
    {:manifest {:name name
                :slug slug
                :version version
                :description description}
     :contributions contributions}))

(def sql:upsert-plugin
  "INSERT INTO ai_harness_plugin
     (id, profile_id, name, slug, version, description, enabled,
      manifest, contributions, content_hash, created_at, modified_at)
   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
   ON CONFLICT (profile_id, slug)
   DO UPDATE SET
      name = EXCLUDED.name,
      version = EXCLUDED.version,
      description = EXCLUDED.description,
      enabled = EXCLUDED.enabled,
      manifest = EXCLUDED.manifest,
      contributions = EXCLUDED.contributions,
      content_hash = EXCLUDED.content_hash,
      modified_at = EXCLUDED.modified_at
   RETURNING *")

(defn install!
  [cfg profile-id {:keys [enabled] :as payload}]
  (let [{raw-manifest :manifest
         content-hash :content-hash}
        (archive/parse-package payload)
        {:keys [manifest contributions]}
        (normalize-manifest raw-manifest)
        now (ct/now)
        row (db/exec-one!
             cfg
             [sql:upsert-plugin
              (uuid/next)
              profile-id
              (:name manifest)
              (:slug manifest)
              (:version manifest)
              (:description manifest)
              (not= false enabled)
              (db/json manifest)
              (db/json contributions)
              content-hash
              now
              now])]
    (decode-row row)))

(defn list!
  [cfg profile-id]
  (->> (db/exec!
        cfg
        ["SELECT *
            FROM ai_harness_plugin
           WHERE profile_id = ?
           ORDER BY enabled DESC, modified_at DESC"
         profile-id])
       (mapv decode-row)))

(defn- parse-id!
  [id]
  (or (uuid/parse* (str id))
      (ex/raise :type :validation
                :code :invalid-ai-plugin-id
                :hint "plugin id is invalid")))

(defn set-enabled!
  [cfg profile-id id enabled]
  (let [id (parse-id! id)
        row (db/update! cfg :ai-harness-plugin
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
  (let [id (parse-id! id)
        result (db/delete! cfg :ai-harness-plugin
                           {:id id :profile-id profile-id})]
    {:deleted (= 1 (db/get-update-count result))
     :plugin-id (str id)}))

(defn active-contributions
  [cfg profile-id]
  (->> (list! cfg profile-id)
       (filter :enabled)
       (map :contributions)
       (reduce
        (fn [result contributions]
          (merge-with
           (fn [left right]
             (cond
               (and (vector? left) (vector? right)) (into left right)
               (and (map? left) (map? right)) (merge left right)
               :else right))
           result
           contributions))
        {})))

(defn prompt-hooks
  [cfg profile-id hook]
  (let [hooks (:hooks (active-contributions cfg profile-id))]
    (->> hooks
         (keep (fn [item]
                 (when (= (keyword hook)
                          (keyword (or (:event item)
                                       (get item "event"))))
                   (or (:prompt item)
                       (get item "prompt")))))
         (remove str/blank?)
         vec)))

(defn contributed-skills
  [cfg profile-id]
  (->> (list! cfg profile-id)
       (filter :enabled)
       (mapcat
        (fn [plugin]
          (let [plugin-slug (:slug plugin)]
            (map
             (fn [skill]
               (let [manifest
                     (harness/normalize-skill-manifest skill)]
                 (assoc manifest
                        :skill-id
                        (str "plugin:" plugin-slug ":" (:slug manifest))
                        :source :plugin
                        :enabled true
                        :instructions
                        (str (or (:instructions skill)
                                 (get skill "instructions")
                                 (:prompt skill)
                                 (get skill "prompt")
                                 "")))))
             (get-in plugin [:contributions :skills] [])))))
       (remove #(str/blank? (:instructions %)))
       vec))

(defn contributed-commands
  [cfg profile-id]
  (->> (list! cfg profile-id)
       (filter :enabled)
       (mapcat
        (fn [plugin]
          (map
           (fn [command]
             {:plugin-id (:plugin-id plugin)
              :plugin (:name plugin)
              :name
              (harness/slugify
               (or (:name command) (get command "name")))
              :description
              (str (or (:description command)
                       (get command "description")
                       "Plugin command"))
              :prompt
              (str (or (:prompt command)
                       (get command "prompt")
                       ""))})
           (get-in plugin [:contributions :commands] []))))
       (remove #(or (str/blank? (:name %))
                    (str/blank? (:prompt %))))
       vec))

(defn resolve-command
  [cfg profile-id name arguments]
  (when-let [command
             (some #(when (= (str/lower-case (str name))
                             (str/lower-case (:name %)))
                      %)
                   (contributed-commands cfg profile-id))]
    (assoc command
           :expanded-prompt
           (str/replace (:prompt command)
                        "{{args}}"
                        (str arguments)))))
