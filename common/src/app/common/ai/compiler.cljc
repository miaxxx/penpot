;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.common.ai.compiler
  "Semantic Design IR -> native Penpot Shape compiler.

  The compiler supports deterministic proposal compilation for common UI
  structure while preserving semantic identity in Penpot plugin data. Existing
  native components are readable and patchable; creation of component instances
  requires a registry-specific instance factory and is rejected when absent."
  (:require
   [app.common.ai.canvas :as canvas]
   [app.common.ai.patch :as patch]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [clojure.string :as str]))

(def compiler-version "1.0")

(def ^:private container-kinds
  #{:page :section :frame :stack :grid :card :button :badge :input :textarea
    :select :tabs :navigation :modal :list :table :form :slot :variant
    :section-heading})

(def ^:private text-kinds
  #{:text :heading :paragraph})

(def ^:private leaf-shape-kinds
  #{:shape :divider :icon})

(defn- getv
  [value & keys]
  (some (fn [key]
          (when (contains? value key)
            (get value key)))
        keys))

(defn- normalize-keyword
  [value]
  (cond
    (keyword? value) value
    (string? value) (-> value
                        (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
                        str/lower-case
                        keyword)
    :else value))

(defn- token-reference?
  [value]
  (and (string? value)
       (str/starts-with? value "{")
       (str/ends-with? value "}")))

(defn- semantic-plugin-data
  [node]
  {:ai {"semantic-id" (str (:id node))
        "kind" (name (:kind node))
        "dsl-version" (or (get-in node [:metadata :dsl-version]) "1.0")
        "compiler-version" compiler-version
        "generated-by" "ai"}})

(defn- node-text
  [node]
  (or (getv (:props node) :text "text" :title "title" :label "label" :description "description")
      (:name node)
      "Text"))

(defn- text-content
  [node fill]
  (let [font-size (str (or (getv (:style node) :font-size :fontSize "fontSize")
                           (case (:kind node)
                             :heading 32
                             :paragraph 16
                             16)))
        font-weight (str (or (getv (:style node) :font-weight :fontWeight "fontWeight")
                             (if (= :heading (:kind node)) 700 400)))
        text-node (cond-> {:text (str (node-text node))
                           :font-size font-size
                           :font-weight font-weight}
                    fill (assoc :fills [{:fill-color fill :fill-opacity 1}]))]
    {:type "root"
     :children
     [{:type "paragraph-set"
       :children
       [{:type "paragraph"
         :children [text-node]}]}]}))

(defn- node-size
  [node]
  (let [layout (:layout node)
        props (:props node)
        width (or (getv layout :width "width")
                  (getv props :width "width"))
        height (or (getv layout :height "height")
                   (getv props :height "height"))
        default (cond
                  (contains? text-kinds (:kind node)) [240 40]
                  (= :divider (:kind node)) [240 1]
                  (= :button (:kind node)) [120 40]
                  (= :icon (:kind node)) [24 24]
                  (contains? container-kinds (:kind node)) [320 160]
                  :else [100 100])]
    {:width (if (number? width) width (first default))
     :height (if (number? height) height (second default))
     :width-mode (normalize-keyword width)
     :height-mode (normalize-keyword height)}))

(defn- layout-attrs
  [node]
  (let [layout (:layout node)
        kind (:kind node)
        layout-type (normalize-keyword (or (getv layout :type "type")
                                           (case kind
                                             :stack :stack
                                             :grid :grid
                                             nil)))
        direction (normalize-keyword (or (getv layout :direction "direction") :vertical))
        gap (getv layout :gap "gap")
        padding (getv layout :padding "padding")
        padding (when padding
                  (if (number? padding)
                    {:top padding :right padding :bottom padding :left padding}
                    padding))
        align (normalize-keyword (getv layout :align "align"))
        justify (normalize-keyword (getv layout :justify "justify"))]
    (cond-> {}
      (= :stack layout-type)
      (assoc :layout :flex
             :layout-flex-dir (if (= direction :horizontal) :row :column))

      (= :grid layout-type)
      (assoc :layout :grid
             :layout-grid-dir (if (= direction :horizontal) :column :row))

      (number? gap)
      (assoc :layout-gap-type :simple
             :layout-gap {:row-gap gap :column-gap gap})

      padding
      (assoc :layout-padding-type :multiple
             :layout-padding {:p1 (or (getv padding :top "top") 0)
                              :p2 (or (getv padding :right "right") 0)
                              :p3 (or (getv padding :bottom "bottom") 0)
                              :p4 (or (getv padding :left "left") 0)})

      align (assoc :layout-align-items align)
      justify (assoc :layout-justify-content justify))))

(defn- size-mode-attrs
  [{:keys [width-mode height-mode]}]
  (cond-> {}
    (= width-mode :fill) (assoc :layout-item-h-sizing :fill)
    (contains? #{:hug :auto} width-mode) (assoc :layout-item-h-sizing :auto)
    (= height-mode :fill) (assoc :layout-item-v-sizing :fill)
    (contains? #{:hug :auto} height-mode) (assoc :layout-item-v-sizing :auto)))

(defn- style-attrs
  [node warnings path]
  (let [style (:style node)
        fill (getv style :fill "fill")
        stroke (getv style :stroke "stroke")
        stroke-width (or (getv style :stroke-width :strokeWidth "strokeWidth") 1)
        radius (getv style :radius "radius")
        opacity (getv style :opacity "opacity")
        token-warnings (cond-> warnings
                         (token-reference? fill)
                         (conj {:code :unresolved-token :path (conj path :style :fill) :token fill})
                         (token-reference? stroke)
                         (conj {:code :unresolved-token :path (conj path :style :stroke) :token stroke}))
        attrs (cond-> {}
                (and (string? fill) (not (token-reference? fill)))
                (assoc :fills [{:fill-color fill :fill-opacity 1}])

                (and (string? stroke) (not (token-reference? stroke)))
                (assoc :strokes [{:stroke-style :solid
                                  :stroke-alignment :inner
                                  :stroke-width stroke-width
                                  :stroke-color stroke
                                  :stroke-opacity 1}])

                (number? radius)
                (assoc :r1 radius :r2 radius :r3 radius :r4 radius)

                (number? opacity)
                (assoc :opacity opacity))]
    [attrs token-warnings]))

(defn- penpot-type
  [node registry]
  (let [kind (:kind node)]
    (cond
      (contains? container-kinds kind) :frame
      (contains? text-kinds kind) :text
      (contains? leaf-shape-kinds kind) :rect
      (= kind :image) :rect
      (contains? #{:component :component-instance} kind)
      (when (get-in registry [(get-in node [:component :registry-id]) :shape-factory])
        :component-factory)
      :else nil)))

(defn- compile-error
  [code path message & [data]]
  (cond-> {:code code :path path :message message}
    data (assoc :data data)))

(declare compile-node*)

(defn- compile-children
  [children parent-id frame-id registry path]
  (reduce-kv
   (fn [result index child]
     (let [compiled (compile-node* child parent-id frame-id registry (conj path :children index) index)]
       (-> result
           (update :shapes into (:shapes compiled))
           (update :root-ids into (:root-ids compiled))
           (update :semantic-index merge (:semantic-index compiled))
           (update :warnings into (:warnings compiled))
           (update :errors into (:errors compiled)))))
   {:shapes [] :root-ids [] :semantic-index {} :warnings [] :errors []}
   (vec children)))

(defn- compile-node*
  [node parent-id parent-frame-id registry path sibling-index]
  (let [kind (:kind node)
        type (penpot-type node registry)]
    (cond
      (= type :component-factory)
      (let [factory (get-in registry [(get-in node [:component :registry-id]) :shape-factory])]
        (factory node parent-id parent-frame-id path sibling-index))

      (nil? type)
      {:shapes []
       :root-ids []
       :semantic-index {}
       :warnings []
       :errors [(compile-error :unsupported-node path
                               "Node cannot be compiled to a native Penpot shape"
                               {:kind kind
                                :component (get-in node [:component :registry-id])})]}

      :else
      (let [id (uuid/next)
            size (node-size node)
            frame-id (if (= type :frame) id parent-frame-id)
            children-result (compile-children (:children node) id frame-id registry path)
            [style warnings] (style-attrs node (:warnings children-result) path)
            props (:props node)
            x (or (getv props :x "x") 0)
            y (or (getv props :y "y") (* sibling-index 56))
            fill (getv (:style node) :fill "fill")
            attrs (merge
                   {:id id
                    :type type
                    :name (or (:name node) (str/capitalize (name kind)))
                    :x x
                    :y y
                    :width (:width size)
                    :height (:height size)
                    :parent-id parent-id
                    :frame-id parent-frame-id
                    :plugin-data (semantic-plugin-data node)}
                   (when (= type :frame)
                     {:shapes (:root-ids children-result)
                      :frame-id parent-frame-id})
                   (when (= type :text)
                     {:content (text-content node
                                             (when (and (string? fill)
                                                        (not (token-reference? fill)))
                                               fill))})
                   (layout-attrs node)
                   (size-mode-attrs size)
                   style)
            shape (cts/setup-shape attrs)]
        {:shapes (into [shape] (:shapes children-result))
         :root-ids [id]
         :semantic-index (assoc (:semantic-index children-result) (str (:id node)) id)
         :warnings (cond-> warnings
                     (= kind :image)
                     (conj {:code :image-placeholder
                            :path path
                            :message "Image requires a Penpot media asset and was compiled as a semantic rectangle placeholder."}))
         :errors (:errors children-result)}))))

(defn compile-document
  "Compiles canonical Document IR (`{:root node}`) below a native Penpot
  parent. Shapes are returned parent-first for preview and native Change
  generation."
  [{:keys [root]} {:keys [parent-id parent-frame-id registry]
                   :or {registry {}}}]
  (let [compiled (compile-node* root parent-id parent-frame-id registry [:root] 0)]
    (assoc compiled :valid? (empty? (:errors compiled)))))

(defn- add-compiled-shapes
  [objects parent-id index compiled]
  (let [root-id (first (:root-ids compiled))
        shapes (:shapes compiled)
        objects (reduce (fn [result shape] (assoc result (:id shape) shape)) objects shapes)
        parent (get objects parent-id)
        children (vec (remove #(= root-id %) (:shapes parent)))
        index (max 0 (min (or index (count children)) (count children)))]
    (if (and root-id parent)
      (assoc objects parent-id
             (assoc parent :shapes
                    (vec (concat (subvec children 0 index)
                                 [root-id]
                                 (subvec children index)))))
      objects)))

(defn- remove-subtree
  [objects id]
  (let [ids (canvas/descendant-ids objects id)
        parent-id (:parent-id (get objects id))
        objects (apply dissoc objects ids)]
    (if-let [parent (get objects parent-id)]
      (assoc objects parent-id
             (update parent :shapes #(vec (remove #{id} %))))
      objects)))

(defn- compile-structural-operation
  [snapshot operation registry]
  (let [op (:op operation)
        parent-id (canvas/resolve-id snapshot (:parent-id operation))
        target-id (canvas/resolve-id snapshot (:node-id operation))
        scope-ids (:scope-ids snapshot)
        node (:node operation)]
    (cond
      (and (contains? #{:insert :create} op)
           (nil? parent-id))
      {:valid? false
       :errors [(compile-error :parent-not-found [:operation :parent-id]
                               "Insert parent does not exist")]
       :warnings []}

      (and (contains? #{:insert :create} op)
           (not (contains? scope-ids parent-id)))
      {:valid? false
       :errors [(compile-error :out-of-scope [:operation :parent-id]
                               "Insert parent is outside the declared scope")]
       :warnings []}

      (contains? #{:insert :create} op)
      (let [parent (get-in snapshot [:penpot parent-id])
            frame-id (if (= :frame (:type parent)) parent-id (:frame-id parent))
            compiled (compile-node* node parent-id frame-id registry [:operation :node] 0)]
        (if (seq (:errors compiled))
          (assoc compiled :valid? false)
          {:valid? true
           :objects (add-compiled-shapes (:penpot snapshot) parent-id (:index operation) compiled)
           :warnings (:warnings compiled)
           :errors []}))

      (= :replace op)
      (cond
        (nil? target-id)
        {:valid? false :errors [(compile-error :target-not-found [:operation :node-id]
                                                "Replace target does not exist")] :warnings []}

        (not (contains? scope-ids target-id))
        {:valid? false :errors [(compile-error :out-of-scope [:operation :node-id]
                                                "Replace target is outside scope")] :warnings []}

        :else
        (let [target (get-in snapshot [:penpot target-id])
              parent-id (:parent-id target)
              parent (get-in snapshot [:penpot parent-id])
              index (.indexOf (:shapes parent) target-id)
              frame-id (if (= :frame (:type parent)) parent-id (:frame-id parent))
              compiled (compile-node* node parent-id frame-id registry [:operation :node] 0)]
          (if (seq (:errors compiled))
            (assoc compiled :valid? false)
            (let [without-target (remove-subtree (:penpot snapshot) target-id)]
              {:valid? true
               :objects (add-compiled-shapes without-target parent-id index compiled)
               :warnings (:warnings compiled)
               :errors []}))))

      (= :duplicate op)
      {:valid? false
       :errors [(compile-error :duplicate-requires-semantic-recompile [:operation]
                               "Duplicate is reserved for a follow-up semantic subtree recompiler.")]
       :warnings []}

      (= :replace-component op)
      {:valid? false
       :errors [(compile-error :component-factory-required [:operation]
                               "Replacing a component requires a registered Penpot instance factory.")]
       :warnings []}

      :else nil)))

(defn compile-patch
  "Compiles canonical Patch IR over native objects. Structural operations use
  the Design Node compiler; property, move and remove operations use the scoped
  pure Patch engine."
  [snapshot patch-ir & {:keys [registry] :or {registry {}}}]
  (let [operations (:operations patch-ir)
        result
        (reduce-kv
         (fn [{:keys [objects errors warnings] :as result} index operation]
           (if (seq errors)
             result
             (let [current (canvas/build-snapshot
                            {:file-id (:file-id snapshot)
                             :page-id (:page-id snapshot)
                             :revision (:revision snapshot)
                             :objects objects
                             :scope (:scope patch-ir)})
                   structural (compile-structural-operation current operation registry)]
               (if structural
                 (if (:valid? structural)
                   {:objects (:objects structural)
                    :errors []
                    :warnings (into warnings (:warnings structural))}
                   {:objects objects
                    :errors (mapv #(assoc % :operation-index index) (:errors structural))
                    :warnings (into warnings (:warnings structural))})
                 (let [patched (patch/apply-patch current
                                                  {:scope (:scope patch-ir)
                                                   :operations [operation]})]
                   {:objects (:objects patched)
                    :errors (mapv #(assoc % :operation-index index) (:errors patched))
                    :warnings (into warnings (:warnings patched))})))))
         {:objects (:penpot snapshot) :errors [] :warnings []}
         (vec operations))
        diff (patch/diff-objects (:penpot snapshot) (:objects result))]
    {:valid? (empty? (:errors result))
     :objects (:objects result)
     :errors (:errors result)
     :warnings (:warnings result)
     :diff diff}))
