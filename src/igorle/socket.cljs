(ns igorle.socket
  "A basic socket interface abstraction."
  (:require [cljs.core.async :as a]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The socket abstraction
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol IWebSocket
  (-listen [_ ch] "Listen all messages from the channel.")
  (-send [_ data] "Send data to the socket.")
  (-close [_] "Close the socket."))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Implementation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftype WebSocket [ws bus mult]
  IWebSocket
  (-listen [_ ch]
    (a/tap mult ch)
    ch)

  (-send [_ data]
    (.send ws data))

  (-close [_]
    (.close ws)
    (a/close! bus)))

(defn websocket*
  [ws]
  (let [bus (a/chan (a/sliding-buffer 256))
        mult (a/mult bus)
        listener (fn [type event]
                   (let [data (.-data event)]
                     (a/put! output {:type type :payload data ::event event})))]
    (set! (.-onmessage ws) (partial listener :message))
    (set! (.-onclose ws) (partial listener :close))
    (set! (.-onopen ws) (partial listener :open))
    (set! (.-onerror ws) (partial listener :error))
    (WebSocket. ws bus mult)))

(deftype FakeWebSocket [bus mult]
  IWebSocket
  (-listen [_ ch]
    (a/tap mult ch)
    ch)

  (-send [_ data]
    (.send ws data))

  (-close [_]
    (.close ws)
    (a/close! bus)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public Api
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn websocket
  [url]
  (let [ws (js/WebSocket. url)]
    (websocket* url)))

(defn fake-websocket
  [ch]
  (let [mult (a/mult ch)]
    (FakeWebSocket. ch mult)))
