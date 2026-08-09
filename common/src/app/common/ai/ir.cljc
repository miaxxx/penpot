;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.common.ai.ir
  "Canonical, deterministic intermediate representation helpers for AI design
  documents and patches. The IR is an exchange/compilation format and is not a
  second source of truth alongside Penpot shapes."
  (:require
   [clojure.string :as str]))

(defn normalize-kind
  [kind]
  (cond
    (keyword? kind) kind
    (string? kind) (-> kind
                       (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
                       str/lower-case
                       keyword)
    :else nil))

(defn normalize-operation
  [op]
  (cond
    (keyword? op) op
    (string? op) (-> op
                     (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
                     str/lower-case
                     keyword)
    :else nil))

(defn walk-nodes
  "Returns a stable pre-order sequence of every node in an IR tree."
  [root]
  (tree-seq #(seq (:children %)) :children root))

(defn index-nodes
  [root]
  (into {} (map (juxt :id identity)) (walk-nodes root)))

(defn node-count
  [root]
  (count (walk-nodes root)))

(defn duplicate-node-ids
  [root]
  (->> (walk-nodes root)
       (map :id)
       frequencies
       (keep (fn [[id count]] (when (> count 1) id)))
       sort
       vec))

(defn semantic-metadata
  [{:keys [id kind component metadata]}]
  (merge
   {:semantic-id id
    :kind kind
    :dsl-version "1.0"
    :generated-by :ai}
   (when-let [registry-id (or (get-in component [:registry-id])
                              (get-in component [:registryId])
                              (when (string? component) component))]
     {:registry-id registry-id})
   metadata))
