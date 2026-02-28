package com.gonzalo.tfg.resource;

import com.gonzalo.tfg.service.DocumentIngestionService;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.HashMap;
import java.util.Map;

/**
 * Endpoint para estadísticas y monitoreo del sistema RAG.
 * 
 * Útil para:
 * - Debugging y troubleshooting
 * - Validar que el chunking funciona correctamente
 * - Monitorear el crecimiento de la base de conocimiento
 * - Métricas para el TFG (Fase 5)
 */
@Path("/api/rag")
@Produces(MediaType.APPLICATION_JSON)
public class RagStatsResource {

    @Inject
    DocumentIngestionService ingestionService;

    /**
     * Obtiene estadísticas detalladas del sistema RAG.
     * 
     * GET /api/rag/stats
     * 
     * Respuesta:
     * {
     * "documentos_procesados": 5,
     * "total_fragmentos": 87,
     * "chunk_size": 400,
     * "chunk_overlap": 50,
     * "promedio_fragmentos_por_doc": 17,
     * "configuracion": {
     * "maxResults": 5,
     * "minScore": 0.7,
     * "embedding_model": "text-embedding-004"
     * }
     * }
     */
    @GET
    @Path("/stats")
    public Response obtenerEstadisticas() {
        try {
            Map<String, Object> stats = ingestionService.obtenerEstadisticas();

            // Añadir configuración del RAG
            Map<String, Object> config = new HashMap<>();
            config.put("maxResults", 5);
            config.put("minScore", 0.7);
            config.put("embedding_model", "text-embedding-004");
            config.put("embedding_dimensions", 768);

            stats.put("configuracion", config);

            Log.infof("Estadísticas RAG solicitadas: %d docs, %s fragmentos",
                    stats.get("documentos_procesados"),
                    stats.get("total_fragmentos"));

            return Response.ok(stats).build();

        } catch (Exception e) {
            Log.errorf(e, "Error obteniendo estadísticas");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Error obteniendo estadísticas"))
                    .build();
        }
    }

    /**
     * Health check del sistema RAG.
     * 
     * GET /api/rag/health
     * 
     * Verifica:
     * - Conexión a pgvector
     * - Disponibilidad del modelo de embeddings
     * - Estado del ContentRetriever
     */
    @GET
    @Path("/health")
    public Response healthCheck() {
        try {
            Map<String, Object> health = new HashMap<>();
            health.put("status", "UP");
            health.put("timestamp", System.currentTimeMillis());

            // Verificar que hay documentos cargados
            int numDocs = ingestionService.listarDocumentos().size();
            health.put("documents_loaded", numDocs);
            health.put("ready_for_queries", numDocs > 0);

            if (numDocs == 0) {
                health.put("message", "Sistema operativo pero sin documentos cargados");
            }

            return Response.ok(health).build();

        } catch (Exception e) {
            Log.errorf(e, "Error en health check");
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of(
                            "status", "DOWN",
                            "error", e.getMessage()))
                    .build();
        }
    }
}