;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.main.ui.workspace.sidebar.ai.panel
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.ai.canvas :as ai-canvas]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.ai.execution :as ai-exec]
   [app.main.repo :as rp]
   [app.main.store :as st]
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

(defn- parse-number
  [value fallback]
  (let [number (js/parseFloat value)]
    (if (js/isNaN number) fallback number)))

(defn- parse-int
  [value fallback]
  (let [number (js/parseInt value 10)]
    (if (js/isNaN number) fallback number)))

(defn- as-keyword
  [value]
  (cond
    (keyword? value) value
    (string? value) (keyword value)
    :else nil))

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
  [{:keys [open? on-close config*]}]
  (let [{:keys [provider base-url model api-key temperature max-tokens]} @config*
        status* (mf/use-state :idle)

        update-config!
        (fn [key value]
          (swap! config* assoc key value))

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
           (if (and (seq base-url) (seq model) (seq api-key))
             (do
               (reset! status* :testing)
               (->> (rp/cmd! :test-ai-provider
                             {:provider provider
                              :base-url base-url
                              :api-key api-key
                              :model model})
                    (rx/subs! on-success on-error)))
             (reset! status* :missing-fields))))]
    (when open?
      [:div {:class (stl/css :settings-panel)}
       [:div {:class (stl/css :section-heading)}
        [:div
         [:div {:class (stl/css :section-title)} "Provider settings"]
         [:div {:class (stl/css :section-subtitle)}
          "Credentials stay in memory and are sent only through the Penpot backend proxy."]]
        [:button {:type "button"
                  :class (stl/css :icon-button)
                  :aria-label "Close provider settings"
                  :on-click on-close}
         "×"]]

       [:label {:class (stl/css :field)}
        [:span {:class (stl/css :field-label)} "Provider"]
        [:select {:value provider
                  :on-change #(update-config! :provider (event-value %))}
         [:option {:value "openai-compatible"} "OpenAI Compatible"]
         [:option {:value "openai"} "OpenAI"]]]

       [:label {:class (stl/css :field)}
        [:span {:class (stl/css :field-label)} "API base URL"]
        [:input {:type "url"
                 :value base-url
                 :on-change #(update-config! :base-url (event-value %))}]]

       [:label {:class (stl/css :field)}
        [:span {:class (stl/css :field-label)} "API key"]
        [:input {:type "password"
                 :autocomplete "off"
                 :value api-key
                 :placeholder "Session only"
                 :on-change #(update-config! :api-key (event-value %))}]]

       [:label {:class (stl/css :field)}
        [:span {:class (stl/css :field-label)} "Model"]
        [:input {:type "text"
                 :value model
                 :on-change #(update-config! :model (event-value %))}]]

       [:div {:class (stl/css :settings-grid)}
        [:label {:class (stl/css :field)}
         [:span {:class (stl/css :field-label)} "Temperature"]
         [:input {:type "number"
                  :min "0"
                  :max "2"
                  :step "0.1"
                  :value temperature
                  :on-change #(update-config! :temperature
                                              (parse-number (event-value %) 0.2))}]]
        [:label {:class (stl/css :field)}
         [:span {:class (stl/css :field-label)} "Max tokens"]
         [:input {:type "number"
                  :min "256"
                  :max "32768"
                  :value max-tokens
                  :on-change #(update-config! :max-tokens
                                              (parse-int (event-value %) 4096))}]]]

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

(mf/defc render-errors*
  {::mf/private true}
  [{:keys [errors]}]
  (when (seq errors)
    [:div {:class (stl/css :status-message :status-warning)}
     [:strong "Proposal rejected"]
     [:ul {:class (stl/css :plan-list)}
      (for [[index error] (map-indexed vector errors)]
        [:li {:key index}
         (or (:message error)
             (some-> (:code error) name)
             "Unknown validation error")])]]))

(mf/defc plan-card*
  {::mf/private true}
  [{:keys [proposal on-discard on-apply]}]
  (when proposal
    (let [counts (get-in proposal [:diff :counts] {})
          plan (:plan proposal)]
      [:div {:class (stl/css :proposal)}
       [:div {:class (stl/css :proposal-label)} "Proposed transaction"]
       [:div {:class (stl/css :proposal-title)}
        (or (:title plan) (:title proposal) "AI canvas update")]

       (when (seq (:steps plan))
         [:ul {:class (stl/css :plan-list)}
          (for [[index item] (map-indexed vector (:steps plan))]
            [:li {:key index} item])])

       [:div {:class (stl/css :diff-card)}
        [:div [:strong "+ " (or (:created counts) 0)] " nodes"]
        [:div [:strong "~ " (or (:modified counts) 0)] " nodes"]
        [:div [:strong "↔ " (or (:moved counts) 0)] " moved"]
        [:div [:strong "− " (or (:removed counts) 0)] " nodes"]]

       (when (seq (:warnings proposal))
         [:div {:class (stl/css :proposal-note)}
          (str (count (:warnings proposal)) " compatibility warning(s).")])

       [:> render-errors* {:errors (:errors proposal)}]

       [:div {:class (stl/css :proposal-note)}
        (if (:valid? proposal)
          "Validated preview — no Penpot changes have been committed."
          "The proposal cannot be applied until all validation errors are resolved.")]

       [:div {:class (stl/css :proposal-actions)}
        [:button {:type "button"
                  :class (stl/css :ghost-button)
                  :on-click on-discard}
         "Discard"]
        [:button {:type "button"
                  :class (stl/css :secondary-button)
                  :on-click on-discard}
         "Continue editing"]
        [:button {:type "button"
                  :class (stl/css :primary-button)
                  :disabled (not (:valid? proposal))
                  :on-click on-apply}
         "Apply design"]]])))

(defn- scope-definition
  [scope selected objects]
  (let [selected-id (first selected)
        selected-shape (get objects selected-id)
        root-id (some-> selected-shape ai-canvas/semantic-id)]
    (case scope
      :page {:type :page}
      :component {:type :component :root-id root-id}
      {:type :selection
       :root-id root-id
       :selection-ids (vec selected)})))

(defn- target-parent
  [selected objects]
  (let [selected-id (first selected)
        selected-shape (get objects selected-id)
        parent-id (cond
                    (contains? selected-shape :shapes) selected-id
                    selected-shape (:parent-id selected-shape)
                    :else uuid/zero)
        parent (get objects parent-id)]
    {:parent-id parent-id
     :parent-frame-id (if (= :frame (:type parent))
                        parent-id
                        (:frame-id parent))}))

(mf/defc panel*
  [{:keys [objects selected page-id file-id]}]
  (let [scope* (mf/use-state :selection)
        mode* (mf/use-state :generate)
        draft* (mf/use-state "")
        settings-open?* (mf/use-state false)
        proposal* (mf/use-state nil)
        messages* (mf/use-state [])
        request-status* (mf/use-state :idle)
        provider-config* (mf/use-state
                          {:provider "openai-compatible"
                           :base-url "https://api.openai.com/v1"
                           :model "gpt-4.1-mini"
                           :api-key ""
                           :temperature 0.2
                           :max-tokens 4096})

        scope-map
        (scope-definition @scope* selected objects)

        snapshot
        (mf/with-memo [objects selected page-id file-id @scope*]
          (ai-canvas/build-snapshot
           {:file-id file-id
            :page-id page-id
            :revision 0
            :objects objects
            :scope scope-map}))

        canvas-summary
        (ai-canvas/summary snapshot)

        on-provider-error
        (mf/use-fn
         (fn [error]
           (reset! request-status* :error)
           (reset! proposal*
                   {:valid? false
                    :title "Provider request failed"
                    :errors [{:code (or (:code error) :provider-error)
                              :message "The model request failed or returned an invalid structured proposal."}]})))

        on-provider-success
        (mf/use-fn
         (fn [response]
           (let [dsl-type (as-keyword (:dsl-type response))
                 dsl (:dsl response)
                 target (target-parent selected objects)
                 compiled
                 (case dsl-type
                   :document
                   (ai-exec/proposal-from-document
                    (merge target
                           {:file-id file-id
                            :page-id page-id
                            :revision 0
                            :objects objects
                            :scope scope-map
                            :document dsl
                            :registry {}}))

                   :patch
                   (ai-exec/proposal-from-patch
                    {:file-id file-id
                     :page-id page-id
                     :revision 0
                     :objects objects
                     :scope scope-map
                     :patch dsl
                     :registry {}})

                   {:valid? false
                    :errors [{:code :invalid-dsl-type
                              :message "Provider returned an unsupported DSL type."}]})]
             (reset! request-status* :idle)
             (reset! proposal* (assoc compiled :plan (:plan response))))))

        on-submit
        (mf/use-fn
         (fn [event]
           (dom/prevent-default event)
           (when (and (seq @draft*)
                      (not= :loading @request-status*))
             (let [request @draft*
                   config @provider-config*
                   context (ai-canvas/compact-context snapshot)]
               (swap! messages* conj {:role :user :content request})
               (reset! request-status* :loading)
               (reset! proposal* nil)
               (->> (rp/cmd! :generate-ai-design-proposal
                             (merge config
                                    {:mode (name @mode*)
                                     :scope (name @scope*)
                                     :prompt request
                                     :context context}))
                    (rx/subs! on-provider-success on-provider-error))
               (reset! draft* "")))))

        on-apply
        (mf/use-fn
         (fn []
           (when (:valid? @proposal*)
             (st/emit! (ai-exec/apply-proposal
                        (assoc @proposal* :page-id page-id)))
             (swap! messages* conj
                    {:role :assistant
                     :content "Applied as one native Penpot transaction. Undo will revert the complete AI change."})
             (reset! proposal* nil))))]
    [:div {:class (stl/css :ai-panel)}
     [:div {:class (stl/css :panel-header)}
      [:div
       [:div {:class (stl/css :panel-title)} "AI Assistant"]
       [:div {:class (stl/css :panel-subtitle)} "Transactional canvas agent"]]
      [:button {:type "button"
                :class (stl/css :settings-button)
                :aria-label "AI provider settings"
                :on-click #(swap! settings-open?* not)}
       "⚙"]]

     [:> provider-settings*
      {:open? @settings-open?*
       :config* provider-config*
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

     [:div {:class (stl/css :proposal-note)}
      (str "Canvas context: " (:node-count canvas-summary) " nodes · "
           (:component-count canvas-summary) " components · "
           (:token-bound-count canvas-summary) " token-bound · "
           (:interaction-count canvas-summary) " interactions")]

     [:div {:class (stl/css :conversation)}
      (if (empty? @messages*)
        [:div {:class (stl/css :empty-state)}
         [:div {:class (stl/css :empty-title)} "Describe a canvas operation"]
         [:p "The agent reads the scoped Penpot shape tree, returns validated Document or Patch DSL, previews a native diff and waits for confirmation."]]
        (for [[index message] (map-indexed vector @messages*)]
          [:div {:key index
                 :class (stl/css-case :message true
                                      :message-user (= :user (:role message)))}
           (:content message)]))

      (when (= :loading @request-status*)
        [:div {:class (stl/css :proposal-note)}
         "Reading canvas context and validating a structured proposal…"])

      [:> plan-card*
       {:proposal @proposal*
        :on-discard #(reset! proposal* nil)
        :on-apply on-apply}]]

     [:form {:class (stl/css :composer)
             :on-submit on-submit}
      [:textarea {:value @draft*
                  :rows 3
                  :placeholder "Generate, modify, move, restyle or restructure the scoped canvas…"
                  :on-change #(reset! draft* (event-value %))}]
      [:button {:type "submit"
                :class (stl/css :primary-button)
                :disabled (or (empty? @draft*)
                              (= :loading @request-status*)
                              (empty? (:api-key @provider-config*)))}
       (if (= :loading @request-status*) "Generating…" "Send")]]]))
