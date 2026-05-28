package com.gonzalo.tfg.resource;

import io.quarkus.logging.Log;
import io.smallrye.jwt.build.Jwt;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.ConfigProvider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.Set;

/**
 * Controlador de autenticación para el MVP.
 *
 * Proporciona un endpoint de login que valida credenciales en base de datos
 * y genera tokens JWT firmados para acceder al WebSocket de chat.
 */
@Path("/api/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuthController {

    /** Duración del token JWT en segundos (1 hora). */
    private static final long TOKEN_DURATION_SECONDS = 3600;

    /**
     * Autentica un usuario y genera un token JWT si todo es correcto.
     *
     * @param request Las credenciales del usuario que intenta entrar.
     * @return El token generado, o un error si fallan las credenciales.
     */
    @POST
    @Path("/login")
    @PermitAll
    public Response login(LoginRequest request) {
        // Comprueba que los campos no estén vacíos
        if (request == null || request.username() == null || request.password() == null
                || request.username().isBlank() || request.password().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("Los campos 'username' y 'password' son obligatorios"))
                    .build();
        }

        String username = request.username().trim();
        String password = request.password();

        // Busca el usuario en la base de datos
        Optional<com.gonzalo.tfg.entity.UserEntity> userOpt = com.gonzalo.tfg.entity.UserEntity.findByUsername(username);

        if (userOpt.isEmpty()) {
            Log.warnf("Intento de login con usuario inexistente: '%s'", username);
            // Compara contra una contraseña inventada para que tarde lo mismo y no delate que el usuario no existe
            io.quarkus.elytron.security.common.BcryptUtil.matches(password, "$2a$10$xyzxyzxyzxyzxyzxyzxyzuxyzxyzxyzxyzxyzxyzxyzxyzxyzxyz");
            return unauthorized();
        }

        com.gonzalo.tfg.entity.UserEntity user = userOpt.get();

        if (!user.active) {
            Log.warnf("Intento de login con cuenta desactivada: '%s'", username);
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new ErrorResponse("Cuenta desactivada"))
                    .build();
        }

        // Comprueba si la contraseña introducida es correcta
        boolean passwordMatch = io.quarkus.elytron.security.common.BcryptUtil.matches(password, user.passwordHash);

        if (!passwordMatch) {
            Log.warnf("Contraseña incorrecta para usuario: '%s'", username);
            return unauthorized();
        }

        String companyId = user.companyId;

        // Crea el token con los datos de usuario y empresa
        String token = Jwt.issuer("asistente-contextual-backend")
                .upn(username)
                .subject(username)
                .claim("company_id", companyId)
                .groups(Set.of("user"))
                .expiresIn(TOKEN_DURATION_SECONDS)
                .sign();

        Log.infof("Login exitoso para usuario '%s' (tenant: %s). Token generado.",
                username, companyId);

        return Response.ok(new LoginResponse(token, TOKEN_DURATION_SECONDS)).build();
    }

    // -------------------------------------------------------------------------
    // RESPUESTAS Y DTOs
    // -------------------------------------------------------------------------

    /**
     * Devuelve error de acceso sin dar pistas de qué falló exactamente.
     */
    private Response unauthorized() {
        return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Credenciales inválidas"))
                .build();
    }

    /** Payload de la petición de login. */
    public record LoginRequest(String username, String password) {
    }

    /** Payload de la respuesta de login exitoso. */
    public record LoginResponse(String token, long expiresIn) {
    }

    /** Payload de error genérico. */
    public record ErrorResponse(String error) {
    }
}
