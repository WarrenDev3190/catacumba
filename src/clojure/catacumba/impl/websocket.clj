;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
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

(ns catacumba.impl.websocket
  "Websocket handler adapter implementation."
  (:require [clojure.core.async :as a]
            [promesa.core :as p]
            [catacumba.impl.helpers :as hp]
            [catacumba.impl.context :as ctx]
            [catacumba.impl.handlers :as hs])
  (:import ratpack.handling.Handler
           ratpack.handling.Context
           ratpack.func.Action
           ratpack.exec.ExecController
           java.util.concurrent.ExecutorService
           io.netty.buffer.ByteBuf
           catacumba.websocket.WebSocketHandler
           catacumba.websocket.WebSocketMessage
           catacumba.websocket.WebSockets
           catacumba.websocket.WebSocket))

(defn- send!
  [ws data]
  (let [c (a/chan)]
    (p/then (.send ws data)
            (fn [_]
              (a/close! c)))
    c))

(deftype WebSocketSession [in out ctrl context handler]
  java.io.Closeable
  (close [_]
    (a/close! in)
    (a/close! out)
    (a/close! ctrl))

  WebSocketHandler
  (^void onOpen [this ^WebSocket ws]
   (let [^Context ctx (:catacumba/context context)
         context (assoc context
                        :in in :out out :ctrl ctrl
                        :ws ws :wssession this)]
     (a/go-loop []
       (if-let [value (a/<! out)]
         (do
           (a/<! (send! ws (str value)))
           (recur))
         (do
           (a/put! ctrl [:close nil])
           (hp/try-on (.close ws))
           (hp/try-on (.close this)))))
     (try
       (handler context)
       (catch Throwable e
         (a/put! ctrl [:error e])
         (hp/try-on (.close ws))
         (hp/try-on (.close this))))))

  (^void onMessage [_ ^WebSocketMessage msg ^Action callback]
   (a/put! in (.getData msg) (fn [_] (.execute callback nil))))

  (^void onClose [this]
   (a/put! ctrl [:close nil])
   (.close this)))

(defn websocket
  [context handler]
  (let [in (a/chan 16)
        out (a/chan 16)
        ctrl (a/chan (a/sliding-buffer 1))]
    (->> (WebSocketSession. in out ctrl context handler)
         (WebSockets/websocket ^Context (:catacumba/context context)))))

(defmethod hs/adapter :catacumba/websocket
  [handler]
  (reify Handler
    (^void handle [_ ^Context ctx]
     (let [context (ctx/create-context ctx)]
       (websocket context handler)))))
