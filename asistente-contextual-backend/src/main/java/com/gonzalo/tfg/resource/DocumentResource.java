package com.gonzalo.tfg.resource;

import com.gonzalo.tfg.model.DocumentoDTO;
import com.gonzalo.tfg.service.DocumentIngestionService;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.jboss.resteasy.reactive.RestForm;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;

/**
 * Punto de acceso (Endpoint) REST para la gestión y administración de ficheros.
 * Integra el "Módulo de Ingestión" según el diseño arquitectónico del sistema.
 * Responsable de la recepción de flujos de datos vía HTTP y su delegación al motor de ingestión.
 *
 * Protocolo de operación:
 * 1. Recepción del fichero mediante petición POST multipart en /api/documentos/upload.
 * 2. Validación técnica de formato (PDF, DOCX, TXT).
 * 3. Validación de restricciones de tamaño (límite establecido en 10MB).
 * 4. Invocación del servicio DocumentIngestionService para su procesamiento semántico.
 * 5. Notificación del resultado con el identificador del recurso generado.
 */
@Path("/api/documentos")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DocumentResource
{

    @Inject
    DocumentIngestionService ingestionService;

    // Tipos de archivo permitidos
    private static final Set<String> TIPOS_PERMITIDOS = Set.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", // DOCX
            "application/msword", // DOC
            "text/plain"
    );

    // Tamaño máximo: 10MB
    private static final long TAMANIO_MAXIMO = 10 * 1024 * 1024;

    @POST
    @Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response subirDocumento(
            @RestForm("file") FileUpload ficheroCargado,
            @RestForm("metadatos") String metadatos
    )
    {
        Log.infof("Petición de subida recibida: %s", ficheroCargado.fileName());

        try
        {
            // 1. Verificación de la integridad del fichero en la petición
            if (ficheroCargado == null || ficheroCargado.fileName() == null || ficheroCargado.fileName().isBlank()) {
                Log.warn("Intento de subida sin contenido de fichero");
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new ErrorResponse("Es obligatorio adjuntar un fichero de datos"))
                        .build();
            }

            // 2. Validación del tipo MIME (Multipurpose Internet Mail Extensions)
            String tipoMime = ficheroCargado.contentType();
            if (!TIPOS_PERMITIDOS.contains(tipoMime))
            {
                Log.warnf("Formato de fichero no soportado: %s", tipoMime);
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new ErrorResponse("Formato incompatible. Extensiones soportadas: PDF, DOCX, DOC, TXT"))
                        .build();
            }

            // 3. Control de cuota de tamaño
            long tamanioEfectivo = Files.size(ficheroCargado.filePath());
            if (tamanioEfectivo > TAMANIO_MAXIMO)
            {
                Log.warnf("Exceso de tamaño detectado: %d bytes", tamanioEfectivo);
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new ErrorResponse("El fichero sobrepasa el límite máximo permitido de 10MB"))
                        .build();
            }

            // 4. Delegación al flujo de ingestión semántica
            Log.infof("Fichero validado técnicamente. Iniciando fase de ingestión...");
            DocumentoDTO resultado = ingestionService.ingerirFichero(
                    ficheroCargado.filePath(),
                    ficheroCargado.fileName(),
                    metadatos
            );

            Log.infof("Proceso de ingestión finalizado con éxito. Recurso ID: %s", resultado.id());

            // 5. Respuesta de confirmación de creación (HTTP 201 Created)
            return Response.status(Response.Status.CREATED)
                    .entity(resultado)
                    .build();

        } catch (IOException e)
        {
            Log.errorf(e, "Fallo en la lectura del flujo de datos: %s", ficheroCargado.fileName());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse("Error durante el procesamiento del flujo de datos: " + e.getMessage()))
                    .build();
        } catch (Exception e)
        {
            Log.errorf(e, "Excepción no controlada durante la subida del fichero");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse("Error interno de servidor no especificado"))
                    .build();
        }
    }

    /**
     * Endpoint para la recuperación síncrona del catálogo completo de ficheros.
     * @return Colección de objetos DocumentoDTO.
     */
    @GET
    public Response listarDocumentos()
    {
        try
        {
            List<DocumentoDTO> catalogo = ingestionService.listarDocumentos();
            return Response.ok(catalogo).build();
        } catch (Exception e)
        {
            Log.errorf(e, "Fallo en la recuperación del catálogo");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse("No se pudo obtener la lista de ficheros"))
                    .build();
        }
    }

    /**
     * Endpoint para la consulta detallada de un recurso por su ID.
     * @param id Identificador único del fichero.
     * @return DocumentoDTO correspondiente o error 404.
     */
    @GET
    @Path("/{id}")
    public Response obtenerDocumento(@PathParam("id") String id)
    {
        try
        {
            DocumentoDTO recurso = ingestionService.obtenerDocumento(id);
            if (recurso == null)
            {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(new ErrorResponse("Recurso no localizado en la base de conocimiento"))
                        .build();
            }
            return Response.ok(recurso).build();
        } catch (Exception e)
        {
            Log.errorf(e, "Fallo en la consulta del recurso con ID: %s", id);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse("Error en la recuperación del recurso solicitado"))
                    .build();
        }
    }

    /**
     * Endpoint para la eliminación física y lógica de un recurso.
     * @param id Identificador único del fichero a purgar.
     * @return Response 204 No Content en éxito o error 404.
     */
    @DELETE
    @Path("/{id}")
    public Response eliminarDocumento(@PathParam("id") String id)
    {
        try
        {
            boolean resultadoPurga = ingestionService.eliminarDocumento(id);
            if (!resultadoPurga)
            {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(new ErrorResponse("No se pudo purgar: recurso no localizado"))
                        .build();
            }
            Log.infof("🗑️ Purga confirmada para el recurso: %s", id);
            return Response.noContent().build();
        } catch (Exception e)
        {
            Log.errorf(e, "Fallo durante la operación de purga del recurso: %s", id);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse("Error interno durante la purga del fichero"))
                    .build();
        }
    }

    /**
     * Clase auxiliar para respuestas de error.
     */
    public record ErrorResponse(String error) {}
}