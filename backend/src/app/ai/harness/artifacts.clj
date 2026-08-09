;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.ai.harness.artifacts
  "Persistent repository-style Harness workspaces and artifacts."
  (:require
   [app.ai.policy :as policy]
   [app.common.ai.repository-harness :as rh]
   [app.common.exceptions :as ex]
   [app.common.uuid :as uuid]
   [app.db :as db])
  (:import
   java.nio.charset.StandardCharsets
   java.security.MessageDigest))

(def max-artifacts 128)
(def max-artifact-bytes (* 512 1024))
(def max-package-bytes (* 4 1024 1024))

(defn- decode-json
  [value]
  (if (db/pgobject? value)
    (db/decode-json-pgobject value)
    value))

(defn decode-workspace
  [row]
  (when row
    (-> row
        (update :settings decode-json)
        (update :status keyword)
        (assoc :workspace-id (:id row))
        (dissoc :profile-id))))

(defn decode-artifact
  [row]
  (when row
    (-> row
        (update :metadata decode-json)
        (update :kind keyword)
        (assoc :artifact-id (:id row))
        (dissoc :profile-id))))

(defn- sha256
  [content]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes (str content) StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) digest))))

(defn- content-bytes
  [content]
  (alength (.getBytes (str content) StandardCharsets/UTF_8)))

(defn- raw-workspace
  [cfg profile-id file-id page-id]
  (db/exec-one!
   cfg
   (if page-id
     ["SELECT * FROM ai_harness_workspace
        WHERE profile_id = ? AND file_id = ? AND page_id = ? AND status = 'active'
        LIMIT 1"
      profile-id file-id page-id]
     ["SELECT * FROM ai_harness_workspace
        WHERE profile_id = ? AND file_id = ? AND page_id IS NULL AND status = 'active'
        LIMIT 1"
      profile-id file-id])))

(defn get-owned!
  [cfg profile-id workspace-id & {:keys [access] :or {access :read}}]
  (let [row (db/get cfg :ai-harness-workspace {:id workspace-id})]
    (when-not (and row (= profile-id (:profile-id row)))
      (ex/raise :type :not-found :code :object-not-found :hint "not found"))
    (case access
      :edit (policy/ensure-edit! cfg profile-id (:file-id row))
      (policy/ensure-read! cfg profile-id (:file-id row)))
    row))

(defn- validate-artifact!
  [{:keys [path kind content]}]
  (when-not (rh/safe-artifact-path? path)
    (ex/raise :type :validation
              :code :invalid-ai-harness-artifact-path
              :hint "Harness artifact path is invalid"))
  (let [kind (keyword (or kind (rh/infer-artifact-kind path)))]
    (when-not (contains? rh/artifact-kinds kind)
      (ex/raise :type :validation
                :code :invalid-ai-harness-artifact-kind
                :hint "Harness artifact kind is invalid")))
  (when (> (content-bytes content) max-artifact-bytes)
    (ex/raise :type :validation
              :code :ai-harness-artifact-too-large
              :hint "Harness artifact exceeds the size limit"))
  true)

(defn upsert-artifact!
  [cfg profile-id workspace-id artifact]
  (let [workspace (get-owned! cfg profile-id workspace-id :access :edit)
        path (:path artifact)
        kind (keyword (or (:kind artifact) (rh/infer-artifact-kind path)))
        content (str (or (:content artifact) ""))]
    (validate-artifact! (assoc artifact :kind kind :content content))
    (-> (db/exec-one!
         cfg
         ["INSERT INTO ai_harness_artifact
             (id, workspace_id, profile_id, path, kind, content_type, content,
              metadata, required, read_order, content_hash)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
           ON CONFLICT (workspace_id, path)
           DO UPDATE SET
             kind = EXCLUDED.kind,
             content_type = EXCLUDED.content_type,
             content = EXCLUDED.content,
             metadata = EXCLUDED.metadata,
             required = EXCLUDED.required,
             read_order = EXCLUDED.read_order,
             content_hash = EXCLUDED.content_hash,
             modified_at = clock_timestamp()
           RETURNING *"
          (uuid/next) workspace-id profile-id path (name kind)
          (or (:content-type artifact) "text/markdown") content
          (db/json (or (:metadata artifact) {}))
          (boolean (or (:required artifact) (rh/required-artifact? path)))
          (long (or (:read-order artifact) 100))
          (sha256 content)])
        decode-artifact
        (assoc :file-id (:file-id workspace)))))

