;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns common-tests.ai-dsl-test
  (:require
   [app.common.ai.capability :as capability]
   [app.common.ai.validation :as validation]
   [clojure.test :as t]))

(def valid-document
  {:dslVersion "1.0"
   :document
   {:id "pain-points-section"
    :kind "section"
    :layout {:type "stack"
             :direction "vertical"
             :width "fill"
             :gap "{space.8}"}
    :children
    [{:id "pain-points-heading"
      :kind "heading"
      :props {:title "Campus pain points"}}
     {:id "pain-point-01"
      :kind "component"
      :component "PainPointCard"
      :props {:number "01"
              :title "Scattered information"}}]}})

(t/deftest validates-document-and-builds-deterministic-ir
  (let [result (validation/validate-document valid-document)]
    (t/is (:valid? result))
    (t/is (= :section (get-in result [:ir :root :kind])))
    (t/is (= :component (get-in result [:ir :root :children 1 :kind])))
    (t/is (= "PainPointCard"
             (get-in result [:ir :root :children 1 :component :registry-id])))))

(t/deftest rejects-duplicate-semantic-ids
  (let [document (assoc-in valid-document
                           [:document :children 1 :id]
                           "pain-points-heading")
        result (validation/validate-document document)]
    (t/is (false? (:valid? result)))
    (t/is (some #(= :duplicate-node-id (:code %)) (:errors result)))))

(t/deftest rejects-unregistered-node-kinds
  (let [document (assoc-in valid-document [:document :kind] "javascript")
        result (validation/validate-document document)]
    (t/is (false? (:valid? result)))
    (t/is (some #(= :unsupported-node-kind (:code %)) (:errors result)))))

(t/deftest validates-minimal-patch
  (let [patch {:dslVersion "1.0"
               :baseRevision 184
               :scope {:type "selection"
                       :rootId "pain-points-section"}
               :operations
               [{:op "set"
                 :nodeId "pain-point-02"
                 :path "variant"
                 :value "blue"}]}
        result (validation/validate-patch patch)]
    (t/is (:valid? result))
    (t/is (= :set (get-in result [:ir :operations 0 :op])))))

(t/deftest rejects-selection-patch-without-root
  (let [patch {:dslVersion "1.0"
               :baseRevision 184
               :scope {:type "selection"}
               :operations
               [{:op "remove"
                 :nodeId "pain-point-02"}]}
        result (validation/validate-patch patch)]
    (t/is (false? (:valid? result)))
    (t/is (some #(= :missing-scope-root (:code %)) (:errors result)))))

(t/deftest reports-compile-mode-issues
  (let [validation-result (validation/validate-document valid-document)
        root (-> validation-result :ir :root)
        report (capability/analyze {} root)]
    (t/is (false? (:compatible? report)))
    (t/is (= 1 (get-in report [:counts :errors])))
    (t/is (some #(= :component-not-registered (:code %))
                (:issues report)))))

(t/deftest accepts-registered-components
  (let [validation-result (validation/validate-document valid-document)
        root (-> validation-result :ir :root)
        registry {"PainPointCard" {:id "PainPointCard"}}
        report (capability/analyze registry root)]
    (t/is (:compatible? report))
    (t/is (= 100 (:score report)))))
