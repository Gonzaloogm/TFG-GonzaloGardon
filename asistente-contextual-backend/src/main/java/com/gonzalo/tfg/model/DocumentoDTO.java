package com.gonzalo.tfg.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * DTO (Data Transfer Object) para documentos procesados.
 * Representa un documento que ha sido ingerido en el sistema RAG.
 * Contiene tanto los metadatos del documento como información sobre
 * su procesamiento (fecha de carga, tamaño, etc.).
 * Este DTO se usa para:
 * - Respuestas del API REST (/api/documentos)
 * - Almacenamiento temporal en memoria (será entidad JPA en Fase 3)
 * - Tracking de documentos procesados
 */
public record DocumentoDTO(
        @JsonProperty("id")
        String id,

        @JsonProperty("nombre")
        String nombre,

        @JsonProperty("tipo")
        String tipo,

        @JsonProperty("contenido")
        String contenido,

        @JsonProperty("metadatos")
        Map<String, String> metadatos,

        @JsonProperty("fecha_carga")
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime fechaCarga,

        @JsonProperty("tamanio_bytes")
        Long tamanioBytes
)
{
    /**
     * Constructor simplificado para crear un documento
     * con metadatos mínimos.
     */
    public DocumentoDTO(String nombre, String tipo, String contenido)
    {
        this(
                UUID.randomUUID().toString(),
                nombre,
                tipo,
                contenido,
                Map.of(),
                LocalDateTime.now(),
                (long) contenido.length()
        );
    }

    /**
     * Constructor para documentos con metadatos personalizados.
     */
    public DocumentoDTO(
            String nombre,
            String tipo,
            String contenido,
            Map<String, String> metadatos
    )
    {
        this(
                UUID.randomUUID().toString(),
                nombre,
                tipo,
                contenido,
                metadatos,
                LocalDateTime.now(),
                (long) contenido.length()
        );
    }
}