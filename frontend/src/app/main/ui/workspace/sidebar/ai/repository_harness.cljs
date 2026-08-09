;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.main.ui.workspace.sidebar.ai.repository-harness
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.refs :as refs]
   [app.main.repo :as rp]
   [beicon.v2.core :as rx]
   [rumext.v2 :as mf]))

(defn- invoke!
  [action arguments on-success on-error]
  (->> (rp/cmd! :invoke-ai-harness-repository
                {:action action
                 :arguments (or arguments {})})
       (rx/subs! on-success on-error)))

(defn- status-label
  [value]
  (if value (name value) "not-started"))

(mf/defc check-row*
  {::mf/private true}
  [{:keys [result]}]
  [:div {:class (stl/css :repository-check)}
   [:span {:class (stl/css-case
                   :repository-check-dot true
                   :repository-check-pass (= :passed (:status result))
                   :repository-check-fail (= :failed (:status result)))}]
   [:span (name (:check-id result))]
   [:span {:class (stl/css :repository-check-status)}
    (status-label (:status result))]])

(mf/defc repository-harness*
  []
  (let [file (mf/deref refs/file)
        page-id (mf/deref refs/current-page-id)
        file-id (:id file)
        workspace* (mf/use-state nil)
        environment* (mf/use-state nil)
        run* (mf/use-state nil)
        artifacts* (mf/use-state [])
        checks* (mf/use-state [])
        status* (mf/use-state :idle)
        error* (mf/use-state nil)

        fail!
        (mf/use-fn
         (fn [error]
           (reset! status* :error)
           (reset! error* (or (:hint error) "Repository Harness request failed."))))

        load-run!
        (mf/use-fn
         (fn [workspace-id]
           (invoke!
            "run.latest" {:workspace-id workspace-id}
            (fn [run]
              (reset! run* run)
              (if-let [run-id (:run-id run)]
                (invoke! "checks.results" {:run-id run-id}
                         #(reset! checks* %) fail!)
                (reset! checks* [])))
            fail!)))

        load-workspace!
        (mf/use-fn
         (fn []
           (when (and file-id page-id)
             (reset! status* :loading)
             (invoke!
              "workspace.ensure"
              {:file-id file-id
               :page-id page-id
               :name "Penpot AI Harness"}
              (fn [workspace]
                (let [workspace-id (:workspace-id workspace)]
                  (reset! workspace* workspace)
                  (invoke! "artifact.list" {:workspace-id workspace-id}
                           #(reset! artifacts* %) fail!)
                  (invoke! "environment.latest" {:workspace-id workspace-id}
                           #(reset! environment* %) (fn [_] nil))
                  (load-run! workspace-id)
                  (reset! status* :ready)))
              fail!))))

        export!
        (mf/use-fn
         (fn []
           (when-let [workspace-id (:workspace-id @workspace*)]
             (invoke!
              "package.export" {:workspace-id workspace-id}
              (fn [package]
                (-> js/navigator .-clipboard
                    (.writeText (js/JSON.stringify (clj->js package) nil 2)))
                (reset! status* :copied))
              fail!))))]

    (mf/use-effect
     (mf/deps file-id page-id)
     (fn []
       (load-workspace!)
       nil))

    [:div {:class (stl/css :repository-harness)}
     [:div {:class (stl/css :repository-header)}
      [:div
       [:strong "Repository Harness 2.0"]
       [:span {:class (stl/css :repository-caption)}
        "Instructions · Tools · Environment · State · Feedback"]]
      [:div {:class (stl/css :repository-actions)}
       [:button {:type "button" :on-click load-workspace!} "Refresh"]
       [:button {:type "button" :on-click export!} "Copy package"]]]

     (when @error*
       [:div {:class (stl/css :repository-error)} @error*])

     [:div {:class (stl/css :repository-stats)}
      [:div
       [:span "Environment"]
       [:strong (status-label (get-in @environment* [:report :status]
                                     (:status @environment*)))] ]
      [:div
       [:span "Run"]
       [:strong (status-label (:status @run*))]]
      [:div
       [:span "Checks"]
       [:strong
        (str (count (filter #(= :passed (:status %)) @checks*))
             "/"
             (max 12 (count @checks*)))]]]

     (when-let [run @run*]
       [:div {:class (stl/css :repository-run)}
        [:div [:span "Goal"] [:strong (or (:goal run) "Not started")]]
        [:div [:span "Next"] [:span (or (:next-action run) "Inspect environment")]]
        (when (seq (:blockers run))
          [:div {:class (stl/css :repository-error)}
           (str "Blocked: " (count (:blockers run)) " issue(s)")])])

     (when (seq @checks*)
       [:details {:class (stl/css :repository-checks)}
        [:summary "Verification evidence"]
        (for [result @checks*]
          [:> check-row* {:key (:check-id result) :result result}])])

     [:details {:class (stl/css :repository-artifacts)}
      [:summary (str "Harness artifacts · " (count @artifacts*))]
      (for [artifact @artifacts*]
        [:div {:key (:artifact-id artifact)
               :class (stl/css :repository-artifact)}
         [:span (:path artifact)]
         [:span (name (:kind artifact))]])]

     (when (= @status* :copied)
       [:div {:class (stl/css :repository-note)}
        "Harness package copied as JSON."])]))
