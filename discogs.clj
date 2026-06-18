#!/usr/bin/env bb

(require '[babashka.pods :as pods])

(pods/load-pod 'huahaiy/datalevin "0.10.16")

(require '[babashka.http-client :as http]
         '[cheshire.core :as json]
         '[clojure.string :as str]
         '[clojure.tools.cli :refer [parse-opts]]
         '[clojure.java.browse :refer [browse-url]]
         '[pod.huahaiy.datalevin :as d])

;; Processes

(defn mpv-exists? []
  (-> (ProcessBuilder. ["sh" "-c" "command -v mpv"])
      (.start)
      (.waitFor)
      (zero?)))

(def player* (atom nil))

;; Interact with tty

(defn stty [& args]
  (-> (ProcessBuilder. (into ["stty"] args))
      (.redirectInput (java.io.File. "/dev/tty"))
      (.start)
      (.waitFor)))

(stty "-icanon" "-echo")

(.addShutdownHook
 (Runtime/getRuntime)
 (Thread. (fn []
            (some-> @player* (.destroy))
            (stty "icanon" "echo"))))

(defn read-char []
  (let [tty (java.io.FileInputStream. "/dev/tty")
        ch (.read tty)]
    (.close tty)
    ch))

;; CLI Options

(def cli-options
  [["-h" "--help"          "Show help"]
   ["-g" "--genre GENRE"   "Genre"]
   ["-s" "--style STYLE"   "Style"]
   ["-y" "--year YEAR"     "Year"]
   ["-p" "--page PAGE"     "Starting page"  :parse-fn parse-long :default 1]
   ["-i" "--index INDEX"   "Starting index" :parse-fn parse-long :default 1]
   ["-d" "--database PATH" "Database path"]])

;; State

(def initial-state
  (let [opts (parse-opts *command-line-args* cli-options)
        {:keys [genre style year page index database]} (:options opts)]
    {:opts          opts
     :genre         genre
     :style         style
     :year          year
     :page          (if (< 0 page) page 1)
     :index         (if (< 0 index) (dec index) 0)
     :results       nil
     :resource      nil
     :video-index   0
     :playing-index nil
     :mpv-exists?   (mpv-exists?)
     :database      database}))

;; Database

; Schema

(def schema {;; Results
             :result/id           #:db{:valueType   :db.type/long
                                       :db/unique   :db.unique/identity
                                       :cardinality :db.cardinality/one}
             :result/genre        #:db{:valueType   :db.type/string
                                       :cardinality :db.cardinality/many}
             :result/style        #:db{:valueType   :db.type/string
                                       :cardinality :db.cardinality/many}
             :result/type         #:db{:valueType   :db.type/string
                                       :cardinality :db.cardinality/one}
             :result/master_url   #:db{:valueType   :db.type/string
                                       :cardinality :db.cardinality/one}
             :result/master_id    #:db{:valueType   :db.type/long
                                       :cardinality :db.cardinality/one}
             :result/resource_url #:db{:valueType   :db.type/string
                                       :cardinality :db.cardinality/one}
             :result/title        #:db{:valueType   :db.type/string
                                       :cardinality :db.cardinality/one}
             :result/label        #:db{:valueType   :db.type/string
                                       :cardinality :db.cardinality/many}
             :result/year         #:db{:valueType   :db.type/string
                                       :cardinality :db.cardinality/one}
             :result/country      #:db{:valueType   :db.type/string
                                       :cardinality :db.cardinality/one}
             :result/uri          #:db{:valueType   :db.type/string
                                       :cardinality :db.cardinality/one}
             :result/thumb        #:db{:valueType   :db.type/string
                                       :cardinality :db.cardinality/one}
             :result/catno        #:db{:valueType   :db.type/string
                                       :cardinality :db.cardinality/one}
             :result/format       #:db{:valueType   :db.type/string
                                       :cardinality :db.cardinality/many}
             :result/barcode      #:db{:valueType   :db.type/string
                                       :cardinality :db.cardinality/many}
             :result/cover_image  #:db{:valueType   :db.type/string
                                       :cardinality :db.cardinality/one}
             :result/resource     #:db{:valueType   :db.type/ref
                                       :cardinality :db.cardinality/one}
             ;; Resources
             :resource/id           #:db{:valueType   :db.type/long
                                         :db/unique   :db.unique/identity
                                         :cardinality :db.cardinality/one}
             :resource/title        #:db{:valueType   :db.type/string
                                         :cardinality :db.cardinality/one}
             :resource/artists      #:db{:valueType   :db.type/ref
                                         :cardinality :db.cardinality/many}
             :resource/year         #:db{:valueType   :db.type/long
                                         :cardinality :db.cardinality/one}
             :resource/uri          #:db{:valueType   :db.type/string
                                         :cardinality :db.cardinality/one}
             :resource/master?      #:db{:valueType   :db.type/boolean
                                         :cardinality :db.cardinality/one}
             :resource/videos       #:db{:valueType   :db.type/ref
                                         :cardinality :db.cardinality/many}
             :resource/resource_url #:db{:valueType   :db.type/string
                                         :cardinality :db.cardinality/one}
             ;; Artists
             :artist/id           #:db{:valueType   :db.type/long
                                       :db/unique    :db.unique/identity
                                       :cardinality :db.cardinality/one}
             :artist/name         #:db{:valueType   :db.type/string
                                       :cardinality :db.cardinality/one}
             :artist/resource_url #:db{:valueType   :db.type/string
                                       :cardinality :db.cardinality/one}
             ;; Videos
             :video/uri         #:db{:valueType   :db.type/string
                                     :db/unique   :db.unique/identity
                                     :cardinality :db.cardinality/one}
             :video/title       #:db{:valueType   :db.type/string
                                     :cardinality :db.cardinality/one}
             :video/description #:db{:valueType   :db.type/string
                                     :cardinality :db.cardinality/one}
             :video/duration    #:db{:valueType   :db.type/long
                                     :cardinality :db.cardinality/one}})

