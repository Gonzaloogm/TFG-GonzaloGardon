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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Gestión de persistencia de chat en PostgreSQL.
 * Implementa limpieza de mensajes RAG para evitar el guardado de prompts
 * aumentados.
 */
@ApplicationScoped
public class PostgresChatMemoryStore implements ChatMemoryStore {

    @Override
    @Transactional
    public List<ChatMessage> getMessages(Object memoryId) {
        String sessionId = (String) memoryId;
        ChatSessionEntity entidad = ChatSessionEntity.findById(sessionId);

        if (entidad == null || entidad.messagesJson == null || entidad.messagesJson.isEmpty()) {
            return new ArrayList<>();
        }

        return ChatMessageDeserializer.messagesFromJson(entidad.messagesJson);
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String sessionId = (String) memoryId;
        ChatSessionEntity entity = ChatSessionEntity.findById(sessionId);

        if (entity == null) {
            entity = new ChatSessionEntity();
            entity.sessionId = sessionId;
            entity.titulo = "Sin nombre";
        }

        // LIMPIEZA QUIRÚRGICA: Cortamos por el separador de instrucciones
        List<ChatMessage> mensajesLimpios = messages.stream()
                .map(m -> {
                    if (m instanceof UserMessage userMsg) {
                        String t = userMsg.singleText();
                        if (t.contains("--- Instrucciones de respuesta ---")) {
                            // Nos quedamos solo con lo que hay ANTES del separador (tu pregunta)
                            return UserMessage.from(t.split("--- Instrucciones de respuesta ---")[0].trim());
                        }
                    }
                    return m;
                }).collect(java.util.stream.Collectors.toList());

        // Título dinámico basado en la pregunta limpia
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

    @Override
    public void deleteMessages(Object memoryId) {
        // La sesión se cierra en memoria, pero la DB mantiene el historial.
    }
}