(ns igorle.core
  (:require-macros [cljs.core.async.macros :refer [go-loop go]])
  (:require [postal.frames :as pframes]
            [postal.core :as postal]
            [igorle.socket :as is]
            [igorle.log :as log :include-macros true]
            [cuerdas.core :as str]
            [promesa.core :as p]
            [cljs.core.async :as a]
            [cats.core :as m]
            [cats.monad.either :as either]))

(defrecord Client [socket options open in-ch in-pub out-ch bus-ch bus-pub])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Input frames decoding process.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- state-channel
  [client]
  (let [ic (a/chan 1)
        oc (a/chan 1)
        sock (:socket client)]
    (is/-listen sock ic)
    (go-loop []
      (when-let [message (a/<! ic)]
        (case (:type message)
          :socket/open (a/>! oc {:type :state :payload :open})
          :socket/close (a/>! oc {:type :state :payload :close})
          nil)
        (recur)))
    oc))

(def ^:private output-channel-xform
  (map (fn [frame] {:type :frame :payload frame})))

(defn- output-channel
  [client]
  (let [in-ch (:out-ch client)
        out-ch (a/chan 1 output-channel-xform)]
    (a/pipe in-ch out-ch)
    out-ch))

(defn- handle-input-messages
  [client]
  (let [ch (a/chan 256)
        bus-ch (:bus-ch client)
        in-pub (:in-pub client)]
    (a/sub in-pub :socket/message ch)
    (go-loop []
      (println "handle-input-messages$go-loop")
      (when-let [message (a/<! ch)]
        (println "handle-input-messages$go-loop$1" (:type message))
        (when (= (:type message) :socket/message)
          (try
            (let [frame (postal/parse (:payload message))]
              (println "handle-input-messages$go-loop$2" frame)
              (a/>! bus-ch frame))
            (catch js/Error e
              (log/warn "Error parsing the incoming message." e))))
        (recur)))))

(defn- handle-input-data
  [client]
  (let [sk (:socket client)
        sk-ch (is/-listen sk (a/chan 16))
        in-ch (:in-ch client)]
    (go-loop []
      (when-let [val (a/<! sk-ch)]
        (println "handle-input-data$go-loop$1")
        (a/>! in-ch val)
        (recur)))))

    ;; (a/pipe in-ch sk-ch true)))

(declare handshake)

