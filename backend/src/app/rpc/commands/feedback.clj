;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.rpc.commands.feedback
  "A general purpose feedback module and AI RPC registration bridge."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.config :as cf]
   [app.db :as db]
   [app.email :as eml]
   [app.loggers.audit :as-alias audit]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.ai :as ai]
   [app.rpc.commands.ai-harness-repository-gateway :as harness-repository]
   [app.rpc.commands.profile :as profile]
   [app.rpc.doc :as-alias doc]
   [app.util.services :as sv]))

(declare ^:private send-user-feedback!)

(defmacro ^:private def-ai-bridge
  [method schema handler]
  `(sv/defmethod ~method
     {::doc/added "2.10"
      ::audit/skip true
      ::sm/params ~schema}
     [cfg# params#]
     (~handler cfg# params#)))

(def-ai-bridge ::test-ai-provider
  ai/schema:test-ai-provider ai/test-ai-provider)
(def-ai-bridge ::generate-ai-design-proposal
  ai/schema:generate-ai-design-proposal ai/generate-ai-design-proposal)
(def-ai-bridge ::create-ai-design-proposal
  ai/schema:create-ai-design-proposal ai/create-ai-design-proposal)
(def-ai-bridge ::list-ai-design-proposals
  ai/schema:list-proposals ai/list-ai-design-proposals)
(def-ai-bridge ::get-ai-design-proposal
  ai/schema:proposal-id ai/get-ai-design-proposal)
(def-ai-bridge ::preview-ai-design-proposal
  ai/schema:preview-proposal ai/preview-ai-design-proposal)
(def-ai-bridge ::discard-ai-design-proposal
  ai/schema:proposal-id ai/discard-ai-design-proposal)
(def-ai-bridge ::request-ai-design-proposal-apply
  ai/schema:proposal-id ai/request-ai-design-proposal-apply)
(def-ai-bridge ::begin-ai-design-proposal-apply
  ai/schema:proposal-id ai/begin-ai-design-proposal-apply)
(def-ai-bridge ::complete-ai-design-proposal-apply
  ai/schema:complete-proposal ai/complete-ai-design-proposal-apply)
(def-ai-bridge ::conflict-ai-design-proposal
  ai/schema:conflict-proposal ai/conflict-ai-design-proposal)
(def-ai-bridge ::list-ai-design-tools
  [:map {:closed true}] ai/list-ai-design-tools)
(def-ai-bridge ::list-ai-mcp-tools
  [:map {:closed true}] ai/list-ai-mcp-tools)
(def-ai-bridge ::invoke-ai-mcp-tool
  ai/schema:invoke-mcp-tool ai/invoke-ai-mcp-tool)

(def-ai-bridge ::get-ai-harness
  [:map {:closed true}] ai/get-ai-harness)
(def-ai-bridge ::install-ai-harness-skill
  ai/schema:install-harness-package ai/install-ai-harness-skill)
(def-ai-bridge ::list-ai-harness-skills
  [:map {:closed true}] ai/list-ai-harness-skills)
(def-ai-bridge ::set-ai-harness-skill-enabled
  ai/schema:harness-item-enabled ai/set-ai-harness-skill-enabled)
(def-ai-bridge ::delete-ai-harness-skill
  ai/schema:harness-item-id ai/delete-ai-harness-skill)
(def-ai-bridge ::install-ai-harness-plugin
  ai/schema:install-harness-package ai/install-ai-harness-plugin)
(def-ai-bridge ::list-ai-harness-plugins
  [:map {:closed true}] ai/list-ai-harness-plugins)
(def-ai-bridge ::set-ai-harness-plugin-enabled
  ai/schema:harness-item-enabled ai/set-ai-harness-plugin-enabled)
(def-ai-bridge ::delete-ai-harness-plugin
  ai/schema:harness-item-id ai/delete-ai-harness-plugin)
(def-ai-bridge ::create-ai-harness-session
  ai/schema:create-harness-session ai/create-ai-harness-session)
(def-ai-bridge ::get-ai-harness-session
  ai/schema:harness-session-id ai/get-ai-harness-session)
(def-ai-bridge ::list-ai-harness-sessions
  ai/schema:list-harness-sessions ai/list-ai-harness-sessions)
(def-ai-bridge ::update-ai-harness-session
  ai/schema:update-harness-settings ai/update-ai-harness-session)
(def-ai-bridge ::close-ai-harness-session
  ai/schema:harness-session-id ai/close-ai-harness-session)
(def-ai-bridge ::list-ai-harness-runs
  ai/schema:list-harness-runs ai/list-ai-harness-runs)

;; Backward-compatible command name. Every normal AI turn now enters the
;; Repository Harness before provider execution.
(sv/defmethod ::run-ai-harness-turn
  {::doc/added "2.10"
   ::audit/skip true
   ::sm/params ai/schema:run-harness-turn}
  [cfg params]
  (harness-repository/invoke
   cfg
   {::rpc/profile-id (::rpc/profile-id params)
    :action "turn.run"
    :arguments (dissoc params ::rpc/profile-id)}))

(sv/defmethod ::invoke-ai-harness-repository
  {::doc/added "2.10"
   ::audit/skip true
   ::sm/params harness-repository/schema:invoke}
  [cfg params]
  (harness-repository/invoke cfg params))

(def ^:private schema:send-user-feedback
  [:map {:title "send-user-feedback"}
   [:subject [:string {:max 500}]]
   [:content [:string {:max 2500}]]
   [:type {:optional true} :string]
   [:error-href {:optional true} [:string {:max 2500}]]
   [:error-report {:optional true} :string]])

(sv/defmethod ::send-user-feedback
  {::doc/added "1.18"
   ::sm/params schema:send-user-feedback}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id] :as params}]
  (when-not (contains? cf/flags :user-feedback)
    (ex/raise :type :restriction
              :code :feedback-disabled
              :hint "feedback not enabled"))
  (let [profile (profile/get-profile pool profile-id)]
    (send-user-feedback! pool profile params)
    nil))

(defn- send-user-feedback!
  [pool profile params]
  (let [destination
        (or (cf/get :user-feedback-destination)
            (cf/get :feedback-destination))
        attachments
        (d/without-nils
         {"error-report.txt" (:error-report params)})]
    (eml/send! {::eml/conn pool
                ::eml/factory eml/user-feedback
                :to destination
                :reply-to (:email profile)
                :email (:email profile)
                :attachments attachments
                :feedback-subject (:subject params)
                :feedback-type (:type params "not-specified")
                :feedback-content (:content params)
                :feedback-error-href (:error-href params)
                :profile profile})
    nil))
