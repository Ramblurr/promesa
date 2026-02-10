;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) Andrey Antukh <niwi@niwi.nz>

(ns ^:no-doc promesa.impl
  "Implementation of promise protocols."
  {:no-dynamic true}
  (:require
   [promesa.protocols :as pt]
   [promesa.util :as pu]
   #?(:cljd nil :cljs [promesa.exec :as exec] :clj [promesa.exec :as exec])
   #?(:cljd ["dart:async" :as da])
   #?(:cljs [promesa.impl.promise :as impl]))

  #?(:cljd nil
     :clj
     (:import
      java.time.Duration
      java.util.concurrent.CompletableFuture
      java.util.concurrent.CompletionException
      java.util.concurrent.CompletionStage
      java.util.concurrent.ExecutionException
      java.util.concurrent.Executor
      java.util.concurrent.Future
      java.util.concurrent.TimeUnit
      java.util.concurrent.TimeoutException
      java.util.function.BiConsumer
      java.util.function.BiFunction
      java.util.function.Function
      java.util.function.Supplier)))

;; --- Global Constants

#?(:cljd nil :clj (set! *warn-on-reflection* true))

(defn promise?
  "Return true if `v` is a promise instance."
  [v]
  #?(:clj
     (or (instance? CompletionStage v)
         (satisfies? pt/IPromise v))

     :cljs
     (or (impl/isThenable v)
         (satisfies? pt/IPromise v))

     :cljd
     (or (dart/is? v da/Future)
         (satisfies? pt/IPromise v))))

(defn deferred?
  "Return true if `v` is a deferred instance."
  [v]
  (satisfies? pt/ICompletable v))

(defn resolved
  [v]
  #?(:cljd (let [d (deferred)]
             (pt/-resolve! d v)
             d)
     :cljs (impl/resolved v)
     :clj (CompletableFuture/completedFuture v)))

(defn rejected
  [v]
  #?(:cljd (let [d (deferred)]
             (pt/-reject! d v)
             d)
     :cljs (impl/rejected v)
     :clj (let [p (CompletableFuture.)]
            (.completeExceptionally ^CompletableFuture p v)
            p)))

(defn coerce
  [v]
  #?(:cljd (if (promise? v)
              (pt/-promise v)
              (resolved v))
     :clj  (if (promise? v)
             v
             (resolved v))
     :cljs (impl/coerce v)))

#?(:cljd
   (defn- ->dart-future
     [x]
     (cond
       (dart/is? x da/Future)
       x

       :else
       (let [p (pt/-promise x)]
         (cond
           (dart/is? p da/Future)
           p

           (satisfies? pt/IPromise p)
           (let [c (da/Completer)]
             (pt/-hmap p
                       (fn [v e]
                         (if e
                           (do
                             (.completeError c e)
                             nil)
                           (do
                             (.complete c v)
                             nil))))
             (.-future c))

           :else
           (da/Future.value p))))))

(defn all
  [promises]
  #?(:cljd (-> (da/Future.wait (mapv ->dart-future promises))
               (pt/-fmap vec))
     :cljs (-> (impl/all (into-array promises))
               (pt/-fmap vec))
     :clj (let [promises (map pt/-promise promises)]
            (-> (CompletableFuture/allOf (into-array CompletableFuture promises))
                (pt/-fmap (fn [_] (mapv pt/-extract promises)))))))

(defn race
  [promises]
  #?(:cljd (da/Future.any (mapv ->dart-future promises))
     :cljs (impl/race (into-array (map pt/-promise promises)))
     :clj (CompletableFuture/anyOf (into-array CompletableFuture (map pt/-promise promises)))))

;; --- Promise Impl

