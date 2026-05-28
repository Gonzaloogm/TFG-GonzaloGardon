package com.gonzalo.tfg.resource;

import com.gonzalo.tfg.entity.UserEntity;
import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.stream.Collectors;

@Path("/api/admin/users")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class UserAdminResource {

    @ConfigProperty(name = "app.admin.key")
    String adminKey;

    @ConfigProperty(name = "app.bootstrap.admin.password")
    String bootstrapPassword;

    /**
     * Bootstrap inicial. Si la tabla está vacía, crea un usuario admin
     * para no quedarse sin acceso al sistema.
     */
    @PostConstruct
    void init() {
        crearAdminSiNecesario();
    }

    @Transactional
    void crearAdminSiNecesario() {
        if (UserEntity.count() == 0) {
            UserEntity admin = new UserEntity();
            admin.nombreUsuario = "admin";
            admin.hashContrasena = BcryptUtil.bcryptHash(bootstrapPassword);
            admin.idEmpresa = "empresa-alpha";
            admin.activo = true;
            admin.persist();
            Log.warn("Bootstrap: Creado usuario administrador ('admin'). Cambia la contraseña en producción.");
        }
    }

    /**
     * Valida el header X-Admin-Key contra el valor de properties
     * utilizando comparación en tiempo constante.
     */
    private void validarAdminKey(String key) {
        if (key == null || adminKey == null) {
            throw new WebApplicationException(Response.status(Response.Status.FORBIDDEN)
                    .entity(new AuthController.RespuestaError("Acceso denegado: admin key no proporcionada"))
                    .build());
        }
        
        boolean match = MessageDigest.isEqual(
                key.getBytes(StandardCharsets.UTF_8), 
                adminKey.getBytes(StandardCharsets.UTF_8));
                
        if (!match) {
            throw new WebApplicationException(Response.status(Response.Status.FORBIDDEN)
                    .entity(new AuthController.RespuestaError("Acceso denegado: admin key inválida"))
                    .build());
        }
    }

    @GET
    public List<RespuestaUsuarioDTO> listUsers(@HeaderParam("X-Admin-Key") String key) {
        validarAdminKey(key);
        return UserEntity.<UserEntity>listAll().stream()
                .map(u -> new RespuestaUsuarioDTO(u.nombreUsuario, u.idEmpresa, u.activo, u.fechaCreacion.toString()))
                .collect(Collectors.toList());
    }

    @POST
    @Transactional
    public Response createUser(@HeaderParam("X-Admin-Key") String key, CrearUsuarioDTO request) {
        validarAdminKey(key);
        
        if (request.nombreUsuario() == null || request.contrasena() == null || request.idEmpresa() == null
                || request.nombreUsuario().isBlank() || request.contrasena().isBlank() || request.idEmpresa().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new AuthController.RespuestaError("Faltan campos obligatorios: nombreUsuario, contrasena, idEmpresa"))
                    .build();
        }

        if (UserEntity.findByNombreUsuario(request.nombreUsuario()).isPresent()) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(new AuthController.RespuestaError("El usuario ya existe"))
                    .build();
        }

        UserEntity user = new UserEntity();
        user.nombreUsuario = request.nombreUsuario().trim();
        user.hashContrasena = BcryptUtil.bcryptHash(request.contrasena());
        user.idEmpresa = request.idEmpresa().trim();
        user.activo = true;
        user.persist();

        return Response.status(Response.Status.CREATED)
                .entity(new RespuestaUsuarioDTO(user.nombreUsuario, user.idEmpresa, user.activo, user.fechaCreacion.toString()))
                .build();
    }

    @DELETE
    @Path("/{nombreUsuario}")
    @Transactional
    public Response deleteUser(@HeaderParam("X-Admin-Key") String key, @PathParam("nombreUsuario") String nombreUsuario) {
        validarAdminKey(key);
        UserEntity user = UserEntity.findByNombreUsuario(nombreUsuario)
                .orElseThrow(() -> new WebApplicationException(Response.status(Response.Status.NOT_FOUND)
                        .entity(new AuthController.RespuestaError("Usuario no encontrado")).build()));
        
        user.activo = false; // Soft delete
        user.persist();
        return Response.noContent().build();
    }

    @PUT
    @Path("/{nombreUsuario}/reset-password")
    @Transactional
    public Response resetPassword(@HeaderParam("X-Admin-Key") String key, 
                                  @PathParam("nombreUsuario") String nombreUsuario, 
                                  RestablecerContrasenaDTO request) {
        validarAdminKey(key);
        
        if (request.nuevaContrasena() == null || request.nuevaContrasena().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new AuthController.RespuestaError("La nueva contraseña es obligatoria"))
                    .build();
        }
        
        UserEntity user = UserEntity.findByNombreUsuario(nombreUsuario)
                .orElseThrow(() -> new WebApplicationException(Response.status(Response.Status.NOT_FOUND)
                        .entity(new AuthController.RespuestaError("Usuario no encontrado")).build()));
                        
        user.hashContrasena = BcryptUtil.bcryptHash(request.nuevaContrasena());
        user.persist();
        return Response.noContent().build();
    }

    @PUT
    @Path("/{nombreUsuario}/reactivar")
    @Transactional
    public Response reactivarUsuario(@PathParam("nombreUsuario") String nombreUsuario,
                                     @HeaderParam("X-Admin-Key") String adminKey) {
        validarAdminKey(adminKey);

        UserEntity user = UserEntity.findByNombreUsuario(nombreUsuario)
                .orElseThrow(() -> new WebApplicationException(
                        Response.status(404)
                                .entity("{\"error\":\"Usuario no encontrado\"}")
                                .type(MediaType.APPLICATION_JSON).build()));

        user.activo = true;
        return Response.ok("{\"message\":\"Usuario reactivado\"}").build();
    }

    // --- DTOs inline ---
    public record RespuestaUsuarioDTO(String nombreUsuario, String idEmpresa, boolean activo, String fechaCreacion) {}
    public record CrearUsuarioDTO(String nombreUsuario, String contrasena, String idEmpresa) {}
    public record RestablecerContrasenaDTO(String nuevaContrasena) {}
}
