(ns igorle.core-tests
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.test :as t]
            [cljs.core.async :as a]
            [promesa.core :as p]
            [postal.core :as pc]
            [postal.frames :as pf]
            [igorle.core :as ig]
            [igorle.socket :as is]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn do-handshake
  [sock]
  (let [busin (:busin sock)
        busout (:busout sock)]
    (go
      (let [hello-frame (a/<! busout)]
        (a/>! busin {:type :socket/message
                     :payload (pc/render (pf/frame :hello {}))})))))

(defn do-open
  [sock]
  (let [busin (:busin sock)]
    (go
      (a/>! busin {:type :socket/open}))))

(t/deftest experiments
  (t/async done
    (let [busin (a/chan)
          busout (a/chan)
          sock (is/fake-websocket busin busout)
          client (ig/client sock)
          result (ig/query client "foobar")]
      (go
        (a/<! (do-open sock))
        (a/<! (do-handshake sock))

        (let [message (a/<! busout)
              received-frame (pc/parse message)
              frame (pf/frame :response (:headers received-frame))]
          (a/>! busin {:type :socket/message
                       :payload (pc/render frame)})
          (p/then result
                  (fn [value]
                    (println 9999 value)
                    (done))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Initial Setup & Entry Point
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(enable-console-print!)

(defmethod t/report [:cljs.test/default :end-run-tests]
  [m]
  (if (t/successful? m)
    (set! (.-exitCode js/process) 0)
    (set! (.-exitCode js/process) 1)))

(set! *main-cli-fn* #(t/run-tests))
