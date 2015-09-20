(ns igorle.log
  "A lightweight logging abstraction."
  (:require [cljs.core.async :as a]
            [cuerdas.core :as str]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Logging output handling process.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- print-event
  "Print a event into the standard output."
  [{:keys [type payload]}]
  (let [message (first payload)
        params (rest payload)]
    (println type ":" (apply str/format message params))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Global Vars declaration
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce ^:private
  +output-channel+
  (let [ch (a/chan (a/sliding-buffer 256))]
    (a/go-loop []
      (when-let [event (a/<! ch)]
        (print-event event)
        (recur)))
    ch))

(defonce ^:dynamic
  *debug* true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

#?(:clj
   (defmacro trace
     "Show a trace log message in a console."
     [& args]
     `(when *debug*
        (a/put! +output-channel+ {:type ::trace :payload ~args}))))

#?(:clj
   (defmacro warn
     "Show a error or warning message in a console.

     This macro baypasses any debug control flag
     and prints directly in a console."
     [& args]
     (print-event {:type ::warn :payload ~args})))
