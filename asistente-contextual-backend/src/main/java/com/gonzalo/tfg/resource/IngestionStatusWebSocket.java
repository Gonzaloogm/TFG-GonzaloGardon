package com.gonzalo.tfg.resource;

import com.gonzalo.tfg.model.IngestionStatusEvent;
import io.quarkus.logging.Log;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OpenConnections;
import io.quarkus.websockets.next.WebSocket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

@WebSocket(path = "/ingestion-status")
@ApplicationScoped
public class IngestionStatusWebSocket {

    /*
     * OpenConnections es un bean @ApplicationScoped que mantiene el registro
     * de todas las conexiones WebSocket activas. A diferencia de
     * WebSocketConnection
     * (@SessionScoped), no requiere un contexto de sesión activo, por lo que
     * puede usarse de forma segura desde hilos de ingestión o observadores CDI.
     */
    @Inject
    OpenConnections openConnections;

    @OnOpen
    public void onOpen() {
        Log.infof("Cliente conectado a /ingestion-status. Conexiones activas: %d",
                openConnections.listAll().size());
    }

    public void onIngestionStatus(@Observes IngestionStatusEvent event) {
        if (openConnections.listAll().isEmpty()) {
            Log.debugf("Sin clientes suscritos a /ingestion-status, evento descartado: fase %d", event.phase());
            return;
        }

        String json = String.format(
                "{\"fileName\":\"%s\",\"phase\":%d,\"message\":\"%s\",\"status\":\"%s\"}",
                escaparJson(event.fileName()),
                event.phase(),
                escaparJson(event.message()),
                escaparJson(event.status()));

        openConnections.listAll().forEach(conn -> {
            try {
                conn.sendTextAndAwait(json);
            } catch (Exception e) {
                Log.warnf("No se pudo notificar a la conexión %s: %s", conn.id(), e.getMessage());
            }
        });
    }

    private static String escaparJson(String valor) {
        if (valor == null)
            return "";
        return valor.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}