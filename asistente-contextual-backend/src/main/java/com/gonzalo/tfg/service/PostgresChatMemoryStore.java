package com.gonzalo.tfg.service;

import com.gonzalo.tfg.entity.ChatSessionEntity;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import io.quarkus.logging.Log;
import org.eclipse.microprofile.jwt.JsonWebToken;
import jakarta.inject.Inject;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class PostgresChatMemoryStore implements ChatMemoryStore {

    @Inject
    JsonWebToken jwt;

    /**
     * Asegura que el identificador de sesión sea texto.
     *
     * @param memoryId Identificador que da LangChain4j.
     * @return El identificador como texto.
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

        List<ChatMessage> allMessages = ChatMessageDeserializer.messagesFromJson(entidad.messagesJson);
        return filtrarToolResultsAnteriores(allMessages);
    }

    /**
     * Borra los resultados antiguos de herramientas del historial.
     * Esto evita que la IA se confunda con búsquedas anteriores.
     *
     * @param messages Todos los mensajes del chat.
     * @return Los mensajes limpios sin resultados viejos.
     */
    private List<ChatMessage> filtrarToolResultsAnteriores(List<ChatMessage> messages) {
        if (messages.isEmpty()) {
            return messages;
        }

        // Busca dónde empezó el último uso de herramientas
        int inicioUltimoTurno = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage msg = messages.get(i);
            if (msg instanceof AiMessage aiMsg && aiMsg.hasToolExecutionRequests()) {
                inicioUltimoTurno = i;
                break;
            }
        }

        // Crea una lista nueva sin los resultados viejos
        List<ChatMessage> resultado = new ArrayList<>(messages.size());
        int filtrados = 0;

        for (int i = 0; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);

            // Si es el uso actual de herramientas, lo guardamos
            if (inicioUltimoTurno != -1 && i >= inicioUltimoTurno) {
                resultado.add(msg);
                continue;
            }

            // Si es un resultado antiguo de herramienta, lo ignoramos
            if (msg instanceof ToolExecutionResultMessage) {
                filtrados++;
                continue;
            }

            resultado.add(msg);
        }

        if (filtrados > 0) {
            Log.debugf("getMessages: filtrados %d ToolExecutionResultMessage de turnos anteriores (sesión: %s)",
                    filtrados, messages.size());
        }

        return resultado;
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String sessionId = resolverSessionId(memoryId);
        ChatSessionEntity entity = ChatSessionEntity.findById(sessionId);

        if (entity == null) {
            entity = new ChatSessionEntity();
            entity.sessionId = sessionId;
            entity.titulo = "Nueva conversación";
            entity.userId = (jwt != null && jwt.getName() != null) ? jwt.getName() : "usuario_desconocido";
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
     * Petición para borrar el historial de una sesión.
     *
     * @param memoryId Identificador de la sesión.
     */
    @Override
    public void deleteMessages(Object memoryId) {
        String sessionId = resolverSessionId(memoryId);
        Log.debugf("deleteMessages invocado para sesión '%s' — eliminación de BD ya gestionada por ChatService.",
                sessionId);
    }
}