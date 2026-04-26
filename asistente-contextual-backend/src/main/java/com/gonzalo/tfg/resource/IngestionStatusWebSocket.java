package com.gonzalo.tfg.resource;

import com.gonzalo.tfg.model.IngestionStatusEvent;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import io.quarkus.logging.Log;

@ApplicationScoped
public class IngestionStatusWebSocket {

    @Inject
    WebSocketConnection connection;

    public void onIngestionStatus(@Observes IngestionStatusEvent event) {
        String json = String.format(
                "{\"fileName\":\"%s\", \"phase\":%d, \"message\":\"%s\", \"status\":\"%s\"}",
                event.fileName(), event.phase(), event.message(), event.status());

        try {
            connection.broadcast().sendTextAndAwait(json);
        } catch (Exception e) {
            Log.error("Error al difundir estado de ingestión", e);
        }
    }
}