package com.gonzalo.tfg.service;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import io.quarkus.logging.Log;

@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AsistenteEvalResource {

    @Inject
    EvaluadorRelevancia evaluador;

    public static class EvalRequest {
        public String pregunta;
        public String respuesta;
        public String contexto;
    }

    public static class EvalResponse {
        public int puntuacion;
        public String mensaje;
    }

    @POST
    @Path("/evaluar")
    public Response evaluarRespuesta(EvalRequest request) {
        Log.infof("Recibida solicitud de evaluación para pregunta: '%s'", request.pregunta);

        try {
            if (request.pregunta == null || request.respuesta == null || request.contexto == null) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("Los campos pregunta, respuesta y contexto son obligatorios.")
                        .build();
            }

            int score = evaluador.evaluar(request.pregunta, request.respuesta, request.contexto);
            
            EvalResponse res = new EvalResponse();
            res.puntuacion = score;
            res.mensaje = "Evaluación RAGAS-style completada.";

            Log.infof("Puntuación otorgada por el evaluador: %d/5", score);
            return Response.ok(res).build();

        } catch (Exception e) {
            Log.error("Error durante la evaluación de la respuesta", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Error en el servicio de evaluación: " + e.getMessage())
                    .build();
        }
    }
}
