package com.gonzalo.tfg.security;

/**
 * Guarda el identificador de empresa del usuario actual para que otros componentes puedan acceder a él durante la misma petición.
 */
public final class TenantContext {

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    /**
     * Guarda el ID de la empresa para la petición actual.
     *
     * @param idEmpresa Identificador de la empresa.
     */
    public static void set(String idEmpresa) {
        CURRENT_TENANT.set(idEmpresa);
    }

    /**
     * Devuelve el ID de la empresa del usuario que está haciendo la petición.
     *
     * @return El ID de la empresa, o null si no hay ninguna.
     */
    public static String get() {
        return CURRENT_TENANT.get();
    }

    /**
     * Borra la empresa guardada para evitar que se mezcle con otras peticiones.
     */
    public static void clear() {
        CURRENT_TENANT.remove();
    }

    private TenantContext() {
        // Esta clase no se puede instanciar porque es estática
    }
}
