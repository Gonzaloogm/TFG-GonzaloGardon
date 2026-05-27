package com.gonzalo.tfg.tools;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class WebSearchTool {

    @Inject
    ObjectMapper objectMapper;

    // Símbolos soportados nativamente por el Banco Central Europeo (Frankfurter API)
    private static final Set<String> SIMBOLOS_BCE = Set.of(
            "USD", "GBP", "JPY", "CHF", "CAD", "AUD", "NZD", "SEK", "NOK",
            "DKK", "PLN", "CZK", "HUF", "RON", "BGN", "HRK", "ISK",
            "TRY", "BRL", "CNY", "HKD", "SGD", "KRW", "MXN", "ZAR",
            "INR", "IDR", "MYR", "PHP", "THB");

    @Tool("USA ESTA ÚNICA TOOL para buscar cualquier información en internet o precios financieros. " +
            "OBLIGATORIO: usa datos nuevos para cada pregunta. " +
            "Si buscas un precio financiero (divisas, criptos, oro), rellena 'simboloISO' (ej. GBP, BTC). " +
            "Si es una búsqueda general, deja 'simboloISO' vacío. " +
            "REGLA CRÍTICA DE PARADA: ESTÁ ESTRICTAMENTE PROHIBIDO ejecutar esta herramienta más de una vez por turno. " +
            "En cuanto recibas el resultado, redacta tu respuesta final al usuario INMEDIATAMENTE. NUNCA reintentes.")

    public String investigarEnInternet(
            @P("Texto EXACTO y reformulado de la búsqueda (obligatorio)") String query,
            @P("Código ISO (ej. GBP, BTC). Manda SOLO UNO. VACÍO si no es una consulta financiera") String simboloISO) {

        // Validamos si el LLM nos ha enviado una intención financiera
        boolean esFinanciero = simboloISO != null && !simboloISO.trim().isEmpty()
                && !simboloISO.equalsIgnoreCase("null")
                && !simboloISO.equalsIgnoreCase("n/a");

        // 1. ENRUTAMIENTO FINANCIERO OFICIAL (API BCE)
        if (esFinanciero) {
            String simboloNorm = simboloISO.toUpperCase().replaceAll("[^A-Z]", "");
            if (simboloNorm.length() > 3) {
                simboloNorm = simboloNorm.substring(0, 3);
            }
            Log.infof("[WebSearchTool] Ruta Financiera detectada para: '%s'", simboloNorm);

            String resultadoFinanciero = obtenerPrecioFinancieroInterno(simboloNorm);

            // Si la API tuvo éxito, devolvemos el dato oficial y terminamos
            if (!resultadoFinanciero.startsWith("ERROR_API")) {
                return resultadoFinanciero;
            }

            // GRACEFUL DEGRADATION: Si falló la API oficial, reconstruimos la query para forzar búsqueda web
            Log.warnf("[WebSearchTool] Fallo/No soportado en API oficial para '%s'. Ejecutando fallback a web.", simboloNorm);
            query = "precio actual " + simboloNorm + " en euros hoy " + LocalDate.now();
        }

        // 2. ENRUTAMIENTO A BÚSQUEDA WEB GENERAL (DuckDuckGo)
        Log.infof("[WebSearchTool] Ruta Web ejecutándose con query: '%s'", query);
        return buscarEnWebInterno(query);
    }

    private String buscarEnWebInterno(String query) {
        try {
            // Anti-Cache: System.currentTimeMillis() asegura que DuckDuckGo no nos devuelva una página vieja
            String url = "https://html.duckduckgo.com/html/?q="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
                    + "&kl=es-es&t=" + System.currentTimeMillis();

            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .header("Accept-Language", "es-ES,es;q=0.9")
                    .header("Cache-Control", "no-cache, no-store")
                    .timeout(15000)
                    .get();

            // Extraemos los snippets de texto
            Elements results = doc.select(".result__snippet, .snippet, .web-result__snippet, .links_main");
            if (results.isEmpty()) {
                results = doc.select("a.result__a");
            }

            String resultado = results.stream()
                    .limit(3) // Limitamos a 3 resultados para no saturar al LLM
                    .map(Element::text)
                    .filter(text -> text.length() > 10)
                    .collect(Collectors.joining("\n\n---\n\n"));

            // Limpieza de URLs relativas basura
            resultado = resultado.replaceAll("http://localhost:\\d+/([a-zA-Z])", "https://$1");
            resultado = resultado.replaceAll("\\s{3,}", "\n\n").trim();

            // ANTI-BUCLE 1: Si no hay resultados, ordenamos al LLM rendirse inmediatamente
            if (resultado.isBlank()) {
                Log.warnf("[WebSearchTool] Búsqueda vacía para query: '%s'", query);
                return "RESULTADO VACÍO. INSTRUCCIÓN OBLIGATORIA PARA EL LLM: Dile al usuario directamente que no has encontrado información sobre esto y DETENTE. NO VUELVAS A BUSCAR. NO REINTENTES.";
            }

            // OPTIMIZACIÓN DE TOKENS: Truncado estricto a 3200 caracteres (evita expulsar el SystemMessage de la memoria)
            if (resultado.length() > 3200) {
                resultado = resultado.substring(0, 3200) + "\n\n[...resultado truncado para optimizar tokens...]";
            }

            return resultado;

        } catch (Exception e) {
            Log.errorf("[WebSearchTool] Error técnico en búsqueda web: %s", e.getMessage());
            // ANTI-BUCLE 2: Si falla la red (Timeout/500), ordenamos al LLM rendirse
            return "FALLO DE RED. INSTRUCCIÓN OBLIGATORIA PARA EL LLM: Dile al usuario que los servicios de búsqueda externa están temporalmente caídos y DETENTE. NO REINTENTES.";
        }
    }

    private String obtenerPrecioFinancieroInterno(String simboloNorm) {
        // Proxy inverso: Frankfurter usa EUR como base implícita, si piden EUR buscamos contra USD
        if (simboloNorm.equals("EUR")) {
            simboloNorm = "USD";
        }

        // Si el activo es Oro (XAU), Bitcoin (BTC), etc., forzamos el error para ir al Fallback Web
        if (!SIMBOLOS_BCE.contains(simboloNorm)) {
            return "ERROR_API: Simbolo no soportado por el BCE (" + simboloNorm + ")";
        }

        try {
            String apiUrl = "https://api.frankfurter.app/latest?from=" + simboloNorm;
            String jsonRaw = Jsoup.connect(apiUrl)
                    .ignoreContentType(true)
                    .header("Accept", "application/json")
                    .timeout(8000)
                    .get()
                    .body()
                    .text();

            JsonNode root = objectMapper.readTree(jsonRaw);
            String fecha = root.path("date").asText("desconocida");
            JsonNode rates = root.path("rates");

            // Formateo del resultado para consumir los mínimos tokens posibles
            StringBuilder sb = new StringBuilder();
            sb.append("Tipo de cambio oficial (Banco Central Europeo) — ").append(fecha).append(":\n");
            sb.append("1 ").append(simboloNorm).append(" equivale a:\n");

            rates.fields().forEachRemaining(entry ->
                    sb.append(String.format("  • %.4f %s%n", entry.getValue().asDouble(), entry.getKey()))
            );

            sb.append("\nFuente: Frankfurter API (datos BCE oficiales)");
            return sb.toString();

        } catch (Exception e) {
            Log.warnf("[WebSearchTool] Error en API Frankfurter para %s: %s", simboloNorm, e.getMessage());
            return "ERROR_API: " + e.getMessage();
        }
    }
}