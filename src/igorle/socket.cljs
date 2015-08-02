(ns igorle.socket
  "A basic socket interface abstraction.")

(def +valid-listener-types+
  #{:message :close :error :open})

(defprotocol ISocket
  (set-listener! [_ type callable]))

;; Default implementation for websockets

(when js/WebSocket
  (extend-type js/WebSocket
    ISocket
    (set-listener! [s type callable]
      (assert (contains? +valid-listener-types+ type))
      (case type
        :message (aset s "onmessage" callable)
        :close (aset s "onclose" callable)
        :open (aset s "onopen" callable)
        :error (aset s "onerror" callable)))))

(defn create
  "Create new socket from the url."
  [url]
  (js/WebSocket. url))
