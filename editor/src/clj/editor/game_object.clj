(ns editor.game-object
  (:require [clojure.java.io :as io]
            [dynamo.buffers :refer :all]
            [editor.protobuf :as protobuf]
            [dynamo.geom :as geom]
            [dynamo.graph :as g]
            [dynamo.types :as t :refer :all]
            [dynamo.ui :refer :all]
            [editor.math :as math]
            [editor.project :as project]
            [editor.scene :as scene]
            [editor.workspace :as workspace]
            [editor.core :as core]
            [editor.ui :as ui]
            [editor.handler :as handler]
            [editor.dialogs :as dialogs]
            [editor.outline-view :as outline-view])
  (:import [com.dynamo.gameobject.proto GameObject$PrototypeDesc]
           [com.dynamo.graphics.proto Graphics$Cubemap Graphics$TextureImage Graphics$TextureImage$Image Graphics$TextureImage$Type]
           [com.dynamo.proto DdfMath$Point3 DdfMath$Quat]
           [com.jogamp.opengl.util.awt TextRenderer]
           [dynamo.types Region Animation Camera Image TexturePacking Rect EngineFormatTexture AABB TextureSetAnimationFrame TextureSetAnimation TextureSet]
           [java.awt.image BufferedImage]
           [java.io PushbackReader]
           [javax.media.opengl GL GL2 GLContext GLDrawableFactory]
           [javax.media.opengl.glu GLU]
           [javax.vecmath Matrix4d Point3d Quat4d Vector3d]))


(def game-object-icon "icons/brick.png")

(defn- gen-ref-ddf [id ^Vector3d position ^Quat4d rotation save-data]
  {:id id
   :position (math/vecmath->clj position)
   :rotation (math/vecmath->clj rotation)
   :component (or (and (:resource save-data) (workspace/proj-path (:resource save-data)))
                  ".unknown")})

(defn- gen-embed-ddf [id ^Vector3d position ^Quat4d rotation save-data]
  {:id id
   :type (or (and (:resource save-data) (:ext (workspace/resource-type (:resource save-data))))
             "unknown")
   :position (math/vecmath->clj position)
   :rotation (math/vecmath->clj rotation)
   :data (or (:content save-data) "")})

(g/defnode ComponentNode
  (inherits scene/SceneNode)

  (property id t/Str)
  (property embedded (t/maybe t/Bool) (visible (g/fnk [] false)))
  (property path (t/maybe t/Str) (visible (g/fnk [] false)))

  (input source t/Any)
  (input outline t/Any)
  (input save-data t/Any)
  (input scene t/Any)
  (input build-targets t/Any)

  (output outline t/Any :cached (g/fnk [node-id embedded path id outline] (let [suffix (if embedded "" (format " (%s)" path))]
                                                                            (assoc outline :node-id node-id :label (str id suffix)))))
  (output ddf-message t/Any :cached (g/fnk [id embedded position rotation save-data] (if embedded
                                                                                       (gen-embed-ddf id position rotation save-data)
                                                                                       (gen-ref-ddf id position rotation save-data))))
  (output scene t/Any :cached (g/fnk [node-id transform scene]
                                     (assoc scene
                                            :node-id node-id
                                            :transform transform
                                            :aabb (geom/aabb-transform (geom/aabb-incorporate (get scene :aabb (geom/null-aabb)) 0 0 0) transform))))
  (output build-targets t/Any :cached (g/fnk [build-targets ddf-message transform]
                                             (if-let [target (first build-targets)]
                                               [(assoc target :instance-data {:resource (:resource target)
                                                                              :instance-msg ddf-message
                                                                              :transform transform})]
                                               [])))

  core/MultiNode
  (sub-nodes [self] (if (:embedded self) [(g/node-value self :source)] [])))

(g/defnk produce-proto-msg [ref-ddf embed-ddf]
  {:components ref-ddf
   :embedded-components embed-ddf})

(g/defnk produce-save-data [resource proto-msg]
  {:resource resource
   :content (protobuf/map->str GameObject$PrototypeDesc proto-msg)})

