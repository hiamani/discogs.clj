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

(defn try-mpv! []
  (-> (ProcessBuilder. ["sh" "-c" "command -v mpv"])
      (.start)
      (.waitFor)
      (zero?)))

(def mpv-exists? (try-mpv!))

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

;; State -----------------------------------------------------------------------

;; CLI Options

(defn parse-page [v]
  (let [n (parse-long v)]
    (if (and n (< 0 n)) n 1)))

(defn parse-index [v]
  (let [n (parse-long v)]
    (if (and n (< 0 n)) (dec n) 0)))

(def cli-config
  [["-h" "--help"          "Show help"]
   ["-g" "--genre GENRE"   "Genre (required)"]
   ["-s" "--style STYLE"   "Style"]
   ["-y" "--year YEAR"     "Year"]
   ["-p" "--page PAGE"     "Starting page"  :parse-fn parse-page :default 1]
   ["-i" "--index INDEX"   "Starting index" :parse-fn parse-index :default 0]
   ["-d" "--database PATH" "Database path"]
   ["-r" "--resume"        "Resume session"]])

(def cli-opts
  (parse-opts *command-line-args* cli-config))

;; State

(def initial-state
  (let [{:keys [genre style year page index]} (:options cli-opts)]
    {:id      -1
     :params  {:genre genre
               :style style
               :year  year}
     :index   {:result  index
               :page    page
               :video   0
               :playing nil}
     :data    {:results  nil
               :resource nil}
     :view    {:key   :init
               :props {:status :fetching}}
     :effects {:browse-uri nil}}))

(def state* (atom initial-state))

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
  (when-let [path (:database (:options cli-opts))]
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

(defn state->progress [{:keys [index params] :as _state}]
  {:genre (:genre params)
   :style (:style params)
   :year  (:year params)
   :page  (:page index)
   :index (:result index)})

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

(defn ?progress-list [conn]
  (some->> (d/q '[:find (pull ?e [*])
                  :in $
                  :where [?e :progress/key]]
                (d/db conn))
           (map first)))

;; Transactions

(defn transact-results! [conn results]
  (d/transact! conn (map result->entity results)))

(defn transact-resource! [conn resource]
  (d/transact! conn [(resource->entity resource)]))

(defn transact-progress! [conn progress]
  (let [ent (progress->entity progress)]
    (d/transact! conn [(into {} (remove (comp nil? val)) ent)])))

;; Effects ---------------------------------------------------------------------

(defn fetch-page! [{:keys [params index] :as state}]
  (let [url      "https://api.discogs.com/database/search"
        q-params {:genre (:genre params)
                  :style (:style params)
                  :year  (:year params)
                  :page  (:page index)
                  :type  "master"}
        response  (http/get url {:query-params q-params :throw false})]
    (if (= 200 (:status response))
      (let [body    (json/parse-string (:body response) true)
            results (when (seq (:results body))
                      (:results body))]
        (some-> $conn (transact-results! results))
        (assoc-in state [:data :results] results))
      (assoc-in state [:data :results] nil))))

(defn fetch-resource! [result]
  (letfn [(fetch! [url master?]
            (when-let [resp (some-> url (http/get {:throw false}))]
              (when (= 200 (:status resp))
                (assoc (json/parse-string (:body resp) true) :master? master?))))]
    (or (fetch! (:master_url result) true)
        (fetch! (:resource_url result) false))))

(defn fetch-current-resource! [{:keys [data index] :as state}]
  (let [result (nth (:results data) (:result index) nil)]
    (if-let [cached (and $conn result
                         (or (?resource-by-url $conn (:master_url result))
                             (?resource-by-url $conn (:resource_url result))))]
      (-> state
          (assoc-in [:data :resource] cached)
          (assoc-in [:index :video] 0)
          (update :id inc))
      (let [resource (some-> result (fetch-resource!))]
        (some-> $conn (transact-resource! resource))
        (-> state
            (assoc-in [:data :resource] resource)
            (assoc-in [:index :video] 0)
            (update :id inc))))))

