;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.common.ai.tools
  "Transport-neutral Tool/Capability Registry for every AI entry point.

  Internal AI, RPC, MCP and future plugins advertise the same tools, while
  policy controls which transports may invoke each operation. Native canvas
  commit is internal-only and always requires Penpot UI confirmation.")

(def registry-version "1.0")

(def empty-input-schema
  {:type "object"
   :properties {}
   :additionalProperties false})

(def proposal-id-input-schema
  {:type "object"
   :properties
   {"proposalId" {:type "string" :format "uuid"}}
   :required ["proposalId"]
   :additionalProperties false})

(def proposal-input-schema
  {:type "object"
   :properties
   {"fileId" {:type "string" :format "uuid"}
    "pageId" {:type "string" :format "uuid"}
    "baseRevision" {:type "integer" :minimum 0}
    "mode" {:type "string"
            :enum ["generate" "modify" "refactor" "adapt"]}
    "scope" {:type "object"}
    "plan" {:type "object"}
    "dsl" {:type "object"}}
   :required ["fileId" "pageId" "baseRevision" "mode" "scope" "dsl"]
   :additionalProperties false})

(def registry
  {:tools.list
   {:id :tools.list
    :version registry-version
    :description "List AI design tools available to a transport."
    :access :read
    :capability :tools/read
    :transports #{:internal :rpc :mcp :plugin}
    :confirmation :none
    :result :tool-list
    :input-schema empty-input-schema}

   :canvas.summary
   {:id :canvas.summary
    :version registry-version
    :description "Read a bounded semantic summary of the active canvas scope."
    :access :read
    :capability :canvas/read
    :transports #{:internal :rpc :mcp :plugin}
    :confirmation :none
    :result :canvas-summary
    :input-schema empty-input-schema}

   :canvas.read
   {:id :canvas.read
    :version registry-version
    :description "Read bounded nodes from an authenticated live workspace bridge."
    :access :read
    :capability :canvas/read
    :transports #{:internal :rpc :mcp :plugin}
    :confirmation :none
    :result :canvas-context
    :input-schema
    {:type "object"
     :properties
     {"depth" {:type "integer" :minimum 0 :maximum 20}
      "limit" {:type "integer" :minimum 1 :maximum 500}}
     :additionalProperties false}}

   :dsl.validate
   {:id :dsl.validate
    :version registry-version
    :description "Validate Document or Patch DSL without creating a proposal."
    :access :read
    :capability :dsl/validate
    :transports #{:internal :rpc :mcp :plugin}
    :confirmation :none
    :result :validation-report
    :input-schema
    {:type "object"
     :properties
     {"dslType" {:type "string" :enum ["document" "patch"]}
      "dsl" {:type "object"}}
     :required ["dslType" "dsl"]
     :additionalProperties false}}

   :proposal.create-document
   {:id :proposal.create-document
    :version registry-version
    :description "Create a validated, expiring Document DSL proposal."
    :access :write
    :capability :proposal/create
    :transports #{:internal :rpc :mcp :plugin}
    :confirmation :required
    :result :proposal-id
    :input-schema proposal-input-schema}

   :proposal.create-patch
   {:id :proposal.create-patch
    :version registry-version
    :description "Create a validated, scoped, expiring Patch DSL proposal."
    :access :write
    :capability :proposal/create
    :transports #{:internal :rpc :mcp :plugin}
    :confirmation :required
    :result :proposal-id
    :input-schema proposal-input-schema}

   :proposal.get
   {:id :proposal.get
    :version registry-version
    :description "Read one proposal owned by the authenticated actor."
    :access :read
    :capability :proposal/read
    :transports #{:internal :rpc :mcp :plugin}
    :confirmation :none
    :result :proposal
    :input-schema proposal-id-input-schema}

   :proposal.preview
   {:id :proposal.preview
    :version registry-version
    :description "Attach a locally compiled Penpot diff summary to a proposal."
    :access :write
    :capability :proposal/preview
    :transports #{:internal :rpc :plugin}
    :confirmation :none
    :result :proposal
    :input-schema
    {:type "object"
     :properties
     {"proposalId" {:type "string" :format "uuid"}
      "preview" {:type "object"}}
     :required ["proposalId" "preview"]
     :additionalProperties false}}

   :proposal.discard
   {:id :proposal.discard
    :version registry-version
    :description "Discard an unapplied proposal."
    :access :write
    :capability :proposal/discard
    :transports #{:internal :rpc :mcp :plugin}
    :confirmation :none
    :result :proposal
    :input-schema proposal-id-input-schema}

   :proposal.request-apply
   {:id :proposal.request-apply
    :version registry-version
    :description "Request Penpot UI confirmation for an existing proposal."
    :access :write
    :capability :proposal/request-apply
    :transports #{:internal :rpc :mcp :plugin}
    :confirmation :penpot-ui
    :result :proposal-id
    :input-schema proposal-id-input-schema}

   :proposal.begin-apply
   {:id :proposal.begin-apply
    :version registry-version
    :description "Lock and revalidate a previewed proposal before native commit."
    :access :commit
    :capability :proposal/apply
    :transports #{:internal :rpc}
    :confirmation :penpot-ui
    :result :apply-token
    :input-schema proposal-id-input-schema}

   :proposal.complete-apply
   {:id :proposal.complete-apply
    :version registry-version
    :description "Mark a proposal applied after the native transaction commits."
    :access :commit
    :capability :proposal/apply
    :transports #{:internal :rpc}
    :confirmation :penpot-ui
    :result :proposal
    :input-schema
    {:type "object"
     :properties
     {"proposalId" {:type "string" :format "uuid"}
      "applyToken" {:type "string" :format "uuid"}
      "transactionId" {:type "string"}}
     :required ["proposalId" "applyToken" "transactionId"]
     :additionalProperties false}}

   :proposal.conflict
   {:id :proposal.conflict
    :version registry-version
    :description "Mark an applying proposal conflicted without writing canvas data."
    :access :commit
    :capability :proposal/apply
    :transports #{:internal :rpc}
    :confirmation :penpot-ui
    :result :proposal
    :input-schema
    {:type "object"
     :properties
     {"proposalId" {:type "string" :format "uuid"}
      "applyToken" {:type "string" :format "uuid"}
      "error" {:type "object"}}
     :required ["proposalId" "applyToken" "error"]
     :additionalProperties false}}

   :native.commit
   {:id :native.commit
    :version registry-version
    :description "Submit native Penpot Change[] as one Undo transaction."
    :access :commit
    :capability :canvas/commit
    :transports #{:internal}
    :confirmation :penpot-ui
    :result :transaction-id
    :input-schema empty-input-schema}})

(defn get-tool
  [tool-id]
  (get registry (keyword tool-id)))

(defn tool-exists?
  [tool-id]
  (some? (get-tool tool-id)))

(defn allowed?
  [tool-id transport]
  (contains? (:transports (get-tool tool-id)) (keyword transport)))

(defn requires-confirmation?
  [tool-id]
  (not= :none (:confirmation (get-tool tool-id))))

(defn list-tools
  ([] (vals registry))
  ([transport]
   (->> registry
        vals
        (filter #(contains? (:transports %) (keyword transport)))
        (sort-by (comp name :id))
        vec)))

(defn capabilities
  [transport]
  (into #{} (map :capability) (list-tools transport)))
