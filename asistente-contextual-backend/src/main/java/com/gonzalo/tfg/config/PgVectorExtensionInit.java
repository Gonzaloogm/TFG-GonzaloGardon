package com.gonzalo.tfg.config;

import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Activa la extensión pgvector al arrancar.
 *
 * La imagen pgvector/pgvector trae la extensión instalada pero no activada en
 * la base de datos. El almacén de embeddings crea su tabla la primera vez que
 * se usa (con una columna de tipo vector), y en una base de datos nueva fallaba
 * con "type vector does not exist".
 */
@ApplicationScoped
public class PgVectorExtensionInit {

    @Inject
    DataSource dataSource;

    void alArrancar(@Observes StartupEvent evento) {
        try (Connection conexion = dataSource.getConnection();
             Statement sentencia = conexion.createStatement()) {
            sentencia.execute("CREATE EXTENSION IF NOT EXISTS vector");
        } catch (SQLException e) {
            // Sin permisos para crear extensiones: debe activarla un administrador de la BD
            Log.warnf("No se pudo activar la extensión pgvector (%s). "
                    + "Ejecuta 'CREATE EXTENSION vector;' en la base de datos.", e.getMessage());
        }
    }
}
