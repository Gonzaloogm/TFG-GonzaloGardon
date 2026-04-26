package com.gonzalo.tfg.service;

import com.gonzalo.tfg.entity.ChatSessionEntity;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Servicio de gestión del ciclo de vida de las sesiones de chat.
 *
 * Centraliza la lógica de eliminación garantizando:
 * 1. Borrado atómico del registro en chat_sessions (mensajes incluidos, pues
 *    se almacenan como JSON en la misma fila — no hay entidad hija separada).
 * 2. Limpieza del store de memoria de LangChain4j para que la sesión
 *    eliminada no vuelva a rehidratarse desde la BD.
 * 3. Purga defensiva de vectores en pgvector filtrados por chat_session_id,
 *    en previsión de que futuras funcionalidades indexen fragmentos de chat.
 */
@ApplicationScoped
public class ChatService {

    @Inject
    PostgresChatMemoryStore chatMemoryStore;

    @Inject
    EmbeddingStore<TextSegment> storeVectores;

    /**
     * Elimina una sesión de chat completa de forma transaccional.
     *
     * @param sessionId Identificador único de la sesión.
     * @return {@code true} si existía y fue eliminada; {@code false} si no existía.
     */
    @Transactional
    public boolean eliminarSesion(String sessionId) {

        // 1. Borrar la fila de chat_sessions (incluye el JSON de mensajes)
        boolean eliminado = ChatSessionEntity.deleteById(sessionId);

        if (!eliminado) {
            Log.warnf("Solicitud de borrado ignorada: sesión '%s' no encontrada en BD.", sessionId);
            return false;
        }

        Log.infof("Sesión de chat '%s' eliminada de la base de datos.", sessionId);

        // 2. Limpiar el store en memoria de LangChain4j para que no rehidrate
        //    la sesión borrada en llamadas posteriores al mismo sessionId.
        try {
            chatMemoryStore.deleteMessages(sessionId);
            Log.infof("Memoria en caché limpiada para sesión '%s'.", sessionId);
        } catch (Exception e) {
            // No crítico: la fila ya fue borrada; solo impide rehidratación fantasma.
            Log.warnf(e, "No se pudo limpiar la memoria en caché para sesión '%s'.", sessionId);
        }

        // 3. Purga defensiva de vectores en pgvector asociados al chatId.
        //    En la arquitectura actual los chats no generan embeddings, pero este
        //    filtro actuará automáticamente si se añade esa funcionalidad en el futuro.
        try {
            Filter filtroChatId = MetadataFilterBuilder
                    .metadataKey("chat_session_id")
                    .isEqualTo(sessionId);
            storeVectores.removeAll(filtroChatId);
            Log.infof("Purga de vectores ejecutada para sesión '%s'.", sessionId);
        } catch (Exception e) {
            // No crítico: puede no haber vectores para este chatId.
            Log.debugf(e, "Purga de vectores sin efecto para sesión '%s' (esperado si el chat no generó embeddings).", sessionId);
        }

        return true;
    }
}
