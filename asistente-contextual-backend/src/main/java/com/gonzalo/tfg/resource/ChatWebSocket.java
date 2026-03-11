package com.gonzalo.tfg.resource;

import io.quarkus.websockets.next.PathParam;
import io.quarkus.websockets.next.OnTextMessage;
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
     * Gestiona la recepción de mensajes de texto desde el cliente.
     * 
     * @param sessionId Identificador de la sesión de chat.
     * @param mensaje Contenido textual de la petición del usuario.
     * 
     * @return Respuesta procesada por el motor de IA, libre de trazas de
     * razonamiento interno.
     */
    @OnTextMessage
    public String alRecibirMensaje(@PathParam("sessionId") String sessionId, String mensaje) {
        String respuesta = asistenteService.chat(sessionId, mensaje);

        // Eliminación de las etiquetas de razonamiento interno (Chain of Thought) para
        // la entrega al usuario
        return respuesta.replaceAll("(?s)<thinking>.*?</thinking>\\s*", "");
    }
}