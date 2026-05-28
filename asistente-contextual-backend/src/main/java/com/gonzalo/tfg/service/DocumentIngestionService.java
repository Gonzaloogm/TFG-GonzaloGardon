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
import com.gonzalo.tfg.security.TenantContext;
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
 * Guarda y procesa los documentos que sube el usuario.
 * Extrae el texto, lo divide en partes y lo guarda en la base de datos para poder buscar en él.
 */
@ApplicationScoped
public class DocumentIngestionService {

    @Inject
    EmbeddingStore<TextSegment> storeVectores;

    @Inject
    EmbeddingModel modeloVectores;

    /** Lista principal donde se guardan todos los documentos. */
    private final Map<String, DocumentoDTO> registroDocumentos = new ConcurrentHashMap<>();

    /** Diccionario rápido para buscar un documento por su nombre. */
    private final Map<String, String> indicePorNombre = new ConcurrentHashMap<>();

    /** Palabras aproximadas en cada trozo de texto. */
    private static final int TAMANIO_FRAGMENTO = 300;

    /** Palabras que se repiten entre trozos seguidos para no perder el hilo. */
    private static final int SOLAPAMIENTO_FRAGMENTO = 30;

    /** Palabras de tecnología para clasificar mejor los textos. */
    private static final Set<String> TECH_STACK = Set.of(
            "java", "quarkus", "postgres", "pgvector", "langchain4j",
            "docker", "kubernetes", "react", "spring boot", "python",
            "javascript", "typescript");

    /** Regla para encontrar fechas dentro del texto. */
    private static final Pattern PATRON_FECHAS = Pattern.compile(
            "\\b(\\d{2}[-/]\\d{2}[-/]\\d{4}|\\d{4}-\\d{2}-\\d{2}|\\d{4})\\b");

    /** Límite de fechas a guardar por cada archivo. */
    private static final int MAX_FECHAS = 5;

    @Inject
    MetadataExtractor metadataExtractor;

    /** Archivo donde se guardan los datos para no perderlos. */
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
     * Carga los documentos guardados al arrancar el servidor.
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
     * Prepara y guarda un archivo en el sistema de búsqueda.
     *
     * @param rutaFichero Dónde está el archivo en el disco.
     * @param nombreOriginal Nombre original del archivo subido.
     * @param metadatosJson Información adicional en formato JSON.
     * @return Datos del documento guardado.
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

            // Multi-tenant: asociar el documento al tenant activo (si hay uno)
            String tenantId = TenantContext.get();
            if (tenantId != null) {
                metadatosMap.put("company_id", tenantId);
            }

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
     * Muestra todos los documentos que hay en el sistema.
     *
     * @return Lista de documentos guardados.
     */
    public List<DocumentoDTO> listarDocumentos() {
        return new ArrayList<>(registroDocumentos.values());
    }

    /**
     * Busca un documento concreto usando su ID.
     *
     * @param id Identificador del documento.
     * @return El documento si existe, o vacío si no.
     */
    public Optional<DocumentoDTO> obtenerDocumento(String id) {
        return Optional.ofNullable(registroDocumentos.get(id));
    }

    /**
     * Borra un documento y todos sus datos asociados.
     *
     * @param identificador Nombre o ID del documento a borrar.
     * @return Verdadero si se borró, falso si no existía.
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
     * Calcula cuántos documentos, fragmentos y datos tenemos en total.
     *
     * @return Diccionario con los datos del sistema.
     */
    public Map<String, Object> obtenerEstadisticas() {
        Map<String, Object> estadisticas = new HashMap<>();

        int totalDocumentos = registroDocumentos.size();

        long caracteresTotales = registroDocumentos.values().stream()
                .mapToLong(DocumentoDTO::tamanioBytes)
                .sum();

        // regla práctica de estimación: 1 token ≈ 4 caracteres en español
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
     * Guarda los fragmentos de texto en la base de datos vectorial.
     *
     * @param segmentos Trozos de texto a guardar.
     */
    private void almacenarSegmentos(List<TextSegment> segmentos) {
        List<Embedding> vectores = modeloVectores.embedAll(segmentos).content();

        for (int i = 0; i < segmentos.size(); i++) {
            storeVectores.add(vectores.get(i), segmentos.get(i));
        }

        Log.infof("%d vectores persistidos en pgvector (procesamiento en lote)", segmentos.size());
    }

    /**
     * Elige cómo leer el archivo según si es PDF, texto, etc.
     *
     * @param nombreFichero Nombre del archivo para saber su tipo.
     * @return Lector adecuado para el archivo.
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
     * Convierte el texto JSON en un mapa fácil de usar.
     *
     * @param metadatosJson Texto con formato JSON.
     * @return Mapa con la información, o mapa vacío si falla.
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
     * Saca la terminación del archivo (ej. pdf o txt).
     *
     * @param nombreFichero El nombre completo del archivo.
     * @return La terminación sin el punto.
     */
    private String obtenerExtension(String nombreFichero) {
        int ultimoPunto = nombreFichero.lastIndexOf('.');
        return ultimoPunto > 0 ? nombreFichero.substring(ultimoPunto + 1) : "";
    }

    /**
     * Convierte la terminación del archivo en un nombre entendible.
     *
     * @param nombreFichero Nombre del archivo.
     * @return Nombre bonito para el tipo de archivo.
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
     * Guarda la lista de documentos en disco para no perderla si se reinicia.
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