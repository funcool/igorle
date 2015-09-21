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

(defprotocol IWebSocketFactory
  (-create [_] "Create a websocket instance."))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Implementation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord WebSocket [ws bus mult]
  IWebSocket
  (-listen [_ ch]
    (a/tap mult ch)
    ch)

  (-send [_ data]
    (.send ws data))

  (-close [_]
    (.close ws)
    (a/close! bus)))

(defn- listener
  [type output event]
  (let [data (.-data event)]
    (a/put! output {:type type :payload data ::event event})))

(defn- websocket*
  [ws]
  (let [bus (a/chan (a/sliding-buffer 256))
        mult (a/mult bus)]
    (set! (.-onmessage ws) (partial listener bus :socket/message))
    (set! (.-onclose ws) (partial listener bus :socket/close))
    (set! (.-onopen ws) (partial listener bus :socket/open))
    (set! (.-onerror ws) (partial listener bus :socket/error))
    (WebSocket. ws bus mult)))

(defrecord FakeWebSocket [busin busout mult]
  IWebSocket
  (-listen [_ ch]
    (a/tap mult ch)
    ch)

  (-send [_ data]
    (a/put! busout data))

  (-close [_]
    (a/close! busin)
    (a/close! busout)))

(declare websocket)

(extend-protocol IWebSocketFactory
  string
  (-create [uri]
    (websocket uri))

  WebSocket
  (-create [it]
    it)

  FakeWebSocket
  (-create [it]
    it))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public Api
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn websocket
  [url]
  (let [ws (js/WebSocket. url)]
    (websocket* url)))

(defn fake-websocket
  [busin busout]
  (let [mult (a/mult busin)]
    (FakeWebSocket. busin busout mult)))
