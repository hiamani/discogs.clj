#!/usr/bin/env bb
(require '[babashka.http-client :as http]
         '[cheshire.core :as json]
         '[clojure.string :as str]
         '[clojure.tools.cli :refer [parse-opts]])

;; Interact with tty

(defn stty [& args]
  (-> (ProcessBuilder. (into ["stty"] args))
      (.redirectInput (java.io.File. "/dev/tty"))
      (.start)
      (.waitFor)))

(stty "-icanon" "-echo")

(.addShutdownHook (Runtime/getRuntime) (Thread. #(stty "icanon" "echo")))

(defn read-char []
  (let [tty (java.io.FileInputStream. "/dev/tty")
        ch (.read tty)]
    (.close tty)
    ch))

;; CLI Options

(def cli-options
  [["-h" "--help"        "Show help"]
   ["-g" "--genre GENRE" "Genre"]
   ["-s" "--style STYLE" "Style"]
   ["-y" "--year YEAR"   "Year"]
   ["-p" "--page PAGE"   "Starting page" :parse-fn parse-long :default 1]
   ["-i" "--index INDEX" "Starting index" :parse-fn parse-long :default 1]])

;; State

(def initial-state
  (let [opts (parse-opts *command-line-args* cli-options)
        {:keys [genre style year page index]} (:options opts)]
    {:opts opts
     :genre genre
     :style style
     :year year
     :page page
     :index index
     :results nil}))

;; Effects

(defn fetch-page! [{:keys [genre style year page] :as state}]
  (let [url "https://api.discogs.com/database/search"
        params {:genre genre
                :style style
                :year year
                :page page}
        resp (http/get url {:query-params params :throw false})
        body (json/parse-string (:body resp) true)
        results (when (< 0 (:items (:pagination body)))
                  (:results body))]
    (assoc state :results results)))

(defn fetch-release! [{:keys [master_url resource_url]}]
  (if-let [master-resp (and master_url (http/get master_url {:throw false}))]
    (when (= (:status master-resp) 200)
      (assoc (json/parse-string (:body master-resp) true) :master? true))
    (let [resource-resp (http/get resource_url {:throw false})]
      (when (= (:status resource-resp) 200)
        (assoc (json/parse-string (:body resource-resp) true) :master? false)))))

(defn advance! [{:keys [index page results] :as state}]
  (let [next-index (inc index)
        next-page? (> next-index (count results))]
    (if next-page?
      (fetch-page! (assoc state :page (inc page) :index 1))
      (assoc state :index next-index))))

(defn fetch-current-release! [{:keys [index results] :as _state}]
  (let [result (nth results (dec index) nil)]
    (fetch-release! result)))

;; Display

(defn print-error [s]
  (let [divider (apply str (repeat 63 \-))]
    (println divider)
    (println " [!] Error:" s)
    (println divider)))

(defn print-summary [state]
  (println "discogs.clj -- Scrape the Discogs API for release videos")
  (println (:summary (:opts state))))

(defn print-init [{:keys [page index genre style] :as _state}]
  (println "Welcome! Querying Discogs for style"
           (str "{" (str/capitalize style) "}")
           "in genre"
           (str "{" (str/capitalize genre) "}"))
  (println "> Starting at page" (str page ", index") index)
  (println "> Press enter for next release, q to quit.")
  (println))

(defn print-release [{:keys [page index results] :as _state} release]
  (let [index-of (str "(" index " of " (count results) ")")]
    (println "-----------" "Page" page index-of "-----------")
    (if release
      (do (println "Artists:" (str/join ", " (map :name (:artists release))))
          (println "Title:"   (:title release))
          (println "Year:"    (:year release))
          (println "Master:"  (if (:master? release) "Yes" "No"))
          (println "Page:"    (:uri release))
          (if (seq (:videos release))
            (do (println "Videos:")
                (doseq [video (:videos release)]
                  (println "  -" (:title video))
                  (println "   " (:uri video))))
            (println "(No Videos)")))
      (println "[!] No release found!"))
    (println)))

;; Main

(defn validate-state [{:keys [opts] :as state} cli-args]
  (cond (or (= 0 (count cli-args))
            (:help (:options opts)))
        (do (print-summary state)
            (System/exit 0))

        (:errors opts)
        (do (run! print-error (:errors opts))
            (print-summary state)
            (System/exit 1))

        (nil? (:genre state))
        (do (print-error "Please specify a genre")
            (print-summary state)
            (System/exit 1))

        (nil? (:style state))
        (do (print-error "Please specify a style")
            (print-summary state)
            (System/exit 1))))

(defn read-loop! [state]
  (loop [state state]
    (let [input (read-char)]
      (cond
        (= input (int \q)) nil

        (= input (int \newline))
        (let [release (fetch-current-release! state)]
          (print-release state release)
          (recur (advance! state)))

        :else (recur state)))))

(defn main! []
  (validate-state initial-state *command-line-args*)
  (let [state (fetch-page! initial-state)]
    (if (some? (:results state))
      (do (print-init state)
          (read-loop! state))
      (println "No initial results"))))

;; Init

(main!)
