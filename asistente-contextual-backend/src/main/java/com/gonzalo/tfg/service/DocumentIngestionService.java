package com.gonzalo.tfg.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gonzalo.tfg.model.DocumentoDTO;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.DocumentSplitter;
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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.File;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/*
 Servicio de Ingestión de Ficheros - Implementación de operaciones CRUD.
 Implementa el "Módulo de Ingestión" según el diseño de arquitectura.
 Responsabilidades:
 1. Extracción de texto de documentos (PDF, DOCX, TXT).
 2. Segmentación (fragmentación) con solapamiento (300 tokens, solapamiento
 30).
 3. Generación de vectores de características (embeddings) mediante
 text-embedding-004 de Gemini.
 4. Almacenamiento persistente en base de datos vectorial (pgvector).
 5. Gestión del registro de metadatos de ficheros procesados.

 Flujo de procesamiento:
 Fichero → Extracción → Fragmentación → Vectores → pgvector
 */
@ApplicationScoped
public class DocumentIngestionService {

    @Inject
    EmbeddingStore<TextSegment> storeVectores;

    @Inject
    EmbeddingModel modeloVectores;

    // Registro en memoria de los metadatos de los ficheros procesados
    private final Map<String, DocumentoDTO> registroDocumentos = new ConcurrentHashMap<>();

    // Configuración de la fragmentación según las especificaciones del TFG
    private static final int TAMANIO_FRAGMENTO = 300; // tokens
    private static final int SOLAPAMIENTO_FRAGMENTO = 30; // tokens

    // Fichero de persistencia para el catálogo de la base de conocimiento
    private static final String FICHERO_CATALOGO = "documents_catalog.json";

    @Inject
    ObjectMapper objectMapper;

    /*
     Inicializa el servicio cargando el catálogo persistente desde disco.
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
                }
                Log.infof("Catálogo de la base de conocimiento cargado correctamente desde %s (%d ficheros)",
                        FICHERO_CATALOGO, lista.size());
            } catch (Exception e) {
                Log.errorf(e, "Error crítico al cargar el catálogo desde %s", FICHERO_CATALOGO);
            }
        }
    }

    /*
     Persiste el estado actual del registro de documentos en el sistema de
     ficheros.
     */
    private void guardarCatalogo() {
        try {
            List<DocumentoDTO> lista = listarDocumentos();
            objectMapper.writeValue(new File(FICHERO_CATALOGO), lista);
            Log.infof("Catálogo de ficheros sincronizado exitosamente en %s", FICHERO_CATALOGO);
        } catch (Exception e) {
            Log.errorf(e, "Error al persistir el catálogo en %s", FICHERO_CATALOGO);
        }
    }

    /*
     Procesa e ingiere un nuevo fichero en el sistema.

     @param rutaFichero    Ruta local del fichero temporal.
     @param nombreOriginal Nombre original proporcionado por el cliente.
     @param metadatosJson  Cadena JSON con metadatos adicionales opcionales.
     @return DocumentoDTO con la información del fichero procesado.
     */
    public DocumentoDTO ingerirFichero(Path rutaFichero, String nombreOriginal, String metadatosJson) {
        Log.infof("Iniciando proceso de ingestión para el fichero: %s", nombreOriginal);

        try {
            // Generación de identificador único universal (UUID)
            String idDocumento = UUID.randomUUID().toString();

            // 1. Procesamiento de metadatos adjuntos
            Map<String, String> metadatos = parsearMetadatos(metadatosJson);
            metadatos.put("documento_id", idDocumento);
            metadatos.put("nombre_archivo", nombreOriginal);

            // 2. Extracción de contenido textual
            Document documento = FileSystemDocumentLoader.loadDocument(
                    rutaFichero,
                    crearAnalizador(nombreOriginal));

            // Inyección de metadatos en el objeto de dominio de LangChain4j
            documento.metadata().put("documento_id", idDocumento);
            documento.metadata().put("nombre_archivo", nombreOriginal);
            metadatos.forEach((clave, valor) -> documento.metadata().put(clave, valor));

            // 3. Fragmentación semántica del texto
            DocumentSplitter fragmentador = DocumentSplitters.recursive(
                    TAMANIO_FRAGMENTO,
                    SOLAPAMIENTO_FRAGMENTO);
            List<TextSegment> segmentos = fragmentador.split(documento);

            Log.infof("Contenido fragmentado en %d segmentos", segmentos.size());

            // 4. Transformación a vectores y almacenamiento en BD vectorial
            almacenarSegmentos(segmentos);

            // 5. Instanciación del DTO de respuesta
            DocumentoDTO documentoDTO = new DocumentoDTO(
                    idDocumento,
                    nombreOriginal,
                    obtenerTipoFichero(nombreOriginal),
                    documento.text(), // Contenido íntegro
                    metadatos,
                    LocalDateTime.now(),
                    (long) documento.text().length());

            // 6. Registro de la operación en memoria y persistencia
            registroDocumentos.put(idDocumento, documentoDTO);
            guardarCatalogo();

            Log.infof("Fichero ingerido y persistido correctamente: %s (ID: %s)", nombreOriginal, idDocumento);

            return documentoDTO;

        } catch (Exception e) {
            Log.errorf(e, "Fallo en la ingestión del fichero: %s", nombreOriginal);
            throw new RuntimeException("Error en flujo de ingestión de ficheros", e);
        }
    }

