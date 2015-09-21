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
  ([sock]
   (do-handshake sock :hello))
  ([sock type]
   (let [busin (:busin sock)
         busout (:busout sock)]
     (go
       (let [hello-frame (a/<! busout)
             frame (case type
                     :error (pf/frame :error {} "Unexpected error")
                     :hello (pf/frame :hello {} ""))]
         (a/>! busin {:type :socket/message :payload (pc/render frame)}))))))


(defn do-open
  [sock]
  (let [busin (:busin sock)]
    (go
      (a/>! busin {:type :socket/open}))))

(defn make-client
  []
  (let [busin (a/chan)
        busout (a/chan)
        sock (is/fake-websocket busin busout)
        client (ig/client sock)]
    [busin busout sock client]))

(t/deftest error-on-handshake-test
  (t/async done
    (let [[busin busout sock client] (make-client)]
      (go
        (a/<! (do-open sock))
        (a/<! (do-handshake sock :error))
        (t/is (ig/closed? client))
        (done)))))

(t/deftest query-frame-success-test
  (t/async done
    (let [[busin busout sock client] (make-client)
          result (ig/query client "/foobar")]
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
                    (t/is (pf/frame? value))
                    (t/is (pf/response? value))
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
