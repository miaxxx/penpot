;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.common.ai.schema
  "Schema definitions for the non-executable AI Design Agent protocols.

  The authoring and patch DSLs are exchange formats only. They never contain
  executable source code and must be validated before normalization or
  compilation into Penpot changes."
  (:require
   [app.common.schema :as sm]))

(def dsl-version "1.0")

(def supported-node-kinds
  #{:page :section :frame :stack :grid :text :image :icon :shape :divider
    :heading :paragraph :button :card :badge :input :textarea :select :tabs
    :navigation :modal :list :table :form :component :component-instance
    :slot :variant :section-heading})

(def supported-patch-operations
  #{:create :insert :set :unset :move :remove :duplicate :replace
    :bind-token :set-variant :replace-component :batch})

(def schema:token-reference
  [:re {:title "DesignTokenReference"}
   #"^\{[a-zA-Z0-9_-][a-zA-Z0-9$_.-]*\}$"])

(def schema:dimension
  [:or
   :number
   [:enum "fill" "hug" "auto"]
   schema:token-reference])

(def schema:padding
  [:map {:closed true}
   [:top {:optional true} [:or :number schema:token-reference]]
   [:right {:optional true} [:or :number schema:token-reference]]
   [:bottom {:optional true} [:or :number schema:token-reference]]
   [:left {:optional true} [:or :number schema:token-reference]]])

(def schema:layout
  [:map {:closed false}
   [:type {:optional true} [:enum "stack" "grid" "absolute"]]
   [:direction {:optional true} [:enum "vertical" "horizontal"]]
   [:width {:optional true} schema:dimension]
   [:height {:optional true} schema:dimension]
   [:minWidth {:optional true} schema:dimension]
   [:maxWidth {:optional true} schema:dimension]
   [:minHeight {:optional true} schema:dimension]
   [:maxHeight {:optional true} schema:dimension]
   [:gap {:optional true} [:or :number schema:token-reference]]
   [:padding {:optional true} schema:padding]
   [:align {:optional true} :string]
   [:justify {:optional true} :string]
   [:columns {:optional true} [:or :int [:vector :string]]]
   [:rows {:optional true} [:or :int [:vector :string]]]])

(def schema:style
  [:map {:closed false}
   [:fill {:optional true} [:or :string schema:token-reference]]
   [:stroke {:optional true} [:or :string schema:token-reference]]
   [:radius {:optional true} [:or :number schema:token-reference]]
   [:shadow {:optional true} [:or :string schema:token-reference]]
   [:opacity {:optional true} [:double {:min 0.0 :max 1.0}]]
   [:typography {:optional true} [:or :string schema:token-reference]]])

(def schema:component-reference
  [:map {:closed false}
   [:registryId :string]
   [:variant {:optional true} [:map-of :string :string]]
   [:props {:optional true} :map]])

(def schema:node
  "Shallow node schema. Recursive child validation is performed by
  app.common.ai.validation so errors can include semantic node paths."
  [:map {:closed false}
   [:id [:string {:min 1 :max 160}]]
   [:kind [:string {:min 1 :max 80}]]
   [:name {:optional true} :string]
   [:component {:optional true} [:or :string schema:component-reference]]
   [:props {:optional true} :map]
   [:layout {:optional true} schema:layout]
   [:style {:optional true} schema:style]
   [:responsive {:optional true} :map]
   [:bindings {:optional true} :map]
   [:metadata {:optional true} :map]
   [:children {:optional true} [:vector :map]]])

(def schema:document
  [:map {:closed true}
   [:dslVersion [:= dsl-version]]
   [:document schema:node]])

(def schema:scope
  [:map {:closed false}
   [:type [:enum "selection" "page" "component"]]
   [:rootId {:optional true} :string]
   [:pageId {:optional true} :string]
   [:componentId {:optional true} :string]])

(def schema:patch-operation
  [:map {:closed false}
   [:op [:string {:min 1 :max 80}]]
   [:nodeId {:optional true} :string]
   [:parentId {:optional true} :string]
   [:index {:optional true} [:int {:min 0}]]
   [:path {:optional true} :string]
   [:value {:optional true} :any]
   [:node {:optional true} :map]
   [:operations {:optional true} [:vector :map]]])

(def schema:patch
  [:map {:closed true}
   [:dslVersion [:= dsl-version]]
   [:baseRevision [:int {:min 0}]]
   [:scope schema:scope]
   [:operations [:vector {:min 1 :max 200} schema:patch-operation]]])

(defn valid-document?
  [value]
  (sm/valid? schema:document value))

(defn valid-patch?
  [value]
  (sm/valid? schema:patch value))

(defn explain-document
  [value]
  (some-> (sm/explain schema:document value) sm/simplify))

(defn explain-patch
  [value]
  (some-> (sm/explain schema:patch value) sm/simplify))
