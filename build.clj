(ns build
  (:require
   [clojure.tools.build.api :as b]
   [clojure.java.io :as jio]
   [clojure.java.shell :refer [sh]]
   [clojure.edn :as edn]
   [clojure.pprint :refer [pprint]]
   [clojure.string :as str]
   [weavejester.dependency :as dep]
   [deps-deploy.deps-deploy :as deploy]
   [kit.sync-lib-deps :as sync-lib-deps]))

(def libs-dir "libs")
(def versions (read-string (slurp "./libs/deps-template/resources/io/github/kit_clj/kit/versions.edn")))
(def group-id "io.github.kit-clj")
(def src ["src"])
(def basis (b/create-basis {:project "deps.edn"}))

(defn clean
  "Delete the build target directory"
  [{:keys [target-dir]}]
  (println (str "Cleaning " target-dir))
  (b/delete {:path target-dir}))

(defn- parse-git-status [line]
  (let [[[_ status path]] (re-seq #"^(.{2}) (.+)$" line)]
    {:status status
     :path path}))

(defn- git-status []
  (let [{:keys [out err]} (sh "git" "status" "--porcelain=v1")]
    (if-not (str/blank? err)
      {:err err}
      {:statuses (if (str/blank? out)
                   '()
                   (map parse-git-status (str/split-lines out)))})))

(defn- git-clean-working-directory? [git-status-result]
  (if (:err git-status-result)
    (do
      (println (:err git-status-result))
      (System/exit 1))
    (some->> (:statuses git-status-result)
             (remove #(re-seq #"^\?" (:status %)))
             empty?)))

(defn- assert-clean-working-directory []
  (when-not (git-clean-working-directory? (git-status))
    (throw (ex-info "All changes must be committed before building." {}))))

(defn make-jar
  "Create the jar from a source pom and source files"
  [{:keys [class-dir lib version basis src jar-file] :as m}]
  (pprint (dissoc m :basis))
  (b/write-pom {:class-dir class-dir
                :lib       lib
                :version   version
                :src-pom   "pom.xml"
                :basis     basis
                :src-dirs  src})
  (b/copy-dir {:src-dirs   src
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file  jar-file}))

(defn install
  "Install jar to local repo"
  [{:keys [basis lib version jar-file class-dir]}]
  (println "Installing... " jar-file)
  (b/install {:basis     basis
              :lib       lib
              :version   version
              :jar-file  jar-file
              :class-dir class-dir}))

(defn- dep-hm [{:keys [libs]}]
  (let [proj                    (map #(symbol group-id (.getName %)) libs)
        only-matching-group-ids (fn [ks] (into #{} (filter (fn [d] (= group-id (namespace d))) ks)))
        deps                    (into [] (comp
                                           (map #(str % "/deps.edn"))
                                           (map slurp)
                                           (map edn/read-string)
                                           (map :deps)
                                           (map keys)
                                           (map only-matching-group-ids))
                                      libs)]
    (zipmap proj deps)))

(defn- build-graph [{:keys [libs] :as m}]
  (let [dep-mappings (dep-hm m)]
    (loop [g (dep/graph) [[k vs :as entry] & m] dep-mappings]
      (if entry
        (recur (reduce #(dep/depend %1 k %2) g vs) m)
        {:graph g :dep-mappings dep-mappings}))))

(defn- topo-sort [{:keys [graph dep-mappings]}]
  (let [sorted (dep/topo-sort graph)]
    (concat sorted (reduce disj (set (keys dep-mappings)) sorted))))

(defn- build-data [lib]
  (let [l          (str libs-dir "/" (name lib))
        src-dir    [(str l "/src") (str l "/resources")]
        target-dir (str l "/target")
        class-dir  (str target-dir "/classes")
        src-pom    (str class-dir "/META-INF/maven/" group-id "/" (name lib) "/pom.xml")
        basis      (b/create-basis {:project (str l "/deps.edn")})
        version    (get versions (name lib))
        jar-file   (format "%s/%s-%s.jar" target-dir (name lib) version)]
    {:target-dir target-dir :class-dir class-dir :lib lib :version version :basis basis :src src-dir
     :src-pom    src-pom :jar-file jar-file}))

(defn deploy
  "Deploy jar locally or to remote artifactory"
  [{:keys [src-pom installer sign-releases? jar-file] :or {installer :local sign-releases? false}}]
  (println "Deploying: " jar-file)
  (deploy/deploy {:installer      installer
                  :sign-releases? sign-releases?
                  :pom-file       src-pom
                  :artifact       jar-file}))

(defn- sync-lib-deps [{:keys [lib] :as m}]
  (println "Syncing lib deps...")
  (doseq [path (sync-lib-deps/sync-lib-deps :libs [(str (name lib))])]
    (println path))
  (println))

(defn- lib-pipeline [publish? bd]
  (sync-lib-deps bd)
  ;; Only check when publishing if files are committed
  (when publish?
    (assert-clean-working-directory))
  (clean bd)
  (make-jar bd)
  (install bd)
  (when publish?
    (deploy (merge {:installer :remote} bd))))

(defn list-files [libs-dir]
  (->> (jio/file libs-dir)
       (.listFiles)
       (filter #(and (.isDirectory %) (not (.startsWith (.getName %) "."))))
       (distinct)))

(defn install-lib [{:keys [artifact-id] publish? :publish :or {publish? false} :as params}]
  (let [libs (list-files libs-dir)
        {:keys [graph dep-mappings]} (build-graph {:libs libs})
        lib (some->> artifact-id name (symbol group-id))]
    (if (contains? dep-mappings lib)
      (if (not-empty (dep/transitive-dependencies graph lib))
        (doseq [lib' (concat (dep/transitive-dependencies graph lib) [lib])
                :let [bd (build-data lib')]]
          (lib-pipeline (and publish? (= lib' lib)) bd))
        (let [bd (build-data lib)]
          (lib-pipeline publish? bd)))
      (println "Can't find: " artifact-id))))

(defn- do-libs [action]
  (let [libs (list-files libs-dir)]
    (doseq [lib (topo-sort (build-graph {:libs libs}))]
      (let [bd (build-data lib)]
        (action bd)))))

(defn clean-libs
  "Clean all libs."
  [_]
  (do-libs clean))

(defn make-jar-libs
  "Make jar for all libs."
  [_]
  (do-libs make-jar))

(defn install-libs
  "Install all libs."
  [_]
  (do-libs install))

(defn publish-libs
  "Publish all libs."
  [_]
  (do-libs
   (fn [bd]
     (deploy (merge {:installer :remote} bd)))))

(defn all
  "Performs clean build and install of all libs. Optionally publishes."
  [{publish? :publish :or {publish? false} :as m}]
  (do-libs #(lib-pipeline publish? %)))
