(ns igorle.log
  "A lightweight logging abstraction."
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go-loop go]]))
  (:require #?(:cljs [cljs.core.async :as a])
            [cuerdas.core :as str]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Logging output handling process.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

#?(:cljs
   (defn- print-event
     "Print a event into the standard output."
     [{:keys [type payload]}]
     (let [message (first payload)
           params (rest payload)]
       (println type ":" (apply str/format message params)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Global Vars declaration
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

#?(:cljs
   (defonce ^:private
     +output-channel+
     (let [ch (a/chan (a/sliding-buffer 256))]
       (go-loop []
         (when-let [event (a/<! ch)]
           (print-event event)
           (recur)))
       ch)))

#?(:cljs
   (defonce ^:dynamic
     *debug* true))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro trace
  "Show a trace log message in a console."
  [& args]
  `(println ~@args))
  ;; `(when *debug*
  ;;    (a/put! +output-channel+ {:type ::trace :payload ~args})))

(defmacro warn
  "Show a error or warning message in a console.

  This macro baypasses any debug control flag
  and prints directly in a console."
  [& args]
  `(println ~@args))
  ;; `(print-event {:type ::warn :payload ~args}))
