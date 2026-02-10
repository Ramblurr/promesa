;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) Andrey Antukh <niwi@niwi.nz>

(ns promesa.util
  {:no-dynamic true}
  (:refer-clojure :exclude [with-open])
  (:require [promesa.protocols :as pt])
  #?(:cljd nil
     :clj
     (:import
      java.lang.reflect.Method
      java.util.concurrent.CancellationException
      java.util.concurrent.CompletionException
      java.util.concurrent.CompletionStage
      java.util.concurrent.ExecutionException
      java.util.concurrent.TimeUnit
      java.util.concurrent.TimeoutException
      java.util.concurrent.locks.ReentrantLock)))

#?(:cljd nil :clj (set! *warn-on-reflection* true))

#?(:cljd
   (defn ->Supplier [f]
     f)
   :bb
   (defn ->Supplier [f]
     (reify java.util.function.Supplier
       (get [_] (f))))
   :clj
   (deftype Supplier [f]
     java.util.function.Supplier
     (get [_] (f))))

#?(:cljd
   (defn ->Function [f]
     f)
   :bb
   (defn ->Function [f]
     (reify java.util.function.Function
       (apply [_ v]
         (f v))))
   :clj
   (deftype Function [f]
     java.util.function.Function
     (apply [_ v]
       (f v))))

(def f-identity
  #?(:cljd identity
     :bb (->Function identity)
     :clj (->Function identity)
     :cljs identity))

(defn unwrap-completion-stage
  {:no-doc true}
  [it]
  #?(:cljd it
     :cljs it
     :clj (.thenCompose ^CompletionStage it
                        ^java.util.function.Function f-identity)))

#?(:cljd nil
   :clj
   (defn completion-exception?
     {:no-doc true}
     [e]
     (instance? CompletionException e)))

#?(:cljd nil
   :clj
   (defn execution-exception?
     {:no-doc true}
     [e]
     (instance? ExecutionException e)))

#?(:cljd nil
   :clj
   (defn cancellation-exception?
     {:no-doc true}
     [e]
     (instance? CancellationException e)))

#?(:cljd nil
   :clj
   (defn timeout-exception?
     {:no-doc true}
     [e]
     (instance? TimeoutException e)))

(defn unwrap-exception
  "Unwrap CompletionException or ExecutionException"
  [cause]
  #?(:cljd cause
     :cljs cause
     :clj (if (or (instance? CompletionException cause)
                  (instance? ExecutionException cause))
            (or (ex-cause cause) cause)
            cause)))

#?(:cljd
   (defn ->Function2 [f]
     f)
   :bb
   (defn ->Function2 [f]
     (reify java.util.function.BiFunction
       (apply [_ r e]
         (f r (unwrap-exception e)))))
   :clj
   (deftype Function2 [f]
     java.util.function.BiFunction
     (apply [_ r e]
       (f r (unwrap-exception e)))))

#?(:cljd
   (defn ->Consumer2 [f]
     f)
   :bb
   (defn ->Consumer2 [f]
     (reify java.util.function.BiConsumer
       (accept [_ r e]
         (f r (unwrap-exception e)))))
   :clj
   (deftype Consumer2 [f]
     java.util.function.BiConsumer
     (accept [_ r e]
       (f r (unwrap-exception e)))))

(defn handler
  "Create a handler, mainly for combine two separate functions
  into a single callbale."
  [fv fc]
  (fn [v c]
    (if c (fc c) (fv v))))

#?(:cljd nil
   :clj
   (defn has-method?
     {:no-doc true}
     [klass name]
     (let [methods (into #{}
                         (map (fn [method] (.getName ^Method method)))
                         (.getDeclaredMethods ^Class klass))]
       (contains? methods name))))

#?(:cljd nil
   :clj
   (defn class-exists?
     {:no-doc true}
     [name]
     (try
       (Class/forName ^String name)
       true
       (catch ClassNotFoundException _
         false))))

#?(:cljd nil
   :clj
   (defn can-eval?
     {:no-doc true}
     [expr]
     (try (eval expr) true
          (catch Throwable _ false))))

#?(:cljd nil
   :clj
   (defmacro with-compile-cond
     ([cond then]
      (if (eval cond) then nil))
     ([cond then else]
      (if (eval cond) then else))))

(defn maybe-deref
  {:no-doc true}
  [o]
  (if (delay? o)
    (deref o)
    o))

(defn mutex
  {:no-doc true}
  []
  #?(:cljd
     (reify
       pt/ILock
       (-lock [_])
       (-unlock [_]))
     :clj
     (let [m (ReentrantLock.)]
       (reify
         pt/ILock
         (-lock [_] (.lock m))
         (-unlock [_] (.unlock m))))
     :cljs
     (reify
       pt/ILock
       (-lock [_])
       (-unlock [_]))))


(defn try*
  {:no-doc true}
  [f on-error]
  (try
    (f)
    (catch #?(:cljd dynamic :cljs :default :clj Throwable) e
      (on-error e))))

;; http://clj-me.cgrand.net/2013/09/11/macros-closures-and-unexpected-object-retention/
;; Explains the use of ^:once metadata

(defmacro ignoring
  [& exprs]
  `(try* (^:once fn* [] ~@exprs) (constantly nil)))

(defmacro try!
  [& exprs]
  `(try* (^:once fn* [] ~@exprs) identity))

(defn close
  ([o]
   (pt/-close o))
  ([o reason]
   (pt/-close o reason)))

(defn close!
  {:deprecated "12.0.0"}
  [& params]
  (apply close params))

#?(:cljd nil
   :clj
   (extend-protocol pt/ICloseable
     java.util.concurrent.ExecutorService
     (-closed? [it]
       (.isTerminated it))
     (-close [it]
       (let [interrupted (volatile! false)]
         (loop [terminated? ^Boolean (.isTerminated it)]
           (when-not terminated?
             (.shutdown it)
             (let [terminated?
                   (try
                     (.awaitTermination it 1 TimeUnit/DAYS)
                     (catch InterruptedException cause
                       (when-not @interrupted
                         (vreset! interrupted true)
                         (.shutdownNow it))
                       terminated?))]
               (recur ^Boolean terminated?))))

           (when @interrupted
             (let [thread (Thread/currentThread)]
               (.interrupt thread)))))

     java.lang.AutoCloseable
     (-closed? [_]
       (throw (IllegalArgumentException. "not implemented")))
     (-close [it]
       (.close ^java.lang.AutoCloseable it))))

(defmacro with-open
  [bindings & body]
  {:pre [(vector? bindings)
         (even? (count bindings))
         (pos? (count bindings))]}

  (when (:ns &env)
    (throw (ex-info "cljs/cljd not supported on with-dispatch! macro" {})))

  (reduce (fn [acc bindings]
            `(let ~(vec bindings)
               (try
                 ~acc
                 (finally
                   (let [target# ~(first bindings)]
                     (if (instance? java.lang.AutoCloseable target#)
                       (.close ^java.lang.AutoCloseable target#)
                       (pt/-close target#)))))))
          `(do ~@body)
          (reverse (partition 2 bindings))))
