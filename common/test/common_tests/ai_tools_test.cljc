;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns common-tests.ai-tools-test
  (:require
   [app.common.ai.tools :as tools]
   [clojure.test :as t]))

(t/deftest all-entry-points-share-one-registry
  (t/is (tools/tool-exists? :proposal.create-document))
  (t/is (tools/tool-exists? :proposal.create-patch))
  (t/is (= :proposal-id
           (:result (tools/get-tool :proposal.create-patch))))
  (t/is (= :penpot-ui
           (:confirmation (tools/get-tool :proposal.request-apply)))))

(t/deftest mcp-cannot-invoke-native-commit
  (t/is (tools/allowed? :proposal.create-patch :mcp))
  (t/is (tools/allowed? :proposal.request-apply :mcp))
  (t/is (false? (tools/allowed? :proposal.begin-apply :mcp)))
  (t/is (false? (tools/allowed? :proposal.complete-apply :mcp)))
  (t/is (false? (tools/allowed? :native.commit :mcp)))
  (t/is (tools/allowed? :native.commit :internal)))

(t/deftest commit-capability-is-singular
  (let [commit-tools (->> (tools/list-tools)
                          (filter #(= :canvas/commit (:capability %))))]
    (t/is (= 1 (count commit-tools)))
    (t/is (= :native.commit (:id (first commit-tools))))))
