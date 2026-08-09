;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns backend-tests.ai-proposals-test
  (:require
   [app.ai.proposals :as proposals]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(t/deftest proposal-state-machine-is-explicit
  (t/is (contains? (get proposals/allowed-transitions :validated) :previewed))
  (t/is (contains? (get proposals/allowed-transitions :previewed) :applying))
  (t/is (contains? (get proposals/allowed-transitions :applying) :applied))
  (t/is (false? (contains? (get proposals/allowed-transitions :validated) :applied)))
  (t/is (empty? (get proposals/allowed-transitions :applied))))

(t/deftest public-proposal-never-exposes-owner-or-apply-token
  (let [id (uuid/next)
        profile-id (uuid/next)
        apply-token (uuid/next)
        view (proposals/public-view
              {:id id
               :profile-id profile-id
               :apply-token apply-token
               :file-id (uuid/next)
               :status :previewed
               :dsl-type :patch})]
    (t/is (= id (:proposal-id view)))
    (t/is (nil? (:id view)))
    (t/is (nil? (:profile-id view)))
    (t/is (nil? (:apply-token view)))
    (t/is (:requires-confirmation view))))

(t/deftest terminal-proposals-do-not-request-confirmation
  (doseq [status proposals/terminal-statuses]
    (t/is (false? (:requires-confirmation
                   (proposals/public-view {:id (uuid/next)
                                           :status status}))))))
