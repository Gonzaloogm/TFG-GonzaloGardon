package com.gonzalo.tfg.config;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reglas para reconocer consultas que no necesitan buscar en los documentos.
 *
 * Las usan el enrutador de consultas (para no recuperar nada) y el
 * transformador de consultas (para no gastar una llamada al LLM generando
 * variantes que luego se descartarían).
 */
public final class ClasificadorConsulta {

    private static final Pattern SALUDO = Pattern.compile(
            ".*(hola|buenos|buenas|gracias|adios|hasta luego|hey|ey|que tal|como estas|estas ahi|ahi estas).*");

    /** Palabras clave para detectar si el usuario pregunta por el catálogo. */
    private static final Set<String> PALABRAS_CATALOGO = Set.of(
            "lista", "documentos", "tienes", "inventario",
            "ficheros", "catalogo", "archivos");

    private ClasificadorConsulta() {
    }

    /** Quita tildes y pasa a minúsculas. */
    public static String normalizar(String texto) {
        if (texto == null)
            return "";
        String nfd = Normalizer.normalize(texto.toLowerCase(), Normalizer.Form.NFD);
        return nfd.replaceAll("\\p{InCombiningDiacriticalMarks}", "");
    }

    /** Saludo o fórmula de cortesía corta (hasta 3 palabras). */
    public static boolean esSaludo(String texto) {
        String t = normalizar(texto).trim();
        return !t.isEmpty() && t.split("\\s+").length <= 3 && SALUDO.matcher(t).matches();
    }

    /** Pregunta por los documentos disponibles: la responde una herramienta, no la búsqueda. */
    public static boolean esConsultaCatalogo(String texto) {
        return Arrays.stream(palabras(texto)).anyMatch(PALABRAS_CATALOGO::contains);
    }

    /** No necesita recuperar fragmentos de documentos. */
    public static boolean noNecesitaBusqueda(String texto) {
        return esSaludo(texto) || esConsultaCatalogo(texto);
    }

    private static String[] palabras(String texto) {
        return normalizar(texto).replaceAll("[\\p{Punct}¿¡]", " ").trim().split("\\s+");
    }
}
