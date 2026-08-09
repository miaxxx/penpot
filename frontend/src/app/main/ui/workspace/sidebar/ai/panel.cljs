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
   [app.main.ui.workspace.sidebar.ai.harness :refer [harness-panel*]]
   [app.util.dom :as dom]
   [beicon.v2.core :as rx]
   [rumext.v2 :as mf]))

(def scope-options
  [{:id :selection :label "Selection"}
   {:id :page :label "Page"}
   {:id :component :label "Component"}])

(def mode-options
  [{:id :generate :label "Generate"}
   {:id :modify :label "Modify"}
   {:id :refactor :label "Refactor"}
   {:id :adapt :label "Adapt"}])

(defn- event-value [event]
  (.. event -target -value))

(defn- parse-number [value fallback]
  (let [number (js/parseFloat value)]
    (if (js/isNaN number) fallback number)))

(defn- parse-int [value fallback]
  (let [number (js/parseInt value 10)]
    (if (js/isNaN number) fallback number)))

(defn- as-keyword [value]
  (cond
    (keyword? value) value
    (string? value) (keyword value)
    :else nil))

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

(defn- scope-root [scope]
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
     :parent-frame-id
     (if (= :frame (:type parent))
       parent-id
       (:frame-id parent))}))

(defn- preview-summary
  [proposal]
  {:counts (get-in proposal [:diff :counts])
   :affected-ids (mapv str (:affected-ids proposal))
   :warning-count (count (:warnings proposal))
   :compiler "native-penpot-change"
   :harness-version "1.0"})

