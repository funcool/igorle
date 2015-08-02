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
  [inputpub msgbus]
  (let [c (a/chan 256)]
    (a/sub inputpub :socket/message c)
    (a/go-loop []
      (when-let [message (a/<! c)]
        (try
          (->> (postal/parse message)
               (a/>! msgbus))
          (catch js/Error e
            (log/error e)
            ;; TODO: put the error in a error channel
            ))
        (recur)))))

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
         client (map->Client {:socket socket
                              :options options
                              :open open
                              :input input
                              :output output
                              :msgbus msgbus
                              :inputpub inputpub
                              :msgbuspub msgbuspub})]

     (s/set-listener! :message #(put! input {:type :socket/message :payload (.-data %)}))
     (s/set-listener! :open #(put! input {:type :socket/open :payload (.-data %)}))
     (s/set-listener! :close #(put! input {:type :socket/close :payload (.-data %)}))
     (s/set-listener! :error #(put! input {:type :socket/error :payload (.-data %)}))

     ;; A process that decodes the messages
     (handle-input-data client)

     ;; A process that encodes messages
     (handle-output-data client)
     client)))

(defn client?
  "Return true if a privided client is instance
  of Client type."
  [client]
  (instance? Client client))

;; (defn wait-open
;;   "Wait until the client is suscessfully in a open state."
;;   ([client])
;;   ([client timeout]))


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

;; (defn connect
;;   "Sends a HELLO frame to the server for establish the
;;   POSTAL protocol connection.

;;   Optionally, the user can provide a timeout as second
;;   parameter. If it is not provided, the default timeout
;;   is 600ms."
;;   ([client]
;;    (connect client 600))
;;   ([client timeout]
;;    {:pre [(client? client)]}
;;    (let [p (:msgbuspub client)
;;          sc (a/chan 1)]
;;      (a/sub p :hello sc)
;;      (p/promise
;;       (fn [resolve reject]
;;         (a/go
;;           (let [[val ch] (a/alts! [c (a/timeout timeout)])]
;;             (a/close! sc)
;;             (if (= ch c)
;;               (resolve nil)
;;               (reject {:type :timeout})))))))


(defn- query*
  [client message]
  (let [output (:output client)
        msgbuspub (:msgbuspub client)
        sc (a/chan 1)
        oc (a/chan 1)]
    (a/sub msgbuspub :response sc)
    (a/sub msgbuspub :error sc)
    (a/go-loop []
      (when-let [frame (a/<! msgbuspub)]
        (let [frameid (get-in frame [:headers :message-id])
              msgid (get-in message [:headers :message-id])]
          (if (= frameid msgid)
            (do
              (case (:command frame)
                :error (a/>! oc (either/left frame))
                :response (a/>! oc (either/right frame)))
              (a/close! sc)
              (a/close! oc))
            (recur)))))
    oc))

(defn query
  "Sends a QUERY frame to the server."
  ([client message]
   (query client message 600))
  ([client message timeout]
   {:pre [(client? client)]}
   (p/promise
    (fn [resolve, reject]
      (let [qch (query* client message)
            tch (a/timeout timeout)]
        (a/go
          (let [[val ch] (a/alts! [qch tch])]
            (cond
              (= ch tch)
              (reject (ex-info "Timeout" {:type :timeout}))

              (either/left? val)
              (reject @val)

              :else
              (resolve @val)))))))))
