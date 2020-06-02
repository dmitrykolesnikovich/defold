;; Copyright 2020 The Defold Foundation
;; Licensed under the Defold License version 1.0 (the "License"); you may not use
;; this file except in compliance with the License.
;; 
;; You may obtain a copy of the License, together with FAQs at
;; https://www.defold.com/license
;; 
;; Unless required by applicable law or agreed to in writing, software distributed
;; under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
;; CONDITIONS OF ANY KIND, either express or implied. See the License for the
;; specific language governing permissions and limitations under the License.

(ns editor.bootloader
  "Loads namespaces in parallel when possible to speed up start time."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def load-info (atom nil))

(defn load-boot
  ([]
   (load-boot false))
  ([print-to-stdout]
   ;; The file sorted_clojure_ns_list.edn is generated at boot time if using lein
   ;; run. See debug.clj. When bundling it is generated by a leiningen task
   ;; called build_ns_batches which is called from bundle.py. The code that
   ;; generates the file is in ns_batch_builder.clj.
   (let [batches (->> (io/resource "sorted_clojure_ns_list.edn")
                      (slurp)
                      (edn/read-string))
         boot-loaded? (promise)
         loader (future
                  (try
                    (doseq [batch batches]
                      (dorun (pmap #(do
                                      (when print-to-stdout
                                        (print (str "Loading namespace " % \newline))
                                        (flush))
                                      (require %))
                                   batch))
                      (when (contains? batch 'editor.boot)
                        (deliver boot-loaded? true)))
                    (catch Throwable t
                      (deliver boot-loaded? t)
                      (throw t))))]
     (reset! load-info [loader boot-loaded?]))))

(defn wait-until-editor-boot-loaded
  []
  (let [result @(second @load-info)]
    (when (instance? Throwable result)
      (throw result))))

(defn load-synchronous
  [print-to-stdout]
  (load-boot print-to-stdout)
  (wait-until-editor-boot-loaded))

(defn main
  [args]
  (let [[loader _] @load-info]
    (reset! load-info nil)
    (ns-unmap *ns* 'load-info)
    (let [boot-main (resolve 'editor.boot/main)]
      (boot-main args loader))))
