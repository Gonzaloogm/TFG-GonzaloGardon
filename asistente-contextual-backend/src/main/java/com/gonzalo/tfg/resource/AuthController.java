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
    public Response login(PeticionLogin request) {
        // Comprueba que los campos no estén vacíos
        if (request == null || request.nombreUsuario() == null || request.contrasena() == null
                || request.nombreUsuario().isBlank() || request.contrasena().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new RespuestaError("Los campos 'nombreUsuario' y 'contrasena' son obligatorios"))
                    .build();
        }

        String nombreUsuario = request.nombreUsuario().trim();
        String contrasena = request.contrasena();

        // Busca el usuario en la base de datos
        Optional<com.gonzalo.tfg.entity.UserEntity> userOpt = com.gonzalo.tfg.entity.UserEntity.findByNombreUsuario(nombreUsuario);

        if (userOpt.isEmpty()) {
            Log.warnf("Intento de login con usuario inexistente: '%s'", nombreUsuario);
            // Compara contra una contraseña inventada para que tarde lo mismo y no delate que el usuario no existe
            io.quarkus.elytron.security.common.BcryptUtil.matches(contrasena, "$2a$10$xyzxyzxyzxyzxyzxyzxyzuxyzxyzxyzxyzxyzxyzxyzxyzxyzxyz");
            return unauthorized();
        }

        com.gonzalo.tfg.entity.UserEntity user = userOpt.get();

        if (!user.activo) {
            Log.warnf("Intento de login con cuenta desactivada: '%s'", nombreUsuario);
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new RespuestaError("Cuenta desactivada"))
                    .build();
        }

        // Comprueba si la contraseña introducida es correcta
        boolean passwordMatch = io.quarkus.elytron.security.common.BcryptUtil.matches(contrasena, user.hashContrasena);

        if (!passwordMatch) {
            Log.warnf("Contraseña incorrecta para usuario: '%s'", nombreUsuario);
            return unauthorized();
        }

        String idEmpresa = user.idEmpresa;

        // Crea el token con los datos de usuario y empresa
        String token = Jwt.issuer("asistente-contextual-backend")
                .upn(nombreUsuario)
                .subject(nombreUsuario)
                .claim("company_id", idEmpresa)
                .groups(Set.of("user"))
                .expiresIn(TOKEN_DURATION_SECONDS)
                .sign();

        Log.infof("Login exitoso para usuario '%s' (tenant: %s). Token generado.",
                nombreUsuario, idEmpresa);

        return Response.ok(new RespuestaLogin(token, TOKEN_DURATION_SECONDS)).build();
    }

    // -------------------------------------------------------------------------
    // RESPUESTAS Y DTOs
    // -------------------------------------------------------------------------

    /**
     * Devuelve error de acceso sin dar pistas de qué falló exactamente.
     */
    private Response unauthorized() {
        return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new RespuestaError("Credenciales inválidas"))
                .build();
    }

    /** Payload de la petición de login. */
    public record PeticionLogin(String nombreUsuario, String contrasena) {
    }

    /** Payload de la respuesta de login exitoso. */
    public record RespuestaLogin(String token, long expiresIn) {
    }

    /** Payload de error genérico. */
    public record RespuestaError(String error) {
    }
}