(defn- control-message
  [response]
  (case (:control response)
    :help "Harness commands are ready. Use /skills, /plugins, /context, /compact, /plan, /apply, /discard, /voice, /remote or /vim."
    :skills (str "Harness has " (count (:skills response)) " available skills.")
    :plugins (str "Harness has " (count (:plugins response)) " installed plugins.")
    :context "Context report loaded for the active Harness session."
    :compact "Older execution traces were compacted."
    :coordinator "Coordinator setting updated."
    :apply-requested "The latest proposal is waiting for Penpot UI confirmation."
    :discarded "The latest proposal was discarded."
    :remote "Remote adapter is ready; an authenticated deployment listener is still required."
    :session-closed "Harness session closed."
    :apply-and-close "Apply requested and Harness session closed."
    "Harness command completed."))

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
  [{:keys [config*]}]
  (let [{:keys [provider base-url model api-key temperature max-tokens]}
        @config*
        status* (mf/use-state :idle)
        update! #(swap! config* assoc %1 %2)
        test!
        (fn []
          (reset! status* :testing)
          (->> (rp/cmd! :test-ai-provider
                        {:provider provider
                         :base-url base-url
                         :api-key api-key
                         :model model})
               (rx/subs!
                (fn [_] (reset! status* :ok))
                (fn [_] (reset! status* :error)))))]
    [:details {:class (stl/css :provider-details)}
     [:summary "Provider settings"]
     [:div {:class (stl/css :provider-grid)}
      [:label
       [:span "Provider"]
       [:select {:value provider
                 :on-change #(update! :provider (event-value %))}
        [:option {:value "openai-compatible"} "OpenAI Compatible"]
        [:option {:value "openai"} "OpenAI"]]]
      [:label
       [:span "Base URL"]
       [:input {:type "url"
                :value base-url
                :on-change #(update! :base-url (event-value %))}]]
      [:label
       [:span "API key"]
       [:input {:type "password"
                :autocomplete "off"
                :value api-key
                :on-change #(update! :api-key (event-value %))}]]
      [:label
       [:span "Model"]
       [:input {:type "text"
                :value model
                :on-change #(update! :model (event-value %))}]]
      [:label
       [:span "Temperature"]
       [:input {:type "number" :min "0" :max "2" :step "0.1"
                :value temperature
                :on-change
                #(update! :temperature
                          (parse-number (event-value %) 0.2))}]]
      [:label
       [:span "Max tokens"]
       [:input {:type "number" :min "256" :max "32768"
                :value max-tokens
                :on-change
                #(update! :max-tokens
                          (parse-int (event-value %) 4096))}]]]
     [:div {:class (stl/css :provider-actions)}
      [:button {:type "button"
                :class (stl/css :secondary-button)
                :disabled (= @status* :testing)
                :on-click test!}
       (if (= @status* :testing) "Testing…" "Test connection")]
      (case @status*
        :ok [:span "Connected"]
        :error [:span {:class (stl/css :status-warning)} "Connection failed"]
        nil)]]))

(mf/defc proposal-card*
  {::mf/private true}
  [{:keys [proposal on-discard on-apply]}]
  (when proposal
    (let [counts (get-in proposal [:diff :counts] {})
          plan (:plan proposal)
          harness (:harness proposal)]
      [:div {:class (stl/css :proposal)}
       [:div {:class (stl/css :proposal-label)}
        (str "Proposal " (:proposal-id proposal)
             " · " (name (or (:status proposal) :validated)))]
       [:div {:class (stl/css :proposal-title)}
        (or (:title plan) "AI canvas update")]
       (when (seq (:steps plan))
         [:ol {:class (stl/css :plan-list)}
          (for [[index step] (map-indexed vector (:steps plan))]
            [:li {:key index} step])])
       [:div {:class (stl/css :diff-card)}
        [:span (str "+ " (or (:created counts) 0))]
        [:span (str "~ " (or (:modified counts) 0))]
        [:span (str "↔ " (or (:moved counts) 0))]
        [:span (str "− " (or (:removed counts) 0))]]
       (when harness
         [:div {:class (stl/css :proposal-note)}
          (str (count (:skills harness)) " skill(s)"
               (when (:coordinator harness)
                 (str " · "
                      (count (get-in harness [:coordinator :roles]))
                      " coordinator roles"))
               " · "
               (get-in harness [:context-report :characters-used] 0)
               " context chars")])
       (when (seq (:warnings proposal))
         [:div {:class (stl/css :status-warning)}
          (str (count (:warnings proposal)) " warning(s)")])
       (when (seq (:errors proposal))
         [:ul {:class (stl/css :error-list)}
          (for [[index error] (map-indexed vector (:errors proposal))]
            [:li {:key index}
             (or (:message error) (some-> (:code error) name))])])
       [:div {:class (stl/css :proposal-note)}
        (if (and (:valid? proposal)
                 (= :previewed (:status proposal)))
          "Validated native preview. Nothing has been committed yet."
          "Apply remains disabled until native compilation and preview succeed.")]
       [:div {:class (stl/css :proposal-actions)}
        [:button {:type "button"
                  :class (stl/css :ghost-button)
                  :on-click on-discard}
         "Discard"]
        [:button {:type "button"
                  :class (stl/css :primary-button)
                  :disabled
                  (not (and (:valid? proposal)
                            (= :previewed (:status proposal))))
                  :on-click on-apply}
         "Apply design"]]])))

(mf/defc panel*
  [{:keys [objects selected page-id file-id]}]
  (let [file (mf/deref refs/file)
        revision (or (:revn file) 0)
        scope* (mf/use-state :selection)
        mode* (mf/use-state :generate)
        persona* (mf/use-state :assistant)
        input-mode* (mf/use-state :assistant)
        coordinator?* (mf/use-state false)
        selected-skills* (mf/use-state #{})
        session-id* (mf/use-state nil)
        draft* (mf/use-state "")
        proposal* (mf/use-state nil)
        messages* (mf/use-state [])
        request-status* (mf/use-state :idle)
        error* (mf/use-state nil)
        provider-config*
        (mf/use-state
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

        fail!
        (mf/use-fn
         (fn [error]
           (reset! request-status* :error)
           (reset! error*
                   (or (:hint error)
                       (:message error)
                       "Harness request failed."))))

        compile-proposal!
        (mf/use-fn
         (fn [response]
           (let [dsl-type (as-keyword (:dsl-type response))
                 proposal-scope (or (:scope response) scope-map)
                 proposal-revision
                 (or (:base-revision response) revision)
                 proposal-snapshot
                 (ai-canvas/build-snapshot
                  {:file-id file-id
                   :page-id page-id
                   :revision revision
                   :objects objects
                   :scope proposal-scope})
                 target
                 (target-parent
                  proposal-scope selected objects proposal-snapshot)
                 compiled
                 (if (not= proposal-revision revision)
                   {:valid? false
                    :errors [{:code :base-revision-mismatch
                              :message
                              "Proposal belongs to an older file revision."}]}
                   (case dsl-type
                     :document
                     (ai-exec/proposal-from-document
                      (merge
                       target
                       {:file-id file-id
                        :page-id page-id
                        :revision revision
                        :objects objects
                        :scope proposal-scope
                        :document (:dsl response)
                        :registry {}}))
                     :patch
                     (ai-exec/proposal-from-patch
                      {:file-id file-id
                       :page-id page-id
                       :revision revision
                       :objects objects
                       :scope proposal-scope
                       :patch (:dsl response)
                       :registry {}})
                     {:valid? false
                      :errors
                      [{:code :invalid-dsl-type
                        :message "Unsupported proposal DSL type."}]}))
                 local
                 (merge response compiled
                        {:plan (:plan response)
                         :status :validated})]
             (if-not (:valid? compiled)
               (do
                 (reset! proposal* local)
                 (reset! request-status* :error))
               (do
                 (reset! request-status* :previewing)
                 (->> (rp/cmd! :preview-ai-design-proposal
                               {:proposal-id (:proposal-id response)
                                :preview (preview-summary local)})
                      (rx/subs!
                       (fn [persisted]
                         (reset! request-status* :idle)
                         (reset! error* nil)
                         (reset! proposal*
                                 (merge local persisted
                                        {:valid? true
                                         :status :previewed})))
                       fail!)))))))

        create-session!
        (mf/use-fn
         (fn [on-ready]
           (->> (rp/cmd! :create-ai-harness-session
                         {:file-id file-id
                          :page-id page-id
                          :base-revision revision
                          :scope scope-map
                          :mode (name @mode*)
                          :transport "internal"
                          :input-mode (name @input-mode*)
                          :persona (name @persona*)
                          :settings
                          {:coordinator @coordinator?*
                           :context-budget 24000
                           :auto-skills true}})
                (rx/subs!
                 (fn [session]
                   (reset! session-id* (:session-id session))
                   (on-ready (:session-id session)))
                 fail!))))

        run-turn!
        (mf/use-fn
         (fn [session-id prompt]
           (reset! request-status* :loading)
           (reset! error* nil)
           (->> (rp/cmd!
                 :run-ai-harness-turn
                 (merge
                  @provider-config*
                  {:session-id session-id
                   :prompt prompt
                   :context (ai-canvas/compact-context snapshot)
                   :selected-skill-ids
                   (vec @selected-skills*)
                   :input (name @input-mode*)
                   :coordinator @coordinator?*
                   :context-budget 24000}))
                (rx/subs!
                 (fn [response]
                   (if (= :control (:kind response))
                     (do
                       (swap! messages* conj
                              {:role :assistant
                               :content (control-message response)})
                       (reset! request-status* :idle))
                     (compile-proposal! response)))
                 fail!))))

        submit!
        (mf/use-fn
         (fn [event]
           (dom/prevent-default event)
           (let [prompt @draft*]
             (when (and (seq prompt)
                        (not (contains?
                              #{:loading :previewing :applying}
                              @request-status*)))
               (swap! messages* conj
                      {:role :user :content prompt})
               (reset! draft* "")
               (if-let [session-id @session-id*]
                 (run-turn! session-id prompt)
                 (create-session!
                  (fn [session-id]
                    (run-turn! session-id prompt))))))))

        discard!
        (mf/use-fn
         (fn []
           (when-let [proposal-id (:proposal-id @proposal*)]
             (->> (rp/cmd! :discard-ai-design-proposal
                           {:proposal-id proposal-id})
                  (rx/subs! (fn [_] nil) (fn [_] nil))))
           (reset! proposal* nil)
           (reset! request-status* :idle)))

        apply!
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
                         (assoc
                          proposal
                          :page-id page-id
                          :apply-token apply-token
                          :on-applied
                          (fn [{:keys [transaction-id]}]
                            (->> (rp/cmd!
                                  :complete-ai-design-proposal-apply
                                  {:proposal-id proposal-id
                                   :apply-token apply-token
                                   :transaction-id transaction-id})
                                 (rx/subs!
                                  (fn [_]
                                    (reset! request-status* :idle)
                                    (swap! messages* conj
                                           {:role :assistant
                                            :content
                                            "Applied as one native Penpot transaction. Undo reverts the complete change."})
                                    (reset! proposal* nil))
                                  fail!)))
                          :on-conflict
                          (fn [{:keys [error]}]
                            (->> (rp/cmd!
                                  :conflict-ai-design-proposal
                                  {:proposal-id proposal-id
                                   :apply-token apply-token
                                   :error error})
                                 (rx/subs!
                                  (fn [persisted]
                                    (reset! request-status* :error)
                                    (reset! proposal*
                                            (merge
                                             proposal persisted
                                             {:valid? false
                                              :errors [error]})))
                                  fail!))))))
                     fail!)))))))]

    (mf/use-effect
     (mf/deps file-id page-id revision @scope* @mode*
              @persona* @input-mode*)
     (fn []
       (reset! session-id* nil)
       (create-session! (fn [_] nil))))

    (mf/use-effect
     (mf/deps file-id page-id)
     (fn []
       (->> (rp/cmd! :list-ai-design-proposals
                     {:file-id file-id :page-id page-id})
            (rx/subs!
             (fn [proposals]
               (when (seq proposals)
                 (compile-proposal! (first proposals))))
             (fn [_] nil)))))

    [:div {:class (stl/css :ai-panel)}
     [:div {:class (stl/css :panel-header)}
      [:div
       [:div {:class (stl/css :panel-title)}
        "AI Design Agent"]
       [:div {:class (stl/css :panel-subtitle)}
        "Harness → Proposal → Native Penpot Transaction"]]
      [:div {:class (stl/css :revision-badge)}
       (str "r" revision)]]

     [:> provider-settings* {:config* provider-config*}]

     [:div {:class (stl/css :controls)}
      [:> choice-row*
       {:label "Scope" :options scope-options
        :value @scope* :on-change #(reset! scope* %)}]
      [:> choice-row*
       {:label "Mode" :options mode-options
        :value @mode* :on-change #(reset! mode* %)}]]

     [:> harness-panel*
      {:selected-skills* selected-skills*
       :coordinator?* coordinator?*
       :persona* persona*
       :input-mode* input-mode*}]

     [:div {:class (stl/css :canvas-summary)}
      (str (:node-count canvas-summary) " nodes · "
           (:component-count canvas-summary) " components · "
           (:token-bound-count canvas-summary) " token-bound · "
           (:interaction-count canvas-summary) " interactions")]

     [:div {:class (stl/css :conversation)}
      (when (empty? @messages*)
        [:div {:class (stl/css :empty-state)}
         [:strong "Describe a design operation"]
         [:p
          "Use natural language, /commands, a voice transcript or Vim-style controls. Every write becomes a persistent Proposal."]])
      (for [[index message] (map-indexed vector @messages*)]
        [:div {:key index
               :class
               (stl/css-case
                :message true
                :message-user (= :user (:role message)))}
         (:content message)])
      (when (contains?
             #{:loading :previewing :applying}
             @request-status*)
        [:div {:class (stl/css :working)}
         (case @request-status*
           :loading "Selecting skills, assembling context and running the Harness…"
           :previewing "Compiling native Penpot Changes and saving the Diff…"
           :applying "Locking revision and committing one native transaction…"
           "Working…")])
      (when @error*
        [:div {:class (stl/css :status-warning)} @error*])
      [:> proposal-card*
       {:proposal @proposal*
        :on-discard discard*
        :on-apply apply*}]]

     [:form {:class (stl/css :composer)
             :on-submit submit!}
      [:textarea
       {:value @draft*
        :rows 4
        :placeholder
        (case @input-mode*
          :voice "Paste or stream a voice transcript…"
          :vim ":skills, :plan, :w, :q…"
          :remote "Send an authenticated remote-session instruction…"
          "Generate, modify, audit or restructure the scoped canvas…")
        :on-change #(reset! draft* (event-value %))}]
      [:button {:type "submit"
                :class (stl/css :primary-button)
                :disabled
                (or (empty? @draft*)
                    (empty? (:api-key @provider-config*))
                    (contains?
                     #{:loading :previewing :applying}
                     @request-status*))}
       (if (= :loading @request-status*)
         "Running…"
         "Send")]]]))
