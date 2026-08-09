;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.main.ui.workspace.sidebar.ai.harness
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.repo :as rp]
   [beicon.v2.core :as rx]
   [clojure.string :as str]
   [rumext.v2 :as mf]))

(defn- file-from-event
  [event]
  (aget (.. event -target -files) 0))

(defn- base64-from-data-url
  [value]
  (second (str/split (str value) #"," 2)))

(defn- read-package!
  [file on-ready on-error]
  (let [reader (js/FileReader.)
        filename (.-name file)
        zip? (str/ends-with? (str/lower-case filename) ".zip")]
    (set! (.-onerror reader)
          (fn [_] (on-error {:hint "Could not read the selected file."})))
    (set! (.-onload reader)
          (fn [event]
            (let [result (.. event -target -result)]
              (on-ready
               {:filename filename
                :encoding (if zip? "base64" "utf-8")
                :content (if zip?
                           (base64-from-data-url result)
                           (str result))
                :enabled true}))))
    (if zip?
      (.readAsDataURL reader file)
      (.readAsText reader file))))

(defn- toggle-set!
  [state* value]
  (swap! state*
         (fn [values]
           (let [values (set values)]
             (if (contains? values value)
               (disj values value)
               (conj values value))))))

(mf/defc module-grid*
  {::mf/private true}
  [{:keys [modules]}]
  [:div {:class (stl/css :module-grid)}
   (for [{:keys [id label status description]} modules]
     [:div {:key (name id)
            :class (stl/css :module-card)}
      [:div {:class (stl/css :module-card-heading)}
       [:strong label]
       [:span {:class (stl/css :module-status)}
        (name status)]]
      [:div {:class (stl/css :module-description)}
       description]])])

(mf/defc skill-row*
  {::mf/private true}
  [{:keys [skill selected? on-select on-enabled on-delete]}]
  (let [builtin? (= :builtin (:source skill))]
    [:div {:class (stl/css :skill-row)}
     [:label {:class (stl/css :skill-main)}
      [:input {:type "checkbox"
               :checked selected?
               :on-change on-select}]
      [:span
       [:span {:class (stl/css :skill-name)}
        (:name skill)]
       [:span {:class (stl/css :skill-meta)}
        (str (:version skill)
             " · "
             (name (or (:source skill) :upload)))]
       (when (seq (:description skill))
         [:span {:class (stl/css :skill-description)}
          (:description skill)])]]
     (when-not builtin?
       [:div {:class (stl/css :skill-actions)}
        [:button {:type "button"
                  :class (stl/css :mini-button)
                  :on-click on-enabled}
         (if (:enabled skill) "Disable" "Enable")]
        [:button {:type "button"
                  :class (stl/css :mini-button :danger-button)
                  :on-click on-delete}
         "Delete"]])]))

(mf/defc harness-panel*
  [{:keys [selected-skills* coordinator?* persona* input-mode*]}]
  (let [open?* (mf/use-state false)
        status* (mf/use-state :loading)
        message* (mf/use-state nil)
        modules* (mf/use-state [])
        skills* (mf/use-state [])
        plugins* (mf/use-state [])

        set-error!
        (mf/use-fn
         (fn [error]
           (reset! status* :error)
           (reset! message*
                   (or (:hint error)
                       "Harness request failed."))))

        load!
        (mf/use-fn
         (fn []
           (reset! status* :loading)
           (->> (rp/cmd! :get-ai-harness {})
                (rx/subs!
                 (fn [report]
                   (reset! modules* (:modules report))
                   (reset! skills* (get-in report [:skills :installed] []))
                   (->> (rp/cmd! :list-ai-harness-skills {})
                        (rx/subs!
                         (fn [skills]
                           (reset! skills* skills)
                           (reset! status* :ready))
                         set-error!)))
                 set-error!))
           (->> (rp/cmd! :list-ai-harness-plugins {})
                (rx/subs!
                 (fn [plugins] (reset! plugins* plugins))
                 (fn [_] nil)))))

        install!
        (mf/use-fn
         (fn [kind event]
           (when-let [file (file-from-event event)]
             (reset! status* :uploading)
             (read-package!
              file
              (fn [payload]
                (->> (rp/cmd!
                      (if (= kind :skill)
                        :install-ai-harness-skill
                        :install-ai-harness-plugin)
                      payload)
                     (rx/subs!
                      (fn [_]
                        (reset! message*
                                (if (= kind :skill)
                                  "Skill installed."
                                  "Plugin installed."))
                        (load!))
                      set-error!)))
              set-error!))
           (set! (.. event -target -value) "")))

        set-skill-enabled!
        (mf/use-fn
         (fn [skill]
           (->> (rp/cmd! :set-ai-harness-skill-enabled
                         {:id (:skill-id skill)
                          :enabled (not (:enabled skill))})
                (rx/subs! (fn [_] (load!)) set-error!))))

        delete-skill!
        (mf/use-fn
         (fn [skill]
           (->> (rp/cmd! :delete-ai-harness-skill
                         {:id (:skill-id skill)})
                (rx/subs!
                 (fn [_]
                   (swap! selected-skills* disj (:skill-id skill))
                   (load!))
                 set-error!))))

        set-plugin-enabled!
        (mf/use-fn
         (fn [plugin]
           (->> (rp/cmd! :set-ai-harness-plugin-enabled
                         {:id (:plugin-id plugin)
                          :enabled (not (:enabled plugin))})
                (rx/subs! (fn [_] (load!)) set-error!))))

        delete-plugin!
        (mf/use-fn
         (fn [plugin]
           (->> (rp/cmd! :delete-ai-harness-plugin
                         {:id (:plugin-id plugin)})
                (rx/subs! (fn [_] (load!)) set-error!))))]

    (mf/use-effect
     (mf/deps)
     (fn [] (load!)))

    [:div {:class (stl/css :harness-shell)}
     [:button {:type "button"
               :class (stl/css :harness-toggle)
               :on-click #(swap! open?* not)}
      [:span
       [:strong "Harness Engineering"]
       [:span {:class (stl/css :harness-caption)}
        "Skills · Commands · Context · Coordinator · Plugins"]]
      [:span (if @open?* "−" "+")]]

     (when @open?*
       [:div {:class (stl/css :harness-body)}
        [:div {:class (stl/css :harness-controls)}
         [:label
          [:span "Persona"]
          [:select {:value (name @persona*)
                    :on-change
                    #(reset! persona*
                             (keyword (.. % -target -value)))}
           [:option {:value "assistant"} "Assistant"]
           [:option {:value "buddy"} "Buddy"]]]
         [:label
          [:span "Input"]
          [:select {:value (name @input-mode*)
                    :on-change
                    #(reset! input-mode*
                             (keyword (.. % -target -value)))}
           [:option {:value "assistant"} "Assistant"]
           [:option {:value "voice"} "Voice transcript"]
           [:option {:value "vim"} "Vim command"]
           [:option {:value "remote"} "Remote session"]]]
         [:label {:class (stl/css :coordinator-toggle)}
          [:input {:type "checkbox"
                   :checked @coordinator?*
                   :on-change #(swap! coordinator?* not)}]
          [:span "Multi-agent coordinator"]]]

        [:div {:class (stl/css :upload-row)}
         [:label {:class (stl/css :upload-button)}
          "Upload Skill"
          [:input {:type "file"
                   :accept ".md,.json,.zip"
                   :on-change #(install! :skill %)}]]
         [:label {:class (stl/css :upload-button)}
          "Upload Plugin"
          [:input {:type "file"
                   :accept ".json,.zip"
                   :on-change #(install! :plugin %)}]]
         [:span {:class (stl/css :upload-note)}
          "SKILL.md, JSON or bounded ZIP · data only · no arbitrary code"]]

        (when @message*
          [:div {:class (stl/css :harness-message)}
           @message*])

        [:div {:class (stl/css :harness-section-title)}
         (str "Skills · "
              (count @selected-skills*)
              " selected")]
        [:div {:class (stl/css :skill-list)}
         (for [skill @skills*]
           [:> skill-row*
            {:key (:skill-id skill)
             :skill skill
             :selected?
             (contains? @selected-skills* (:skill-id skill))
             :on-select
             #(toggle-set! selected-skills* (:skill-id skill))
             :on-enabled #(set-skill-enabled! skill)
             :on-delete #(delete-skill! skill)}])]

        (when (seq @plugins*)
          [:<>
           [:div {:class (stl/css :harness-section-title)}
            "Installed plugins"]
           [:div {:class (stl/css :plugin-list)}
            (for [plugin @plugins*]
              [:div {:key (:plugin-id plugin)
                     :class (stl/css :plugin-row)}
               [:span
                [:strong (:name plugin)]
                [:span
                 (str (:version plugin)
                      (if (:enabled plugin) " · enabled" " · disabled"))]]
               [:div {:class (stl/css :skill-actions)}
                [:button {:type "button"
                          :class (stl/css :mini-button)
                          :on-click #(set-plugin-enabled! plugin)}
                 (if (:enabled plugin) "Disable" "Enable")]
                [:button {:type "button"
                          :class (stl/css :mini-button :danger-button)
                          :on-click #(delete-plugin! plugin)}
                 "Delete"]]])]])

        [:div {:class (stl/css :harness-section-title)}
         "Capability modules"]
        [:> module-grid* {:modules @modules*}]

        (when (= @status* :uploading)
          [:div {:class (stl/css :harness-message)}
           "Validating and installing package…"])
        (when (= @status* :error)
          [:div {:class (stl/css :harness-error)}
           @message*])])]))
