;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.common.ai.capability
  "Static Compile Mode readiness checks for canonical Design IR."
  (:require
   [app.common.ai.ir :as ir]
   [clojure.string :as str]))

(def ^:private semantic-layout-kinds
  #{:page :section :frame :stack :grid :navigation :modal :list :table :form
    :card :component :component-instance})

(defn token-reference?
  [value]
  (and (string? value)
       (str/starts-with? value "{")
       (str/ends-with? value "}")))

(defn- issue
  [severity code node-id path message]
  {:severity severity
   :code code
   :node-id node-id
   :path path
   :message message})

(defn- analyze-node
  [registry node]
  (let [node-id (:id node)
        kind (:kind node)
        layout-type (get-in node [:layout :type])
        registry-id (get-in node [:component :registry-id])
        fill (get-in node [:style :fill])
        stroke (get-in node [:style :stroke])
        shadow (get-in node [:style :shadow])]
    (cond-> []
      (and (contains? semantic-layout-kinds kind)
           (= "absolute" layout-type))
      (conj (issue :warning :absolute-layout node-id [:layout :type]
                   "Absolute layout reduces deterministic code compatibility."))

      (and (contains? #{:component :component-instance} kind)
           (nil? (get registry registry-id)))
      (conj (issue :error :component-not-registered node-id [:component :registry-id]
                   "Compile Mode requires a registered component mapping."))

      (and (string? fill)
           (not (token-reference? fill)))
      (conj (issue :warning :hardcoded-fill node-id [:style :fill]
                   "Bind fill colors to a design token for stable code output."))

      (and (string? stroke)
           (not (token-reference? stroke)))
      (conj (issue :warning :hardcoded-stroke node-id [:style :stroke]
                   "Bind stroke colors to a design token for stable code output."))

      (and (string? shadow)
           (not (token-reference? shadow)))
      (conj (issue :warning :hardcoded-shadow node-id [:style :shadow]
                   "Bind shadows to a design token for stable code output.")))))

(defn analyze
  "Returns deterministic issues and a coarse readiness score for a Design IR.
  Errors block Compile Mode; warnings lower the score but remain repairable."
  [registry root]
  (let [issues (->> (ir/walk-nodes root)
                    (mapcat #(analyze-node registry %))
                    vec)
        errors (count (filter #(= :error (:severity %)) issues))
        warnings (count (filter #(= :warning (:severity %)) issues))
        score (max 0 (- 100 (* errors 25) (* warnings 5)))]
    {:compatible? (zero? errors)
     :score score
     :issues issues
     :counts {:errors errors
              :warnings warnings}}))
