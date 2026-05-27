package com.gonzalo.tfg.config;

import com.gonzalo.tfg.service.PostgresChatMemoryStore;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

/*
 Configuración de la memoria del chat para LangChain4j.
 Provee un mecanismo para que el asistente recuerde el contexto de la conversación
 basándose en una ventana deslizante de mensajes persistidos.
 */
@ApplicationScoped
public class ChatMemoryConfig {

    @Inject
    PostgresChatMemoryStore chatMemoryStore;

    /*
     Define el proveedor de memoria para los servicios de IA.
     Configura una ventana de 20 mensajes para optimizar el uso de tokens sin perder contexto relevante.
     
     @return ChatMemoryProvider configurado con persistencia en PostgreSQL.
     */
    @Produces
    public ChatMemoryProvider chatMemoryProvider() {
        return sessionId -> MessageWindowChatMemory.builder()
                .id(sessionId)
                .maxMessages(10)
                .chatMemoryStore(chatMemoryStore)
                .build();
    }
}
