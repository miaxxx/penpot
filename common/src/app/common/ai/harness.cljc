;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.common.ai.harness
  "Clean-room Harness Engineering contracts for the Penpot AI assistant.

  This module intentionally models capabilities rather than reproducing any
  third-party implementation. Every write capability still resolves to a
  Proposal and the single native Penpot transaction gateway."
  (:require
   [clojure.string :as str]))

(def harness-version "1.0")

(def valid-input-modes
  #{:assistant :buddy :voice :vim :remote :mcp :plugin})

(def valid-transports
  #{:internal :rpc :mcp :plugin :remote})

(def valid-personas
  #{:assistant :buddy})

(def valid-session-statuses
  #{:active :paused :closed :expired})

(def valid-run-statuses
  #{:running :control :proposal-created :completed :failed :cancelled})

(def max-skill-files 64)
(def max-skill-package-bytes (* 2 1024 1024))
(def max-skill-file-bytes (* 256 1024))
(def default-context-budget 24000)
(def max-context-budget 75000)

(def commands
  [{:name "help"
    :aliases ["?"]
    :description "List Harness commands and active capabilities."
    :kind :read}
   {:name "skills"
    :description "List installed and built-in skills."
    :kind :read}
   {:name "plugins"
    :description "List declarative plugins and their contributions."
    :kind :read}
   {:name "context"
    :description "Inspect the current context budget and compartments."
    :kind :read}
   {:name "compact"
    :description "Compact older session traces before the next turn."
    :kind :write}
   {:name "plan"
    :description "Enable coordinator planning for the next design turn."
    :kind :write}
   {:name "coordinator"
    :description "Toggle planner/designer/verifier role decomposition."
    :kind :write}
   {:name "apply"
    :description "Request Penpot UI confirmation for the latest proposal."
    :kind :write}
   {:name "discard"
    :description "Discard the latest unapplied proposal."
    :kind :write}
   {:name "voice"
    :description "Treat the remaining text as a voice transcript."
    :kind :write}
   {:name "remote"
    :description "Show the authenticated remote-session descriptor."
    :kind :read}
   {:name "vim"
    :description "Parse a safe Vim-style Harness command."
    :kind :write}])

(def builtin-skills
  [{:skill-id "builtin:design-audit"
    :slug "design-audit"
    :name "Design Audit"
    :version harness-version
    :description "Review hierarchy, spacing, alignment, consistency and usability before proposing minimal fixes."
    :keywords ["audit" "review" "hierarchy" "spacing" "alignment" "consistency"]
    :tools ["canvas.summary" "canvas.read" "proposal.create-patch"]
    :capabilities ["canvas/read" "proposal/create"]
    :instructions
    "Audit the scoped Penpot design before editing. Identify concrete visual and usability defects, preserve deliberate style choices, and prefer a minimal Patch DSL over rebuilding."}
   {:skill-id "builtin:accessibility"
    :slug "accessibility"
    :name "Accessibility Review"
    :version harness-version
    :description "Check contrast, readable sizing, focus order, labels and interaction affordances."
    :keywords ["accessibility" "a11y" "contrast" "focus" "readability"]
    :tools ["canvas.summary" "canvas.read" "proposal.create-patch"]
    :capabilities ["canvas/read" "proposal/create"]
    :instructions
    "Evaluate the scoped UI for accessibility. Preserve the visual identity while improving contrast, readable type, target size, labels, focus order and clear states. Report unsupported checks explicitly."}
   {:skill-id "builtin:design-tokens"
    :slug "design-tokens"
    :name "Design Token Refactor"
    :version harness-version
    :description "Normalize repeated values and bind existing Penpot tokens without inventing token names."
    :keywords ["token" "tokens" "system" "refactor" "normalize"]
    :tools ["canvas.read" "proposal.create-patch"]
    :capabilities ["canvas/read" "proposal/create"]
    :instructions
    "Refactor only with tokens present in the supplied Penpot context. Use bindToken for valid semantic paths, preserve resolved values, and never invent token names."}
   {:skill-id "builtin:responsive-layout"
    :slug "responsive-layout"
    :name "Responsive Layout"
    :version harness-version
    :description "Improve Flex/Grid structure, sizing, gaps, padding and responsive constraints."
    :keywords ["responsive" "layout" "flex" "grid" "resize" "adaptive"]
    :tools ["canvas.read" "proposal.create-patch"]
    :capabilities ["canvas/read" "proposal/create"]
    :instructions
    "Prefer native Penpot Flex/Grid and sizing semantics. Keep the edit inside the declared scope, preserve content order and avoid absolute positioning unless required."}
   {:skill-id "builtin:ui-generation"
    :slug "ui-generation"
    :name "UI Generation"
    :version harness-version
    :description "Create structured, editable UI with native Penpot shapes and layout."
    :keywords ["generate" "create" "screen" "page" "component" "ui"]
    :tools ["canvas.summary" "proposal.create-document"]
    :capabilities ["canvas/read" "proposal/create"]
    :instructions
    "Generate editable native Penpot UI using semantic containers, text and layout. Do not invent media, component, token or prototype identifiers. Use clearly named placeholders for unavailable assets."}])

(defn command-registry
  []
  {:version harness-version
   :commands commands})

(defn command-name
  [value]
  (some-> value str str/trim str/lower-case))

(defn parse-command
  [input]
  (let [input (str/trim (str input))]
    (when (str/starts-with? input "/")
      (let [[head & tail] (str/split (subs input 1) #"\s+")
            requested (command-name head)
            command (some (fn [{:keys [name aliases] :as command}]
                            (when (or (= requested name)
                                      (some #{requested} aliases))
                              command))
                          commands)]
        {:command command
         :name requested
         :arguments (str/join " " tail)
         :raw input}))))

(defn slugify
  [value]
  (let [slug (-> (str value)
                 str/lower-case
                 (str/replace #"[^a-z0-9._-]+" "-")
                 (str/replace #"(^-|-$)" ""))]
    (if (str/blank? slug) "skill" slug)))

(defn normalize-string-list
  [values]
  (->> values
       (keep #(when (some? %) (str %)))
       (map str/trim)
       (remove str/blank?)
       distinct
       vec))

(defn normalize-skill-manifest
  [manifest]
  (let [name (str/trim (str (or (:name manifest)
                                (get manifest "name")
                                "Uploaded Skill")))
        slug (slugify (or (:slug manifest) (get manifest "slug") name))
        version (str/trim (str (or (:version manifest)
                                   (get manifest "version")
                                   harness-version)))
        description (str/trim (str (or (:description manifest)
                                       (get manifest "description")
                                       "")))
        tools (normalize-string-list
               (or (:tools manifest) (get manifest "tools") []))
        capabilities (normalize-string-list
                      (or (:capabilities manifest)
                          (get manifest "capabilities")
                          []))
        keywords (normalize-string-list
                  (or (:keywords manifest) (get manifest "keywords") []))]
    {:name name
     :slug slug
     :version version
     :description description
     :tools tools
     :capabilities capabilities
     :keywords keywords
     :auto-activate (boolean
                     (or (:auto-activate manifest)
                         (:autoActivate manifest)
                         (get manifest "autoActivate")
                         (get manifest "auto-activate")))
     :entrypoint (or (:entrypoint manifest)
                     (get manifest "entrypoint")
                     "SKILL.md")}))

(defn valid-input-mode?
  [value]
  (contains? valid-input-modes (keyword value)))

(defn valid-transport?
  [value]
  (contains? valid-transports (keyword value)))

(defn clamp-context-budget
  [value]
  (-> (or value default-context-budget)
      long
      (max 2000)
      (min max-context-budget)))
