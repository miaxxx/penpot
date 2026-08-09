;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.ai.harness.service
  "End-to-end Harness orchestration for the embedded Penpot AI assistant."
  (:require
   [app.ai.harness.commands :as commands]
   [app.ai.harness.context :as context]
   [app.ai.harness.coordinator :as coordinator]
   [app.ai.harness.plugins :as plugins]
   [app.ai.harness.sessions :as sessions]
   [app.ai.harness.skills :as skills]
   [app.ai.policy :as policy]
   [app.ai.proposals :as proposals]
   [app.ai.service :as design-service]
   [app.common.exceptions :as ex]
   [app.common.time :as ct]
   [clojure.string :as str]))

(defn- trace-event
  [kind data]
  {:at (ct/now)
   :kind kind
   :data data})

(defn- compact-summary
  [runs]
  (->> runs
       (map (fn [run]
              (str "- "
                   (name (:status run))
                   ": "
                   (subs (str (:input-text run))
                         0
                         (min 240 (count (str (:input-text run)))))
                   (when-let [proposal-id (:proposal-id run)]
                     (str " [proposal " proposal-id "]")))))
       (str/join "\n")))

(defn- latest-proposal!
  [cfg profile-id session-id]
  (or (sessions/latest-proposal-id cfg profile-id session-id)
      (ex/raise :type :validation
                :code :missing-ai-harness-proposal
                :hint "this Harness session has no proposal")))

(defn- control!
  [cfg profile-id session resolved]
  (let [session-id (:id session)
        command (or (:command resolved)
                    {:name (:name resolved)
                     :arguments (:arguments resolved)})
        name (:name command)
        arguments (:arguments command)
        recent (delay (sessions/recent-runs cfg profile-id session-id 8))]
    (case name
      ("help" "?")
      {:kind :control
       :control :help
       :registry (commands/registry)}

      "skills"
      {:kind :control
       :control :skills
       :skills (skills/list! cfg profile-id)}

      "plugins"
      {:kind :control
       :control :plugins
       :plugins (plugins/list! cfg profile-id)}

      "context"
      {:kind :control
       :control :context
       :session (sessions/decode-session session)
       :last-context-report (:context-report (last @recent))}

      "compact"
      (let [summary (compact-summary @recent)
            session (sessions/set-summary! cfg profile-id session-id summary)]
        {:kind :control
         :control :compact
         :summary (:summary session)
         :session session})

      "plan"
      {:kind :control
       :control :coordinator
       :session
       (sessions/update-settings! cfg profile-id session-id
                                  {:coordinator true})}

      "coordinator"
      (let [enabled (not (contains? #{"off" "false" "0"}
                                    (str/lower-case arguments)))]
        {:kind :control
         :control :coordinator
         :session
         (sessions/update-settings! cfg profile-id session-id
                                    {:coordinator enabled})})

      "apply"
      {:kind :control
       :control :apply-requested
       :proposal
       (proposals/request-apply!
        cfg profile-id (latest-proposal! cfg profile-id session-id))}

      "discard"
      {:kind :control
       :control :discarded
       :proposal
       (proposals/discard!
        cfg profile-id (latest-proposal! cfg profile-id session-id))}

      "remote"
      {:kind :control
       :control :remote
       :remote
       {:session-id session-id
        :transport (:transport session)
        :authenticated true
        :listener :not-configured
        :note
        "A deployment transport may attach to this session but must reuse the Harness and Proposal services."}}

      "voice"
      {:kind :forward
       :prompt arguments
       :input-mode :voice}

      "vim"
      (let [vim (commands/parse-vim arguments)]
        {:kind :vim :vim vim})

      (if-let [plugin-command
               (plugins/resolve-command cfg profile-id name arguments)]
        {:kind :forward
         :prompt (:expanded-prompt plugin-command)
         :input-mode :plugin
         :plugin-command
         (select-keys plugin-command
                      [:plugin-id :plugin :name :description])}
        (ex/raise :type :validation
                  :code :unknown-ai-harness-command
                  :hint "unknown Harness command"
                  :command name)))))