(defn- handle-output-data
  [client]
  (let [output-ch (output-channel client)
        state-ch (state-channel client)
        ch (a/chan 256)
        open (:open client)
        sock (:socket client)
        mixer (a/mix ch)]

    ;; Initialize the mixer
    (a/admix mixer output-ch)
    (a/admix mixer state-ch)
    (a/toggle mixer {output-ch {:pause true}})

    ;; Start the process
    (go-loop []
      (println "handle-output-data$go-loop")
      (when-let [{:keys [type payload] :as msg} (a/<! ch)]
        (println "handle-output-data$go-loop$1" msg)
        (case type
          :state
          (case payload
            :open
            (let [result (a/<! (handshake client))]
              (vreset! open result)
              (a/toggle mixer {output-ch {:pause false}}))

            :close
            (do
              (vreset! open false)
              (a/toggle mixer {output-ch {:pause true}})))

          :frame
          (let [frame (postal/render payload)]
            (is/-send sock frame)))
        (recur)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Handshake
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare wait-frame)
(declare fatal-state!)

;; TODO: add authentication to the hello frame

(defn handshake
  [client]
  (let [frame (pframes/frame :hello {} "")
        sock (:socket client)
        timeout (get-in client [:options :handshake-timeout] 600)]
    (is/-send sock (postal/render frame))
    (go
      (println "handshake$go")
      (let [frame (a/<! (wait-frame client :hello nil timeout))]
        (println "handshake$go$1" frame)
        (if (nil? frame)
          (do
            (log/warn "Timeout on handshake.")
            (fatal-state! client)
            false)
          (if (= (:command frame) :error)
            (do
              (println "[log]: Error occured while handsake is performed.")
              (fatal-state! client)
              false)

            (do
              (println "[log]: Handskale perfromed successfully.")
              true)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Client Constructor.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:dynamic
  *default-config*
  {:output-buffersize 256
   :input-buffersize 256
   :handshake-timeout 1000
   :debug false})

(defn client
  "Creates a new client instance from socket."
  ([uri]
   (client uri {}))
  ([uri options]
   (let [socket (is/-create uri)
         in-ch (a/chan 256)
         in-pub (a/pub in-ch :type)
         out-ch (a/chan 256)
         bus-ch (a/chan)
         bus-pub (a/pub bus-ch :command)
         open (volatile! false)
         options (merge *default-config* options)
         client (map->Client {:socket socket
                              :options options
                              :open open
                              :in-ch in-ch
                              :in-pub in-pub
                              :out-ch out-ch
                              :bus-ch bus-ch
                              :bus-pub bus-pub})]

     ;; Process that pipes the socket messages
     ;; into internal client input bus (in-ch)
     (handle-input-data client)

     ;; Process that filters postal frames from
     ;; the input and put them into the message
     ;; bus (bus-ch).
     (handle-input-messages client)

     ;; Process that pipes the frames from internal
     ;; output bus (out-ch) to the socket.
     (handle-output-data client)

     client)))

(defn client?
  "Return true if a privided client is instance
  of Client type."
  [client]
  (instance? Client client))

(defn closed?
  [client]
  (let [open (:open client)]
    (not @open)))

(defn frame-with-id?
  "A predicate for check that frame comes with id."
  [frame]
  (not (nil? (get-in frame :headers :id))))

(defn- fatal-state!
  "Set a client in fatal state.

  This can hapens in client initialization, initial handshake
  and other similar situations where the user can't take any
  action.  This closes the socket and set a client into no
  usable state."
  [client data]
  (let [sock (:socket client)
        out-ch (:out-ch client)]
    (log/warn "The client '%s' enters in fatal state", client)
    ;; TODO: add the ability to centralize all input messages
    ;; (a/put! input {:type :client/error :payload data})
    (is/-close sock)
    (a/close! out-ch)))

(defn subscribe*
  "Subscribe to arbitrary events on the internal
  message bus channel."
  {:internal true :no-doc true}
  ([client key]
   (subscribe* client key (a/chan 1)))
  ([client key ch]
   (let [pub (:bus-pub client)]
     (a/sub pub key ch true)
     ch)))

(defn- send-frame
  [client frame]
  (let [output (:out-ch client)]
    (a/put! output frame)))

(defn- normalize-headers
  [headers]
  (merge {:id (str (random-uuid))} headers))

(defn query
  "Sends a QUERY frame to the server."
  ([client body]
   (query client body {}))
  ([client body {:keys [timeout headers] :or {headers {}}}]
   (let [headers (normalize-headers headers)
         timeout (get-in client [:options :handshake-timeout] 600)
         frame (pframes/query headers body)]
     (p/promise
      (fn [resolve, reject]
        (let [msgid (get-in frame [:headers :id])
              ch (wait-frame client :response msgid timeout)]
          (send-frame client frame)
          (a/take! ch (fn [frame]
                        (println "query$take$1" frame)
                        (if (nil? frame)
                          (reject (ex-info "Timeout" {:type :timeout}))
                          (if (= (:command frame) :error)
                            (reject frame)
                            (resolve frame)))))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- wait-frame
  [client frametype msgid timeout]
  {:pre [(keyword? frametype)]}
  (let [out-ch (:out-ch client)
        bus-pub (:bus-pub client)
        tc (a/timeout timeout)
        ic (a/chan 1)]
    (a/sub bus-pub frametype ic)
    (a/sub bus-pub :error ic)
    (go-loop []
      (println "wait-frame$go-loop" frametype msgid)
      (let [[frame oc] (a/alts! [ic tc])]
        (println "wait-frame$go-loop$1" frametype msgid frame)
        (if (= oc tc)
          (do
            (println "wait-frame$go-loop$timeout" timeout)
            (a/close! ic)
            nil)
          (let [frameid (get-in frame [:headers :message-id])]
            (println "wait-frame$go-loop$2" frametype msgid frame)
            (if (= frameid msgid)
              (do
                (println "wait-frame$go-loop$3" frametype msgid frame)
                (a/close! ic)
                frame)
              (recur))))))))
