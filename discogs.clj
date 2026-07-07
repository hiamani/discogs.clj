#!/usr/bin/env bb

(require '[babashka.pods :as pods])

(pods/load-pod 'huahaiy/datalevin "0.10.16")

(require '[babashka.http-client :as http]
         '[cheshire.core :as json]
         '[clojure.string :as str]
         '[clojure.tools.cli :refer [parse-opts]]
         '[clojure.java.browse :refer [browse-url]]
         '[pod.huahaiy.datalevin :as d])

;; Processes -------------------------------------------------------------------

(defn mpv-exists? []
  (-> (ProcessBuilder. ["sh" "-c" "command -v mpv"])
      (.start)
      (.waitFor)
      (zero?)))

(def player* (atom nil))

;; tty helpers

(defn stty [args {:keys [read?]}]
  (let [proc (-> (ProcessBuilder. (into ["stty"] args))
                 (.redirectInput (java.io.File. "/dev/tty"))
                 (.start))
        out  (when read? (slurp (.getInputStream proc)))]
    (.waitFor proc)
    out))

(defn term-rows []
  (some-> (stty ["size"] {:read? true})
          (str/trim)
          (str/split #"\s+")
          (first)
          (parse-long)))

(defn read-char []
  (let [tty (java.io.FileInputStream. "/dev/tty")
        ch  (.read tty)]
    (.close tty)
    ch))

;; Configure tty

(stty ["-icanon" "-echo"] {:read? false})

(.addShutdownHook
 (Runtime/getRuntime)
 (Thread. (fn []
            (some-> @player* (.destroy))
            (stty ["icanon" "echo"] {:read? false}))))

;; State -----------------------------------------------------------------------

;; CLI Options

(def cli-options
  [["-h" "--help"          "Show help"]
   ["-g" "--genre GENRE"   "Genre (required)"]
   ["-s" "--style STYLE"   "Style"]
   ["-y" "--year YEAR"     "Year"]
   ["-p" "--page PAGE"     "Starting page"  :parse-fn parse-long]
   ["-i" "--index INDEX"   "Starting index" :parse-fn parse-long]
   ["-d" "--database PATH" "Database path"]
   ["-r" "--resume"        "Resume session"]])

;; Initial State

(def initial-state
  (let [opts (parse-opts *command-line-args* cli-options)
        {:keys [genre style year page index database resume]} (:options opts)]
    {:opts          opts
     :genre         genre
     :style         style
     :year          year
     :page          (if (and page (< 0 page)) page 1)
     :index         (if (and index (< 0 index)) (dec index) 0)
     :results       nil
     :resource      nil
     :video-index   0
     :playing-index nil
     :mpv-exists?   (mpv-exists?)
     :database      database
     :resume        resume}))

;; Database --------------------------------------------------------------------

;; Schema

(def result-schema
  {:result/id           #:db{:valueType   :db.type/long
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
                             :cardinality :db.cardinality/one}})
(def resource-schema
  {:resource/id           #:db{:valueType   :db.type/long
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
                               :cardinality :db.cardinality/one}})
(def artist-schema
  {:artist/id           #:db{:valueType   :db.type/long
                             :db/unique    :db.unique/identity
                             :cardinality :db.cardinality/one}
   :artist/name         #:db{:valueType   :db.type/string
                             :cardinality :db.cardinality/one}
   :artist/resource_url #:db{:valueType   :db.type/string
                             :cardinality :db.cardinality/one}})
(def video-schema
  {:video/uri         #:db{:valueType   :db.type/string
                           :db/unique   :db.unique/identity
                           :cardinality :db.cardinality/one}
   :video/title       #:db{:valueType   :db.type/string
                           :cardinality :db.cardinality/one}
   :video/description #:db{:valueType   :db.type/string
                           :cardinality :db.cardinality/one}
   :video/duration    #:db{:valueType   :db.type/long
                           :cardinality :db.cardinality/one}})

