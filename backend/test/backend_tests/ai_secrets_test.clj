;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns backend-tests.ai-secrets-test
  (:require
   [app.ai.secrets :as secrets]
   [clojure.test :as t]))

(t/deftest masks-credential-display
  (t/is (= "••••••••7890" (secrets/last-four "sk-example-1234567890")))
  (t/is (= secrets/redacted (secrets/last-four "1234"))))

(t/deftest recursively-redacts-sensitive-values
  (let [input {:api-key "secret"
               :nested {:authorization "Bearer secret"
                        :safe "visible"}
               :items [{:credential "other-secret"}]
               "accessToken" "string-key-secret"}
        result (secrets/redact-value input)]
    (t/is (= secrets/redacted (:api-key result)))
    (t/is (= secrets/redacted (get-in result [:nested :authorization])))
    (t/is (= "visible" (get-in result [:nested :safe])))
    (t/is (= secrets/redacted (get-in result [:items 0 :credential])))
    (t/is (= secrets/redacted (get result "accessToken")))))

(t/deftest validates-request-local-credentials
  (t/is (secrets/valid-session-key? "sk-valid"))
  (t/is (false? (secrets/valid-session-key? "")))
  (t/is (false? (secrets/valid-session-key? nil))))
