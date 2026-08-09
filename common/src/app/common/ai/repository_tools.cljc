;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.common.ai.repository-tools
  "Read-oriented Repository Harness tools available to authenticated MCP and
  other transports. Verification evidence and completion remain internal."
  (:require
   [app.common.ai.repository-harness :as rh]))

(def uuid-property {:type "string" :format "uuid"})

(def workspace-input
  {:type "object"
   :properties {"workspaceId" uuid-property}
   :required ["workspaceId"]
   :additionalProperties false})

(def run-input
  {:type "object"
   :properties {"runId" uuid-property}
   :required ["runId"]
   :additionalProperties false})

(def registry
  {:harness.workspace.ensure
   {:id :harness.workspace.ensure
    :version rh/version
    :description "Create or return the authenticated Penpot Harness workspace for a file/page."
    :access :write
    :capability :harness/workspace
    :transports #{:internal :rpc :mcp :plugin}
    :confirmation :none
    :result :harness-workspace
    :input-schema
    {:type "object"
     :properties {"fileId" uuid-property
                  "pageId" uuid-property}
     :required ["fileId"]
     :additionalProperties false}}

   :harness.artifacts.list
   {:id :harness.artifacts.list
    :version rh/version
    :description "List repository-style Harness artifacts in read order."
    :access :read
    :capability :harness/read
    :transports #{:internal :rpc :mcp :plugin}
    :confirmation :none
    :result :harness-artifact-list
    :input-schema workspace-input}

   :harness.artifact.get
   {:id :harness.artifact.get
    :version rh/version
    :description "Read one Harness artifact such as AGENTS.md, checks or handoff."
    :access :read
    :capability :harness/read
    :transports #{:internal :rpc :mcp :plugin}
    :confirmation :none
    :result :harness-artifact
    :input-schema
    {:type "object"
     :properties {"workspaceId" uuid-property
                  "path" {:type "string" :minLength 1 :maxLength 240}}
     :required ["workspaceId" "path"]
     :additionalProperties false}}

   :harness.run.latest
   {:id :harness.run.latest
    :version rh/version
    :description "Read the latest persisted Harness run and next action."
    :access :read
    :capability :harness/read
    :transports #{:internal :rpc :mcp :plugin}
    :confirmation :none
    :result :harness-run
    :input-schema workspace-input}

   :harness.checks.list
   {:id :harness.checks.list
    :version rh/version
    :description "List registered evidence checks and completion requirements."
    :access :read
    :capability :harness/read
    :transports #{:internal :rpc :mcp :plugin}
    :confirmation :none
    :result :harness-check-definitions
    :input-schema {:type "object" :properties {} :additionalProperties false}}

   :harness.checks.results
   {:id :harness.checks.results
    :version rh/version
    :description "Read persisted verification evidence for a Harness run."
    :access :read
    :capability :harness/read
    :transports #{:internal :rpc :mcp :plugin}
    :confirmation :none
    :result :harness-check-results
    :input-schema run-input}})

(defn get-tool [tool-id]
  (get registry (keyword tool-id)))

(defn list-tools [transport]
  (->> registry
       vals
       (filter #(contains? (:transports %) (keyword transport)))
       (sort-by (comp name :id))
       vec))
