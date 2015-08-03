(ns igorle.core
  (:require [taoensso.timbre :as log :include-macros true]
            [postal.frames :as pframes]
            [postal.core :as postal]
            [igorle.socket :as socket]
            [cuerdas.core :as str]
            [promesa.core :as p]
            [cats.core :as m]
            [cats.monad.either :as either]))

(defrecord Client [socket options open input output msgbus inputpub msgbuspub])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Input frames decoding process.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- handle-input-data
  [client]
  (let [c (a/chan 256)
        pub (:inputpub client)
        bus (:msgbus client)]
    (a/sub inputpub :socket/message c)
    (a/go-loop []
      (when-let [message (a/<! c)]
        (try
          (->> (postal/parse message)
               (a/>! bus))
          (catch js/Error e
            (log/error e)
            ;; TODO: put the error in a error channel
            ))
        (recur)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Handshake
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare wait-frame)

(defn handshake
  [client]
  (let [frame (pframes/frame :hello {} "")
        sock (:socket client)
        oc (a/chan)]
    (s/send! sock (postal/render frame))
    (a/go
      (let [{:keys [type payload]} (a/<! (wait-frame client :hello nil 600))]
        (case type
          :timeout ;; todo
          :error   ;; todo
          :hello   ;; todo
          )
        (a/close! oc)))
    oc))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; State & Output processing.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- state-channel
  [inputpub]
  (let [sc (a/chan 1)
        oc (a/chan 1)]
    (a/sub inputpub :socket/open sc)
    (a/sub inputpub :socket/close sc)
    (a/go-loop []
      (when-let [{:keys [type payload]} (a/<! sc)]
        (case type
          :socket/open (a/!> oc {:type :state :payload :open})
          :socket/close (a/!> oc {:type :state :payload :close}))
        (recur)))
    oc))

(defn- output-channel
  [in-ch]
  (let [xform (map (fn [frame] {:type :frame :payload frame}))
        out-ch (a/chan 1 xform)]
    (a/pipe in-ch out-ch)
    out-ch))

(defn- handle-output-data
  [client]
  (let [och (output-channel (:output client))
        sch (state-channel (:inputpub client))
        och (a/chan 256)
        sock (:socket client)
        mixer (a/mix och)]

    ;; Initialize the mixer
    (a/admix mixer och)
    (a/admix mixer sch)
    (a/toggle mixer {och {:pause true}})

    ;; Start the process
    (a/go-loop []
      (when-let [{:keys [type payload]} (a/<! och)]
        (case type
          :state
          (case payload
            :open
            (do
              (vreset! open true)
              (a/<! (handshake sock))
              (a/toggle mixer {och {:pause false}}))

            :close
            (do
              (vreset! open false)
              (a/toggle mixer {och {:pause true}})))

          :frame
          (let [frame (postal/render payload)]
            (s/send! socket frame)))

        (recur)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Client Constructor.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:dynamic
  *default-config*
  {:output-buffersize 256
   :input-buffersize 256
   :debug false})

(defn client?
  "Return true if a privided client is instance
  of Client type."
  [client]
  (instance? Client client))

(defn frame-with-id?
  "A predicate for check that frame comes with id."
  [frame]
  (not (nil? (get-in frame :headers :id))))

(defn debug-mode?
  "Return true if a client is set into a debug mode."
  [client]
  (get-in client [:options :debug] false))

(defn- fatal-state!
  "Set a client in fatal state.

  This can hapens in client initialization, initial handshake
  and other similar situations where the user can't take any
  action.  This closes the socket and set a client into no
  usable state."
  [client data]
  (let [sock (:socket client)
        input (:input client)
        output (:output client)]
    (.close sock)
    (a/put! input {:type :client/error :payload data})
    (a/close! input)
    (a/close! output)))

(defn client
  "Creates a new client instance from socket."
  ([uri]
   (client uri {}))
  ([uri options]
   (let [socket (s/create uri)
         input (a/chan)
         inputpub (a/pub input :type)
         output (a/chan 256)
         msgbus (a/chan)
         msgbuspub (a/pub msgbus :command)
         open (volatile! false)
         options (merge *default-config* options)
         client (map->Client {:socket socket
                              :options options
                              :open open
                              :input input
                              :output output
                              :msgbus msgbus
                              :inputpub inputpub
                              :msgbuspub msgbuspub})]

     (s/listen! socket input)

     ;; A process that decodes the messages
     (handle-input-data client)

     ;; A process that encodes messages
     (handle-output-data client)
     client)))

(defn subscribe*
  "Subscribe to arbitrary events on the internal
  message bus channel."
  {:internal true :no-doc true}
  ([client key]
   (subscribe* client key (a/chan 1)))
  ([client key ch]
   (let [p (:inputpub client)]
     (sub p key ch true)
     ch)))

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
    (a/go-loop []
      (let [[frame ch] (a/alts! [sc tc])]
        (if (= ch tc)
          (a/>! oc {:type :timeout})
          (let [frameid (get-in frame [:headers :message-id])]
            (if (= frameid msgid)
              (do
                (case (:command frame)
                  :error (a/>! oc {:type :error :payload frame})
                  :response (a/>! oc {:type :response :payload frame})
                (a/close! sc)
                (a/close! oc))
              (recur))))))
    oc))

(defn- send-frame
  [client frame]
  (let [output (:output client)]
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
