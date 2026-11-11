package com.gonzalo.tfg.config;

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
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;

import com.gonzalo.tfg.security.ContextoSesion;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import com.gonzalo.tfg.service.DocumentIngestionService;
import com.gonzalo.tfg.service.PostgresKeywordRetriever;
import com.gonzalo.tfg.model.DocumentoDTO;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Configura el motor de búsquedas para el asistente.
 * Combina búsquedas por significado y por palabras clave para mejorar los resultados.
 */
@ApplicationScoped
public class RagConfig {

    @Inject
    DocumentIngestionService ingestionService;

    @Inject
    PostgresKeywordRetriever postgresKeywordRetriever;

    @Inject
    CustomQueryTransformer customQueryTransformer;

    // -------------------------------------------------------------------------
    // CONSTANTES DE CONFIGURACIÓN
    // -------------------------------------------------------------------------

    /** Suaviza la mezcla de resultados para no descartar opciones válidas. */
    private static final int RRF_K = 60;

    /** Límite de resultados a evaluar para ahorrar peticiones al LLM. */
    private static final int MAX_RERANKING_CANDIDATES = 10;

    /** Límite de documentos que se enviarán finalmente al LLM. */
    private static final int MAX_RESULTADOS_FINALES = 5;

    /** Palabras comunes que no aportan significado a las búsquedas. */
    private static final Set<String> STOPWORDS_IDIOMA = Set.of(
            "dime", "este", "estos", "sobre", "todos", "para", "donde",
            "cual", "cuales", "quien", "como", "algun", "algunos", "cuál",
            "cuáles", "quién", "cómo", "algún");

    /** Palabras de relleno que usan los usuarios al preguntar. */
    private static final Set<String> STOPWORDS_RAG = Set.of(
            "dime", "puedes", "hacer", "esta", "investigacion", "especificas",
            "mencionan", "documentos", "especificamente");

    /** Todas las palabras a ignorar unidas en una sola lista. */
    private static final Set<String> TODAS_STOPWORDS = new HashSet<>();
    static {
        TODAS_STOPWORDS.addAll(STOPWORDS_IDIOMA);
        TODAS_STOPWORDS.addAll(STOPWORDS_RAG);
    }

    // -------------------------------------------------------------------------
    // PRODUCTOR DEL RETRIEVAL AUGMENTOR
    // -------------------------------------------------------------------------

