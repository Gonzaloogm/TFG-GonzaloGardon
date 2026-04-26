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

    private final AsistenteService asistenteService;

    @Inject
    WebSocketConnection conexion;

    public ChatWebSocket(AsistenteService asistenteService) {
        this.asistenteService = asistenteService;
    }

    /*
     * Al abrir la conexión WebSocket envía al cliente el sessionId canónico
     * (el del path param), que es exactamente la clave primaria en chat_sessions.
     * Esto evita que el frontend use connection.id() — un UUID interno de Quarkus
     * que difiere en cada conexión y nunca coincide con la clave de la BD.
     */
    @OnOpen
    public String alAbrir(@PathParam("sessionId") String sessionId) {
        Log.infof("WebSocket abierto. sessionId canónico: %s | connectionId interno: %s",
                sessionId, conexion.id());
        return "{\"type\":\"session_ready\",\"sessionId\":\"" + sessionId + "\"}";
    }

    /*
     * Gestiona la recepción de mensajes de texto desde el cliente.
     *
     * @param sessionId Identificador de la sesión de chat (clave primaria en BD).
     * @param mensaje   Contenido textual de la petición del usuario.
     * @return Respuesta del motor de IA sin trazas de razonamiento interno.
     */
    @OnTextMessage
    public String alRecibirMensaje(@PathParam("sessionId") String sessionId, String mensaje) {
        String respuesta = asistenteService.chat(sessionId, mensaje);
        return respuesta.replaceAll("(?s)<thinking>.*?</thinking>\\s*", "");
    }
}