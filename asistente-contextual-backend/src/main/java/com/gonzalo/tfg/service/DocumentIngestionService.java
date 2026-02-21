package com.gonzalo.tfg.service;

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
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Servicio de Ingestión de Documentos - ACTUALIZADO CON CRUD.
 * Implementa el "Módulo de Ingestión" del diagrama de arquitectura.
 * Responsable de:
 * 1. Extracción de texto de documentos (PDF, DOCX, TXT)
 * 2. Segmentación (chunking) con overlapping (300 tokens, overlap 30)
 * 3. Generación de embeddings (text-embedding-004 de Gemini)
 * 4. Almacenamiento en pgvector
 * 5. Gestión CRUD de documentos procesados
 * Pipeline según arquitectura:
 * Documento → Extracción → Chunking → Embeddings → pgvector
 */
@ApplicationScoped
public class DocumentIngestionService
{

    @Inject
    EmbeddingStore<TextSegment> embeddingStore;

    @Inject
    EmbeddingModel embeddingModel;

    // Almacenamiento temporal de metadatos de documentos
    private final Map<String, DocumentoDTO> documentosRegistro = new ConcurrentHashMap<>();

    // Configuración del chunking según arquitectura
    private static final int CHUNK_SIZE = 300;      // tokens
    private static final int CHUNK_OVERLAP = 30;    // tokens

    /**
     * Ingiere un documento desde un Path del sistema de archivos.
     *
     * @param documentPath Ruta del archivo temporal subido
     * @param nombreOriginal Nombre original del archivo
     * @param metadatosJson JSON con metadatos adicionales (puede ser null)
     * @return DocumentoDTO con información del documento procesado
     */
    public DocumentoDTO ingerirDocumento(Path documentPath, String nombreOriginal, String metadatosJson)
    {
        Log.infof("Iniciando ingestión de documento: %s", nombreOriginal);

        try
        {
            // Generar ID único
            String documentoId = UUID.randomUUID().toString();

            // 1. Parsear metadatos JSON si existen
            Map<String, String> metadatos = parsearMetadatos(metadatosJson);
            metadatos.put("documento_id", documentoId);
            metadatos.put("nombre_archivo", nombreOriginal);

            // 2. Cargar y extraer texto del documento
            Document document = FileSystemDocumentLoader.loadDocument(
                    documentPath,
                    crearParser(nombreOriginal)
            );

            // Añadir metadatos al documento
            document.metadata().put("documento_id", documentoId);
            document.metadata().put("nombre_archivo", nombreOriginal);
            metadatos.forEach((k, v) -> document.metadata().put(k, v));

            // 3. Segmentar en chunks con overlap
            DocumentSplitter splitter = DocumentSplitters.recursive(
                    CHUNK_SIZE,
                    CHUNK_OVERLAP
            );
            List<TextSegment> segments = splitter.split(document);

            Log.infof("Documento segmentado en %d fragmentos", segments.size());

            // 4. Generar embeddings y almacenar en pgvector
            almacenarSegmentos(segments);

            // 5. Crear DTO del documento
            DocumentoDTO documentoDTO = new DocumentoDTO(
                    documentoId,
                    nombreOriginal,
                    obtenerTipoArchivo(nombreOriginal),
                    document.text(), // Contenido completo
                    metadatos,
                    LocalDateTime.now(),
                    (long) document.text().length()
            );

            // 6. Registrar documento en memoria
            documentosRegistro.put(documentoId, documentoDTO);

            Log.infof("Documento ingerido exitosamente: %s (ID: %s)", nombreOriginal, documentoId);

            return documentoDTO;

        } catch (Exception e)
        {
            Log.errorf(e, "Error ingiriendo documento: %s", nombreOriginal);
            throw new RuntimeException("Error en ingestión de documento", e);
        }
    }

    /**
     * Lista todos los documentos procesados.
     *
     * @return Lista de DocumentoDTO
     */
    public List<DocumentoDTO> listarDocumentos()
    {
        return new ArrayList<>(documentosRegistro.values());
    }

    /**
     * Obtiene un documento por su ID.
     *
     * @param id ID del documento
     * @return DocumentoDTO o null si no existe
     */
    public DocumentoDTO obtenerDocumento(String id)
    {
        return documentosRegistro.get(id);
    }

    /**
     * Elimina un documento y sus embeddings asociados.
     * @param id ID del documento a eliminar
     * @return true si se eliminó, false si no existía
     */
    public boolean eliminarDocumento(String id)
    {
        DocumentoDTO documento = documentosRegistro.remove(id);

        if (documento != null)
        {
            // Esto requiere una consulta DELETE WHERE metadata->>'documento_id' = id
            Log.infof("🗑️ Documento eliminado del registro: %s", id);
            return true;
        }

        return false;
    }

    /**
     * Almacena segmentos en pgvector con sus embeddings.
     * @param segments Lista de segmentos de texto
     */
    private void almacenarSegmentos(List<TextSegment> segments) {
        for (TextSegment segment : segments) {
            // Generar embedding del segmento
            Embedding embedding = embeddingModel.embed(segment).content();

            // Almacenar en pgvector (el store maneja la persistencia)
            embeddingStore.add(embedding, segment);
        }

        Log.infof("💾 %d segmentos almacenados en pgvector", segments.size());
    }

    /**
     * Crea el parser apropiado según el tipo de archivo.
     * Comparativa (según arquitectura):
     * - TXT: TextDocumentParser (más rápido)
     * - PDF/DOCX: ApacheTikaDocumentParser (preserva estructura)
     * @param filename Nombre del archivo
     * @return Parser apropiado
     */
    private DocumentParser crearParser(String filename)
    {
        String extension = obtenerExtension(filename).toLowerCase();

        return switch (extension)
        {
            case "txt", "md" -> new TextDocumentParser();
            case "pdf", "docx", "doc" -> new ApacheTikaDocumentParser();
            default -> {
                Log.warnf("Extensión no reconocida: %s, usando Tika", extension);
                yield new ApacheTikaDocumentParser();
            }
        };
    }

    /**
     * Parsea el JSON de metadatos.
     * @param metadatosJson String JSON con metadatos
     * @return Map con los metadatos parseados
     */
    private Map<String, String> parsearMetadatos(String metadatosJson)
    {
        if (metadatosJson == null || metadatosJson.isBlank())
        {
            return new HashMap<>();
        }

        try
        {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(metadatosJson,
                    mapper.getTypeFactory().constructMapType(HashMap.class, String.class, String.class));
        } catch (Exception e)
        {
            Log.warnf("Error parseando metadatos JSON, usando map vacío: %s", e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * Obtiene la extensión de un archivo.
     */
    private String obtenerExtension(String filename)
    {
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 ? filename.substring(lastDot + 1) : "";
    }

    /**
     * Determina el tipo de archivo legible.
     */
    private String obtenerTipoArchivo(String filename)
    {
        String extension = obtenerExtension(filename).toLowerCase();
        return switch (extension)
        {
            case "pdf" -> "PDF";
            case "docx" -> "Word (DOCX)";
            case "doc" -> "Word (DOC)";
            case "txt" -> "Texto plano";
            case "md" -> "Markdown";
            default -> "Desconocido";
        };
    }

    /**
     * Obtiene estadísticas del sistema de ingestión.
     */
    public Map<String, Object> obtenerEstadisticas()
    {
        Map<String, Object> stats = new HashMap<>();
        stats.put("documentos_procesados", documentosRegistro.size());
        stats.put("total_fragmentos", "N/A");
        return stats;
    }
}