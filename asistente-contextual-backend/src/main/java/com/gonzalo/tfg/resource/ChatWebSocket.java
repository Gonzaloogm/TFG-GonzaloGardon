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
    WebSocketConnection conexion;

    public ChatWebSocket(AsistenteService asistenteService) {
        this.asistenteService = asistenteService;
    }

    /**
     * Gestiona la recepción de mensajes de texto desde el cliente.
     * 
     * @param mensaje Contenido textual de la petición del usuario.
     * @return Respuesta procesada por el motor de IA, libre de trazas de
     *         razonamiento interno.
     */
    @OnTextMessage
    public String alRecibirMensaje(String mensaje) {
        String idSesion = conexion.id();
        String respuesta = asistenteService.chat(idSesion, mensaje);

        // Eliminación de las etiquetas de razonamiento interno (Chain of Thought) para
        // la entrega al usuario
        return respuesta.replaceAll("(?s)<thinking>.*?</thinking>\\s*", "");
    }
}