;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.main.ui.workspace.sidebar.ai.panel
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.ai.canvas :as ai-canvas]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.ai.execution :as ai-exec]
   [app.main.refs :as refs]
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
        update-config! (fn [key value] (swap! config* assoc key value))
        on-success (mf/use-fn (fn [_] (reset! status* :ok)))
        on-error (mf/use-fn (fn [_] (reset! status* :error)))
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
                  :min "0" :max "2" :step "0.1"
                  :value temperature
                  :on-change #(update-config! :temperature
                                              (parse-number (event-value %) 0.2))}]]
        [:label {:class (stl/css :field)}
         [:span {:class (stl/css :field-label)} "Max tokens"]
         [:input {:type "number"
                  :min "256" :max "32768"
                  :value max-tokens
                  :on-change #(update-config! :max-tokens
                                              (parse-int (event-value %) 4096))}]]]

       [:button {:type "button"
                 :class (stl/css :secondary-button)
                 :disabled (= :testing @status*)
                 :on-click on-test}
        (if (= :testing @status*) "Testing…" "Test connection")]

       (case @status*
         :missing-fields [:div {:class (stl/css :status-message :status-warning)}
                          "Complete the base URL, model and API key first."]
         :ok [:div {:class (stl/css :status-message)}
              "Connection successful. The key remains session-only."]
         :error [:div {:class (stl/css :status-message :status-warning)}
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
       [:div {:class (stl/css :proposal-label)}
        (str "Proposal " (or (:proposal-id proposal) "local")
             " · " (name (or (:status proposal) :validated)))]
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
        (if (and (:valid? proposal) (= :previewed (:status proposal)))
          "Validated preview — no Penpot changes have been committed."
          "The proposal cannot be applied until validation and preview complete.")]

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
                  :disabled (not (and (:valid? proposal)
                                      (= :previewed (:status proposal))))
                  :on-click on-apply}
         "Apply design"]]])))

(defn- scope-definition
  [scope selected objects]
  (let [selected-id (first selected)
        selected-shape (get objects selected-id)
        root-id (some-> selected-shape ai-canvas/semantic-id)]
    (case scope
      :page {:type :page}
      :component {:type :component
                  :root-id root-id
                  :selection-ids (vec selected)}
      {:type :selection
       :root-id root-id
       :selection-ids (vec selected)})))

(defn- scope-root
  [scope]
  (or (:root-id scope) (:rootId scope)
      (get scope "root-id") (get scope "rootId")))

