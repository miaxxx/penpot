;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.ai.policy
  "Unified identity, permission, scope and revision policy for AI operations."
  (:require
   [app.common.exceptions :as ex]
   [app.config :as cf]
   [app.rpc.commands.files :as files]))

(def valid-scope-types #{:selection :page :component})
(def valid-origins #{:internal :rpc :mcp :plugin})

(defn ensure-enabled!
  []
  (when-not (contains? cf/flags :ai-design-agent)
    (ex/raise :type :restriction
              :code :ai-design-agent-disabled
              :hint "AI Design Agent is disabled")))

(defn ensure-origin!
  [origin]
  (let [origin (keyword origin)]
    (when-not (contains? valid-origins origin)
      (ex/raise :type :validation
                :code :invalid-ai-origin
                :hint "AI operation origin is not supported"))
    origin))

(defn ensure-read!
  [cfg profile-id file-id]
  (when-not profile-id
    (ex/raise :type :authentication
              :code :authentication-required
              :hint "authenticated profile required"))
  (files/check-read-permissions! cfg profile-id file-id)
  true)

(defn ensure-edit!
  [cfg profile-id file-id]
  (when-not profile-id
    (ex/raise :type :authentication
              :code :authentication-required
              :hint "authenticated profile required"))
  (files/check-edition-permissions! cfg profile-id file-id)
  true)

(defn current-revision
  [cfg file-id]
  (:revn (files/get-minimal-file cfg file-id)))

(defn ensure-revision!
  [cfg file-id expected]
  (let [current (current-revision cfg file-id)]
    (when-not (= (long expected) (long current))
      (ex/raise :type :validation
                :code :ai-proposal-revision-conflict
                :hint "proposal base revision does not match the current file"
                :expected expected
                :current current))
    current))

(defn- scope-type
  [scope]
  (some-> (or (:type scope) (get scope "type")) keyword))

(defn- scope-root
  [scope]
  (or (:root-id scope) (:rootId scope)
      (get scope "root-id") (get scope "rootId")))

(defn ensure-scope!
  [scope]
  (let [type (scope-type scope)
        root (scope-root scope)]
    (when-not (contains? valid-scope-types type)
      (ex/raise :type :validation
                :code :invalid-ai-scope
                :hint "scope type must be selection, page or component"))
    (when (and (contains? #{:selection :component} type)
               (nil? root)
               (empty? (or (:selection-ids scope)
                           (:selectionIds scope)
                           (get scope "selectionIds"))))
      (ex/raise :type :validation
                :code :missing-ai-scope-root
                :hint "selection and component scopes require a root or selection"))
    (assoc scope :type type)))

(defn ensure-owner!
  [profile-id proposal]
  (when-not (= profile-id (:profile-id proposal))
    ;; Preserve Penpot's not-found behavior so proposal existence is not leaked.
    (ex/raise :type :not-found
              :code :object-not-found
              :hint "not found"))
  proposal)