; Connection

(def conn
  (when-let [path (:database initial-state)]
    (d/get-conn path schema)))

; Transformers

(defn result->entity [result]
  (let [ks [:id :genre :style :type :master_url :resource_url
            :master_id :title :label :year :uri :thumb :catno :format
            :barcode :country :cover_image]
        m  (select-keys result ks)]
    (into {} (map (fn [[k v]] [(keyword "result" (name k)) v]) m))))

(defn video->entity [video]
  {:video/uri         (:uri video)
   :video/title       (:title video)
   :video/description (or (:description video) "")
   :video/duration    (:duration video)})

(defn artist->entity [artist]
  {:artist/id           (:id artist)
   :artist/name         (:name artist)
   :artist/resource_url (:resource_url artist)})

(defn resource->entity [resource]
  (let [ks [:id :title :artists :uri :master? :videos :resource_url :year]
        m  (-> resource
               (select-keys ks)
               (update :videos (partial map video->entity))
               (update :artists (partial map artist->entity)))]
    (into {} (map (fn [[k v]] [(keyword "resource" (name k)) v]) m))))

(defn entity->map [entity]
  (into {} (map (fn [[k v]] [(keyword (name k)) v]) (dissoc entity :db/id))))

(defn entity->resource [entity]
  (-> entity
      (entity->map)
      (update :artists (partial map entity->map))
      (update :videos  (partial map entity->map))))

; Queries

(defn ?resource-by-url [conn resource_url]
  (some-> (d/q '[:find (pull ?e [* {:resource/artists [*]
                                    :resource/videos [*]}])
                 :in $ ?resource_url
                 :where [?e :resource/id ?id]
                 [?e :resource/resource_url ?resource_url]]
               (d/db conn)
               resource_url)
          (ffirst)
          (entity->resource)
          (assoc :cached? true)))

;; Effects

