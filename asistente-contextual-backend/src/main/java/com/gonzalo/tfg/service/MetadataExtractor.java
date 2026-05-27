package com.gonzalo.tfg.service;

import io.quarkiverse.langchain4j.RegisterAiService;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.SystemMessage;

@RegisterAiService(chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
public interface MetadataExtractor {

        @SystemMessage("Especialista en indexación. Extrae metadatos puros. No converses. No busques en internet.")
        @UserMessage("""
                        Analiza este fragmento. Responde ÚNICAMENTE con este formato exacto, sin texto adicional:
                        ENTIDADES: valor1, valor2
                        CATEGORIA: valor
                        RESUMEN: máximo 10 palabras

                        Fragmento: {text}
                        """)
        String extraerEntidades(String text);

        /**
         * Parsea la salida cruda del LLM y devuelve un mapa con las claves
         * "entidades", "categoria" y "resumen" separadas.
         * Si el LLM no respeta el formato, devuelve el texto completo en "entidades"
         * como fallback para no perder la información.
         */
        static java.util.Map<String, String> parsear(String salidaLLM) {
                java.util.Map<String, String> resultado = new java.util.HashMap<>();
                if (salidaLLM == null || salidaLLM.isBlank())
                        return resultado;

                for (String lineaCruda : salidaLLM.split("\\r?\\n")) {
                        String linea = lineaCruda.replace("**", "")
                                        .replace("*", "")
                                        .replace("`", "")
                                        .trim();

                        String lineaMayus = linea.toUpperCase();

                        if (lineaMayus.startsWith("ENTIDADES:")) {
                                resultado.put("entidades", linea.substring(lineaMayus.indexOf(':') + 1).trim());
                        } else if (lineaMayus.startsWith("CATEGORIA:")) {
                                resultado.put("categoria", linea.substring(lineaMayus.indexOf(':') + 1).trim());
                        } else if (lineaMayus.startsWith("RESUMEN:")) {
                                resultado.put("resumen", linea.substring(lineaMayus.indexOf(':') + 1).trim());
                        }
                }

                // Fallback: si el LLM no respetó el formato, guarda todo en entidades
                if (resultado.isEmpty()) {
                        resultado.put("entidades", salidaLLM.trim());
                }

                return resultado;
        }
}