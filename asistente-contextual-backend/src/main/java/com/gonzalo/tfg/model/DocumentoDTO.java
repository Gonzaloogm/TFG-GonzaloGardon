package com.gonzalo.tfg.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Objeto de Transferencia de Datos (DTO) que representa un fichero procesado en el sistema RAG.
 * Integra la información semántica extraída junto con sus atributos técnicos y contextuales.
 * 
 * Ámbitos de aplicación:
 * - Transferencia de estado en la capa de persistencia (Fase de Ingestión).
 * - Estructura de respuesta en el API REST (/api/documentos).
 * - Seguimiento y trazabilidad de los recursos de conocimiento.
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
     * Constructor auxiliar para la creación ágil de registros con metadatos predeterminados.
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
     * Constructor especializado para la integración de ficheros con metadatos contextuales.
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