(defn fetch-page! [{:keys [genre style year page] :as state}]
  (let [url     "https://api.discogs.com/database/search"
        params  {:genre genre
                 :style style
                 :year year
                 :page page
                 :type "master"}
        response (http/get url {:query-params params :throw false})]
    (if (= 200 (:status response))
      (let [body    (json/parse-string (:body response) true)
            results (when (< 0 (:items (:pagination body)))
                      (:results body))]
        (when conn
          (d/transact! conn (map result->entity results)))
        (assoc state :results results :response body))
      (assoc state :results nil))))

(defn -fetch-resource! [result k]
  (when-let [resp (some-> (get result k) (http/get {:throw false}))]
    (when (= 200 (:status resp))
      (assoc (json/parse-string (:body resp) true)
             :master? (= k :master_url)))))

(defn fetch-resource! [result]
  (or (-fetch-resource! result :master_url)
      (-fetch-resource! result :resource_url)))

(defn fetch-current-resource! [{:keys [index results] :as state}]
  (let [result (nth results index nil)]
    (if-let [cached (and conn result
                         (or (?resource-by-url conn (:master_url result))
                             (?resource-by-url conn (:resource_url result))))]
      (assoc state :resource cached :video-index 0)
      (let [resource (some-> result (fetch-resource!))]
        (when conn
          (d/transact! conn [(resource->entity resource)]))
        (assoc state :resource resource :video-index 0)))))

(defn forward! [{:keys [index page results] :as state}]
  (let [next-index (inc index)
        next-page? (>= next-index (count results))]
    (-> (if next-page?
          (fetch-page! (assoc state :page (inc page) :index 0))
          (assoc state :index next-index))
        (fetch-current-resource!))))

(defn back! [{:keys [index page] :as state}]
  (let [prev-index (dec index)]
    (cond
      (>= prev-index 0)
      (-> state
          (assoc :index prev-index)
          (fetch-current-resource!))

      (> page 1)
      (let [prev-state (-> state
                           (assoc :page (dec page))
                           (fetch-page!))]
        (if (some? (:results prev-state))
          (-> prev-state
              (assoc :index (dec (count (:results prev-state))))
              (fetch-current-resource!))
          (fetch-current-resource! state)))

      :else
      (fetch-current-resource! state))))

;; Display

; Text styles

(defn bold [s] (str "\033[1m" s "\033[0m"))

(defn red [s] (str "\033[31m" s "\033[0m"))

(defn green [s] (str "\033[32m" s "\033[0m"))

(defn yellow [s] (str "\033[33m" s "\033[0m"))

(defn blue [s] (str "\033[34m" s "\033[0m"))

; Printing

(defn clear []
  (print "\033[2J\033[H")
  (flush))

(defn print-error [s]
  (let [divider (apply str (repeat 56 \-))]
    (println divider)
    (println "" (red "[!]") "Error:" s)
    (println divider)))

(defn print-summary [state]
  (println "discogs.clj -- Scrape the Discogs API for release videos")
  (println (:summary (:opts state))))

(defn print-init [{:keys [page index genre style] :as _state}]
  (if style
    (println "Welcome! Querying Discogs for style"
             (green (str/capitalize style))
             "in genre"
             (green (str/capitalize genre)))
    (println "Welcome! Querying Discogs for genre"
             (str "{" (str/capitalize genre) "}")))
  (println (green ">") "Starting at page" (str page ", index") (inc index)))

(defn print-instructions [state]
  (println)
  (println (str (blue "[?]") " Press " (yellow "n") " for next release, " (yellow "p") " for previous, " (yellow "q") " to quit."))
  (println (str "    To navigate videos, press " (yellow "j") " to go down, " (yellow "k") " to go up."))
  (println (str "    Press " (yellow "Enter") " to open video link in the browser."))
  (when (:mpv-exists? state)
    (println (str "    Press " (yellow "Space") " to play/pause audio."))))

