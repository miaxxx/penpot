;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.ai.providers.protocol)

(defprotocol Provider
  (test-connection! [provider cfg credential]
    "Tests provider connectivity without returning or logging the credential.")
  (generate-design! [provider cfg request]
    "Returns provider text for one structured design proposal. The provider has
    no canvas commit, filesystem, plugin or arbitrary tool capability."))
