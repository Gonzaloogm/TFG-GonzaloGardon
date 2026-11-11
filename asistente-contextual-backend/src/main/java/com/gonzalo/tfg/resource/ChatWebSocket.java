package com.gonzalo.tfg.resource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gonzalo.tfg.security.AuthState;
import com.gonzalo.tfg.security.ContextoSesion;
import com.gonzalo.tfg.security.JwtValidator;
import com.gonzalo.tfg.service.AsistenteService;
import com.gonzalo.tfg.service.ChatService;
import com.gonzalo.tfg.service.FiltroThinking;
import io.quarkus.logging.Log;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.PathParam;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gestiona la conexión del chat en tiempo real.
 *
 * Protocolo (todos los mensajes del servidor son JSON con un campo "type"):
 * <ul>
 *   <li>{@code auth_required}: el cliente debe enviar {@code AUTH:<jwt>}.</li>
 *   <li>{@code auth_success} / {@code auth_failed}: resultado de la autenticación.</li>
 *   <li>{@code session_unavailable}: la sesión pertenece a otro usuario.</li>
 *   <li>{@code token}: fragmento de la respuesta ("content"), en streaming.</li>
 *   <li>{@code end}: la respuesta ha terminado.</li>
 *   <li>{@code error}: fallo al generar la respuesta ("reason").</li>
 * </ul>
 */
@WebSocket(path = "/chat/{sessionId}")
@Blocking
public class ChatWebSocket {

    @Inject
    AsistenteService asistenteService;

    @Inject
    JwtValidator jwtValidator;

    @Inject
    ChatService chatService;

    @Inject
    ObjectMapper objectMapper;

    /** Guarda si el usuario está autenticado, usando el ID de su conexión. */
    private final ConcurrentHashMap<String, AuthState> estadoConexiones = new ConcurrentHashMap<>();

