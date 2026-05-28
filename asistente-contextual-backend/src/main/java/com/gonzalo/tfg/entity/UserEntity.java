package com.gonzalo.tfg.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Entity
@Table(name = "users")
public class UserEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "username", unique = true, nullable = false)
    public String nombreUsuario;

    @Column(name = "password_hash", nullable = false)
    public String hashContrasena;

    @Column(name = "company_id", nullable = false)
    public String idEmpresa;

    @Column(name = "active", nullable = false)
    public boolean activo = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    public LocalDateTime fechaCreacion;

    @PrePersist
    protected void onCreate() {
        if (fechaCreacion == null) {
            fechaCreacion = LocalDateTime.now();
        }
    }

    public static Optional<UserEntity> findByNombreUsuario(String nombreUsuario) {
        return find("nombreUsuario", nombreUsuario).firstResultOptional();
    }
}
