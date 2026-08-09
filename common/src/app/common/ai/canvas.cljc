;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.common.ai.canvas
  "Lossless local Penpot canvas snapshots plus bounded model-facing context.

  A snapshot keeps the original Penpot shape maps for compilation, revision
  checks and conflict handling. `compact-context` intentionally emits a smaller
  semantic representation so a provider does not receive an entire file by
  default."
  (:require
   [clojure.string :as str]))

(def snapshot-version 1)
(def default-child-depth 5)
(def default-parent-depth 2)
(def default-node-limit 500)

(def ^:private geometry-keys
  [:x :y :width :height :rotation :flip-x :flip-y :selrect :points])

(def ^:private layout-keys
  [:layout :layout-flex-dir :layout-gap-type :layout-gap
   :layout-align-items :layout-align-content :layout-justify-items
   :layout-justify-content :layout-wrap-type :layout-padding-type
   :layout-padding :layout-grid-dir :layout-grid-columns :layout-grid-rows
   :layout-grid-cells :layout-item-margin :layout-item-margin-type
   :layout-item-h-sizing :layout-item-v-sizing :layout-item-min-h
   :layout-item-max-h :layout-item-min-w :layout-item-max-w
   :layout-item-align-self :layout-item-absolute :layout-item-z-index])

(def ^:private style-keys
  [:fills :strokes :opacity :blend-mode :r1 :r2 :r3 :r4 :shadow :blur
   :background-blur :masked-group :show-content :hide-fill-on-export])

(def ^:private component-keys
  [:component-id :component-file :component-root :main-instance :remote-synced
   :shape-ref :variant-id :variant-name :variant-properties :touched])

(def ^:private behavior-keys
  [:constraints-h :constraints-v :fixed-scroll :interactions :exports :grids
   :blocked :locked :hidden :collapsed :hide-in-viewer])

(def ^:private binding-keys
  [:applied-tokens :plugin-data])

(defn semantic-id
  "Returns the stable AI semantic id when present, otherwise the Penpot UUID
  string. Shape plugin data follows Penpot's keyword -> string map contract."
  [shape]
  (or (get-in shape [:plugin-data :ai "semantic-id"])
      (some-> (:id shape) str)))

(defn semantic-kind
  [shape]
  (cond
    (and (:component-id shape) (:main-instance shape)) :component
    (:component-id shape) :component-instance
    (= :frame (:type shape))
    (case (:layout shape)
      :grid :grid
      :flex :stack
      :frame)
    (= :group (:type shape)) :group
    (= :bool (:type shape)) :boolean-shape
    (= :rect (:type shape)) :shape
    (= :circle (:type shape)) :shape
    (= :path (:type shape)) :path
    (= :svg-raw (:type shape)) :svg
    (= :image (:type shape)) :image
    (= :text (:type shape)) :text
    :else :unknown))

(defn- collect-text-values
  [value]
  (cond
    (map? value)
    (concat
     (when (string? (:text value)) [(:text value)])
     (mapcat collect-text-values (vals (dissoc value :text))))

    (sequential? value)
    (mapcat collect-text-values value)

    :else []))

(defn plain-text
  [shape]
  (->> (:content shape)
       collect-text-values
       (remove str/blank?)
       (str/join "\n")
       not-empty))

(defn- select-present
  [shape keys]
  (reduce (fn [result key]
            (if (contains? shape key)
              (assoc result key (get shape key))
              result))
          {}
          keys))

(defn shape->node
  "Converts one native Penpot shape to a semantic node while retaining the
  complete original shape under `:penpot`."
  [shape]
  {:id (semantic-id shape)
   :penpot-id (:id shape)
   :kind (semantic-kind shape)
   :penpot-type (:type shape)
   :name (:name shape)
   :parent-id (:parent-id shape)
   :frame-id (:frame-id shape)
   :children (vec (or (:shapes shape) []))
   :text (plain-text shape)
   :geometry (select-present shape geometry-keys)
   :layout (select-present shape layout-keys)
   :style (select-present shape style-keys)
   :component (select-present shape component-keys)
   :behavior (select-present shape behavior-keys)
   :bindings (select-present shape binding-keys)
   :penpot (into {} shape)})

