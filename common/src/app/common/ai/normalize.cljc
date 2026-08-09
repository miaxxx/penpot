;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.common.ai.normalize
  "Transforms validated authoring/patch DSL values into the canonical IR."
  (:require
   [app.common.ai.ir :as ir]))

(defn- get-key
  [value camel kebab]
  (if (contains? value camel)
    (get value camel)
    (get value kebab)))

(defn- normalize-component
  [component]
  (cond
    (string? component)
    {:registry-id component}

    (map? component)
    {:registry-id (get-key component :registryId :registry-id)
     :variant (or (:variant component) {})
     :props (or (:props component) {})}

    :else nil))

(defn normalize-node
  [node]
  (let [kind      (ir/normalize-kind (:kind node))
        component (normalize-component (:component node))
        children  (mapv normalize-node (or (:children node) []))]
    (cond->
     {:id (:id node)
      :kind kind
      :name (:name node)
      :props (or (:props node) {})
      :layout (or (:layout node) {})
      :style (or (:style node) {})
      :responsive (or (:responsive node) {})
      :bindings (or (:bindings node) {})
      :metadata (merge {:generated-by-ai true
                        :semantic-role (some-> kind name)
                        :dsl-version "1.0"}
                       (:metadata node))
      :children children}
      component (assoc :component component)
      (nil? (:name node)) (dissoc :name))))

(defn normalize-document
  [dsl]
  {:dsl-version (get-key dsl :dslVersion :dsl-version)
   :root (normalize-node (:document dsl))})

(defn- normalize-patch-node
  [node]
  (when node
    (normalize-node node)))

(defn normalize-patch-operation
  [operation]
  (cond->
   {:op (ir/normalize-operation (:op operation))}
    (:nodeId operation) (assoc :node-id (:nodeId operation))
    (:node-id operation) (assoc :node-id (:node-id operation))
    (:parentId operation) (assoc :parent-id (:parentId operation))
    (:parent-id operation) (assoc :parent-id (:parent-id operation))
    (contains? operation :index) (assoc :index (:index operation))
    (:path operation) (assoc :path (:path operation))
    (contains? operation :value) (assoc :value (:value operation))
    (:node operation) (assoc :node (normalize-patch-node (:node operation)))
    (:operations operation)
    (assoc :operations (mapv normalize-patch-operation (:operations operation)))))

(defn normalize-patch
  [dsl]
  {:dsl-version (get-key dsl :dslVersion :dsl-version)
   :base-revision (get-key dsl :baseRevision :base-revision)
   :scope (let [scope (:scope dsl)]
            {:type (some-> (:type scope) keyword)
             :root-id (get-key scope :rootId :root-id)
             :page-id (get-key scope :pageId :page-id)
             :component-id (get-key scope :componentId :component-id)})
   :operations (mapv normalize-patch-operation (:operations dsl))})