(defn- vim-control!
  [cfg profile-id session vim]
  (let [session-id (:id session)
        action (get-in vim [:descriptor :action])]
    (case action
      :request-apply
      {:kind :control
       :control :apply-requested
       :proposal
       (proposals/request-apply!
        cfg profile-id (latest-proposal! cfg profile-id session-id))}

      :close-session
      {:kind :control
       :control :session-closed
       :session (sessions/close! cfg profile-id session-id)}

      :apply-and-close
      {:kind :control
       :control :apply-and-close
       :proposal
       (proposals/request-apply!
        cfg profile-id (latest-proposal! cfg profile-id session-id))
       :session (sessions/close! cfg profile-id session-id)}

      :list-skills
      {:kind :control :control :skills
       :skills (skills/list! cfg profile-id)}

      :list-plugins
      {:kind :control :control :plugins
       :plugins (plugins/list! cfg profile-id)}

      :context-report
      {:kind :control :control :context
       :last-context-report
       (:context-report
        (last (sessions/recent-runs cfg profile-id session-id 1)))}

      :compact
      (control! cfg profile-id session
                {:command {:name "compact" :arguments ""}})

      :coordinator-on
      {:kind :control :control :coordinator
       :session
       (sessions/update-settings! cfg profile-id session-id
                                  {:coordinator true})}

      :coordinator-off
      {:kind :control :control :coordinator
       :session
       (sessions/update-settings! cfg profile-id session-id
                                  {:coordinator false})}

      (ex/raise :type :validation
                :code :unknown-ai-harness-vim-command
                :hint "unknown or unsupported Vim command"
                :command (:name vim)))))

(defn- record-control!
  [cfg profile-id session input-mode input-text control]
  (let [run
        (sessions/create-run!
         cfg profile-id (:id session)
         {:input-mode input-mode
          :input-text input-text
          :command (some-> (:control control) name)
          :selected-skills []
          :context-report {}
          :trace [(trace-event :control-dispatched
                               {:control (:control control)})]})]
    (sessions/complete-run!
     cfg profile-id (:run-id run)
     {:status :control
      :result control
      :trace [(trace-event :control-completed
                           {:control (:control control)})]})
    control))

(defn- persona-context
  [session]
  (case (:persona session)
    :buddy
    {:name "Penpot Buddy"
     :instructions
     "Be encouraging but precise. Explain design reasoning briefly, never hide risks, and always preserve the Proposal confirmation boundary."}
    {:name "Penpot Assistant"
     :instructions
     "Be direct, technical and design-aware. Prefer minimal native Penpot changes and explicit limitations."}))

