(ns bortexz.resocket
  (:require [clojure.core.async :as a])
  (:import (java.net.http WebSocket$Listener WebSocket$Builder HttpClient WebSocket)
           (java.io ByteArrayOutputStream)
           (java.nio.channels Channels)
           (java.nio ByteBuffer)
           (java.time Duration)
           (java.net URI ConnectException)))

;;
;; Impl
;;

(defprotocol -Sendable
  "Implementation detail. Protocol for sending different message types."
  (-send [data ws]))

(extend-protocol -Sendable
  (Class/forName "[B")
  (-send [data ^WebSocket ws]
    (.sendBinary ws (ByteBuffer/wrap data) true))
  CharSequence
  (-send [data ^WebSocket ws]
    (.sendText ws data true)))

(defn- signal-closed
  [{:keys [input output closed]} {:keys [ping pong]} close-args]
  (a/put! closed close-args)
  (run! a/close! [input output ping pong]))

(defn- abort
  [conn ka-chs {::keys [-abort!]}]
  (-abort! (:ws conn))
  (signal-closed conn ka-chs {:close-type :abort}))

(defn- text-handler
  [input parse-input]
  (let [sb (StringBuilder.)]
    (fn [data last?]
      (.append sb data)
      (when last?
        (a/>!! input (parse-input (.toString sb)))
        (.setLength sb 0)
        nil))))

(defn- binary-handler
  [input parse-input]
  (let [baos (ByteArrayOutputStream.)
        baosc (Channels/newChannel baos)]
    (fn [data last?]
      (.write baosc data)
      (when last?
        (a/>!! input (parse-input (.toByteArray baos)))
        (.reset baos))
      nil)))

(defn- build-listener
  [{:keys [input] :as conn}
   {:keys [pong] :as ka-chs}
   {:keys [input-parser]}]
  (let [handle-text (text-handler input input-parser)
        handle-binary (binary-handler input input-parser)]
    (reify WebSocket$Listener
      (onText [_ ws data last?]
        (handle-text data last?)
        (.request ws 1)
        nil)
      (onBinary [_ ws data last?]
        (handle-binary data last?)
        (.request ws 1)
        nil)
      (onPong [_ ws data]
        (a/>!! pong data)
        (.request ws 1)
        nil)
      (onClose [_ _ status description]
        (signal-closed conn ka-chs {:close-type :on-close
                                    :status status
                                    :description description})
        nil)
      (onError [_ _ err]
        (signal-closed conn ka-chs {:close-type :on-error
                                    :error err})
        nil))))

(defn- send-process
  [{:keys [output closed ws] :as conn}
   {:keys [ping] :as ka-chs}
   {:keys [output-parser ex-handler close-timeout] ::keys [-ping! -close! -send!] :as opts}]
  (a/thread
    (loop []
      (let [[v p] (a/alts!! [ping output])]
        (when (not (.isOutputClosed ^WebSocket ws))
          (try
            (condp = p
              output
              (if (some? v)
                @(-send! ws (output-parser v))
                (do @(-close! ws)
                    (when (pos-int? close-timeout)
                      (a/alt!!
                        [closed (a/timeout close-timeout)]
                        ([_ p] (when-not (= p closed)
                                 (abort conn ka-chs opts)))))))
              
              ping
              @(-ping! ws v))
            (catch Exception e
              (ex-handler
               (ex-info "Error sending output"
                        {:val v
                         :port (if (= p ping) :ping :output)
                         :error e}))))
          (recur))))))

(defn- heartbeat-process
  [conn
   {:keys [ping pong] :as ka-chs}
   {:keys [ping-interval ping-timeout ping-payload] :as opts}]
  (a/go-loop [pending-pong? false
              timer (a/timeout ping-interval)]
    (a/alt!
      [timer pong]
      ([v p]
       (condp = p
         timer
         (if pending-pong?
           (abort conn ka-chs opts)
           (do
             (a/>! ping ping-payload)
             (if ping-timeout
               (recur true (a/timeout ping-timeout))
               (recur false (a/timeout ping-interval)))))

         pong
         (when (some? v)
           (recur false (a/timeout ping-interval))))))))