    @Produces
    @ApplicationScoped
    public RetrievalAugmentor configuradorRAG(
            EmbeddingStore<TextSegment> storeVectores,
            EmbeddingModel modeloVectores) {

        Log.info("Inicializando RetrievalAugmentor con Híbrido RRF y enrutamiento inteligente");

        // Decide qué estrategia de búsqueda usar para la consulta
        QueryRouter enrutadorConsultas = query -> {

            String inputOriginal = query.text();
            // Quita tildes para evitar fallos de búsqueda
            String input = normalizarTexto(inputOriginal);

            // Obtiene la empresa actual para aislar sus documentos
            // La empresa viaja en el memoryId de la conversación (no en un ThreadLocal),
            // porque con streaming esta fase puede ejecutarse en otro hilo.
            String idEmpresa = query.metadata() == null ? null
                    : ContextoSesion.empresaDe(query.metadata().chatMemoryId());
            // Sin empresa no se recupera nada: nunca se busca en los documentos de todas
            if (idEmpresa == null) {
                Log.warn("Enrutador: consulta sin empresa en contexto. Sin recuperación.");
                return Collections.emptyList();
            }
            Filter filtroTenant = MetadataFilterBuilder.metadataKey("company_id").isEqualTo(idEmpresa);
            // Si es un saludo corto, no busca en documentos para ahorrar tiempo
            if (ClasificadorConsulta.esSaludo(inputOriginal)) {
                Log.debug("Enrutador: Detectado saludo/cortesía. Sin recuperación vectorial.");
                return Collections.emptyList();
            }

            // Si preguntan por los archivos disponibles, no hace búsqueda vectorial
            if (ClasificadorConsulta.esConsultaCatalogo(inputOriginal)) {
                Log.debug("Enrutador: Detectada consulta de catálogo. Delegando en Tool.");
                return Collections.emptyList();
            }

            // Limpia la consulta dejando solo las palabras clave importantes
            String inputSinPuntuacion = input.replaceAll("[\\p{Punct}¿¡]", " ");
            String queryParaKeywords = Arrays.stream(inputSinPuntuacion.split("\\s+"))
                    .filter(word -> word.length() > 3)
                    .filter(word -> !TODAS_STOPWORDS.contains(word)) // M1: O(1) lookup
                    .collect(Collectors.joining(" "))
                    .trim();

            if (queryParaKeywords.isEmpty()) {
                queryParaKeywords = inputSinPuntuacion.trim();
            }
            Log.infof("Query optimizada para Keywords: '%s'", queryParaKeywords);

            // Si menciona un documento concreto, busca directamente en él
            List<DocumentoDTO> todosLosDocs = ingestionService.listarDocumentos(idEmpresa);
            List<ContentRetriever> recuperadoresSeleccionados = new ArrayList<>();

            for (DocumentoDTO doc : todosLosDocs) {
                // M2: Normalizamos también el nombre del archivo para comparar sin tildes
                String nombreNormalizado = normalizarTexto(doc.nombre());
                String nombreBase = nombreNormalizado.contains(".")
                        ? nombreNormalizado.substring(0, nombreNormalizado.lastIndexOf('.'))
                        : nombreNormalizado;

                if (input.contains(nombreNormalizado) || input.contains(nombreBase)) {
                    Log.infof("Enrutador: Coincidencia detectada para [%s]", doc.nombre());

                    // Aplica filtro por documento y empresa
                    Filter filtroArchivo = MetadataFilterBuilder.metadataKey("nombre_archivo").isEqualTo(doc.nombre());
                    Filter filtroFinal = Filter.and(filtroArchivo, filtroTenant);

                    recuperadoresSeleccionados.add(EmbeddingStoreContentRetriever.builder()
                            .embeddingStore(storeVectores)
                            .embeddingModel(modeloVectores)
                            .maxResults(10)
                            .minScore(0.65)
                            .filter(filtroFinal)
                            .build());

                    recuperadoresSeleccionados.add(
                            postgresKeywordRetriever.crearRetriever(doc.nombre(), queryParaKeywords, idEmpresa));
                }
            }

            if (!recuperadoresSeleccionados.isEmpty()) {
                return recuperadoresSeleccionados;
            }

            // TODO: Implementar búsqueda hipotética (HyDE) en el futuro

            // Si la frase tiene más palabras importantes, exige más similitud
            double umbralDinamico = Math.max(0.65, calcularUmbralDinamico(queryParaKeywords));
            Log.debugf("Búsqueda global activa. Umbral dinámico: %.2f", umbralDinamico);

            // Aplica filtro de empresa a la búsqueda global
            EmbeddingStoreContentRetriever.EmbeddingStoreContentRetrieverBuilder globalBuilder =
                    EmbeddingStoreContentRetriever.builder()
                            .embeddingStore(storeVectores)
                            .embeddingModel(modeloVectores)
                            .maxResults(8)
                            .minScore(umbralDinamico)
                            .filter(filtroTenant);

            return Arrays.asList(
                    globalBuilder.build(),
                    postgresKeywordRetriever.crearRetriever(null, queryParaKeywords, idEmpresa));
        };

        // Calcula la distancia matemática entre el texto y los documentos
        ScoringModel scoringModel = new ScoringModel() {
            @Override
            public Response<List<Double>> scoreAll(List<TextSegment> segments, String query) {
                Embedding queryEmbedding = modeloVectores.embed(query).content();
                List<Embedding> segEmbeddings = modeloVectores.embedAll(segments).content();

                List<Double> scores = new ArrayList<>();
                for (Embedding segEmb : segEmbeddings) {
                    scores.add(similitudCoseno(queryEmbedding.vector(), segEmb.vector()));
                }
                return Response.from(scores);
            }
        };

        // Mezcla y ordena los resultados de los distintos buscadores
        ContentAggregator agregadorRRF = queryToContents -> {
            Log.debug("RRF: Iniciando fusión de resultados híbridos.");

            Map<String, Double> contentScores = new HashMap<>();
            Map<String, Content> contentMap = new HashMap<>();

            for (Collection<List<Content>> coleccionRecuperadores : queryToContents.values()) {
                for (List<Content> resultados : coleccionRecuperadores) {
                    int rank = 1;
                    for (Content contenido : resultados) {
                        String id = contenido.textSegment().text();
                        double rrfScore = 1.0 / (RRF_K + rank); 
                        contentScores.merge(id, rrfScore, Double::sum); 
                        contentMap.putIfAbsent(id, contenido);
                        rank++;
                    }
                }
            }

            // Se queda con los mejores candidatos para evaluarlos en detalle
            List<Content> candidatosRRF = contentScores.entrySet().stream()
                    .sorted((e1, e2) -> Double.compare(e2.getValue(), e1.getValue()))
                    .limit(MAX_RERANKING_CANDIDATES) 
                    .map(e -> contentMap.get(e.getKey()))
                    .collect(Collectors.toList());

            if (candidatosRRF.isEmpty())
                return candidatosRRF;

            try {
                Log.debug("RE-RANKER: Aplicando Scoring Model sobre candidatos RRF.");
                List<TextSegment> segmentsToScore = candidatosRRF.stream()
                        .map(Content::textSegment)
                        .collect(Collectors.toList());

                dev.langchain4j.rag.query.Query primeraQuery = queryToContents.keySet().iterator().next();
                String qText = primeraQuery.text();
                String idEmpresaAgregador = primeraQuery.metadata() == null ? null
                        : ContextoSesion.empresaDe(primeraQuery.metadata().chatMemoryId());
                // Limpia la consulta original para filtrado
                String qNormalizada = normalizarTexto(qText);

                List<Double> scores = scoringModel.scoreAll(segmentsToScore, qText).content();

                List<Map.Entry<Content, Double>> reranked = new ArrayList<>();
                for (int i = 0; i < candidatosRRF.size(); i++) {
                    reranked.add(new AbstractMap.SimpleEntry<>(candidatosRRF.get(i), scores.get(i)));
                }

                // Si la pregunta menciona algún archivo, descarta los fragmentos que sean de otros
                List<String> archivosReferenciados = ingestionService.listarDocumentos(idEmpresaAgregador).stream()
                        .map(doc -> normalizarTexto(doc.nombre()))
                        .map(nombre -> nombre.contains(".")
                                ? nombre.substring(0, nombre.lastIndexOf('.'))
                                : nombre)
                        .filter(qNormalizada::contains)
                        .collect(Collectors.toList());

                return reranked.stream()
                        .filter(e -> {
                            // Pasa todo si no se mencionó ningún archivo
                            if (archivosReferenciados.isEmpty())
                                return true;

                            // Solo pasan los fragmentos de los archivos mencionados
                            String fuenteNormalizada = normalizarTexto(
                                    e.getKey().textSegment().metadata()
                                            .getString("nombre_archivo"));
                            return archivosReferenciados.stream()
                                    .anyMatch(fuenteNormalizada::contains);
                        })
                        .sorted((e1, e2) -> Double.compare(e2.getValue(), e1.getValue()))
                        .limit(MAX_RESULTADOS_FINALES)
                        .map(Map.Entry::getKey)
                        .collect(Collectors.toList());

            } catch (Exception ex) {
                Log.error("Error en Re-Ranking, aplicando fallback RRF.", ex);
                return candidatosRRF.stream().limit(MAX_RESULTADOS_FINALES).collect(Collectors.toList());
            }
        };

        // Añade información extra a los fragmentos para que el LLM tenga más contexto
        DefaultContentInjector inyectorContenido = DefaultContentInjector.builder()
                .metadataKeysToInclude(Arrays.asList(
                        "nombre_archivo", // Para que el LLM sepa de dónde viene
                        "documento_id", // ID único del documento
                        "entidades", // Conceptos detectados
                        "tecnologias", // Tecnologías usadas
                        "fechas" // Fechas relevantes
                ))
                .promptTemplate(PromptTemplate.from(
                        "{{userMessage}}\n\n" +
                                "--- Contexto de Documentos ---\n" +
                                "{{contents}}"))
                .build();

        return DefaultRetrievalAugmentor.builder()
                .queryTransformer(customQueryTransformer)
                .queryRouter(enrutadorConsultas)
                .contentAggregator(agregadorRRF)
                .contentInjector(inyectorContenido)
                .build();
    }

