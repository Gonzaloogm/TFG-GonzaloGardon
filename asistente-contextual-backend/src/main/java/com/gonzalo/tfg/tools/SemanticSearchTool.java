package com.gonzalo.tfg.tools;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Búsqueda semántica ad-hoc en el vector store, sin pasar por el pipeline RAG.
 *
 * El pipeline RAG normal recupera fragmentos para aumentar el contexto del LLM.
 * Esta tool permite al LLM hacer búsquedas exploratorias directas:
 * "¿En qué documentos se menciona Kafka?" o "¿Dónde aparece el concepto de
 * embeddings?"
 *
 * Útil cuando el usuario quiere localizar información sin formular una pregunta
 * completa.
 */
@ApplicationScoped
public class SemanticSearchTool {

    @Inject
    EmbeddingStore<TextSegment> storeVectores;

    @Inject
    EmbeddingModel modeloVectores;

    private static final int MAX_RESULTADOS = 5;
    private static final double MIN_RELEVANCIA = 0.65;

    @Tool("SOLO úsala si el contexto inyectado automáticamente no es suficiente para responder. Realiza una búsqueda semántica auxiliar en la base de conocimiento...")
    public String buscarFragmentosSemanticamente(String consulta) {
        Log.infof("El motor de IA solicita búsqueda semántica directa para: '%s'", consulta);

        if (consulta == null || consulta.isBlank()) {
            return "La consulta no puede estar vacía.";
        }

        try {
            Embedding vectorConsulta = modeloVectores.embed(consulta).content();

            EmbeddingSearchRequest peticion = EmbeddingSearchRequest.builder()
                    .queryEmbedding(vectorConsulta)
                    .maxResults(MAX_RESULTADOS)
                    .minScore(MIN_RELEVANCIA)
                    .build();

            List<EmbeddingMatch<TextSegment>> coincidencias = storeVectores.search(peticion).matches();

            if (coincidencias.isEmpty()) {
                return "No se encontraron fragmentos relevantes para «" + consulta
                        + "» con un umbral de relevancia del " + (int) (MIN_RELEVANCIA * 100) + "%.";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Fragmentos más relevantes para «").append(consulta).append("»:\n\n");

            for (int i = 0; i < coincidencias.size(); i++) {
                EmbeddingMatch<TextSegment> match = coincidencias.get(i);
                String nombreDoc = match.embedded().metadata().getString("nombre_archivo");
                String entidades = match.embedded().metadata().getString("entidades");
                double score = match.score();

                sb.append(i + 1).append(". [").append(String.format("%.0f%%", score * 100)).append(" relevancia]");
                sb.append(" Fuente: ").append(nombreDoc != null ? nombreDoc : "desconocido").append("\n");

                if (entidades != null && !entidades.isBlank()) {
                    sb.append("   Entidades: ").append(entidades).append("\n");
                }

                String fragmento = match.embedded().text();
                if (fragmento.length() > 300) {
                    fragmento = fragmento.substring(0, 300) + "...";
                }
                sb.append("   ").append(fragmento).append("\n\n");
            }

            return sb.toString();

        } catch (Exception e) {
            Log.errorf("Error en búsqueda semántica directa: %s", e.getMessage());
            return "Error al realizar la búsqueda semántica: " + e.getMessage();
        }
    }

    @Tool("SOLO úsala si el usuario pide buscar en un documento específico Y la información no está ya presente en tu contexto actual...")
    public String buscarEnDocumentoConcreto(String nombreDocumento, String consulta) {
        Log.infof("Búsqueda semántica en documento '%s' para: '%s'", nombreDocumento, consulta);

        if (nombreDocumento == null || nombreDocumento.isBlank()) {
            return "Debes especificar el nombre del documento.";
        }
        if (consulta == null || consulta.isBlank()) {
            return "La consulta no puede estar vacía.";
        }

        try {
            Embedding vectorConsulta = modeloVectores.embed(consulta).content();

            dev.langchain4j.store.embedding.filter.Filter filtroDoc = dev.langchain4j.store.embedding.filter.MetadataFilterBuilder
                    .metadataKey("nombre_archivo")
                    .isEqualTo(nombreDocumento);

            EmbeddingSearchRequest peticion = EmbeddingSearchRequest.builder()
                    .queryEmbedding(vectorConsulta)
                    .maxResults(MAX_RESULTADOS)
                    .minScore(0.55) // Umbral más permisivo al buscar en doc concreto
                    .filter(filtroDoc)
                    .build();

            List<EmbeddingMatch<TextSegment>> coincidencias = storeVectores.search(peticion).matches();

            if (coincidencias.isEmpty()) {
                return "No se encontraron fragmentos relevantes sobre «" + consulta + "» en el documento «"
                        + nombreDocumento + "».";
            }

            return coincidencias.stream()
                    .map(m -> String.format("[%.0f%% relevancia] %s", m.score() * 100, m.embedded().text()))
                    .collect(Collectors.joining("\n\n---\n\n",
                            "Fragmentos de «" + nombreDocumento + "» sobre «" + consulta + "»:\n\n", ""));

        } catch (Exception e) {
            Log.errorf("Error en búsqueda por documento: %s", e.getMessage());
            return "Error al buscar en el documento: " + e.getMessage();
        }
    }
}