(def ^:private default-connection-opts
  {:ping-interval 30000
   :ping-timeout 10000
   :ping-payload (byte-array [])
   :connect-timeout 30000
   :close-timeout 10000
   :input-parser identity
   :output-parser identity
   :ex-handler (fn [ex]
                 (-> (Thread/currentThread)
                     .getUncaughtExceptionHandler
                     (.uncaughtException (Thread/currentThread) ex))
                 nil)})

(def ^:private -default-websocket-fns
  {::-ping! (fn [ws data] (.sendPing ^WebSocket ws (ByteBuffer/wrap data)))
   ::-close! (fn [ws] (.sendClose ^WebSocket ws WebSocket/NORMAL_CLOSURE ""))
   ::-send! (fn [ws data] (-send data ws))
   ::-abort! (fn [ws] (.abort ^WebSocket ws))})

(defn- with-headers
  ^WebSocket$Builder [builder headers]
  (reduce-kv
   (fn [^WebSocket$Builder b ^String hk ^String hv]
     (.header b hk hv))
   builder
   headers))

(defn- create-ws
  [url {:keys [http-client connect-timeout headers subprotocols listener]}]
  (let [^HttpClient http-client (if (instance? HttpClient http-client)
                                  http-client
                                  (HttpClient/newHttpClient))]
    (cond-> (.newWebSocketBuilder http-client)
      connect-timeout    (.connectTimeout (Duration/ofMillis connect-timeout))
      (seq subprotocols) (.subprotocols (first subprotocols) (into-array String (rest subprotocols)))
      headers            (with-headers headers)
      true               (.buildAsync (URI/create url) listener))))

;;
;; Public API
;;

(defn connection
  "creates a websocket connection given `url` and (optional) `opts`.
   
   If successful, returns a map of:
   - `ws` wrapped jdk11 websocket.
   - `input` chan of received messages. Closed when the connection closes.
   - `output` chan to send messages, and close the connection (closing the chan). Closed when connection is closed.
   - `closed` promise-chan that will be delivered once the connection is closed with a map of:
     - `close-type` either `#{:on-close :on-error :abort}`. `:abort` is used when aborting connection through
       heartbeat-timeout or close-timeout elapsed (see `opts` below).
     - (when close-type :on-close) `status` and `description`
     - (when close-type :on-error) `error`
   
   If an exception happens when opening a connection, returns a map of:
   - `error` the exception that was thrown
   
   Available opts:
   - `ping-interval` milliseconds interval to send ping frames to the server. Defaults 30000 (30 secs).
   - `ping-timeout` if ellapsed milliseconds without receiving a pong, aborts the connection. Defaults 10000 (10 secs).
   - `ping-payload` ping frame byte-array. Defaults to empty byte-array.
   - `http-client` java.http.HttpClient instance to use, it will create a default one if not specified.
   - `connect-timeout` timeout in milliseconds to wait for establish a connection. Defaults to 30000 (30 secs)
   - `headers` map string->string with headers to use in http-client
   - `subprotocols` coll of string subprotocols to use
   - `input-buf` int/buffer to use on input chan. Unbuffered by default.
   - `output-buf` int/buffer to use on output chan. Unbuffered by default.
   - `input-parser` unary fn that will be called with complete messages received (either byte-array or string) and 
     parses it before putting it into input chan. Defaults to identity.
   - `output-parser` unary fn that will be called with output messages before sending them through the websocket. Must
     return a String (for sending text) or a byte-array (for sending binary). Defaults to identity.
   - `close-timeout` (optional) millisecond to wait before aborting the connection after closing the output. Defaults to
     10000 (10 secs).
   - `ex-handler` (optional) unary fn called with errors happening on internal calls to the jdk11 websocket. Defaults to
     use uncaught exception handler. Errors are wrapped in an ExceptionInfo that contains :val, :port and :error on its
     data."
  ([url] (connection url {}))
  ([url {:keys [ping-interval
                ping-timeout
                ping-payload
                http-client
                connect-timeout
                headers
                subprotocols
                input-buf
                output-buf
                input-parser
                output-parser
                close-timeout
                ex-handler]
         :as opts}]
   (let [opts (merge default-connection-opts -default-websocket-fns opts)
         input (a/chan input-buf)
         output (a/chan output-buf)

         ping (a/chan)
         pong (a/chan (a/dropping-buffer 1))

         closed (a/promise-chan)

         conn {:input input
               :output output
               :closed closed}

         ka-chs {:ping ping
                 :pong pong}]
     (a/thread
       (try
         (let [ws @(create-ws url (merge opts {:listener (build-listener conn ka-chs opts)}))
               conn (assoc conn :ws ws)]
           (send-process conn ka-chs opts)
           (when ping-interval (heartbeat-process conn ka-chs opts))
           conn)
         (catch Exception e
           {:error (ex-cause e)}))))))

