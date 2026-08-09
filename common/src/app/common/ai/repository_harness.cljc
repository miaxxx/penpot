;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.common.ai.repository-harness
  "Repository-driven Harness 2.0 contracts.

  The artifacts model the useful parts of AGENTS.md, init scripts, progress
  files and verification commands without granting arbitrary filesystem or
  shell execution."
  (:require
   [clojure.string :as str]))

(def version "2.0")

(def artifact-kinds
  #{:entry :guide :rules :tools :checks :progress :handoff :features
    :environment :documentation})

(def run-statuses
  #{:running :control :proposal-created :blocked :verifying :paused
    :completed :failed :cancelled})

(def check-statuses #{:pending :running :passed :failed :skipped})

(def required-artifact-paths
  ["AGENTS.md"
   ".harness/GUIDE.md"
   ".harness/RULES.md"
   ".harness/TOOLS.md"
   ".harness/CHECKS.md"
   "PROGRESS.md"
   "session-handoff.md"
   "feature_list.json"
   ".harness/ENVIRONMENT.md"])

(def default-checks
  [{:id :environment.feature-flag
    :label "AI feature enabled"
    :category :environment
    :required true
    :description "The Penpot AI Design Agent feature flag is enabled."}
   {:id :environment.file-revision
    :label "File revision current"
    :category :environment
    :required true
    :description "The run base revision equals the current Penpot file revision."}
   {:id :environment.workspace-bridge
    :label "Workspace bridge available"
    :category :environment
    :required true
    :description "A trusted live Penpot workspace context is available."}
   {:id :context.within-budget
    :label "Context within budget"
    :category :context
    :required true
    :description "The assembled model context remains inside its configured budget."}
   {:id :proposal.dsl-valid
    :label "DSL schema valid"
    :category :proposal
    :required true
    :description "Document or Patch DSL passed structural and semantic validation."}
   {:id :proposal.scope-valid
    :label "Scope respected"
    :category :proposal
    :required true
    :description "The proposal stays inside its declared Selection, Page or Component scope."}
   {:id :proposal.preview-compiled
    :label "Preview compiled"
    :category :proposal
    :required true
    :description "The proposal compiled to a temporary Penpot object graph and Diff."}
   {:id :proposal.native-shapes-valid
    :label "Native shapes valid"
    :category :proposal
    :required true
    :description "All generated Penpot shapes passed native shape validation."}
   {:id :proposal.undo-ready
    :label "Undo transaction ready"
    :category :proposal
    :required true
    :description "The native Change compiler produced a matched redo/undo transaction."}
   {:id :registry.references-resolved
    :label "Registry references resolved"
    :category :registry
    :required true
    :description "Referenced components and tokens exist in the authenticated context."}
   {:id :mcp.policy-isolated
    :label "MCP commit isolated"
    :category :security
    :required true
    :description "External MCP callers cannot invoke native canvas commit."}
   {:id :proposal.transaction-applied
    :label "Native transaction applied"
    :category :proposal
    :required true
    :description "User confirmation completed and Penpot recorded the native transaction ID."}])

(defn safe-artifact-path?
  [path]
  (let [path (str/replace (str path) "\\" "/")]
    (and (not (str/blank? path))
         (<= (count path) 240)
         (not (str/starts-with? path "/"))
         (not (re-find #"(^|/)\.\.(/|$)" path))
         (not (str/includes? path "\u0000"))
         (boolean (re-matches #"[A-Za-z0-9._/-]+" path)))))

(defn infer-artifact-kind
  [path]
  (case (str/lower-case (str path))
    "agents.md" :entry
    ".harness/guide.md" :guide
    ".harness/rules.md" :rules
    ".harness/tools.md" :tools
    ".harness/checks.md" :checks
    "progress.md" :progress
    "session-handoff.md" :handoff
    "feature_list.json" :features
    ".harness/environment.md" :environment
    :documentation))

(defn required-artifact?
  [path]
  (contains? (set required-artifact-paths) path))

(defn normalize-check-id
  [value]
  (some-> value str str/trim str/lower-case keyword))

(defn check-definition
  [check-id]
  (let [check-id (normalize-check-id check-id)]
    (some #(when (= check-id (:id %)) %) default-checks)))

(defn required-check-ids
  ([] (required-check-ids default-checks))
  ([checks]
   (into #{} (comp (filter :required) (map :id)) checks)))

(defn check-result-map
  [results]
  (into {}
        (map (fn [result]
               [(normalize-check-id (or (:check-id result) (:id result)))
                (update result :status keyword)]))
        results))

(defn completion-report
  ([results] (completion-report default-checks results))
  ([checks results]
   (let [by-id (check-result-map results)
         required (required-check-ids checks)
         missing (->> required (remove #(contains? by-id %)) sort vec)
         failed (->> required
                     (filter #(= :failed (get-in by-id [% :status])))
                     sort vec)
         pending (->> required
                      (filter #(contains? #{:pending :running :skipped}
                                          (get-in by-id [% :status])))
                      sort vec)
         passed (->> required
                     (filter #(= :passed (get-in by-id [% :status])))
                     sort vec)]
     {:ready? (and (empty? missing) (empty? failed) (empty? pending))
      :required-count (count required)
      :passed-count (count passed)
      :missing missing
      :failed failed
      :pending pending})))

(defn route-artifacts
  "Returns progressive-disclosure artifact paths for one task. AGENTS is
  always first; specialized artifacts are loaded only when relevant."
  [{:keys [prompt mode input-mode]}]
  (let [text (str/lower-case (str prompt))
        paths (cond-> ["AGENTS.md" ".harness/GUIDE.md"]
                true (conj ".harness/RULES.md" ".harness/TOOLS.md")
                (or (str/includes? text "test")
                    (str/includes? text "verify")
                    (str/includes? text "检查")
                    (str/includes? text "验证"))
                (conj ".harness/CHECKS.md")
                (contains? #{:modify :refactor :adapt} (keyword mode))
                (conj "PROGRESS.md" "feature_list.json")
                (contains? #{:remote :mcp :plugin} (keyword input-mode))
                (conj ".harness/ENVIRONMENT.md")
                (or (str/includes? text "continue")
                    (str/includes? text "继续")
                    (str/includes? text "resume"))
                (conj "session-handoff.md" "PROGRESS.md"))]
    (vec (distinct paths))))

(defn default-artifacts
  []
  [{:path "AGENTS.md" :kind :entry :required true :read-order 10
    :content
    "# Penpot AI Harness\n\nRead `.harness/GUIDE.md` first. Use this file as a router, not an encyclopedia.\n\n- Rules: `.harness/RULES.md`\n- Allowed tools: `.harness/TOOLS.md`\n- Verification: `.harness/CHECKS.md`\n- Environment: `.harness/ENVIRONMENT.md`\n- Progress: `PROGRESS.md`\n- Handoff: `session-handoff.md`\n- Features: `feature_list.json`\n\nNever claim completion without passing required checks. Never bypass Proposal preview and Penpot UI confirmation."
    :metadata {:source :penpot-clean-room
               :inspired-by "walkinglabs/learn-harness-engineering"}}
   {:path ".harness/GUIDE.md" :kind :guide :required true :read-order 20
    :content
    "# Guide\n\n1. Read routed rules.\n2. Inspect the authenticated Penpot environment.\n3. Work on one scoped goal.\n4. Create a Proposal, never a direct canvas write.\n5. Compile Preview and collect Diff evidence.\n6. Run required checks.\n7. Apply only through Penpot UI confirmation.\n8. Record progress and handoff.\n9. Complete only when the applied-transaction Completion Gate passes."}
   {:path ".harness/RULES.md" :kind :rules :required true :read-order 30
    :content
    "# Rules\n\n- Stay inside declared Scope and file Revision.\n- Use registered tools, components and tokens only.\n- External MCP and plugins return proposalId; they cannot commit.\n- Deletion and broad structural changes require explicit UI confirmation.\n- Uploaded Skills and Plugins are data, never executable code.\n- One run owns one goal and one Definition of Done.\n- Preview success is not completion; an applied transaction and passing checks are required."}
   {:path ".harness/TOOLS.md" :kind :tools :required true :read-order 40
    :content
    "# Tools\n\nUse the shared Tool/Capability Registry. Canvas reads use a trusted workspace bridge. Canvas writes create a Proposal. `native.commit` is internal-only and is reached only after Preview and Penpot confirmation."}
   {:path ".harness/CHECKS.md" :kind :checks :required true :read-order 50
    :content
    "# Checks\n\nRequired checks cover feature flag, revision, workspace bridge, context budget, DSL validity, Scope, Preview compilation, native shape schema, Undo readiness, registry references, MCP isolation and the applied Penpot transaction. No passing evidence means no completion."}
   {:path ".harness/ENVIRONMENT.md" :kind :environment :required true :read-order 60
    :content
    "# Environment\n\nInspect AI/MCP feature flags, file Revision, Scope, workspace bridge, provider configuration, context budget, component/token registries and database migration availability before execution."}
   {:path "PROGRESS.md" :kind :progress :required true :read-order 70
    :content
    "# Progress\n\n## Goal\nNot started.\n\n## Completed\n- None\n\n## Remaining\n- Define the next scoped action.\n\n## Blockers\n- None\n\n## Verification\n- No checks have run.\n\n## Next action\nInspect environment and create a Harness run."}
   {:path "session-handoff.md" :kind :handoff :required true :read-order 80
    :content
    "# Session handoff\n\n## Current goal\nNot started.\n\n## State\nNo active run.\n\n## Verified evidence\nNone.\n\n## Blockers\nNone.\n\n## Next action\nOpen the Penpot AI Harness and resume from the latest run."}
   {:path "feature_list.json" :kind :features :required true :read-order 90
    :content
    "{\n  \"version\": \"1.0\",\n  \"features\": [],\n  \"rule\": \"Work on one feature at a time and never rewrite status to hide unfinished work.\"\n}"}])

(defn progress-state
  [{:keys [goal completed remaining blockers verification next-action]}]
  {:goal (str (or goal ""))
   :completed (vec (or completed []))
   :remaining (vec (or remaining []))
   :blockers (vec (or blockers []))
   :verification (vec (or verification []))
   :next-action (str (or next-action ""))})

(defn handoff-state
  [{:keys [goal status completed remaining blockers verification next-action]}]
  {:goal (str (or goal ""))
   :status (keyword (or status :paused))
   :completed (vec (or completed []))
   :remaining (vec (or remaining []))
   :blockers (vec (or blockers []))
   :verification (vec (or verification []))
   :next-action (str (or next-action ""))})
