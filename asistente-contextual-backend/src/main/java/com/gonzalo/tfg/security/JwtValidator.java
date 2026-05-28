package com.gonzalo.tfg.security;

import io.quarkus.logging.Log;
import io.smallrye.jwt.auth.principal.JWTParser;
import io.smallrye.jwt.auth.principal.ParseException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.Optional;

/**
 * Validador manual de tokens JWT para el flujo WebSocket.

 * ¿Por qué validación manual?
 * {@code quarkus-smallrye-jwt} intercepta y valida automáticamente los tokens
 * en las peticiones HTTP REST (endpoints con {@code @Authenticated} o
 * {@code @RolesAllowed}). Sin embargo, los WebSockets de Quarkus
 * ({@code quarkus-websockets-next}) no pasan por el filtro de seguridad
 * HTTP estándar, por lo que es necesario validar el token manualmente
 * dentro del handler {@code @OnTextMessage}.

 * Se utiliza el {@link JWTParser} proporcionado por SmallRye JWT, que aplica
 * las mismas reglas de verificación que el filtro automático: firma RSA,
 * expiración, issuer y audience.
 */
@ApplicationScoped
public class JwtValidator {

    @Inject
    JWTParser jwtParser;

    /**
     * Valida un token JWT y extrae el estado de autenticación.
     *
     * @param token Token JWT en formato compacto (sin el prefijo "Bearer ").
     * @return {@link Optional} con {@link AuthState} autenticado si el token es válido;
     *         {@link Optional#empty()} si la validación falla por cualquier motivo
     *         (firma inválida, expirado, claims faltantes, etc.).
     */
    public Optional<AuthState> validate(String token) {
        try {
            JsonWebToken jwt = jwtParser.parse(token.trim());

            String username = jwt.getSubject();
            String companyId = jwt.getClaim("company_id");

            if (username == null || companyId == null) {
                Log.warnf("JWT válido pero sin claims obligatorios: sub=%s, company_id=%s",
                        username, companyId);
                return Optional.empty();
            }

            Log.infof("JWT validado correctamente para usuario '%s' (tenant: %s)",
                    username, companyId);
            return Optional.of(new AuthState(true, username, companyId));

        } catch (ParseException e) {
            Log.warnf("Fallo en la validación del JWT: %s", e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            Log.errorf(e, "Error inesperado durante la validación del JWT");
            return Optional.empty();
        }
    }
}
