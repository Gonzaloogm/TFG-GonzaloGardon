package com.gonzalo.tfg.resource;

import io.quarkus.logging.Log;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.PathParam;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import com.gonzalo.tfg.service.AsistenteService;
import jakarta.inject.Inject;
import io.smallrye.common.annotation.Blocking;

@WebSocket(path = "/chat/{sessionId}")
@Blocking
public class ChatWebSocket {

    @Inject
    AsistenteService asistenteService;

    /*
     * CORRECCIÓN: WebSocketConnection ya NO se inyecta como campo @SessionScoped.
     *
     * El problema original: al inyectar WebSocketConnection como campo de la clase,
     * Quarkus crea un proxy @SessionScoped visible en todo el contexto CDI de la
     * aplicación. Cuando una petición REST (DELETE /api/chats/{id}) se ejecuta sin
     * sesión WebSocket activa, el proxy no puede resolverse y devuelve su
     * toString()
     * —un RequestContextState— como valor, que se propagaba hasta ChatService y la
     * BD.
     *
     * La solución es recibir WebSocketConnection como parámetro de los métodos
     * 
     * @OnOpen y @OnTextMessage. Quarkus WebSockets Next lo inyecta automáticamente
     * en ese caso sin crear un proxy @SessionScoped global.
     */

    @OnOpen
    public String alAbrir(@PathParam("sessionId") String sessionId, WebSocketConnection conexion) {
        Log.infof("WebSocket abierto. sessionId canónico: %s | connectionId interno: %s",
                sessionId, conexion.id());
        return "{\"type\":\"session_ready\",\"sessionId\":\"" + sessionId + "\"}";
    }

    @OnTextMessage
    public String alRecibirMensaje(@PathParam("sessionId") String sessionId, String mensaje) {
        String respuesta = asistenteService.chat(sessionId, mensaje);
        return respuesta.replaceAll("(?s)<thinking>.*?</thinking>\\s*", "");
    }
}