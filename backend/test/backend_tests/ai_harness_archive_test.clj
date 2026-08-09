;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns backend-tests.ai-harness-archive-test
  (:require
   [app.ai.harness.archive :as archive]
   [clojure.test :as t])
  (:import
   java.io.ByteArrayOutputStream
   java.nio.charset.StandardCharsets
   java.util.Base64
   java.util.zip.ZipEntry
   java.util.zip.ZipOutputStream))

(defn- zip-base64
  [files]
  (let [out (ByteArrayOutputStream.)]
    (with-open [zip (ZipOutputStream. out)]
      (doseq [[path content] files]
        (.putNextEntry zip (ZipEntry. path))
        (.write zip (.getBytes content StandardCharsets/UTF_8))
        (.closeEntry zip)))
    (.encodeToString (Base64/getEncoder) (.toByteArray out))))

(t/deftest parses-markdown-skill
  (let [package
        (archive/parse-package
         {:filename "audit.md"
          :encoding "utf-8"
          :content
          (str "---\n"
               "name: Audit Skill\n"
               "version: 1.2\n"
               "tools: [\"canvas.read\"]\n"
               "---\n"
               "Review the selected design.")})]
    (t/is (= "Audit Skill" (get-in package [:manifest :name])))
    (t/is (= "Review the selected design."
             (:instructions package)))
    (t/is (= 64 (count (:content-hash package))))))

(t/deftest parses-bounded-zip-skill
  (let [package
        (archive/parse-package
         {:filename "skill.zip"
          :encoding "base64"
          :content
          (zip-base64
           {"SKILL.md" "Use native Penpot layout."
            "manifest.json"
            "{\"name\":\"Layout Skill\",\"version\":\"1.0\",\"tools\":[\"canvas.read\"]}"})})]
    (t/is (= "Layout Skill" (get-in package [:manifest :name])))
    (t/is (= 2 (count (:files package))))))

(t/deftest finds-skill-files-inside-an-outer-folder
  (let [package
        (archive/parse-package
         {:filename "nested.zip"
          :encoding "base64"
          :content
          (zip-base64
           {"my-skill/SKILL.md" "Nested skill instructions."
            "my-skill/manifest.json"
            "{\"name\":\"Nested Skill\",\"version\":\"1.0\"}"})})]
    (t/is (= "Nested Skill" (get-in package [:manifest :name])))
    (t/is (= "Nested skill instructions." (:instructions package)))))

(t/deftest rejects-path-traversal
  (t/is
   (thrown?
    Exception
    (archive/parse-package
     {:filename "unsafe.zip"
      :encoding "base64"
      :content (zip-base64 {"../SKILL.md" "unsafe"})}))))
