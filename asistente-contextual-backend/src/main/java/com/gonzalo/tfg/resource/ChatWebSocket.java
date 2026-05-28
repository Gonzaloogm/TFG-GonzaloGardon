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
 * Endpoint WebSocket para el chat del asistente contextual.
 *
 * <h3>Protocolo de autenticación en dos fases</h3>
 * <ol>
 *   <li><strong>Fase 1 — Conexión</strong>: {@code @OnOpen} acepta la conexión
 *       pero la marca como "pendiente de autenticación" en un
 *       {@link ConcurrentHashMap} thread-safe.</li>
 *   <li><strong>Fase 2 — Autenticación</strong>: El primer mensaje de texto debe
 *       empezar por {@code "AUTH:"} seguido del token JWT. Si es válido, la sesión
 *       pasa a estado autenticado. Si no, se cierra con código 4001.</li>
 * </ol>
 *
 * <h3>¿Por qué no usar {@code ?token=xxx} en la URL?</h3>
 * <p>Los query parameters aparecen en logs de proxy, access logs del servidor,
 * historial del navegador y herramientas de debug de red. Enviar el token como
 * primer mensaje WebSocket evita esta exposición.</p>
 *
 * <h3>¿Por qué ConcurrentHashMap y no CDI scope?</h3>
 * <p>El bean {@code @WebSocket} no es {@code @ApplicationScoped} de forma estándar
 * en Quarkus WebSockets Next. Usar un {@link ConcurrentHashMap} estático como
 * campo de instancia garantiza thread-safety sin depender del ciclo de vida CDI.
 * Las claves son los {@code connectionId} internos de Quarkus, que son únicos
 * por conexión.</p>
 */
@WebSocket(path = "/chat/{sessionId}")
@Blocking
public class ChatWebSocket {

    @Inject
    AsistenteService asistenteService;

    @Inject
    JwtValidator jwtValidator;

    /**
     * Registro de estados de autenticación por conexión.
     * Clave: {@code WebSocketConnection.id()} — único por conexión.
     * Valor: {@link AuthState} — inmutable, se reemplaza al autenticar.
     */
    private final ConcurrentHashMap<String, AuthState> estadoConexiones = new ConcurrentHashMap<>();

    /** Prefijo que identifica el mensaje de autenticación. */
    private static final String AUTH_PREFIX = "AUTH:";

    /** Código de cierre WebSocket personalizado: autenticación fallida. */
    private static final int CLOSE_AUTH_FAILED = 4001;

    // -------------------------------------------------------------------------
    // CICLO DE VIDA DE LA CONEXIÓN
    // -------------------------------------------------------------------------

    /**
     * Fase 1: Acepta la conexión y la registra como pendiente de autenticación.
     * No procesa mensajes de chat hasta que el cliente envíe un token válido.
     */
    @OnOpen
    public String alAbrir(@PathParam("sessionId") String sessionId, WebSocketConnection conexion) {
        estadoConexiones.put(conexion.id(), AuthState.pending());
        Log.infof("WebSocket abierto (pendiente de AUTH). sessionId: %s | connectionId: %s",
                sessionId, conexion.id());
        return "{\"type\":\"auth_required\",\"sessionId\":\"" + sessionId + "\"}";
    }

    /**
     * Fase 2 + procesamiento de mensajes.
     *
     * <p>Si la conexión NO está autenticada, espera un mensaje {@code AUTH:<token>}.
     * Si la conexión YA está autenticada, procesa el mensaje como consulta al
     * asistente, propagando el {@code company_id} del tenant via
     * {@link TenantContext}.</p>
     */
    @OnTextMessage
    public String alRecibirMensaje(@PathParam("sessionId") String sessionId,
                                   String mensaje,
                                   WebSocketConnection conexion) {

        AuthState estado = estadoConexiones.get(conexion.id());

        // Guarda de seguridad: estado corrupto o conexión no registrada
        if (estado == null) {
            Log.errorf("Estado de conexión no encontrado para connectionId: %s. Cerrando.", conexion.id());
            conexion.close().subscribe().with(
                    v -> {},
                    err -> Log.warnf("Error cerrando conexión huérfana: %s", err.getMessage()));
            return "{\"type\":\"error\",\"reason\":\"Estado de conexión corrupto\"}";
        }

        // --- CONEXIÓN NO AUTENTICADA: esperar AUTH:<token> ---
        if (!estado.authenticated()) {
            if (mensaje.startsWith(AUTH_PREFIX)) {
                String token = mensaje.substring(AUTH_PREFIX.length()).trim();
                Optional<AuthState> resultado = jwtValidator.validate(token);

                if (resultado.isPresent()) {
                    // Autenticación exitosa: reemplazar estado en el mapa
                    estadoConexiones.put(conexion.id(), resultado.get());
                    Log.infof("WebSocket autenticado. user=%s, tenant=%s, sessionId=%s",
                            resultado.get().username(), resultado.get().companyId(), sessionId);
                    return "{\"type\":\"auth_success\",\"username\":\"" +
                            resultado.get().username() + "\"}";
                } else {
                    // Token inválido: cerrar conexión con código 4001
                    Log.warnf("Autenticación fallida para sessionId: %s. Cerrando con 4001.", sessionId);
                    conexion.close().subscribe().with(
                            v -> {},
                            err -> Log.warnf("Error cerrando conexión tras auth fallida: %s", err.getMessage()));
                    estadoConexiones.remove(conexion.id());
                    return "{\"type\":\"auth_failed\",\"reason\":\"Token JWT inválido o expirado\"}";
                }
            } else {
                // Primer mensaje sin prefijo AUTH: rechazar
                Log.warnf("Primer mensaje sin AUTH: para sessionId: %s. Cerrando con 4001.", sessionId);
                conexion.close().subscribe().with(
                        v -> {},
                        err -> Log.warnf("Error cerrando conexión sin auth: %s", err.getMessage()));
                estadoConexiones.remove(conexion.id());
                return "{\"type\":\"auth_failed\",\"reason\":\"El primer mensaje debe ser AUTH:<token>\"}";
            }
        }

        // --- CONEXIÓN AUTENTICADA: procesar mensaje de chat ---
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
     * Limpieza al cerrar la conexión.
     * Elimina el estado de autenticación del mapa para evitar fugas de memoria.
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