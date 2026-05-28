package com.gonzalo.tfg.resource;

import com.gonzalo.tfg.security.AuthState;
import com.gonzalo.tfg.security.JwtValidator;
import com.gonzalo.tfg.security.TenantContext;
import com.gonzalo.tfg.service.AsistenteService;
import io.quarkus.logging.Log;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.PathParam;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gestiona la conexión del chat en tiempo real.
 * Requiere que el usuario envíe su token antes de poder hablar.
 */
@WebSocket(path = "/chat/{sessionId}")
@Blocking
public class ChatWebSocket {

    @Inject
    AsistenteService asistenteService;

    @Inject
    JwtValidator jwtValidator;

    /** Guarda si el usuario está autenticado, usando el ID de su conexión. */
    private final ConcurrentHashMap<String, AuthState> estadoConexiones = new ConcurrentHashMap<>();

    /** Texto que debe ir antes del token para iniciar sesión. */
    private static final String AUTH_PREFIX = "AUTH:";

    /** Código de error cuando falla el inicio de sesión. */
    private static final int CLOSE_AUTH_FAILED = 4001;

    // -------------------------------------------------------------------------
    // CICLO DE VIDA DE LA CONEXIÓN
    // -------------------------------------------------------------------------

    /**
     * Se ejecuta cuando un usuario se conecta al chat.
     *
     * @param sessionId Identificador de la sesión de chat.
     * @param conexion La conexión actual.
     * @return Mensaje pidiendo el token al cliente.
     */
    @OnOpen
    public String alAbrir(@PathParam("sessionId") String sessionId, WebSocketConnection conexion) {
        estadoConexiones.put(conexion.id(), AuthState.pending());
        Log.infof("WebSocket abierto (pendiente de AUTH). sessionId: %s | connectionId: %s",
                sessionId, conexion.id());
        return "{\"type\":\"auth_required\",\"sessionId\":\"" + sessionId + "\"}";
    }

    /**
     * Recibe los mensajes del usuario.
     * Si no ha iniciado sesión, valida el token. Si ya está dentro, envía el mensaje al asistente.
     *
     * @param sessionId Identificador de la sesión de chat.
     * @param mensaje El texto que envía el usuario.
     * @param conexion La conexión actual.
     * @return La respuesta del asistente o un mensaje de error.
     */
    @OnTextMessage
    public String alRecibirMensaje(@PathParam("sessionId") String sessionId,
                                   String mensaje,
                                   WebSocketConnection conexion) {

        AuthState estado = estadoConexiones.get(conexion.id());

        // Si no hay estado de conexión, cerramos para evitar errores
        if (estado == null) {
            Log.errorf("Estado de conexión no encontrado para connectionId: %s. Cerrando.", conexion.id());
            conexion.close().subscribe().with(
                    v -> {},
                    err -> Log.warnf("Error cerrando conexión huérfana: %s", err.getMessage()));
            return "{\"type\":\"error\",\"reason\":\"Estado de conexión corrupto\"}";
        }

        // Si aún no ha enviado el token, lo comprobamos
        if (!estado.authenticated()) {
            if (mensaje.startsWith(AUTH_PREFIX)) {
                String token = mensaje.substring(AUTH_PREFIX.length()).trim();
                Optional<AuthState> resultado = jwtValidator.validate(token);

                if (resultado.isPresent()) {
                    // Token válido, marcamos al usuario como conectado
                    estadoConexiones.put(conexion.id(), resultado.get());
                    Log.infof("WebSocket autenticado. user=%s, tenant=%s, sessionId=%s",
                            resultado.get().username(), resultado.get().companyId(), sessionId);
                    return "{\"type\":\"auth_success\",\"username\":\"" +
                            resultado.get().username() + "\"}";
                } else {
                    // Token falso o caducado, cerramos la conexión
                    Log.warnf("Autenticación fallida para sessionId: %s. Cerrando con 4001.", sessionId);
                    conexion.close().subscribe().with(
                            v -> {},
                            err -> Log.warnf("Error cerrando conexión tras auth fallida: %s", err.getMessage()));
                    estadoConexiones.remove(conexion.id());
                    return "{\"type\":\"auth_failed\",\"reason\":\"Token JWT inválido o expirado\"}";
                }
            } else {
                // Si no envía el token primero, lo echamos
                Log.warnf("Primer mensaje sin AUTH: para sessionId: %s. Cerrando con 4001.", sessionId);
                conexion.close().subscribe().with(
                        v -> {},
                        err -> Log.warnf("Error cerrando conexión sin auth: %s", err.getMessage()));
                estadoConexiones.remove(conexion.id());
                return "{\"type\":\"auth_failed\",\"reason\":\"El primer mensaje debe ser AUTH:<token>\"}";
            }
        }

        // Si ya está conectado, respondemos a su pregunta
        TenantContext.set(estado.companyId());
        try {
            String respuesta = asistenteService.chat(sessionId, mensaje);
            return respuesta.replaceAll("(?s)<thinking>.*?</thinking>\\s*", "");
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("429") || msg.contains("RATE_LIMIT")
                    || msg.contains("quota") || msg.contains("exhausted")) {
                Log.warnf("Rate limit alcanzado para sesión %s: %s", sessionId, msg);
                return "Límite de velocidad de la API alcanzado. " +
                        "Espera unos segundos antes de continuar.";
            }
            Log.errorf(e, "Error procesando mensaje de sesión %s", sessionId);
            return "Error al procesar tu consulta. Por favor, inténtalo de nuevo.";
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Se ejecuta cuando el usuario cierra el chat.
     *
     * @param conexion La conexión actual.
     */
    @OnClose
    public void alCerrar(WebSocketConnection conexion) {
        AuthState removed = estadoConexiones.remove(conexion.id());
        if (removed != null) {
            Log.infof("WebSocket cerrado. connectionId: %s, autenticado: %b",
                    conexion.id(), removed.authenticated());
        }
    }
}