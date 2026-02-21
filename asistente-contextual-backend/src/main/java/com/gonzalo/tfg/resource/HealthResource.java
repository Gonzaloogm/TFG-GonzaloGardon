package com.gonzalo.tfg.resource;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/api/health")
public class HealthResource
{

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public HealthResponse health()
    {
        return new HealthResponse(
                "OK",
                "Asistente Contextual Backend",
                "1.0.0",
                "Sistema operativo correctamente");
    }

    public record HealthResponse(
            String status,
            String service,
            String version,
            String message) {
    }
}