package com.gonzalo.tfg.security;

/**
 * Identificador de memoria de una conversación (@MemoryId) que además lleva
 * la empresa y el usuario autenticados.
 *
 * Con respuestas en streaming, la recuperación RAG y la ejecución de
 * herramientas pueden ocurrir en hilos distintos al del WebSocket, así que un
 * ThreadLocal no es fiable. LangChain4j propaga el @MemoryId a todas las fases
 * (memoria, QueryRouter vía query.metadata().chatMemoryId() y herramientas vía
 * @ToolMemoryId), de modo que la empresa viaja con la propia petición.
 *
 * @param sessionId     Identificador de la sesión de chat (UUID generado por el cliente).
 * @param idEmpresa     Empresa (tenant) del claim company_id del JWT.
 * @param nombreUsuario Usuario del claim sub del JWT.
 */
public record ContextoSesion(String sessionId, String idEmpresa, String nombreUsuario) {

    public ContextoSesion {
        if (sessionId == null || idEmpresa == null || nombreUsuario == null) {
            throw new IllegalArgumentException("sessionId, idEmpresa y nombreUsuario son obligatorios");
        }
    }

    /**
     * Extrae la empresa de un memoryId genérico.
     *
     * @return el idEmpresa, o null si el objeto no es un ContextoSesion.
     */
    public static String empresaDe(Object memoryId) {
        return memoryId instanceof ContextoSesion ctx ? ctx.idEmpresa() : null;
    }

    /** Se usa en logs: no expone datos de la empresa ni del usuario. */
    @Override
    public String toString() {
        return sessionId;
    }
}
