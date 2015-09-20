(require '[cljs.build.api :as b])

(println "Building ...")

(let [start (System/nanoTime)]
  (b/build
   (b/inputs "test" "src")
   {:main 'igorle.core-tests
    :output-to "out/tests.js"
    :output-dir "out"
    :target :nodejs
    :verbose true})
  (println "... done. Elapsed" (/ (- (System/nanoTime) start) 1e9) "seconds"))
