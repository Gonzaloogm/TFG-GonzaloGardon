package com.gonzalo.tfg.resource;

import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocket;
import com.gonzalo.tfg.service.AsistenteService;

@WebSocket(path = "/chat")
public class ChatWebSocket {

    private final AsistenteService asistenteService;

    public ChatWebSocket(AsistenteService asistenteService) {
        this.asistenteService = asistenteService;
    }

    @OnTextMessage
    public String onMessage(String message) {
        return asistenteService.chat(message);
    }
}