#!/usr/bin/env bb
(require '[babashka.pods :as pods]
         '[babashka.http-client :as http]
         '[cheshire.core :as json]
         '[clojure.string :as str]
         '[clojure.tools.cli :refer [parse-opts]]
         '[clojure.java.browse :refer [browse-url]])

(pods/load-pod 'huahaiy/datalevin "0.10.16")

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
    {:opts    opts
     :genre   genre
     :style   style
     :year    year
     :page    (if (< 0 page) page 1)
     :index   (if (< 0 index) (dec index) 0)
     :results nil
     :release nil
     :video-index 0}))

;; Effects

(defn fetch-page! [{:keys [genre style year page] :as state}]
  (let [url "https://api.discogs.com/database/search"
        params {:genre genre
                :style style
                :year year
                :page page}
        resp (http/get url {:query-params params :throw false})]
    (if (= 200 (:status resp))
      (let [body    (json/parse-string (:body resp) true)
            results (when (< 0 (:items (:pagination body)))
                      (:results body))]
        (assoc state :results results))
      (assoc state :results nil))))

(defn fetch-resource! [result k]
  (when-let [resp (some-> (get result k) (http/get {:throw false}))]
    (when (= 200 (:status resp))
      (assoc (json/parse-string (:body resp) true)
             :master? (= k :master_url)))))

(defn fetch-release! [result]
  (or (fetch-resource! result :master_url)
      (fetch-resource! result :resource_url)))

(defn fetch-current-release! [{:keys [index results] :as state}]
  (let [release (some-> results (nth index nil) (fetch-release!))]
    (assoc state :release release :video-index 0)))

(defn forward! [{:keys [index page results] :as state}]
  (let [next-index (inc index)
        next-page? (>= next-index (count results))]
    (-> (if next-page?
          (fetch-page! (assoc state :page (inc page) :index 1))
          (assoc state :index next-index))
        (fetch-current-release!))))

(defn back! [{:keys [index page] :as state}]
  (let [prev-index (dec index)]
    (cond
      (>= prev-index 0)
      (-> state
          (assoc :index prev-index)
          (fetch-current-release!))

      (> page 1)
      (let [prev-state (-> state
                           (assoc :page (dec page))
                           (fetch-page!))]
        (if (some? (:results prev-state))
          (-> prev-state
              (assoc :index (dec (count (:results prev-state))))
              (fetch-current-release!))
          (fetch-current-release! state)))

      :else
      (fetch-current-release! state))))

;; Display

(defn print-error [s]
  (let [divider (apply str (repeat 56 \-))]
    (println divider)
    (println " [!] Error:" s)
    (println divider)))

(defn print-summary [state]
  (println "discogs.clj -- Scrape the Discogs API for release videos")
  (println (:summary (:opts state))))

(defn print-init [{:keys [page index genre style] :as _state}]
  (if style
    (println "Welcome! Querying Discogs for style"
             (str "{" (str/capitalize style) "}")
             "in genre"
             (str "{" (str/capitalize genre) "}"))
    (println "Welcome! Querying Discogs for genre"
             (str "{" (str/capitalize genre) "}")))
  (println "> Starting at page" (str page ", index") (inc index)))

(defn print-instructions []
  (println)
  (println "[?] Press n for next release, p for previous, q to quit.")
  (println "    To navigate videos, press j to go up, k to go down")
  (println "    Press Enter to open video link in the browser"))

(defn bold [s]
  (str "\033[1m" s "\033[0m"))

(defn green [s]
  (str "\033[32m" s "\033[0m"))

(defn print-release [{:keys [page index results release video-index]}]
  (let [index-of (str "(" (inc index) " of " (count results) ")")]
    (println "-----------" "Page" page index-of "-----------")
    (if release
      (do (println "Artists:" (str/join ", " (map :name (:artists release))))
          (println "Title:"   (:title release))
          (println "Year:"    (:year release))
          (println "Master:"  (if (:master? release) "Yes" "No"))
          (println "Page:"    (:uri release))
          (if (seq (:videos release))
            (do (println "Videos:")
                (doseq [[i video] (map-indexed vector (:videos release))]
                  (if (= i video-index)
                    (do (println "  -" (green (bold (:title video))))
                        (println "   " (green (bold (:uri video)))))
                    (do (println "  -" (:title video))
                        (println "   " (:uri video))))))
            (println "(No Videos)")))
      (println "[!] No release found!"))))

(map-indexed vector [:a :b :c])
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
            (System/exit 1))))

(defn clear []
  (print "\033[2J\033[H")
  (flush))

(defn read-loop! [state]
  (loop [state state]
    (let [input (read-char)]
      (cond
        (= input (int \q)) nil

        (or (= input (int \n))
            (= input (int \p)))
        (if (some? (:results state))
          (let [state' (cond (not (:started? state)) (fetch-current-release! state)
                             (= input (int \n))      (forward! state)
                             :else                   (back! state))
                state' (assoc state' :started? true)]
            (clear)
            (print-release state')
            (print-instructions)
            (recur state'))
          (do (println "[i] No more releases!")
              (recur state)))

        (= input (int \j))
        (if (:release state)
          (let [videos-length (dec (count (:videos (:release state))))
                video-index   (min videos-length (inc (:video-index state)))
                state'        (assoc state :video-index video-index)]
            (clear)
            (print-release state')
            (print-instructions)
            (recur state'))
          (recur state))

        (= input (int \k))
        (if (:release state)
          (let [video-index (max 0 (dec (:video-index state)))
                state'        (assoc state :video-index video-index)]
            (clear)
            (print-release state')
            (print-instructions)
            (recur state'))
          (recur state))

        (= input (int \newline))
        (let [release (:release state)
              video   (nth (:videos release) (:video-index state) nil)]
          (when video
            (browse-url (:uri video)))
          (recur state))

        :else (recur state)))))

(defn main! []
  (validate-state initial-state *command-line-args*)
  (clear)
  (print-init initial-state)
  (print "> Fetching initial results... ")
  (let [state (fetch-page! initial-state)]
    (println "Done!")
    (if (some? (:results state))
      (do (print-instructions)
          (read-loop! state))
      (println "[!] No initial results!"))))

;; Init

(main!)
