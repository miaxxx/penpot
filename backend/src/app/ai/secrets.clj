;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.ai.secrets
  "Credential helpers. Raw secrets must stay request-local and must never be
  placed in RPC responses, exception data, logs, telemetry or Penpot files."
  (:require
   [clojure.string :as str]))

(def redacted "••••••••")

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
              (if (contains? #{:api-key :apiKey :authorization :credential} key)
                redacted
                (redact-value item))))
     {}
     value)

    (vector? value)
    (mapv redact-value value)

    (sequential? value)
    (map redact-value value)

    :else value))

(defn valid-session-key?
  [secret]
  (and (string? secret)
       (not (str/blank? secret))
       (<= (count secret) 4096)))
