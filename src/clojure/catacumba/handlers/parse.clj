;; Copyright (c) 2015 Andrey Antukh <niwi@niwi.nz>
;; All rights reserved.
;;
;; Redistribution and use in source and binary forms, with or without
;; modification, are permitted provided that the following conditions are met:
;;
;; * Redistributions of source code must retain the above copyright notice, this
;;   list of conditions and the following disclaimer.
;;
;; * Redistributions in binary form must reproduce the above copyright notice,
;;   this list of conditions and the following disclaimer in the documentation
;;   and/or other materials provided with the distribution.
;;
;; THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
;; AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
;; IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
;; DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
;; FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
;; DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
;; SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
;; CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
;; OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
;; OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

(ns catacumba.handlers.parse
  (:require [catacumba.impl.context :as ctx]
            [promesa.core :as p]
            [cheshire.core :as json]
            [cognitect.transit :as transit]
            [clojure.edn :as edn])
  (:import ratpack.http.TypedData
           ratpack.handling.Context
           (java.io InputStreamReader
                    PushbackReader)))

(defmulti parse-body
  "A polymorophic method for parse body into clojure
  friendly data structure."
  (fn [^Context ctx ^TypedData body]
    (let [^String contenttype (.. body getContentType getType)]
      (if contenttype
        (keyword (.toLowerCase contenttype))
        :application/octet-stream)))
  :default :application/octet-stream)

(defmethod parse-body :multipart/form-data
  [^Context ctx ^TypedData body]
  (ctx/get-formdata* ctx body))

(defmethod parse-body :application/x-www-form-urlencoded
  [^Context ctx ^TypedData body]
  (ctx/get-formdata* ctx body))

(defmethod parse-body :application/json
  [^Context ctx ^TypedData body]
  (let [^String data (slurp body)]
    (json/parse-string data true)))

(defmethod parse-body :application/octet-stream
  [^Context ctx ^TypedData body]
  nil)

(defmethod parse-body :application/transit+json
  [^Context ctx ^TypedData body]
  (let [reader (transit/reader (.getInputStream body) :json)]
    (transit/read reader)))

(defmethod parse-body :application/transit+msgpack
  [^Context ctx ^TypedData body]
  (let [reader (transit/reader (.getInputStream body) :msgpack)]
    (transit/read reader)))

(defmethod parse-body :application/edn
  [^Context ctx ^TypedData body]
  (edn/read {:eof nil :readers *data-readers*}
            (-> (.getInputStream body)
                (InputStreamReader. "UTF-8")
                (PushbackReader.))))

;; --- Handlers

(defn read-body
  "A handler that just reads the body from request and puts
  it on the context under `:body` key."
  [{:keys [method] :as context}]
  (if (= method :get)
    (ctx/delegate {:body nil})
    (->> (ctx/get-body! context)
         (p/map (fn [^TypedData body]
                  (ctx/delegate {:body body}))))))

(defn body-params
  "A route chain that parses the body into
  a clojure friendly data structure.

  This function optionally accept a used defined method
  or multimethod where to delegate the body parsing."
  ([] (body-params nil))
  ([{:keys [parsefn attr] :or {parsefn parse-body attr :data}}]
   (fn [{:keys [method body] :as context}]
     (let [ctx (ctx/get-context* context)]
       (cond
         (= method :get)
         (ctx/delegate {attr nil})

         body
         (ctx/delegate {attr (parsefn ctx body)})

         (nil? body)
         (->> (ctx/get-body! ctx)
              (p/map (fn [^TypedData body]
                       (ctx/delegate {attr (parsefn ctx body) :body body})))))))))