    /** Texto que debe ir antes del token para iniciar sesión. */
    private static final String AUTH_PREFIX = "AUTH:";

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
        return evento("auth_required", "sessionId", sessionId);
    }

    /**
     * Recibe los mensajes del usuario.
     * Si no ha iniciado sesión, valida el token. Si ya está dentro, responde en streaming.
     *
     * @param sessionId Identificador de la sesión de chat.
     * @param mensaje El texto que envía el usuario.
     * @param conexion La conexión actual.
     * @return Flujo de mensajes JSON hacia el cliente.
     */
    @OnTextMessage
    @Blocking
    public Multi<String> alRecibirMensaje(@PathParam("sessionId") String sessionId,
                                          String mensaje,
                                          WebSocketConnection conexion) {

        AuthState estado = estadoConexiones.get(conexion.id());

        // Si no hay estado de conexión, cerramos para evitar errores
        if (estado == null) {
            Log.errorf("Estado de conexión no encontrado para connectionId: %s. Cerrando.", conexion.id());
            return enviarYCerrar(evento("error", "reason", "Estado de conexión corrupto"), conexion);
        }

        // Al devolver Multi, Quarkus no garantiza un hilo de trabajo: la autenticación
        // (consulta a BD) y la llamada al asistente (RAG, JDBC, embeddings) se ejecutan
        // explícitamente en el pool de workers para no bloquear el event loop.
        if (!estado.authenticated()) {
            return Multi.createFrom().deferred(() -> autenticar(sessionId, mensaje, conexion))
                    .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
        }

        ContextoSesion contexto = new ContextoSesion(sessionId, estado.idEmpresa(), estado.nombreUsuario());
        return Multi.createFrom().deferred(() -> responder(contexto, mensaje))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                // Errores al arrancar la respuesta (antes de emitir nada) también llegan como evento
                .onFailure().recoverWithItem(e -> evento("error", "reason", mensajeDeError(contexto, e)));
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

    // -------------------------------------------------------------------------
    // AUTENTICACIÓN
    // -------------------------------------------------------------------------

    /** Valida el primer mensaje (AUTH:&lt;jwt&gt;) y la propiedad de la sesión. */
    private Multi<String> autenticar(String sessionId, String mensaje, WebSocketConnection conexion) {
        if (!mensaje.startsWith(AUTH_PREFIX)) {
            Log.warnf("Primer mensaje sin AUTH: para sessionId: %s. Cerrando.", sessionId);
            return enviarYCerrar(evento("auth_failed", "reason", "El primer mensaje debe ser AUTH:<token>"), conexion);
        }

        Optional<AuthState> resultado = jwtValidator.validate(mensaje.substring(AUTH_PREFIX.length()).trim());
        if (resultado.isEmpty()) {
            Log.warnf("Autenticación fallida para sessionId: %s. Cerrando.", sessionId);
            return enviarYCerrar(evento("auth_failed", "reason", "Token JWT inválido o expirado"), conexion);
        }
        AuthState autenticado = resultado.get();

        if (!esSessionIdValido(sessionId)) {
            Log.warnf("sessionId con formato inválido: %s. Cerrando.", sessionId);
            return enviarYCerrar(evento("session_unavailable", "reason", "Identificador de sesión inválido"), conexion);
        }

        if (!chatService.puedeUsarSesion(sessionId, autenticado.nombreUsuario())) {
            // La sesión existe y es de otro usuario: no se revela nada de ella.
            // No es un fallo de login, así que el cliente no debe cerrar la sesión del usuario.
            Log.warnf("Usuario '%s' intentó abrir la sesión ajena %s. Cerrando.",
                    autenticado.nombreUsuario(), sessionId);
            return enviarYCerrar(evento("session_unavailable", "reason", "Sesión no disponible"), conexion);
        }

        estadoConexiones.put(conexion.id(), autenticado);
        Log.infof("WebSocket autenticado. user=%s, tenant=%s, sessionId=%s",
                autenticado.nombreUsuario(), autenticado.idEmpresa(), sessionId);
        return uno(evento("auth_success", "nombreUsuario", autenticado.nombreUsuario()));
    }

    // -------------------------------------------------------------------------
    // RESPUESTA EN STREAMING
    // -------------------------------------------------------------------------

    /**
     * Envía la respuesta del asistente fragmento a fragmento.
     * Los bloques &lt;thinking&gt; se filtran sobre la marcha, aunque lleguen partidos.
     */
    private Multi<String> responder(ContextoSesion contexto, String mensaje) {
        FiltroThinking filtro = new FiltroThinking();

        return asistenteService.chat(contexto, mensaje)
                .map(filtro::procesar)
                .onCompletion().continueWith(() -> List.of(filtro.finalizar()))
                .filter(texto -> !texto.isEmpty())
                .map(texto -> evento("token", "content", texto))
                .onCompletion().continueWith(evento("end", null, null))
                .onFailure().recoverWithItem(e -> evento("error", "reason", mensajeDeError(contexto, e)));
    }

    /** Traduce una excepción a un mensaje para el usuario, sin detalles internos. */
    private static String mensajeDeError(ContextoSesion contexto, Throwable e) {
        String msg = e.getMessage() != null ? e.getMessage() : "";
        if (msg.contains("429") || msg.contains("RATE_LIMIT")
                || msg.contains("quota") || msg.contains("exhausted")) {
            Log.warnf("Rate limit alcanzado para sesión %s: %s", contexto, msg);
            return "Límite de velocidad de la API alcanzado. Espera unos segundos antes de continuar.";
        }
        // Fallos puntuales del proveedor: servicio saturado o respuesta mal formada
        // (Gemini devuelve a veces finishReason=MALFORMED_RESPONSE). No se reintenta
        // aquí porque la pregunta ya está en la memoria y quedaría duplicada.
        if (msg.contains("503") || msg.contains("UNAVAILABLE") || msg.contains("high demand")
                || msg.contains("MALFORMED")) {
            Log.warnf("Fallo transitorio del modelo en sesión %s: %s", contexto, msg);
            return "El modelo de IA no ha respondido correctamente (puede estar saturado). Vuelve a enviar la pregunta.";
        }
        Log.errorf(e, "Error procesando mensaje de sesión %s", contexto);
        return "Error al procesar tu consulta. Por favor, inténtalo de nuevo.";
    }

    // -------------------------------------------------------------------------
    // UTILIDADES
    // -------------------------------------------------------------------------

    /** Construye un mensaje JSON {"type": tipo, clave: valor} con el escapado correcto. */
    private String evento(String tipo, String clave, String valor) {
        Map<String, String> json = new LinkedHashMap<>();
        json.put("type", tipo);
        if (clave != null) {
            json.put(clave, valor);
        }
        try {
            return objectMapper.writeValueAsString(json);
        } catch (JsonProcessingException e) {
            // No debería ocurrir con un mapa de strings
            return "{\"type\":\"error\",\"reason\":\"Error interno\"}";
        }
    }

    private static Multi<String> uno(String evento) {
        return Multi.createFrom().item(evento);
    }

    /**
     * Envía un último mensaje y después cierra la conexión.
     * Cerrar antes de devolver el mensaje provocaba que el cliente no lo recibiera.
     */
    private Multi<String> enviarYCerrar(String evento, WebSocketConnection conexion) {
        estadoConexiones.remove(conexion.id());
        return uno(evento).onCompletion().call(conexion::close);
    }

    /** El sessionId lo genera el cliente con crypto.randomUUID(): se exige formato UUID. */
    private static boolean esSessionIdValido(String sessionId) {
        try {
            UUID.fromString(sessionId);
            return true;
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }

}
