(ns clinicico.worker.main
  (:use [clojure.string :only [split trim capitalize]]
        [clojure.tools.cli :only [cli]])
  (:require [clinicico.worker.task :as tasks :only [initialize]]
            [clinicico.worker.store :as store]
            [clinicico.worker.pirate.core :as pirate]
            [clojure.tools.logging :as log]))


(defn run
  [method id params callback]
  (let [results (pirate/execute method id params callback)]
    (store/save-result id {:body results :id (:id params)})))

(defn -main
  [& args]
  (let [[options args banner]
        (cli args
             ["-h" "--help" "Show help" :default false :flag true]
             ["-n" "--nworkers" "Amount of worker threads to start"
              :default (.availableProcessors (Runtime/getRuntime))
              :parse-fn #(Integer. %)]
             ["-m" "--method" "R method name" :default "echo"]
             ["-p" "--packages" "Comma seperated list of additional R packages to load"
              :parse-fn #(split % #",\s?")]
             ["-f" "--file" "R file to execute" :default "resources/pirate/echo.R"])
        method (:method options)
        file (:file options)]
    (when (or (:help options))
      (println banner)
      (System/exit 0))
    (pirate/initialize (:file options) (:packages options))
    (tasks/initialize method (:nworkers options) run)
    (while true (Thread/sleep 100))))
