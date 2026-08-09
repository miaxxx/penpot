;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.common.ai.patch
  "Pure, scoped Patch DSL execution over native Penpot object maps.

  This layer does not persist changes. It validates targets and paths, applies
  operations to an in-memory object graph and produces a deterministic diff.
  The frontend native-change adapter is the only layer allowed to commit."
  (:require
   [app.common.ai.canvas :as canvas]
   [clojure.string :as str]))

(def max-patch-operations 200)

(def ^:private protected-root-attrs
  #{:id :type :parent-id :frame-id :shapes :selrect :points
    :transform :transform-inverse})

(def ^:private path-aliases
  {"name" [:name]
   "geometry.x" [:x]
   "geometry.y" [:y]
   "geometry.width" [:width]
   "geometry.height" [:height]
   "geometry.rotation" [:rotation]
   "style.opacity" [:opacity]
   "style.fill" [:fills 0 :fill-color]
   "style.fillOpacity" [:fills 0 :fill-opacity]
   "style.stroke" [:strokes 0 :stroke-color]
   "style.strokeWidth" [:strokes 0 :stroke-width]
   "style.radius" [:ai/radius]
   "layout.type" [:layout]
   "layout.direction" [:layout-flex-dir]
   "layout.gap" [:ai/layout-gap]
   "layout.padding" [:ai/layout-padding]
   "layout.align" [:layout-align-items]
   "layout.justify" [:layout-justify-content]
   "layout.width" [:ai/layout-width]
   "layout.height" [:ai/layout-height]
   "visible" [:ai/visible]
   "locked" [:locked]
   "hidden" [:hidden]
   "interactions" [:interactions]
   "tokens" [:applied-tokens]
   "pluginData" [:plugin-data]})

(defn- error
  [code operation-index message & [data]]
  (cond-> {:code code
           :operation-index operation-index
           :message message}
    data (assoc :data data)))

