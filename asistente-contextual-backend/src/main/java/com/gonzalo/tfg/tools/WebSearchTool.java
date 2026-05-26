package com.gonzalo.tfg.tools;

import java.net.URLEncoder;
import java.util.stream.Collectors;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import dev.langchain4j.agent.tool.Tool;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class WebSearchTool {

    @Tool("Busca en internet información actualizada (noticias, precios, tendencias de 2026).")
    public String buscarEnWebGratis(String query) {
        Log.infof("Investigando en la red: %s", query);
        try {
            String url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8") + "&kl=es-es";

            Document doc = Jsoup.connect(url)
                    .userAgent(
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .header("Accept-Language", "es-ES,es;q=0.9")
                    .timeout(15000)
                    .get();

            Elements results = doc.select(".result__snippet, .snippet, .web-result__snippet, .links_main");

            if (results.isEmpty()) {
                results = doc.select("a.result__a");
            }

            String resultado = results.stream()
                    .limit(3)
                    .map(Element::text)
                    .filter(text -> text.length() > 20)
                    .collect(Collectors.joining("\n\n---\n\n"));

            resultado = resultado.replaceAll("\\s{3,}", "\n\n").trim();

            if (resultado.length() > 3200) {
                resultado = resultado.substring(0, 3200) + "\n[...resultado truncado para optimizar tokens]";
            }

            return resultado;   
        } catch (Exception e) {
            return "Error técnico en la búsqueda: " + e.getMessage();
        }
    }
}