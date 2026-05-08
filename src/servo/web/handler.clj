(ns servo.web.handler
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hiccup2.core :as h]
            [jsonista.core :as json]
            [reitit.ring :as ring]
            [ring.middleware.content-type :as content-type]
            [ring.middleware.params :as params]
            [ring.middleware.resource :as resource]
            [servo.db :as db]
            [servo.pattern :as pattern]
            [servo.scan :as scan]))

(defn- layout [title & body]
  (str
   "<!DOCTYPE html>"
   (h/html
    [:html {:lang "en"}
     [:head
      [:meta {:charset "utf-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
      [:title title]
      [:link {:rel "stylesheet" :href "/css/output.css"}]
      [:script {:src "https://unpkg.com/htmx.org@2.0.4" :defer true}]
      [:script {:src "https://unpkg.com/alpinejs@3.14.7/dist/cdn.min.js" :defer true}]]
     [:body {:class "min-h-screen bg-base-100 text-base-content"}
      [:nav {:class "navbar bg-base-200 border-b border-base-300 px-4"}
       [:div {:class "navbar-start"}
        [:a {:href "/" :class "text-xl font-bold tracking-tight"} "Servo"]]
       [:div {:class "navbar-end gap-2"}
        [:a {:href "/patterns" :class "btn btn-ghost btn-sm gap-2"}
         [:svg {:xmlns "http://www.w3.org/2000/svg" :class "h-4 w-4" :viewBox "0 0 24 24"
                :fill "none" :stroke "currentColor" :stroke-width "2"
                :stroke-linecap "round" :stroke-linejoin "round"}
          [:path {:d "M3 6h18"}]
          [:path {:d "M3 12h18"}]
          [:path {:d "M3 18h18"}]]
         "Patterns"]
        [:a {:href "/scan" :class "btn btn-ghost btn-sm gap-2"}
         [:svg {:xmlns "http://www.w3.org/2000/svg" :class "h-4 w-4" :viewBox "0 0 24 24"
                :fill "none" :stroke "currentColor" :stroke-width "2"
                :stroke-linecap "round" :stroke-linejoin "round"}
          [:path {:d "M21 2v6h-6"}]
          [:path {:d "M3 12a9 9 0 0 1 15-6.7L21 8"}]
          [:path {:d "M3 22v-6h6"}]
          [:path {:d "M21 12a9 9 0 0 1-15 6.7L3 16"}]]
         "Scan"]]]
      (into [:main {:class "container mx-auto p-8"}] body)]])))

(def ^:private placeholder-tile
  [:div {:class "bg-base-300 rounded flex items-center justify-center text-base-content/40 text-2xl"}
   "?"])

(defn- thumbnail-grid [collection-id models]
  (let [previews (->> models
                      (filter :preview-path)
                      (take 4))
        slots    (concat
                  (for [m previews]
                    [:img {:src   (str "/previews/" collection-id "/" (:filename m) ".png")
                           :class "w-full h-full object-cover rounded"}])
                  (repeat (- 4 (count previews)) placeholder-tile))]
    (into [:div {:class "grid grid-cols-2 gap-1 aspect-square mb-3"}] slots)))

(defn- empty-collection-tile []
  [:div {:class "aspect-square bg-base-300 rounded flex items-center justify-center mb-3 text-base-content/60"}
   "No models"])

(defn- collection-card [{:keys [id name tags models]}]
  [:div {:class "card bg-base-200 shadow-md hover:shadow-xl transition-shadow relative"}
   [:div {:class "card-body p-4"}
    (if (seq models)
      (thumbnail-grid id models)
      (empty-collection-tile))
    [:h2 {:class "card-title text-sm font-semibold line-clamp-2"}
     ;; overlay anchor covers the card; tag pill clicks sit above it via z-index
     [:a {:href  (str "/collections/" id)
          :class "absolute inset-0 z-0"}]
     name]
    [:div {:class "relative z-10 flex flex-wrap gap-1 mt-2 items-center"}
     (for [tag tags]
       [:a {:href  (str "/?q=" tag)
            :class "badge badge-sm badge-secondary"}
        tag])
     [:span {:class "badge badge-sm badge-outline"}
      (str (count models) " models")]]]])

(defn- filter-collections [collections q]
  (if (str/blank? q)
    collections
    (let [needle (str/lower-case q)
          match? (fn [s] (and s (str/includes? (str/lower-case s) needle)))]
      (filter (fn [{:keys [name tags]}]
                (or (match? name)
                    (some match? tags)))
              collections))))

(defn- collection-grid [collections]
  (into [:div {:id    "collection-grid"
               :class "grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-6"}]
        (map collection-card collections)))

(defn- search-bar [q]
  [:div {:class "mb-6"}
   [:input {:type        "search"
            :name        "q"
            :value       q
            :placeholder "Search collections…"
            :class       "input input-bordered w-full max-w-md"
            :hx-get      "/"
            :hx-trigger  "input changed delay:200ms"
            :hx-target   "#collection-grid"
            :hx-swap     "outerHTML"}]])

(defn- index-handler [db]
  (fn [req]
    (let [q           (or (get-in req [:query-params "q"]) "")
          collections (->> (db/read-store db "collections.edn" {})
                           vals
                           (sort-by :name)
                           (#(filter-collections % q)))
          htmx?       (= "true" (get-in req [:headers "hx-request"]))
          grid        (collection-grid collections)]
      {:status  200
       :headers {"content-type" "text/html; charset=utf-8"}
       :body    (if htmx?
                  (str (h/html grid))
                  (layout "servo" (search-bar q) grid))})))

(defn- health-handler [_]
  {:status  200
   :headers {"content-type" "application/json"}
   :body    (json/write-value-as-string {:status "ok"})})

(def ^:private not-found
  {:status 404 :body "Not found"})

(defn- preview-handler [db]
  (fn [{{:keys [collection-id filename]} :path-params}]
    (let [collections (db/read-store db "collections.edn" {})
          folder      (some-> (get collections collection-id) :folder-path)]
      (if-not folder
        not-found
        (let [images-dir (io/file folder ".servo-images")
              file       (io/file images-dir filename)
              dir-path   (str (.getCanonicalPath images-dir) java.io.File/separator)]
          ;; canonicalise then prefix-check to reject path traversal via filename (e.g. "../../etc/passwd")
          (if (and (.exists file)
                   (.isFile file)
                   (.canRead file)
                   (.startsWith (.getCanonicalPath file) dir-path))
            {:status  200
             :headers {"content-type" "image/png"}
             :body    file}
            not-found))))))

(defn- collection-detail-region [id name-value tags]
  [:div {:id "detail-region"}
   [:input {:type "text" :name "name" :value name-value
            :class "input input-lg font-bold w-full mb-4"
            :hx-patch (str "/collections/" id)
            :hx-trigger "blur"
            :hx-target "#detail-region"
            :hx-swap "outerHTML"
            :hx-include "#tags-form"}]
   [:div {:id "tags-form"
          :x-data (str "tagEditor(" (json/write-value-as-string {:tags tags :id id}) ")")}
    [:div {:class "flex flex-wrap gap-2 mb-2"}
     [:template {:x-for "tag in tags" :key "tag"}
      [:span {:class "badge badge-secondary gap-1"}
       [:span {:x-text "tag"}]
       [:button {:type "button" :class "btn btn-ghost btn-xs p-0"
                 "@click" "removeTag(tag)"} "×"]]]
     [:template {:x-for "tag in tags" :key "tag"}
      [:input {:type "hidden" :name "tag" "x-bind:value" "tag"}]]]
    [:input {:type "text" :placeholder "Add tag…"
             :class "input input-sm input-bordered"
             :list "tag-suggestions"
             :hx-get "/tags/autocomplete"
             :hx-trigger "input changed delay:150ms"
             :hx-target "#tag-suggestions"
             :hx-swap "innerHTML"
             :hx-include "this"
             :name "q"
             "@keydown.enter.prevent" "addTag($event.target.value); $event.target.value = ''"
             "@keydown.comma.prevent" "addTag($event.target.value.replace(',','')); $event.target.value = ''"}]
    [:datalist {:id "tag-suggestions"}]]])

(def ^:private tag-editor-script
  "function tagEditor(config) {
  return {
    tags: config.tags,
    id: config.id,
    addTag(val) {
      const t = val.trim();
      if (t && !this.tags.includes(t)) {
        this.tags.push(t);
        this.$nextTick(() => htmx.trigger(this.$el.closest('[hx-patch],[data-hx-patch]') || document.querySelector('[hx-patch]'), 'blur'));
      }
    },
    removeTag(tag) {
      this.tags = this.tags.filter(t => t !== tag);
      this.$nextTick(() => {
        const nameInput = document.querySelector('input[name=\"name\"]');
        if (nameInput) htmx.trigger(nameInput, 'blur');
      });
    }
  }
}
function lightbox() {
  return {
    open: false,
    photoUrl: '',
    show(url) { this.photoUrl = url; this.open = true; },
    close() { this.open = false; }
  }
}")

(defn- model-tile [collection-id m]
  (let [src (str "/previews/" collection-id "/" (:filename m) ".png")]
    [:div {:class "flex flex-col items-center gap-1"}
     [:img {:src   src
            :class "rounded shadow w-full aspect-square object-cover cursor-pointer"
            "@click" "show($event.target.src)"}]
     [:span {:class "text-xs text-center truncate w-full"} (:filename m)]]))

(defn- model-grid [collection-id models]
  (into [:div {:class "grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-6 gap-4 mt-6"}]
        (map #(model-tile collection-id %) models)))

(def ^:private lightbox-overlay
  [:div {:x-show                   "open"
         "@click.self"             "close()"
         "@keydown.escape.window"  "close()"
         :class                    "fixed inset-0 z-50 bg-black/80 flex items-center justify-center p-4"
         :style                    "display: none"}
   [:img {:x-bind:src "photoUrl"
          :class      "max-w-full max-h-full object-contain rounded shadow-2xl"}]])

(defn- collection-detail-page [{:keys [id name tags models]}]
  (layout name
          [:a {:href "/" :class "btn btn-ghost btn-sm mb-6"} "← Back"]
          (collection-detail-region id name tags)
          [:div {:x-data "lightbox()"}
           (model-grid id models)
           lightbox-overlay]
          [:script (h/raw tag-editor-script)]))

(defn- collection-detail-handler [db]
  (fn [{{:keys [id]} :path-params}]
    (let [collections (db/read-store db "collections.edn" {})]
      (if-let [collection (get collections id)]
        {:status  200
         :headers {"content-type" "text/html; charset=utf-8"}
         :body    (collection-detail-page collection)}
        not-found))))

(defn- form-tags [form-params]
  ;; htmx sends one "tag" field per chip; ring's wrap-params returns a string
  ;; for a single occurrence and a vector for multiple — normalise both.
  (let [raw (get form-params "tag")]
    (->> (cond
           (nil? raw)     []
           (string? raw)  [raw]
           :else          (vec raw))
         (remove str/blank?)
         vec)))

(defn- collection-patch-handler [db]
  (fn [{{:keys [id]} :path-params :keys [form-params]}]
    (let [collections (db/read-store db "collections.edn" {})]
      (if-not (contains? collections id)
        not-found
        (let [name-value (or (get form-params "name") "")
              tags       (form-tags form-params)
              updated    (update collections id merge {:name name-value :tags tags})]
          (db/write-store! db "collections.edn" updated)
          {:status  200
           :headers {"content-type" "text/html; charset=utf-8"}
           :body    (str (h/html (collection-detail-region id name-value tags)))})))))

(defn- all-tags [collections]
  (->> collections vals (mapcat :tags) distinct))

(defn- matching-tags [tags q]
  (if (str/blank? q)
    tags
    (let [needle (str/lower-case q)]
      (filter #(str/includes? (str/lower-case %) needle) tags))))

(defn- tags-autocomplete-handler [db]
  (fn [req]
    (let [q           (or (get-in req [:query-params "q"]) "")
          collections (db/read-store db "collections.edn" {})
          options     (sort (matching-tags (all-tags collections) q))]
      {:status  200
       :headers {"content-type" "text/html; charset=utf-8"}
       :body    (str (h/html (for [tag options]
                               [:option {:value tag}])))})))

(defn- scan-running-fragment [state]
  [:div {:id         "scan-status-region"
         :hx-get     "/scan/status"
         :hx-trigger "every 1s"
         :hx-target  "#scan-status-region"
         :hx-swap    "outerHTML"}
   [:div {:class "flex items-center gap-3 mb-2"}
    [:span {:class "loading loading-spinner"}]
    [:span (:message state "Scanning…")]]
   (when-let [{:keys [done total]} (:progress state)]
     [:div {:class "w-full mt-2"}
      [:progress {:class "progress progress-primary w-full"
                  :value done :max total}]
      [:p {:class "text-xs text-base-content/60 mt-1"}
       (str done " / " total " models")]])])

(defn- scan-complete-fragment [{:keys [collections-found previews-generated errors]}]
  [:div {:id "scan-status-region"}
   [:div {:class "alert alert-success mb-4"}
    [:span "Scan complete"]]
   [:ul {:class "list-disc list-inside space-y-1 mb-4"}
    [:li (str collections-found " collections found")]
    [:li (str previews-generated " new previews generated")]
    (when (seq errors)
      [:li {:class "text-error"}
       (str (count errors) " models failed: " (str/join ", " errors))])]
   [:a {:href "/" :class "btn btn-primary"} "Browse Library"]])

(defn- scan-error-fragment [{:keys [message]}]
  [:div {:id "scan-status-region"}
   [:div {:class "alert alert-error"}
    [:span (str "Scan failed: " message)]]])

(defn- scan-status-fragment [state]
  (case (:status state)
    :running  (scan-running-fragment state)
    :complete (scan-complete-fragment state)
    :error    (scan-error-fragment state)
    [:div {:id "scan-status-region"}]))

(defn- html-response [hiccup]
  {:status  200
   :headers {"content-type" "text/html; charset=utf-8"}
   :body    (str (h/html hiccup))})

(defn- scan-page-handler [config _db]
  (let [root-path (:stl-root config)]
    (fn [_req]
      {:status  200
       :headers {"content-type" "text/html; charset=utf-8"}
       :body    (layout "servo — scan"
                        [:div {:class "max-w-lg"}
                         [:h1 {:class "text-2xl font-bold mb-4"} "Scan Library"]
                         [:p {:class "mb-6 text-base-content/70"}
                          "Root: " [:code {:class "font-mono"} root-path]]
                         [:form {:hx-post   "/scan"
                                 :hx-target "#scan-status"
                                 :hx-swap   "innerHTML"}
                          [:button {:type "submit" :class "btn btn-primary"} "Scan Now"]]
                         [:div {:id "scan-status" :class "mt-6"}]])})))

(defn- scan-post-handler [config db]
  (let [root-path (:stl-root config)]
    (fn [_req]
      (scan/trigger! root-path db)
      (html-response (scan-running-fragment {:status :running :message "Starting scan..."})))))

(defn- scan-status-handler []
  (fn [_req]
    (html-response (scan-status-fragment (scan/get-state)))))

(defn- patterns-page [patterns {:keys [error name-value pattern-value]}]
  (layout
   "servo — patterns"
   [:div {:class "max-w-2xl"}
    [:h1 {:class "text-2xl font-bold mb-2"} "Patterns"]
    [:p {:class "mb-6 text-base-content/70"}
     "Order matters — first match wins at scan time."]
    (if (seq patterns)
      [:table {:class "table table-zebra mb-8"}
       [:thead [:tr [:th "Name"] [:th "Pattern"] [:th]]]
       (into [:tbody]
             (for [{:keys [id name pattern]} patterns]
               [:tr
                [:td name]
                [:td [:code {:class "font-mono"} pattern]]
                [:td
                 [:form {:method "post" :action (str "/patterns/" id "/delete")}
                  [:button {:type "submit" :class "btn btn-ghost btn-sm text-error"}
                   "Delete"]]]]))]
      [:p {:class "mb-8 text-base-content/60"} "No patterns yet."])
    [:h2 {:class "text-lg font-semibold mb-3"} "Add pattern"]
    (when error
      [:div {:class "alert alert-error mb-4"} [:span error]])
    [:form {:method "post" :action "/patterns" :class "flex flex-col gap-3"}
     [:label {:class "form-control"}
      [:span {:class "label-text mb-1"} "Name"]
      [:input {:type "text" :name "name" :value (or name-value "")
               :class "input input-bordered" :placeholder "Warhammer 40k"}]]
     [:label {:class "form-control"}
      [:span {:class "label-text mb-1"} "Pattern"]
      [:input {:type "text" :name "pattern" :value (or pattern-value "")
               :class "input input-bordered font-mono"
               :placeholder "40k/{unit_type}/{unit}/{creator}"}]]
     [:button {:type "submit" :class "btn btn-primary self-start"} "Add"]]]))

(defn- read-patterns [db]
  (db/read-store db "patterns.edn" []))

(defn- redirect-to [path]
  {:status 303 :headers {"location" path} :body ""})

(defn- find-conflict [existing-patterns new-parsed]
  (->> existing-patterns
       (filter #(pattern/conflict? new-parsed (pattern/parse-pattern (:pattern %))))
       first))

(defn- patterns-index-handler [db]
  (fn [_req]
    {:status  200
     :headers {"content-type" "text/html; charset=utf-8"}
     :body    (patterns-page (read-patterns db) {})}))

(defn- render-patterns-error [db error name-value pattern-value]
  {:status  200
   :headers {"content-type" "text/html; charset=utf-8"}
   :body    (patterns-page (read-patterns db)
                           {:error         error
                            :name-value    name-value
                            :pattern-value pattern-value})})

(defn- patterns-create-handler [db]
  (fn [{:keys [form-params]}]
    (let [name-value    (str/trim (or (get form-params "name") ""))
          pattern-value (str/trim (or (get form-params "pattern") ""))
          existing      (read-patterns db)]
      (cond
        (str/blank? name-value)
        (render-patterns-error db "Name is required." name-value pattern-value)

        (str/blank? pattern-value)
        (render-patterns-error db "Pattern is required." name-value pattern-value)

        :else
        (let [new-parsed (pattern/parse-pattern pattern-value)
              conflict   (find-conflict existing new-parsed)]
          (if conflict
            (render-patterns-error
             db
             (str "Conflicts with existing pattern \"" (:name conflict)
                  "\" (" (:pattern conflict) ").")
             name-value pattern-value)
            (let [new-pattern {:id      (str (java.util.UUID/randomUUID))
                               :name    name-value
                               :pattern pattern-value}]
              (db/write-store! db "patterns.edn" (conj existing new-pattern))
              (redirect-to "/patterns"))))))))

(defn- patterns-delete-handler [db]
  (fn [{{:keys [id]} :path-params}]
    (let [patterns (read-patterns db)]
      (db/write-store! db "patterns.edn" (filterv #(not= id (:id %)) patterns))
      (redirect-to "/patterns"))))

(defn- routes [config db]
  [["/"                                  {:get   (index-handler db)}]
   ["/health"                            {:get   health-handler}]
   ["/scan"                              {:get   (scan-page-handler config db)
                                          :post  (scan-post-handler config db)}]
   ["/scan/status"                       {:get   (scan-status-handler)}]
   ["/patterns"                          {:get  (patterns-index-handler db)
                                          :post (patterns-create-handler db)}]
   ["/patterns/:id/delete"               {:post (patterns-delete-handler db)}]
   ["/collections/:id"                   {:get   (collection-detail-handler db)
                                          :patch (collection-patch-handler db)}]
   ["/tags/autocomplete"                 {:get   (tags-autocomplete-handler db)}]
   ["/previews/:collection-id/:filename" {:get   (preview-handler db)}]])

(defn- build-handler [config db]
  (-> (ring/ring-handler
       (ring/router (routes config db))
       (ring/routes
        (ring/redirect-trailing-slash-handler)
        (ring/create-default-handler)))
      params/wrap-params
      (resource/wrap-resource "public")
      content-type/wrap-content-type))

(defrecord Handler [config db ring-handler]
  clojure.lang.IFn
  (invoke [_ req] (ring-handler req))
  (applyTo [_ args] (apply ring-handler args))

  com.stuartsierra.component/Lifecycle
  (start [this]
    (assoc this :ring-handler (build-handler config db)))
  (stop [this]
    (assoc this :ring-handler nil)))

(defn new-handler [config]
  (map->Handler {:config config}))
