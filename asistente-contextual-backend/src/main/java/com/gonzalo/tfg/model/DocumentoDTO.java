package com.gonzalo.tfg.model;

import java.time.LocalDateTime;
import java.util.Map;

/**
 Representa un documento en el sistema de gestión de conocimiento

 Este modelo se usa en el Módulo de Ingestión (según arquitectura)
 antes de ser procesado, fragmentado y vectorizado
 */
public record DocumentoDTO(
        String id,
        String nombre,
        String tipo, // PDF, DOCX, TXT, etc.
        String contenido, // Texto extraído
        Map<String, String> metadatos, // Autor, departamento, fecha, etc
        LocalDateTime fechaCarga,
        Long tamanioBytes) {

    /**
     Constructor de conveniencia para documentos simples
     */
    public DocumentoDTO(String nombre, String tipo, String contenido) {
        this(
                java.util.UUID.randomUUID().toString(),
                nombre,
                tipo,
                contenido,
                Map.of(),
                LocalDateTime.now(),
                (long) contenido.length());
    }

    /**
     Verifica si el documento tiene metadatos específicos
     */
    public boolean tieneMetadato(String clave) {
        return metadatos != null && metadatos.containsKey(clave);
    }

    /**
     Obtiene un metadato específico
     */
    public String obtenerMetadato(String clave) {
        return metadatos != null ? metadatos.get(clave) : null;
    }
}
