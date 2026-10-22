;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.embedded
  "Local, backend-free workspace bootstrap used by embeddable canvas hosts.

  This namespace intentionally reuses Penpot's native file/page schemas and
  workspace state. It only replaces the remote bundle/persistence bootstrap."
  (:require
   [app.common.files.builder :as fb]
   [app.common.types.file :as ctf]
   [app.main.data.workspace.shape-layout :as dwsl]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn make-empty-file
  [{:keys [file-id page-id project-id name]
    :or {name "Untitled"}}]
  (ctf/make-file
   {:id file-id
    :project-id project-id
    :name name
    :features fb/default-features
    :migrations fb/available-migrations}
   :page-id page-id))

(defn initialize
  [{:keys [team-id project-id file-id] :as params}]
  (let [file (make-empty-file params)]
    (ptk/reify ::initialize
      ptk/UpdateEvent
      (update [_ state]
        (-> state
            (assoc :embedded-workspace? true)
            (assoc :current-team-id team-id)
            (assoc :current-project-id project-id)
            (assoc :current-file-id file-id)
            (assoc :workspace-ready file-id)
            (assoc :workspace-presence {})
            (assoc :workspace-undo {})
            (assoc :thumbnails {})
            (update :files (fnil assoc {}) file-id file)
            ;; A minimal editable local team entry keeps existing derived refs
            ;; and any workspace code that reads team permissions functional.
            (update :teams (fnil assoc {})
                    team-id
                    {:id team-id
                     :name "Embedded"
                     :permissions {:can-edit true
                                   :is-admin true
                                   :is-owner true}})))

      ptk/WatchEvent
      (watch [_ _ _]
        ;; Shape layout is local editor infrastructure and does not require
        ;; project/file persistence services.
        (rx/of (dwsl/initialize-shape-layout))))))

(defn finalize
  []
  (ptk/reify ::finalize
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (dissoc :embedded-workspace?
                  :current-team-id
                  :current-project-id
                  :current-file-id
                  :current-page-id
                  :workspace-ready
                  :workspace-editor-state
                  :workspace-wasm-editor-styles
                  :workspace-media-objects
                  :workspace-presence
                  :workspace-tokens
                  :workspace-undo
                  :workspace-versions)
          (update :workspace-global dissoc :read-only?)
          ;; Keep the in-memory file available until the runtime is destroyed
          ;; so host code can snapshot it after UI unmount if needed.
          (update :files identity)))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (dwsl/finalize-shape-layout)))))