(defn- target-parent
  [proposal-scope selected objects snapshot]
  (let [resolved-root (some->> (scope-root proposal-scope)
                               (ai-canvas/resolve-id snapshot))
        selected-id (or resolved-root (first selected))
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

(defn- preview-summary
  [proposal]
  {:counts (get-in proposal [:diff :counts])
   :affected-ids (mapv str (:affected-ids proposal))
   :warning-count (count (:warnings proposal))
   :compiler "native-penpot-change"})

(mf/defc panel*
  [{:keys [objects selected page-id file-id]}]
  (let [file (mf/deref refs/file)
        revision (or (:revn file) 0)
        scope* (mf/use-state :selection)
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

        scope-map (scope-definition @scope* selected objects)
        snapshot
        (mf/with-memo [objects selected page-id file-id revision @scope*]
          (ai-canvas/build-snapshot
           {:file-id file-id
            :page-id page-id
            :revision revision
            :objects objects
            :scope scope-map}))
        canvas-summary (ai-canvas/summary snapshot)

        set-request-error!
        (mf/use-fn
         (fn [title error]
           (reset! request-status* :error)
           (reset! proposal*
                   {:valid? false
                    :title title
                    :errors [{:code (or (:code error) :proposal-error)
                              :message (or (:hint error)
                                           "The proposal lifecycle request failed.")}]})))

        on-provider-error
        (mf/use-fn
         (fn [error]
           (set-request-error! "Provider request failed" error)))

        on-provider-success
        (mf/use-fn
         (fn [response]
           (let [dsl-type (as-keyword (:dsl-type response))
                 dsl (:dsl response)
                 proposal-scope (or (:scope response) scope-map)
                 proposal-revision (or (:base-revision response) revision)
                 proposal-snapshot
                 (ai-canvas/build-snapshot
                  {:file-id file-id
                   :page-id page-id
                   :revision revision
                   :objects objects
                   :scope proposal-scope})
                 target (target-parent proposal-scope selected objects proposal-snapshot)
                 compiled
                 (if (not= proposal-revision revision)
                   {:valid? false
                    :errors [{:code :base-revision-mismatch
                              :message "This proposal was created for an older file revision."}]}
                   (case dsl-type
                     :document
                     (ai-exec/proposal-from-document
                      (merge target
                             {:file-id file-id
                              :page-id page-id
                              :revision revision
                              :objects objects
                              :scope proposal-scope
                              :document dsl
                              :registry {}}))

                     :patch
                     (ai-exec/proposal-from-patch
                      {:file-id file-id
                       :page-id page-id
                       :revision revision
                       :objects objects
                       :scope proposal-scope
                       :patch dsl
                       :registry {}})

                     {:valid? false
                      :errors [{:code :invalid-dsl-type
                                :message "Provider returned an unsupported DSL type."}]}))
                 local-proposal (merge response compiled
                                       {:plan (:plan response)
                                        :status :validated})]
             (when-let [restored-scope (some-> proposal-scope :type as-keyword)]
               (reset! scope* restored-scope))
             (when-let [restored-mode (some-> (:mode response) as-keyword)]
               (reset! mode* restored-mode))
             (if-not (:valid? compiled)
               (do
                 (reset! request-status* :error)
                 (reset! proposal* local-proposal))
               (do
                 (reset! request-status* :previewing)
                 (->> (rp/cmd! :preview-ai-design-proposal
                               {:proposal-id (:proposal-id response)
                                :preview (preview-summary local-proposal)})
                      (rx/subs!
                       (fn [persisted]
                         (reset! request-status* :idle)
                         (reset! proposal*
                                 (merge local-proposal persisted
                                        {:valid? true :status :previewed})))
                       (fn [error]
                         (set-request-error! "Preview persistence failed" error)))))))))

        _
        (mf/use-effect
         (mf/deps file-id page-id)
         (fn []
           (->> (rp/cmd! :list-ai-design-proposals
                         {:file-id file-id
                          :page-id page-id})
                (rx/subs!
                 (fn [proposals]
                   (when (and (nil? @proposal*) (seq proposals))
                     (on-provider-success (first proposals))))
                 (fn [_error] nil)))))

        on-submit
        (mf/use-fn
         (fn [event]
           (dom/prevent-default event)
           (when (and (seq @draft*)
                      (not (contains? #{:loading :previewing :applying}
                                      @request-status*)))
             (let [request @draft*
                   config @provider-config*
                   context (ai-canvas/compact-context snapshot)]
               (swap! messages* conj {:role :user :content request})
               (reset! request-status* :loading)
               (reset! proposal* nil)
               (->> (rp/cmd! :generate-ai-design-proposal
                             (merge config
                                    {:file-id file-id
                                     :page-id page-id
                                     :base-revision revision
                                     :mode (name @mode*)
                                     :scope (name @scope*)
                                     :prompt request
                                     :context context}))
                    (rx/subs! on-provider-success on-provider-error))
               (reset! draft* "")))))

        on-discard
        (mf/use-fn
         (fn []
           (when-let [proposal-id (:proposal-id @proposal*)]
             (->> (rp/cmd! :discard-ai-design-proposal
                           {:proposal-id proposal-id})
                  (rx/subs! (fn [_] nil) (fn [_] nil))))
           (reset! proposal* nil)
           (reset! request-status* :idle)))

        on-apply
        (mf/use-fn
         (fn []
           (when (and (:valid? @proposal*)
                      (= :previewed (:status @proposal*)))
             (reset! request-status* :applying)
             (let [proposal @proposal*
                   proposal-id (:proposal-id proposal)]
               (->> (rp/cmd! :begin-ai-design-proposal-apply
                             {:proposal-id proposal-id})
                    (rx/subs!
                     (fn [{:keys [apply-token]}]
                       (st/emit!
                        (ai-exec/apply-proposal
                         (assoc proposal
                                :page-id page-id
                                :apply-token apply-token
                                :on-applied
                                (fn [{:keys [transaction-id]}]
                                  (->> (rp/cmd! :complete-ai-design-proposal-apply
                                                {:proposal-id proposal-id
                                                 :apply-token apply-token
                                                 :transaction-id transaction-id})
                                       (rx/subs!
                                        (fn [_]
                                          (reset! request-status* :idle)
                                          (swap! messages* conj
                                                 {:role :assistant
                                                  :content "Applied as one native Penpot transaction. Undo reverts the complete AI change."})
                                          (reset! proposal* nil))
                                        (fn [error]
                                          (set-request-error! "Transaction completion failed" error)))))
                                :on-conflict
                                (fn [{:keys [error]}]
                                  (->> (rp/cmd! :conflict-ai-design-proposal
                                                {:proposal-id proposal-id
                                                 :apply-token apply-token
                                                 :error error})
                                       (rx/subs!
                                        (fn [persisted]
                                          (reset! request-status* :error)
                                          (reset! proposal*
                                                  (merge proposal persisted
                                                         {:valid? false
                                                          :errors [error]})))
                                        (fn [rpc-error]
                                          (set-request-error! "Conflict persistence failed" rpc-error)))))))))
                     (fn [error]
                       (set-request-error! "Apply authorization failed" error))))))))]
    [:div {:class (stl/css :ai-panel)}
     [:div {:class (stl/css :panel-header)}
      [:div
       [:div {:class (stl/css :panel-title)} "AI Assistant"]
       [:div {:class (stl/css :panel-subtitle)} "Unified proposal transaction agent"]]
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
           (:interaction-count canvas-summary) " interactions · revision " revision)]

     [:div {:class (stl/css :conversation)}
      (if (empty? @messages*)
        [:div {:class (stl/css :empty-state)}
         [:div {:class (stl/css :empty-title)} "Describe a canvas operation"]
         [:p "Every entry point creates the same persistent Proposal, previews a native diff and waits for Penpot confirmation."]]
        (for [[index message] (map-indexed vector @messages*)]
          [:div {:key index
                 :class (stl/css-case :message true
                                      :message-user (= :user (:role message)))}
           (:content message)]))

      (when (contains? #{:loading :previewing :applying} @request-status*)
        [:div {:class (stl/css :proposal-note)}
         (case @request-status*
           :loading "Reading canvas context and creating a persistent proposal…"
           :previewing "Compiling native Penpot Changes and saving the preview diff…"
           :applying "Locking revision and committing one native transaction…"
           "Working…")])

      [:> plan-card*
       {:proposal @proposal*
        :on-discard on-discard
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
                              (contains? #{:loading :previewing :applying}
                                         @request-status*)
                              (empty? (:api-key @provider-config*)))}
       (if (= :loading @request-status*) "Generating…" "Send")]]]))
