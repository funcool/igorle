(ns igorle.socket
  "A basic socket interface abstraction."
  (:require [cljs.core.async :as a]))

(defprotocol ISocket
  (listen! [_ ch] "Listen all events on channel."))

(defprotocol ISocketFactory
  String
  (-socket [_]
    (js/WebSocket. url))

(defn- listener
  "A listener factory helper."
  [type output]
  (fn [event]
    (a/put! output {:type type :payload (.-data event)})))

;; Default implementation for websockets

(when js/WebSocket
  (extend-type js/WebSocket
    ISocket
    (listen! [s ch]
      (case type
        :message (aset s "onmessage" (listener :socket/message input))
        :close (aset s "onclose" (listener :socket/close input))
        :open (aset s "onopen" (listener :socket/open input))
        :error (aset s "onerror" (listener :socket/error input))))))

(defn socket
  "Create new socket instance."
  [url]
  (-socket url))
