;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.host-bridge
  "Host protocol for the backend-free embedded canvas.

  The bridge deliberately translates a small deterministic transport into
  Penpot's native workspace events. Geometry, layout, undo and rendering stay
  inside Penpot; higher-level Nowgen selectors/DSL live in the host."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.types.path :as path]
   [app.common.types.shape :as cts]
   [app.common.types.text :as txt]
   [app.common.uuid :as uuid]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.transforms :as dwt]
   [app.main.data.workspace.undo :as dwu]
   [app.main.store :as st]))

(def channel "nowgen:canvas:v1")

(defonce ^:private listener* (atom nil))
(defonce ^:private revision* (atom 0))
(defonce ^:private transactions* (atom {}))

(defn- uuid-str [value]
  (when value (str value)))

(defn- nowgen-alias
  [shape]
  (get-in shape [:plugin-data :nowgen "alias"]))

(defn- fill-summary
  [fill]
  (select-keys fill
               [:fill-color
                :fill-opacity
                :fill-color-gradient
                :fill-image
                :fill-color-ref-id
                :fill-color-ref-file]))

(defn- stroke-summary
  [stroke]
  (select-keys stroke
               [:stroke-color
                :stroke-opacity
                :stroke-width
                :stroke-style
                :stroke-alignment
                :stroke-cap-start
                :stroke-cap-end
                :stroke-color-gradient]))

(defn- shadow-summary
  [shadow]
  (select-keys shadow
               [:style :color :opacity :offset-x :offset-y :blur :spread :hidden]))

(defn- style-summary
  [shape]
  {:opacity (or (:opacity shape) 1)
   :blendMode (some-> (:blend-mode shape) name)
   :radius {:topLeft (:r1 shape)
            :topRight (:r2 shape)
            :bottomRight (:r3 shape)
            :bottomLeft (:r4 shape)}
   :fills (mapv fill-summary (or (:fills shape) []))
   :strokes (mapv stroke-summary (or (:strokes shape) []))
   :shadows (mapv shadow-summary (or (:shadow shape) []))
   :blur (when-let [blur (:blur shape)]
           (select-keys blur [:type :value :hidden]))})

(defn- layout-summary
  [shape]
  (when-let [layout (:layout shape)]
    {:type (name layout)
     :direction (some-> (:layout-flex-dir shape) name)
     :wrap (some-> (:layout-wrap-type shape) name)
     :alignItems (some-> (:layout-align-items shape) name)
     :justifyContent (some-> (:layout-justify-content shape) name)
     :gap (:layout-gap shape)
     :padding (:layout-padding shape)
     :horizontalSizing (some-> (:layout-item-h-sizing shape) name)
     :verticalSizing (some-> (:layout-item-v-sizing shape) name)
     :absolute (boolean (:layout-item-absolute shape))}))

(defn- text-summary
  [shape]
  (when (= :text (:type shape))
    {:characters (txt/content->text (:content shape))
     :growType (some-> (:grow-type shape) name)}))

(defn- node-summary
  [shape]
  (cond-> {:id (uuid-str (:id shape))
           :alias (nowgen-alias shape)
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
           :locked (boolean (:blocked shape))
           :style (style-summary shape)}
    (= :text (:type shape))
    (assoc :text (text-summary shape))

    (:component-id shape)
    (assoc :componentId (uuid-str (:component-id shape)))

    (:layout shape)
    (assoc :layout (layout-summary shape))))

(defn snapshot
  ([]
   (snapshot @st/state))
  ([state]
   (let [file-id (:current-file-id state)
         page-id (:current-page-id state)
         file (get-in state [:files file-id])
         page (dsh/lookup-page state file-id page-id)]
     (when-not (and file page)
       (throw (js/Error. "Embedded canvas is not ready")))
     (let [selected (dm/get-in state [:workspace-local :selected])
           nodes (reduce-kv
                  (fn [result id shape]
                    (assoc result (str id) (node-summary shape)))
                  {}
                  (:objects page))]
       {:schema "nowgen.canvas"
        :version 1
        :revision @revision*
        :fileId (uuid-str file-id)
        :pageId (uuid-str page-id)
        :rootIds (mapv str (or (get-in page [:objects uuid/zero :shapes]) []))
        :selectedIds (mapv str selected)
        :nodes nodes}))))

