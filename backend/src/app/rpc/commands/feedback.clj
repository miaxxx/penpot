;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.rpc.commands.feedback
  "A general purpose feedback module."
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
   [app.rpc.commands.profile :as profile]
   [app.rpc.doc :as-alias doc]
   [app.util.services :as sv]))

(declare ^:private send-user-feedback!)

;; The main RPC registry currently scans an explicit namespace list that already
;; includes this namespace. Keep these registration shims small and delegate all
;; behavior to app.rpc.commands.ai.
(sv/defmethod ::test-ai-provider
  {::doc/added "2.10"
   ::audit/skip true
   ::sm/params ai/schema:test-ai-provider}
  [cfg params]
  (ai/test-ai-provider cfg params))

(sv/defmethod ::generate-ai-design-proposal
  {::doc/added "2.10"
   ::audit/skip true
   ::sm/params ai/schema:generate-ai-design-proposal}
  [cfg params]
  (ai/generate-ai-design-proposal cfg params))

(sv/defmethod ::create-ai-design-proposal
  {::doc/added "2.10"
   ::audit/skip true
   ::sm/params ai/schema:create-ai-design-proposal}
  [cfg params]
  (ai/create-ai-design-proposal cfg params))

(sv/defmethod ::list-ai-design-proposals
  {::doc/added "2.10"
   ::audit/skip true
   ::sm/params ai/schema:list-proposals}
  [cfg params]
  (ai/list-ai-design-proposals cfg params))

(sv/defmethod ::get-ai-design-proposal
  {::doc/added "2.10"
   ::audit/skip true
   ::sm/params ai/schema:proposal-id}
  [cfg params]
  (ai/get-ai-design-proposal cfg params))

(sv/defmethod ::preview-ai-design-proposal
  {::doc/added "2.10"
   ::audit/skip true
   ::sm/params ai/schema:preview-proposal}
  [cfg params]
  (ai/preview-ai-design-proposal cfg params))

(sv/defmethod ::discard-ai-design-proposal
  {::doc/added "2.10"
   ::audit/skip true
   ::sm/params ai/schema:proposal-id}
  [cfg params]
  (ai/discard-ai-design-proposal cfg params))

(sv/defmethod ::request-ai-design-proposal-apply
  {::doc/added "2.10"
   ::audit/skip true
   ::sm/params ai/schema:proposal-id}
  [cfg params]
  (ai/request-ai-design-proposal-apply cfg params))

(sv/defmethod ::begin-ai-design-proposal-apply
  {::doc/added "2.10"
   ::audit/skip true
   ::sm/params ai/schema:proposal-id}
  [cfg params]
  (ai/begin-ai-design-proposal-apply cfg params))

(sv/defmethod ::complete-ai-design-proposal-apply
  {::doc/added "2.10"
   ::audit/skip true
   ::sm/params ai/schema:complete-proposal}
  [cfg params]
  (ai/complete-ai-design-proposal-apply cfg params))

(sv/defmethod ::conflict-ai-design-proposal
  {::doc/added "2.10"
   ::audit/skip true
   ::sm/params ai/schema:conflict-proposal}
  [cfg params]
  (ai/conflict-ai-design-proposal cfg params))

(sv/defmethod ::list-ai-design-tools
  {::doc/added "2.10"
   ::audit/skip true
   ::sm/params [:map {:closed true}]}
  [cfg params]
  (ai/list-ai-design-tools cfg params))

(sv/defmethod ::list-ai-mcp-tools
  {::doc/added "2.10"
   ::audit/skip true
   ::sm/params [:map {:closed true}]}
  [cfg params]
  (ai/list-ai-mcp-tools cfg params))

(sv/defmethod ::invoke-ai-mcp-tool
  {::doc/added "2.10"
   ::audit/skip true
   ::sm/params ai/schema:invoke-mcp-tool}
  [cfg params]
  (ai/invoke-ai-mcp-tool cfg params))

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
            ;; LEGACY
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
