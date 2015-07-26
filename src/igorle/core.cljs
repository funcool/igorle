(ns igorle.core
  (:require [taoensso.timbre :as log :require-macros true]
            [postal.parser :as postal]
            [igorle.socket :as socket]
            [cuerdas.core :as str]
            [cats.core :as m]
            [cats.monad.either :as either]))

(defrecord Client [socket options open
                   input output inputpub msgbus msgbuspub]

(defn- handle-socket-state
  [inputpub openstate]
  (let [c (a/chan)]
    (a/sub inputpub :socket/open c)
    (a/sub inputpub :socket/close c)

    (a/go-loop []
      (when-let [{:keys [type payload]} (a/<! c)]
        (case type
          :socket/open (vreset! openstate true)
          :socket/open (vreset! openstate false))
        (recur)))))

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

(defn client
  "Creates a new client instance from socket."
  ([uri]
   (client socket {}))
  ([uri options]
   (assert (satisfies? socket/ISocket socket))
   (let [input (a/chan)
         inputpub (a/pub input :type)
         output (a/chan 256)
         msgbus (a/chan)
         msgbuspub (a/pub msgbus #(keyword (str/lower (:command %))))
         open (volatile! false)]

     ;; A process for maintaining the open/close state of the socket.
     (handle-socket-state inputpub open)

     ;; A process that decodes the messages
     (handle-input-data inputpub msgbus)

     (socket/set-listener! :message #(put! input {:type :socket/message :payload (.-data %)}))
     (socket/set-listener! :open #(put! input {:type :socket/open :payload (.-data %)}))
     (socket/set-listener! :close #(put! input {:type :socket/close :payload (.-data %)}))
     (socket/set-listener! :error #(put! input {:type :socket/error :payload (.-data %)}))

     (map->Client {:socket socket
                   :options options
                   :open open
                   :input input
                   :output output
                   :msgbus msgbus
                   :inputpub inputpub
                   :msgbuspub msgbuspub}))))

(defn client?
  "Return true if a privided client is instance
  of Client type."
  [client]
  (instance? Client client))

;; (defn wait-open
;;   "Wait until the client is suscessfully in a open state."
;;   ([client])
;;   ([client timeout]))

(defn get-socket-error-chan
  "Return a channel that will receive all low level
  socket erorrs.

  The user can pass hes own channel as second
  argument."
  ([client]
   (get-socket-error-chan client (a/chan)))
  ([client ch]
   {:pre [(client? client)]}
   (let [p (:inputpub client)]
     (sub p :error ch true))))

(defn get-socket-message-chan
  "Return a channel that will receive all incoming
  messages from the server.

  The user can pass hes own channel as second
  argument."
  ([client]
   (get-socket-message-chan client (a/chan)))
  ([client ch]
   {:pre [(client? client)]}
   (let [p (:inputpub client)]
     (sub p :message ch true))))


;; TODO: send a :proto/connected message for
;; give the ability to watch socket connected
;; event.x

(defn connect
  "Sends a HELLO frame to the server for establish the
  POSTAL protocol connection.

  Optionally, the user can provide a timeout as second
  parameter. If it is not provided, the default timeout
  is 600ms."
  ([client]
   (connect client 600))
  ([client timeout]
   {:pre [(client? client)]}
   (let [p (:msgbuspub client)
         sc (a/chan)
         oc (a/chan)]
     (a/sub p :hello sc)
     (a/go
       (let [[val ch] (a/alts! [c (a/timeout timeout)])]
         (if (= ch c)
           (a/put! oc true)
           (a/put! oc false))
         (a/close! sc)
         (a/close! oc)))
     oc)))

(defn query
  "Sends a QUERY frame to the server."
  ([client message]
   (query client message))
  ([client message timeout]
   {:pre [(client? client)]}
   (let [output (:output client)
         msgbuspub (:msgbuspub client)
         sc (a/chan)
         oc (a/chan)]
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
     oc)))




