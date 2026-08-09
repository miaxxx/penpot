;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns backend-tests.ai-mcp-protocol-test
  (:require
   [app.ai.adapters.mcp-protocol :as protocol]
   [clojure.test :as t]))

(t/deftest initializes-as-an-mcp-tool-server
  (let [response (protocol/handle-request!
                  nil nil
                  {:jsonrpc "2.0"
                   :id 1
                   :method "initialize"
                   :params {}})]
    (t/is (= 1 (:id response)))
    (t/is (= protocol/protocol-version
             (get-in response [:result :protocolVersion])))
    (t/is (= false
             (get-in response [:result :capabilities :tools :listChanged])))))

(t/deftest lists-schema-backed-tools-without-native-commit
  (let [response (protocol/handle-request!
                  nil nil
                  {:jsonrpc "2.0"
                   :id 2
                   :method "tools/list"
                   :params {}})
        tools (get-in response [:result :tools])
        names (into #{} (map :name) tools)
        patch (first (filter #(= "proposal.create-patch" (:name %)) tools))
        list-tool (first (filter #(= "proposal.list" (:name %)) tools))]
    (t/is (contains? names "proposal.create-patch"))
    (t/is (contains? names "proposal.list"))
    (t/is (contains? names "proposal.request-apply"))
    (t/is (not (contains? names "native.commit")))
    (t/is (= "object" (get-in patch [:inputSchema :type])))
    (t/is (= ["fileId"] (get-in list-tool [:inputSchema :required])))
    (t/is (= :proposal-id (:result patch)))))

(t/deftest notifications-do-not-receive-json-rpc-responses
  (t/is (nil?
         (protocol/handle-request!
          nil nil
          {:jsonrpc "2.0"
           :method "notifications/initialized"
           :params {}}))))

(t/deftest returns-json-rpc-method-errors
  (let [response (protocol/handle-request!
                  nil nil
                  {:jsonrpc "2.0"
                   :id 3
                   :method "unknown/method"})]
    (t/is (= -32601 (get-in response [:error :code])))
    (t/is (= "unknown/method" (get-in response [:error :data :method])))))
