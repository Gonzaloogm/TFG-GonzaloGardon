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
import java.util.UUID;
import java.util.stream.Collectors;

@Path("/api/chats")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ChatResource {

    @Inject
    ChatService chatService;

    @GET
    public List<ChatSessionSummaryDTO> obtenerSesiones() {
        return ChatSessionEntity.<ChatSessionEntity>listAll(Sort.by("updatedAt").descending())
                .stream()
                .map(entity -> new ChatSessionSummaryDTO(entity.sessionId, entity.updatedAt, entity.titulo))
                .collect(Collectors.toList());
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response borrarSesion(@PathParam("id") String id) {
        /*
         * Validación explícita del formato UUID antes de llegar al servicio.
         *
         * El problema original: Quarkus resuelve el proxy @SessionScoped de
         * WebSocketConnection (inyectado en ChatWebSocket) en el contexto CDI
         * compartido, y su toString() llega aquí como valor del parámetro cuando
         * no hay sesión WebSocket activa durante una petición REST.
         *
         * Esta validación actúa como firewall: si el valor no es un UUID válido,
         * falla rápido con 400 en lugar de propagar el objeto de contexto corrupto
         * al servicio y a la BD.
         */
        if (id == null || id.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"El identificador de sesión no puede estar vacío.\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        try {
            UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            io.quarkus.logging.Log.errorf(
                    "DELETE /api/chats recibió un id no válido: '%s'. " +
                            "Posible contaminación del contexto CDI por WebSocketConnection @SessionScoped.",
                    id);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"Identificador de sesión con formato inválido.\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        try {
            boolean eliminado = chatService.eliminarSesion(id);
            if (!eliminado) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("{\"error\":\"Sesión no encontrada: " + id + "\"}")
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            }
            return Response.noContent().build();

        } catch (Exception e) {
            io.quarkus.logging.Log.errorf(e, "Error crítico al eliminar la sesión %s", id);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Error interno al eliminar la sesión. Consulte los logs.\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
    }

    @PUT
    @Path("/{id}/titulo")
    @Transactional
    public Response actualizarTitulo(@PathParam("id") String id, String nuevoTitulo) {
        ChatSessionEntity entity = ChatSessionEntity.findById(id);
        if (entity == null) {
            throw new WebApplicationException("Sesión no encontrada: " + id, Response.Status.NOT_FOUND);
        }
        entity.titulo = nuevoTitulo;
        return Response.ok().build();
    }

    @GET
    @Path("/{id}/historial")
    public String obtenerHistorial(@PathParam("id") String id) {
        ChatSessionEntity entity = ChatSessionEntity.findById(id);
        if (entity == null) {
            throw new WebApplicationException("Sesión no encontrada: " + id, Response.Status.NOT_FOUND);
        }
        if (entity.messagesJson == null || entity.messagesJson.isBlank()) {
            return "[]";
        }
        return entity.messagesJson;
    }
}