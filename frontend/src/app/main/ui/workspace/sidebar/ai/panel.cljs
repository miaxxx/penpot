;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.main.ui.workspace.sidebar.ai.panel
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.workspace.ai.context :as ai-context]
   [app.main.repo :as rp]
   [app.util.dom :as dom]
   [beicon.v2.core :as rx]
   [rumext.v2 :as mf]))

(def ^:private scope-options
  [{:id :selection :label "Selection"}
   {:id :page :label "Page"}
   {:id :component :label "Component"}])

(def ^:private mode-options
  [{:id :generate :label "Generate"}
   {:id :modify :label "Modify"}
   {:id :refactor :label "Refactor"}
   {:id :adapt :label "Adapt"}])

(defn- event-value
  [event]
  (.. event -target -value))

(mf/defc choice-row*
  {::mf/private true}
  [{:keys [label options value on-change]}]
  [:div {:class (stl/css :choice-group)}
   [:div {:class (stl/css :field-label)} label]
   [:div {:class (stl/css :choice-row)}
    (for [{:keys [id label]} options]
      [:button {:key (name id)
                :type "button"
                :class (stl/css-case :choice true
                                     :choice-selected (= id value))
                :on-click #(on-change id)}
       label])]])

(mf/defc provider-settings*
  {::mf/private true}
  [{:keys [open? on-close]}]
  (let [provider* (mf/use-state "openai-compatible")
        base-url* (mf/use-state "https://api.openai.com/v1")
        model* (mf/use-state "gpt-4.1-mini")
        api-key* (mf/use-state "")
        temperature* (mf/use-state "0.2")
        max-tokens* (mf/use-state "4096")
        status* (mf/use-state :idle)

        on-success
        (mf/use-fn
         (fn [_]
           (reset! status* :ok)))

        on-error
        (mf/use-fn
         (fn [_]
           (reset! status* :error)))

        on-test
        (mf/use-fn
         (fn [event]
           (dom/prevent-default event)
           (if (and (seq @base-url*) (seq @model*) (seq @api-key*))
             (do
               (reset! status* :testing)
               (->> (rp/cmd! :test-ai-provider
                             {:provider @provider*
                              :base-url @base-url*
                              :api-key @api-key*
                              :model @model*})
                    (rx/subs! on-success on-error)))
             (reset! status* :missing-fields))))]
    (when open?
      [:div {:class (stl/css :settings-panel)}
       [:div {:class (stl/css :section-heading)}
        [:div
         [:div {:class (stl/css :section-title)} "Provider settings"]
         [:div {:class (stl/css :section-subtitle)}
          "Credentials stay in memory for this panel session and are sent only to the Penpot backend proxy."]]
        [:button {:type "button"
                  :class (stl/css :icon-button)
                  :aria-label "Close provider settings"
                  :on-click on-close}
         "×"]]

       [:label {:class (stl/css :field)}
        [:span {:class (stl/css :field-label)} "Provider"]
        [:select {:value @provider*
                  :on-change #(reset! provider* (event-value %))}
         [:option {:value "openai-compatible"} "OpenAI Compatible"]
         [:option {:value "openai"} "OpenAI"]]]

       [:label {:class (stl/css :field)}
        [:span {:class (stl/css :field-label)} "API base URL"]
        [:input {:type "url"
                 :value @base-url*
                 :on-change #(reset! base-url* (event-value %))}]]

       [:label {:class (stl/css :field)}
        [:span {:class (stl/css :field-label)} "API key"]
        [:input {:type "password"
                 :autocomplete "off"
                 :value @api-key*
                 :placeholder "Session only"
                 :on-change #(reset! api-key* (event-value %))}]]

       [:label {:class (stl/css :field)}
        [:span {:class (stl/css :field-label)} "Model"]
        [:input {:type "text"
                 :value @model*
                 :on-change #(reset! model* (event-value %))}]]

       [:div {:class (stl/css :settings-grid)}
        [:label {:class (stl/css :field)}
         [:span {:class (stl/css :field-label)} "Temperature"]
         [:input {:type "number"
                  :min "0"
                  :max "2"
                  :step "0.1"
                  :value @temperature*
                  :on-change #(reset! temperature* (event-value %))}]]
        [:label {:class (stl/css :field)}
         [:span {:class (stl/css :field-label)} "Max tokens"]
         [:input {:type "number"
                  :min "256"
                  :max "32768"
                  :value @max-tokens*
                  :on-change #(reset! max-tokens* (event-value %))}]]]

       [:button {:type "button"
                 :class (stl/css :secondary-button)
                 :disabled (= :testing @status*)
                 :on-click on-test}
        (if (= :testing @status*) "Testing…" "Test connection")]

       (case @status*
         :missing-fields
         [:div {:class (stl/css :status-message :status-warning)}
          "Complete the base URL, model and API key first."]

         :ok
         [:div {:class (stl/css :status-message)}
          "Connection successful. The key remains session-only."]

         :error
         [:div {:class (stl/css :status-message :status-warning)}
          "Connection failed. Verify the provider URL, model access and credential."]

         nil)])))

(mf/defc plan-card*
  {::mf/private true}
  [{:keys [proposal on-discard]}]
  (when proposal
    [:div {:class (stl/css :proposal)}
     [:div {:class (stl/css :proposal-label)} "Proposed transaction"]
     [:div {:class (stl/css :proposal-title)} (:title proposal)]
     [:ul {:class (stl/css :plan-list)}
      (for [item (:plan proposal)]
        [:li {:key item} item])]
     [:div {:class (stl/css :diff-card)}
      [:div [:strong "+ " (:created proposal)] " nodes"]
      [:div [:strong "~ " (:modified proposal)] " nodes"]
      [:div [:strong "− " (:removed proposal)] " nodes"]]
     [:div {:class (stl/css :proposal-note)}
      "Preview only — no Penpot changes have been committed."]
     [:div {:class (stl/css :proposal-actions)}
      [:button {:type "button"
                :class (stl/css :ghost-button)
                :on-click on-discard}
       "Discard"]
      [:button {:type "button"
                :class (stl/css :secondary-button)
                :on-click #(js/console.info "AI proposal remains in preview state")}
       "Continue editing"]
      [:button {:type "button"
                :class (stl/css :primary-button)
                :disabled true
                :title "The Penpot Change compiler is intentionally not connected in the foundation PR."}
       "Apply design"]]]))

(mf/defc panel*
  [{:keys [objects selected page-id file-id]}]
  (let [scope* (mf/use-state :selection)
        mode* (mf/use-state :generate)
        draft* (mf/use-state "")
        settings-open?* (mf/use-state false)
        proposal* (mf/use-state nil)
        messages* (mf/use-state [])

        on-submit
        (mf/use-fn
         (fn [event]
           (dom/prevent-default event)
           (when (seq @draft*)
             (let [context (ai-context/build-context
                            @scope*
                            {:objects objects
                             :selected selected
                             :page-id page-id
                             :file-id file-id})
                   request @draft*
                   selected-count (count selected)
                   modifying? (contains? #{:modify :refactor :adapt} @mode*)]
               (swap! messages* conj {:role :user :content request})
               (reset! proposal*
                       {:title (str (name @mode*) " in " (name @scope*))
                        :plan [(str "Read scoped context (" selected-count " selected nodes)")
                               "Produce schema-validated Document or Patch DSL"
                               "Compile into temporary Penpot changes"
                               "Show canvas and property-level diff before commit"]
                        :created (if modifying? 0 6)
                        :modified (if modifying? (max 1 selected-count) 0)
                        :removed 0
                        :context context})
               (reset! draft* ""))))]
    [:div {:class (stl/css :ai-panel)}
     [:div {:class (stl/css :panel-header)}
      [:div
       [:div {:class (stl/css :panel-title)} "AI Assistant"]
       [:div {:class (stl/css :panel-subtitle)} "Transactional design agent"]]
      [:button {:type "button"
                :class (stl/css :settings-button)
                :aria-label "AI provider settings"
                :on-click #(swap! settings-open?* not)}
       "⚙"]]

     [:> provider-settings*
      {:open? @settings-open?*
       :on-close #(reset! settings-open?* false)}]

     [:div {:class (stl/css :controls)}
      [:> choice-row* {:label "Scope"
                       :options scope-options
                       :value @scope*
                       :on-change #(reset! scope* %)}]
      [:> choice-row* {:label "Mode"
                       :options mode-options
                       :value @mode*
                       :on-change #(reset! mode* %)}]]

     [:div {:class (stl/css :conversation)}
      (if (empty? @messages*)
        [:div {:class (stl/css :empty-state)}
         [:div {:class (stl/css :empty-title)} "Describe a structured UI change"]
         [:p "The assistant will inspect only the selected scope, return a plan and keep every generated change in preview until you confirm."]]
        (for [[index message] (map-indexed vector @messages*)]
          [:div {:key index
                 :class (stl/css :message :message-user)}
           (:content message)]))

      [:> plan-card*
       {:proposal @proposal*
        :on-discard #(reset! proposal* nil)}]]

     [:form {:class (stl/css :composer)
             :on-submit on-submit}
      [:textarea {:value @draft*
                  :rows 3
                  :placeholder "Describe what you want to generate or modify…"
                  :on-change #(reset! draft* (event-value %))}]
      [:button {:type "submit"
                :class (stl/css :primary-button)
                :disabled (empty? @draft*)}
       "Send"]]]))