(defn- seed-defaults!
  [cfg profile-id workspace-id]
  (doseq [artifact (rh/default-artifacts)]
    (upsert-artifact! cfg profile-id workspace-id artifact)))

(defn- insert-workspace!
  [cfg profile-id file-id page-id name settings]
  (db/exec-one!
   cfg
   ["INSERT INTO ai_harness_workspace
       (id, profile_id, file_id, page_id, name, version, status, settings)
     VALUES (?, ?, ?, ?, ?, ?, 'active', ?)
     ON CONFLICT DO NOTHING
     RETURNING *"
    (uuid/next) profile-id file-id page-id
    (or name "Penpot AI Harness") rh/version
    (db/json (or settings {}))]))

(defn ensure-workspace!
  [cfg profile-id {:keys [file-id page-id name settings]}]
  (policy/ensure-enabled!)
  (policy/ensure-edit! cfg profile-id file-id)
  (or (some-> (raw-workspace cfg profile-id file-id page-id) decode-workspace)
      (let [inserted (insert-workspace! cfg profile-id file-id page-id name settings)
            workspace (decode-workspace
                       (or inserted
                           (raw-workspace cfg profile-id file-id page-id)))]
        (when inserted
          (seed-defaults! cfg profile-id (:workspace-id workspace)))
        workspace)))

(defn get-workspace!
  [cfg profile-id workspace-id]
  (decode-workspace (get-owned! cfg profile-id workspace-id)))

(defn list-artifacts!
  [cfg profile-id workspace-id]
  (get-owned! cfg profile-id workspace-id)
  (mapv decode-artifact
        (db/exec!
         cfg
         ["SELECT * FROM ai_harness_artifact
            WHERE workspace_id = ? AND profile_id = ?
            ORDER BY read_order, path"
          workspace-id profile-id])))

(defn get-artifact!
  [cfg profile-id workspace-id path]
  (get-owned! cfg profile-id workspace-id)
  (or (some-> (db/get cfg :ai-harness-artifact
                      {:workspace-id workspace-id
                       :profile-id profile-id
                       :path path})
              decode-artifact)
      (ex/raise :type :not-found :code :object-not-found :hint "not found")))

(defn routed-artifacts!
  [cfg profile-id workspace-id routing]
  (let [paths (rh/route-artifacts routing)]
    (mapv #(get-artifact! cfg profile-id workspace-id %) paths)))

(defn export-package!
  [cfg profile-id workspace-id]
  (let [workspace (get-workspace! cfg profile-id workspace-id)
        artifacts (list-artifacts! cfg profile-id workspace-id)]
    {:format "penpot-ai-harness"
     :version rh/version
     :workspace (select-keys workspace [:name :version :settings])
     :artifacts
     (mapv #(select-keys % [:path :kind :content-type :content :metadata
                            :required :read-order :content-hash])
           artifacts)
     :license-notices
     [{:project "walkinglabs/learn-harness-engineering"
       :license "MIT"
       :note "Concepts and template structure adapted for Penpot."}]}))

(defn import-package!
  [cfg profile-id workspace-id package]
  (let [artifacts (:artifacts package)]
    (when-not (and (map? package)
                   (= "penpot-ai-harness" (:format package))
                   (vector? artifacts))
      (ex/raise :type :validation
                :code :invalid-ai-harness-package
                :hint "Harness package is invalid"))
    (when (> (count artifacts) max-artifacts)
      (ex/raise :type :validation
                :code :too-many-ai-harness-artifacts
                :hint "Harness package contains too many artifacts"))
    (let [total (reduce + 0 (map (comp content-bytes :content) artifacts))]
      (when (> total max-package-bytes)
        (ex/raise :type :validation
                  :code :ai-harness-package-too-large
                  :hint "Harness package exceeds the size limit")))
    {:workspace-id workspace-id
     :imported
     (mapv #(upsert-artifact! cfg profile-id workspace-id %) artifacts)}))
