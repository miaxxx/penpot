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
   [app.common.types.path.impl :as path-impl]
   [app.common.types.shape :as cts]
   [app.common.types.text :as txt]
   [app.common.uuid :as uuid]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.bool :as dwb]
   [app.main.data.workspace.drawing :as dwd]
   [app.main.data.workspace.groups :as dwg]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.transforms :as dwt]
   [app.main.data.workspace.undo :as dwu]
   [app.main.data.workspace.zoom :as dwz]
   [app.main.store :as st]
   [app.render-wasm.api :as wasm.api]))

(def channel "nowgen:canvas:v1")

(defonce ^:private listener* (atom nil))
(defonce ^:private revision* (atom 0))
(defonce ^:private transactions* (atom {}))
(def ^:private state-watch-key ::embedded-host-state-watch)

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

(defn- select-event
  [state payload]
  (let [ids (parse-ids (:ids payload))
        mode (keyword (or (:mode payload) "replace"))
        current (or (dm/get-in state [:workspace-local :selected]) (d/ordered-set))
        selected
        (case mode
          :add
          (into current ids)

          :remove
          (into (d/ordered-set)
                (remove (set ids))
                current)

          :toggle
          (reduce (fn [result id]
                    (if (contains? result id)
                      (disj result id)
                      (conj result id)))
                  current
                  ids)

          ids)]
    (dws/select-shapes selected)))

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
    "path-closed" :path
    "closed-path" :path
    "vector" :path
    nil))

(defn- normalize-fill
  [value opacity]
  (cond
    (string? value)
    [{:fill-color value
      :fill-opacity (if (number? opacity) opacity 1)}]

    (and (sequential? value) (every? map? value))
    (mapv (fn [fill]
            (cond-> fill
              (and (contains? fill :fill-color)
                   (not (contains? fill :fill-opacity)))
              (assoc :fill-opacity (if (number? opacity) opacity 1))))
          value)

    :else nil))

(defn- normalize-stroke
  [props]
  (let [strokes (:strokes props)
        stroke (:stroke props)]
    (cond
      (and (sequential? strokes) (every? map? strokes))
      (vec strokes)

      (string? stroke)
      [{:stroke-color stroke
        :stroke-opacity (or (:strokeOpacity props) 1)
        :stroke-width (or (:strokeWidth props) 1)
        :stroke-style (some-> (or (:strokeStyle props) "solid") keyword)
        :stroke-alignment (some-> (or (:strokeAlignment props) "center") keyword)
        :stroke-join (some-> (:strokeJoin props) keyword)
        :stroke-cap-start (some-> (:startCap props) keyword)
        :stroke-cap-end (some-> (:endCap props) keyword)}]

      :else nil)))

(defn- update-strokes
  [strokes patch]
  (let [strokes (or (normalize-stroke patch) strokes)
        fields [[:strokeWidth :stroke-width identity]
                [:strokeOpacity :stroke-opacity identity]
                [:strokeStyle :stroke-style keyword]
                [:strokeAlignment :stroke-alignment keyword]
                [:strokeJoin :stroke-join keyword]
                [:startCap :stroke-cap-start keyword]
                [:endCap :stroke-cap-end keyword]]]
    (mapv
     (fn [stroke]
       (reduce
        (fn [result [input-key output-key convert]]
          (if (contains? patch input-key)
            (assoc result output-key (convert (get patch input-key)))
            result))
        stroke
        fields))
     strokes)))

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

(defn- path-geometry
  [props]
  (when (seq (:points props))
    (let [points (->> (:points props)
                      (keep point-value)
                      (into []))]
      (when (>= (count points) 2)
        (let [xs (map :x points)
              ys (map :y points)
              min-x (apply min xs)
              min-y (apply min ys)
              max-x (apply max xs)
              max-y (apply max ys)
              width (max 1 (- max-x min-x))
              height (max 1 (- max-y min-y))
              origin-x (if (number? (:x props)) (:x props) min-x)
              origin-y (if (number? (:y props)) (:y props) min-y)
              normalized (mapv (fn [point]
                                 [(- (:x point) min-x)
                                  (- (:y point) min-y)])
                               points)]
          {:x origin-x
           :y origin-y
           :width width
           :height height
           :points normalized})))))