(def progress-schema
  {:progress/key    #:db{:valueType   :db.type/string
                         :db/unique   :db.unique/identity
                         :cardinality :db.cardinality/one}
   :progress/genre  #:db{:valueType   :db.type/string
                         :cardinality :db.cardinality/one}
   :progress/style  #:db{:valueType   :db.type/string
                         :cardinality :db.cardinality/one}
   :progress/year   #:db{:valueType   :db.type/string
                         :cardinality :db.cardinality/one}
   :progress/page   #:db{:valueType   :db.type/long
                         :cardinality :db.cardinality/one}
   :progress/index  #:db{:valueType   :db.type/long
                         :cardinality :db.cardinality/one}})

(def schema
  (merge result-schema
         resource-schema
         artist-schema
         video-schema
         progress-schema))

;; Connection

(def $conn
  (when-let [path (:database initial-state)]
    (d/get-conn path schema)))

;; Transformers

(defn map->entity [m ns']
  (update-keys m #(keyword (name ns') (name %))))

(defn entity->map [ent]
  (update-keys (dissoc ent :db/id) (comp keyword name)))

(defn result->entity [result]
  (let [ks [:id :genre :style :type :master_url :resource_url
            :master_id :title :label :year :uri :thumb :catno :format
            :barcode :country :cover_image]]
    (-> result (select-keys ks) (map->entity :result))))

(defn video->entity [video]
  (-> video
      (select-keys [:uri :title :description :duration])
      (update :description #(or % ""))
      (map->entity :video)))

(defn artist->entity [artist]
  (-> artist
      (select-keys [:id :name :resource_url])
      (map->entity :artist)))

(defn resource->entity [resource]
  (-> resource
      (select-keys [:id :title :artists :uri :master? :videos :resource_url :year])
      (update :videos (partial map video->entity))
      (update :artists (partial map artist->entity))
      (map->entity :resource)))

(defn progress-key [progress]
  (str/join "|" (map progress [:genre :style :year])))

(defn progress->entity [progress]
  (-> progress
      (select-keys [:genre :style :year :page :index])
      (assoc :key (progress-key progress))
      (map->entity :progress)))

(defn entity->resource [entity]
  (-> entity
      (entity->map)
      (update :artists (partial map entity->map))
      (update :videos  (partial map entity->map))))

;; Queries

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

(defn ?progress-by-key [conn progress]
  (some-> (d/q '[:find (pull ?e [*])
                 :in $ ?key
                 :where [?e :progress/key ?key]]
               (d/db conn)
               (progress-key progress))
          (ffirst)))

; Transactions

(defn transact-results! [conn results]
  (d/transact! conn (map result->entity results)))

(defn transact-resource! [conn resource]
  (d/transact! conn [(resource->entity resource)]))

(defn transact-progress! [conn progress]
  (let [ent (progress->entity progress)]
    (d/transact! conn [(into {} (remove (comp nil? val)) ent)])))

;; Effects ---------------------------------------------------------------------

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
            results (when (seq (:results body))
                      (:results body))]
        (some-> $conn (transact-results! results))
        (assoc state
               :results results
               :response body))
      (assoc state :results nil))))

(defn fetch-resource! [result]
  (letfn [(fetch! [url master?]
            (when-let [resp (some-> url (http/get {:throw false}))]
              (when (= 200 (:status resp))
                (assoc (json/parse-string (:body resp) true) :master? master?))))]
    (or (fetch! (:master_url result) true)
        (fetch! (:resource_url result) false))))

