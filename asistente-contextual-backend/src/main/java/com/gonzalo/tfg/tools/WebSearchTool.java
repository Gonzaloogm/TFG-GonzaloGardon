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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class WebSearchTool {

    @Tool("Busca información externa en internet (noticias, tendencias, datos de 2026) cuando no esté en los documentos locales.")
    public String buscarEnWebGratis(String query) {
        Log.infof("Ejecutando búsqueda web gratuita para: '%s'", query);

        try {
            // DuckDuckGo en modo HTML (ligero y fácil de procesar)
            String url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);

            Document doc = Jsoup.connect(url)
                    .userAgent(
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .timeout(10000)
                    .get();

            Elements links = doc.select(".result__body");
            List<String> resultados = new ArrayList<>();

            // Extraemos los 4 mejores resultados
            for (Element link : links) {
                if (resultados.size() >= 4)
                    break;

                String titulo = link.select(".result__title").text();
                String snippet = link.select(".result__snippet").text();
                String linkUrl = link.select(".result__url").text();

                if (!titulo.isEmpty() && !snippet.isEmpty()) {
                    resultados.add(String.format("**Fuente Web**\n- Título: %s\n- Resumen: %s\n- Link: %s",
                            titulo, snippet, linkUrl));
                }
            }

            if (resultados.isEmpty())
                return "No se han encontrado resultados públicos para esta búsqueda.";

            return resultados.stream().collect(Collectors.joining("\n\n---\n\n"));

        } catch (Exception e) {
            Log.error("Error en el WebSearchTool gratuito", e);
            return "No he podido conectar con el buscador externo por un problema técnico local.";
        }
    }
}