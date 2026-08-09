;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns common-tests.ai-harness-test
  (:require
   [app.common.ai.harness :as harness]
   [clojure.test :as t]))

(t/deftest parses-slash-commands
  (t/is (= "skills"
           (get-in (harness/parse-command "/skills")
                   [:command :name])))
  (t/is (= "off"
           (:arguments
            (harness/parse-command "/coordinator off"))))
  (t/is (nil? (harness/parse-command "create a dashboard"))))

(t/deftest normalizes-uploaded-skill-manifests
  (let [manifest
        (harness/normalize-skill-manifest
         {:name "My Design Skill"
          :version "2.0"
          :tools ["canvas.read" "proposal.create-patch"]
          :keywords ["audit" "layout"]
          :autoActivate true})]
    (t/is (= "my-design-skill" (:slug manifest)))
    (t/is (= "2.0" (:version manifest)))
    (t/is (:auto-activate manifest))
    (t/is (= "SKILL.md" (:entrypoint manifest)))))

(t/deftest clamps-context-budgets
  (t/is (= 2000 (harness/clamp-context-budget 10)))
  (t/is (= harness/max-context-budget
           (harness/clamp-context-budget 999999))))
