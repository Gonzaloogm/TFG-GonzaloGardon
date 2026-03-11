package com.gonzalo.tfg.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

/*
 Objeto de Transferencia de Datos (DTO) para el resumen de sesiones de chat.
 Se utiliza para listar las conversaciones disponibles sin cargar el historial completo de mensajes.
 */
public record ChatSessionSummaryDTO(
    @JsonProperty("sessionId")
    String sessionId,
    
    @JsonProperty("updatedAt")
    LocalDateTime updatedAt,

    @JsonProperty("titulo")
    String titulo
) {}
