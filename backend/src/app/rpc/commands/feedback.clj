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
;; includes this namespace. Keep the registration shims small and delegate all
;; provider behavior to app.rpc.commands.ai. They can move unchanged when the AI
;; namespace receives its own scanner entry.
(sv/defmethod ::test-ai-provider
  {::doc/added "2.10"
   ::audit/skip true
   ::sm/params ai/schema:test-ai-provider}
  [cfg params]
  (ai/test-ai-provider cfg params))

(sv/defmethod ::generate-ai-design-proposal
  {::doc/added "2.10"
   ;; Provider credentials and scoped design context must never become generic
   ;; RPC audit properties.
   ::audit/skip true
   ::sm/params ai/schema:generate-ai-design-proposal}
  [cfg params]
  (ai/generate-ai-design-proposal cfg params))

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
                :to       destination
                :reply-to (:email profile)
                :email    (:email profile)
                :attachments attachments

                :feedback-subject (:subject params)
                :feedback-type (:type params "not-specified")
                :feedback-content (:content params)
                :feedback-error-href (:error-href params)
                :profile profile})
    nil))