(defn- run-prompt!
  [provider-instance cfg profile-id session params prompt input-mode]
  (let [session-id (:id session)
        settings (:settings session)
        plugin-skills (plugins/contributed-skills cfg profile-id)
        selected
        (skills/select! cfg profile-id prompt
                        (:selected-skill-ids params)
                        plugin-skills)
        plugin-hooks (plugins/prompt-hooks cfg profile-id :before-turn)
        recent (mapv context/compact-run
                     (sessions/recent-runs cfg profile-id session-id 8))
        coordinator? (if (contains? params :coordinator)
                       (boolean (:coordinator params))
                       (boolean (:coordinator settings)))
        deterministic
        (when coordinator?
          (coordinator/deterministic-plan
           {:prompt prompt
            :mode (:mode session)
            :scope (:scope session)
            :base-revision (:base-revision session)
            :skills selected}))
        coordinator-plan
        (when deterministic
          (coordinator/request-plan!
           provider-instance
           cfg
           (assoc params
                  :prompt prompt
                  :mode (:mode session)
                  :scope (:scope session)
                  :base-revision (:base-revision session)
                  :skills selected)
           deterministic))
        assembled
        (context/assemble
         {:base-context (:context params)
          :session (assoc (sessions/decode-session session)
                          :persona-context (persona-context session))
          :skills (skills/prompt-context selected)
          :plugin-hooks plugin-hooks
          :recent-runs recent
          :coordinator-plan coordinator-plan
          :context-budget
          (or (:context-budget params)
              (:context-budget settings))})
        trace
        [(trace-event :skills-selected
                      {:skill-ids (mapv :skill-id selected)})
         (trace-event :context-assembled (:report assembled))
         (trace-event :coordinator
                      {:enabled coordinator?
                       :roles (:roles coordinator-plan)})]
        run
        (sessions/create-run!
         cfg profile-id session-id
         {:input-mode input-mode
          :input-text prompt
          :selected-skills (mapv :skill-id selected)
          :coordinator-plan coordinator-plan
          :context-report (:report assembled)
          :trace trace})]
    (try
      (let [generated
            (design-service/generate-proposal!
             provider-instance
             cfg
             (assoc params
                    :prompt prompt
                    :mode (:mode session)
                    :scope (keyword (get-in session [:scope :type]))
                    :context (:context assembled)))
            review
            (when coordinator?
              (coordinator/review!
               provider-instance cfg
               (assoc params
                      :prompt prompt
                      :mode (:mode session)
                      :scope (:scope session)
                      :base-revision (:base-revision session))
               generated
               coordinator-plan))
            _ (when (and review (false? (:approved review)))
                (ex/raise :type :validation
                          :code :ai-harness-verification-failed
                          :hint "coordinator verifier rejected the generated proposal"
                          :issues (:issues review)))
            proposal
            (proposals/create!
             cfg
             {:profile-id profile-id
              :file-id (:file-id session)
              :page-id (:page-id session)
              :origin :internal
              :mode (:mode session)
              :dsl-type (:dsl-type generated)
              :base-revision (:base-revision session)
              :scope (:scope session)
              :plan
              (cond-> (:plan generated)
                coordinator-plan
                (assoc :coordinator
                       (select-keys coordinator-plan
                                    [:strategy :roles :planner-output]))
                review
                (assoc :verification review))
              :dsl (:dsl generated)})
            final-trace
            (conj trace
                  (trace-event :proposal-created
                               {:proposal-id (:proposal-id proposal)
                                :dsl-type (:dsl-type proposal)}))]
        (sessions/complete-run!
         cfg profile-id (:run-id run)
         {:status :proposal-created
          :proposal-id (:proposal-id proposal)
          :result
          {:proposal-id (:proposal-id proposal)
           :verification review}
          :trace final-trace
          :coordinator-plan coordinator-plan
          :context-report (:report assembled)})
        (assoc proposal
               :kind :proposal
               :harness
               {:version "1.0"
                :session-id session-id
                :run-id (:run-id run)
                :input-mode input-mode
                :skills
                (mapv #(select-keys % [:skill-id :name :version])
                      selected)
                :coordinator coordinator-plan
                :context-report (:report assembled)
                :verification review}))
      (catch Throwable cause
        (sessions/complete-run!
         cfg profile-id (:run-id run)
         {:status :failed
          :result
          {:error
           {:code (or (:code (ex-data cause))
                      :ai-harness-turn-failed)
            :message (or (:hint (ex-data cause))
                         (.getMessage cause))}}
          :trace
          (conj trace
                (trace-event :failed
                             {:code (:code (ex-data cause))}))})
        (throw cause)))))

(defn run-turn!
  [provider-instance cfg profile-id
   {:keys [session-id input prompt] :as params}]
  (policy/ensure-enabled!)
  (let [session (sessions/get-owned! cfg profile-id session-id :access :edit)
        _ (policy/ensure-revision!
           cfg (:file-id session) (:base-revision session))
        input-mode (keyword (or input (:input-mode session) :assistant))
        resolved (commands/resolve-input prompt input-mode)]
    (case (:kind resolved)
      :command
      (let [control (control! cfg profile-id session resolved)]
        (cond
          (= :forward (:kind control))
          (run-prompt! provider-instance cfg profile-id session params
                       (:prompt control) (:input-mode control))

          (= :vim (:kind control))
          (record-control!
           cfg profile-id session input-mode prompt
           (vim-control! cfg profile-id session (:vim control)))

          :else
          (record-control! cfg profile-id session input-mode prompt control)))

      :vim
      (record-control!
       cfg profile-id session input-mode prompt
       (vim-control! cfg profile-id session (:vim resolved)))

      :prompt
      (run-prompt! provider-instance cfg profile-id session params
                   (:prompt resolved) input-mode))))