(defn- normalize-keyword
  [value]
  (cond
    (keyword? value) value
    (string? value) (-> value
                        (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
                        str/lower-case
                        keyword)
    :else value))

(defn- normalize-operation
  [operation]
  (reduce-kv
   (fn [result key value]
     (assoc result (normalize-keyword key)
            (if (= (normalize-keyword key) :op)
              (normalize-keyword value)
              value)))
   {}
   operation))

(defn- parse-path-segment
  [segment]
  (if (re-matches #"\d+" segment)
    #?(:clj (Long/parseLong segment)
       :cljs (js/parseInt segment 10))
    (normalize-keyword segment)))

(defn resolve-path
  [path]
  (let [path (if (keyword? path) (name path) (str path))]
    (or (get path-aliases path)
        (when (str/starts-with? path "penpot.")
          (->> (subs path 7)
               (str/split #"\.")
               (mapv parse-path-segment))))))

(defn- safe-path?
  [path]
  (and (seq path)
       (not (contains? protected-root-attrs (first path)))))

(defn- child-frame-id
  [objects parent-id]
  (let [parent (get objects parent-id)]
    (if (= :frame (:type parent))
      parent-id
      (:frame-id parent))))

(defn- remove-child
  [parent child-id]
  (update parent :shapes
          (fn [children]
            (vec (remove #(= child-id %) (or children []))))))

(defn- insert-child
  [parent child-id index]
  (let [children (vec (remove #(= child-id %) (or (:shapes parent) [])))
        index (max 0 (min (or index (count children)) (count children)))]
    (assoc parent :shapes
           (vec (concat (subvec children 0 index)
                        [child-id]
                        (subvec children index))))))

(defn- descendants-set
  [objects id]
  (set (canvas/descendant-ids objects id)))

(defn- within-scope?
  [scope-ids id]
  (contains? scope-ids id))

(defn- resolve-target
  [snapshot operation key]
  (canvas/resolve-id snapshot (get operation key)))

(defn- coerce-layout-value
  [path value]
  (case (first path)
    :layout (normalize-keyword value)
    :layout-flex-dir (case (normalize-keyword value)
                       :vertical :column
                       :horizontal :row
                       (normalize-keyword value))
    :layout-align-items (normalize-keyword value)
    :layout-justify-content (normalize-keyword value)
    value))

(defn- set-semantic-value
  [shape path value]
  (case (first path)
    :ai/radius
    (let [value (double value)]
      (assoc shape :r1 value :r2 value :r3 value :r4 value))

    :ai/layout-gap
    (let [value (double value)]
      (assoc shape
             :layout-gap-type :simple
             :layout-gap {:row-gap value :column-gap value}))

    :ai/layout-padding
    (let [padding (if (number? value)
                    {:top value :right value :bottom value :left value}
                    value)]
      (assoc shape
             :layout-padding-type :multiple
             :layout-padding {:p1 (or (:top padding) (get padding "top") 0)
                              :p2 (or (:right padding) (get padding "right") 0)
                              :p3 (or (:bottom padding) (get padding "bottom") 0)
                              :p4 (or (:left padding) (get padding "left") 0)}))

    :ai/layout-width
    (case (normalize-keyword value)
      :fill (assoc shape :layout-item-h-sizing :fill)
      :hug (assoc shape :layout-item-h-sizing :auto)
      :auto (assoc shape :layout-item-h-sizing :auto)
      (if (number? value)
        (assoc shape :width value :layout-item-h-sizing :fix)
        shape))

    :ai/layout-height
    (case (normalize-keyword value)
      :fill (assoc shape :layout-item-v-sizing :fill)
      :hug (assoc shape :layout-item-v-sizing :auto)
      :auto (assoc shape :layout-item-v-sizing :auto)
      (if (number? value)
        (assoc shape :height value :layout-item-v-sizing :fix)
        shape))

    :ai/visible
    (assoc shape :hidden (not (boolean value)))

    (assoc-in shape path (coerce-layout-value path value))))

(defn- unset-semantic-value
  [shape path]
  (case (first path)
    :ai/radius (dissoc shape :r1 :r2 :r3 :r4)
    :ai/layout-gap (dissoc shape :layout-gap :layout-gap-type)
    :ai/layout-padding (dissoc shape :layout-padding :layout-padding-type)
    :ai/layout-width (dissoc shape :layout-item-h-sizing)
    :ai/layout-height (dissoc shape :layout-item-v-sizing)
    :ai/visible (dissoc shape :hidden)
    (update-in shape (butlast path) dissoc (last path))))

(defn- apply-set
  [state snapshot scope-ids operation operation-index unset?]
  (let [id (resolve-target snapshot operation :node-id)
        path (resolve-path (:path operation))]
    (cond
      (nil? id)
      (update state :errors conj
              (error :target-not-found operation-index "Patch target does not exist"
                     {:node-id (:node-id operation)}))

      (not (within-scope? scope-ids id))
      (update state :errors conj
              (error :out-of-scope operation-index "Patch target is outside the declared scope"
                     {:node-id (:node-id operation)}))

      (nil? path)
      (update state :errors conj
              (error :unsupported-path operation-index "Patch path is not supported"
                     {:path (:path operation)}))

      (not (safe-path? path))
      (update state :errors conj
              (error :protected-path operation-index "Core shape identity/tree fields require an explicit operation"
                     {:path (:path operation)}))

      :else
      (let [before (get-in state [:objects id])
            after (if unset?
                    (unset-semantic-value before path)
                    (set-semantic-value before path (:value operation)))]
        (-> state
            (assoc-in [:objects id] after)
            (update :modified conj id))))))

(defn- apply-move
  [state snapshot scope-ids operation operation-index]
  (let [id (resolve-target snapshot operation :node-id)
        parent-id (resolve-target snapshot operation :parent-id)
        objects (:objects state)
        old-parent-id (:parent-id (get objects id))]
    (cond
      (or (nil? id) (nil? parent-id))
      (update state :errors conj
              (error :move-target-not-found operation-index "Move target or parent does not exist"
                     {:node-id (:node-id operation)
                      :parent-id (:parent-id operation)}))

      (or (not (within-scope? scope-ids id))
          (not (within-scope? scope-ids parent-id)))
      (update state :errors conj
              (error :out-of-scope operation-index "Move target and destination must be inside scope"))

      (= id parent-id)
      (update state :errors conj
              (error :parent-cycle operation-index "A node cannot be its own parent"))

      (contains? (descendants-set objects id) parent-id)
      (update state :errors conj
              (error :parent-cycle operation-index "Move would create a descendant cycle"))

      (not (contains? (get objects parent-id) :shapes))
      (update state :errors conj
              (error :invalid-parent operation-index "Destination does not accept children"
                     {:parent-id (:parent-id operation)}))

      :else
      (let [objects (cond-> objects
                      (get objects old-parent-id)
                      (update old-parent-id remove-child id)

                      :always
                      (update parent-id insert-child id (:index operation))

                      :always
                      (update id assoc
                              :parent-id parent-id
                              :frame-id (child-frame-id objects parent-id)))]
        (-> state
            (assoc :objects objects)
            (update :moved conj id)
            (update :modified into (remove nil? [old-parent-id parent-id])))))))

(defn- apply-remove
  [state snapshot scope-ids scope-root-id operation operation-index]
  (let [id (resolve-target snapshot operation :node-id)
        objects (:objects state)]
    (cond
      (nil? id)
      (update state :errors conj
              (error :target-not-found operation-index "Remove target does not exist"
                     {:node-id (:node-id operation)}))

      (not (within-scope? scope-ids id))
      (update state :errors conj
              (error :out-of-scope operation-index "Remove target is outside the declared scope"))

      (= id scope-root-id)
      (update state :errors conj
              (error :remove-scope-root operation-index "The active scope root cannot be removed"))

      :else
      (let [ids (canvas/descendant-ids objects id)
            id-set (set ids)
            parent-id (:parent-id (get objects id))
            objects (cond-> (apply dissoc objects ids)
                      (get objects parent-id)
                      (update parent-id remove-child id))]
        (-> state
            (assoc :objects objects)
            (update :removed into id-set)
            (update :modified conj parent-id))))))

(declare apply-operation)

(defn- apply-batch
  [state snapshot scope-ids scope-root-id operation operation-index]
  (reduce-kv
   (fn [result child-index child]
     (apply-operation result snapshot scope-ids scope-root-id
                      (normalize-operation child)
                      [operation-index child-index]))
   state
   (vec (:operations operation))))

(defn apply-operation
  [state snapshot scope-ids scope-root-id operation operation-index]
  (case (:op operation)
    :set (apply-set state snapshot scope-ids operation operation-index false)
    :unset (apply-set state snapshot scope-ids operation operation-index true)
    :bind-token (apply-set state snapshot scope-ids operation operation-index false)
    :set-variant (apply-set state snapshot scope-ids
                            (assoc operation :path "penpot.variant-properties")
                            operation-index false)
    :move (apply-move state snapshot scope-ids operation operation-index)
    :remove (apply-remove state snapshot scope-ids scope-root-id operation operation-index)
    :batch (apply-batch state snapshot scope-ids scope-root-id operation operation-index)
    (update state :errors conj
            (error :compiler-required operation-index
                   "Operation requires the Design Node compiler"
                   {:op (:op operation)}))))

(defn- changed-attributes
  [before after]
  (->> (concat (keys before) (keys after))
       set
       (filter #(not= (get before %) (get after %)))
       sort
       vec))

(defn diff-objects
  [before after]
  (let [before-ids (set (keys before))
        after-ids (set (keys after))
        created (clojure.set/difference after-ids before-ids)
        removed (clojure.set/difference before-ids after-ids)
        common (clojure.set/intersection before-ids after-ids)
        modified (into {}
                       (keep (fn [id]
                               (let [attrs (changed-attributes (get before id) (get after id))]
                                 (when (seq attrs) [id attrs]))))
                       common)
        moved (into #{}
                    (filter #(not= (:parent-id (get before %))
                                   (:parent-id (get after %))))
                    common)]
    {:created created
     :removed removed
     :modified modified
     :moved moved
     :counts {:created (count created)
              :removed (count removed)
              :modified (count modified)
              :moved (count moved)}}))

(defn apply-patch
  "Validates and applies operations that target existing Penpot nodes. Insert,
  create, duplicate, replace and replace-component are delegated to the Design
  Node compiler because they require native shape construction/registry access."
  [snapshot patch]
  (let [operations (mapv normalize-operation (:operations patch))
        scope (or (:scope patch) (:scope snapshot))
        scope-ids (canvas/scope-ids (:penpot snapshot) snapshot scope)
        root-ref (or (:root-id scope) (:rootId scope))
        scope-root-id (canvas/resolve-id snapshot root-ref)
        initial {:objects (:penpot snapshot)
                 :errors []
                 :warnings []
                 :modified #{}
                 :moved #{}
                 :removed #{}}
        result (if (> (count operations) max-patch-operations)
                 (update initial :errors conj
                         (error :operation-limit nil "Patch exceeds operation limit"
                                {:max max-patch-operations}))
                 (reduce-kv
                  (fn [state index operation]
                    (apply-operation state snapshot scope-ids scope-root-id operation index))
                  initial
                  operations))
        diff (diff-objects (:penpot snapshot) (:objects result))]
    {:valid? (empty? (:errors result))
     :errors (:errors result)
     :warnings (:warnings result)
     :objects (:objects result)
     :diff diff}))
