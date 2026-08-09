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

;; The main RPC registry scans an explicit namespace list that includes this
;; namespace. Keep registration shims small and delegate behavior to the AI
;; modules, which remain transport-independent.

(sv/defmethod ::test-ai-provider
  {::doc/added "2.10" ::audit/skip true
   ::sm/params ai/schema:test-ai-provider}
  [cfg params]
  (ai/test-ai-provider cfg params))

(sv/defmethod ::generate-ai-design-proposal
  {::doc/added "2.10" ::audit/skip true
   ::sm/params ai/schema:generate-ai-design-proposal}
  [cfg params]
  (ai/generate-ai-design-proposal cfg params))

(sv/defmethod ::create-ai-design-proposal
  {::doc/added "2.10" ::audit/skip true
   ::sm/params ai/schema:create-ai-design-proposal}
  [cfg params]
  (ai/create-ai-design-proposal cfg params))

(sv/defmethod ::list-ai-design-proposals
  {::doc/added "2.10" ::audit/skip true
   ::sm/params ai/schema:list-proposals}
  [cfg params]
  (ai/list-ai-design-proposals cfg params))

(sv/defmethod ::get-ai-design-proposal
  {::doc/added "2.10" ::audit/skip true
   ::sm/params ai/schema:proposal-id}
  [cfg params]
  (ai/get-ai-design-proposal cfg params))

(sv/defmethod ::preview-ai-design-proposal
  {::doc/added "2.10" ::audit/skip true
   ::sm/params ai/schema:preview-proposal}
  [cfg params]
  (ai/preview-ai-design-proposal cfg params))

(sv/defmethod ::discard-ai-design-proposal
  {::doc/added "2.10" ::audit/skip true
   ::sm/params ai/schema:proposal-id}
  [cfg params]
  (ai/discard-ai-design-proposal cfg params))

(sv/defmethod ::request-ai-design-proposal-apply
  {::doc/added "2.10" ::audit/skip true
   ::sm/params ai/schema:proposal-id}
  [cfg params]
  (ai/request-ai-design-proposal-apply cfg params))

(sv/defmethod ::begin-ai-design-proposal-apply
  {::doc/added "2.10" ::audit/skip true
   ::sm/params ai/schema:proposal-id}
  [cfg params]
  (ai/begin-ai-design-proposal-apply cfg params))

(sv/defmethod ::complete-ai-design-proposal-apply
  {::doc/added "2.10" ::audit/skip true
   ::sm/params ai/schema:complete-proposal}
  [cfg params]
  (ai/complete-ai-design-proposal-apply cfg params))

(sv/defmethod ::conflict-ai-design-proposal
  {::doc/added "2.10" ::audit/skip true
   ::sm/params ai/schema:conflict-proposal}
  [cfg params]
  (ai/conflict-ai-design-proposal cfg params))

(sv/defmethod ::list-ai-design-tools
  {::doc/added "2.10" ::audit/skip true
   ::sm/params [:map {:closed true}]}
  [cfg params]
  (ai/list-ai-design-tools cfg params))

(sv/defmethod ::list-ai-mcp-tools
  {::doc/added "2.10" ::audit/skip true
   ::sm/params [:map {:closed true}]}
  [cfg params]
  (ai/list-ai-mcp-tools cfg params))

(sv/defmethod ::invoke-ai-mcp-tool
  {::doc/added "2.10" ::audit/skip true
   ::sm/params ai/schema:invoke-mcp-tool}
  [cfg params]
  (ai/invoke-ai-mcp-tool cfg params))

(sv/defmethod ::get-ai-harness
  {::doc/added "2.10" ::audit/skip true
   ::sm/params [:map {:closed true}]}
  [cfg params]
  (ai/get-ai-harness cfg params))

(sv/defmethod ::install-ai-harness-skill
  {::doc/added "2.10" ::audit/skip true
   ::sm/params ai/schema:install-harness-package}
  [cfg params]
  (ai/install-ai-harness-skill cfg params))

(sv/defmethod ::list-ai-harness-skills
  {::doc/added "2.10" ::audit/skip true
   ::sm/params [:map {:closed true}]}
  [cfg params]
  (ai/list-ai-harness-skills cfg params))

(sv/defmethod ::set-ai-harness-skill-enabled
  {::doc/added "2.10" ::audit/skip true
   ::sm/params ai/schema:harness-item-enabled}
  [cfg params]
  (ai/set-ai-harness-skill-enabled cfg params))

(sv/defmethod ::delete-ai-harness-skill
  {::doc/added "2.10" ::audit/skip true
   ::sm/params ai/schema:harness-item-id}
  [cfg params]
  (ai/delete-ai-harness-skill cfg params))

(sv/defmethod ::install-ai-harness-plugin
  {::doc/added "2.10" ::audit/skip true
   ::sm/params ai/schema:install-harness-package}
  [cfg params]
  (ai/install-ai-harness-plugin cfg params))

(sv/defmethod ::list-ai-harness-plugins
  {::doc/added "2.10" ::audit/skip true
   ::sm/params [:map {:closed true}]}
  [cfg params]
  (ai/list-ai-harness-plugins cfg params))

(sv/defmethod ::set-ai-harness-plugin-enabled
  {::doc/added "2.10" ::audit/skip true
   ::sm/params ai/schema:harness-item-enabled}
  [cfg params]
  (ai/set-ai-harness-plugin-enabled cfg params))

(sv/defmethod ::delete-ai-harness-plugin
  {::doc/added "2.10" ::audit/skip true
   ::sm/params ai/schema:harness-item-id}
  [cfg params]
  (ai/delete-ai-harness-plugin cfg params))

(sv/defmethod ::create-ai-harness-session
  {::doc/added "2.10" ::audit/skip true
   ::sm/params ai/schema:create-harness-session}
  [cfg params]
  (ai/create-ai-harness-session cfg params))

(sv/defmethod ::get-ai-harness-session
  {::doc/added "2.10" ::audit/skip true
   ::sm/params ai/schema:harness-session-id}
  [cfg params]
  (ai/get-ai-harness-session cfg params))

(sv/defmethod ::list-ai-harness-sessions
  {::doc/added "2.10" ::audit/skip true
   ::sm/params ai/schema:list-harness-sessions}
  [cfg params]
  (ai/list-ai-harness-sessions cfg params))

(sv/defmethod ::update-ai-harness-session
  {::doc/added "2.10" ::audit/skip true
   ::sm/params ai/schema:update-harness-settings}
  [cfg params]
  (ai/update-ai-harness-session cfg params))

(sv/defmethod ::close-ai-harness-session
  {::doc/added "2.10" ::audit/skip true
   ::sm/params ai/schema:harness-session-id}
  [cfg params]
  (ai/close-ai-harness-session cfg params))

(sv/defmethod ::list-ai-harness-runs
  {::doc/added "2.10" ::audit/skip true
   ::sm/params ai/schema:list-harness-runs}
  [cfg params]
  (ai/list-ai-harness-runs cfg params))

(sv/defmethod ::run-ai-harness-turn
  {::doc/added "2.10" ::audit/skip true
   ::sm/params ai/schema:run-harness-turn}
  [cfg params]
  (ai/run-ai-harness-turn cfg params))

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