(defn- externalize [inst-data resources]
  (map (fn [data]
         (let [{:keys [resource instance-msg transform]} data
               resource (get resources resource)
               instance-msg (dissoc instance-msg :type :data)]
           (merge instance-msg
                  {:component (workspace/proj-path resource)})))
       inst-data))

(defn- build-game-object [self basis resource dep-resources user-data]
  (let [instance-msgs (externalize (:instance-data user-data) dep-resources)
        msg {:components instance-msgs}]
    {:resource resource :content (protobuf/map->bytes GameObject$PrototypeDesc msg)}))

(g/defnk produce-build-targets [node-id resource proto-msg dep-build-targets]
  [{:node-id node-id
    :resource (workspace/make-build-resource resource)
    :build-fn build-game-object
    :user-data {:proto-msg proto-msg :instance-data (map :instance-data (flatten dep-build-targets))}
    :deps (flatten dep-build-targets)}])

(g/defnk produce-scene [node-id child-scenes]
  {:node-id node-id
   :aabb (reduce geom/aabb-union (geom/null-aabb) (filter #(not (nil? %)) (map :aabb child-scenes)))
   :children child-scenes})

(g/defnode GameObjectNode
  (inherits project/ResourceNode)

  (input outline t/Any :array)
  (input ref-ddf t/Any :array)
  (input embed-ddf t/Any :array)
  (input child-scenes t/Any :array)
  (input child-ids t/Str :array)
  (input dep-build-targets t/Any :array)

  (output outline t/Any :cached (g/fnk [node-id outline] {:node-id node-id :label "Game Object" :icon game-object-icon :children outline}))
  (output proto-msg t/Any :cached produce-proto-msg)
  (output save-data t/Any :cached produce-save-data)
  (output build-targets t/Any :cached produce-build-targets)
  (output scene t/Any :cached produce-scene))

(defn- connect-if-output [out-node out-label in-node in-label]
  (if ((g/outputs out-node) out-label)
    (g/connect out-node out-label in-node in-label)
    []))

(defn- gen-component-id [go-node base]
  (let [ids (g/node-value go-node :child-ids)]
    (loop [postfix 0]
      (let [id (if (= postfix 0) base (str base postfix))]
        (if (empty? (filter #(= id %) ids))
          id
          (recur (inc postfix)))))))

(defn- add-component [self source-node id position rotation]
  (let [path (if source-node (workspace/proj-path (:resource source-node)) "")]
    (g/make-nodes (g/node->graph-id self)
                  [comp-node [ComponentNode :id id :position position :rotation rotation :path path]]
                  (concat
                   (g/connect comp-node :outline self :outline)
                   (g/connect comp-node :self    self :nodes)
                   (g/connect comp-node :build-targets    self :dep-build-targets)
                   (when source-node
                    (concat
                      (g/connect comp-node   :ddf-message self      :ref-ddf)
                      (g/connect comp-node   :id          self      :child-ids)
                      (g/connect comp-node   :scene       self      :child-scenes)
                      (g/connect source-node :self        comp-node :source)
                      (connect-if-output source-node :outline comp-node :outline)
                      (connect-if-output source-node :save-data comp-node :save-data)
                      (connect-if-output source-node :scene comp-node :scene)
                      (connect-if-output source-node :build-targets comp-node :build-targets)))))))

(defn add-component-handler [self]
  (let [project (project/get-project self)
        workspace (:workspace (:resource self))
        component-exts (map :ext (workspace/get-resource-types workspace :component))]
    (when-let [; TODO - filter component files
               resource (first (dialogs/make-resource-dialog workspace {}))]
      (let [id (gen-component-id self (:ext (workspace/resource-type resource)))
            op-seq (gensym)
            [comp-node] (g/tx-nodes-added
                          (g/transact
                            (concat
                              (g/operation-label "Add Component")
                              (g/operation-sequence op-seq)
                              (add-component self (project/get-resource-node project resource) id [0 0 0] [0 0 0]))))]
        ; Selection
        (g/transact
          (concat
            (g/operation-sequence op-seq)
            (g/operation-label "Add Component")
            (project/select project [comp-node])))))))

(handler/defhandler :add-from-file :global
    (enabled? [selection] (and (= 1 (count selection)) (= GameObjectNode (g/node-type (g/node-by-id (first selection))))))
    (run [selection] (add-component-handler (g/node-by-id (first selection)))))

(defn- add-embedded-component [self project type data id position rotation]
  (let [resource (project/make-embedded-resource project type data)]
    (if-let [resource-type (and resource (workspace/resource-type resource))]
      (g/make-nodes (g/node->graph-id self)
                    [comp-node [ComponentNode :id id :embedded true :position position :rotation rotation]
                     source-node [(:node-type resource-type) :resource resource :project-id (g/node-id project) :resource-type resource-type]]
                    (g/connect source-node :self        comp-node :source)
                    (g/connect source-node :outline     comp-node :outline)
                    (g/connect source-node :save-data   comp-node :save-data)
                    (g/connect source-node :scene       comp-node :scene)
                    (g/connect source-node :build-targets       comp-node :build-targets)
                    (g/connect source-node :self        self      :nodes)
                    (g/connect comp-node   :outline     self      :outline)
                    (g/connect comp-node   :ddf-message self      :embed-ddf)
                    (g/connect comp-node   :id          self      :child-ids)
                    (g/connect comp-node   :scene       self      :child-scenes)
                    (g/connect comp-node   :self        self      :nodes)
                    (g/connect comp-node   :build-targets        self      :dep-build-targets))
      (g/make-nodes (g/node->graph-id self)
                    [comp-node [ComponentNode :id id :embedded true]]
                    (g/connect comp-node   :outline      self      :outline)
                    (g/connect comp-node   :self         self      :nodes)))))

(defn add-embedded-component-handler [self]
  (let [project (project/get-project self)
        workspace (:workspace (:resource self))
        ; TODO - add sub menu with all components
        component-type (first (workspace/get-resource-types workspace :component))
        template (workspace/template component-type)]
    (let [id (gen-component-id self (:ext component-type))
          op-seq (gensym)
          [comp-node source-node] (g/tx-nodes-added
                                    (g/transact
                                      (concat
                                        (g/operation-sequence op-seq)
                                        (g/operation-label "Add Component")
                                        (add-embedded-component self project (:ext component-type) template id [0 0 0] [0 0 0]))))]
      (g/transact
        (concat
          (g/operation-sequence op-seq)
          (g/operation-label "Add Component")
          ((:load-fn component-type) project source-node (io/reader (:resource source-node)))
          (project/select project [comp-node]))))))

(handler/defhandler :add :global
    (enabled? [selection] (and (= 1 (count selection)) (= GameObjectNode (g/node-type (g/node-by-id (first selection))))))
    (run [selection] (add-embedded-component-handler (g/node-by-id (first selection)))))

(defn- v4->euler [v]
  (math/quat->euler (doto (Quat4d.) (math/clj->vecmath v))))

(defn load-game-object [project self input]
  (let [project-graph (g/node->graph-id self)
        prototype (protobuf/read-text GameObject$PrototypeDesc input)]
    (concat
      (for [component (:components prototype)
            :let [source-node (project/resolve-resource-node self (:component component))]]
        (add-component self source-node (:id component) (:position component) (v4->euler (:rotation component))))
      (for [embedded (:embedded-components prototype)]
        (add-embedded-component self project (:type embedded) (:data embedded) (:id embedded) (:position embedded) (v4->euler (:rotation embedded)))))))

(defn register-resource-types [workspace]
  (workspace/register-resource-type workspace
                                    :ext "go"
                                    :node-type GameObjectNode
                                    :load-fn load-game-object
                                    :icon game-object-icon
                                    :view-types [:scene]
                                    :view-opts {:scene {:grid true}}
                                    :template "templates/template.go"))