(defn forward! [{:keys [index data] :as state}]
  (let [next-index (inc (:result index))
        next-page? (>= next-index (count (:results data)))]
    (if next-page?
      (let [state' (-> state (update-in [:index :page] inc)
                       (assoc-in [:index :result] 0)
                       (fetch-page!))]
        (if (seq (:results (:data state')))
          (fetch-current-resource! state')
          state))
      (fetch-current-resource! (assoc-in state [:index :result] next-index)))))

(defn back! [{:keys [index] :as state}]
  (let [prev-index (dec (:result index))]
    (cond
      (>= prev-index 0)
      (-> state
          (assoc-in [:index :result] prev-index)
          (fetch-current-resource!))

      (> (:page index) 1)
      (let [prev-state (-> state (update-in [:index :page] dec) (fetch-page!))]
        (if (some? (:results (:data prev-state)))
          (-> prev-state
              (assoc-in [:index :result] (dec (count (:results (:data prev-state)))))
              (fetch-current-resource!))
          state))

      :else
      (fetch-current-resource! state))))

;; Display ---------------------------------------------------------------------

;; Text styles

(defn bold [s] (str "\033[1m" s "\033[0m"))

(defn red [s] (str "\033[31m" s "\033[0m"))

(defn green [s] (str "\033[32m" s "\033[0m"))

(defn yellow [s] (str "\033[33m" s "\033[0m"))

(defn blue [s] (str "\033[34m" s "\033[0m"))

;; Printing

(defn print-error [s]
  (let [divider (apply str (repeat 56 \-))]
    (println divider)
    (println "" (red "[!]") "Error:" s)
    (println divider)))

(defn print-summary [summary]
  (println "discogs.clj -- Scrape the Discogs API for release videos")
  (println summary))

;; Lines

(defn line [& parts]
  (str/join " " (map str parts)))

(defn welcome-lines [{:keys [params index]}]
  [(if (:style params)
     (line "Welcome! Querying Discogs for style"
           (green (str/capitalize (:style params)))
           "in genre"
           (green (str/capitalize (:genre params))))
     (line "Welcome! Querying Discogs for genre"
           (green (str/capitalize (:genre params)))))
   (line (green ">")
         "Starting at page"
         (str (:page index) ", index")
         (inc (:result index)))])

(defn instruction-lines [_state]
  (cond-> [""
           (line (blue "[?]") "Press" (yellow "n") "for next release," (yellow "p") "for previous," (yellow "q") "to quit.")
           (line "   " "To navigate videos, press" (yellow "j") "to go down," (yellow "k") "to go up.")
           (line "   " "Press" (yellow "Enter") "to open video link in the browser.")]
    mpv-exists?
    (conj (str "    Press " (yellow "Space") " to play/pause audio."))))

(defn index-of-lines [{:keys [index data] :as _state}]
  (let [index-of (str "(" (inc (:result index)) " of " (count (:results data)) ")")]
    [(line "-----------" "Page" (:page index) index-of "-----------")]))

(defn header-lines [{:keys [index data params] :as state}]
  (let [result   (nth (:results data) (:result index))
        resource (:resource data)]
    (cond-> (index-of-lines state)
      :always
      (into [(line "Artists:" (str/join ", " (map :name (:artists resource))))
             (line "Title:  " (:title resource))
             (line "Year:   " (:year resource))
             (line "Master: " (if (:master? resource) "Yes" "No"))
             (line "Page:   " (:uri resource))
             (line "Exact   "
                   (if (= #{(str/capitalize (:style params))} (set (:style result)))
                     "Yes" "No"))
             (line "Have:   " (:have (:community result)))
             (line "Want:   " (:want (:community result)))])
      $conn
      (conj (line "Seen:   " (if (:cached? resource) (green "Yes") "No"))))))

(defn video-lines [{:keys [index] :as _state} video-index video]
  (let [uri-display (str (when (= video-index (:playing index)) "▶ ") (:uri video))
        dur-display (str "[" (int (/ (:duration video) 60)) "m"
                         (format "%02d" (mod (:duration video) 60)) "s" "]")]
    (if (= video-index (:video index))
      [(line " "   (green (bold (str "> " (:title video)))))
       (line "   " (green (bold uri-display)))
       (line "   " (green (bold dur-display)))]
      [(line "  -" (:title video))
       (line "   " uri-display)
       (line "   " dur-display)])))

(defn resource-lines [{:keys [data index] :as state}]
  (let [resource (:resource data)]
    (if-not resource
      (conj (index-of-lines state) (line (red "[!] No resource found!")))
      (let [header (header-lines state)
            videos (vec (:videos resource))]
        (if-not (seq videos)
          (conj header (red "(No Videos)"))
          (let [reserved (+ (count header) (count (instruction-lines state)) 1)
                visible  (max 1 (quot (- (term-rows) reserved) 3))
                total    (count videos)
                v-index  (:video index)
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
                              (range start end))))))))))

(def init-statuses
  {:fetching (str (green ">") " Fetching initial results...")
   :done     (str (green ">") " Fetching initial results... Done!")
   :error    (red "[!] No initial results!")})

(defn init-lines [state]
  (let [status (-> state :view :props :status)]
    (cond-> (welcome-lines state)
      (not mpv-exists?)
      (conj (red "> mpv not found - audio playback disabled"))
      (some? status)   (conj (get init-statuses status))
      (= :done status) (into (instruction-lines state)))))

;; Handlers --------------------------------------------------------------------

(defn handle-n-p [{:keys [view data] :as state} input]
  (if (some? (:results data))
    (let [init? (= :init (:key view))
          next? (= input (int \n))
          state'    (cond init? (fetch-current-resource! state)
                          next? (forward! state)
                          :else (back! state))]
      (-> state'
          (assoc-in [:index :playing] nil)
          (assoc :view {:key :resources})
          (update :id inc)))
    (do (println "[i] No more resources!")
        state)))

(defn handle-j [{:keys [data index] :as state} _input]
  (if (:resource data)
    (let [videos-length (dec (count (:videos (:resource data))))
          video-index   (min videos-length (inc (:video index)))]
      (assoc-in state [:index :video] video-index))
    state))

(defn handle-k [{:keys [data index] :as state} _input]
  (if (:resource data)
    (let [video-index (max 0 (dec (:video index)))]
      (assoc-in state [:index :video] video-index))
    state))

(defn handle-enter [{:keys [data index] :as state} _input]
  (let [video (nth (:videos (:resource data)) (:video index) nil)]
    (when video
      (browse-url (:uri video)))
    state))

(defn handle-space [{:keys [data index] :as state} _input]
  (let [video (nth (:videos (:resource data)) (:video index) nil)
        no-mpv (not mpv-exists?)]
    (cond
      no-mpv state
      video  (if (= (:video index) (:playing index))
               (assoc-in state [:index :playing] nil)
               (assoc-in state [:index :playing] (:video index)))
      :else  state)))

(defn handle-input [state input]
  (let [n-or-p-input (or (= input (int \n)) (= input (int \p)))]
    (cond
      n-or-p-input             (handle-n-p state input)
      (= input (int \j))       (handle-j state input)
      (= input (int \k))       (handle-k state input)
      (= input (int \newline)) (handle-enter state input)
      (= input (int \space))   (handle-space state input)
      :else state)))

;; Watchers --------------------------------------------------------------------

;; Player

(def player* (atom nil))

(defn destroy-player! []
  (when-let [p @player*]
    (.destroy p)
    (reset! player* nil)))

(defn play-video! [uri]
  (let [args   ["mpv" "--no-video" "--really-quiet" uri]
        player (-> (ProcessBuilder. args) (.start))]
    (reset! player* player)))

(defn play! [_ _ prev {:keys [data index]}]
  (when (not= (:playing (:index prev)) (:playing index))
    (destroy-player!)
    (when-let [idx (:playing index)]
      (some-> data :resource :videos (nth idx nil) :uri (play-video!)))))

;; Progress transactor

(defn save-progress! [_ _ prev next]
  (when (and (not= (:id prev) (:id next)) (< 0 (:id next)))
    (some-> $conn (transact-progress! (state->progress next)))))

;; Renderer

(defn render! [_ _ _ {:keys [view] :as state}]
  (let [lines (case (:key view)
                :init      (init-lines state)
                :resources (into (resource-lines state)
                                 (instruction-lines state)))
        frame (str "\033[H" (str/join "\033[K\n" lines) "\033[K\033[J")]
    (print frame)
    (flush)))

;; Initialization --------------------------------------------------------------

(defn check-args! [{:keys [options summary]}]
  (cond (or (empty? *command-line-args*)
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

(defn resolve-state! []
  (let [progress (when (:resume (:options cli-opts))
                   (some-> $conn
                           (?progress-by-key (state->progress initial-state))
                           (entity->map)))]
    (if (some? progress)
      (-> initial-state
          (assoc-in [:index :page]   (:page progress))
          (assoc-in [:index :result] (:index progress)))
      initial-state)))

(defn read-loop! []
  (loop []
    (let [input (read-char)]
      (when-not (= input (int \q))
        (let [state  @state*
              state' (handle-input state input)]
          (when-not (identical? state state')
            (reset! state* state'))
          (recur))))))

;; Process ---------------------------------------------------------------------

;; Configure tty

(stty ["-icanon" "-echo"] {:read? false})

;; Shutdown Hook

(.addShutdownHook
 (Runtime/getRuntime)
 (Thread. (fn []
            (some-> @player* (.destroy))
            (stty ["icanon" "echo"] {:read? false}))))

;; Main

(defn mount! []
  (add-watch state* ::render render!)
  (add-watch state* ::play play!)
  (add-watch state* ::save-progress save-progress!)
  (reset! state* (resolve-state!)))

(defn main! []
  (check-args! cli-opts)
  (mount!)
  (let [state (fetch-page! @state*)
        ok?   (some? (:results (:data state)))
        view  {:key :init :props {:status (if ok? :done :error)}}]
    (reset! state* (assoc state :view view))
    (when ok?
      (read-loop!))))

;; Run

(main!)
