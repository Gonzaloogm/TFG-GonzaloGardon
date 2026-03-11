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
 * Punto de acceso (Endpoint) REST para la monitorización y telemetría del sistema RAG.
 * Proporciona métricas operativas y verifica la integridad de los componentes del motor de búsqueda.
 * 
 * Funcionalidades clave:
 * - Diagnóstico y resolución de incidencias técnica (Troubleshooting).
 * - Validación del proceso de fragmentación (Chunking).
 * - Seguimiento del volumen de la base de conocimiento.
 * - Suministro de métricas para la evaluación del rendimiento del TFG.
 */
@Path("/api/rag")
@Produces(MediaType.APPLICATION_JSON)
public class RagStatsResource
{

    @Inject
    DocumentIngestionService servicioIngestion;

    /**
     * Recupera estadísticas agregadas de la base de conocimiento.
     * GET /api/rag/stats
     * 
     * @return Response con métricas de ficheros, fragmentos y configuración del motor.
     */
    @GET
    @Path("/stats")
    public Response obtenerEstadisticas()
    {
        try
        {
            Map<String, Object> metricas = servicioIngestion.obtenerEstadisticas();

            // Enriquecimiento con parámetros de configuración del motor RAG
            Map<String, Object> configuracion = new HashMap<>();
            configuracion.put("maxResults", 5);
            configuracion.put("minScore", 0.7);
            configuracion.put("modelo_embeddings", "text-embedding-004");
            configuracion.put("dimensiones_vectoriales", 768);

            metricas.put("configuracion_motor", configuracion);

            Log.infof("Informe de métricas RAG: %d ficheros procesados, %s fragmentos generados",
                    metricas.get("documentos_procesados"),
                    metricas.get("total_fragmentos"));

            return Response.ok(metricas).build();

        } catch (Exception e)
        {
            Log.errorf(e, "Fallo técnico al recuperar métricas operativas");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Error interno durante la consolidación de estadísticas"))
                    .build();
        }
    }

    /**
     * Verificación del estado de salud (Health Check) del sistema RAG.
     * GET /api/rag/health
     * 
     * Evalúa:
     * - Conectividad con el almacén vectorial (pgvector).
     * - Disponibilidad de la lógica de negocio.
     * - Carga inicial de datos de conocimiento.
     */
    @GET
    @Path("/health")
    public Response comprobacionEstado()
    {
        try
        {
            Map<String, Object> estado = new HashMap<>();
            estado.put("estado", "OPERATIVO");
            estado.put("marca_tiempo", System.currentTimeMillis());

            // Validación de la base de conocimiento cargada
            int numFicheros = servicioIngestion.listarDocumentos().size();
            estado.put("ficheros_cargados", numFicheros);
            estado.put("listo_para_consultas", numFicheros > 0);

            if (numFicheros == 0)
            {
                estado.put("mensaje", "Sistema activo pero carece de base de conocimiento cargada.");
            }

            return Response.ok(estado).build();

        } catch (Exception e)
        {
            Log.errorf(e, "Fallo crítico en la verificación de estado");
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of(
                            "estado", "ERROR_CRITICO",
                            "detalle", e.getMessage()))
                    .build();
        }
    }
}