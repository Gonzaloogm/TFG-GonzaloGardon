package com.gonzalo.tfg.resource;

import com.gonzalo.tfg.model.IngestionStatusEvent;

import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import io.quarkus.logging.Log;

/**
 * Endpoint de WebSocket para la notificación de estados de ingestión de
 * documentos.
 * 
 * Emplea el Broadcaster Global de WebSockets Next para asegurar la entrega de
 * mensajes
 * desde hilos que no poseen contexto de petición activo.
 */
@WebSocket(path = "/ingestion-status")
@ApplicationScoped
public class IngestionStatusWebSocket {

    @Inject
    WebSocketConnection connection;

    @OnOpen
    public void onOpen() {
        Log.info("Client connected to ingestion-status WebSocket");
    }

    /**
     * Observa eventos de cambio de estado de ingestión y los difunde a todos los
     * clientes suscritos.
     * 
     * @param event Evento de estado disparado por DocumentIngestionService.
     */
    public void onIngestionStatus(@Observes IngestionStatusEvent event) {
        String json = String.format(
                "{\"fileName\":\"%s\", \"phase\":%d, \"message\":\"%s\", \"status\":\"%s\"}",
                event.fileName(), event.phase(), event.message(), event.status());

        try {
            // broadcast() no requiere una conexión "actual" (Self), lo que lo hace
            // ideal para disparar mensajes desde observadores CDI.
            connection.broadcast().sendTextAndAwait(json);
        } catch (Exception e) {
            Log.error("Fallo al difundir el estado de ingestión vía WebSocket", e);
        }
    }
}