(defn reconnector
  "Creates a reconnector process that will create a new connection when the previous one has been closed. If creating
   a new connection fails, then function `on-error-retry-fn?` will be called with the error, and if it does not return
   true, the reconnector will be closed. If it returns true, then `retry-ms-fn` is called with the current attempt,
   and must return an integer or nil. If returns integer, reconnector will wait that amount of milliseconds to retry a 
   new connection. If it returns nil, the reconnector will be closed.
   
   Returns map of:
   - `connections` unbuffered chan of new connections. Will be closed when the reconnector is closed.
   - `close` promise-chan that will close the reconnector when delivered or closed. It will close currently active 
     connection if there's one.
   
   Available opts:
   - `retry-ms-fn` unary fn called with the current attempt at reconnecting, starting at 1. It must return a numer of 
     milliseconds to wait before retrying a new connection, or nil to close the reconnector. Defaults to 5000 (5 secs).
   - `on-error-retry-fn?` unary fn called with error when creating a new connection. Must return true to start a retry
     timeout to retry, otherwise the reconnector will be closed. Note: It is called with the original Exception unwrapped,
     which will probably be an ExecutionException from the CompletableFuture returned by creating a new Websocket in JDK11.
     You might want to get the wrapped exception for more details `(ex-cause <error>)`. Defaults to returning true 
     when the wrapped exception is a java.net.ConnectException, false otherwise."
  [{:keys [retry-ms-fn on-error-retry-fn? get-url get-opts]
    :or {retry-ms-fn (constantly 5000)
         on-error-retry-fn? (fn [err] (instance? ConnectException (ex-cause err)))
         get-opts (constantly nil)}}]
  (let [connections (a/chan)
        close (a/promise-chan)]
    (a/go-loop [retry-att 0 
                conn nil]
      (if (some? conn)
        (let [{conn-closed :closed conn-output :output} conn
              [_ p] (a/alts! [conn-closed close])]
          (condp = p
            conn-closed (recur 0 nil)
            close (do (a/close! conn-output)
                      (a/<! conn-closed)
                      (a/close! connections))))
        (let [retry-ms (if (pos? retry-att) (retry-ms-fn retry-att) 0)
              timer? (pos? retry-ms)
              retry? (or (and (not timer?) retry-ms (zero? retry-ms))
                         (and timer? (a/alt! [(a/timeout retry-ms) close] ([_ p] (not= p close)))))]
          (if retry?
            (let [{:keys [error] :as conn} (a/<! (connection (get-url) (get-opts)))]
              (if-not error
                (do (a/>! connections conn)
                    (recur 0 conn))
                (if (on-error-retry-fn? error)
                  (recur (inc retry-att) nil)
                  (a/close! connections))))
            (a/close! connections)))))
    {:connections connections
     :close close}))
