# resocket

WebSocket client for Clojure wrapping the JDK11 WebSocket Client.

## Features

- core.async API with `input` and `output` channels for receiving/sending messages, and `closed` promise-chan with details about the closing of the connection.
- Automatically send `ping` frames to the server at given intervals, abort connections that do not `pong` within a certain timeout.
- Close a connection by closing `output` channel, abort within a certain timeout if `on-close` is not received from the server.
- Specify `input` and `output` parsers to take/put your prefered data structure on the channels (maps, vecs, ...) and use the parser for json/edn stringify, etc.
- Minimal dependencies, just `clojure` and `core.async`.
- `reconnector` process to create new connections when the previous ones have been closed, returning a core.async `connections` channel to take new connections from.

## Usage

See the docs for more detailed information about all the options available.

### Quick introduction

Connection:
```Clojure
(let [{:keys [input output closed error]} (a/<!! (resocket/connection "ws://<service>" {}))]
    (when-not error
      (a/<!! input) ;; Take new messages
      (a/>!! output "Hi from client") ;; Send messages
      (a/close! output) ;; Close connection
      (a/<!! closed)) ;; {:close-type :on-close :status 1000 :description ""}
    )
```

Reconnector:
```Clojure
(let [{:keys [connections close]} (resocket/reconnector {:get-url (constantly "ws://<service>")})]
    (a/go-loop []
      (when-let [conn (a/<! connections)] ;; get new connections until reconnector closed
        (loop []
          (when-let [v (a/<! (:input conn))]
            ;; Process messages
            (recur)))
        (recur)))
    ;; Somewhere else, when tired of receiving new connections
    (a/close! close) ; Closes current connection (if any) and the reconnector
  )
```

## Credits
- [hato](https://github.com/gnarroway/hato) as this library borrows some code from hato's websocket client.

## License

Copyright © 2022 Alberto Fernandez

Distributed under the Eclipse Public License version 1.0.
