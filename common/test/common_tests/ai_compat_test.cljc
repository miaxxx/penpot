;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns common-tests.ai-compat-test
  (:require
   [app.common.ai.canvas :as canvas]
   [app.common.ai.compat :as compat]
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
    :shapes children}))

(defn- group
  [id parent-id frame-id children]
  (cts/setup-shape
   {:id id
    :type :group
    :name "Group"
    :x 0 :y 0 :width 200 :height 100
    :parent-id parent-id
    :frame-id frame-id
    :shapes children}))

(defn- rect
  [id parent-id frame-id semantic-id]
  (cts/setup-shape
   {:id id
    :type :rect
    :name semantic-id
    :x 0 :y 0 :width 100 :height 40
    :parent-id parent-id
    :frame-id frame-id
    :fills [{:fill-color "#ffffff" :fill-opacity 1}]
    :strokes []
    :plugin-data {:ai {"semantic-id" semantic-id}}}))

(t/deftest selection-scope-keeps-every-selected-root
  (let [left-id (uuid/next)
        right-id (uuid/next)
        objects {left-id (rect left-id uuid/zero uuid/zero "left")
                 right-id (rect right-id uuid/zero uuid/zero "right")}
        snapshot (canvas/build-snapshot
                  {:objects objects
                   :scope {:type :selection
                           :root-id "left"
                           :selection-ids [left-id right-id]}})]
    (t/is (= #{left-id right-id} (:scope-ids snapshot)))))

(t/deftest component-scope-climbs-from-selected-descendant
  (let [component-id (uuid/next)
        child-id (uuid/next)
        component (assoc (frame component-id uuid/zero [child-id])
                         :component-id (uuid/next)
                         :component-root true
                         :main-instance true)
        objects {component-id component
                 child-id (rect child-id component-id component-id "component-label")}
        snapshot (canvas/build-snapshot
                  {:objects objects
                   :scope {:type :component
                           :root-id "component-label"}})]
    (t/is (= #{component-id child-id} (:scope-ids snapshot)))
    (t/is (= component-id (canvas/component-root-id objects child-id)))))

(t/deftest compatibility-pass-canonicalizes-paints-and-geometry
  (let [id (uuid/next)
        before-shape (rect id uuid/zero uuid/zero "card")
        before {id before-shape}
        after {id (-> before-shape
                      (assoc :x 48 :width 220)
                      (assoc :fills {0 {:fill-color "#101010"}})
                      (assoc :strokes {0 {:stroke-color "#ffffff"}}))}
        result (compat/finalize-objects before after)
        shape (get result id)]
    (t/is (vector? (:fills shape)))
    (t/is (vector? (:strokes shape)))
    (t/is (= 1 (get-in shape [:fills 0 :fill-opacity])))
    (t/is (= :solid (get-in shape [:strokes 0 :stroke-style])))
    (t/is (= 48 (get-in shape [:selrect :x])))
    (t/is (= 220 (get-in shape [:selrect :width])))
    (t/is (cts/valid-shape? shape))))

(t/deftest compatibility-pass-propagates-frame-id-through-moved-subtree
  (let [frame-a-id (uuid/next)
        frame-b-id (uuid/next)
        group-id (uuid/next)
        child-id (uuid/next)
        before {frame-a-id (frame frame-a-id uuid/zero [group-id])
                frame-b-id (frame frame-b-id uuid/zero [])
                group-id (group group-id frame-a-id frame-a-id [child-id])
                child-id (rect child-id group-id frame-a-id "nested")}
        after (-> before
                  (assoc-in [frame-a-id :shapes] [])
                  (assoc-in [frame-b-id :shapes] [group-id])
                  (assoc-in [group-id :parent-id] frame-b-id))
        result (compat/finalize-objects before after)]
    (t/is (= frame-b-id (get-in result [group-id :frame-id])))
    (t/is (= frame-b-id (get-in result [child-id :frame-id])))))