#?(:cljd
   (do
     (defn- forward-result! [target result-p]
       (pt/-hmap result-p (fn [v e] (if e (pt/-reject! target e) (pt/-resolve! target v)))))

     (deftype DeferredImpl [^da/Completer completer state value]
       pt/IPromiseFactory
       (-promise [it] it)

       pt/IPromise
       (-fmap [it f]
         (let [d (deferred)]
           (-> (.-future completer)
               (.then (fn [v] (pt/-resolve! d (f v))))
               (.catchError (fn [e] (pt/-reject! d e))))
           d))
       (-fmap [it f _] (pt/-fmap it f))

       (-mcat [it f]
         (let [d (deferred)]
           (-> (.-future completer)
               (.then (fn [v] (forward-result! d (pt/-promise (f v)))))
               (.catchError (fn [e] (pt/-reject! d e))))
           d))
       (-mcat [it f _] (pt/-mcat it f))

       (-hmap [it f]
         (let [d (deferred)]
           (-> (.-future completer)
               (.then (fn [v] (pt/-resolve! d (f v nil))))
               (.catchError (fn [e] (pt/-resolve! d (f nil e)))))
           d))
       (-hmap [it f _] (pt/-hmap it f))

       (-merr [it f]
         (let [d (deferred)]
           (-> (.-future completer)
               (.then (fn [v] (pt/-resolve! d v)))
               (.catchError (fn [e] (forward-result! d (pt/-promise (f e))))))
           d))
       (-merr [it f _] (pt/-merr it f))

       (-fnly [it f]
         (-> (.-future completer)
             (.then (fn [v] (f v nil)))
             (.catchError (fn [e] (f nil e))))
         it)
       (-fnly [it f _] (pt/-fnly it f))

       (-then [it f] (pt/-mcat it (fn [v] (coerce (f v)))))
       (-then [it f _] (pt/-then it f))

       pt/ICompletable
       (-resolve! [_ v]
         (when (= @state ::pending)
           (reset! state ::resolved)
           (reset! value v)
           (.complete ^da/Completer completer v)))
       (-reject! [_ e]
         (when (= @state ::pending)
           (reset! state ::rejected)
           (reset! value e)
           (.completeError ^da/Completer completer e)))

       pt/ICancellable
       (-cancel! [it]
         (pt/-reject! it (ex-info "promise cancelled" {})))
       (-cancelled? [_] false)

       clojure.core/IDeref
       (-deref [_]
         (if (= @state ::rejected)
           (throw @value)
           @value))

       pt/IState
       (-extract [_] @value)
       (-extract [_ default] (if (= @state ::pending) default @value))
       (-resolved? [_] (= @state ::resolved))
       (-rejected? [_] (= @state ::rejected))
       (-pending? [_] (= @state ::pending)))))

(defn deferred
  []
  #?(:cljd (DeferredImpl. (da/Completer) (atom ::pending) (atom nil))
     :clj (CompletableFuture.)
     :cljs (impl/deferred)))

#?(:cljs
   (defn extend-promise!
     [t]
     (extend-type t
       pt/IPromiseFactory
       (-promise [p] (impl/create p)))))


#?(:cljs (extend-promise! js/Promise))

#?(:cljd
   (extend-type da/Future
     pt/IPromiseFactory
     (-promise [p]
       (let [d (deferred)]
         (-> p (.then (fn [v] (pt/-resolve! d v))) (.catchError (fn [e] (pt/-reject! d e))))
         d))

     pt/IPromise
     (-fmap [it f] (pt/-fmap (pt/-promise it) f))
     (-fmap [it f executor] (pt/-fmap (pt/-promise it) f executor))
     (-mcat [it f] (pt/-mcat (pt/-promise it) f))
     (-mcat [it f executor] (pt/-mcat (pt/-promise it) f executor))
     (-hmap [it f] (pt/-hmap (pt/-promise it) f))
     (-hmap [it f executor] (pt/-hmap (pt/-promise it) f executor))
     (-merr [it f] (pt/-merr (pt/-promise it) f))
     (-merr [it f executor] (pt/-merr (pt/-promise it) f executor))
     (-fnly [it f] (pt/-fnly (pt/-promise it) f))
     (-fnly [it f executor] (pt/-fnly (pt/-promise it) f executor))
     (-then [it f] (pt/-then (pt/-promise it) f))
     (-then [it f executor] (pt/-then (pt/-promise it) f executor))

     pt/IState
     (-extract [_] nil)
     (-extract [_ default] default)
     (-resolved? [_] false)
     (-rejected? [_] false)
     (-pending? [_] true)))

