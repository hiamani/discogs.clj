#!/usr/bin/env bb

(require '[babashka.pods :as pods])

(pods/load-pod 'huahaiy/datalevin "0.10.16")

(require '[babashka.fs :as fs]
         '[babashka.http-client :as http]
         '[cheshire.core :as json]
         '[clojure.string :as str]
         '[clojure.tools.cli :refer [parse-opts]]
         '[clojure.java.browse :refer [browse-url]]
         '[pod.huahaiy.datalevin :as d])

(import '[java.time Instant Duration])

;; Common ----------------------------------------------------------------------

;; Text Styles

(defn bold [s] (str "\033[1m" s "\033[0m"))

(defn red [s] (str "\033[31m" s "\033[0m"))

(defn green [s] (str "\033[32m" s "\033[0m"))

(defn yellow [s] (str "\033[33m" s "\033[0m"))

(defn blue [s] (str "\033[34m" s "\033[0m"))

(defn print-error [s]
  (println (red "[!]") "Error:" s))

(defn print-summary [summary]
  (println "discogs.clj -- Scrape the Discogs API for release videos")
  (println summary))

;; CLI Options -----------------------------------------------------------------

;; Validation

(defn validate-args! [{:keys [options summary errors]}]
  (cond
    (or (empty? *command-line-args*) (:help options))
    (do (print-summary summary)
        (System/exit 0))

    errors
    (do (run! print-error errors)
        (print-summary summary)
        (System/exit 1))

    (every? nil? (select-keys options [:genre :list :resume]))
    (do (print-error "Please specify a genre")
        (print-summary summary)
        (System/exit 1))

    (and (:list options) (:no-database options))
    (do (print-error "Cannot list sessions without database")
        (print-summary summary)
        (System/exit 1))

    (and (:resume options) (:no-database options))
    (do (print-error "Cannot resume without database")
        (print-summary summary)
        (System/exit 1))))

;; Parsing

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
   ["-r" "--resume"        "Resume last session"]
   ["-l" "--list"          "List and resume sessions"]
   ["-d" "--database PATH" "Database path"]
   ["-x" "--no-database"   "Skip database connection"]])

(def cli-opts
  (parse-opts *command-line-args* cli-config))

(validate-args! cli-opts)

;; Processes -------------------------------------------------------------------

(defn try-mpv! []
  (-> (ProcessBuilder. ["sh" "-c" "command -v mpv"])
      (.start)
      (.waitFor)
      (zero?)))

(def mpv-exists? (try-mpv!))

;; tty Helpers

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

(def initial-state
  (let [{:keys [genre style year page index]} (:options cli-opts)]
    {:id      0
     :params  {:genre genre
               :style style
               :year  year}
     :index   {:result  index
               :page    page
               :video   0
               :session 0}
     :data    {:results  nil
               :resource nil
               :sessions nil}
     :view    {:key   :view/init
               :props {:status :fetching}}
     :entry   :entry/default
     :actions {:play-video   nil
               :browse-uri   nil
               :browse-id    0
               :system/exit! false}
     :issues  {:invalid-videos #{}}}))

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

(def meta-schema
  {:meta/created-at #:db{:valueType :db.type/instant}})

(def schema
  (merge result-schema
         resource-schema
         artist-schema
         video-schema
         progress-schema
         meta-schema))

;; Connection

(defn default-db-path []
  (let [home     (System/getProperty "user.home")
        xdg-data (System/getenv "XDG_DATA_HOME")]
    (if (not-empty xdg-data)
      (str (fs/path xdg-data "discogs-clj" "db"))
      (str (fs/path home ".local" "share" "discogs-clj" "db")))))

(def db-path
  (or (:database (:options cli-opts))
      (default-db-path)))

(def $conn
  (when-not (:no-database (:options cli-opts))
    (.mkdirs (java.io.File. db-path))
    (d/get-conn db-path schema)))

;; Transformers

(defn strip-nils [m]
  (into {} (remove (comp nil? val)) m))

(defn map->entity [m ns']
  (-> m
      (strip-nils)
      (update-keys #(keyword (name ns') (name %)))))

(defn entity->map [ent]
  (-> ent
      (dissoc :db/id)
      (update-keys (comp keyword name))))

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
      (select-keys [:id :title :artists :uri :master? :videos
                    :resource_url :year])
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
      (map->entity :progress)
      (assoc :meta/created-at (java.util.Date.))))

(defn entity->resource [entity]
  (-> entity
      (entity->map)
      (update :artists (partial mapv entity->map))
      (update :videos  (partial mapv entity->map))))

;; Queries

(defn ?resource-by-url [db resource_url]
  (some-> (d/q '[:find (pull ?e [* {:resource/artists [*]
                                    :resource/videos [*]}]) .
                 :in $ ?resource_url
                 :where [?e :resource/id ?id]
                 [?e :resource/resource_url ?resource_url]]
               db
               resource_url)
          (entity->resource)
          (assoc :cached? true)))

;; -- May be used at some point!
;;
;; (defn ?progress-by-key [db progress]
;;   (some-> (d/q '[:find (pull ?e [*]) .
;;                  :in $ ?key
;;                  :where [?e :progress/key ?key]]
;;                db
;;                (progress-key progress))
;;           (entity->map)))

(defn ?progress-list [db]
  (some->> (d/q '[:find [(pull ?e [*]) ...]
                  :in $
                  :where [?e :progress/key]]
                db)
           (sort-by :meta/created-at #(compare %2 %1))
           (map entity->map)))

(defn ?last-progress-inst [db]
  (d/q '[:find (max ?t) .
         :where [?e :progress/key]
         [?e :meta/created-at ?t]]
       db))

(defn ?last-progress [db]
  (let [t (?last-progress-inst db)]
    (some-> (d/q '[:find (pull ?e [*]) .
                   :in $ ?t
                   :where [?e :meta/created-at ?t]
                   [?e :progress/key]]
                 db t)
            (entity->map))))

;; Transactions

(defn transact-results! [conn results]
  (d/transact! conn (map result->entity results)))

(defn transact-resource! [conn resource]
  (d/transact! conn [(resource->entity resource)]))

(defn transact-progress! [conn progress]
  (d/transact! conn [(progress->entity progress)]))

;; Helpers ---------------------------------------------------------------------

(defn current-result [{:keys [data index] :as _state}]
  (nth (:results data) (:result index) nil))

(defn video-by-index [{:keys [data] :as _state} video-index]
  (some-> data :resource :videos (nth video-index nil)))

(defn current-video [{:keys [index] :as state}]
  (video-by-index state (:video index)))

(defn merge-progress [state progress]
  (-> state
      (assoc-in [:index  :page]   (:page progress))
      (assoc-in [:index  :result] (:index progress))
      (assoc-in [:params :genre]  (:genre progress))
      (assoc-in [:params :style]  (:style progress))
      (assoc-in [:params :year]   (:year progress))))

(defn relative-time [date]
  (let [then (.toInstant date)
        secs (.getSeconds (Duration/between then (Instant/now)))
        mins (quot secs 60)
        hrs  (quot mins 60)
        days (quot hrs 24)]
    (cond
      (< secs 60)  "just now"
      (< mins 60)  (str mins "m ago")
      (< hrs 24)   (str hrs "h ago")
      (< days 7)   (str days "d ago")
      (< days 30)  (str (quot days 7) "w ago")
      (< days 365) (str (quot days 30) "mo ago")
      :else        (str (quot days 365) "y ago"))))

;; Effects ---------------------------------------------------------------------

;; API Calls

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
            results (not-empty (:results body))]
        (some-> $conn (transact-results! results))
        (assoc-in state [:data :results] results))
      (assoc-in state [:data :results] nil))))

