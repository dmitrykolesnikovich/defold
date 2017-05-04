(ns editor.graph-util
  (:require [dynamo.graph :as g]
            [internal.graph :as ig]
            [internal.graph.types :as gt]
            [internal.system :as is]))

(set! *warn-on-reflection* true)

(defmacro passthrough [field]
  `(g/fnk [~field] ~field))

(defn prev-sequence-label [graph]
  (let [sys @g/*the-system*]
    (when-let [prev-step (some-> (is/graph-history sys graph)
                                 (is/undo-stack)
                                 (last))]
      (:sequence-label prev-step))))

(defn disconnect-all [basis node-id label]
  (for [[src-node-id src-label] (g/sources-of basis node-id label)]
    (g/disconnect src-node-id src-label node-id label)))

(defn array-subst-remove-errors [arr]
  (vec (remove g/error? arr)))

(defn outputs [node]
  (mapv #(do [(second (gt/head %)) (gt/tail %)]) (gt/arcs-by-head (g/now) node)))

(defn explicit-outputs [node]
    ;; don't include arcs from override-original nodes
  (mapv #(do [(second (gt/head %)) (gt/tail %)]) (ig/explicit-arcs-by-source (g/now) node)))
