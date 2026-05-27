package com.gonzalo.tfg.tools;

import java.net.URLEncoder;
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

/**
 * Herramienta de búsqueda web e información financiera en tiempo real.
 *
 * Expone dos tools al LLM:
 * 1. buscarEnWebGratis() — búsqueda general en DuckDuckGo HTML
 * 2. obtenerPrecioFinanciero() — tipos de cambio via Frankfurter (BCE) con
 * fallback automático a búsqueda web para
 * activos no cubiertos (cripto, materias primas)
 */
@ApplicationScoped
public class WebSearchTool {

    @Inject
    ObjectMapper objectMapper;

    /**
     * Símbolos soportados nativamente por Frankfurter (BCE).
     * Para cualquier otro símbolo se aplica fallback a búsqueda web.
     */
    private static final Set<String> SIMBOLOS_BCE = Set.of(
            "USD", "GBP", "JPY", "CHF", "CAD", "AUD", "NZD", "SEK", "NOK",
            "DKK", "PLN", "CZK", "HUF", "RON", "BGN", "HRK", "ISK",
            "TRY", "BRL", "CNY", "HKD", "SGD", "KRW", "MXN", "ZAR",
            "INR", "IDR", "MYR", "PHP", "THB");

    // -------------------------------------------------------------------------
    // TOOL 1 — BÚSQUEDA WEB GENERAL
    // -------------------------------------------------------------------------

    @Tool("USA ESTA TOOL para buscar en internet cualquier información actualizada " +
            "(noticias, empresas, personas, eventos, tendencias de mercado, precios no financieros). " +
            "Parámetro 'query': texto EXACTO reformulado del mensaje ACTUAL del usuario. " +
            "OBLIGATORIO: ejecuta esta tool con una query nueva para CADA pregunta. " +
            "El resultado de una búsqueda anterior NO sirve para responder otra pregunta distinta.")
    public String buscarEnWebGratis(@P("Texto de búsqueda reformulado del mensaje actual del usuario") String query) {
        Log.infof("[WebSearchTool] buscarEnWebGratis invocada. Query: '%s'", query);
        try {
            String timestamp = String.valueOf(System.currentTimeMillis());
            String url = "https://html.duckduckgo.com/html/?q="
                    + URLEncoder.encode(query, "UTF-8")
                    + "&kl=es-es&t=" + timestamp;

            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .header("Accept-Language", "es-ES,es;q=0.9")
                    .header("Cache-Control", "no-cache, no-store")
                    .timeout(15000)
                    .get();

            Elements results = doc.select(".result__snippet, .snippet, .web-result__snippet, .links_main");
            if (results.isEmpty()) {
                results = doc.select("a.result__a");
            }

            String resultado = results.stream()
                    .limit(3)
                    .map(Element::text)
                    .filter(text -> text.length() > 10)
                    .collect(Collectors.joining("\n\n---\n\n"));

            // Limpia URLs relativas mal formadas que DuckDuckGo introduce ocasionalmente
            resultado = resultado.replaceAll("http://localhost:\\d+/([a-zA-Z])", "https://$1");
            resultado = resultado.replaceAll("\\s{3,}", "\n\n").trim();

            if (resultado.isBlank()) {
                return "No se encontraron resultados para: " + query;
            }
            if (resultado.length() > 3200) {
                resultado = resultado.substring(0, 3200) + "\n[resultado truncado]";
            }

            return resultado;

        } catch (Exception e) {
            Log.warnf("[WebSearchTool] Error en búsqueda web: %s", e.getMessage());
            return "Error técnico en la búsqueda: " + e.getMessage();
        }
    }

    // -------------------------------------------------------------------------
    // TOOL 2 — PRECIOS FINANCIEROS (BCE via Frankfurter + fallback web)
    // -------------------------------------------------------------------------