(defn build-index
  "Builds semantic id -> Penpot UUID lookup. Duplicate semantic ids are
  reported separately and never silently replace the first mapping."
  [objects]
  (reduce-kv
   (fn [{:keys [index duplicates] :as result} id shape]
     (let [semantic (semantic-id shape)]
       (if (contains? index semantic)
         (assoc result :duplicates (conj duplicates semantic))
         (assoc result :index (assoc index semantic id)))))
   {:index {} :duplicates #{}}
   objects))

(defn resolve-id
  [snapshot id]
  (cond
    (nil? id) nil
    (contains? (:penpot snapshot) id) id
    :else (get (:index snapshot) (str id))))

(defn descendant-ids
  "Returns root and all descendants. Cycles and missing references terminate
  safely and are recorded by `integrity-report`."
  [objects root-id]
  (loop [queue (if root-id [root-id] [])
         seen #{}
         result []]
    (if-let [id (first queue)]
      (if (contains? seen id)
        (recur (subvec (vec queue) 1) seen result)
        (let [children (vec (or (:shapes (get objects id)) []))]
          (recur (into (subvec (vec queue) 1) children)
                 (conj seen id)
                 (conj result id))))
      result)))

(defn parent-ids
  [objects id max-depth]
  (loop [current-id id
         depth 0
         seen #{}
         result []]
    (let [parent-id (:parent-id (get objects current-id))]
      (if (or (nil? parent-id)
              (contains? seen parent-id)
              (>= depth max-depth)
              (nil? (get objects parent-id)))
        result
        (recur parent-id
               (inc depth)
               (conj seen parent-id)
               (conj result parent-id))))))

(defn integrity-report
  [objects]
  (let [missing-children
        (reduce-kv
         (fn [errors parent-id shape]
           (into errors
                 (keep (fn [child-id]
                         (when-not (contains? objects child-id)
                           {:code :missing-child
                            :parent-id parent-id
                            :child-id child-id})))
                 (:shapes shape)))
         []
         objects)

        missing-parents
        (reduce-kv
         (fn [errors id shape]
           (let [parent-id (:parent-id shape)]
             (cond-> errors
               (and parent-id
                    (not= id parent-id)
                    (not (contains? objects parent-id)))
               (conj {:code :missing-parent
                      :id id
                      :parent-id parent-id}))))
         []
         objects)

        cycles
        (reduce-kv
         (fn [errors id _]
           (loop [current id
                  seen #{}]
             (let [parent (:parent-id (get objects current))]
               (cond
                 (nil? parent) errors
                 (= parent current) errors
                 (contains? seen parent)
                 (conj errors {:code :parent-cycle :id id :at parent})
                 (nil? (get objects parent)) errors
                 :else (recur parent (conj seen current))))))
         []
         objects)]
    {:valid? (empty? (concat missing-children missing-parents cycles))
     :errors (vec (concat missing-children missing-parents cycles))}))

(defn scope-ids
  "Returns Penpot UUIDs readable/writable by a scope. Selection and component
  scopes include descendants; page scope includes all objects."
  [objects snapshot {:keys [type root-id rootId selection-ids selectionIds]}]
  (let [type (keyword (name (or type :selection)))
        root-ref (or root-id rootId)
        selected (or selection-ids selectionIds [])
        roots (case type
                :page (keys objects)
                :component [(resolve-id snapshot root-ref)]
                :selection (if root-ref
                             [(resolve-id snapshot root-ref)]
                             (keep #(resolve-id snapshot %) selected))
                [])]
    (if (= type :page)
      (set (keys objects))
      (into #{} (mapcat #(descendant-ids objects %) (remove nil? roots))))))

(defn build-snapshot
  [{:keys [file-id page-id revision objects scope]
    :or {objects {} scope {:type :page}}}]
  (let [{:keys [index duplicates]} (build-index objects)
        nodes (into {} (map (fn [[id shape]] [id (shape->node shape)])) objects)
        snapshot {:snapshot-version snapshot-version
                  :file-id file-id
                  :page-id page-id
                  :revision revision
                  :scope scope
                  :penpot objects
                  :nodes nodes
                  :index index
                  :duplicate-semantic-ids duplicates}
        allowed (scope-ids objects snapshot scope)]
    (assoc snapshot
           :scope-ids allowed
           :integrity (integrity-report objects))))

(defn- compact-node
  [node]
  (cond->
   {:id (:id node)
    :penpot-id (some-> (:penpot-id node) str)
    :kind (:kind node)
    :penpot-type (:penpot-type node)
    :name (:name node)
    :parent-id (some-> (:parent-id node) str)
    :frame-id (some-> (:frame-id node) str)
    :children (mapv str (:children node))
    :geometry (:geometry node)
    :layout (:layout node)
    :style (:style node)
    :component (:component node)
    :behavior (:behavior node)
    :tokens (get-in node [:bindings :applied-tokens])}
    (:text node) (assoc :text (:text node))))

(defn compact-context
  "Builds a bounded model-facing context from a local snapshot. Parent context
  is included separately so the model understands constraints without gaining
  write access outside the declared scope."
  ([snapshot] (compact-context snapshot {}))
  ([snapshot {:keys [node-limit parent-depth]
              :or {node-limit default-node-limit
                   parent-depth default-parent-depth}}]
   (let [objects (:penpot snapshot)
         scoped (take node-limit (:scope-ids snapshot))
         parent-set (into #{} (mapcat #(parent-ids objects % parent-depth)) scoped)
         nodes (:nodes snapshot)]
     {:snapshot-version snapshot-version
      :file-id (some-> (:file-id snapshot) str)
      :page-id (some-> (:page-id snapshot) str)
      :revision (:revision snapshot)
      :scope (:scope snapshot)
      :scope-node-count (count (:scope-ids snapshot))
      :truncated? (> (count (:scope-ids snapshot)) node-limit)
      :nodes (mapv #(compact-node (get nodes %)) scoped)
      :parents (mapv #(compact-node (get nodes %)) parent-set)
      :integrity (:integrity snapshot)})))

(defn summary
  [snapshot]
  (let [scope-nodes (keep #(get-in snapshot [:nodes %]) (:scope-ids snapshot))]
    {:node-count (count scope-nodes)
     :text-node-count (count (filter :text scope-nodes))
     :component-count (count (filter #(seq (:component %)) scope-nodes))
     :token-bound-count (count (filter #(seq (get-in % [:bindings :applied-tokens])) scope-nodes))
     :interaction-count (reduce + 0 (map #(count (get-in % [:behavior :interactions])) scope-nodes))
     :kinds (frequencies (map :kind scope-nodes))
     :integrity-valid? (get-in snapshot [:integrity :valid?])}))
