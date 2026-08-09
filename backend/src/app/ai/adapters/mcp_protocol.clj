;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.ai.adapters.mcp-protocol
  "MCP JSON-RPC method adapter for authenticated Penpot transports.

  This namespace does not open a socket or trust identity supplied by JSON-RPC.
  HTTP/SSE/stdio transports must authenticate first and pass an actor map with
  profile-id and, for live reads, a bounded workspace-context bridge."
  (:require
   [app.ai.adapters.mcp :as mcp]
   [app.common.json :as json]
   [clojure.string :as str]))

(def protocol-version "2025-06-18")
(def server-name "penpot-ai-design-agent")
(def server-version "1.0")

(defn initialize-result
  []
  {:protocolVersion protocol-version
   :capabilities {:tools {:listChanged false}}
   :serverInfo {:name server-name
                :version server-version}
   :instructions
   "Canvas writes create a persistent proposalId. Native Penpot changes require explicit confirmation in the Penpot workspace and cannot be committed by MCP."})

(defn- tool-result
  [result]
  {:content [{:type "text"
              :text (json/encode result :key-fn json/write-camel-key)}]
   :structuredContent result
   :isError false})

(defn- tool-name
  [params]
  (or (:name params) (get params "name")))

(defn- tool-arguments
  [params]
  (or (:arguments params) (get params "arguments") {}))

(defn invoke-method!
  "Dispatches one MCP method after transport authentication.

  Exceptions intentionally propagate so the concrete transport can map Penpot
  exception types/codes to JSON-RPC errors without losing structured metadata."
  [cfg actor method params]
  (case method
    "initialize"
    (initialize-result)

    "notifications/initialized"
    nil

    "ping"
    {}

    "tools/list"
    {:tools (:tools (mcp/list-tools))}

    "tools/call"
    (tool-result
     (mcp/invoke! cfg actor (tool-name params) (tool-arguments params)))

    {:unsupported-method method}))

(defn handle-request!
  [cfg actor request]
  (let [jsonrpc (or (:jsonrpc request) (get request "jsonrpc"))
        id (or (:id request) (get request "id"))
        method (or (:method request) (get request "method"))
        params (or (:params request) (get request "params") {})]
    (cond
      (not= "2.0" jsonrpc)
      {:jsonrpc "2.0"
       :id id
       :error {:code -32600
               :message "Invalid JSON-RPC request"}}

      (or (not (string? method)) (str/blank? method))
      {:jsonrpc "2.0"
       :id id
       :error {:code -32600
               :message "Missing JSON-RPC method"}}

      :else
      (let [result (invoke-method! cfg actor method params)]
        (cond
          ;; JSON-RPC notifications never receive a response.
          (nil? id)
          nil

          (= result {:unsupported-method method})
          {:jsonrpc "2.0"
           :id id
           :error {:code -32601
                   :message "Method not found"
                   :data {:method method}}}

          :else
          {:jsonrpc "2.0"
           :id id
           :result result})))))
