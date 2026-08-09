;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.main.data.workspace.ai.context
  "Builds intentionally small AI context payloads from the current workspace.
  Full files are never included by default."
  (:require
   [app.common.data :as d]))

(def ^:private shape-context-keys
  [:id :name :type :parent-id :frame-id :component-id :component-file
   :main-instance :layout :layout-flex-dir :layout-gap :layout-padding
   :layout-item-h-sizing :layout-item-v-sizing :constraints-h :constraints-v
   :applied-tokens :touched])

(defn- summarize-shape
  [shape]
  (let [summary (select-keys shape shape-context-keys)]
    (cond-> summary
      (seq (:shapes shape))
      (assoc :children-count (count (:shapes shape))))))

(defn- parent-chain
  [objects shape max-depth]
  (loop [current shape
         depth 0
         result []]
    (let [parent (get objects (:parent-id current))]
      (if (or (nil? parent) (>= depth max-depth))
        result
        (recur parent (inc depth) (conj result (summarize-shape parent)))))))

(defn- child-tree
  [objects shape max-depth]
  (letfn [(build [node depth]
            (let [summary (summarize-shape node)]
              (if (>= depth max-depth)
                summary
                (assoc summary
                       :children
                       (->> (:shapes node)
                            (keep #(get objects %))
                            (mapv #(build % (inc depth))))))))]
    (build shape 0)))

(defn build-selection-context
  [{:keys [file-id page-id objects selected]}]
  (let [selected-shapes (->> selected (keep #(get objects %)) vec)]
    {:scope {:type :selection
             :file-id file-id
             :page-id page-id
             :selection-ids (mapv :id selected-shapes)}
     :selection (mapv #(child-tree objects % 4) selected-shapes)
     :parents (->> selected-shapes
                   (mapcat #(parent-chain objects % 2))
                   (d/distinct-by :id)
                   vec)
     :limits {:child-depth 4
              :parent-depth 2}}))

(defn build-page-summary
  [{:keys [file-id page-id objects]}]
  (let [shapes (vals objects)
        top-level (->> shapes
                       (filter #(or (nil? (:parent-id %))
                                    (= (:parent-id %) page-id)))
                       (sort-by #(or (:y %) 0)))]
    {:scope {:type :page
             :file-id file-id
             :page-id page-id}
     :dimensions nil
     :sections (mapv summarize-shape top-level)
     :shape-count (count shapes)
     :tokens-used (->> shapes (mapcat #(vals (:applied-tokens %))) set count)
     :components-used (->> shapes (keep :component-id) set count)}))

(defn build-context
  [scope params]
  (case scope
    :page (build-page-summary params)
    :component (build-selection-context params)
    (build-selection-context params)))