(defn- post!
  [payload]
  (let [parent (.-parent js/window)
        target (if (identical? js/window parent)
                 js/window
                 parent)]
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

(defn- respond-next-tick!
  [request data]
  (js/setTimeout
   (fn []
     (respond! request
               (if (fn? data)
                 (data)
                 data)))
   0))

(defn- parse-id
  [value]
  (some-> value uuid/parse*))

(defn- parse-ids
  [ids]
  (into (d/ordered-set)
        (keep uuid/parse*)
        ids))

(defn- node-type
  [value]
  (case value
    "frame" :frame
    "board" :frame
    "rectangle" :rect
    "rect" :rect
    "ellipse" :circle
    "circle" :circle
    "text" :text
    "line" :path
    "arrow" :path
    "path" :path
    nil))

(defn- normalize-fill
  [value opacity]
  (when (string? value)
    [{:fill-color value
      :fill-opacity (if (number? opacity) opacity 1)}]))

(defn- radius-patch
  [radius]
  (when (number? radius)
    {:r1 radius :r2 radius :r3 radius :r4 radius}))

(defn- point-value
  [value]
  (when (and (vector? value)
             (= 2 (count value))
             (every? number? value))
    (gpt/point (first value) (second value))))

(defn- path-content
  [node-type props]
  (let [points (->> (:points props)
                    (keep point-value)
                    (into []))
        points (if (>= (count points) 2)
                 points
                 (let [x (or (:x props) 0)
                       y (or (:y props) 0)
                       width (or (:width props) 100)
                       height (or (:height props) 0)]
                   [(gpt/point x y)
                    (gpt/point (+ x width) (+ y height))]))]
    (path/points->content points :close (= node-type "path-closed"))))

(defn- path-strokes
  [node-type props]
  [{:stroke-style (some-> (or (:strokeStyle props) "solid") keyword)
    :stroke-alignment :center
    :stroke-width (or (:strokeWidth props) 1)
    :stroke-color (or (:stroke props) "#000000")
    :stroke-opacity (or (:strokeOpacity props) 1)
    :stroke-cap-start (some-> (:startCap props) keyword)
    :stroke-cap-end (cond
                      (:endCap props) (keyword (:endCap props))
                      (= node-type "arrow") :line-arrow
                      :else nil)}])

(defn- apply-simple-patch
  [shape patch]
  (let [shape
        (cond-> shape
          (contains? patch :name)
          (assoc :name (:name patch))

          (contains? patch :opacity)
          (assoc :opacity (:opacity patch))

          (contains? patch :hidden)
          (assoc :hidden (boolean (:hidden patch)))

          (contains? patch :locked)
          (assoc :blocked (boolean (:locked patch)))

          (contains? patch :rotation)
          (assoc :rotation (:rotation patch))

          (contains? patch :fill)
          (assoc :fills (normalize-fill (:fill patch) (:fillOpacity patch)))

          (contains? patch :radius)
          (merge (radius-patch (:radius patch)))

          (and (= :text (:type shape))
               (contains? patch :text))
          (assoc :content
                 (txt/change-text
                  (:content shape)
                  (:text patch)
                  :font-size (str (or (:fontSize patch) 14))
                  :font-weight (str (or (:fontWeight patch) 400))
                  :font-family (or (:fontFamily patch) "sourcesanspro")
                  :font-id (or (:fontId patch) "sourcesanspro")
                  :fills (or (normalize-fill (:fill patch) (:fillOpacity patch))
                             (txt/get-default-text-fills)))))]
    shape))

(defn- create-shape-event
  [state payload]
  (let [objects (dsh/lookup-page-objects state)
        node-type-name (:nodeType payload)
        type (node-type node-type-name)
        _ (when-not type
            (throw (js/Error. (str "Unsupported create node type: " (:nodeType payload)))))
        parent-id (or (parse-id (:parentId payload)) uuid/zero)
        parent (get objects parent-id)
        _ (when-not parent
            (throw (js/Error. (str "Parent not found: " (:parentId payload)))))
        frame-id (if (= :frame (:type parent))
                   parent-id
                   (or (:frame-id parent) uuid/zero))
        props (or (:props payload) {})
        id (uuid/next)
        alias (:alias payload)
        x (or (:x props) 0)
        y (or (:y props) 0)
        width (or (:width props) (if (= type :text) 160 100))
        height (or (:height props) (if (= type :text) 24 100))
        fills (normalize-fill (:fill props) (:fillOpacity props))
        plugin-data (cond-> {}
                      alias (assoc :nowgen {"alias" alias}))
        path? (= type :path)
        attrs
        (cond-> {:id id
                 :type type
                 :name (or (:name props) alias)
                 :x x
                 :y y
                 :width width
                 :height height
                 :parent-id parent-id
                 :frame-id frame-id
                 :plugin-data plugin-data}
          fills
          (assoc :fills fills)

          (number? (:opacity props))
          (assoc :opacity (:opacity props))

          (number? (:radius props))
          (merge (radius-patch (:radius props)))

          path?
          (assoc :content (path-content node-type-name props)
                 :fills []
                 :strokes (path-strokes node-type-name props))

          (= type :text)
          (assoc :content
                 (txt/change-text
                  nil
                  (or (:text props) "")
                  :font-size (str (or (:fontSize props) 14))
                  :font-weight (str (or (:fontWeight props) 400))
                  :font-family (or (:fontFamily props) "sourcesanspro")
                  :font-id (or (:fontId props) "sourcesanspro")
                  :fills (or fills (txt/get-default-text-fills)))))
        shape (cts/setup-shape attrs)]
    {:id id
     :event (dwsh/add-shape shape {:skip-edition? true})}))

(defn- update-event
  [payload]
  (let [ids (vec (parse-ids (:ids payload)))
        patch (or (:patch payload) {})]
    (when (empty? ids)
      (throw (js/Error. "Update requires at least one valid shape id")))
    (dwsh/update-shapes ids #(apply-simple-patch % patch))))

(defn- move-events
  [state payload]
  (let [ids (vec (parse-ids (:ids payload)))
        objects (dsh/lookup-page-objects state)]
    (mapv
     (fn [id]
       (let [shape (get objects id)
             x (cond
                 (number? (:x payload)) (:x payload)
                 (number? (:dx payload)) (+ (:x shape) (:dx payload))
                 :else (:x shape))
             y (cond
                 (number? (:y payload)) (:y payload)
                 (number? (:dy payload)) (+ (:y shape) (:dy payload))
                 :else (:y shape))]
         (dwt/update-position id {:x x :y y} {:absolute? true})))
     ids)))

(defn- resize-events
  [payload]
  (let [ids (vec (parse-ids (:ids payload)))
        width (:width payload)
        height (:height payload)]
    (when-not (and (seq ids) (number? width) (number? height))
      (throw (js/Error. "Resize requires ids, width and height")))
    [(dwt/update-dimensions ids :width width)
     (dwt/update-dimensions ids :height height)]))

(defn- rotate-event
  [payload]
  (let [ids (vec (parse-ids (:ids payload)))
        angle (:angle payload)]
    (when-not (and (seq ids) (number? angle))
      (throw (js/Error. "Rotate requires ids and angle")))
    (dwt/increase-rotation ids angle)))

(defn- delete-event
  [payload]
  (let [ids (set (parse-ids (:ids payload)))]
    (when (empty? ids)
      (throw (js/Error. "Delete requires at least one valid shape id")))
    (dwsh/delete-shapes ids)))

(defn- reparent-event
  [state payload]
  (let [ids (set (parse-ids (:ids payload)))
        parent-id (parse-id (:parentId payload))
        objects (dsh/lookup-page-objects state)
        parent (get objects parent-id)
        index (or (:index payload) (count (:shapes parent)))]
    (when-not (and (seq ids) parent)
      (throw (js/Error. "Reparent requires valid ids and parent")))
    (dwsh/relocate-shapes ids parent-id index)))

(defn- padding-map
  [value]
  (cond
    (number? value)
    {:p1 value :p2 value :p3 value :p4 value}

    (and (vector? value) (= 4 (count value)))
    (let [[top right bottom left] value]
      {:p1 top :p2 right :p3 bottom :p4 left})

    :else nil))

(defn- layout-changes
  [layout]
  (let [gap (:gap layout)
        row-gap (or (:rowGap layout) gap)
        column-gap (or (:columnGap layout) gap)
        padding (padding-map (:padding layout))]
    (d/without-nils
     {:layout-flex-dir (some-> (:direction layout) keyword)
      :layout-wrap-type (when (contains? layout :wrap)
                          (if (:wrap layout) :wrap :nowrap))
      :layout-align-items (some-> (:align layout) keyword)
      :layout-justify-content (some-> (:justify layout) keyword)
      :layout-gap (when (or (number? row-gap) (number? column-gap))
                    {:row-gap (or row-gap 0)
                     :column-gap (or column-gap 0)})
      :layout-padding padding})))

(defn- layout-events
  [state payload]
  (let [ids (vec (parse-ids (:ids payload)))
        objects (dsh/lookup-page-objects state)
        layout (or (:layout payload) {})
        type (some-> (:type layout) keyword)
        changes (layout-changes layout)]
    (when-not (seq ids)
      (throw (js/Error. "Layout requires at least one valid shape id")))
    (vec
     (mapcat
      (fn [id]
        (let [shape (get objects id)
              current (:layout shape)
              create? (and (contains? #{:flex :grid} type)
                           (not= current type))]
          (cond-> []
            create?
            (conj (dwsl/create-layout-from-id id type :from-frame? true))

            (seq changes)
            (conj (dwsl/update-layout [id] changes)))))
      ids))))

(defn- emit-mutation!
  [events]
  (doseq [event events]
    (when event
      (st/emit! event)))
  (swap! revision* inc))

(defn- handle-message!
  [event]
  (let [raw (.-data event)
        message (when raw (js->clj raw :keywordize-keys true))]
    (when (and (= channel (:channel message))
               (= "host" (:source message)))
      (try
        (case (:type message)
          "ping"
          (respond! message {:ready (boolean (and (:workspace-ready @st/state)
                                                   (:current-page-id @st/state)))})

          "snapshot"
          (respond! message (snapshot))

          "selection"
          (respond! message {:selectedIds
                             (mapv str (dm/get-in @st/state [:workspace-local :selected]))})

          "select"
          (let [ids (parse-ids (get-in message [:payload :ids]))]
            (st/emit! (dws/select-shapes ids))
            (respond-next-tick! message
                                (fn [] {:selectedIds
                                        (mapv str (dm/get-in @st/state [:workspace-local :selected]))})))

          "create"
          (let [{:keys [id event]} (create-shape-event @st/state (:payload message))]
            (emit-mutation! [event])
            (respond-next-tick! message
                                (fn [] {:createdId (str id)
                                        :revision @revision*})))

          "update"
          (do
            (emit-mutation! [(update-event (:payload message))])
            (respond-next-tick! message {:accepted true :revision @revision*}))

          "move"
          (do
            (emit-mutation! (move-events @st/state (:payload message)))
            (respond-next-tick! message {:accepted true :revision @revision*}))

          "resize"
          (do
            (emit-mutation! (resize-events (:payload message)))
            (respond-next-tick! message {:accepted true :revision @revision*}))

          "rotate"
          (do
            (emit-mutation! [(rotate-event (:payload message))])
            (respond-next-tick! message {:accepted true :revision @revision*}))

          "delete"
          (do
            (emit-mutation! [(delete-event (:payload message))])
            (respond-next-tick! message {:accepted true :revision @revision*}))

          "reparent"
          (do
            (emit-mutation! [(reparent-event @st/state (:payload message))])
            (respond-next-tick! message {:accepted true :revision @revision*}))

          "layout"
          (do
            (emit-mutation! (layout-events @st/state (:payload message)))
            (respond-next-tick! message {:accepted true :revision @revision*}))

          "transaction-start"
          (let [transaction-id (get-in message [:payload :id])
                undo-id (js/Symbol)]
            (when-not (string? transaction-id)
              (throw (js/Error. "Transaction id is required")))
            (swap! transactions* assoc transaction-id undo-id)
            (st/emit! (dwu/start-undo-transaction undo-id :timeout 0))
            (respond! message {:transactionId transaction-id}))

          "transaction-commit"
          (let [transaction-id (get-in message [:payload :id])
                undo-id (get @transactions* transaction-id)]
            (when-not undo-id
              (throw (js/Error. (str "Unknown transaction: " transaction-id))))
            (st/emit! (dwu/commit-undo-transaction undo-id))
            (swap! transactions* dissoc transaction-id)
            (respond-next-tick! message {:transactionId transaction-id
                                         :revision @revision*}))

          "undo"
          (do
            (st/emit! dwu/undo)
            (swap! revision* inc)
            (respond-next-tick! message {:accepted true :revision @revision*}))

          "redo"
          (do
            (st/emit! dwu/redo)
            (swap! revision* inc)
            (respond-next-tick! message {:accepted true :revision @revision*}))

          (respond-error! message "UNSUPPORTED_REQUEST"
                          (str "Unsupported embedded canvas request: " (:type message))))
        (catch :default cause
          (respond-error! message "KERNEL_ERROR"
                          (or (.-message cause) (str cause))))))))

(defn start!
  []
  (when-not @listener*
    (reset! revision* 0)
    (reset! transactions* {})
    (let [handler (fn [event] (handle-message! event))]
      (.addEventListener js/window "message" handler)
      (reset! listener* handler)
      (post! {:type "bridge-ready"
              :capabilities ["snapshot"
                             "selection"
                             "select"
                             "create"
                             "update"
                             "move"
                             "resize"
                             "rotate"
                             "delete"
                             "reparent"
                             "layout"
                             "transaction-start"
                             "transaction-commit"
                             "undo"
                             "redo"]}))))

(defn stop!
  []
  (when-let [handler @listener*]
    (.removeEventListener js/window "message" handler)
    (reset! transactions* {})
    (reset! listener* nil)))
