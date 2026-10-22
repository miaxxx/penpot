;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.host-bridge
  "Minimal host bridge for the backend-free embedded canvas.

  The public protocol is intentionally small. Nowgen's higher-level Runtime
  and DSL sit outside Penpot and translate into this transport."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.uuid :as uuid]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.undo :as dwu]
   [app.main.store :as st]))

(def channel "nowgen:canvas:v1")

(defonce ^:private listener* (atom nil))

(defn- uuid-str [value]
  (when value (str value)))

(defn- node-summary
  [shape]
  (cond-> {:id (uuid-str (:id shape))
           :type (some-> (:type shape) name)
           :name (:name shape)
           :parentId (uuid-str (:parent-id shape))
           :children (mapv uuid-str (:shapes shape))
           :bounds {:x (:x shape)
                    :y (:y shape)
                    :width (:width shape)
                    :height (:height shape)}
           :rotation (or (:rotation shape) 0)
           :hidden (boolean (:hidden shape))
           :locked (boolean (:blocked shape))}
    (:component-id shape)
    (assoc :componentId (uuid-str (:component-id shape)))

    (:layout shape)
    (assoc :layout (name (:layout shape)))))

(defn snapshot
  ([]
   (snapshot @st/state))
  ([state]
   (let [file-id (:current-file-id state)
         page-id (:current-page-id state)
         file (get-in state [:files file-id])
         page (dsh/lookup-page state file-id page-id)
         selected (dm/get-in state [:workspace-local :selected])
         nodes (reduce-kv
                (fn [result id shape]
                  (assoc result (str id) (node-summary shape)))
                {}
                (:objects page))]
     {:schema "nowgen.canvas"
      :version 1
      :revision (or (:revn file) 0)
      :fileId (uuid-str file-id)
      :pageId (uuid-str page-id)
      :rootIds (mapv str (or (get-in page [:objects uuid/zero :shapes]) []))
      :selectedIds (mapv str selected)
      :nodes nodes})))

(defn- post!
  [payload]
  (let [target (if (identical? js/window js/window.parent)
                 js/window
                 js/window.parent)]
    (.postMessage target
                  (clj->js (assoc payload
                                  :channel channel
                                  :source "canvas"))
                  "*")))

(defn- respond!
  [request data]
  (post! {:type "result"
          :requestId (:id request)
          :ok true
          :data data}))

(defn- respond-error!
  [request code message]
  (post! {:type "result"
          :requestId (:id request)
          :ok false
          :error {:code code :message message}}))

(defn- parse-ids
  [ids]
  (into (d/ordered-set)
        (keep uuid/parse*)
        ids))

(defn- handle-message!
  [event]
  (let [raw (.-data event)
        message (when raw (js->clj raw :keywordize-keys true))]
    (when (and (= channel (:channel message))
               (= "host" (:source message)))
      (try
        (case (:type message)
          "ping"
          (respond! message {:ready true})

          "snapshot"
          (respond! message (snapshot))

          "selection"
          (respond! message {:selectedIds
                             (mapv str (dm/get-in @st/state [:workspace-local :selected]))})

          "select"
          (let [ids (parse-ids (get-in message [:payload :ids]))]
            (st/emit! (dws/select-shapes ids))
            (respond! message {:selectedIds (mapv str ids)}))

          "undo"
          (do
            (st/emit! dwu/undo)
            (respond! message {:accepted true}))

          "redo"
          (do
            (st/emit! dwu/redo)
            (respond! message {:accepted true}))

          (respond-error! message "UNSUPPORTED_REQUEST"
                          (str "Unsupported embedded canvas request: " (:type message))))
        (catch :default cause
          (respond-error! message "KERNEL_ERROR"
                          (or (.-message cause) (str cause))))))))

(defn start!
  []
  (when-not @listener*
    (let [handler (fn [event] (handle-message! event))]
      (.addEventListener js/window "message" handler)
      (reset! listener* handler)
      (post! {:type "ready"
              :capabilities ["snapshot" "selection" "select" "undo" "redo"]}))))

(defn stop!
  []
  (when-let [handler @listener*]
    (.removeEventListener js/window "message" handler)
    (reset! listener* nil)))