(defn print-video [{:keys [video-index playing-index]} index video]
  (let [uri-display (str (when (= index playing-index) "▶ ") (:uri video))
        dur-display (str "[" (int (/ (:duration video) 60)) "m"
                         (format "%02d" (mod (:duration video) 60)) "s" "]")]
    (if (= index video-index)
      (do (println " "   (green (bold (str "> " (:title video)))))
          (println "   " (green (bold uri-display)))
          (println "   " (green (bold dur-display))))
      (do (println "  -" (:title video))
          (println "   " uri-display)
          (println "   " dur-display)))))

(defn print-resource [{:keys [page index results resource] :as state}]
  (let [index-of (str "(" (inc index) " of " (count results) ")")]
    (println "-----------" "Page" page index-of "-----------")
    (if resource
      (do (println "Artists:" (str/join ", " (map :name (:artists resource))))
          (println "Title:"   (:title resource))
          (println "Year:"    (:year resource))
          (println "Master:"  (if (:master? resource) "Yes" "No"))
          (println "Page:"    (:uri resource))
          (println "Seen:"    (if (:cached? resource) (green "Yes") "No"))
          (if (seq (:videos resource))
            (do (println "Videos:")
                (doseq [[index video] (map-indexed vector (:videos resource))]
                  (print-video state index video)))
            (println "(No Videos)")))
      (println "[!] No resource found!"))))

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

(defn read-loop! [state]
  (loop [state state]
    (let [input (read-char)]
      (cond
        (= input (int \q)) nil

        (or (= input (int \n))
            (= input (int \p)))
        (if (some? (:results state))
          (let [state' (cond (not (:started? state)) (fetch-current-resource! state)
                             (= input (int \n))      (forward! state)
                             :else                   (back! state))
                state' (assoc state' :started? true :playing-index nil)]
            (when-let [p @player*]
              (.destroy p)
              (reset! player* nil))
            (clear)
            (print-resource state')
            (print-instructions state')
            (recur state'))
          (do (println "[i] No more resources!")
              (recur state)))

        (= input (int \j))
        (if (:resource state)
          (let [videos-length (dec (count (:videos (:resource state))))
                video-index   (min videos-length (inc (:video-index state)))
                state'        (assoc state :video-index video-index)]
            (clear)
            (print-resource state')
            (print-instructions state')
            (recur state'))
          (recur state))

        (= input (int \k))
        (if (:resource state)
          (let [video-index (max 0 (dec (:video-index state)))
                state'        (assoc state :video-index video-index)]
            (clear)
            (print-resource state')
            (print-instructions state')
            (recur state'))
          (recur state))

        (= input (int \newline))
        (let [resource (:resource state)
              video    (nth (:videos resource) (:video-index state) nil)]
          (when video
            (browse-url (:uri video)))
          (recur state))

        (= input (int \space))
        (let [video (nth (:videos (:resource state)) (:video-index state) nil)]
          (cond
            (not (:mpv-exists? state))
            (recur state)

            video
            (do (when-let [player @player*]
                  (.destroy player)
                  (reset! player* nil))
                (clear)
                (if (= (:video-index state) (:playing-index state))
                  (let [state' (assoc state :playing-index nil)]
                    (print-resource state')
                    (print-instructions state')
                    (recur state'))
                  (let [args   ["mpv" "--no-video" "--really-quiet" (:uri video)]
                        player (-> (ProcessBuilder. args) (.start))
                        state' (assoc state :playing-index (:video-index state))]
                    (reset! player* player)
                    (print-resource state')
                    (print-instructions state')
                    (recur state'))))

            :else (recur state)))

        :else (recur state)))))

(defn main! []
  (validate-state initial-state *command-line-args*)
  (clear)
  (print-init initial-state)
  (when-not (:mpv-exists? initial-state)
    (println (red "> mpv not found - audio playback disabled")))
  (print (green ">") "Fetching initial results... ")
  (let [state (fetch-page! initial-state)]
    (println "Done!")
    (println (green ">") "Press" (yellow "n") "to fetch the first result")
    (if (some? (:results state))
      (do (print-instructions initial-state)
          (read-loop! state))
      (println (red "[!]") "No initial results!"))))

;; Init

(main!)