    /*
     Recupera la lista completa de ficheros registrados en el sistema.
     
     @return Lista de objetos DocumentoDTO.
     */
    public List<DocumentoDTO> listarDocumentos() {
        return new ArrayList<>(registroDocumentos.values());
    }

    /*
     Recupera la información de un fichero específico mediante su identificador
     único.
     
     @param id Identificador del fichero.
     @return Objeto DocumentoDTO o null si no se encuentra en el registro.
     */
    public DocumentoDTO obtenerDocumento(String id) {
        return registroDocumentos.get(id);
    }

    /*
     Elimina de forma lógica y física (vectores) un fichero del sistema.
     
     @param identificador Identificador único (UUID) o nombre literal del fichero.
     @return true si la operación se completó con éxito; false en caso contrario.
     */
    public boolean eliminarDocumento(String identificador) {

        // 1. Identificación del recurso: Búsqueda por ID o nombre comercial
        DocumentoDTO docAEliminar = null;
        for (DocumentoDTO doc : registroDocumentos.values()) {
            if (doc.id().equals(identificador) || doc.nombre().equals(identificador)) {
                docAEliminar = doc;
                break;
            }
        }

        if (docAEliminar != null) {

            String idTraza = docAEliminar.id();
            String nombreTraza = docAEliminar.nombre();

            // 2. Limpieza de memoria volátil y actualización del catálogo persistente
            registroDocumentos.remove(idTraza);
            Log.infof("🗑️ Recurso eliminado del registro: %s (ID: %s)", nombreTraza, idTraza);

            guardarCatalogo();

            // 3. Purga en la base de datos vectorial
            try {
                // Se utiliza la clave de metadatos 'nombre_archivo' para localizar los vectores
                // huérfanos
                Filter filtro = MetadataFilterBuilder.metadataKey("nombre_archivo").isEqualTo(nombreTraza);
                storeVectores.removeAll(filtro);
                Log.infof("🗑️ Vectores de características eliminados para el recurso: %s", nombreTraza);
            } catch (Exception e) {
                Log.errorf(e,
                        "Error en la purga de la base de datos vectorial para: %s. Verifique soporte de borrado nativo.",
                        nombreTraza);
            }

            return true;
        }

        Log.warnf("Solicitud de eliminación fallida: no existe recurso con identificador %s", identificador);
        return false;
    }

    /*
     Transforma segmentos de texto en vectores y los persiste en el almacén
     vectorial.
     
     @param segmentos Lista de fragmentos de texto generados.
     */
    private void almacenarSegmentos(List<TextSegment> segmentos) {
        for (TextSegment segmento : segmentos) {
            // Generación de representación vectorial
            Embedding vector = modeloVectores.embed(segmento).content();

            // Persistencia en pgvector (gestión delegada al adaptador de LangChain4j)
            storeVectores.add(vector, segmento);
        }

        Log.infof("💾 %d vectores de características almacenados en la base de datos", segmentos.size());
    }

    /*
     Determina el analizador de contenido óptimo basándose en la naturaleza del
     fichero.
     
     @param nombreFichero Nombre completo del fichero.
     @return Implementación de DocumentParser adecuada.
     */
    private DocumentParser crearAnalizador(String nombreFichero) {
        String extension = obtenerExtension(nombreFichero).toLowerCase();

        return switch (extension) {
            case "txt", "md" -> new TextDocumentParser();
            case "pdf", "docx", "doc" -> new ApacheTikaDocumentParser();
            default -> {
                Log.warnf("Extensión desconocida: %s. Se empleará el analizador genérico Tika.", extension);
                yield new ApacheTikaDocumentParser();
            }
        };
    }

    /*
     Procesa una cadena JSON para extraer un mapa de metadatos.
     
     @param metadatosJson Cadena en formato JSON.
     @return Mapa asociativo de metadatos.
     */
    private Map<String, String> parsearMetadatos(String metadatosJson) {
        if (metadatosJson == null || metadatosJson.isBlank()) {
            return new HashMap<>();
        }

        try {
            return objectMapper.readValue(metadatosJson,
                    objectMapper.getTypeFactory().constructMapType(HashMap.class, String.class, String.class));
        } catch (Exception e) {
            Log.warnf("Fallo en el procesamiento de metadatos JSON; se ignorarán: %s", e.getMessage());
            return new HashMap<>();
        }
    }

    /*
     Extrae el sufijo de extensión de un nombre de fichero.
     */
    private String obtenerExtension(String nombreFichero) {
        int ultimoPunto = nombreFichero.lastIndexOf('.');
        return ultimoPunto > 0 ? nombreFichero.substring(ultimoPunto + 1) : "";
    }

    /*
     Clasifica el tipo de fichero según su extensión para una visualización
     amigable.
     */
    private String obtenerTipoFichero(String nombreFichero) {
        String extension = obtenerExtension(nombreFichero).toLowerCase();
        return switch (extension) {
            case "pdf" -> "Documento PDF";
            case "docx" -> "Procesador de textos (DOCX)";
            case "doc" -> "Procesador de textos (DOC)";
            case "txt" -> "Texto plano";
            case "md" -> "Markdown";
            default -> "Formato no especificado";
        };
    }

    /*
     Genera un reporte estadístico del estado del sistema de ingestión.
     */
    public Map<String, Object> obtenerEstadisticas() {
        Map<String, Object> estadisticas = new HashMap<>();
        estadisticas.put("ficheros_procesados", registroDocumentos.size());
        estadisticas.put("total_fragmentos", "N/D");
        return estadisticas;
    }
}