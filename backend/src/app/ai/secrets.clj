;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.ai.secrets
  "Credential helpers. Raw secrets must stay request-local and must never be
  placed in RPC responses, exception data, logs, telemetry or Penpot files."
  (:require
   [clojure.string :as str]))

(def redacted "••••••••")

(def ^:private sensitive-key-names
  #{"api-key" "apikey" "authorization" "credential" "access-token"
    "accesstoken" "secret"})

(defn- sensitive-key?
  [key]
  (-> key name str/lower-case (contains? sensitive-key-names)))

(defn last-four
  [secret]
  (let [secret (str secret)]
    (if (<= (count secret) 4)
      redacted
      (str redacted (subs secret (- (count secret) 4))))))

(defn redact-value
  [value]
  (cond
    (map? value)
    (reduce-kv
     (fn [result key item]
       (assoc result key
              (if (sensitive-key? key)
                redacted
                (redact-value item))))
     (empty value)
     value)

    (vector? value)
    (mapv redact-value value)

    (set? value)
    (into #{} (map redact-value) value)

    (sequential? value)
    (doall (map redact-value value))

    :else value))

(defn valid-session-key?
  [secret]
  (and (string? secret)
       (not (str/blank? secret))
       (<= (count secret) 4096)))