    // -------------------------------------------------------------------------
    // MÉTODOS DE UTILIDAD PRIVADOS
    // -------------------------------------------------------------------------

    /**
     * Limpia un texto quitando tildes y pasándolo a minúsculas.
     *
     * @param texto Texto a limpiar.
     * @return Texto sin acentos y en minúsculas.
     */
    private static String normalizarTexto(String texto) {
        return ClasificadorConsulta.normalizar(texto);
    }

    /**
     * Decide cuánto deben parecerse los textos según las palabras de la pregunta.
     * A más palabras en la pregunta, más exactitud exige.
     *
     * @param queryKeywords Consulta del usuario ya limpia.
     * @return Nivel de exigencia para los resultados (entre 0.60 y 0.75).
     */
    private static double calcularUmbralDinamico(String queryKeywords) {
        long numPalabras = Arrays.stream(queryKeywords.split("\\s+"))
                .filter(w -> !w.isBlank())
                .count();

        if (numPalabras <= 2)
            return 0.68;
        if (numPalabras <= 4)
            return 0.70;
        return 0.75;
    }

    /**
     * Calcula qué tan parecidos son dos textos comparando sus vectores.
     *
     * @param vA Vector numérico de la consulta.
     * @param vB Vector numérico del fragmento de documento.
     * @return Puntuación de similitud entre 0.0 y 1.0.
     */
    private static double similitudCoseno(float[] vA, float[] vB) {
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < vA.length; i++) {
            dotProduct += vA[i] * vB[i]; 
            normA += vA[i] * vA[i];
            normB += vB[i] * vB[i];
        }

        double denominador = Math.sqrt(normA * normB); 

        if (denominador == 0.0)
            return 0.0; 

        double cosineSim = dotProduct / denominador;
        return (cosineSim + 1.0) / 2.0; // Adapta el resultado a rango positivo
    }
}
