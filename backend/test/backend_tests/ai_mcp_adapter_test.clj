;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns backend-tests.ai-mcp-adapter-test
  (:require
   [app.ai.adapters.mcp :as mcp]
   [clojure.test :as t]))

(t/deftest mcp-advertises-proposals-not-native-commit
  (let [tools (:tools (mcp/list-tools))
        ids (into #{} (map :id) tools)]
    (t/is (contains? ids :proposal.create-document))
    (t/is (contains? ids :proposal.create-patch))
    (t/is (contains? ids :proposal.list))
    (t/is (contains? ids :proposal.request-apply))
    (t/is (not (contains? ids :proposal.begin-apply)))
    (t/is (not (contains? ids :proposal.complete-apply)))
    (t/is (not (contains? ids :native.commit)))))

(t/deftest mcp-write-tools-return-proposal-identities
  (let [tools (:tools (mcp/list-tools))
        patch-tool (first (filter #(= :proposal.create-patch (:id %)) tools))]
    (t/is (= :proposal-id (:result patch-tool)))
    (t/is (= :required (:confirmation patch-tool)))
    (t/is (= "object" (get-in patch-tool [:inputSchema :type])))))

(t/deftest mcp-apply-is-only-a-ui-confirmation-request
  (let [tools (:tools (mcp/list-tools))
        apply-tool (first (filter #(= :proposal.request-apply (:id %)) tools))]
    (t/is (= :penpot-ui (:confirmation apply-tool)))
    (t/is (= :proposal-id (:result apply-tool)))))
