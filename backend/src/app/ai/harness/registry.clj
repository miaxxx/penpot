;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.ai.harness.registry
  "Unified Harness capability report used by UI, RPC and adapters."
  (:require
   [app.ai.harness.commands :as commands]
   [app.ai.harness.plugins :as plugins]
   [app.ai.harness.skills :as skills]
   [app.common.ai.harness :as harness]
   [app.common.ai.tools :as tools]))

(def modules
  [{:id :tools
    :label "Tools"
    :status :ready
    :description "Shared Tool/Capability Registry with transport policy."}
   {:id :commands
    :label "Commands"
    :status :ready
    :description "Slash commands and safe Vim-style command parsing."}
   {:id :services
    :label "Services"
    :status :ready
    :description "Provider, Proposal, context and orchestration services."}
   {:id :utils
    :label "Utils"
    :status :ready
    :description "Bounded archives, hashing, redaction and context budgeting."}
   {:id :context
    :label "Context"
    :status :ready
    :description "Priority compartments, history compaction and token reports."}
   {:id :coordinator
    :label "Coordinator"
    :status :ready
    :description "Planner, designer, reviewer, verifier and executor roles."}
   {:id :assistant
    :label "Assistant"
    :status :ready
    :description "Default embedded Penpot assistant persona."}
   {:id :buddy
    :label "Buddy"
    :status :ready
    :description "Companion persona with the same permissions and confirmation."}
   {:id :remote
    :label "Remote"
    :status :adapter-ready
    :description "Authenticated session adapter; deployment listener not bundled."}
   {:id :plugins
    :label "Plugins"
    :status :ready
    :description "Declarative contributions without arbitrary code execution."}
   {:id :skills
    :label "Skills"
    :status :ready
    :description "Upload, install, enable, auto-select and inject SKILL.md packages."}
   {:id :voice
    :label "Voice"
    :status :adapter-ready
    :description "Transcript input mode; speech-to-text transport is external."}
   {:id :vim
    :label "Vim"
    :status :ready
    :description "Safe colon commands for Harness controls."}])

(defn report
  [cfg profile-id]
  {:version harness/harness-version
   :modules modules
   :tools
   {:registry-version tools/registry-version
    :count (count (tools/list-tools :internal))
    :capabilities (sort (map str (tools/capabilities :internal)))}
   :commands (commands/registry)
   :skills
   {:built-in-count (count harness/builtin-skills)
    :installed (skills/list-uploaded! cfg profile-id)}
   :plugins
   {:installed (plugins/list! cfg profile-id)}
   :security
   {:arbitrary-code-execution false
    :native-commit-tool "native.commit"
    :proposal-required true
    :identity-from-authenticated-transport true
    :scope-and-revision-required true}})
