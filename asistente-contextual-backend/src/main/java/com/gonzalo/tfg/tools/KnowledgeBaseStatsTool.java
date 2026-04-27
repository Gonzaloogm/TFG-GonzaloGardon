package com.gonzalo.tfg.tools;

import com.gonzalo.tfg.model.DocumentoDTO;
import com.gonzalo.tfg.service.DocumentIngestionService;
import dev.langchain4j.agent.tool.Tool;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class KnowledgeBaseStatsTool {

    @Inject
    DocumentIngestionService servicioIngestion;

    private static final int MAX_DOCUMENTOS_A_LISTAR = 15;

    // Prompt de la herramienta optimizado para evitar falsos positivos del LLM
    @Tool("Devuelve métricas operativas de la base de conocimiento (conteo, tipos, tecnologías, tamaño). " +
            "Úsala SOLAMENTE cuando el usuario pregunte explícitamente por estadísticas, cantidad de archivos " +
            "o un resumen general del catálogo. NO la uses para responder preguntas sobre el contenido semántico de los textos.")
    public String obtenerEstadisticasBaseConocimiento() {
        Log.info("El motor de IA solicita estadísticas de la base de conocimiento.");

        List<DocumentoDTO> documentos = servicioIngestion.listarDocumentos();

        if (documentos.isEmpty()) {
            return "La base de conocimiento está vacía. No hay documentos indexados.";
        }

        Map<String, Long> porTipo = documentos.stream()
                .collect(Collectors.groupingBy(DocumentoDTO::tipo, Collectors.counting()));

        String tecnologias = documentos.stream()
                .map(d -> d.metadatos() != null ? d.metadatos().getOrDefault("tecnologias", "") : "")
                .filter(t -> !t.isBlank())
                .flatMap(t -> java.util.Arrays.stream(t.split(",\\s*")))
                .map(String::trim)
                .filter(t -> !t.isBlank())
                .distinct()
                .sorted()
                .collect(Collectors.joining(", "));

        long caracteresTotales = documentos.stream()
                .mapToLong(d -> d.tamanioBytes() != null ? d.tamanioBytes() : 0L)
                .sum();

        StringBuilder sb = new StringBuilder();
        sb.append("Estado de la base de conocimiento:\n\n");
        sb.append("- Documentos totales: ").append(documentos.size()).append("\n");
        sb.append("- Tamaño total indexado: ").append(caracteresTotales / 1024).append(" KB\n\n");

        sb.append("Distribución por formato:\n");
        porTipo.forEach((tipo, count) -> sb.append("  • ").append(tipo).append(": ").append(count).append("\n"));

        if (!tecnologias.isBlank()) {
            sb.append("\nTecnologías / Categorías identificadas:\n");
            sb.append("  ").append(tecnologias).append("\n");
        }

        sb.append("\nCatálogo de documentos ");
        if (documentos.size() > MAX_DOCUMENTOS_A_LISTAR) {
            sb.append("(Mostrando los ").append(MAX_DOCUMENTOS_A_LISTAR).append(" primeros):\n");
        } else {
            sb.append("disponibles:\n");
        }

        documentos.stream()
                .limit(MAX_DOCUMENTOS_A_LISTAR)
                .forEach(d -> sb.append("  • ").append(d.nombre())
                        .append(" (").append(d.tamanioBytes() / 1024).append(" KB)\n"));

        if (documentos.size() > MAX_DOCUMENTOS_A_LISTAR) {
            sb.append("  • ... y ").append(documentos.size() - MAX_DOCUMENTOS_A_LISTAR).append(" documentos más.\n");
        }

        return sb.toString();
    }
}