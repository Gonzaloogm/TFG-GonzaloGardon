package com.gonzalo.tfg.service;

import io.quarkiverse.langchain4j.RegisterAiService;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.SystemMessage;

@RegisterAiService
public interface MetadataExtractor {

    @SystemMessage("""
            Eres un especialista en indexación de datos de mercado.
            Tu única función es recibir un texto y devolver metadatos puros.
            No converses. No busques en internet. Solo extrae.
            """)
    @UserMessage("""
            Analiza este fragmento y extrae exclusivamente estos 3 campos separados por pipe (|):
            ENTIDADES: (Empresas, Tecnologías o Personas clave)
            CATEGORIA: (Tendencia, Competencia o Normativa)
            RESUMEN: (Máximo 10 palabras)

            Fragmento: {text}
            """)
    String extraerEntidades(String text);
}