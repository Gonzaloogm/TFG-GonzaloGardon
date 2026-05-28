package com.gonzalo.tfg.security;

/**
 * Estado de autenticación de una conexión WebSocket.
 *
 * Inmutable: una vez creado, su estado no cambia. Se reemplaza en el
 * {@code ConcurrentHashMap} al pasar de no-autenticado a autenticado,
 * garantizando visibilidad thread-safe sin sincronización adicional.
 *
 * @param authenticated {@code true} si el cliente ya envió un token JWT válido.
 * @param username      Nombre de usuario extraído del claim {@code sub} del JWT.
 * @param companyId     Identificador del tenant extraído del claim {@code company_id}.
 */
public record AuthState(
        boolean authenticated,
        String username,
        String companyId) {

    /**
     * Crea un estado pendiente de autenticación.
     * Se usa en {@code @OnOpen} para registrar una conexión recién abierta.
     */
    public static AuthState pending() {
        return new AuthState(false, null, null);
    }
}
