;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns common-tests.ai-canvas-test
  (:require
   [app.common.ai.canvas :as canvas]
   [app.common.ai.compiler :as compiler]
   [app.common.ai.normalize :as normalize]
   [app.common.ai.patch :as patch]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(defn- frame
  [id parent-id children]
  (cts/setup-shape
   {:id id
    :type :frame
    :name "Frame"
    :x 0 :y 0 :width 600 :height 400
    :parent-id parent-id
    :frame-id parent-id
    :shapes children
    :layout :flex
    :layout-flex-dir :column}))

(defn- rect
  [id parent-id frame-id name]
  (cts/setup-shape
   {:id id
    :type :rect
    :name name
    :x 0 :y 0 :width 100 :height 40
    :parent-id parent-id
    :frame-id frame-id
    :fills [{:fill-color "#ffffff" :fill-opacity 1}]
    :plugin-data {:ai {"semantic-id" name}}}))

(t/deftest reads-lossless-and-compact-canvas-context
  (let [root-id (uuid/next)
        child-id (uuid/next)
        sibling-id (uuid/next)
        objects {root-id (frame root-id uuid/zero [child-id])
                 child-id (rect child-id root-id root-id "card-1")
                 sibling-id (rect sibling-id uuid/zero uuid/zero "outside")}
        snapshot (canvas/build-snapshot
                  {:objects objects
                   :scope {:type :selection :root-id "card-1"}})
        context (canvas/compact-context snapshot)]
    (t/is (= #{child-id} (:scope-ids snapshot)))
    (t/is (= (into {} (get objects child-id))
             (get-in snapshot [:nodes child-id :penpot])))
    (t/is (= "card-1" (get-in context [:nodes 0 :id])))
    (t/is (= 1 (get-in (canvas/summary snapshot) [:node-count])))))

(t/deftest applies-scoped-property-and-move-patches
  (let [root-id (uuid/next)
        second-parent-id (uuid/next)
        child-id (uuid/next)
        objects {root-id (frame root-id uuid/zero [child-id])
                 second-parent-id (frame second-parent-id uuid/zero [])
                 child-id (rect child-id root-id root-id "card-1")}
        page-snapshot (canvas/build-snapshot
                       {:objects objects :scope {:type :page}})
        result (patch/apply-patch
                page-snapshot
                {:scope {:type :page}
                 :operations [{:op :set
                               :node-id "card-1"
                               :path "style.fill"
                               :value "#0066ff"}
                              {:op :move
                               :node-id "card-1"
                               :parent-id (str second-parent-id)
                               :index 0}]})]
    (t/is (:valid? result))
    (t/is (= "#0066ff"
             (get-in result [:objects child-id :fills 0 :fill-color])))
    (t/is (= second-parent-id
             (get-in result [:objects child-id :parent-id])))
    (t/is (= 1 (get-in result [:diff :counts :moved])))))

(t/deftest rejects-out-of-scope-patches
  (let [root-id (uuid/next)
        child-id (uuid/next)
        outside-id (uuid/next)
        objects {root-id (frame root-id uuid/zero [child-id])
                 child-id (rect child-id root-id root-id "inside")
                 outside-id (rect outside-id uuid/zero uuid/zero "outside")}
        snapshot (canvas/build-snapshot
                  {:objects objects
                   :scope {:type :selection :root-id "inside"}})
        result (patch/apply-patch
                snapshot
                {:scope {:type :selection :root-id "inside"}
                 :operations [{:op :set
                               :node-id "outside"
                               :path "name"
                               :value "Changed"}]})]
    (t/is (false? (:valid? result)))
    (t/is (some #(= :out-of-scope (:code %)) (:errors result)))))

(t/deftest compiles-document-ir-to-valid-native-shapes
  (let [parent-id (uuid/next)
        ir (normalize/normalize-document
            {:dslVersion "1.0"
             :document
             {:id "feature-section"
              :kind "section"
              :name "Features"
              :layout {:type "stack" :direction "vertical" :gap 16}
              :style {:fill "#f5f5f5" :radius 16}
              :children
              [{:id "feature-heading"
                :kind "heading"
                :props {:text "Everything in one place"}}
               {:id "feature-card"
                :kind "card"
                :style {:fill "#ffffff"}
                :children []}]}})
        result (compiler/compile-document
                ir
                {:parent-id parent-id
                 :parent-frame-id parent-id})]
    (t/is (:valid? result))
    (t/is (= 3 (count (:shapes result))))
    (t/is (every? cts/valid-shape? (:shapes result)))
    (t/is (= "feature-section"
             (get-in (first (:shapes result))
                     [:plugin-data :ai "semantic-id"])))
    (t/is (= :flex (:layout (first (:shapes result)))))))

(t/deftest compiles-structural-insert-patch
  (let [root-id (uuid/next)
        objects {root-id (frame root-id uuid/zero [])}
        snapshot (canvas/build-snapshot
                  {:objects objects :scope {:type :page}})
        patch-ir (normalize/normalize-patch
                  {:dslVersion "1.0"
                   :baseRevision 0
                   :scope {:type "page"}
                   :operations
                   [{:op "insert"
                     :parentId (str root-id)
                     :index 0
                     :node {:id "new-card"
                            :kind "card"
                            :style {:fill "#ffffff"}
                            :children []}}]})
        result (compiler/compile-patch snapshot patch-ir)]
    (t/is (:valid? result))
    (t/is (= 1 (get-in result [:diff :counts :created])))
    (t/is (= 1 (count (get-in result [:objects root-id :shapes]))))))
