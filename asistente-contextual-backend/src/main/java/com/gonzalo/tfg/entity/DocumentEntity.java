package com.gonzalo.tfg.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * Entidad JPA para persistir el catálogo de documentos en PostgreSQL.
 * Sustituye al almacenamiento volátil en memoria y al catálogo JSON.
 */
@Entity
@Table(name = "documents")
public class DocumentEntity extends PanacheEntityBase {

    @Id
    public String id;

    @Column(nullable = false)
    public String nombre;

    @Column(nullable = false)
    public String tipo;

    @Column(columnDefinition = "TEXT")
    public String contenido;

    @Column(name = "metadatos_json", columnDefinition = "TEXT")
    public String metadatosJson;

    @Column(name = "fecha_carga")
    public LocalDateTime fechaCarga;

    @Column(name = "tamanio_bytes")
    public Long tamanioBytes;

    public DocumentEntity() {
    }

    public DocumentEntity(String id, String nombre, String tipo, String contenido, String metadatosJson, Long tamanioBytes) {
        this.id = id;
        this.nombre = nombre;
        this.tipo = tipo;
        this.contenido = contenido;
        this.metadatosJson = metadatosJson;
        this.fechaCarga = LocalDateTime.now();
        this.tamanioBytes = tamanioBytes;
    }
}
