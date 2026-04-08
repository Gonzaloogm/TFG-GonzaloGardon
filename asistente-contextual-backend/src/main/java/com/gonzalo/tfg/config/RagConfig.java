package com.gonzalo.tfg.config;

// --- IMPORTS LIMPIOS AL PRINCIPIO ---
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.query.router.QueryRouter;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.content.injector.DefaultContentInjector;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import com.gonzalo.tfg.service.DocumentIngestionService;
import com.gonzalo.tfg.service.PostgresKeywordRetriever;
import com.gonzalo.tfg.model.DocumentoDTO;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Configuración avanzada del motor de Generación Aumentada por Recuperación (RAG).
 * Arquitectura Nivel 3: Híbrido (Semántico + Keyword) + RRF + Re-Ranking Manual.
 */
@ApplicationScoped
public class RagConfig {

    @Inject
    DocumentIngestionService ingestionService;

    @Inject
    PostgresKeywordRetriever postgresKeywordRetriever;

    @Produces
    @ApplicationScoped
    public RetrievalAugmentor configuradorRAG(
            EmbeddingStore<TextSegment> storeVectores,
            EmbeddingModel modeloVectores) {

        Log.info("Inicializando RetrievalAugmentor con Híbrido RRF y enrutamiento inteligente");

        // 1. DEFINICIÓN DEL ENRUTADOR INTELIGENTE
        QueryRouter enrutadorConsultas = query -> {
            String textoUsuario = query.text().toLowerCase();
            
            // Bloqueo de seguridad para evitar bucles de herramientas
            if (textoUsuario.matches(".*(lista|qué documentos|tienes|inventario|ficheros).*")) {
                Log.debug("Enrutador: Detectada consulta de catálogo. Vaciando RAG para forzar Tool.");
                return Collections.emptyList();
            }

            List<DocumentoDTO> documentos = ingestionService.listarDocumentos();

            for (DocumentoDTO doc : documentos) {
                String nombreSinExt = doc.nombre().contains(".")
                        ? doc.nombre().substring(0, doc.nombre().lastIndexOf('.'))
                        : doc.nombre();

                if (textoUsuario.contains(doc.nombre().toLowerCase())
                        || textoUsuario.contains(nombreSinExt.toLowerCase())) {
                    Log.infof("🔍 Enrutador Híbrido: Mención detectada para '%s'. Generando filtros.", doc.nombre());

                    ContentRetriever retrieverSemanticoFiltrado = EmbeddingStoreContentRetriever.builder()
                            .embeddingStore(storeVectores)
                            .embeddingModel(modeloVectores)
                            .maxResults(15)
                            .minScore(0.75)
                            .filter(MetadataFilterBuilder.metadataKey("nombre_archivo").isEqualTo(doc.nombre()))
                            .build();

                    ContentRetriever retrieverKeywordFiltrado = postgresKeywordRetriever.crearRetriever(doc.nombre());

                    return Arrays.asList(retrieverSemanticoFiltrado, retrieverKeywordFiltrado);
                }
            }

            Log.debug("Enrutador Híbrido: Búsqueda global en ambos motores.");
            return Arrays.asList(
                    EmbeddingStoreContentRetriever.builder()
                            .embeddingStore(storeVectores)
                            .embeddingModel(modeloVectores)
                            .maxResults(15)
                            .minScore(0.70)
                            .build(),
                    postgresKeywordRetriever.crearRetriever(null)
            );
        };

        // 2. SCORING MODEL PARA RE-RANKING (Similitud Coseno)
        ScoringModel scoringModel = new ScoringModel() {
            @Override
            public Response<List<Double>> scoreAll(List<TextSegment> segments, String query) {
                Embedding queryEmbedding = modeloVectores.embed(query).content();
                List<Embedding> segEmbeddings = modeloVectores.embedAll(segments).content();

                List<Double> scores = new ArrayList<>();
                for (Embedding segEmb : segEmbeddings) {
                    float[] vA = queryEmbedding.vector();
                    float[] vB = segEmb.vector();
                    double dotProduct = 0.0;
                    double normA = 0.0;
                    double normB = 0.0;
                    for (int i = 0; i < vA.length; i++) {
                        dotProduct += vA[i] * vB[i];
                        normA += Math.pow(vA[i], 2);
                        normB += Math.pow(vB[i], 2);
                    }
                    double cosineSim = dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
                    scores.add((cosineSim + 1.0) / 2.0);
                }
                return Response.from(scores);
            }
        };

        // 3. FUSIÓN DE RESULTADOS (RRF) Y RE-RANKING FINAL
        ContentAggregator agregadorRRF = queryToContents -> {
            Log.debug("RRF: Iniciando fusión de resultados híbridos.");
            int smoothingConstant = 60;
            Map<String, Double> contentScores = new HashMap<>();
            Map<String, Content> contentMap = new HashMap<>();

            for (Collection<List<Content>> coleccionRecuperadores : queryToContents.values()) {
                for (List<Content> resultados : coleccionRecuperadores) {
                    int rank = 1;
                    for (Content contenido : resultados) {
                        String id = contenido.textSegment().text();
                        double rrfScore = 1.0 / (smoothingConstant + rank);
                        contentScores.merge(id, rrfScore, Double::sum);
                        contentMap.putIfAbsent(id, contenido);
                        rank++;
                    }
                }
            }

            // Seleccionamos los 15 mejores candidatos RRF para el Re-Ranking
            List<Content> candidatosRRF = contentScores.entrySet().stream()
                    .sorted((e1, e2) -> Double.compare(e2.getValue(), e1.getValue()))
                    .limit(15)
                    .map(e -> contentMap.get(e.getKey()))
                    .collect(Collectors.toList());

            if (candidatosRRF.isEmpty()) return candidatosRRF;

            try {
                Log.debug("RE-RANKER: Aplicando Scoring Model sobre candidatos RRF.");
                List<TextSegment> segmentsToScore = candidatosRRF.stream()
                        .map(Content::textSegment)
                        .collect(Collectors.toList());

                String qText = queryToContents.keySet().iterator().next().text();
                List<Double> scores = scoringModel.scoreAll(segmentsToScore, qText).content();

                List<Map.Entry<Content, Double>> reranked = new ArrayList<>();
                for (int i = 0; i < candidatosRRF.size(); i++) {
                    reranked.add(new AbstractMap.SimpleEntry<>(candidatosRRF.get(i), scores.get(i)));
                }

                // El "Portero" de Seguridad: Filtrado final por mención de archivo
                String qLower = qText.toLowerCase();
                return reranked.stream()
                        .filter(e -> {
                            String fuente = e.getKey().textSegment().metadata().getString("nombre_archivo").toLowerCase();
                            if (qLower.contains("anteproyecto") && !fuente.contains("anteproyecto")) return false;
                            if (qLower.contains("normas") && !fuente.contains("normas")) return false;
                            return true;
                        })
                        .sorted((e1, e2) -> Double.compare(e2.getValue(), e1.getValue()))
                        .limit(5)
                        .map(Map.Entry::getKey)
                        .collect(Collectors.toList());

            } catch (Exception ex) {
                Log.error("Error en Re-Ranking, aplicando fallback RRF.", ex);
                return candidatosRRF.stream().limit(5).collect(Collectors.toList());
            }
        };

        // 4. INYECTOR DE CONTENIDO
        DefaultContentInjector inyectorContenido = DefaultContentInjector.builder()
                .metadataKeysToInclude(Arrays.asList("nombre_archivo", "entidades"))
                .promptTemplate(PromptTemplate.from("Responde EXCLUSIVAMENTE con esta información:\n\n{{contents}}"))
                .build();

        return DefaultRetrievalAugmentor.builder()
                .queryRouter(enrutadorConsultas)
                .contentAggregator(agregadorRRF)
                .contentInjector(inyectorContenido)
                .build();
    }

    public static ContentRetriever createFilteredRetriever(
            EmbeddingStore<TextSegment> embeddingStore,
            EmbeddingModel embeddingModel,
            String filterKey,
            String filterValue) {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(5)
                .minScore(0.70)
                .filter(MetadataFilterBuilder.metadataKey(filterKey).isEqualTo(filterValue))
                .build();
    }
}