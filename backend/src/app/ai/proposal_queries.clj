;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.ai.proposal-queries
  "Read models for resuming persistent AI proposals in Penpot workspaces."
  (:require
   [app.ai.policy :as policy]
   [app.ai.proposals :as proposals]
   [app.db :as db]))

(def ^:private sql:list-file-active
  "select *
     from ai_design_proposal
    where profile_id = ?
      and file_id = ?
      and status in ('validated', 'previewed')
      and expires_at > clock_timestamp()
    order by modified_at desc
    limit 20")

(def ^:private sql:list-page-active
  "select *
     from ai_design_proposal
    where profile_id = ?
      and file_id = ?
      and page_id = ?
      and status in ('validated', 'previewed')
      and expires_at > clock_timestamp()
    order by modified_at desc
    limit 20")

(defn list-active!
  [cfg profile-id file-id page-id]
  (policy/ensure-enabled!)
  (policy/ensure-read! cfg profile-id file-id)
  (let [rows (if page-id
               (db/exec! cfg [sql:list-page-active profile-id file-id page-id])
               (db/exec! cfg [sql:list-file-active profile-id file-id]))]
    (mapv (comp proposals/public-view proposals/decode-row) rows)))
