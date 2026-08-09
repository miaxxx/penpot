;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.ai.harness.archive
  "Bounded parser for uploaded Harness skill/plugin packages.

  Packages are data only. Executable files may be stored for portability but
  are never executed by this module or by the Harness runtime."
  (:require
   [app.common.ai.harness :as harness]
   [app.common.exceptions :as ex]
   [app.common.json :as json]
   [clojure.string :as str])
  (:import
   java.io.ByteArrayInputStream
   java.io.ByteArrayOutputStream
   java.nio.charset.StandardCharsets
   java.security.MessageDigest
   java.util.Base64
   java.util.zip.ZipInputStream))

(def text-extensions
  #{".md" ".txt" ".json" ".yaml" ".yml" ".edn" ".clj" ".cljs" ".cljc"
    ".js" ".ts" ".tsx" ".css" ".scss" ".html" ".svg" ".csv" ".xml"})

(defn- fail!
  [code hint & kvs]
  (apply ex/raise :type :validation :code code :hint hint kvs))

(defn- decode-base64
  [value]
  (try
    (.decode (Base64/getDecoder) ^String value)
    (catch Throwable _
      (fail! :invalid-ai-harness-package
             "uploaded package is not valid base64"))))

(defn payload-bytes
  [{:keys [content encoding]}]
  (let [bytes (case (keyword encoding)
                :base64 (decode-base64 content)
                :utf-8 (.getBytes (str content) StandardCharsets/UTF_8)
                :text (.getBytes (str content) StandardCharsets/UTF_8)
                (fail! :invalid-ai-harness-encoding
                       "encoding must be utf-8, text or base64"))]
    (when (> (alength bytes) harness/max-skill-package-bytes)
      (fail! :ai-harness-package-too-large
             "skill/plugin package exceeds the upload limit"
             :max-bytes harness/max-skill-package-bytes))
    bytes))

(defn sha256
  [^bytes bytes]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") bytes)]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) digest))))

(defn- safe-path!
  [value]
  (let [path (-> (str value)
                 (str/replace "\\" "/")
                 (str/replace #"^\./" ""))]
    (when (or (str/blank? path)
              (str/starts-with? path "/")
              (re-find #"(^|/)\.\.($|/)" path)
              (re-find #"[\u0000-\u001f]" path))
      (fail! :unsafe-ai-harness-package-path
             "package contains an unsafe path"
             :path value))
    path))

(defn- text-path?
  [path]
  (or (#{"SKILL.md" "skill.md" "manifest.json" "skill.json" "plugin.json"}
       path)
      (some #(str/ends-with? (str/lower-case path) %) text-extensions)))

(defn- read-entry-bytes
  [^ZipInputStream stream]
  (let [out (ByteArrayOutputStream.)
        buffer (byte-array 8192)]
    (loop [total 0]
      (let [read (.read stream buffer)]
        (if (neg? read)
          (.toByteArray out)
          (let [total (+ total read)]
            (when (> total harness/max-skill-file-bytes)
              (fail! :ai-harness-package-file-too-large
                     "one package file exceeds the per-file limit"
                     :max-bytes harness/max-skill-file-bytes))
            (.write out buffer 0 read)
            (recur total)))))))

(defn unzip-text-files
  [^bytes bytes]
  (with-open [stream (ZipInputStream. (ByteArrayInputStream. bytes))]
    (loop [files {}
           count 0
           total 0]
      (if-let [entry (.getNextEntry stream)]
        (let [path (safe-path! (.getName entry))]
          (if (.isDirectory entry)
            (recur files count total)
            (let [entry-bytes (read-entry-bytes stream)
                  size (alength entry-bytes)
                  count (inc count)
                  total (+ total size)]
              (when (> count harness/max-skill-files)
                (fail! :ai-harness-package-too-many-files
                       "package contains too many files"
                       :max-files harness/max-skill-files))
              (when (> total harness/max-skill-package-bytes)
                (fail! :ai-harness-package-too-large
                       "expanded package exceeds the upload limit"))
              (recur
               (if (text-path? path)
                 (assoc files path
                        (String. entry-bytes StandardCharsets/UTF_8))
                 files)
               count
               total))))
        files))))

(defn- parse-inline-list
  [value]
  (let [value (str/trim value)]
    (cond
      (str/blank? value) []
      (and (str/starts-with? value "[")
           (str/ends-with? value "]"))
      (or (try
            (json/decode value)
            (catch Throwable _ nil))
          (->> (subs value 1 (dec (count value)))
               (str/split #",")
               (map str/trim)
               (map #(str/replace % #"^['\"]|['\"]$" ""))
               vec))
      :else
      (->> (str/split value #",")
           (map str/trim)
           (remove str/blank?)
           vec))))

(defn- parse-frontmatter-value
  [key value]
  (case key
    ("tools" "capabilities" "keywords") (parse-inline-list value)
    ("autoActivate" "auto-activate") (= "true" (str/lower-case (str/trim value)))
    (str/replace (str/trim value) #"^['\"]|['\"]$" "")))

(defn parse-frontmatter
  [content]
  (let [lines (str/split-lines (str content))]
    (if-not (= "---" (first lines))
      {:manifest {} :body (str content)}
      (let [[header rest-lines] (split-with #(not= "---" %) (rest lines))
            body (if (seq rest-lines)
                   (str/join "\n" (rest rest-lines))
                   "")
            manifest
            (reduce
             (fn [result line]
               (if-let [[_ key value] (re-matches #"\s*([A-Za-z0-9_.-]+)\s*:\s*(.*)" line)]
                 (assoc result (keyword key)
                        (parse-frontmatter-value key value))
                 result))
             {}
             header)]
        {:manifest manifest :body body}))))

(defn- file-by-name
  [files candidate]
  (or (when-let [content (get files candidate)]
        [candidate content])
      (->> files
           (filter (fn [[path _]]
                     (= (str/lower-case candidate)
                        (str/lower-case
                         (last (str/split path #"/"))))))
           (sort-by (comp count first))
           first)))

(defn- json-file
  [files & paths]
  (some (fn [candidate]
          (when-let [[path content] (file-by-name files candidate)]
            (try
              (json/decode content :key-fn keyword)
              (catch Throwable _
                (fail! :invalid-ai-harness-manifest
                       "package JSON manifest is invalid"
                       :path path)))))
        paths))

(defn parse-package
  [{:keys [filename] :as payload}]
  (let [bytes (payload-bytes payload)
        lower (str/lower-case (str filename))
        files
        (cond
          (str/ends-with? lower ".zip")
          (unzip-text-files bytes)

          (str/ends-with? lower ".json")
          {"manifest.json" (String. bytes StandardCharsets/UTF_8)}

          :else
          {"SKILL.md" (String. bytes StandardCharsets/UTF_8)})
        markdown (or (second (file-by-name files "SKILL.md"))
                     (second (file-by-name files "skill.md"))
                     "")
        frontmatter (parse-frontmatter markdown)
        manifest (merge (:manifest frontmatter)
                        (or (json-file files
                                       "manifest.json"
                                       "skill.json"
                                       "plugin.json")
                            {}))
        entrypoint (or (:entrypoint manifest) "SKILL.md")
        instructions
        (or (:instructions manifest)
            (:prompt manifest)
            (get manifest "instructions")
            (get manifest "prompt")
            (if (#{"SKILL.md" "skill.md"} entrypoint)
              (:body frontmatter)
              (or (second (file-by-name files entrypoint))
                  (:body frontmatter)
                  markdown)))]
    {:filename filename
     :content-hash (sha256 bytes)
     :manifest manifest
     :files files
     :instructions (str instructions)}))
