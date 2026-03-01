package com.gonzalo.tfg.resource;

import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import com.gonzalo.tfg.service.AsistenteService;
import jakarta.inject.Inject;

@WebSocket(path = "/chat")
public class ChatWebSocket {

    private final AsistenteService asistenteService;

    @Inject
    WebSocketConnection connection;

    public ChatWebSocket(AsistenteService asistenteService) {
        this.asistenteService = asistenteService;
    }

    @OnTextMessage
    public String onMessage(String message) {
        String sessionId = connection.id();
        return asistenteService.chat(sessionId, message);
    }
}