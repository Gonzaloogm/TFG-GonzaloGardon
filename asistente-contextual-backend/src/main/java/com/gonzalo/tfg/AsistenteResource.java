package com.gonzalo.tfg;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/asistente")
public class AsistenteResource {

    private final AsistenteService asistenteService;

    public AsistenteResource(AsistenteService asistenteService) {
        this.asistenteService = asistenteService;
    }

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String hello() {
        return "Hello from Quarkus REST";
    }

    @POST
    @Path("/chat")
    @Produces(MediaType.TEXT_PLAIN)
    public String chat(String message) {
        return asistenteService.chat(message);
    }
}
