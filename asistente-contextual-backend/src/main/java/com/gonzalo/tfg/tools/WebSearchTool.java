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
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class WebSearchTool {

    @Tool("Busca información actualizada en internet cuando no esté en los documentos locales.")
    public String buscarEnWebGratis(String query) {
        Log.infof("Buscando en internet: %s", query);
        try {
            // Buscamos en la versión HTML de DuckDuckGo (sin JS, más fácil de leer)
            String url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);

            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .get();

            Elements links = doc.select(".result__body");
            List<String> resultados = new ArrayList<>();

            for (Element link : links.subList(0, Math.min(links.size(), 4))) {
                String titulo = link.select(".result__title").text();
                String snippet = link.select(".result__snippet").text();
                String linkUrl = link.select(".result__url").text();
                resultados.add(String.format("Título: %s\nInfo: %s\nFuente: %s", titulo, snippet, linkUrl));
            }

            if (resultados.isEmpty())
                return "No se encontraron resultados públicos.";
            return resultados.stream().collect(Collectors.joining("\n\n---\n\n"));

        } catch (Exception e) {
            Log.error("Error en búsqueda gratuita", e);
            return "Error al conectar con el buscador: " + e.getMessage();
        }
    }
}