;; Copyright © 2019 State Street Bank and Trust Company.  All rights reserved
;; Copyright © 2019-2022 Manetu, Inc.  All rights reserved
;;
;; SPDX-License-Identifier: Apache-2.0

(ns protojure.pedestal.interceptors.grpc
  "A [Pedestal](http://pedestal.io/) [interceptor](http://pedestal.io/reference/interceptors) for [GRPC](https://grpc.io/) support"
  (:require [clojure.core.async :refer [go >! <!] :as async]
            [protojure.grpc.codec.lpm :as lpm]
            [protojure.grpc.status :as status]
            [promesa.core :as p]
            [io.pedestal.interceptor :as pedestal]
            [io.pedestal.interceptor.error :as err]
            [io.pedestal.log :as log]
            [protojure.grpc.status :as grpc.status])
  (:import [java.nio ByteBuffer]))

(set! *warn-on-reflection* true)

(def ^{:const true :no-doc true} supported-encodings (-> protojure.grpc.codec.compression/builtin-codecs (keys) (conj "identity") (set)))

(defn- determine-output-encoding
  [accepted-encodings]
  (->> (clojure.string/split accepted-encodings #",")
       (filter supported-encodings)
       (first)))

(defn- create-req-ctx
  [f {:keys [body-ch] {:strs [grpc-encoding] :or {grpc-encoding "identity"}} :headers :as req}]
  (let [in body-ch
        out (async/chan 128)]
    {:in       in
     :out      out
     :encoding grpc-encoding
     :status   (lpm/decode f in out {:content-coding grpc-encoding})}))

(defn- create-resp-ctx
  [f {{:strs [grpc-accept-encoding] :or {grpc-accept-encoding ""}} :headers :as req}]
  (let [in (async/chan 128)
        out (async/chan 128)
        encoding (or (determine-output-encoding grpc-accept-encoding) "identity")]
    {:in       in
     :out      out
     :encoding encoding
     :status   (lpm/encode f in out {:content-coding encoding :max-frame-size 16383})}))

(defn- set-params [context params]
  (assoc-in context [:request :grpc-params] params))

(defn- grpc-enter
  "<enter> interceptor for handling GRPC requests"
  [{:keys [server-streaming client-streaming input output] :as rpc-metadata}
   {:keys [request] :as context}]
  (let [req-ctx (create-req-ctx input request)
        resp-ctx (create-resp-ctx output request)
        input-ch (:out req-ctx)
        context (-> context
                    (assoc ::ctx {:req-ctx req-ctx :resp-ctx resp-ctx})
                    (cond-> server-streaming
                      (assoc-in [:request :grpc-out] (:in resp-ctx))))]

    ;; set :grpc-params
    (if client-streaming
      (set-params context input-ch)                         ;; client-streaming means simply pass the channel directly
      (if-let [params (async/poll! input-ch)]
        (set-params context params)                         ;; materialize unary params opportunistically,  if available
        (go (set-params context (<! input-ch)))))))         ;; else, defer context until unary params materialize

(defn- take-promise [ch]
  (p/create
   (fn [resolve reject]
     (async/take! ch resolve))))

(defn- put [ch val]
  (p/create
   (fn [resolve reject]
     (async/put! ch val resolve))))

(defn- ->trailers
  [{:keys [grpc-status grpc-message] :or {grpc-status 0}}]
  (-> {"grpc-status" grpc-status}
      (cond-> (some? grpc-message) (assoc "grpc-message" grpc-message))))

(defn- prepare-trailers [{:keys [trailers] :as response}]
  (let [ch (async/promise-chan)]
    [ch (fn [_] (-> (if (some? trailers)
                      (take-promise trailers)
                      response)
                    (p/then ->trailers)
                    (p/then (partial put ch))))]))

(defn- grpc-leave
  "<leave> interceptor for handling GRPC responses"
  [{:keys [server-streaming] :as rpc-metadata}
   {{:keys [body] :as response} :response {:keys [req-ctx resp-ctx]} ::ctx :as context}]

  (let [output-ch (:in resp-ctx)
        [trailers-ch trailers-fn] (prepare-trailers response)]

    (cond
      ;; special-case unary return types
      (not server-streaming)
      (do
        (async/>!! output-ch (or body {}))
        (async/close! output-ch))

      ;; Auto-close the output ch if the user does not signify they have consumed it
      ;; by referencing it in the :body
      (not= output-ch body)
      (async/close! output-ch))

    ;; defer sending trailers until our IO has completed
    (-> (p/all (mapv :status [req-ctx resp-ctx]))
        (p/then trailers-fn)
        (p/timeout 30000)
        (p/catch (fn [ex]
                   (log/error :msg "Pipeline" :exception ex)
                   (status/error :internal))))

    (update context :response
            #(assoc %
                    :headers  {"Content-Type" "application/grpc+proto"
                               "grpc-encoding" (:encoding resp-ctx)}
                    :status   200                ;; always return 200
                    :body     (:out resp-ctx)
                    :trailers trailers-ch))))

(defn route-interceptor
  [rpc-metadata]
  (pedestal/interceptor {:name ::interceptor
                         :enter (partial grpc-enter rpc-metadata)
                         :leave (partial grpc-leave rpc-metadata)}))

(defn- err-status
  [ctx status msg]
  (update ctx :response
          assoc
          :headers {"Content-Type" "application/grpc+proto"}
          :status 200
          :body ""
          :trailers (->trailers {:grpc-status status :grpc-message msg})))

(def error-interceptor
  (err/error-dispatch
   [ctx ex]

   [{:exception-type ::status/error}]
   (let [{:keys [code msg]} (ex-data ex)]
     (err-status ctx code msg))

   :else
   (err-status ctx (grpc.status/get-code :internal) (ex-message ex))))
