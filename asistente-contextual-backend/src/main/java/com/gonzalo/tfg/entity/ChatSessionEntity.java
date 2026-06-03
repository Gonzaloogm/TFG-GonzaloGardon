package com.gonzalo.tfg.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/*
 Entidad JPA para la persistencia de sesiones de chat.
 Almacena el historial de mensajes y aísla la información por usuario.
 */
@Entity
@Table(name = "chat_sessions")
public class ChatSessionEntity extends PanacheEntityBase {

    @Id
    @Column(name = "session_id")
    public String sessionId;

    @Column(name = "user_id", nullable = false)
    public String userId;

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
     */
    public ChatSessionEntity(String sessionId, String userId, String messagesJson, String titulo) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.messagesJson = messagesJson;
        this.titulo = titulo;
        this.updatedAt = LocalDateTime.now();
    }
}