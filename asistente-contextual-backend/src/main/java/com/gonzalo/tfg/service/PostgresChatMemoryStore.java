package com.gonzalo.tfg.service;

import com.gonzalo.tfg.entity.ChatSessionEntity;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/*
 * Implementación persistente de ChatMemoryStore utilizando PostgreSQL.
 * Gestiona el ciclo de vida de los mensajes de chat para cada sesión de usuario.
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
    @Transactional
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String sessionId = (String) memoryId;
        String jsonMensajes = ChatMessageSerializer.messagesToJson(messages);

        ChatSessionEntity entidad = ChatSessionEntity.findById(sessionId);

        if (entidad == null) {
            entidad = new ChatSessionEntity();
            entidad.sessionId = sessionId;
        }

        // Lógica de autogeneración de título si no existe
        if (entidad.titulo == null || entidad.titulo.trim().isEmpty()) {
            String primerMensajeUsuario = messages.stream()
                    .filter(m -> m.type() == dev.langchain4j.data.message.ChatMessageType.USER)
                    .findFirst()
                    .map(m -> {
                        if (m instanceof dev.langchain4j.data.message.UserMessage userMessage) {
                            return userMessage.contents().stream()
                                    .filter(c -> c.type() == dev.langchain4j.data.message.ContentType.TEXT)
                                    .map(c -> ((dev.langchain4j.data.message.TextContent) c).text())
                                    .findFirst()
                                    .orElse("");
                        }
                        return "";
                    })
                    .orElse(null);

            if (primerMensajeUsuario != null && !primerMensajeUsuario.isEmpty()) {
                String titulo = primerMensajeUsuario.trim();
                if (titulo.length() > 30) {
                    titulo = titulo.substring(0, 30) + "...";
                }
                entidad.titulo = titulo;
            } else {
                entidad.titulo = "Sin nombre";
            }
        }

        entidad.messagesJson = jsonMensajes;
        entidad.updatedAt = LocalDateTime.now();
        entidad.persist();
    }

    @Override
    @Transactional
    public void deleteMessages(Object memoryId) {
        String sessionId = (String) memoryId;
        ChatSessionEntity.deleteById(sessionId);
    }
}
