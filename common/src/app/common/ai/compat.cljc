;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.common.ai.compat
  "Compatibility repair between semantic AI object graphs and native Penpot
  Shape invariants.

  The Patch DSL intentionally edits a small set of semantic paths. This module
  canonicalizes the resulting values before native Change generation so broad
  AI writes still respect Penpot's geometry, paint and frame ancestry model."
  (:require
   [app.common.types.shape :as cts]))

(def ^:private geometry-attrs
  #{:x :y :width :height :rotation :flip-x :flip-y})

(def ^:private rect-geometry-types
  #{:frame :group :rect :circle :text :image :svg-raw})

(defn- integer-keyed-map?
  [value]
  (and (map? value)
       (every? integer? (keys value))))

(defn- canonical-vector
  [value]
  (cond
    (vector? value) value
    (sequential? value) (vec value)
    (integer-keyed-map? value) (->> value (sort-by key) (mapv val))
    (nil? value) nil
    :else value))

(defn- canonical-fill
  [fill]
  (cond-> fill
    (and (map? fill)
         (or (:fill-color fill)
             (:fill-color-gradient fill)
             (:fill-image fill))
         (not (contains? fill :fill-opacity)))
    (assoc :fill-opacity 1)))

(defn- canonical-stroke
  [stroke]
  (cond-> stroke
    (and (map? stroke)
         (or (:stroke-color stroke)
             (:stroke-color-gradient stroke)
             (:stroke-image stroke))
         (not (contains? stroke :stroke-opacity)))
    (assoc :stroke-opacity 1)

    (and (map? stroke)
         (or (:stroke-color stroke)
             (:stroke-color-gradient stroke)
             (:stroke-image stroke))
         (not (contains? stroke :stroke-width)))
    (assoc :stroke-width 1)

    (and (map? stroke)
         (or (:stroke-color stroke)
             (:stroke-color-gradient stroke)
             (:stroke-image stroke))
         (not (contains? stroke :stroke-style)))
    (assoc :stroke-style :solid)

    (and (map? stroke)
         (or (:stroke-color stroke)
             (:stroke-color-gradient stroke)
             (:stroke-image stroke))
         (not (contains? stroke :stroke-alignment)))
    (assoc :stroke-alignment :inner)))

(defn- canonical-paints
  [shape]
  (cond-> shape
    (contains? shape :fills)
    (update :fills
            (fn [fills]
              (let [fills (canonical-vector fills)]
                (if (vector? fills)
                  (mapv canonical-fill fills)
                  fills))))

    (contains? shape :strokes)
    (update :strokes
            (fn [strokes]
              (let [strokes (canonical-vector strokes)]
                (if (vector? strokes)
                  (mapv canonical-stroke strokes)
                  strokes))))))

(defn- geometry-changed?
  [before after]
  (some #(not= (get before %) (get after %)) geometry-attrs))

(defn- rebuild-geometry
  [before shape]
  (if (and before
           (contains? rect-geometry-types (:type shape))
           (geometry-changed? before shape))
    (-> shape
        (dissoc :selrect :points)
        cts/setup-shape)
    shape))

(defn- containing-frame-id
  [objects id]
  (loop [current-id id
         seen #{}]
    (let [shape (get objects current-id)
          parent-id (:parent-id shape)
          parent (get objects parent-id)]
      (cond
        (nil? shape) nil
        (nil? parent) (:frame-id shape)
        (= current-id parent-id) (:frame-id shape)
        (contains? seen parent-id) (:frame-id shape)
        (= :frame (:type parent)) parent-id
        :else (recur parent-id (conj seen current-id))))))

(defn- descendant-ids
  [objects root-id]
  (loop [queue (if root-id [root-id] [])
         seen #{}
         result #{}]
    (if-let [id (first queue)]
      (if (contains? seen id)
        (recur (subvec (vec queue) 1) seen result)
        (let [children (vec (or (:shapes (get objects id)) []))]
          (recur (into (subvec (vec queue) 1) children)
                 (conj seen id)
                 (conj result id))))
      result)))

(defn- changed-ids
  [before after]
  (into #{}
        (keep (fn [[id shape]]
                (when (not= shape (get before id)) id)))
        after))

(defn- frame-repair-ids
  [before after changed]
  (let [structural-roots
        (filter (fn [id]
                  (let [old (get before id)
                        new (get after id)]
                    (or (nil? old)
                        (not= (:parent-id old) (:parent-id new)))))
                changed)]
    (into changed (mapcat #(descendant-ids after %) structural-roots))))

(defn- repair-frame-ids
  [objects repair-ids]
  (reduce
   (fn [result id]
     (if-let [shape (get result id)]
       (if-let [frame-id (containing-frame-id result id)]
         (assoc result id (assoc shape :frame-id frame-id))
         result)
       result))
   objects
   repair-ids))

(defn finalize-objects
  "Canonicalizes AI-produced object graphs before Shape validation and native
  Change generation.

  Repairs include:
  * integer-keyed paint maps produced by assoc-in on empty fills/strokes;
  * Penpot paint defaults required by Shape schemas;
  * stale selrect/points after direct semantic geometry edits;
  * frame-id propagation for every descendant after cross-frame moves."
  [before after]
  (let [changed (changed-ids before after)
        repair-ids (frame-repair-ids before after changed)
        normalized
        (reduce-kv
         (fn [result id shape]
           (assoc result id
                  (if (contains? changed id)
                    (-> shape
                        canonical-paints
                        (cond-> (= :text (:type shape))
                          (dissoc :position-data))
                        (rebuild-geometry (get before id)))
                    shape)))
         {}
         after)]
    (repair-frame-ids normalized repair-ids)))