(defn- path-content
  [node-type props]
  (if (seq (:segments props))
    (let [segments
          (mapv
           (fn [{:keys [command params x y to c1 c2]}]
             (let [command (keyword command)
                   params (or params
                              (case command
                                :move-to {:x x :y y}
                                :line-to {:x x :y y}
                                :curve-to {:c1x (or (:x c1) (first c1))
                                           :c1y (or (:y c1) (second c1))
                                           :c2x (or (:x c2) (first c2))
                                           :c2y (or (:y c2) (second c2))
                                           :x (or (:x to) (first to))
                                           :y (or (:y to) (second to))}
                                {}))]
               (case command
                 :move-to {:command :move-to :params params}
                 :line-to {:command :line-to :params params}
                 :curve-to {:command :curve-to :params params}
                 :close-path {:command :close-path}
                 (throw (js/Error. (str "Unsupported path segment: " command))))))
           (:segments props))]
      (when-not (and (>= (count segments) 2)
                     (= :move-to (:command (first segments))))
        (throw (js/Error. "Path segments must begin with move-to and contain at least two segments")))
      (path-impl/from-plain segments))
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
      (path/points->content points :close (= node-type "path-closed")))))

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
  (let [text-patch? (and (= :text (:type shape))
                         (some #(contains? patch %)
                               [:text :fontSize :fontWeight :fontFamily :fill :fillOpacity]))
        path-patch? (and (= :path (:type shape))
                         (some #(contains? patch %) [:points :segments]))
        path-geometry (when (and path-patch? (contains? patch :points))
                        (path-geometry (merge {:x (:x shape)
                                               :y (:y shape)
                                               :width (:width shape)
                                               :height (:height shape)}
                                              patch)))
        closed-path? (or (contains? patch :closed) (:closed patch)
                         (some #(= :close-path (keyword (:command %))) (:segments patch)))
        shape
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

          (or (seq (:strokes patch))
              (contains? patch :stroke)
              (contains? patch :strokeWidth)
              (contains? patch :strokeOpacity)
              (contains? patch :strokeStyle)
              (contains? patch :strokeAlignment)
              (contains? patch :strokeJoin)
              (contains? patch :startCap)
              (contains? patch :endCap))
          (assoc :strokes (update-strokes (:strokes shape) patch))

          (contains? patch :blendMode)
          (assoc :blend-mode (keyword (:blendMode patch)))

          (contains? patch :shadows)
          (assoc :shadow (:shadows patch))

          (contains? patch :blur)
          (assoc :blur (:blur patch))

          (contains? patch :radius)
          (merge (radius-patch (:radius patch)))

          path-geometry
          (merge path-geometry)

          path-patch?
          (assoc :content (path-content (if closed-path? "path-closed" "path") patch)
                 :fills (if closed-path?
                          (or (normalize-fill (:fill patch) (:fillOpacity patch)) (:fills shape) [])
                          []))

          text-patch?
          (assoc :content
                 (txt/change-text
                  (:content shape)
                  (or (:text patch) (txt/content->text (:content shape)))
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
        path-geometry (when (= type :path) (path-geometry props))
        props (merge props path-geometry)
        id (uuid/next)
        alias (:alias payload)
        x (or (:x props) 0)
        y (or (:y props) 0)
        width (or (:width props) (if (= type :text) 160 100))
        height (or (:height props) (if (= type :text) 24 100))
        fills (normalize-fill (:fill props) (:fillOpacity props))
        strokes (normalize-stroke props)
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

          strokes
          (assoc :strokes strokes)

          (number? (:opacity props))
          (assoc :opacity (:opacity props))

          (number? (:radius props))
          (merge (radius-patch (:radius props)))

          path?
          (assoc :content (path-content node-type-name props)
                 :fills (if (contains? #{"path-closed" "closed-path"} node-type-name)
                          (or fills [])
                          [])
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
      (st/emit! event))))

(defn- bytes->base64
  [bytes]
  (let [length (.-length bytes)]
    (loop [offset 0
           chunks []]
      (if (>= offset length)
        (apply str chunks)
        (let [end (min length (+ offset 24576))
              binary (apply str
                            (map (fn [index]
                                   (js/String.fromCharCode (aget bytes index)))
                                 (range offset end)))]
          (recur end (conj chunks (.btoa js/window binary))))))))

(defn- render-shape
  [payload]
  (let [object-id (parse-id (:objectId payload))
        format (keyword (or (:format payload) "png"))
        scale (or (:scale payload) 1)]
    (when-not object-id
      (throw (js/Error. "Render requires a valid objectId")))
    (when-not (and (number? scale) (pos? scale) (<= scale 8))
      (throw (js/Error. "Render scale must be between 0 and 8")))
    (let [[bytes mime]
          (case format
            :png [(wasm.api/render-shape-pixels object-id scale) "image/png"]
            :pdf [(wasm.api/render-shape-pdf object-id scale) "application/pdf"]
            (throw (js/Error. (str "Unsupported render format: " format))))]
      {:format (name format)
       :mimeType mime
       :base64 (bytes->base64 bytes)})))

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

          "reset"
          (let [objects (dsh/lookup-page-objects @st/state)
                root-ids (set (get-in objects [uuid/zero :shapes]))]
            (when (seq root-ids)
              (st/emit! (dwsh/delete-shapes root-ids)))
            (st/emit! (dws/select-shapes (d/ordered-set)))
            (respond-next-tick! message (fn [] {:revision @revision*
                                                :snapshot (snapshot)})))

          "set-tool"
          (let [tool (some-> (get-in message [:payload :tool]) keyword)]
            (when-not (contains? #{:select :frame :rect :circle :line :path :curve :text :hand :comments :scale}
                                 tool)
              (throw (js/Error. (str "Unsupported canvas tool: " tool))))
            (st/emit! (dwd/select-for-drawing tool))
            (respond-next-tick! message (fn [] {:tool (name tool)})))

          "viewport-fit-all"
          (do
            (st/emit! dwz/zoom-to-fit-all)
            (respond-next-tick! message {:accepted true}))

          "viewport-fit-selection"
          (do
            (st/emit! dwz/zoom-to-selected-shape)
            (respond-next-tick! message {:accepted true}))

          "viewport-fit"
          (let [ids (vec (parse-ids (get-in message [:payload :ids])))]
            (when (empty? ids)
              (throw (js/Error. "Viewport fit requires at least one shape id")))
            (st/emit! (dwz/fit-to-shapes ids))
            (respond-next-tick! message {:accepted true}))

          "viewport-set-zoom"
          (let [scale (get-in message [:payload :scale])]
            (when-not (and (number? scale) (pos? scale))
              (throw (js/Error. "Zoom scale must be a positive number")))
            (st/emit! (dwz/set-zoom scale))
            (respond-next-tick! message {:scale scale}))

          "render"
          (respond-next-tick! message (render-shape (:payload message)))

          "export"
          (respond-next-tick! message (render-shape (:payload message)))

          "selection"
          (respond! message {:selectedIds
                             (mapv str (dm/get-in @st/state [:workspace-local :selected]))})

          "select"
          (do
            (st/emit! (select-event @st/state (:payload message)))
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
            (respond-next-tick! message (fn [] {:accepted true :revision @revision*})))

          "move"
          (do
            (emit-mutation! (move-events @st/state (:payload message)))
            (respond-next-tick! message (fn [] {:accepted true :revision @revision*})))

          "resize"
          (do
            (emit-mutation! (resize-events (:payload message)))
            (respond-next-tick! message (fn [] {:accepted true :revision @revision*})))

          "rotate"
          (do
            (emit-mutation! [(rotate-event (:payload message))])
            (respond-next-tick! message (fn [] {:accepted true :revision @revision*})))

          "duplicate"
          (let [payload (:payload message)
                ids (vec (parse-ids (:ids payload)))
                return-ref (atom nil)]
            (when (empty? ids)
              (throw (js/Error. "Duplicate requires at least one valid shape id")))
            (st/emit! (dws/duplicate-shapes ids
                                            :move-delta? true
                                            :change-selection? true
                                            :return-ref return-ref))
            (js/setTimeout
             (fn []
               (if-let [duplicate-id @return-ref]
                 (do
                   (when-let [alias (:alias payload)]
                     (st/emit! (dwsh/update-shapes
                                [duplicate-id]
                                #(assoc-in % [:plugin-data :nowgen "alias"] alias))))
                   (respond-next-tick!
                    message
                    (fn [] {:createdId (str duplicate-id)
                            :revision @revision*})))
                 (respond-error! message "KERNEL_ERROR"
                                 "Penpot did not return a duplicated node id")))
             0))

          "delete"
          (do
            (emit-mutation! [(delete-event (:payload message))])
            (respond-next-tick! message (fn [] {:accepted true :revision @revision*})))

          "reparent"
          (do
            (emit-mutation! [(reparent-event @st/state (:payload message))])
            (respond-next-tick! message (fn [] {:accepted true :revision @revision*})))

          "layout"
          (do
            (emit-mutation! (layout-events @st/state (:payload message)))
            (respond-next-tick! message (fn [] {:accepted true :revision @revision*})))

          "group"
          (let [payload (:payload message)
                ids (vec (parse-ids (:ids payload)))
                group-id (uuid/next)]
            (when (empty? ids)
              (throw (js/Error. "Group requires at least one valid shape id")))
            (st/emit! (dwg/group-shapes group-id ids :change-selection? true))
            (respond-next-tick! message
                                (fn [] {:createdId (str group-id)
                                        :revision @revision*})))

          "ungroup"
          (let [ids (vec (parse-ids (get-in message [:payload :ids])))]
            (when (empty? ids)
              (throw (js/Error. "Ungroup requires at least one valid shape id")))
            (st/emit! (dwg/ungroup-shapes ids :change-selection? true))
            (respond-next-tick! message (fn [] {:accepted true :revision @revision*})))

          "mask"
          (let [ids (vec (parse-ids (get-in message [:payload :ids])))]
            (when (empty? ids)
              (throw (js/Error. "Mask requires at least one valid shape id")))
            (st/emit! (dwg/mask-group ids))
            (respond-next-tick! message (fn [] {:accepted true :revision @revision*})))

          "unmask"
          (let [ids (vec (parse-ids (get-in message [:payload :ids])))]
            (when (empty? ids)
              (throw (js/Error. "Unmask requires at least one valid shape id")))
            (st/emit! (dwg/unmask-group ids))
            (respond-next-tick! message (fn [] {:accepted true :revision @revision*})))

          "boolean"
          (let [payload (:payload message)
                ids (vec (parse-ids (:ids payload)))
                operation (some-> (:operation payload) keyword)
                bool-id (uuid/next)]
            (when-not (contains? #{:union :difference :intersection :exclude :exclusion} operation)
              (throw (js/Error. (str "Unsupported boolean operation: " (:operation payload)))))
            (when (empty? ids)
              (throw (js/Error. "Boolean operation requires valid shape ids")))
            (let [operation (if (= operation :exclusion) :exclude operation)]
              (st/emit! (dwb/create-bool operation :ids ids :force-shape-id bool-id)))
            (respond-next-tick! message
                                (fn [] {:createdId (str bool-id)
                                        :revision @revision*})))

          "componentize"
          (let [payload (:payload message)
                ids (vec (parse-ids (:ids payload)))
                component-id* (atom nil)]
            (when (empty? ids)
              (throw (js/Error. "Componentize requires valid shape ids")))
            (st/emit! (dwl/add-component component-id* ids))
            (js/setTimeout
             (fn []
               (when-let [component-id @component-id*]
                 (when-let [name (:name payload)]
                   (st/emit! (dwl/rename-component-and-main-instance component-id name))))
               (respond-next-tick!
                message
                (fn [] {:componentId (some-> @component-id* str)
                        :revision @revision*})))
             0))

          "detach-component"
          (let [ids (vec (parse-ids (get-in message [:payload :ids])))]
            (when (empty? ids)
              (throw (js/Error. "Detach component requires valid shape ids")))
            (st/emit! (dwl/detach-components ids))
            (respond-next-tick! message (fn [] {:accepted true :revision @revision*})))

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
            (respond-next-tick! message
                                (fn [] {:transactionId transaction-id
                                        :revision @revision*})))

          "undo"
          (do
            (st/emit! dwu/undo)
            (respond-next-tick! message (fn [] {:accepted true :revision @revision*})))

          "redo"
          (do
            (st/emit! dwu/redo)
            (respond-next-tick! message (fn [] {:accepted true :revision @revision*})))

          (respond-error! message "UNSUPPORTED_REQUEST"
                          (str "Unsupported embedded canvas request: " (:type message))))
        (catch :default cause
          (respond-error! message "KERNEL_ERROR"
                          (or (.-message cause) (str cause))))))))

(defn- page-objects
  [state]
  (let [file-id (:current-file-id state)
        page-id (:current-page-id state)]
    (when (and file-id page-id)
      (some-> (dsh/lookup-page state file-id page-id)
              :objects))))

(defn- selected-ids
  [state]
  (mapv str (or (dm/get-in state [:workspace-local :selected]) [])))

(defn- viewport-summary
  [state]
  {:zoom (dm/get-in state [:workspace-local :zoom])
   :vbox (dm/get-in state [:workspace-local :vbox])})

(defn- drawing-tool
  [state]
  (some-> (dm/get-in state [:workspace-drawing :tool]) name))

(defn- observe-workspace-state!
  [_ _ old-state new-state]
  (let [old-selection (selected-ids old-state)
        new-selection (selected-ids new-state)
        old-objects (page-objects old-state)
        new-objects (page-objects new-state)
        old-viewport (viewport-summary old-state)
        new-viewport (viewport-summary new-state)
        old-tool (drawing-tool old-state)
        new-tool (drawing-tool new-state)]
    (when (not= old-selection new-selection)
      (post! {:type "selection-changed"
              :selectedIds new-selection}))

    (when (and new-objects
               (not (identical? old-objects new-objects)))
      (swap! revision* inc)
      (post! {:type "document-changed"
              :revision @revision*}))

    (when (not= old-viewport new-viewport)
      (post! {:type "viewport-changed"
              :viewport new-viewport}))

    (when (not= old-tool new-tool)
      (post! {:type "tool-changed"
              :tool new-tool}))))

(defn start!
  []
  (when-not @listener*
    (reset! revision* 0)
    (reset! transactions* {})
    (let [handler (fn [event] (handle-message! event))]
      (.addEventListener js/window "message" handler)
      (add-watch st/state state-watch-key observe-workspace-state!)
      (reset! listener* handler)
      (post! {:type "bridge-ready"
              :capabilities ["snapshot"
                             "reset"
                             "render"
                             "export"
                             "set-tool"
                             "viewport-fit-all"
                             "viewport-fit-selection"
                             "viewport-fit"
                             "viewport-set-zoom"
                             "selection"
                             "select"
                             "create"
                             "duplicate"
                             "update"
                             "move"
                             "resize"
                             "rotate"
                             "delete"
                             "reparent"
                             "layout"
                             "group"
                             "ungroup"
                             "mask"
                             "unmask"
                             "boolean"
                             "componentize"
                             "detach-component"
                             "transaction-start"
                             "transaction-commit"
                             "undo"
                             "redo"]}))))

(defn stop!
  []
  (when-let [handler @listener*]
    (.removeEventListener js/window "message" handler)
    (remove-watch st/state state-watch-key)
    (reset! transactions* {})
    (reset! listener* nil)))
