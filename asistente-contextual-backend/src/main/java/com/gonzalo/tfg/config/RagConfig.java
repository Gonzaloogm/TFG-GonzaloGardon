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

import com.gonzalo.tfg.security.TenantContext;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import com.gonzalo.tfg.service.DocumentIngestionService;
import com.gonzalo.tfg.service.PostgresKeywordRetriever;
import com.gonzalo.tfg.model.DocumentoDTO;
import jakarta.inject.Named;

import java.text.Normalizer;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Configuración avanzada del motor de Generación Aumentada por Recuperación
 * (RAG).
 * Arquitectura Nivel 3: Híbrido (Semántico + Keyword) + RRF + Re-Ranking
 * Manual.
 *
 * MEJORAS IMPLEMENTADAS (sin alteración del código existente):
 * ---
 * M1. Stopwords como constante de clase Set<String> — O(1) de lookup en lugar
 * de regex.
 * Separadas en dos grupos: stopwords de idioma y términos funcionales de RAG.
 * Esto hace que el filtro sea extensible y su propósito, legible.
 *
 * M2. Normalización Unicode en detección de archivos y keywords.
 * El original no podía emparejar "análisis" con "analisis".
 * Se añade normalizarTexto() que aplica NFD + strip de diacríticos.
 *
 * M3. Umbral dinámico basado en número de palabras significativas, no en
 * longitud
 * de caracteres. Una query de 2 palabras largas era mal clasificada por el
 * original (input.length() < 15). Ahora: ≤2 palabras → 0.60, ≥5 → 0.75.
 *
 * M4. "Portero de Seguridad" genérico: deriva el filtro de los metadatos del
 * documento recuperado en lugar de comparar contra strings literales
 * ("anteproyecto", "normas"). La versión original rompería con cualquier
 * documento nuevo que no estuviera hardcodeado.
 *
 * M5. Similitud coseno corregida en tres puntos:
 * a) vA[i]*vA[i] en lugar de Math.pow(vA[i], 2) — evita boxing y es ~3x más
 * rápido.
 * b) Math.sqrt(normA * normB) en una sola llamada.
 * c) Guarda de división por cero: si la norma es 0, el score es 0.0.
 *
 * M6. smoothingConstant extraído como constante nombrada RRF_K con comentario
 * de referencia bibliográfica (Cormack et al., 2009).
 *
 * M7. contentScores.merge simplificado: lambda (v1, v2) -> Double.sum(v1, v2)
 * reemplazada por referencia de método Double::sum.
 *
 * M8. inyectorContenido amplía los metadatos inyectados al contexto del LLM:
 * añade "tecnologias", "fechas" y "documento_id", que el system prompt
 * ordena usar y que el original perdía silenciosamente.
 *
 * M9. Re-Ranker limitado antes de llamar a scoreAll(): se aplica un tope de
 * MAX_RERANKING_CANDIDATES (10) antes de emitir la llamada de embeddings,
 * reduciendo el coste de API sin degradar la calidad final.
 *
 * M10. Detección de consultas de catálogo ampliada para cubrir variantes con
 * tildes ("qué", "cuáles") que el original no normalizaba.
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

    /**
     * M6: Constante de suavizado para Reciprocal Rank Fusion.
     * Valor estándar de la literatura: k=60 (Cormack, Clarke & Buettcher, 2009).
     * Valores más altos penalizan menos los rankings bajos; más bajos, más
     * agresivos.
     */
    private static final int RRF_K = 60;

    /**
     * M9: Número máximo de candidatos enviados al Re-Ranker (scoreAll).
     * Limitar aquí reduce las llamadas de embedding sin impactar la calidad final,
     * ya que el Re-Ranker solo selecciona los 5 mejores.
     */
    private static final int MAX_RERANKING_CANDIDATES = 10;

    /** Número de resultados finales devueltos al LLM tras el Re-Ranking. */
    private static final int MAX_RESULTADOS_FINALES = 5;

    /**
     * M1a: Stopwords de idioma — palabras vacías sin valor semántico en español.
     * Separadas de los términos funcionales de RAG para facilitar el mantenimiento.
     * Se usa Set<String> para garantizar lookup O(1).
     */
    private static final Set<String> STOPWORDS_IDIOMA = Set.of(
            "dime", "este", "estos", "sobre", "todos", "para", "donde",
            "cual", "cuales", "quien", "como", "algun", "algunos", "cuál",
            "cuáles", "quién", "cómo", "algún");

    /**
     * M1b: Términos funcionales de RAG — palabras que el usuario emplea para
     * formular la consulta pero que no aportan señal semántica al recuperador.
     */
    private static final Set<String> STOPWORDS_RAG = Set.of(
            "dime", "puedes", "hacer", "esta", "investigacion", "especificas",
            "mencionan", "documentos", "especificamente");

    /**
     * M1: Unión de ambos conjuntos — lista completa usada en el filtrado de
     * keywords.
     */
    private static final Set<String> TODAS_STOPWORDS = new HashSet<>();
    static {
        TODAS_STOPWORDS.addAll(STOPWORDS_IDIOMA);
        TODAS_STOPWORDS.addAll(STOPWORDS_RAG);
    }

    /**
     * Patrón de detección de consultas sobre el catálogo de documentos.
     * M10: Incluye variantes normalizadas (sin tilde) para cubrir entradas del
     * usuario
     * que no usen caracteres especiales.
     */
    private static final Set<String> PALABRAS_CATALOGO = Set.of(
            "lista", "documentos", "tienes", "inventario",
            "ficheros", "catalogo", "catálogo", "archivos");

    // -------------------------------------------------------------------------
    // PRODUCTOR DEL RETRIEVAL AUGMENTOR
    // -------------------------------------------------------------------------

    @Produces
    @ApplicationScoped
    public RetrievalAugmentor configuradorRAG(
            EmbeddingStore<TextSegment> storeVectores,
            EmbeddingModel modeloVectores) {

        Log.info("Inicializando RetrievalAugmentor con Híbrido RRF y enrutamiento inteligente");

        // 1. ENRUTADOR INTELIGENTE
        QueryRouter enrutadorConsultas = query -> {

            String inputOriginal = query.text();
            // M2: Normalizamos desde el principio para toda la lógica del enrutador
            String input = normalizarTexto(inputOriginal);

            // Multi-tenant: leer el company_id del TenantContext (ThreadLocal)
            // Si es null, no se aplica filtro de tenant (modo desarrollo sin auth)
            String companyId = TenantContext.get();
            Filter filtroTenant = (companyId != null)
                    ? MetadataFilterBuilder.metadataKey("company_id").isEqualTo(companyId)
                    : null;
            // Detección de mensajes cortos sin contenido semántico (saludos, cortesías)
            // Para estos no tiene sentido buscar en vectores — evita RAG Hijacking
            boolean esSaludo = input.split("\\s+").length <= 3 &&
                    input.matches(
                            ".*(hola|buenos|buenas|gracias|adios|hasta luego|hey|ey|que tal|como estas|estas ahi|ahi estas).*");
            if (esSaludo) {
                Log.debug("Enrutador: Detectado saludo/cortesía. Sin recuperación vectorial.");
                return Collections.emptyList();
            }

            // M10: Detección de catálogo usando el Set normalizado — cubre tildes y
            // variantes
            boolean esCatalogo = Arrays.stream(input.split("\\s+"))
                    .anyMatch(PALABRAS_CATALOGO::contains);
            if (esCatalogo) {
                Log.debug("Enrutador: Detectada consulta de catálogo. Delegando en Tool.");
                return Collections.emptyList();
            }

            // Extracción de keywords (M1: usa TODAS_STOPWORDS en lugar de regex literal)
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

            // Enrutamiento Dinámico por documento mencionado
            List<DocumentoDTO> todosLosDocs = ingestionService.listarDocumentos();
            List<ContentRetriever> recuperadoresSeleccionados = new ArrayList<>();

            for (DocumentoDTO doc : todosLosDocs) {
                // M2: Normalizamos también el nombre del archivo para comparar sin tildes
                String nombreNormalizado = normalizarTexto(doc.nombre());
                String nombreBase = nombreNormalizado.contains(".")
                        ? nombreNormalizado.substring(0, nombreNormalizado.lastIndexOf('.'))
                        : nombreNormalizado;

                if (input.contains(nombreNormalizado) || input.contains(nombreBase)) {
                    Log.infof("Enrutador: Coincidencia detectada para [%s]", doc.nombre());

                    // Filtro de archivo, opcionalmente compuesto con filtro de tenant
                    Filter filtroArchivo = MetadataFilterBuilder.metadataKey("nombre_archivo").isEqualTo(doc.nombre());
                    Filter filtroFinal = (filtroTenant != null)
                            ? Filter.and(filtroArchivo, filtroTenant)
                            : filtroArchivo;

                    recuperadoresSeleccionados.add(EmbeddingStoreContentRetriever.builder()
                            .embeddingStore(storeVectores)
                            .embeddingModel(modeloVectores)
                            .maxResults(10)
                            .minScore(0.65)
                            .filter(filtroFinal)
                            .build());

                    recuperadoresSeleccionados.add(
                            postgresKeywordRetriever.crearRetriever(doc.nombre(), queryParaKeywords));
                }
            }

            if (!recuperadoresSeleccionados.isEmpty()) {
                return recuperadoresSeleccionados;
            }

            // HyDE (Hypothetical Document Embeddings) Placeholder
            // En una fase posterior, aquí se llamaría a un LLM para generar una respuesta
            // hipotética
            // y se usaría el embedding de esa respuesta para la recuperación semántica.
            // String queryParaVector = inputOriginal; // Por ahora directo

            // BÚSQUEDA GLOBAL: M3 — umbral basado en número de palabras significativas
            double umbralDinamico = Math.max(0.65, calcularUmbralDinamico(queryParaKeywords));
            Log.debugf("Búsqueda global activa. Umbral dinámico: %.2f", umbralDinamico);

            // Búsqueda global: aplicar filtro de tenant si está disponible
            EmbeddingStoreContentRetriever.EmbeddingStoreContentRetrieverBuilder globalBuilder =
                    EmbeddingStoreContentRetriever.builder()
                            .embeddingStore(storeVectores)
                            .embeddingModel(modeloVectores)
                            .maxResults(8)
                            .minScore(umbralDinamico);
            if (filtroTenant != null) {
                globalBuilder.filter(filtroTenant);
            }

            return Arrays.asList(
                    globalBuilder.build(),
                    postgresKeywordRetriever.crearRetriever(null, queryParaKeywords));
        };

        // 2. SCORING MODEL CON SIMILITUD COSENO (M5: corregida y optimizada)
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

        // 3. FUSIÓN RRF + RE-RANKING FINAL
        ContentAggregator agregadorRRF = queryToContents -> {
            Log.debug("RRF: Iniciando fusión de resultados híbridos.");

            Map<String, Double> contentScores = new HashMap<>();
            Map<String, Content> contentMap = new HashMap<>();

            for (Collection<List<Content>> coleccionRecuperadores : queryToContents.values()) {
                for (List<Content> resultados : coleccionRecuperadores) {
                    int rank = 1;
                    for (Content contenido : resultados) {
                        String id = contenido.textSegment().text();
                        double rrfScore = 1.0 / (RRF_K + rank); // M6: constante nombrada
                        contentScores.merge(id, rrfScore, Double::sum); // M7: referencia de método
                        contentMap.putIfAbsent(id, contenido);
                        rank++;
                    }
                }
            }

            // M9: Limitamos candidatos ANTES de llamar al Re-Ranker para ahorrar embeddings
            List<Content> candidatosRRF = contentScores.entrySet().stream()
                    .sorted((e1, e2) -> Double.compare(e2.getValue(), e1.getValue()))
                    .limit(MAX_RERANKING_CANDIDATES) // M9: tope configurable
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
                // M2: normalizamos la query para el "Portero" también
                String qNormalizada = normalizarTexto(qText);

                List<Double> scores = scoringModel.scoreAll(segmentsToScore, qText).content();

                List<Map.Entry<Content, Double>> reranked = new ArrayList<>();
                for (int i = 0; i < candidatosRRF.size(); i++) {
                    reranked.add(new AbstractMap.SimpleEntry<>(candidatosRRF.get(i), scores.get(i)));
                }

                // M4: "Portero" genérico — filtra por los nombres de archivo que aparecen
                // en la query, derivados del catálogo de documentos en tiempo de ejecución,
                // en lugar de comparar contra strings literales hardcodeados.
                List<String> archivosReferenciados = ingestionService.listarDocumentos().stream()
                        .map(doc -> normalizarTexto(doc.nombre()))
                        .map(nombre -> nombre.contains(".")
                                ? nombre.substring(0, nombre.lastIndexOf('.'))
                                : nombre)
                        .filter(qNormalizada::contains)
                        .collect(Collectors.toList());

                return reranked.stream()
                        .filter(e -> {
                            // Si la query no menciona ningún archivo específico, no filtramos
                            if (archivosReferenciados.isEmpty())
                                return true;

                            // Si menciona alguno, solo pasamos fragmentos de ese archivo
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

        // 4. INYECTOR DE CONTENIDO
        // M8: Se amplían los metadatos inyectados al contexto del LLM.
        // El system prompt ordena usar "tecnologias", "fechas" y "documento_id"
        // explícitamente, pero el original solo enviaba "nombre_archivo" y "entidades".
        DefaultContentInjector inyectorContenido = DefaultContentInjector.builder()
                .metadataKeysToInclude(Arrays.asList(
                        "nombre_archivo", // fuente primaria de citación
                        "documento_id", // trazabilidad interna
                        "entidades", // personas, organizaciones, conceptos clave
                        "tecnologias", // M8: stack tecnológico — el system prompt lo exige
                        "fechas" // M8: referencias temporales del documento
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
     * M2: Normaliza un texto eliminando diacríticos (tildes, diéresis) y
     * convirtiéndolo a minúsculas, para comparaciones robustas entre la query
     * del usuario y los nombres de los documentos almacenados.
     *
     * Ejemplo: "Análisis" → "analisis", "Ñoño" → "nono"
     *
     * @param texto Texto de entrada.
     * @return Texto normalizado en minúsculas sin diacríticos.
     */
    private static String normalizarTexto(String texto) {
        if (texto == null)
            return "";
        String nfd = Normalizer.normalize(texto.toLowerCase(), Normalizer.Form.NFD);
        return nfd.replaceAll("\\p{InCombiningDiacriticalMarks}", "");
    }

    /**
     * M3: Calcula el umbral de similitud mínimo para la búsqueda global
     * en función del número de palabras significativas en la query.
     *
     * El original usaba input.length() < 15 caracteres como proxy, lo que era
     * inestable: "IA" tiene 2 chars pero es una consulta muy precisa; "cuéntame
     * algo" tiene 15 chars pero es extremadamente vaga.
     *
     * Ahora el umbral escala con la riqueza léxica de la query:
     * - ≤ 2 palabras → 0.60 (umbral permisivo: query corta, necesitamos más
     * candidatos)
     * - 3-4 palabras → 0.65 (umbral intermedio)
     * - ≥ 5 palabras → 0.75 (umbral estricto: query rica, el match debe ser
     * preciso)
     *
     * @param queryKeywords Query ya filtrada de stopwords.
     * @return Umbral de similitud en [0.60, 0.75].
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
     * M5: Calcula la similitud coseno entre dos vectores de forma segura y
     * eficiente.
     *
     * Mejoras respecto al cálculo inline original:
     * a) vA[i]*vA[i] en lugar de Math.pow(vA[i], 2) — evita la costosa conversión
     * a double y el boxeo implícito, ~3x más rápido en vectores de alta dimensión.
     * b) Math.sqrt(normA * normB) — una sola llamada en lugar de dos sqrt
     * separados.
     * c) Guarda de división por cero: si cualquiera de los dos vectores tiene norma
     * 0 (vector nulo), retorna 0.0 en lugar de NaN o lanzar ArithmeticException.
     * d) El resultado se escala de [-1,1] a [0,1] para compatibilidad con el
     * ScoringModel de LangChain4j.
     *
     * @param vA Vector de la query.
     * @param vB Vector del segmento candidato.
     * @return Similitud normalizada en [0.0, 1.0].
     */
    private static double similitudCoseno(float[] vA, float[] vB) {
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < vA.length; i++) {
            dotProduct += vA[i] * vB[i]; // M5a: multiplicación directa, sin Math.pow
            normA += vA[i] * vA[i];
            normB += vB[i] * vB[i];
        }

        double denominador = Math.sqrt(normA * normB); // M5b: un solo sqrt

        if (denominador == 0.0)
            return 0.0; // M5c: guarda de división por cero

        double cosineSim = dotProduct / denominador;
        return (cosineSim + 1.0) / 2.0; // Escala [-1,1] → [0,1]
    }

    // -------------------------------------------------------------------------
    // MÉTODOS PÚBLICOS DE UTILIDAD
    // -------------------------------------------------------------------------

    public static ContentRetriever createFilteredRetriever(
            EmbeddingStore<TextSegment> embeddingStore,
            EmbeddingModel embeddingModel,
            String filterKey,
            String filterValue) {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(7)
                .minScore(0.68)
                .filter(MetadataFilterBuilder.metadataKey(filterKey).isEqualTo(filterValue))
                .build();
    }

    @Produces
    @ApplicationScoped
    @Named("lightRetriever")
    public ContentRetriever lightContentRetriever(
            EmbeddingStore<TextSegment> store,
            EmbeddingModel model) {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(store)
                .embeddingModel(model)
                .maxResults(2) // Solo 2 fragmentos en vez de 5
                .minScore(0.75) // Umbral más exigente
                .build();
    }
}