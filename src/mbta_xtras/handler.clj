(ns mbta-xtras.handler
  (:require [clojure.data.json :as json]
            [compojure.core :refer [context defroutes GET POST]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.params :refer [wrap-params]]
            [selmer.parser :refer [render-file]]

            [mbta-xtras.api-spec :as api :refer [defapi keyfn]]
            [mbta-xtras.db :as db]
            [mbta-xtras.trip-performance :as trip]

            [clojure.spec :as s]
            [clojure.string :as str]
            [mbta-xtras.utils :as $]))


(defapi find-stops ::api/find-stops-query
  [{:keys [db params]}]
  (db/find-stops db params))

(defapi trip-performance ::api/trip-performance-request
  [{:keys [db params]}]
  (let [results (trip/observed-trip-performance db
                                                (:trip-id params)
                                                (:trip-start params))]
    {:performance results}))

(defapi travel-times ::api/travel-times-request
  [{:keys [db params]}]
  (let [{:keys [from-datetime to-datetime
                from-stop to-stop]} params]
    {:travel-times (trip/travel-times db from-stop to-stop
                                      (Long/parseLong from-datetime)
                                      (Long/parseLong to-datetime))}))

(defapi dwells ::api/dwells-request
  [{:keys [db params]}]
  {:status 501
   :body "Not implemented"})

(defapi headways ::api/headways-request
  [{:keys [db params]}]
  {:status 501
   :body "Not implemented"})

(defapi daily-metrics ::api/daily-metrics-request
  [_]
  {:status 501
   :body "Not implemented"})

(defapi current-metrics ::api/current-metrics-request
  [_]
  {:status 501
   :body "Not implemented"})

(defapi trips-for-stop ::api/trips-for-stop-request
  [{:keys [db params]}]
  (db/find-trips-for-stop db (:stop-id params)))

(defapi trip-updates ::api/trip-updates-request
  [{:keys [db params]}]
  (let [{:keys [trip-id trip-start]} params]
    (json/write-str
     (db/trip-updates db trip-id trip-start)
     :key-fn keyfn)))

(defapi services-at ::api/services-request
  [{:keys [db params]}]
  (let [dt ($/datetime-for-stamp (Integer/parseInt (:at params)))]
    (case (db/scheduled-trips-at db dt))))

(defapi trips-at ::api/trips-at-request
  [{:keys [db conformed]}]
  (let [conform-key (first conformed)
        {:keys [day at stamp]} (second conformed)
        dt (case conform-key
             ::api/date-time (.. ($/date-for-str day) (atTime ($/time-for-str at)))
             ::api/stamp ($/datetime-for-stamp (Integer/parseInt at)))]
    (db/scheduled-trips-at db dt)))

(defn select-route-trip [{{:keys [route-id]} :params, db :db}]
  (let [trips (db/trips-for-route db route-id)
        directions ($/index-by :direction trips)]
    (render-file "template/select_trip.djhtml" {:trips trips
                                                :route-id route-id})))

(defn benchmark [{{:keys [trip-id]} :params, db :db}]
  (let [stop-times (->> (db/stop-times-for-trip db trip-id)
                        (db/add-stop-info db)
                        (sort-by :stop-sequence ))]
    (render-file "template/benchmark.djhtml" {:stops stop-times
                                              :trip-id trip-id})))

(defn stats [{:keys [db]}]
  (let [recent-stops (db/recent-trip-stops db 3600)
        trip-ids (into #{} (map :trip-id) recent-stops)
        route-ids (into #{} (map :route-id) recent-stops)
        stop-ids (into #{} (map :stop-id) recent-stops)]
    (render-file "template/stats.djhtml"
                 {:time-range "the last hour"
                  :recent-trip-stops recent-stops
                  :unique-trips trip-ids
                  :unique-routes (sort route-ids)
                  :unique-stops stop-ids})))

(defroutes handler
  (GET "/find_stops" []  find-stops)
  (GET "/trips_for_stop" [] trips-for-stop)
  (GET "/trip_performance" []  trip-performance)
  (GET "/services" [] services-at)
  (GET "/trips_at" [] trips-at)
  ;; Helper for recording stop times:
  (GET "/select_trip/:route-id" req select-route-trip)
  (GET "/trip_benchmark/:trip-id" req benchmark)
  (GET "/stats" req stats)

  ;; MBTA Performance API:
  (GET "/dwells" [] dwells)
  (GET "/headways" [] headways)
  (GET "/traveltimes" [] travel-times)
  (GET "/trip_updates" [] trip-updates))

(def app
  (-> #'handler
      (wrap-keyword-params)
      (wrap-params)))

(defn make-app
  "Runs when the Web component is started. Works as a middleware wrapper by
  associating a mongo connection and database to each request before passing the
  request to handlers."
  [conn db]
  (fn [req]
    (app (assoc req :mongo conn, :db db))))
