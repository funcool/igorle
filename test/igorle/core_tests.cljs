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

(t/deftest experiments
  (t/async done
    (let [in (a/chan)
          out (a/chan)
          sock (is/fake-websocket in out)
          client (ig/client sock)
          result (ig/query client "foobar")]
      (go
        (a/>! in {:type :socket/open})
        (let [v1 (a/<! out)
              _  (a/>! in {:type :socket/message
                           :payload (pc/render (pf/frame :hello {}))})
              v2 (a/<! out)
              _  (a/>! in {:type :socket/message
                           :payload (pc/render (pf/frame :response {:message-id (:id (:headers (pc/parse v2)))}))})]
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
