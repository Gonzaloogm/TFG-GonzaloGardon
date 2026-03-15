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
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String sessionId = (String) memoryId;
        ChatSessionEntity entity = ChatSessionEntity.findById(sessionId);

        if (entity == null) {
            entity = new ChatSessionEntity();
            entity.sessionId = sessionId;
            entity.titulo = "Sin nombre"; // Título por defecto al abrir la web
        }

        if (("Sin nombre".equals(entity.titulo) || entity.titulo == null) && !messages.isEmpty()) {
            for (ChatMessage msg : messages) {
                if (msg.type() == dev.langchain4j.data.message.ChatMessageType.USER) {

                    String textoUsuario = ((dev.langchain4j.data.message.UserMessage) msg).singleText();

                    if (textoUsuario.contains("Responde utilizando EXCLUSIVAMENTE")) {
                        textoUsuario = textoUsuario.split("Responde utilizando EXCLUSIVAMENTE")[0].trim();
                    }

                    entity.titulo = textoUsuario.length() > 25 ? textoUsuario.substring(0, 25) + "..." : textoUsuario;
                    break;
                }
            }
        }

        // Guardamos el JSON y actualizamos la fecha/hora
        entity.messagesJson = ChatMessageSerializer.messagesToJson(messages);
        entity.updatedAt = LocalDateTime.now();

        entity.persist();
    }

    @Override
    public void deleteMessages(Object memoryId) {
        System.out.println("Cerrando sesión en memoria (Historial conservado en DB para: " + memoryId + ")");
    }
}