(defn fetch-current-resource! [{:keys [index results] :as state}]
  (let [result (nth results index nil)]
    (if-let [cached (and $conn result
                         (or (?resource-by-url $conn (:master_url result))
                             (?resource-by-url $conn (:resource_url result))))]
      (assoc state :resource cached :video-index 0)
      (let [resource (some-> result (fetch-resource!))]
        (some-> $conn (transact-resource! resource))
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
      (let [prev-state (-> state (assoc :page (dec page)) (fetch-page!))]
        (if (some? (:results prev-state))
          (-> prev-state
              (assoc :index (dec (count (:results prev-state))))
              (fetch-current-resource!))
          state))

      :else
      (fetch-current-resource! state))))

;; Display ---------------------------------------------------------------------

; Text styles

(defn bold [s] (str "\033[1m" s "\033[0m"))

(defn red [s] (str "\033[31m" s "\033[0m"))

(defn green [s] (str "\033[32m" s "\033[0m"))

(defn yellow [s] (str "\033[33m" s "\033[0m"))

(defn blue [s] (str "\033[34m" s "\033[0m"))

; Printing

(defn print-error [s]
  (let [divider (apply str (repeat 56 \-))]
    (println divider)
    (println "" (red "[!]") "Error:" s)
    (println divider)))

(defn print-summary [summary]
  (println "discogs.clj -- Scrape the Discogs API for release videos")
  (println summary))

; Rendering

(defn line [& parts]
  (str/join " " (map str parts)))

(defn init-lines [{:keys [page index genre style] :as _state}]
  [(if style
     (line "Welcome! Querying Discogs for style" (green (str/capitalize style))
           "in genre" (green (str/capitalize genre)))
     (line "Welcome! Querying Discogs for genre" (str "{" (str/capitalize genre) "}")))
   (line (green ">") "Starting at page" (str page ", index") (inc index))])

(defn instruction-lines [state]
  (cond-> [""
           (line (blue "[?]") "Press" (yellow "n") "for next release," (yellow "p") "for previous," (yellow "q") "to quit.")
           (line "   " "To navigate videos, press" (yellow "j") "to go down," (yellow "k") "to go up.")
           (line "   " "Press" (yellow "Enter") "to open video link in the browser.")]
    (:mpv-exists? state)
    (conj (str "    Press " (yellow "Space") " to play/pause audio."))))

(defn video-lines [{:keys [video-index playing-index]} index video]
  (let [uri-display (str (when (= index playing-index) "▶ ") (:uri video))
        dur-display (str "[" (int (/ (:duration video) 60)) "m"
                         (format "%02d" (mod (:duration video) 60)) "s" "]")]
    (if (= index video-index)
      [(line " "   (green (bold (str "> " (:title video)))))
       (line "   " (green (bold uri-display)))
       (line "   " (green (bold dur-display)))]
      [(line "  -" (:title video))
       (line "   " uri-display)
       (line "   " dur-display)])))

(defn index-of-lines [{:keys [page index results] :as _state}]
  (let [index-of (str "(" (inc index) " of " (count results) ")")]
    [(line "-----------" "Page" page index-of "-----------")]))

(defn header-lines [{:keys [index results resource style] :as state}]
  (let [result (nth results index)]
    (cond-> (index-of-lines state)
      :always
      (into [(line "Artists:" (str/join ", " (map :name (:artists resource))))
             (line "Title:  " (:title resource))
             (line "Year:   " (:year resource))
             (line "Master: " (if (:master? resource) "Yes" "No"))
             (line "Page:   " (:uri resource))
             (line "Exact   "
                   (if (= #{(str/capitalize style)} (set (:style result)))
                     "Yes" "No"))
             (line "Have:   " (:have (:community result)))
             (line "Want:   " (:want (:community result)))])
      $conn
      (conj (line "Seen:   " (if (:cached? resource) (green "Yes") "No"))))))

(defn resource-lines [{:keys [resource] :as state}]
  (if-not resource
    (conj (index-of-lines state) (line (red "[!] No resource found!")))
    (let [header (header-lines state)
          videos (vec (:videos resource))]
      (if-not (seq videos)
        (conj header (red "(No Videos)"))
        (let [reserved (+ (count header) (count (instruction-lines state)) 1)
              visible  (max 1 (quot (- (term-rows) reserved) 3))
              total    (count videos)
              v-index  (:video-index state)
              half     (quot visible 2)
              start    (-> (- v-index half)
                           (max 0)
                           (min (max 0 (- total visible))))
              end      (min total (+ start visible))]
          (-> header
              (conj (line "Videos:"
                          (when (> total visible)
                            (str "(" (inc start) "-" end " of " total ")"))))
              (into (mapcat #(video-lines state % (nth videos %))
                            (range start end)))))))))

(def init-statuses
  {:fetching (str (green ">") " Fetching initial results...")
   :done     (str (green ">") " Fetching initial results... Done!")
   :error    (red "[!] No initial results!")})

(defn render-init! [state {:keys [status]}]
  (let [lines (cond-> (init-lines state)
                (not (:mpv-exists? state))
                (conj (red "> mpv not found - audio playback disabled"))
                (some? status)   (conj (get init-statuses status))
                (= :done status) (into (instruction-lines state)))
        frame (str "\033[H" (str/join "\033[K\n" lines) "\033[K\033[J")]
    (print frame)
    (flush)))

(defn render! [state]
  (let [lines (into (resource-lines state) (instruction-lines state))
        frame (str "\033[H" (str/join "\033[K\n" lines) "\033[K\033[J")]
    (print frame)
    (flush)))

;; Handlers --------------------------------------------------------------------

(defn handle-n-p [state input]
  (if (some? (:results state))
    (let [state'   (cond (not (:started? state)) (fetch-current-resource! state)
                         (= input (int \n))      (forward! state)
                         :else                   (back! state))
          state'   (assoc state' :started? true :playing-index nil)
          progress (select-keys state' [:genre :style :year :page :index])]
      (some-> $conn (transact-progress! progress))
      (when-let [p @player*]
        (.destroy p)
        (reset! player* nil))
      (render! state')
      state')
    (do (println "[i] No more resources!")
        state)))

(defn handle-j [state _input]
  (if (:resource state)
    (let [videos-length (dec (count (:videos (:resource state))))
          video-index   (min videos-length (inc (:video-index state)))
          state'        (assoc state :video-index video-index)]
      (render! state')
      state')
    state))

(defn handle-k [state _input]
  (if (:resource state)
    (let [video-index (max 0 (dec (:video-index state)))
          state'      (assoc state :video-index video-index)]
      (render! state')
      state')
    state))

(defn handle-enter [state _input]
  (let [resource (:resource state)
        video    (nth (:videos resource) (:video-index state) nil)]
    (when video
      (browse-url (:uri video)))
    state))

(defn handle-space [state _input]
  (let [video (nth (:videos (:resource state)) (:video-index state) nil)]
    (cond
      (not (:mpv-exists? state)) state

      video
      (do (when-let [player @player*]
            (.destroy player)
            (reset! player* nil))
          (if (= (:video-index state) (:playing-index state))
            (let [state' (assoc state :playing-index nil)]
              (render! state')
              state')
            (let [args   ["mpv" "--no-video" "--really-quiet" (:uri video)]
                  player (-> (ProcessBuilder. args) (.start))
                  state' (assoc state :playing-index (:video-index state))]
              (reset! player* player)
              (render! state')
              state')))

      :else state)))

;; Main ------------------------------------------------------------------------

(defn check-args! [{:keys [options summary]}]
  (cond (or (every? (comp nil? val) options)
            (:help options))
        (do (print-summary summary)
            (System/exit 0))

        (:errors options)
        (do (run! print-error (:errors options))
            (print-summary summary)
            (System/exit 1))

        (nil? (:genre options))
        (do (print-error "Please specify a genre")
            (print-summary summary)
            (System/exit 1))))

(defn read-loop! [state]
  (loop [state state]
    (let [input (read-char)]
      (cond
        (= input (int \q)) nil

        (or (= input (int \n)) (= input (int \p)))
        (recur (handle-n-p state input))

        (= input (int \j))
        (recur (handle-j state input))

        (= input (int \k))
        (recur (handle-k state input))

        (= input (int \newline))
        (recur (handle-enter state input))

        (= input (int \space))
        (recur (handle-space state input))

        :else (recur state)))))

(defn resolve-state []
  (let [progress (when (:resume initial-state)
                   (some-> $conn (?progress-by-key initial-state)))]
    (cond-> initial-state
      (some? progress)
      (merge (select-keys (entity->map progress) [:page :index])))))

(defn main! []
  (check-args! (:opts initial-state))
  (let [state (resolve-state)]
    (render-init! state {:status :fetching})
    (let [state' (fetch-page! state)]
      (if (some? (:results state'))
        (do (render-init! state' {:status :done})
            (read-loop! state'))
        (do (render-init! state' {:status :error})
            (println))))))

;; Init

(main!)
