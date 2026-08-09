;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.common.ai.registry
  "Pure helpers for resolving semantic components to Penpot components and
  code symbols. Registry persistence is deliberately left to the file/account
  integration layer."
  (:require
   [app.common.schema :as sm]))

(def schema:entry
  [:map {:closed true}
   [:id :string]
   [:name :string]
   [:penpot
    [:map {:closed false}
     [:componentId :string]
     [:fileId {:optional true} :string]
     [:variantSchema {:optional true} [:map-of :string [:vector :string]]]]]
   [:code
    [:map {:closed false}
     [:framework [:= "react"]]
     [:importPath :string]
     [:exportName :string]
     [:propSchema {:optional true} :map]]]
   [:slots {:optional true} [:vector :map]]
   [:defaults {:optional true} :map]])

(defn valid-entry?
  [entry]
  (sm/valid? schema:entry entry))

(defn build-registry
  [entries]
  (reduce
   (fn [registry entry]
     (if (valid-entry? entry)
       (assoc registry (:id entry) entry)
       registry))
   {}
   entries))

(defn resolve-entry
  [registry registry-id]
  (get registry registry-id))

(defn validate-component-reference
  [registry {:keys [registry-id variant props]}]
  (if-let [entry (resolve-entry registry registry-id)]
    (let [variant-schema (get-in entry [:penpot :variantSchema] {})
          invalid-variants
          (reduce-kv
           (fn [result property value]
             (if (contains? (set (get variant-schema (name property) [])) value)
               result
               (conj result {:property property :value value})))
           []
           (or variant {}))]
      {:valid? (empty? invalid-variants)
       :entry entry
       :props (merge (:defaults entry) props)
       :errors (mapv #(assoc % :code :invalid-component-variant) invalid-variants)})
    {:valid? false
     :errors [{:code :component-not-registered
               :registry-id registry-id}]}))
