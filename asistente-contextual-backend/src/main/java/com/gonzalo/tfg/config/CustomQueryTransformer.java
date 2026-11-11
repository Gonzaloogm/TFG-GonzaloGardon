package com.gonzalo.tfg.config;

import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collection;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class CustomQueryTransformer implements QueryTransformer {

    private static final Logger Log = Logger.getLogger(CustomQueryTransformer.class);

    @Inject
    dev.langchain4j.model.chat.ChatModel chatModel;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Caché de textos generados (HyDE + variaciones) por pregunta.
     * Se guardan solo los textos, no los objetos Query: cada Query lleva los
     * metadatos de su conversación (empresa incluida) y no debe reutilizarse
     * entre usuarios. Tamaño limitado para no crecer sin control.
     */
    private static final int MAX_ENTRADAS_CACHE = 500;
    private final ConcurrentHashMap<String, List<String>> cache = new ConcurrentHashMap<>();

    @Override
    public Collection<Query> transform(Query query) {
        String originalText = query.text();

        // Saludos y preguntas por el catálogo no buscan en documentos (el enrutador
        // las descarta): generar variantes sería una llamada al LLM desperdiciada.
        if (ClasificadorConsulta.noNecesitaBusqueda(originalText)) {
            Log.debug("CustomQueryTransformer: consulta sin búsqueda documental, no se transforma.");
            return List.of(query);
        }

        // 1. Verificación en caché (Opcional pero recomendado)
        List<String> enCache = cache.get(originalText);
        if (enCache != null) {
            Log.debug("CustomQueryTransformer: Usando caché para la consulta.");
            return construirQueries(query, enCache);
        }

        Log.debug("CustomQueryTransformer: Generando HyDE y Multi-Query para: " + originalText);

        // Pedimos una respuesta hipotética y 3 variaciones en una SÓLA llamada
        String prompt = "Genera una respuesta hipotética y 3 variaciones de búsqueda para esta consulta: \""
                + originalText + "\". Devuélvelo en formato JSON con la siguiente estructura exacta:\n" +
                "{\n" +
                "  \"respuesta_hipotetica\": \"texto de la respuesta\",\n" +
                "  \"variaciones\": [\"variacion1\", \"variacion2\", \"variacion3\"]\n" +
                "}\n" +
                "Solo devuelve el JSON, sin texto adicional.";

        String jsonResponse = null;
        int maxRetries = 3;
        long backoff = 2000; // Inicia en 2s

        // 2. Exponential Backoff manual para lidiar con errores 429
        for (int i = 0; i <= maxRetries; i++) {
            try {
                jsonResponse = chatModel.chat(prompt);
                break; // Éxito, salimos del bucle
            } catch (Exception e) {
                if (i == maxRetries) {
                    Log.error(
                            "CustomQueryTransformer: Falló tras " + maxRetries + " reintentos. Usando query original.",
                            e);
                    return List.of(query);
                }
                String msg = e.getMessage() != null ? e.getMessage() : "";
                if (msg.contains("429") || msg.contains("Too Many Requests") || msg.contains("quota")) {
                    Log.warn("CustomQueryTransformer: Error 429 API Rate Limit. Reintentando en " + (backoff / 1000)
                            + " segundos...");
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return List.of(query);
                    }
                    backoff *= 2; // Exponential backoff: 2s, 4s, 8s
                } else {
                    Log.error("CustomQueryTransformer: Error inesperado en el LLM. Usando query original.", e);
                    return List.of(query);
                }
            }
        }

        // 3. Parseo y empaquetado de resultados
        List<String> textosGenerados = new ArrayList<>();

        try {
            if (jsonResponse != null && !jsonResponse.isBlank()) {
                // Limpiar posibles etiquetas markdown de código que añade el LLM
                String cleanJson = jsonResponse.replaceAll("```json", "").replaceAll("```", "").trim();
                JsonNode root = objectMapper.readTree(cleanJson);

                if (root.has("respuesta_hipotetica")) {
                    String hyde = root.get("respuesta_hipotetica").asText();
                    if (!hyde.isBlank()) {
                        textosGenerados.add(hyde);
                    }
                }

                if (root.has("variaciones") && root.get("variaciones").isArray()) {
                    for (JsonNode varNode : root.get("variaciones")) {
                        String var = varNode.asText();
                        if (!var.isBlank()) {
                            textosGenerados.add(var);
                        }
                    }
                }
                Log.debug("CustomQueryTransformer: Generadas " + textosGenerados.size() + " queries aumentadas.");
            }
        } catch (Exception e) {
            Log.error("CustomQueryTransformer: Error parseando JSON: " + jsonResponse, e);
        }

        // Guardar en caché antes de devolver
        if (cache.size() >= MAX_ENTRADAS_CACHE) {
            cache.clear();
        }
        cache.put(originalText, List.copyOf(textosGenerados));
        return construirQueries(query, textosGenerados);
    }

    /**
     * Construye las queries finales: la original más las generadas, todas con
     * los metadatos de la conversación actual (memoryId, historial...).
     */
    private static Collection<Query> construirQueries(Query original, List<String> textos) {
        List<Query> queries = new ArrayList<>();
        queries.add(original);
        for (String texto : textos) {
            queries.add(original.metadata() != null ? Query.from(texto, original.metadata()) : Query.from(texto));
        }
        return queries;
    }
}
