# nebula

## Description

Nebula is a lightweight, open-source Java WebSocket server designed for local 
testing purposes, compliant with [RFC 6455](https://www.rfc-editor.org/rfc/rfc6455). 
Named after the beautiful and vast nebulae in space, it provides an easy-to-use api for
WebSocket connections, handling text and binary messages, and control frames like ping, pong, and close.

## Features

- Accepts WebSocket connections
- Handles text and binary messages
- Supports control frames (ping, pong, close)
- Simple API for sending text messages and control frames

## Installation

To include NebulaWebSocketServer in your project, clone the repository and build it using your preferred build tool.
TODO

```bash
git clone https://github.com/yourusername/NebulaWebSocketServer.git
cd NebulaWebSocketServer
./gradlew build
```

## Usage

```java
import com.coffeeclub.nebula.WebsocketServer;
import de.coffeeclub.websockettestserver.*;

public class Main {
    public static void main(String[] args) {
        MessageConsumer messageConsumer = new MessageConsumer() {
            @Override
            public void onText(WebsocketServer server, CharBuffer buffer, boolean last) {
                // Handle text message
            }

            @Override
            public void onBinary(WebsocketServer server, ByteBuffer buffer, boolean last) {
                // Handle binary message
            }

            @Override
            public void onPing(WebsocketServer server, ByteBuffer buffer) {
                // Handle ping
            }

            @Override
            public void onPong(WebsocketServer server, ByteBuffer buffer) {
                // Handle pong
            }
        };

        WebsocketServer server = new WebsocketServer(8080, messageConsumer);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
    }
}
```

## Contributing

Pull requests are welcome. For major changes, please open an issue first to discuss what you would like to change.

## License

Licensed under the GNU General Public License v2.0. See the LICENSE file for details.

## Keywords

WebSocket, Java, RFC 6455, WebSocket Server, WebSocket Client, WebSocket API, WebSocket Connection, WebSocket Message, WebSocket Frame, WebSocket Control Frame, WebSocket Text Frame, WebSocket Binary Frame, WebSocket Ping Frame, WebSocket Pong Frame, WebSocket Close Frame, WebSocket Handshake, WebSocket Protocol, WebSocket RFC, WebSocket RFC 6455, WebSocket RFC 6455 Compliant, WebSocket RFC 6455 Implementation, WebSocket RFC 6455 Server, WebSocket RFC 6455 Client, WebSocket RFC 6455 API, WebSocket RFC 6455 Connection, WebSocket RFC 6455 Message, WebSocket RFC 6455 Frame, WebSocket RFC 6455 Control Frame, WebSocket RFC 6455 Text Frame, WebSocket RFC 6455 Binary Frame, WebSocket RFC 6455 Ping Frame, WebSocket RFC 6455 Pong Frame, WebSocket RFC 6455 Close Frame, WebSocket RFC 6455 Handshake, WebSocket RFC 6455 Protocol
