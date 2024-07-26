package com.coffeeclub.nebula;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;

public interface MessageConsumer {
    void onBinary(WebsocketServer server, ByteBuffer buffer, boolean last);

    void onText(WebsocketServer server, CharBuffer buffer, boolean last);

    void onPing(WebsocketServer server, ByteBuffer buffer);

    void onPong(WebsocketServer server, ByteBuffer buffer);

}
