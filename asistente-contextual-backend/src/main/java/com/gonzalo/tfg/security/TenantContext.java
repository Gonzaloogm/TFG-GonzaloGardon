package com.gonzalo.tfg.security;

/**
 * Propagación thread-safe del {@code company_id} del tenant activo.
 *
 * <h3>¿Por qué ThreadLocal y no CDI {@code @RequestScoped}?</h3>
 * <ol>
 *   <li>El {@code RetrievalAugmentor} es un bean {@code @ApplicationScoped} (singleton).
 *       Si se inyectara un bean {@code @RequestScoped} dentro de él, CDI lanzaría una
 *       excepción de contexto porque el scope {@code @RequestScoped} no está activo
 *       en el momento de la inyección del singleton.</li>
 *   <li>El {@code ChatWebSocket} usa {@code @Blocking}, lo que mueve la ejecución
 *       a un worker thread de Vert.x. En ese modelo, {@code ThreadLocal} es seguro
 *       porque cada mensaje se procesa en un hilo dedicado durante toda su duración.</li>
 * </ol>
 *
 * <h3>ADVERTENCIA para migración futura</h3>
 * <p>Si en el futuro se elimina {@code @Blocking} y se adopta un modelo reactivo puro,
 * habría que reemplazar {@code ThreadLocal} por {@code Vertx.currentContext().putLocal("company_id", value)},
 * ya que en el event-loop de Vert.x un mismo hilo procesa múltiples requests concurrentes.</p>
 *
 * <h3>Patrón de uso obligatorio</h3>
 * <pre>{@code
 * TenantContext.set(companyId);
 * try {
 *     // ... lógica de negocio que lee TenantContext.get()
 * } finally {
 *     TenantContext.clear(); // OBLIGATORIO para evitar fugas entre requests
 * }
 * }</pre>
 */
public final class TenantContext {

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    /**
     * Establece el {@code company_id} del tenant para el hilo actual.
     *
     * @param companyId Identificador del tenant (nunca {@code null}).
     */
    public static void set(String companyId) {
        CURRENT_TENANT.set(companyId);
    }

    /**
     * Obtiene el {@code company_id} del tenant activo en el hilo actual.
     *
     * @return Identificador del tenant, o {@code null} si no hay tenant activo
     *         (modo desarrollo sin autenticación).
     */
    public static String get() {
        return CURRENT_TENANT.get();
    }

    /**
     * Limpia el contexto del tenant para el hilo actual.
     * <strong>Debe llamarse siempre en un bloque {@code finally}</strong>
     * para evitar fugas de contexto entre peticiones que reutilicen el mismo hilo.
     */
    public static void clear() {
        CURRENT_TENANT.remove();
    }

    private TenantContext() {
        // No instanciable — clase de utilidad estática
    }
}
