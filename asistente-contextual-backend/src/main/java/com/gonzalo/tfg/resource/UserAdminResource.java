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
            admin.username = "admin";
            admin.passwordHash = BcryptUtil.bcryptHash(bootstrapPassword);
            admin.companyId = "empresa-alpha";
            admin.active = true;
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
                    .entity(new AuthController.ErrorResponse("Acceso denegado: admin key no proporcionada"))
                    .build());
        }
        
        boolean match = MessageDigest.isEqual(
                key.getBytes(StandardCharsets.UTF_8), 
                adminKey.getBytes(StandardCharsets.UTF_8));
                
        if (!match) {
            throw new WebApplicationException(Response.status(Response.Status.FORBIDDEN)
                    .entity(new AuthController.ErrorResponse("Acceso denegado: admin key inválida"))
                    .build());
        }
    }

    @GET
    public List<UserResponseDTO> listUsers(@HeaderParam("X-Admin-Key") String key) {
        validarAdminKey(key);
        return UserEntity.<UserEntity>listAll().stream()
                .map(u -> new UserResponseDTO(u.username, u.companyId, u.active, u.createdAt.toString()))
                .collect(Collectors.toList());
    }

    @POST
    @Transactional
    public Response createUser(@HeaderParam("X-Admin-Key") String key, CreateUserDTO request) {
        validarAdminKey(key);
        
        if (request.username == null || request.password == null || request.companyId == null
                || request.username.isBlank() || request.password.isBlank() || request.companyId.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new AuthController.ErrorResponse("Faltan campos obligatorios: username, password, companyId"))
                    .build();
        }

        if (UserEntity.findByUsername(request.username).isPresent()) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(new AuthController.ErrorResponse("El usuario ya existe"))
                    .build();
        }

        UserEntity user = new UserEntity();
        user.username = request.username.trim();
        user.passwordHash = BcryptUtil.bcryptHash(request.password);
        user.companyId = request.companyId.trim();
        user.active = true;
        user.persist();

        return Response.status(Response.Status.CREATED)
                .entity(new UserResponseDTO(user.username, user.companyId, user.active, user.createdAt.toString()))
                .build();
    }

    @DELETE
    @Path("/{username}")
    @Transactional
    public Response deleteUser(@HeaderParam("X-Admin-Key") String key, @PathParam("username") String username) {
        validarAdminKey(key);
        UserEntity user = UserEntity.findByUsername(username)
                .orElseThrow(() -> new WebApplicationException(Response.status(Response.Status.NOT_FOUND)
                        .entity(new AuthController.ErrorResponse("Usuario no encontrado")).build()));
        
        user.active = false; // Soft delete
        user.persist();
        return Response.noContent().build();
    }

    @PUT
    @Path("/{username}/reset-password")
    @Transactional
    public Response resetPassword(@HeaderParam("X-Admin-Key") String key, 
                                  @PathParam("username") String username, 
                                  ResetPasswordDTO request) {
        validarAdminKey(key);
        
        if (request.newPassword == null || request.newPassword.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new AuthController.ErrorResponse("La nueva contraseña es obligatoria"))
                    .build();
        }
        
        UserEntity user = UserEntity.findByUsername(username)
                .orElseThrow(() -> new WebApplicationException(Response.status(Response.Status.NOT_FOUND)
                        .entity(new AuthController.ErrorResponse("Usuario no encontrado")).build()));
                        
        user.passwordHash = BcryptUtil.bcryptHash(request.newPassword);
        user.persist();
        return Response.noContent().build();
    }

    @PUT
    @Path("/{username}/reactivar")
    @Transactional
    public Response reactivarUsuario(@PathParam("username") String username,
                                     @HeaderParam("X-Admin-Key") String adminKey) {
        validarAdminKey(adminKey);

        UserEntity user = UserEntity.findByUsername(username)
                .orElseThrow(() -> new WebApplicationException(
                        Response.status(404)
                                .entity("{\"error\":\"Usuario no encontrado\"}")
                                .type(MediaType.APPLICATION_JSON).build()));

        user.active = true;
        return Response.ok("{\"message\":\"Usuario reactivado\"}").build();
    }

    // --- DTOs inline ---
    public record UserResponseDTO(String username, String companyId, boolean active, String createdAt) {}
    public record CreateUserDTO(String username, String password, String companyId) {}
    public record ResetPasswordDTO(String newPassword) {}
}