    @Tool("USA ESTA TOOL para obtener el tipo de cambio o precio EXACTO y ACTUAL de divisas " +
            "y activos financieros. Úsala cuando el usuario pregunte por: tipos de cambio entre monedas " +
            "(EUR/GBP, USD/EUR, etc.), precio del oro, plata, Bitcoin, o cualquier activo financiero. " +
            "Parámetro 'simbolo': código ISO del activo. Ejemplos: 'GBP' (libra), 'USD' (dólar), " +
            "'CHF' (franco suizo), 'JPY' (yen), 'XAU' (oro), 'XAG' (plata), 'BTC' (Bitcoin).")
    public String obtenerPrecioFinanciero(
            @P("Código ISO del activo financiero: GBP, USD, XAU, BTC, etc.") String simbolo) {

        if (simbolo == null || simbolo.isBlank()) {
            return "Símbolo financiero no válido. Proporciona un código ISO como GBP, USD, XAU o BTC.";
        }

        String simboloNorm = simbolo.trim().toUpperCase();
        Log.infof("[WebSearchTool] obtenerPrecioFinanciero invocada. Símbolo: '%s'", simboloNorm);

        // EUR consultado al revés (Frankfurter necesita from != to)
        if (simboloNorm.equals("EUR")) {
            simboloNorm = "USD";
            Log.debugf("[WebSearchTool] EUR solicitado — consultando USD/EUR como proxy");
        }

        // Activos NO cubiertos por BCE → fallback directo a búsqueda web
        if (!SIMBOLOS_BCE.contains(simboloNorm)) {
            Log.infof("[WebSearchTool] '{}' no está en BCE. Usando fallback web.", simboloNorm);
            return buscarEnWebGratis("precio actual " + simboloNorm + " en euros hoy " + LocalDate.now());
        }

        // Consulta a Frankfurter (datos oficiales del Banco Central Europeo)
        try {
            String apiUrl = "https://api.frankfurter.app/latest?from=" + simboloNorm + "&to=EUR,USD";
            Log.debugf("[WebSearchTool] Consultando Frankfurter: %s", apiUrl);

            String jsonRaw = Jsoup.connect(apiUrl)
                    .ignoreContentType(true)
                    .header("Accept", "application/json")
                    .timeout(8000)
                    .get()
                    .body()
                    .text();

            return parsearRespuestaFrankfurter(jsonRaw, simboloNorm);

        } catch (Exception e) {
            Log.warnf("[WebSearchTool] Frankfurter no disponible para '%s': %s. Activando fallback web.",
                    simboloNorm, e.getMessage());
            return buscarEnWebGratis("tipo de cambio " + simboloNorm + " euro hoy " + LocalDate.now());
        }
    }

    // -------------------------------------------------------------------------
    // MÉTODOS PRIVADOS
    // -------------------------------------------------------------------------

    /**
     * Parsea la respuesta JSON de Frankfurter y construye un mensaje legible.
     *
     * Ejemplo de respuesta Frankfurter:
     * {"amount":1.0,"base":"GBP","date":"2026-05-27","rates":{"EUR":1.1745,"USD":1.3521}}
     */
    private String parsearRespuestaFrankfurter(String json, String simbolo) {
        try {
            JsonNode root = objectMapper.readTree(json);

            String fecha = root.path("date").asText("desconocida");
            JsonNode rates = root.path("rates");

            StringBuilder sb = new StringBuilder();
            sb.append("Tipo de cambio oficial (Banco Central Europeo) — ").append(fecha).append(":\n");
            sb.append("1 ").append(simbolo).append(" equivale a:\n");

            rates.fields().forEachRemaining(entry -> {
                String moneda = entry.getKey();
                double valor = entry.getValue().asDouble();
                sb.append(String.format("  • %.4f %s%n", valor, moneda));
            });

            sb.append("\nFuente: Frankfurter API (datos BCE oficiales)");
            return sb.toString();

        } catch (Exception e) {
            Log.warnf("[WebSearchTool] Error parseando JSON de Frankfurter: %s", e.getMessage());
            // Si el parseo falla, devuelve el JSON crudo — mejor que nada
            return "Datos de cambio para " + simbolo + ": " + json;
        }
    }
}