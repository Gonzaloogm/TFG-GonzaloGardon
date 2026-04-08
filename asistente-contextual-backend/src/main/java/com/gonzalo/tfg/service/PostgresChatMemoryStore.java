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

        List<ChatMessage> cleanMessages = messages.stream()
                .map(msg -> {
                    if (msg instanceof UserMessage userMsg) {
                        String text = userMsg.singleText();
                        if (text.contains("Responde utilizando EXCLUSIVAMENTE")) {
                            // Cortamos el prompt inyectado y nos quedamos solo con la solicitud del usuario
                            String originalQuery = text.split("Responde utilizando EXCLUSIVAMENTE")[0].trim();
                            return UserMessage.from(originalQuery);
                        }
                    }
                    return msg;
                })
                .collect(Collectors.toList());

        if (("Sin nombre".equals(entity.titulo) || entity.titulo == null) && !cleanMessages.isEmpty()) {
            for (ChatMessage msg : cleanMessages) {
                if (msg.type() == ChatMessageType.USER) {
                    String texto = ((UserMessage) msg).singleText();
                    entity.titulo = texto.length() > 25 ? texto.substring(0, 25) + "..." : texto;
                    break;
                }
            }
        }

        // Guardamos la lista LIMPIA, no la original que tiene el RAG
        entity.messagesJson = ChatMessageSerializer.messagesToJson(cleanMessages);
        entity.updatedAt = LocalDateTime.now();
        entity.persist();
    }

    @Override
    public void deleteMessages(Object memoryId) {
        // La sesión se cierra en memoria, pero la DB mantiene el historial.
    }
}