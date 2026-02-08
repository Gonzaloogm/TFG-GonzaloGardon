package com.gonzalo.tfg.resource;

import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocket;

@WebSocket(path = "/chat")
public class ChatWebSocket {

    @OnTextMessage
    public String onMessage(String message) {
        // Lógica de chat
        return "Echo: " + message;
    }
}