package com.gonzalo.tfg.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gonzalo.tfg.model.DocumentoDTO;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.event.Event;
import com.gonzalo.tfg.model.IngestionStatusEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Servicio de ingestión de documentos en el sistema RAG.
 *
 * <p>
 * Implementa el ciclo completo de procesamiento de documentos:
 * extracción de texto, enriquecimiento semántico de metadatos mediante LLM,
 * fragmentación con solapamiento, vectorización en lote y persistencia
 * en la base de datos vectorial pgvector.
 * </p>
 *
 * <p>
 * Flujo de procesamiento:
 * </p>
 * 
 * <pre>
 *   Fichero → Extracción (Tika/Plain) → Enriquecimiento (LLM)
 *           → Fragmentación (300 tokens / 30 solapamiento)
 *           → Vectorización en lote (embedAll)
 *           → pgvector + catálogo JSON
 * </pre>
 *
 * <p>
 * El registro de metadatos se mantiene en memoria mediante un
 * {@link ConcurrentHashMap} e indexado por nombre en minúsculas para
 * búsquedas O(1) en la operación de eliminación. El estado se persiste
 * en un fichero JSON local ({@value #FICHERO_CATALOGO}) para sobrevivir
 * reinicios de la aplicación.
 * </p>
 */
@ApplicationScoped
public class DocumentIngestionService {

    @Inject
    EmbeddingStore<TextSegment> storeVectores;

    @Inject
    EmbeddingModel modeloVectores;

    /**
     * Registro principal de documentos procesados, indexado por UUID de documento.
     * Se utiliza {@link ConcurrentHashMap} para garantizar acceso seguro en
     * entornos multihilo.
     */
    private final Map<String, DocumentoDTO> registroDocumentos = new ConcurrentHashMap<>();

    /**
     * Índice invertido que mapea el nombre del fichero en minúsculas a su UUID.
     * Permite resolver búsquedas por nombre en tiempo O(1), evitando la iteración
     * lineal sobre {@link #registroDocumentos}.
     */
    private final Map<String, String> indicePorNombre = new ConcurrentHashMap<>();

    /** Tamaño de cada fragmento textual expresado en tokens. */
    private static final int TAMANIO_FRAGMENTO = 300;

    /**
     * Número de tokens compartidos entre fragmentos consecutivos para preservar
     * coherencia semántica.
     */
    private static final int SOLAPAMIENTO_FRAGMENTO = 30;

    /**
     * Conjunto inmutable de términos tecnológicos usados en la extracción pasiva de
     * metadatos.
     * Se define como constante estática para evitar su reinstanciación en cada
     * invocación de {@link #ingerirFichero}.
     */
    private static final Set<String> TECH_STACK = Set.of(
            "java", "quarkus", "postgres", "pgvector", "langchain4j",
            "docker", "kubernetes", "react", "spring boot", "python",
            "javascript", "typescript");

    /**
     * Patrón compilado para la detección de referencias temporales en el cuerpo del
     * documento.
     *
     * <p>
     * Cubre los siguientes formatos:
     * </p>
     * <ul>
     * <li>{@code dd-MM-yyyy} y {@code dd/MM/yyyy} — formatos de fecha habituales en
     * español</li>
     * <li>{@code yyyy-MM-dd} — formato ISO 8601</li>
     * <li>{@code yyyy} — años de cuatro dígitos aislados</li>
     * </ul>
     *
     * <p>
     * La compilación se realiza una única vez en tiempo de carga de la clase para
     * evitar la penalización de {@link Pattern#compile} dentro de bucles de
     * ingestión.
     * </p>
     */
    private static final Pattern PATRON_FECHAS = Pattern.compile(
            "\\b(\\d{2}[-/]\\d{2}[-/]\\d{4}|\\d{4}-\\d{2}-\\d{2}|\\d{4})\\b");

    /** Número máximo de referencias temporales que se extraen por documento. */
    private static final int MAX_FECHAS = 5;

    @Inject
    MetadataExtractor metadataExtractor;

    /**
     * Ruta relativa del fichero JSON que persiste el catálogo de documentos entre
     * reinicios.
     */
    private static final String FICHERO_CATALOGO = "documents_catalog.json";

    @Inject
    ObjectMapper objectMapper;

    @Inject
    Event<IngestionStatusEvent> statusEvent;

    private void notificar(String fileName, int phase, String msg, String status) {
        statusEvent.fire(new IngestionStatusEvent(fileName, phase, msg, status));
    }

    // -------------------------------------------------------------------------
    // CICLO DE VIDA DEL BEAN
    // -------------------------------------------------------------------------

    /**
     * Inicializa el servicio restituyendo el estado del catálogo desde el fichero
     * de
     * persistencia local.
     *
     * <p>
     * Si el fichero {@value #FICHERO_CATALOGO} existe, deserializa la lista de
     * {@link DocumentoDTO} y reconstruye tanto el registro principal como el índice
     * invertido por nombre, garantizando consistencia entre ambas estructuras en
     * arranques en caliente.
     * </p>
     */
    @PostConstruct
    void inicializar() {
        File archivoCatalogo = new File(FICHERO_CATALOGO);
        if (archivoCatalogo.exists()) {
            try {
                List<DocumentoDTO> lista = objectMapper.readValue(archivoCatalogo,
                        new TypeReference<List<DocumentoDTO>>() {
                        });
                for (DocumentoDTO doc : lista) {
                    registroDocumentos.put(doc.id(), doc);
                    indicePorNombre.put(doc.nombre().toLowerCase(), doc.id());
                }
                Log.infof("Catálogo de la base de conocimiento cargado desde %s (%d ficheros)",
                        FICHERO_CATALOGO, lista.size());
            } catch (Exception e) {
                Log.errorf(e, "Error crítico al cargar el catálogo desde %s", FICHERO_CATALOGO);
            }
        }
    }

    // -------------------------------------------------------------------------
    // OPERACIONES PRINCIPALES
    // -------------------------------------------------------------------------

    /**
     * Procesa un fichero y lo incorpora al sistema RAG.
     *
     * <p>
     * El método ejecuta secuencialmente las siguientes fases:
     * </p>
     * <ol>
     * <li>Validación de entradas con fallo rápido.</li>
     * <li>Extracción de texto mediante el analizador apropiado (Tika o
     * plain-text).</li>
     * <li>Extracción pasiva de metadatos tecnológicos y temporales del cuerpo del
     * documento.</li>
     * <li>Fragmentación semántica con {@link DocumentSplitters#recursive}.</li>
     * <li>Enriquecimiento de cada fragmento mediante llamada al
     * {@link MetadataExtractor} (LLM).</li>
     * <li>Vectorización en lote de todos los fragmentos con una única llamada a
     * {@code embedAll()}, minimizando la latencia de red.</li>
     * <li>Registro del documento en el catálogo en memoria y persistencia en
     * disco.</li>
     * </ol>
     *
     * @param rutaFichero    Ruta absoluta al fichero temporal ya almacenado en
     *                       disco.
     * @param nombreOriginal Nombre original del fichero proporcionado por el
     *                       cliente HTTP.
     * @param metadatosJson  Cadena JSON con metadatos adicionales opcionales; puede
     *                       ser
     *                       {@code null} o vacía.
     * @return {@link DocumentoDTO} con los metadatos del documento procesado.
     * @throws IllegalArgumentException si {@code rutaFichero} es nulo, no existe, o
     *                                  {@code nombreOriginal} es nulo o vacío.
     * @throws RuntimeException         si se produce un error irrecuperable durante
     *                                  cualquier fase del pipeline de ingestión.
     */
    public DocumentoDTO ingerirFichero(Path rutaFichero, String nombreOriginal, String metadatosJson) {
        return ingerirFichero(rutaFichero, nombreOriginal, metadatosJson, true);
    }

    public DocumentoDTO ingerirFichero(Path rutaFichero, String nombreOriginal, String metadatosJson,
            boolean enriquecer) {

        if (rutaFichero == null || !rutaFichero.toFile().exists()) {
            throw new IllegalArgumentException(
                    "La ruta del fichero es nula o el fichero no existe: " + rutaFichero);
        }
        if (nombreOriginal == null || nombreOriginal.isBlank()) {
            throw new IllegalArgumentException(
                    "El nombre original del fichero no puede ser nulo o vacío.");
        }

        Log.infof("Iniciando proceso de ingestión para el fichero: %s", nombreOriginal);

        try {
            String idDocumento = UUID.randomUUID().toString();

            // Fase 1: Procesamiento de metadatos adjuntos
            Map<String, String> metadatos = parsearMetadatos(metadatosJson);
            metadatos.put("documento_id", idDocumento);
            metadatos.put("nombre_archivo", nombreOriginal);

            // Fase 2: Análisis y Fragmentación
            notificar(nombreOriginal, 2, "Analizando estructura y fragmentando...", "processing");

            Map<String, String> metadatosMap = parsearMetadatos(metadatosJson);
            metadatosMap.put("documento_id", idDocumento);
            metadatosMap.put("nombre_archivo", nombreOriginal);

            Document documento = FileSystemDocumentLoader.loadDocument(
                    rutaFichero,
                    crearAnalizador(nombreOriginal));

            // Extracción pasiva de metadatos (Graph-Lite)
            String contenidoLower = documento.text().toLowerCase();
            String extension = obtenerExtension(nombreOriginal).toLowerCase();

            // Preservación de Metadatos por Tipo de Archivo
            if (extension.equals("md")) {
                // Extraer título de cabecera en Markdown (Ej: # Mi Titulo)
                Matcher mTitle = Pattern.compile("^#\\s+(.*)", Pattern.MULTILINE).matcher(documento.text());
                if (mTitle.find()) {
                    String titulo = mTitle.group(1).trim();
                    documento.metadata().put("titulo_markdown", titulo);
                    metadatosMap.put("titulo_markdown", titulo);
                }
            }

            // Extraer metadatos específicos de Apache Tika (ej. Author de .docx, title)
            String[] clavesTika = { "Author", "creator", "title" };
            for (String key : clavesTika) {
                if (documento.metadata().containsKey(key)) {
                    String val = documento.metadata().getString(key);
                    if (val != null && !val.isBlank()) {
                        String cleanKey = key.toLowerCase();
                        if (!metadatosMap.containsKey(cleanKey)) {
                            // Reemplazar caracteres nulos que rompen JSONB
                            metadatosMap.put(cleanKey, val.replace("\u0000", ""));
                        }
                    }
                }
            }

            Set<String> tecnologiasEncontradas = TECH_STACK.stream()
                    .filter(contenidoLower::contains)
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            Set<String> fechasEncontradas = new LinkedHashSet<>();
            Matcher m = PATRON_FECHAS.matcher(contenidoLower);
            while (m.find() && fechasEncontradas.size() < MAX_FECHAS) {
                fechasEncontradas.add(m.group());
            }

            if (!tecnologiasEncontradas.isEmpty()) {
                String techs = String.join(", ", tecnologiasEncontradas);
                metadatosMap.put("tecnologias", techs);
            }
            if (!fechasEncontradas.isEmpty()) {
                String dates = String.join(", ", fechasEncontradas);
                metadatosMap.put("fechas", dates);
            }

            // Propagación de metadatos a un objeto Document limpio
            // Esto evita que metadatos extraños de Tika (con caracteres nulos o formatos
            // raros)
            // rompan el driver de pgvector.
            Map<String, Object> metadatosLimpios = new HashMap<>(metadatosMap);

            Document documentoLimpio = Document.from(documento.text(), Metadata.from(metadatosLimpios));

            // Fragmentación semántica
            DocumentSplitter fragmentador = DocumentSplitters.recursive(TAMANIO_FRAGMENTO, SOLAPAMIENTO_FRAGMENTO);
            List<TextSegment> segmentos = fragmentador.split(documentoLimpio);

            // Fase 3: Enriquecimiento y Generación de Embeddings
            notificar(nombreOriginal, 3, "Generando embeddings vectoriales...", "processing");

            List<TextSegment> segmentosAProcesar;
            if (enriquecer) {
                Log.infof("Iniciando enriquecimiento paralelo para %d segmentos...", segmentos.size());
                segmentosAProcesar = segmentos.parallelStream().map(seg -> {
                    try {
                        String salidaLLM = metadataExtractor.extraerEntidades(seg.text());
                        java.util.Map<String, String> metaParsed = MetadataExtractor.parsear(salidaLLM);
                        dev.langchain4j.data.document.Metadata meta = seg.metadata().copy();
                        // Guarda cada campo por separado en lugar del string crudo completo
                        metaParsed.forEach(meta::put);
                        return TextSegment.from(seg.text(), meta);
                    } catch (Exception e) {
                        Log.warnf("Error enriqueciendo fragmento: %s", e.getMessage());
                        return seg;
                    }
                }).collect(Collectors.toList());
            } else {
                segmentosAProcesar = segmentos;
            }

            // Fase 4: Indexación en Base de Datos
            notificar(nombreOriginal, 4, "Indexando en Base de Datos...", "processing");
            almacenarSegmentos(segmentosAProcesar);

            // Fase 7: Registro del documento en el catálogo
            DocumentoDTO documentoDTO = new DocumentoDTO(
                    idDocumento,
                    nombreOriginal,
                    obtenerTipoFichero(nombreOriginal),
                    documento.text(),
                    metadatosMap,
                    LocalDateTime.now(),
                    (long) documento.text().length());

            registroDocumentos.put(idDocumento, documentoDTO);
            indicePorNombre.put(nombreOriginal.toLowerCase(), idDocumento);
            guardarCatalogo();

            // Fase 5: Documento listo
            notificar(nombreOriginal, 5, "¡Documento listo!", "completed");
            Log.infof("Ingestión completada: %s (ID: %s)", nombreOriginal, idDocumento);
            return documentoDTO;

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            Log.errorf(e, "Fallo en el pipeline de ingestión del fichero: %s", nombreOriginal);
            throw new RuntimeException("Error en el flujo de ingestión de ficheros", e);
        }
    }

    /**
     * Devuelve la lista completa de documentos registrados en el catálogo.
     *
     * @return Lista de {@link DocumentoDTO}; puede estar vacía si no se ha
     *         procesado
     *         ningún documento.
     */
    public List<DocumentoDTO> listarDocumentos() {
        return new ArrayList<>(registroDocumentos.values());
    }

    /**
     * Recupera un documento por su identificador único.
     *
     * <p>
     * Se devuelve un {@link Optional} vacío en lugar de {@code null} para
     * obligar a los consumidores a manejar explícitamente la ausencia del recurso,
     * eliminando el riesgo de {@link NullPointerException} silenciosas.
     * </p>
     *
     * @param id UUID del documento a recuperar.
     * @return {@link Optional} con el {@link DocumentoDTO} si existe; vacío en caso
     *         contrario.
     */
    public Optional<DocumentoDTO> obtenerDocumento(String id) {
        return Optional.ofNullable(registroDocumentos.get(id));
    }

    /**
     * Elimina un documento del catálogo y purga sus vectores asociados en pgvector.
     *
     * <p>
     * La resolución del documento se realiza en dos pasos:
     * </p>
     * <ol>
     * <li>Búsqueda directa por UUID en {@link #registroDocumentos} — O(1).</li>
     * <li>Si no coincide, resolución por nombre de fichero (insensible a
     * mayúsculas)
     * a través del índice invertido {@link #indicePorNombre} — también O(1).</li>
     * </ol>
     *
     * <p>
     * La purga de vectores en pgvector se aplica mediante un filtro de metadatos
     * sobre la clave {@code nombre_archivo}. Si la implementación del
     * {@link EmbeddingStore} no soporta eliminación nativa, se registra el error
     * sin interrumpir la operación de catálogo.
     * </p>
     *
     * @param identificador UUID o nombre de fichero del documento a eliminar.
     * @return {@code true} si el documento existía y fue eliminado; {@code false}
     *         en caso contrario.
     */
    public boolean eliminarDocumento(String identificador) {

        DocumentoDTO docAEliminar = registroDocumentos.get(identificador);

        if (docAEliminar == null) {
            // Resolución secundaria por nombre de fichero mediante índice invertido
            String idPorNombre = indicePorNombre.get(identificador.toLowerCase());
            if (idPorNombre != null) {
                docAEliminar = registroDocumentos.get(idPorNombre);
            }
        }

        if (docAEliminar != null) {
            String idTraza = docAEliminar.id();
            String nombreTraza = docAEliminar.nombre();

            registroDocumentos.remove(idTraza);
            indicePorNombre.remove(nombreTraza.toLowerCase());
            Log.infof("Documento eliminado del catálogo: %s (ID: %s)", nombreTraza, idTraza);

            guardarCatalogo();

            // Purga de vectores en la base de datos vectorial mediante filtro de metadatos
            try {
                Filter filtro = MetadataFilterBuilder.metadataKey("nombre_archivo").isEqualTo(nombreTraza);
                storeVectores.removeAll(filtro);
                Log.infof("Vectores eliminados de pgvector para el documento: %s", nombreTraza);
            } catch (Exception e) {
                Log.errorf(e,
                        "Error en la purga de vectores para '%s'. Verifique soporte de eliminación nativa en el EmbeddingStore configurado.",
                        nombreTraza);
            }

            return true;
        }

        Log.warnf("Solicitud de eliminación sin efecto: no existe documento con identificador '%s'", identificador);
        return false;
    }

    /**
     * Devuelve un mapa de estadísticas agregadas del catálogo de documentos.
     *
     * <p>
     * El recuento de fragmentos se obtiene mediante una estimación heurística:
     * {@code fragmentos ≈ caracteres_totales / (TAMANIO_FRAGMENTO × 4)},
     * donde el factor 4 aproxima la relación carácter/token para texto en español.
     * </p>
     *
     * @return Mapa con las claves: {@code ficheros_procesados},
     *         {@code total_fragmentos_estimados},
     *         {@code caracteres_totales_indexados},
     *         {@code distribucion_por_tipo}, {@code tamanio_fragmento_tokens},
     *         {@code solapamiento_tokens}.
     */
    public Map<String, Object> obtenerEstadisticas() {
        Map<String, Object> estadisticas = new HashMap<>();

        int totalDocumentos = registroDocumentos.size();

        long caracteresTotales = registroDocumentos.values().stream()
                .mapToLong(DocumentoDTO::tamanioBytes)
                .sum();

        // Heurística de estimación: 1 token ≈ 4 caracteres en español
        long fragmentosEstimados = caracteresTotales / (TAMANIO_FRAGMENTO * 4L);

        Map<String, Long> distribucionTipos = registroDocumentos.values().stream()
                .collect(Collectors.groupingBy(DocumentoDTO::tipo, Collectors.counting()));

        estadisticas.put("ficheros_procesados", totalDocumentos);
        estadisticas.put("total_fragmentos_estimados", fragmentosEstimados);
        estadisticas.put("caracteres_totales_indexados", caracteresTotales);
        estadisticas.put("distribucion_por_tipo", distribucionTipos);
        estadisticas.put("tamanio_fragmento_tokens", TAMANIO_FRAGMENTO);
        estadisticas.put("solapamiento_tokens", SOLAPAMIENTO_FRAGMENTO);

        return estadisticas;
    }

    // -------------------------------------------------------------------------
    // MÉTODOS DE INFRAESTRUCTURA INTERNA
    // -------------------------------------------------------------------------

    /**
     * Vectoriza una lista de segmentos en una única llamada al modelo de embeddings
     * y los persiste en el {@link EmbeddingStore}.
     *
     * <p>
     * El procesamiento en lote mediante {@code embedAll()} reduce a una sola
     * las llamadas de red al modelo de embeddings, independientemente del número de
     * segmentos, minimizando latencia y coste de API.
     * </p>
     *
     * @param segmentos Lista de {@link TextSegment} a vectorizar y almacenar.
     */
    private void almacenarSegmentos(List<TextSegment> segmentos) {
        List<Embedding> vectores = modeloVectores.embedAll(segmentos).content();

        for (int i = 0; i < segmentos.size(); i++) {
            storeVectores.add(vectores.get(i), segmentos.get(i));
        }

        Log.infof("%d vectores persistidos en pgvector (procesamiento en lote)", segmentos.size());
    }

    /**
     * Selecciona el {@link DocumentParser} adecuado en función de la extensión del
     * fichero.
     *
     * <p>
     * Los ficheros de texto plano y Markdown se procesan con
     * {@link TextDocumentParser}
     * por su menor coste computacional. Los formatos binarios (PDF, DOCX, DOC) se
     * delegan a {@link ApacheTikaDocumentParser}, que aplica la detección
     * automática
     * de formato de Apache Tika.
     * </p>
     *
     * @param nombreFichero Nombre completo del fichero, incluida la extensión.
     * @return Instancia de {@link DocumentParser} apropiada para el tipo de
     *         fichero.
     */
    private DocumentParser crearAnalizador(String nombreFichero) {
        String extension = obtenerExtension(nombreFichero).toLowerCase();

        if (extension.equals("txt") || extension.equals("md")) {
            // Forzar UTF-8 para archivos de texto plano
            return new TextDocumentParser(java.nio.charset.StandardCharsets.UTF_8);
        }

        // Tika Document Parser universal para auto-detectar MIME types (PDF, DOCX,
        // HTML, etc)
        return new ApacheTikaDocumentParser();
    }

    /**
     * Deserializa una cadena JSON en un mapa de metadatos clave-valor.
     *
     * <p>
     * Si la cadena es nula, vacía o no contiene JSON válido, se devuelve
     * un mapa vacío modificable para no interrumpir el flujo de ingestión.
     * El error de parseo se registra con nivel {@code WARN} para facilitar
     * el diagnóstico sin elevar a excepción.
     * </p>
     *
     * @param metadatosJson Cadena JSON; puede ser {@code null} o vacía.
     * @return Mapa de tipo {@code Map<String, String>} con los metadatos
     *         deserializados,
     *         o un mapa vacío si la entrada no es procesable.
     */
    private Map<String, String> parsearMetadatos(String metadatosJson) {
        if (metadatosJson == null || metadatosJson.isBlank()) {
            return new HashMap<>();
        }

        try {
            return objectMapper.readValue(metadatosJson,
                    objectMapper.getTypeFactory().constructMapType(HashMap.class, String.class, String.class));
        } catch (Exception e) {
            Log.warnf("Metadatos JSON no procesables; se ignorarán: %s", e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * Extrae el sufijo de extensión de un nombre de fichero.
     *
     * @param nombreFichero Nombre del fichero, con o sin ruta.
     * @return Extensión sin el punto separador (p. ej. {@code "pdf"}),
     *         o cadena vacía si el nombre no contiene punto.
     */
    private String obtenerExtension(String nombreFichero) {
        int ultimoPunto = nombreFichero.lastIndexOf('.');
        return ultimoPunto > 0 ? nombreFichero.substring(ultimoPunto + 1) : "";
    }

    /**
     * Resuelve una etiqueta de tipo legible para el usuario a partir de la
     * extensión del fichero.
     *
     * @param nombreFichero Nombre del fichero.
     * @return Cadena descriptiva del tipo de documento (p. ej.
     *         {@code "Documento PDF"}).
     */
    private String obtenerTipoFichero(String nombreFichero) {
        String extension = obtenerExtension(nombreFichero).toLowerCase();
        return switch (extension) {
            case "pdf" -> "Documento PDF";
            case "docx" -> "Procesador de textos (DOCX)";
            case "doc" -> "Procesador de textos (DOC)";
            case "html" -> "Página Web (HTML)";
            case "txt" -> "Texto plano";
            case "md" -> "Markdown";
            default -> "Formato auto-detectado (Tika)";
        };
    }

    /**
     * Persiste el estado actual del catálogo de documentos en el fichero
     * {@value #FICHERO_CATALOGO}.
     *
     * <p>
     * Se invoca tras cada operación de escritura (ingestión o eliminación)
     * para mantener la coherencia entre el estado en memoria y el almacenamiento
     * persistente. Los errores de E/S se registran sin propagarse, ya que la
     * falta de persistencia no invalida la operación en curso.
     * </p>
     */
    private void guardarCatalogo() {
        try {
            List<DocumentoDTO> lista = listarDocumentos();
            objectMapper.writeValue(new File(FICHERO_CATALOGO), lista);
            Log.infof("Catálogo sincronizado correctamente en %s", FICHERO_CATALOGO);
        } catch (Exception e) {
            Log.errorf(e, "Error al persistir el catálogo en %s", FICHERO_CATALOGO);
        }
    }
}