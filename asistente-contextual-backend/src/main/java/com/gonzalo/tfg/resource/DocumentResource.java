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
 * Endpoint REST para la gestión de documentos.
 * Parte del "Módulo de Ingestión" según el diagrama de arquitectura (Nivel 3).
 * Responsable de recibir documentos vía HTTP y delegarlos al servicio de ingestión.
 * Flujo:
 * 1. Usuario sube documento vía POST /api/documentos/upload
 * 2. Validación de tipo de archivo (PDF, DOCX, TXT)
 * 3. Validación de tamaño (máx 10MB por defecto)
 * 4. Delegación a DocumentIngestionService para procesamiento
 * 5. Respuesta con ID del documento procesado
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
    public Response uploadDocumento(
            @RestForm("file") FileUpload file,
            @RestForm("metadatos") String metadatos
    )
    {
        Log.infof("Recibiendo documento: %s", file.fileName());

        try
        {
            // 1. Validar que el archivo existe
            if (file == null || file.fileName() == null || file.fileName().isBlank()) {
                Log.warn("Archivo no proporcionado");
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new ErrorResponse("El archivo es obligatorio"))
                        .build();
            }

            // 2. Validar tipo de archivo
            String contentType = file.contentType();
            if (!TIPOS_PERMITIDOS.contains(contentType))
            {
                Log.warnf("Tipo de archivo no permitido: %s", contentType);
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new ErrorResponse("Tipo de archivo no permitido. Solo se aceptan: PDF, DOCX, DOC, TXT"))
                        .build();
            }

            // 3. Validar tamaño
            long tamanio = Files.size(file.filePath());
            if (tamanio > TAMANIO_MAXIMO)
            {
                Log.warnf("Archivo muy grande: %d bytes", tamanio);
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new ErrorResponse("El archivo excede el tamaño máximo de 10MB"))
                        .build();
            }

            // 4. Procesar el documento
            Log.infof("Archivo validado. Iniciando ingestión...");
            DocumentoDTO documento = ingestionService.ingerirDocumento(
                    file.filePath(),
                    file.fileName(),
                    metadatos
            );

            Log.infof("Documento procesado exitosamente: %s", documento.id());

            // 5. Retornar respuesta exitosa
            return Response.status(Response.Status.CREATED)
                    .entity(documento)
                    .build();

        } catch (IOException e)
        {
            Log.errorf(e, "Error leyendo archivo: %s", file.fileName());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse("Error procesando el archivo: " + e.getMessage()))
                    .build();
        } catch (Exception e)
        {
            Log.errorf(e, "Error inesperado procesando documento");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse("Error interno del servidor"))
                    .build();
        }
    }

    /**
     * Endpoint para listar todos los documentos procesados.
     * @return Lista de documentos
     */
    @GET
    public Response listarDocumentos()
    {
        try
        {
            List<DocumentoDTO> documentos = ingestionService.listarDocumentos();
            return Response.ok(documentos).build();
        } catch (Exception e)
        {
            Log.errorf(e, "Error listando documentos");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse("Error obteniendo documentos"))
                    .build();
        }
    }

    /**
     * Endpoint para obtener un documento por ID.
     * @param id ID del documento
     * @return Documento encontrado
     */
    @GET
    @Path("/{id}")
    public Response obtenerDocumento(@PathParam("id") String id)
    {
        try
        {
            DocumentoDTO documento = ingestionService.obtenerDocumento(id);
            if (documento == null)
            {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(new ErrorResponse("Documento no encontrado"))
                        .build();
            }
            return Response.ok(documento).build();
        } catch (Exception e)
        {
            Log.errorf(e, "Error obteniendo documento: %s", id);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse("Error obteniendo documento"))
                    .build();
        }
    }

    /**
     * Endpoint para eliminar un documento.
     * @param id ID del documento a eliminar
     * @return Response 204 No Content si se eliminó correctamente
     */
    @DELETE
    @Path("/{id}")
    public Response eliminarDocumento(@PathParam("id") String id)
    {
        try
        {
            boolean eliminado = ingestionService.eliminarDocumento(id);
            if (!eliminado)
            {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(new ErrorResponse("Documento no encontrado"))
                        .build();
            }
            Log.infof("🗑️ Documento eliminado: %s", id);
            return Response.noContent().build();
        } catch (Exception e)
        {
            Log.errorf(e, "Error eliminando documento: %s", id);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse("Error eliminando documento"))
                    .build();
        }
    }

    /**
     * Clase auxiliar para respuestas de error.
     */
    public record ErrorResponse(String error) {}
}