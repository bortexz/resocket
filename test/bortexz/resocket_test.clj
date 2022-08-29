(ns bortexz.resocket-test
  (:require [clojure.test :refer [deftest testing is]]
            [org.httpkit.server :as http-kit]
            [clojure.core.async :as a]
            [bortexz.resocket :as rs])
  (:import (java.nio ByteBuffer)))

(do
  ;; Borrowed from https://github.com/gnarroway/hato/blob/master/test/hato/websocket_test.clj
  (defn ws-handler
    "Fake WebSocket handler with overrides."
    [{:keys [on-receive on-ping on-close init]} req]
    (when init (init req))
    (http-kit/with-channel req ch
      (http-kit/on-receive ch #(when on-receive (on-receive ch %)))
      (http-kit/on-ping ch #(when on-ping (on-ping ch %)))
      (http-kit/on-close ch #(when on-close (on-close ch %)))))

  (defmacro with-ws-server
    "Spins up a local WebSocket server with http-kit."
    [opts & body]
    `(let [s# (http-kit/run-server (partial ws-handler ~opts) {:port 1234})]
       (try ~@body
            (finally
              (s# :timeout 100))))))

(def ws-url "ws://localhost:1234")

(deftest test-connection
  (with-ws-server {:on-receive #(http-kit/send! %1 %2)}
    (testing "can establish a connection and send messages"
      (let [{:keys [input output closed]} (a/<!! (rs/connection ws-url {}))]
        (a/>!! output "Hello World")
        (is (= "Hello World" (a/<!! input)))
        (a/close! output)
        (is (= :on-close (:close-type (a/<!! closed)))))))
  
  (testing "correctly gets exception through ch when failed"
    (Thread/sleep 300)
    (let [{:keys [error]} (a/<!! (rs/connection "ws://localhost:1111" {}))]
      (is (true? (instance? java.net.ConnectException error))))))

(deftest test-keep-alive
  (let [pingp (promise)]
    (with-ws-server {:on-ping (fn [_ch _] (deliver pingp true))}
      (testing "sends ping every interval"
        (let [{:keys [output]} (a/<!! (rs/connection ws-url {:ping-interval 100}))]
          (Thread/sleep 150)
          (is (true? @pingp))
          (a/close! output)))))

  (with-ws-server {}
    (testing "Will close the connection when no ping ack"
      (let [{:keys [closed]}
            (a/<!! (rs/connection ws-url {::rs/-ping! (fn [_ws _data] (atom nil))
                                          :ping-interval 100
                                          :ping-timeout 100}))]
        (is (= :abort (:close-type (a/<!! closed))))))))

(deftest close-timeout
  (with-ws-server {}
    (testing "Will abort the connection after trying to close gracefully"
      (let [{:keys [closed output]}
            (a/<!! (rs/connection ws-url {::rs/-close! (fn [_ws] (atom nil))
                                          :close-timeout 100}))]
        (a/close! output)
        (is (= :abort (:close-type (a/<!! closed))))))))

(deftest handlers
  (testing "Handlers properly accumulate their messages"
    (let [text (a/chan 1)
          binary (a/chan 1)
          handle-text (#'rs/text-handler text parse-long)
          handle-binary (#'rs/binary-handler binary vec)]
      (handle-text "1" false)
      (handle-text "23" true)
      (is (= 123 (a/<!! text)))

      (handle-binary (ByteBuffer/wrap (byte-array [1 2 3])) false)
      (handle-binary (ByteBuffer/wrap (byte-array [4 5 6])) true)
      (is (= [1 2 3 4 5 6] (a/<!! binary))))))

(deftest reconnector
  (testing "base test"
    (with-ws-server {:on-receive #(http-kit/send! %1 %2)}
      (let [{:keys [connections close]} (rs/reconnector {:get-url (constantly ws-url)})
            {:keys [input output]} (a/<!! connections)]
        (a/>!! output "Hello World")
        (is (= "Hello World" (a/<!! input)))
        (a/close! close)
        (is (nil? (a/<!! input)))
        (is (nil? (a/<!! connections))))))
  
  (testing "Creates new connections"
    (with-ws-server {:on-receive #(http-kit/send! %1 %2)}
      (let [{:keys [connections close]} (rs/reconnector {:get-url (constantly ws-url)})
            {:keys [output]} (a/<!! connections)
            _ (is (some? output))
            _ (a/close! output)
            
            {:keys [output]} (a/<!! connections)
            _ (is (some? output))
            _ (a/close! output)
            
            {:keys [closed]} (a/<!! connections)]
        (a/close! close)
        (a/<!! closed)
        (is (nil? (a/<!! connections))))))
  
  (testing "retries"
    (let [retries (atom 0)
          {:keys [connections]} (rs/reconnector {:get-url (constantly ws-url)
                                                 :retry-ms-fn (fn [_att] (swap! retries inc) 100)
                                                 :on-error-retry-fn? (fn [_err] (< @retries 3))})]
      (Thread/sleep 500)
      (is (= 3 @retries))
      (is (nil? (a/<!! connections))))))

