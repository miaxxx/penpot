;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.common.ai.validation
  "Semantic validation that complements the structural Malli schemas."
  (:require
   [app.common.ai.ir :as ir]
   [app.common.ai.normalize :as normalize]
   [app.common.ai.schema :as schema]
   [app.common.schema :as sm]))

(def max-document-nodes 500)
(def max-tree-depth 32)

(defn- error
  [code path message & [data]]
  (cond-> {:code code :path path :message message}
    data (assoc :data data)))

(defn- validate-node
  [node path depth]
  (let [kind (ir/normalize-kind (:kind node))
        structural-errors
        (when-not (sm/valid? schema/schema:node node)
          [(error :invalid-node path "Node does not match the authoring DSL schema"
                  (sm/simplify (sm/explain schema/schema:node node)))])
        depth-errors
        (when (> depth max-tree-depth)
          [(error :tree-too-deep path "Node tree exceeds the maximum supported depth"
                  {:max-depth max-tree-depth})])
        kind-errors
        (when-not (contains? schema/supported-node-kinds kind)
          [(error :unsupported-node-kind (conj path :kind)
                  "Node kind is not supported by DSL v1"
                  {:kind (:kind node)})])
        component-errors
        (when (and (contains? #{:component :component-instance} kind)
                   (nil? (:component node)))
          [(error :missing-component-reference (conj path :component)
                  "Component nodes must reference the Component Registry")])
        child-errors
        (mapcat (fn [[index child]]
                  (validate-node child (conj path :children index) (inc depth)))
                (map-indexed vector (or (:children node) [])))]
    (vec (concat structural-errors depth-errors kind-errors component-errors child-errors))))

(defn validate-document
  [dsl]
  (let [schema-errors
        (when-not (schema/valid-document? dsl)
          [(error :invalid-document [] "Document DSL does not match schema"
                  (schema/explain-document dsl))])
        node-errors
        (when (empty? schema-errors)
          (validate-node (:document dsl) [:document] 0))
        normalized
        (when (empty? (concat schema-errors node-errors))
          (normalize/normalize-document dsl))
        root (:root normalized)
        count-errors
        (when (and root (> (ir/node-count root) max-document-nodes))
          [(error :node-limit-exceeded [:document]
                  "Document exceeds the maximum node count"
                  {:max-nodes max-document-nodes
                   :node-count (ir/node-count root)})])
        duplicate-errors
        (when-let [duplicates (seq (and root (ir/duplicate-node-ids root)))]
          [(error :duplicate-node-id [:document]
                  "Every AI semantic node id must be unique"
                  {:ids duplicates})])
        errors (vec (concat schema-errors node-errors count-errors duplicate-errors))]
    {:valid? (empty? errors)
     :errors errors
     :warnings []
     :ir (when (empty? errors) normalized)}))

(defn- required-operation-fields
  [op]
  (case op
    :create #{:node}
    :insert #{:parent-id :node}
    :set #{:node-id :path :value}
    :unset #{:node-id :path}
    :move #{:node-id :parent-id}
    :remove #{:node-id}
    :duplicate #{:node-id}
    :replace #{:node-id :node}
    :bind-token #{:node-id :path :value}
    :set-variant #{:node-id :value}
    :replace-component #{:node-id :value}
    :batch #{:operations}
    #{}))

(defn- validate-operation
  [operation path]
  (let [op (:op operation)
        unsupported
        (when-not (contains? schema/supported-patch-operations op)
          [(error :unsupported-patch-operation (conj path :op)
                  "Patch operation is not supported by DSL v1"
                  {:op op})])
        required (required-operation-fields op)
        missing (remove #(contains? operation %) required)
        missing-errors
        (mapv #(error :missing-operation-field (conj path %)
                      "Patch operation is missing a required field"
                      {:op op :field %})
              missing)
        nested-errors
        (when (= op :batch)
          (mapcat (fn [[index child]]
                    (validate-operation child (conj path :operations index)))
                  (map-indexed vector (:operations operation))))]
    (vec (concat unsupported missing-errors nested-errors))))

(defn validate-patch
  [dsl]
  (let [schema-errors
        (when-not (schema/valid-patch? dsl)
          [(error :invalid-patch [] "Patch DSL does not match schema"
                  (schema/explain-patch dsl))])
        normalized
        (when (empty? schema-errors)
          (normalize/normalize-patch dsl))
        operation-errors
        (when normalized
          (mapcat (fn [[index operation]]
                    (validate-operation operation [:operations index]))
                  (map-indexed vector (:operations normalized))))
        scope-errors
        (when (and normalized
                   (= :selection (get-in normalized [:scope :type]))
                   (nil? (get-in normalized [:scope :root-id])))
          [(error :missing-scope-root [:scope :root-id]
                  "Selection-scoped patches require a root id")])
        errors (vec (concat schema-errors operation-errors scope-errors))]
    {:valid? (empty? errors)
     :errors errors
     :warnings []
     :ir (when (empty? errors) normalized)}))
