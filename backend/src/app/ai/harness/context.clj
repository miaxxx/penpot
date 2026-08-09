;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.ai.harness.context
  "Deterministic context assembly, prioritization and reporting."
  (:require
   [app.common.ai.harness :as harness]
   [app.common.json :as json]
   [clojure.string :as str]))

(def chars-per-token 4)

(defn- encoded-size
  [value]
  (count (json/encode value :key-fn json/write-camel-key)))

(defn- truncate-string
  [value limit]
  (let [value (str value)]
    (if (> (count value) limit)
      (str (subs value 0 (max 0 (- limit 18))) "\n…[truncated]")
      value)))

(defn- bounded
  [value budget]
  (cond
    (string? value)
    (truncate-string value budget)

    (map? value)
    (loop [entries (seq value)
           result {}
           remaining budget]
      (if (or (nil? entries) (<= remaining 16))
        result
        (let [[key item] (first entries)
              item-budget (max 16 (quot remaining (max 1 (count entries))))
              bounded-item (bounded item item-budget)
              size (encoded-size {key bounded-item})]
          (recur (next entries)
                 (assoc result key bounded-item)
                 (max 0 (- remaining size))))))

    (sequential? value)
    (loop [items (seq value)
           result []
           remaining budget]
      (if (or (nil? items) (<= remaining 16))
        result
        (let [item-budget (max 16 (quot remaining (max 1 (count items))))
              item (bounded (first items) item-budget)
              size (encoded-size item)]
          (recur (next items)
                 (conj result item)
                 (max 0 (- remaining size))))))

    :else value))

(defn- compartment
  [id priority value]
  {:id id
   :priority priority
   :value value
   :characters (encoded-size value)})

(defn assemble
  [{:keys [base-context session skills plugin-hooks recent-runs
           coordinator-plan command context-budget]}]
  (let [token-budget (harness/clamp-context-budget context-budget)
        char-budget (* token-budget chars-per-token)
        compartments
        [(compartment :canvas 100 base-context)
         (compartment :session 90
                      (select-keys session
                                   [:session-id :transport :input-mode :persona
                                    :mode :base-revision :scope :summary
                                    :persona-context]))
         (compartment :skills 80 skills)
         (compartment :plugin-hooks 70 plugin-hooks)
         (compartment :coordinator 65 coordinator-plan)
         (compartment :command 60 command)
         (compartment :history 40 recent-runs)]
        ordered (sort-by (comp - :priority) compartments)]
    (loop [remaining char-budget
           pending ordered
           included {}
           report []]
      (if-let [{:keys [id priority value characters]} (first pending)]
        (cond
          (or (nil? value)
              (and (coll? value) (empty? value))
              (and (string? value) (str/blank? value)))
          (recur remaining
                 (rest pending)
                 included
                 (conj report {:id id
                               :priority priority
                               :included false
                               :characters 0
                               :reason :empty}))

          (<= characters remaining)
          (recur (- remaining characters)
                 (rest pending)
                 (assoc included id value)
                 (conj report {:id id
                               :priority priority
                               :included true
                               :characters characters
                               :truncated false}))

          (> remaining 256)
          (let [value (bounded value remaining)
                size (encoded-size value)]
            (recur (max 0 (- remaining size))
                   (rest pending)
                   (assoc included id value)
                   (conj report {:id id
                                 :priority priority
                                 :included true
                                 :characters size
                                 :truncated true})))

          :else
          (recur remaining
                 (rest pending)
                 included
                 (conj report {:id id
                               :priority priority
                               :included false
                               :characters 0
                               :reason :budget})))
        {:context
         (assoc (or (:canvas included) {})
                :harness
                (merge
                 {:version harness/harness-version}
                 (dissoc included :canvas)))
         :report
         {:token-budget token-budget
          :character-budget char-budget
          :characters-used (- char-budget remaining)
          :characters-remaining remaining
          :compartments report}}))))

(defn compact-run
  [run]
  {:run-id (some-> (:id run) str)
   :status (:status run)
   :input-mode (:input-mode run)
   :input (truncate-string (:input-text run) 500)
   :command (:command run)
   :proposal-id (some-> (:proposal-id run) str)
   :result
   (when-let [result (:result run)]
     (bounded result 1200))
   :created-at (:created-at run)})
