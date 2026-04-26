package com.gonzalo.tfg.service;

import com.gonzalo.tfg.entity.ChatSessionEntity;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import io.quarkus.logging.Log;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class PostgresChatMemoryStore implements ChatMemoryStore {

    /**
     * Convierte el memoryId que LangChain4j proporciona a String de forma segura.
     *
     * LangChain4j no garantiza que memoryId sea siempre un String: en contextos
     * CDI sin petición activa (hilos de Vert.x, observadores) puede llegar como
     * un objeto de scope interno de Quarkus. El cast directo (String) memoryId
     * producía el toString() del RequestContextState en lugar del UUID real,
     * causando que todas las búsquedas en BD fallaran silenciosamente.
     */
    private static String resolverSessionId(Object memoryId) {
        if (memoryId instanceof String s)
            return s;
        if (memoryId instanceof UUID u)
            return u.toString();
        throw new IllegalArgumentException(
                "memoryId de tipo inesperado: " + memoryId.getClass().getName() + " = " + memoryId);
    }

    @Override
    @Transactional
    public List<ChatMessage> getMessages(Object memoryId) {
        String sessionId = resolverSessionId(memoryId);
        ChatSessionEntity entidad = ChatSessionEntity.findById(sessionId);

        if (entidad == null || entidad.messagesJson == null || entidad.messagesJson.isEmpty()) {
            return new ArrayList<>();
        }

        return ChatMessageDeserializer.messagesFromJson(entidad.messagesJson);
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String sessionId = resolverSessionId(memoryId);
        ChatSessionEntity entity = ChatSessionEntity.findById(sessionId);

        if (entity == null) {
            entity = new ChatSessionEntity();
            entity.sessionId = sessionId;
            entity.titulo = "Sin nombre";
        }

        List<ChatMessage> mensajesLimpios = messages.stream()
                .map(m -> {
                    if (m instanceof UserMessage userMsg) {
                        String t = userMsg.singleText();
                        if (t.contains("--- Instrucciones de respuesta ---")) {
                            return UserMessage.from(t.split("--- Instrucciones de respuesta ---")[0].trim());
                        }
                    }
                    return m;
                }).collect(Collectors.toList());

        if (("Sin nombre".equals(entity.titulo) || entity.titulo == null) && !mensajesLimpios.isEmpty()) {
            for (ChatMessage msg : mensajesLimpios) {
                if (msg.type() == ChatMessageType.USER) {
                    String txt = ((UserMessage) msg).singleText();
                    entity.titulo = txt.length() > 30 ? txt.substring(0, 30) + "..." : txt;
                    break;
                }
            }
        }

        entity.messagesJson = ChatMessageSerializer.messagesToJson(mensajesLimpios);
        entity.updatedAt = LocalDateTime.now();
        entity.persist();
    }

    /**
     * LangChain4j llama a este método para que el store limpie su caché interna.
     * La eliminación real de la fila en BD la gestiona
     * ChatService.eliminarSesion(),
     * que ya llama a ChatSessionEntity.deleteById() dentro de su propia
     * transacción.
     * Repetir el deleteById() aquí causaba una doble eliminación: la primera
     * devolvía true y la segunda false, haciendo que ChatService reportara
     * erróneamente que la sesión no existía.
     */
    @Override
    public void deleteMessages(Object memoryId) {
        String sessionId = resolverSessionId(memoryId);
        Log.debugf("deleteMessages invocado para sesión '%s' — eliminación de BD ya gestionada por ChatService.",
                sessionId);
    }
}