#?(:cljs
   (extend-type impl/PromiseImpl
     pt/IPromiseFactory
     (-promise [p] p)

     pt/IPromise
     (-fmap
       ([it f] (.fmap it #(f %)))
       ([it f e] (.fmap it #(f %))))

     (-mcat
       ([it f] (.fbind it #(f %)))
       ([it f executor] (.fbind it #(f %))))

     (-hmap
       ([it f] (.fmap it #(f % nil) #(f nil %)))
       ([it f e] (.fmap it #(f % nil) #(f nil %))))

     (-merr
       ([it f] (.fbind it pt/-promise #(f %)))
       ([it f e] (.fbind it pt/-promise #(f %))))

     (-fnly
       ([it f] (.handle it f) it)
       ([it f executor] (.handle it f) it))

     (-then
       ([it f] (.then it #(f %)))
       ([it f executor] (.then it #(f %))))

     pt/ICompletable
     (-resolve! [it v]
       (.resolve ^js it v))
     (-reject! [it v]
       (.reject ^js it v))

     pt/ICancellable
     (-cancel! [it]
       (.cancel it))
     (-cancelled? [it]
       (.isCancelled it))

     cljs.core/IDeref
     (-deref [it]
       (let [value (unchecked-get it "value")]
         (if (.isRejected it)
           (throw value)
           value)))

     pt/IState
     (-extract
       ([it]
        (unchecked-get it "value"))
       ([it default]
        (if (.isPending it)
          default
          (unchecked-get it "value"))))

     (-resolved? [it]
       (.isResolved it))

     (-rejected? [it]
       (.isRejected it))

     (-pending? [it]
       (.isPending it))))

(defn- unwrap
  ([v]
   (if (promise? v)
     (pt/-mcat v unwrap)
     (pt/-promise v)))
  ([v executor]
   (if (promise? v)
     (pt/-mcat v unwrap executor)
     (pt/-promise v))))

#?(:cljd nil
   :clj
   (extend-protocol pt/IPromise
     CompletionStage
     (-fmap
       ([it f]
        (.thenApply ^CompletionStage it
                    ^Function (pu/->Function f)))

       ([it f executor]
        (.thenApplyAsync ^CompletionStage it
                         ^Function (pu/->Function f)
                         ^Executor (exec/resolve-executor executor))))

     (-mcat
       ([it f]
        (.thenCompose ^CompletionStage it
                      ^Function (pu/->Function f)))

       ([it f executor]
        (.thenComposeAsync ^CompletionStage it
                           ^Function (pu/->Function f)
                           ^Executor (exec/resolve-executor executor))))

     (-hmap
       ([it f]
        (.handle ^CompletionStage it
                 ^BiFunction (pu/->Function2 f)))

       ([it f executor]
        (.handleAsync ^CompletionStage it
                      ^BiFunction (pu/->Function2 f)
                      ^Executor (exec/resolve-executor executor))))

     (-merr
       ([it f]
        (-> ^CompletionStage it
            (.handle ^BiFunction (pu/->Function2 #(if %2 (f %2) it)))
            (.thenCompose ^Function pu/f-identity)))

       ([it f executor]
        (-> ^CompletionStage it
            (.handleAsync ^BiFunction (pu/->Function2 #(if %2 (f %2) it))
                          ^Executor (exec/resolve-executor executor))
            (.thenCompose ^Function pu/f-identity))))

     (-then
       ([it f]
        (pt/-mcat it (fn [v] (unwrap (f v)))))

       ([it f executor]
        (pt/-mcat it (fn [v] (unwrap (f v) executor)) executor)))

     (-fnly
       ([it f]
        (.whenComplete ^CompletionStage it
                       ^BiConsumer (pu/->Consumer2 f)))

       ([it f executor]
        (.whenCompleteAsync ^CompletionStage it
                            ^BiConsumer (pu/->Consumer2 f)
                            ^Executor (exec/resolve-executor executor))))

     ))

#?(:cljd nil
   :clj
   (extend-type Future
     pt/ICancellable
     (-cancel! [it]
       (.cancel it true))
     (-cancelled? [it]
       (.isCancelled it))))

#?(:cljd nil
   :clj
   (extend-type CompletableFuture
     pt/ICancellable
     (-cancel! [it]
       (.cancel it true))
     (-cancelled? [it]
       (.isCancelled it))

     pt/ICompletable
     (-resolve! [f v] (.complete f v))
     (-reject! [f v] (.completeExceptionally f v))

     pt/IState
     (-extract
       ([it]
        (try
          (.getNow it nil)
          (catch ExecutionException e
            (.getCause e))
          (catch CompletionException e
            (.getCause e))))
       ([it default]
        (try
          (.getNow it default)
          (catch ExecutionException e
            (.getCause e))
          (catch CompletionException e
            (.getCause e)))))

     (-resolved? [it]
       (and (.isDone it)
            (not (.isCompletedExceptionally it))))

     (-rejected? [it]
       (and (.isDone it)
            (.isCompletedExceptionally it)))

     (-pending? [it]
       (not (.isDone it)))))

;; NOTE: still implement for backward compatibility, but is replaced
;; with IJoinable protocol internally
#?(:cljd nil
   :clj
   (extend-protocol pt/IAwaitable
     CompletableFuture
     (-await!
       ([it]
        (.get ^CompletableFuture it))
       ([it duration]
        (let [timeout (if (instance? Duration duration)
                        (.toMillis ^Duration duration)
                        (int duration))]
          (.get ^CompletableFuture it ^long timeout TimeUnit/MILLISECONDS))))

     CompletionStage
     (-await!
       ([it]
        (pt/-await! (.toCompletableFuture ^CompletionStage it)))
       ([it duration]
        (pt/-await! (.toCompletableFuture ^CompletionStage it) duration)))

     ;; The slow path, forward IAwaitable protocol calls to the
     ;; IJoinable protocol as default implementation.
     Object
     (-await!
       ([it]
        (if (satisfies? pt/IJoinable it)
          (pt/-join it)
          (throw (IllegalArgumentException. "IJoinable protocol not implemented"))))
       ([it duration]
        (if (satisfies? pt/IJoinable it)
          (pt/-join it duration)
          (throw (IllegalArgumentException. "IJoinable protocol not implemented")))))))

#?(:cljd nil
   :clj
   (extend-protocol pt/IJoinable
     Object
     (-join
       ([it]
        (if (satisfies? pt/IAwaitable it)
          (pt/-await! it)
          (throw (IllegalArgumentException. "protocol not implemented"))))
       ([it duration]
        (if (satisfies? pt/IAwaitable it)
          (pt/-await! it duration)
          (throw (IllegalArgumentException. "protocol not implemented")))))

     CompletableFuture
     (-join
       ([it] (.get ^CompletableFuture it))
       ([it duration]
        (let [timeout (if (instance? Duration duration)
                        (.toMillis ^Duration duration)
                        (long duration))]
          (.get ^CompletableFuture it ^long timeout TimeUnit/MILLISECONDS))))

     CompletionStage
     (-join
       ([it] (pt/-join (.toCompletableFuture ^CompletionStage it)))
       ([it duration] (pt/-join (.toCompletableFuture ^CompletionStage it) duration)))))

;; --- Promise Factory

;; This code is responsible of coercing the incoming value to a valid
;; promise type. In some cases we will receive a valid promise object,
;; in this case we return it as is. This is useful when you want to
;; `then` or `map` over a plain value that can be o can not be a
;; promise object

#?(:cljd
   (extend-protocol pt/IPromiseFactory
     Object
     (-promise [v]
       (if (try
             (some? (ex-data v))
             (catch dynamic _
               false))
         (rejected v)
         (resolved v)))

     nil
     (-promise [v]
       (resolved v)))

   :clj
   (extend-protocol pt/IPromiseFactory
     CompletionStage
     (-promise [cs] cs)

     Throwable
     (-promise [e]
       (rejected e))

     Object
     (-promise [v]
       (resolved v))

     nil
     (-promise [v]
       (resolved v)))

   :cljs
   (extend-protocol pt/IPromiseFactory
     js/Error
     (-promise [e]
       (rejected e))

     default
     (-promise [v]
       (resolved v))))

;; --- Pretty printing

(defn promise->str
  [p]
   #?(:cljd "#<dart/Future[~]>" :default "#<js/Promise[~]>"))

#?(:cljs
   (extend-type js/Promise
     IPrintWithWriter
     (-pr-writer [p writer opts]
       (-write writer  #?(:cljd "#<dart/Future[~]>" :default "#<js/Promise[~]>")))))

#?(:cljs
   (extend-type impl/PromiseImpl
     IPrintWithWriter
     (-pr-writer [p writer opts]
       (-write writer (str "#<Promise["
                           (cond
                             (pt/-pending? p)   "pending"
                             (pt/-cancelled? p) "cancelled"
                             (pt/-rejected? p)  "rejected"
                             :else              "resolved")
                           ":" (hash p)
                           "]>")))))
