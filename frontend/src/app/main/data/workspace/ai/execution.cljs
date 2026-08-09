;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.main.data.workspace.ai.execution
  "Native Penpot Change transaction boundary for AI-generated shapes.

  The model never calls this namespace. A validated compiler produces complete
  Penpot shapes in parent-before-child order. Preview uses the local objects
  snapshot returned by `prepare-add-objects`; apply commits the exact same
  redo/undo pair through Penpot's existing persistence and Undo machinery."
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.undo :as dwu]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn prepare-add-objects
  "Builds native redo/undo changes and a temporary page-object snapshot.

  `shapes` must already be validated Penpot shapes ordered parent-first. No
  workspace state, persistence queue, collaboration history or undo stack is
  touched by this function."
  [origin page-id objects shapes]
  (let [changes (-> (pcb/empty-changes origin page-id)
                    (pcb/with-objects objects)
                    (pcb/add-objects shapes))]
    {:changes changes
     :objects (pcb/lookup-objects changes)
     :summary {:created (count shapes)
               :modified 0
               :removed 0
               :parent-ids (into #{} (keep :parent-id) shapes)}}))

(defn apply-add-objects
  "Commits a previously validated set of shapes as one AI undo transaction.

  This event intentionally rebuilds the change set from current objects at
  apply time. The caller must perform revision/scope conflict validation before
  emitting it; stale proposals must not reach this boundary."
  [{:keys [page-id shapes]}]
  (ptk/reify ::apply-add-objects
    ptk/WatchEvent
    (watch [it state _]
      (let [objects (dsh/lookup-page-objects state page-id)
            {:keys [changes summary]}
            (prepare-add-objects it page-id objects shapes)
            transaction-id (js/Symbol)
            parent-ids (:parent-ids summary)]
        (rx/of
         (dwu/start-undo-transaction transaction-id)
         (dch/commit-changes changes)
         (when (seq parent-ids)
           (ptk/data-event :layout/update {:ids parent-ids}))
         (dwu/commit-undo-transaction transaction-id))))))
