package com.gonzalo.tfg.resource;

import com.gonzalo.tfg.entity.ChatSessionEntity;
import com.gonzalo.tfg.model.ChatSessionSummaryDTO;
import com.gonzalo.tfg.service.ChatService;
import io.quarkus.panache.common.Sort;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.stream.Collectors;

/*
 Recurso REST para la gestión y consulta del historial de chats.
 Permite al frontend recuperar las sesiones guardadas y el contenido de las mismas.
 */
@Path("/api/chats")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ChatResource {

    @Inject
    ChatService chatService;

    /*
     Obtiene el listado de todas las sesiones de chat registradas.
     Las sesiones se devuelven ordenadas por fecha de actualización descendente.
     
     @return Lista de resúmenes de sesiones (ID y fecha).
     */
    @GET
    public List<ChatSessionSummaryDTO> obtenerSesiones() {
        return ChatSessionEntity.<ChatSessionEntity>listAll(Sort.by("updatedAt").descending())
                .stream()
                .map(entity -> new ChatSessionSummaryDTO(entity.sessionId, entity.updatedAt, entity.titulo))
                .collect(Collectors.toList());
    }

    /*
     * Elimina una sesión de chat por su identificador.
     * Borra el registro de BD (con su JSON de mensajes), limpia la memoria
     * en caché de LangChain4j y purga los vectores asociados en pgvector.
     *
     * @param id Identificador único de la sesión.
     * @return 204 No Content si se eliminó correctamente, 404 si no existe.
     */
    @DELETE
    @Path("/{id}")
    public Response borrarSesion(@PathParam("id") String id) {
        boolean eliminado = chatService.eliminarSesion(id);
        if (!eliminado) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"Sesión no encontrada: " + id + "\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        return Response.noContent().build();
    }

    /*
     * Actualiza manualmente el título de una sesión de chat.
     * 
     * @param id Identificador único de la sesión.
     * @param nuevoTitulo Nuevo texto para el título.
     * @return 200 OK si se actualizó correctamente.
     */
    @PUT
    @Path("/{id}/titulo")
    @Transactional
    public Response actualizarTitulo(@PathParam("id") String id, String nuevoTitulo) {
        ChatSessionEntity entity = ChatSessionEntity.findById(id);
        if (entity == null) {
            throw new WebApplicationException("Sesión no encontrada para actualizar título: " + id, Response.Status.NOT_FOUND);
        }
        entity.titulo = nuevoTitulo;
        return Response.ok().build();
    }

    /*
     Recupera el historial completo de mensajes de una sesión específica.
     
     @param id Identificador único de la sesión (sessionId).
     @return Cadena JSON con el historial de mensajes.
     */
    @GET
    @Path("/{id}/historial")
    public String obtenerHistorial(@PathParam("id") String id) {
        ChatSessionEntity entity = ChatSessionEntity.findById(id);

        if (entity == null) {
            throw new WebApplicationException("Sesión de chat no encontrada: " + id, Response.Status.NOT_FOUND);
        }

        // Devuelve array vacío si la sesión existe pero todavía no tiene mensajes persistidos,
        // evitando un cuerpo nulo que el frontend interpretaría como error 500.
        if (entity.messagesJson == null || entity.messagesJson.isBlank()) {
            return "[]";
        }

        return entity.messagesJson;
    }
}
