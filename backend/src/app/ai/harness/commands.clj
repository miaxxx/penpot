;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.ai.harness.commands
  "Safe command and Vim-style input parsing for the embedded AI assistant."
  (:require
   [app.common.ai.harness :as harness]
   [clojure.string :as str]))

(def vim-commands
  {"w" {:action :request-apply
        :description "Request application of the latest proposal."}
   "q" {:action :close-session
        :description "Close the active Harness session."}
   "wq" {:action :apply-and-close
         :description "Request application, then close after completion."}
   "skills" {:action :list-skills
             :description "List Harness skills."}
   "plugins" {:action :list-plugins
              :description "List Harness plugins."}
   "context" {:action :context-report
              :description "Show the current context budget."}
   "compact" {:action :compact
              :description "Compact older traces."}
   "plan" {:action :coordinator-on
           :description "Enable coordinator planning."}
   "noplan" {:action :coordinator-off
             :description "Disable coordinator planning."}})

(defn registry
  []
  {:version harness/harness-version
   :commands harness/commands
   :vim-commands
   (mapv (fn [[name descriptor]]
           (assoc descriptor :name name))
         vim-commands)})

(defn parse-vim
  [value]
  (let [value (-> (str value)
                  str/trim
                  (str/replace #"^:" "")
                  str/lower-case)
        [name & tail] (str/split value #"\s+")
        descriptor (get vim-commands name)]
    {:name name
     :arguments (str/join " " tail)
     :descriptor descriptor
     :known? (some? descriptor)}))

(defn resolve-input
  [input input-mode]
  (let [input-mode (keyword input-mode)
        slash (harness/parse-command input)]
    (cond
      slash
      {:kind :command
       :command slash
       :input-mode input-mode}

      (= input-mode :vim)
      {:kind :vim
       :vim (parse-vim input)
       :input-mode input-mode}

      :else
      {:kind :prompt
       :prompt (str input)
       :input-mode input-mode})))
