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
 * Configuración avanzada del motor de Generación Aumentada por Recuperación
 * (RAG).
 * Arquitectura Nivel 3: Híbrido (Semántico + Keyword) + RRF + Re-Ranking
 * Manual.
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
            String input = query.text().toLowerCase();

            // Bloqueo de seguridad: Prioridad absoluta a la Tool de Inventario
            if (input.matches(".*(lista|qué documentos|tienes|inventario|ficheros).*")) {
                return Collections.emptyList();
            }

            List<DocumentoDTO> todosLosDocs = ingestionService.listarDocumentos();
            List<ContentRetriever> recuperadoresSeleccionados = new ArrayList<>();

            // Identificamos TODOS los documentos mencionados en la consulta
            for (DocumentoDTO doc : todosLosDocs) {
                String nombreBase = doc.nombre().toLowerCase().split("_")[0];
                if (input.contains(nombreBase) || input.contains(doc.nombre().toLowerCase())) {
                    Log.infof("Enrutador: Coincidencia detectada para [%s]. Añadiendo al flujo.", doc.nombre());

                    // Filtro específico por metadatos (Aislamiento de contexto)
                    recuperadoresSeleccionados.add(EmbeddingStoreContentRetriever.builder()
                            .embeddingStore(storeVectores)
                            .embeddingModel(modeloVectores)
                            .maxResults(15)
                            .minScore(0.65) // Bajamos a 0.65 para ganar riqueza en consultas cortas
                            .filter(MetadataFilterBuilder.metadataKey("nombre_archivo").isEqualTo(doc.nombre()))
                            .build());

                    // Añadimos el motor de palabras clave para este archivo específico
                    recuperadoresSeleccionados.add(postgresKeywordRetriever.crearRetriever(doc.nombre()));
                }
            }

            // SI SE DETECTARON ARCHIVOS: Devolvemos solo lo relevante para esos archivos
            if (!recuperadoresSeleccionados.isEmpty()) {
                return recuperadoresSeleccionados;
            }

            // SI NO SE DETECTARON ARCHIVOS (Búsqueda Global):
            // Estrategia: Si la consulta es muy corta (< 10 caracteres), somos más
            // permisivos con el score.
            double umbralDinamico = input.length() < 10 ? 0.60 : 0.70;

            Log.debugf("🔍 Enrutador: Búsqueda global activa. Umbral dinámico: %.2f", umbralDinamico);
            return Arrays.asList(
                    EmbeddingStoreContentRetriever.builder()
                            .embeddingStore(storeVectores)
                            .embeddingModel(modeloVectores)
                            .maxResults(20) // Más candidatos para que el RRF tenga donde elegir
                            .minScore(umbralDinamico)
                            .build(),
                    postgresKeywordRetriever.crearRetriever(null));
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

            if (candidatosRRF.isEmpty())
                return candidatosRRF;

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
                            String fuente = e.getKey().textSegment().metadata().getString("nombre_archivo")
                                    .toLowerCase();
                            if (qLower.contains("anteproyecto") && !fuente.contains("anteproyecto"))
                                return false;
                            if (qLower.contains("normas") && !fuente.contains("normas"))
                                return false;
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
                .promptTemplate(PromptTemplate.from(
                        "{{userMessage}}\n\n" +
                                "--- Instrucciones de respuesta ---\n" +
                                "Responde EXCLUSIVAMENTE con la información del catálogo inyectada a continuación:\n\n{{contents}}"))
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