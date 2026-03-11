package com.gonzalo.tfg.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/*
 Entidad JPA para la persistencia de sesiones de chat.
 Almacena el historial de mensajes serializado en formato JSON para cada sesión de usuario.
 */
@Entity
@Table(name = "chat_sessions")
public class ChatSessionEntity extends PanacheEntityBase {

    @Id
    @Column(name = "session_id")
    public String sessionId;

    @Column(name = "messages_json", columnDefinition = "TEXT")
    public String messagesJson;

    @Column(name = "updated_at")
    public LocalDateTime updatedAt;

    @Column(name = "titulo")
    public String titulo;

    /*
     Constructor por defecto requerido por JPA.
     */
    public ChatSessionEntity() {
    }

    /*
     Constructor para inicializar una nueva sesión o actualizar una existente.
     
     @param sessionId Identificador único de la sesión (ej. ID de WebSocket).
     @param messagesJson Contenido del historial de chat en formato JSON.
     */
    public ChatSessionEntity(String sessionId, String messagesJson) {
        this.sessionId = sessionId;
        this.messagesJson = messagesJson;
        this.updatedAt = LocalDateTime.now();
    }
}
