package com.gonzalo.tfg.config;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;

/**
 * Produce el modelo de chat en streaming de Gemini.
 *
 * La extensión quarkus-langchain4j-ai-gemini 1.0.2 solo ofrece el modelo
 * síncrono, así que el de streaming se construye a mano con la misma clave y
 * parámetros de generación. El modelo es distinto: el de streaming es el principal
 * (app.gemini.modelo-chat) y el síncrono, uno ligero para tareas auxiliares.
 */
@ApplicationScoped
public class StreamingModelConfig {

    @ConfigProperty(name = "quarkus.langchain4j.ai.gemini.api-key")
    String apiKey;

    @ConfigProperty(name = "app.gemini.modelo-chat")
    String modelo;

    @ConfigProperty(name = "quarkus.langchain4j.ai.gemini.chat-model.temperature", defaultValue = "0.7")
    double temperatura;

    @ConfigProperty(name = "quarkus.langchain4j.ai.gemini.chat-model.max-output-tokens", defaultValue = "2048")
    int maxTokens;

    @ConfigProperty(name = "quarkus.langchain4j.ai.gemini.chat-model.top-p", defaultValue = "0.95")
    double topP;

    @ConfigProperty(name = "quarkus.langchain4j.ai.gemini.chat-model.timeout", defaultValue = "PT100S")
    Duration timeout;

    @Produces
    @ApplicationScoped
    public StreamingChatModel modeloStreaming() {
        return GoogleAiGeminiStreamingChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelo)
                .temperature(temperatura)
                .maxOutputTokens(maxTokens)
                .topP(topP)
                .timeout(timeout)
                .build();
    }
}
