package com.gonzalo.tfg.tools;

import dev.langchain4j.agent.tool.Tool;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

@ApplicationScoped
public class WebSearchTool {

    @Tool("Busca en internet información actualizada (noticias, precios, cuotas de mercado de 2026).")
    public String buscarEnWebGratis(String query) {
        Log.infof("🌐 Buscando: %s", query);
        try {
            // Usamos una URL de búsqueda más directa
            String url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);

            Document doc = Jsoup.connect(url)
                    .userAgent(
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36")
                    .timeout(10000)
                    .get();

            // DuckDuckGo HTML usa la clase .result__snippet para los resúmenes
            Elements snippets = doc.select(".result__snippet");
            if (snippets.isEmpty())
                return "No se han encontrado resultados públicos hoy.";

            return snippets.stream()
                    .limit(3) // Solo los 3 mejores para no saturar
                    .map(Element::text)
                    .collect(Collectors.joining("\n\n---\n\n"));

        } catch (Exception e) {
            return "Error en la conexión con el buscador: " + e.getMessage();
        }
    }
}