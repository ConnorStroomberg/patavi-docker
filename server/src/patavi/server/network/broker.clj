(ns patavi.server.network.broker
  (:import clojure.lang.PersistentQueue)
  (:require [zeromq.zmq :as zmq]
            [overtone.at-at :as at]
            [clojure.set :refer [select]]
            [patavi.common.zeromq :as q]
            [patavi.common.util :refer [update-vals take-all]]
            [clojure.tools.logging :as log]))

(def ^:private services (atom {}))
(def ^:private max-ttl 5000) ;; in millis
(def ^:private ttl-pool (at/mk-pool))

(defn- now [] (System/currentTimeMillis))

(defrecord Service [name waiting workers requests])
(defrecord Worker [address expiry])

(def bind-socket
  (memoize
   (fn [context type address]
     (zmq/bind (zmq/socket context type) address))))

(defn- request-service
  [name]
  (let [service (get @services name)]
    (if (nil? service)
      (let [new-service (Service. name PersistentQueue/EMPTY #{} PersistentQueue/EMPTY)]
        (swap! services assoc name new-service)))
    (get @services name)))

(defn- find-worker
  [service-name address]
  (let [workers (:workers (request-service service-name))
        matches (filter #(= (:address %) address) workers)]
    (assert (<= (count matches) 1))
    (first matches)))

(defn- put-worker
  [service-name address]
  (let [service (request-service service-name)
        worker (or
                 (find-worker service-name address)
                 (Worker. address (now)))]
    (swap! services assoc service-name
           (-> service
               (update-in [:workers] #(conj % worker))
               (update-in [:waiting] #(conj % worker))))))

(defn- get-available
  [key]
  (fn [service]
    (if-let [itm (peek (get service key))]
      (do
        (swap! services assoc-in [(:name service) key]
               (pop (get service key)))
        itm))))

(def ^:private available-worker (get-available :waiting))
(def ^:private available-request (get-available :requests))

(defn service-available?
  [service-name]
  (> (count (get-in @services [service-name :workers])) 0))

(defn- update-expiry
  [service-name address]
  (let [service (request-service service-name)
        worker (find-worker service-name address)]
    (swap! services assoc (:name service)
           (-> service
               (update-in [:workers] #(disj % worker))
               (update-in [:workers] #(conj % (assoc worker :expiry (now))))))))

(defn- purge
  ([] (doall (map #(purge %) (vals @services))))
  ([service]
     (let [workers (:workers service)
           expired (filter (fn [{:keys [expiry]}] (> (- (now) expiry) max-ttl)) workers)
           expired? (fn [worker] (some #(= (:address worker) (:address %)) expired))
           waiting (:waiting service)]
       (when (seq expired)
         (log/warn "[broker] workers were expired:"
                   (map #(str (:address %) ":" (:name service)) expired))
         (swap! services assoc (:name service)
                (-> service
                    (assoc :workers (apply (partial disj workers) expired))
                    (assoc :waiting (into PersistentQueue/EMPTY
                                          (select (comp not expired?)
                                                  (set (take-all waiting)))))))))))

;; Periodically check the workers
(at/every 1000 purge ttl-pool)

(defn- dispatch
  [socket [_ service-name request :as msg]]
  (let [service (request-service service-name)]
    (when-not (nil? request) (swap! services assoc service-name
                                    (-> service
                                        (update-in [:requests] #(conj % msg)))))
    (while (and (not (empty? (get-in @services [service-name :waiting])))
                (not (empty? (get-in @services [service-name :requests]))))
      (let [service (request-service service-name)
            [client-addr _ request] (available-request service)
            worker-addr (:address (available-worker service))]
        (q/send! socket [worker-addr q/MSG-REQ client-addr request])))))

(defn- process-backend-message
  [backend frontend updates]
  (let [^ZMsg msg (q/receive! backend)
        [worker-addr msg-type method] (q/retrieve-data msg [String Byte String])]
    (condp = msg-type
      q/MSG-UPDATE (let [[process-id update]
                         (q/retrieve-data msg [String zmq/bytes-type])]
                     (q/send! updates [process-id update]))
      q/MSG-PING   (do (update-expiry method worker-addr)
                       (q/send! backend [worker-addr q/MSG-PONG]))
      q/MSG-READY  (do (put-worker method worker-addr)
                       (dispatch backend [worker-addr method]))
      q/MSG-REP    (let [[client-addr reply] (q/retrieve-data msg [String zmq/bytes-type])]
                     (q/send! frontend [client-addr q/STATUS-OK reply]))
      (log/warn "could not parse message" worker-addr msg-type))))

(defn- process-frontend-message
  [backend frontend]
  (dispatch backend (q/receive! frontend [String String zmq/bytes-type])))

(defn- broker-fn
  [frontend-address backend-address updates-address]
  (let [context (zmq/context)
        updates (bind-socket context :pub updates-address)
        [frontend backend :as sides] (map (partial bind-socket context :router)
                                          [frontend-address backend-address])
        poller (zmq/poller context 2)]
    (fn []
      (doseq [side sides] (zmq/register poller side :pollin))
      (while (not (.. Thread currentThread isInterrupted))
        (zmq/poll poller)
        (if (zmq/check-poller poller 0 :pollin)
          (process-frontend-message backend frontend))
        (if (zmq/check-poller poller 1 :pollin)
          (process-backend-message backend frontend updates))))))

(defn start
  [frontend-address backend-address updates-address]
  (let [broker-fn (broker-fn frontend-address backend-address updates-address)]
    (.start (Thread. broker-fn))))
