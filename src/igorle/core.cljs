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

(defrecord Client [socket options open in-ch in-p out-ch bus-ch bus-pub])

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
          :socket/close (a/>! oc {:type :state :payload :close}))
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
      (when-let [message (a/<! ch)]
        (when (= (:type message) :socket/message)
          (try
            (let [frame (postal/parse (:payload message))]
              (a/>! bus-ch frame))
            (catch js/Error e
              (log/warn "Error parsing the incoming message." e))))
        (recur)))))

(defn- handle-input-data
  [client]
  (let [sk (:socket client)
        sk-ch (is/-listen sk (a/chan 16))
        in-ch (:in-ch client)]
    (a/pipe in-ch sk-ch true)))

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
      (when-let [{:keys [type payload]} (a/<! ch)]
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
      (let [{:keys [type payload]} (a/<! (wait-frame client :hello nil timeout))]
        (case type
          :timeout
          (do
            (log/warn "Timeout on handshake.")
            (fatal-state! client)
            false)

          :error
          (do
            (log/warn "Error occured while handsake is performed.")
            (fatal-state! client)
            false)

          :hello
          (do
            (log/trace "Handskale perfromed successfully.")
            true))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Client Constructor.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:dynamic
  *default-config*
  {:output-buffersize 256
   :input-buffersize 256
   :handshake-timeout 600
   :debug false})

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

(defn query
  "Sends a QUERY frame to the server."
  ([client frame]
   (query client frame 600))
  ([client frame timeout]
   {:pre [(client? client)
          (frame-with-id? frame)]}
   (p/promise
    (fn [resolve, reject]
      (let [msgid (get-in frame [:headers :id])
            ch (wait-frame client :response msgid timeout)]
        (send-frame client frame)
        (a/take! ch (fn [{:keys [type payload]}]
                      (case type
                        :timeout (reject (ex-info "Timeout" {:type :timeout}))
                        :error (reject frame)
                        :response (resolve frame)))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- wait-frame
  [client frametype msgid timeout]
  {:pre [(keyword? frametype)]}
  (let [output (:output client)
        msgbuspub (:msgbuspub client)
        tc (a/timeout timeout)
        sc (a/chan 1)
        oc (a/chan 1)]
    (a/sub msgbuspub frametype sc)
    (a/sub msgbuspub :error sc)
    (go-loop []
      (let [[frame ch] (a/alts! [sc tc])]
        (if (= ch tc)
          (a/>! oc {:type :timeout})
          (let [frameid (get-in frame [:headers :message-id])]
            (if (= frameid msgid)
              (do
                (condp = (:command frame)
                  :error (a/>! oc {:type :error :payload frame})
                  frametype (a/>! oc {:type :response :payload frame}))
                (a/close! sc)
                (a/close! oc))
              (recur))))))
    oc))
