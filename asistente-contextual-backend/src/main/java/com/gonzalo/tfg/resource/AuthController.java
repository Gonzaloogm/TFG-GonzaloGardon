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

 * Proporciona un endpoint de login que valida credenciales contra usuarios
 * hardcodeados en {@code application.properties} y genera tokens JWT firmados
 * con RSA para el axscceso al WebSocket de chat.

 * Formato de usuarios en application.properties

 * app.users.{username}.password={contraseña en claro}
 * app.users.{username}.company-id={identificador del tenant}

 * Decisiones de seguridad

 *   Las contraseñas se comparan con {@link MessageDigest#isEqual(byte[], byte[])}
 *       para evitar timing attacks (comparación en tiempo constante).
 *   Los mensajes de error no revelan si el fallo es por usuario o por contraseña
 *       para evitar enumeración de usuarios.
 *   El claim {@code groups} incluye {@code ["user"]} para compatibilidad futura
 *       con {@code @RolesAllowed}.
 *
 */
@Path("/api/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuthController {

    /** Duración del token JWT en segundos (1 hora). */
    private static final long TOKEN_DURATION_SECONDS = 3600;

    /**
     * Autentica un usuario y genera un token JWT.
     *
     * @param request Credenciales del usuario (username + password).
     * @return HTTP 200 con {@link LoginResponse} si las credenciales son válidas;
     *         HTTP 401 si no lo son;
     *         HTTP 400 si faltan campos obligatorios.
     */
    @POST
    @Path("/login")
    @PermitAll
    public Response login(LoginRequest request) {
        // Validación de entrada
        if (request == null || request.username() == null || request.password() == null
                || request.username().isBlank() || request.password().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("Los campos 'username' y 'password' son obligatorios"))
                    .build();
        }

        String username = request.username().trim();
        String password = request.password();

        // Resolución de credenciales desde base de datos
        Optional<com.gonzalo.tfg.entity.UserEntity> userOpt = com.gonzalo.tfg.entity.UserEntity.findByUsername(username);

        if (userOpt.isEmpty()) {
            Log.warnf("Intento de login con usuario inexistente: '%s'", username);
            // Prevención de timing attacks en usuarios inexistentes usando un hash ficticio de Bcrypt
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

        // Comparación timing-safe integrada en BcryptUtil
        boolean passwordMatch = io.quarkus.elytron.security.common.BcryptUtil.matches(password, user.passwordHash);

        if (!passwordMatch) {
            Log.warnf("Contraseña incorrecta para usuario: '%s'", username);
            return unauthorized();
        }

        String companyId = user.companyId;

        // Generación del token JWT con SmallRye JWT Build
        String token = Jwt.issuer("asistente-contextual-backend")
                .upn(username)                              // MicroProfile JWT: User Principal Name
                .subject(username)                          // claim "sub"
                .claim("company_id", companyId)             // claim personalizado para multi-tenant
                .groups(Set.of("user"))                     // claim "groups" para @RolesAllowed futuro
                .expiresIn(TOKEN_DURATION_SECONDS)
                .sign();                                    // Firma con la clave privada RSA configurada

        Log.infof("Login exitoso para usuario '%s' (tenant: %s). Token generado.",
                username, companyId);

        return Response.ok(new LoginResponse(token, TOKEN_DURATION_SECONDS)).build();
    }

    // -------------------------------------------------------------------------
    // RESPUESTAS Y DTOs
    // -------------------------------------------------------------------------

    /**
     * Respuesta genérica 401 Unauthorized.
     * El mensaje NO distingue entre usuario inexistente y contraseña incorrecta
     * para evitar enumeración de usuarios.
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