(defn fetch-resource! [{:keys [master_url resource_url] :as _result}]
  (letfn [(fetch! [url]
            (when-let [resp (some-> url (http/get {:throw false}))]
              (when (= 200 (:status resp))
                (json/parse-string (:body resp) true))))]
    (or (some-> (fetch! master_url) (assoc :master? true))
        (some-> (fetch! resource_url) (assoc :master? false)))))

;; Cache Helper

(defn find-cached-result! [result]
  (when (and $conn result)
    (let [db (d/db $conn)]
      (or (?resource-by-url db (:master_url result))
          (?resource-by-url db (:resource_url result))))))

;; Resource Helpers

(defn fetch-current-resource! [state]
  (let [result (current-result state)]
    (or (find-cached-result! result)
        (when-let [resource (some-> result (fetch-resource!))]
          (some-> $conn (transact-resource! resource))
          resource))))

(defn set-resource [state resource]
  (-> state
      (assoc-in [:data :resource] resource)
      (assoc-in [:index :video] 0)
      (update :id inc)))

;; FX

(defn forward! [{:keys [index data] :as state}]
  (let [next-index (inc (:result index))
        next-page? (>= next-index (count (:results data)))]
    (if next-page?
      (let [state' (-> state
                       (update-in [:index :page] inc)
                       (assoc-in [:index :result] 0)
                       (fetch-page!))]
        (if (seq (:results (:data state')))
          (set-resource state' (fetch-current-resource! state'))
          state))
      (let [state' (assoc-in state [:index :result] next-index)]
        (set-resource state' (fetch-current-resource! state'))))))

(defn back! [{:keys [index] :as state}]
  (let [prev-index (dec (:result index))]
    (cond
      (>= prev-index 0)
      (let [state' (assoc-in state [:index :result] prev-index)]
        (set-resource state' (fetch-current-resource! state')))

      (> (:page index) 1)
      (let [state' (fetch-page! (update-in state [:index :page] dec))]
        (if-let [results (not-empty (:results (:data state')))]
          (let [index   (dec (count results))
                state'' (assoc-in state' [:index :result] index)]
            (set-resource state'' (fetch-current-resource! state'')))
          state))

      :else state)))

(defn load! [state]
  (let [state' (fetch-page! state)]
    (if (seq (:results (:data state')))
      (-> state'
          (set-resource (fetch-current-resource! state'))
          (assoc :view {:key :view/resources}))
      (-> state'
          (assoc :view {:key :view/init :props {:status :error}})
          (assoc-in [:actions :system/exit!] true)))))

(defn init! [state]
  (let [state' (fetch-page! state)
        ok?    (seq (:results (:data state')))
        status (if ok? :done :error)]
    (-> state'
        (assoc :view {:key :view/init :props {:status status}})
        (assoc-in [:actions :system/exit!] (not ok?)))))

;; Setup

(defn run-effect! [state [effect callback]]
  (cond-> (case effect
            :fx/init    (init! state)
            :fx/load    (load! state)
            :fx/refetch (set-resource state (fetch-current-resource! state))
            :fx/forward (forward! state)
            :fx/back    (back! state))
    callback (callback)))

;; Handlers --------------------------------------------------------------------

;; Helpers

(defn n-or-p-input [input]
  (or (= input (int \n)) (= input (int \p))))

;; General

(defn fetch-callback [state]
  (-> state
      (assoc-in [:actions :play-video] nil)
      (assoc :view {:key :view/resources})))

(defn handle-nav-resource [{:keys [view data] :as state} input]
  (if (some? (:results data))
    (let [init?  (= :view/init (:key view))
          next?  (= input (int \n))]
      [state (cond init? [:fx/refetch fetch-callback]
                   next? [:fx/forward fetch-callback]
                   :else [:fx/back    fetch-callback])])
    [state nil]))

;; Resources View

(defn handle-resources-j [{:keys [data index] :as state} _input]
  (if (:resource data)
    (let [videos-length (dec (count (:videos (:resource data))))
          video-index   (min videos-length (inc (:video index)))]
      [(assoc-in state [:index :video] video-index) nil])
    [state nil]))

(defn handle-resources-k [{:keys [data index] :as state} _input]
  (if (:resource data)
    (let [video-index (max 0 (dec (:video index)))]
      [(assoc-in state [:index :video] video-index) nil])
    [state nil]))

(defn handle-resources-enter [state _input]
  (let [uri (some-> (current-video state) :uri)]
    [(-> state
         (assoc-in [:actions :browse-uri] uri)
         (update-in [:actions :browse-id] inc))
     nil]))

(defn handle-resources-space [{:keys [index actions] :as state} _input]
  (let [video  (current-video state)
        no-mpv (not mpv-exists?)]
    (cond
      no-mpv [state nil]
      video  [(if (= (:video index) (:play-video actions))
                (assoc-in state [:actions :play-video] nil)
                (assoc-in state [:actions :play-video] (:video index)))
              nil]
      :else  [state nil])))

(defn handle-resources-input [state input]
  (cond
    (n-or-p-input input)     (handle-nav-resource state input)
    (= input (int \j))       (handle-resources-j state input)
    (= input (int \k))       (handle-resources-k state input)
    (= input (int \newline)) (handle-resources-enter state input)
    (= input (int \space))   (handle-resources-space state input)
    :else                    [state nil]))

;; Init View

(defn handle-init-input [state input]
  (cond
    (n-or-p-input input) (handle-nav-resource state input)
    :else                [state nil]))

;; Sessions View

(defn handle-sessions-j [{:keys [data index] :as state} _input]
  (let [sessions-length (dec (count (:sessions data)))
        session-index   (min sessions-length (inc (:session index)))]
    [(assoc-in state [:index :session] session-index) nil]))

(defn handle-sessions-k [{:keys [index] :as state} _input]
  (let [session-index (max 0 (dec (:session index)))]
    [(assoc-in state [:index :session] session-index) nil]))

(defn handle-sessions-enter [{:keys [index data] :as state} _input]
  (let [session (nth (:sessions data) (:session index))]
    (-> state
        (merge-progress session)
        (assoc :view {:key :view/init :props {:status :fetching}})
        (vector [:fx/load]))))

(defn handle-sessions-input [state input]
  (cond
    (= input (int \j))       (handle-sessions-j state input)
    (= input (int \k))       (handle-sessions-k state input)
    (= input (int \newline)) (handle-sessions-enter state input)
    :else                    [state nil]))

;; Main

(defn handle-input [state input]
  (case (:key (:view state))
    :view/sessions  (handle-sessions-input state input)
    :view/init      (handle-init-input state input)
    :view/resources (handle-resources-input state input)
    [state nil]))

;; Display ---------------------------------------------------------------------

;; Helpers

(defn line [& parts]
  (str/join " " (map str parts)))

(defn list-item-range [total capacity selected]
  (let [half   (quot capacity 2)
        center (- selected half)
        clamp  (max 0 center)
        start  (min clamp (max 0 (- total capacity)))
        end    (min total (+ start capacity))]
    [start end]))

(defn list-capacity [header-len instructions-len item-len]
  (let [reserved (+ header-len instructions-len)]
    (max 1 (quot (- (term-rows) reserved) item-len))))

;; Init Lines

(defn welcome-message [{:keys [genre style year] :as _params}]
  (let [prefix "Welcome! Querying Discogs for genre"]
    (cond-> (line prefix (green (str/capitalize genre)))
      style (line "in style" (green (str/capitalize style)))
      year  (line "from" (green year)))))

(defn welcome-lines [{:keys [params index]}]
  [(welcome-message params)
   (line (green ">")
         "Starting at page"
         (str (:page index) ", index")
         (inc (:result index)))
   (if-not (:no-database (:options cli-opts))
     (line (green ">") "Using database at:" db-path)
     (line (yellow ">") "Skipping database connection"))])

(def init-instructions-lines
  (cond-> [(line)
           (line (blue "[?]")
                 "Press" (yellow "n") "or"
                 (yellow "p") "to load the first release,"
                 (yellow "q") "to quit.")]))

(def init-statuses
  {:fetching [(line (green ">") "Fetching initial results...")]
   :done     [(line (green ">") "Fetching initial results... Done!")]
   :error    [(line)
              (line (red "[!]") "No results found! Exiting")]})

(defn init-lines [state]
  (let [status (-> state :view :props :status)]
    (cond-> (welcome-lines state)
      (not mpv-exists?)
      (conj (red "> mpv not found - audio playback disabled"))
      (some? status)   (into (get init-statuses status))
      (= :done status) (into init-instructions-lines))))

;; Resource Lines

(def resource-instructions-lines
  (cond-> [(line)
           (line (blue "[?]")
                 "Press" (yellow "n") "for next release,"
                 (yellow "p") "for previous,"
                 (yellow "q") "to quit.")
           (line "   "
                 "To navigate videos, press"
                 (yellow "j") "to go down,"
                 (yellow "k") "to go up.")
           (line "   "
                 "Press"
                 (yellow "Enter") "to open a video link in the browser.")]
    mpv-exists?
    (conj (line "   "  "Press" (yellow "Space") "to play/pause audio."))))

(defn resource-index-of-lines [{:keys [index data] :as _state}]
  (let [result-index (inc (:result index))
        results-len  (count (:results data))
        index-of     (str "(Release " result-index " of " results-len ")")]
    [(blue (line "Page" (:page index) index-of))]))

(defn resource-header-lines [{:keys [data index params] :as state}]
  (let [result      (current-result state)
        resource    (:resource data)
        param-style (some-> (:style params) (str/capitalize))
        videos-len  (count (:videos (:resource data)))
        video-idx   (inc (:video index))]
    (cond-> (resource-index-of-lines state)
      :always
      (into [(line "Artists:" (str/join ", " (map :name (:artists resource))))
             (line "Title:  " (:title resource))
             (line "Year:   " (:year resource))
             (line "Master: " (if (:master? resource) "Yes" "No"))
             (line "Page:   " (:uri resource))
             (line "Exact   " (if (= #{param-style} (set (:style result)))
                                "Yes" "No"))
             (line "Have:   " (:have (:community result)))
             (line "Want:   " (:want (:community result)))
             (line "Videos:" (str " (" video-idx " of " videos-len ")"))])
      $conn
      (conj (line "Seen:   " (if (:cached? resource) (green "Yes") "No"))))))

(defn video-duration-display [duration]
  (str "[" (int (/ duration 60)) "m" (format "%02d" (mod duration 60)) "s]"))

(defn video-lines [state idx {:keys [uri title duration] :as _video}]
  (let [current? (= idx (:video (:index state)))
        playing? (= idx (:play-video (:actions state)))
        invalid? (contains? (:invalid-videos (:issues state)) uri)]
    (cond->> [(line " "   (if current? (str "> " title) (str "- " title)))
              (line "   " (str (cond invalid? "! "
                                     playing? "▶ "
                                     :else    "")
                               uri))
              (line "   " (video-duration-display duration))]
      (and current? (not invalid?))
      (map (comp bold green))
      invalid?
      (map (comp bold red)))))

(defn videos-lines [{:keys [data index] :as state} header-len]
  (let [videos      (:videos (:resource data))
        total       (count videos)
        instr-len   (count resource-instructions-lines)
        capacity    (list-capacity header-len instr-len 3)
        [start end] (list-item-range total capacity (:video index))]
    (mapcat #(video-lines state % (nth videos %)) (range start end))))

(defn resource-lines [{:keys [data] :as state}]
  (let [resource (:resource data)
        videos   (:videos resource)
        header   (resource-header-lines state)]
    (-> (cond
          (not resource)
          (conj (resource-index-of-lines state)
                (line (red "[!] No resource found!")))

          (empty? videos)
          (conj header (red "(No Videos)"))

          :else
          (into header (videos-lines state (count header))))
        (into resource-instructions-lines))))

;; Sessions

(def session-instructions-lines
  [(line)
   (line (blue "[?]")
         "Press" (yellow "j") "for next session,"
         (yellow "k") "for previous,"
         (yellow "q") "to quit.")
   (line "   " "Press" (yellow "Enter") "to resume session.")])

(defn session-item-title [{:keys [genre style year] :as _session}]
  (str "Genre: " (str/capitalize genre) " | "
       "Style: " (str/capitalize style) " | "
       "Year: "  year))

(defn session-item-lines [state idx {:keys [page index created-at] :as session}]
  (let [current? (= idx (:session (:index state)))
        title    (session-item-title session)]
    (cond->> [(line " "   (if current? (str "> " title) (str "- " title)))
              (line "   " (str "Page: " page " - Release: " (inc index)))
              (line "   " (str "Updated: " (relative-time created-at)))]
      current? (map (comp bold green)))))

(defn session-list-lines [{:keys [data index] :as state} header-len]
  (let [sessions    (:sessions data)
        total       (count sessions)
        instr-len   (count session-instructions-lines)
        capacity    (list-capacity header-len instr-len 3)
        [start end] (list-item-range total capacity (:session index))]
    (mapcat #(session-item-lines state % (nth sessions %)) (range start end))))

(defn sessions-lines [{:keys [data index] :as state}]
  (let [idx    (+ (:session index) 1)
        len    (count (:sessions data))
        header [(line "Welcome! Choose a session to resume:"
                      (str "(" idx " of " len ")"))]]
    (-> header
        (into (session-list-lines state (count header)))
        (into session-instructions-lines))))

;; Watchers --------------------------------------------------------------------

;; Browser

(defn browse! [_ _ prev next]
  (let [id1 (get-in prev [:actions :browse-id])
        id2 (get-in next [:actions :browse-id])
        uri (get-in next [:actions :browse-uri])]
    (when (and (not= id1 id2) uri)
      (browse-url uri))))

;; Player

(def player* (atom nil))

(defn destroy-player! []
  (when-let [p @player*]
    (reset! player* nil)
    (.destroy p)))

(defn invalidate-video [state uri]
  (let [current? (= uri (some-> (current-video state) :uri))]
    (cond-> state
      :always  (update-in [:issues :invalid-videos] #(conj % uri))
      current? (assoc-in [:actions :play-video] nil))))

(defn play-video! [uri]
  (let [args   ["mpv" "--no-video" "--really-quiet" uri]
        player (-> (ProcessBuilder. args) (.start))]
    (reset! player* player)
    (future
      (.waitFor player)
      (when (and (not (zero? (.exitValue player)))
                 (identical? player @player*))
        (swap! state* invalidate-video uri)))))

(defn play! [_ _ prev next]
  (when (not= (:play-video (:actions prev))
              (:play-video (:actions next)))
    (destroy-player!)
    (when-let [idx (:play-video (:actions next))]
      (some-> (video-by-index next idx) :uri (play-video!)))))

;; Progress Transactor

(defn save-progress! [_ _ prev next]
  (when (and (not= (:id prev) (:id next)) (< 0 (:id next)))
    (some-> $conn (transact-progress! (state->progress next)))))

;; Renderer

(defn render! [_ _ _ {:keys [view] :as state}]
  (let [lines (case (:key view)
                :view/sessions  (sessions-lines state)
                :view/init      (init-lines state)
                :view/resources (resource-lines state))
        frame (str "\033[H" (str/join "\033[K\n" lines) "\033[K\033[J")]
    (print frame)
    (flush)))

;; System

(defn system! [_ _ _ next]
  (when (:system/exit! (:actions next))
    (println)
    (System/exit 1)))

;; Setup

(defn watch! []
  (add-watch state* ::browse browse!)
  (add-watch state* ::play   play!)
  (add-watch state* ::save   save-progress!)
  (add-watch state* ::render render!)
  (add-watch state* ::system system!))

;; Initialization --------------------------------------------------------------

(defn resolve-progress! [options]
  (when (and (:resume options) (not (:no-database options)))
    (some-> $conn d/db (?last-progress))))

(defn resolve-sessions! [options]
  (when (and (:list options) (not (:no-database options)))
    (some-> $conn d/db (?progress-list))))

(defn resolve-state! [{:keys [options]}]
  (let [sessions (resolve-sessions! options)
        progress (when-not sessions (resolve-progress! options))]
    (cond
      (seq sessions)
      (-> initial-state
          (assoc :view {:key :view/sessions} :entry :entry/sessions)
          (assoc-in [:data :sessions] sessions)
          (vector :ok))

      (:list options)
      [nil :error "No saved sessions found"]

      (some? progress)
      (-> initial-state
          (merge-progress progress)
          (assoc :entry :entry/resume)
          (vector :ok))

      (:resume options)
      [nil :error "No progress found in database, cannot resume"]

      :else
      [initial-state :ok])))

(defn transition! [state [next fx]]
  (when-not (identical? state next)
    (reset! state* next))
  (let [final (if fx (run-effect! next fx) next)]
    (when-not (identical? next final)
      (reset! state* final))))

(defn read-loop! []
  (loop []
    (let [input (read-char)]
      (if-not (= input (int \q))
        (let [state  @state*
              result (handle-input state input)]
          (transition! state result)
          (recur))
        (println)))))

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
  (let [[state status error] (resolve-state! cli-opts)]
    (case status
      :ok    (do (watch!) (reset! state* state))
      :error (do (print-error error)
                 (print-summary (:summary cli-opts))
                 (System/exit 1)))))

(defn main! []
  (mount!)
  (case (:entry @state*)
    :entry/default   (swap! state* #(run-effect! % [:fx/init]))
    :entry/resume    (swap! state* #(run-effect! % [:fx/load]))
    :entry/sessions  nil
    nil)
  (read-loop!))

;; Run

(main!)
