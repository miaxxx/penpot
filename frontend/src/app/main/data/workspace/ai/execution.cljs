;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.main.data.workspace.ai.execution
  "Native Penpot preview and atomic transaction boundary for AI proposals."
  (:require
   [app.common.ai.canvas :as canvas]
   [app.common.ai.compat :as compat]
   [app.common.ai.compiler :as compiler]
   [app.common.ai.patch :as patch]
   [app.common.ai.validation :as validation]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.shapes-helpers :as cfsh]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.undo :as dwu]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(def ^:private tree-attrs
  #{:parent-id :frame-id :shapes})

(defn- object-depth
  [objects id]
  (loop [current id depth 0 seen #{}]
    (let [parent-id (:parent-id (get objects current))]
      (if (or (nil? parent-id)
              (= current parent-id)
              (contains? seen parent-id)
              (nil? (get objects parent-id)))
        depth
        (recur parent-id (inc depth) (conj seen current))))))

(defn- changed-attrs
  [before after]
  (->> (concat (keys before) (keys after))
       set
       (remove tree-attrs)
       (filter #(not= (get before %) (get after %)))
       set))

(defn- validate-result-shapes!
  [after diff]
  (doseq [id (concat (:created diff) (keys (:modified diff)))]
    (when-let [shape (get after id)]
      (cts/check-shape shape)))
  after)

(defn- apply-removals
  [changes before removed]
  (if (seq removed)
    (pcb/remove-objects
     changes
     (sort-by #(object-depth before %) > removed)
     {:ignore-touched false})
    changes))

(defn- apply-additions
  [changes after created]
  (reduce
   (fn [changes id]
     (let [shape (get after id)
           objects (pcb/lookup-objects changes)
           [_ changes] (cfsh/prepare-add-shape changes shape objects)]
       changes))
   changes
   (sort-by #(object-depth after %) created)))

(defn- apply-moves
  [changes after moved]
  (reduce
   (fn [changes id]
     (let [objects (pcb/lookup-objects changes)
           shape (get objects id)
           destination (get after id)
           parent-id (:parent-id destination)
           parent (get after parent-id)
           index (first (keep-indexed (fn [index child-id]
                                        (when (= id child-id) index))
                                      (:shapes parent)))]
       (if (and shape parent-id parent)
         (pcb/change-parent changes parent-id [shape] index
                            {:ignore-touched true})
         changes)))
   changes
   (sort-by #(object-depth after %) moved)))

(defn- apply-modifications
  [changes before after modified created removed]
  (reduce-kv
   (fn [changes id _]
     (if (or (contains? created id)
             (contains? removed id))
       changes
       (let [old (get before id)
             new (get after id)
             attrs (changed-attrs old new)]
         (if (seq attrs)
           (pcb/update-shapes changes [id] (constantly new)
                              {:attrs attrs
                               :ignore-touched true})
           changes))))
   changes
   modified))

(defn prepare-object-diff
  "Compiles an in-memory before/after graph into native Penpot redo and undo
  changes. No workspace state, collaboration history or persistence is mutated.

  The compatibility pass repairs semantic writes into canonical Penpot Shape
  structures before validation, preview and commit."
  [origin page-id before after]
  (let [after (compat/finalize-objects before after)
        diff (patch/diff-objects before after)
        _ (validate-result-shapes! after diff)
        changes (-> (pcb/empty-changes origin page-id)
                    (pcb/with-objects before)
                    (apply-removals before (:removed diff))
                    (apply-additions after (:created diff))
                    (apply-moves after (:moved diff))
                    (apply-modifications before after (:modified diff)
                                         (:created diff) (:removed diff)))
        affected (into #{}
                       (concat (:created diff)
                               (:removed diff)
                               (:moved diff)
                               (keys (:modified diff))))
        parent-ids (into #{}
                         (keep (fn [id]
                                 (or (:parent-id (get after id))
                                     (:parent-id (get before id)))))
                         affected)]
    {:changes changes
     :objects (pcb/lookup-objects changes)
     :target-objects after
     :base-objects (select-keys before (into affected parent-ids))
     :affected-ids affected
     :parent-ids parent-ids
     :diff diff}))

(defn- scope-type
  [scope]
  (let [value (:type scope)]
    (if (keyword? value) value (some-> value keyword))))

(defn- scope-root
  [scope]
  (or (:root-id scope) (:rootId scope)))

(defn- compatible-scope?
  [expected actual]
  (and (= (scope-type expected) (scope-type actual))
       (or (not (contains? #{:selection :component} (scope-type expected)))
           (nil? (scope-root expected))
           (= (str (scope-root expected))
              (str (scope-root actual))))))

(defn proposal-from-document
  [{:keys [file-id page-id revision objects scope document parent-id
           parent-frame-id registry]}]
  (let [validated (validation/validate-document document)]
    (if-not (:valid? validated)
      {:valid? false :errors (:errors validated) :warnings (:warnings validated)}
      (let [snapshot (canvas/build-snapshot
                      {:file-id file-id
                       :page-id page-id
                       :revision revision
                       :objects objects
                       :scope scope})]
        (cond
          (nil? parent-id)
          {:valid? false
           :errors [{:code :missing-target-parent
                     :message "Select a frame/container before generating a document."}]
           :warnings []}

          (not (contains? (:scope-ids snapshot) parent-id))
          {:valid? false
           :errors [{:code :out-of-scope
                     :message "Generated document target is outside the active scope."}]
           :warnings []}

          :else
          (let [compiled (compiler/compile-document
                          (:ir validated)
                          {:parent-id parent-id
                           :parent-frame-id parent-frame-id
                           :registry registry})]
            (if-not (:valid? compiled)
              {:valid? false
               :errors (:errors compiled)
               :warnings (:warnings compiled)}
              (let [root-id (first (:root-ids compiled))
                    after (reduce (fn [result shape]
                                    (assoc result (:id shape) shape))
                                  objects
                                  (:shapes compiled))
                    after (update-in after [parent-id :shapes]
                                     (fn [children]
                                       (conj (vec (or children [])) root-id)))
                    prepared (prepare-object-diff ::document page-id objects after)]
                (merge prepared
                       {:valid? true
                        :type :document
                        :snapshot snapshot
                        :semantic-index (:semantic-index compiled)
                        :warnings (:warnings compiled)
                        :errors []})))))))))

(defn proposal-from-patch
  [{:keys [file-id page-id revision objects patch registry scope]}]
  (let [validated (validation/validate-patch patch)]
    (if-not (:valid? validated)
      {:valid? false :errors (:errors validated) :warnings (:warnings validated)}
      (let [patch-ir (:ir validated)
            patch-scope (:scope patch-ir)]
        (if (and scope (not (compatible-scope? scope patch-scope)))
          {:valid? false
           :errors [{:code :scope-escalation
                     :message "Patch scope differs from the active canvas scope."}]
           :warnings []}
          (let [effective-scope (or scope patch-scope)
                patch-ir (assoc patch-ir :scope effective-scope)
                snapshot (canvas/build-snapshot
                          {:file-id file-id
                           :page-id page-id
                           :revision revision
                           :objects objects
                           :scope effective-scope})
                compiled (compiler/compile-patch snapshot patch-ir
                                                 :registry registry)]
            (if-not (:valid? compiled)
              {:valid? false
               :errors (:errors compiled)
               :warnings (:warnings compiled)}
              (merge (prepare-object-diff ::patch page-id objects
                                          (:objects compiled))
                     {:valid? true
                      :type :patch
                      :snapshot snapshot
                      :warnings (:warnings compiled)
                      :errors []}))))))))

(defn proposal-stale?
  [current-objects {:keys [base-objects affected-ids]}]
  (or
   (some (fn [[id object]]
           (not= object (get current-objects id)))
         base-objects)
   (some (fn [id]
           (and (not (contains? base-objects id))
                (contains? current-objects id)))
         affected-ids)))

(defn- callback-event
  [callback payload]
  (ptk/reify ::callback-event
    ptk/EffectEvent
    (effect [_ _ _]
      (when callback
        (callback payload)))))

(defn apply-proposal
  "The only native AI write gateway. Revalidates current objects, submits one
  Penpot Undo transaction and reports completion/conflict to the unified
  proposal lifecycle."
  [{:keys [page-id target-objects parent-ids proposal-id apply-token
           on-applied on-conflict]
    :as proposal}]
  (ptk/reify ::apply-proposal
    ptk/WatchEvent
    (watch [it state _]
      (let [current (dsh/lookup-page-objects state page-id)]
        (if (proposal-stale? current proposal)
          (let [payload {:proposal-id proposal-id
                         :apply-token apply-token
                         :page-id page-id
                         :affected-ids (:affected-ids proposal)
                         :error {:code :stale-canvas
                                 :message "Canvas changed after proposal preview."}}]
            (rx/from
             [(ptk/data-event :ai/proposal-conflict payload)
              (callback-event on-conflict payload)]))
          (let [{:keys [changes]}
                (prepare-object-diff it page-id current target-objects)
                transaction-id (uuid/next)
                payload {:proposal-id proposal-id
                         :apply-token apply-token
                         :transaction-id (str transaction-id)
                         :page-id page-id
                         :affected-ids (:affected-ids proposal)}
                events (cond-> [(dwu/start-undo-transaction transaction-id)
                                (dch/commit-changes changes)]
                         (seq parent-ids)
                         (conj (ptk/data-event :layout/update {:ids parent-ids}))

                         :always
                         (conj (dwu/commit-undo-transaction transaction-id)
                               (ptk/data-event :ai/proposal-applied payload)
                               (callback-event on-applied payload)))]
            (rx/from events)))))))
