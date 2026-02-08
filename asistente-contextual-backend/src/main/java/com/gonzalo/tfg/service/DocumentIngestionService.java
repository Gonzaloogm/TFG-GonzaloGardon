package com.gonzalo.tfg.service;

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
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

/**
 * Servicio de Ingestión de Documentos.
 * 
 * Implementa el "Módulo de Ingestión" del diagrama de arquitectura.
 * Responsable de:
 * 1. Extracción de texto de documentos (PDF, DOCX, TXT)
 * 2. Segmentación (chunking) con overlapping
 * 3. Generación de embeddings
 * 4. Almacenamiento en pgvector
 * 
 * Pipeline según arquitectura:
 * Documento → Extracción → Chunking (300 tokens, overlap 30) → Embeddings → pgvector
 */
@ApplicationScoped
public class DocumentIngestionService {

    @Inject
    EmbeddingStore<TextSegment> embeddingStore;

    @Inject
    EmbeddingModel embeddingModel;

    // Configuración del chunking según arquitectura
    private static final int CHUNK_SIZE = 300;      // tokens
    private static final int CHUNK_OVERLAP = 30;    // tokens

    /**
     * Ingiere un documento desde un Path del sistema de archivos.
     * 
     * @param documentPath Ruta del documento
     * @param metadata Metadatos adicionales (autor, departamento, etc.)
     */
    public void ingerirDocumento(Path documentPath, String metadata) {
        Log.infof("Iniciando ingestión de documento: %s", documentPath);

        try {
            // 1. Cargar documento
            Document document = FileSystemDocumentLoader.loadDocument(
                documentPath,
                crearParser(documentPath.toString())
            );

            // 2. Segmentar en chunks con overlap
            DocumentSplitter splitter = DocumentSplitters.recursive(
                CHUNK_SIZE,
                CHUNK_OVERLAP
            );
            List<TextSegment> segments = splitter.split(document);

            Log.infof("Documento segmentado en %d fragmentos", segments.size());

            // 3. Generar embeddings y almacenar
            almacenarSegmentos(segments);

            Log.infof("✅ Documento ingerido exitosamente: %s", documentPath);

        } catch (Exception e) {
            Log.errorf(e, "❌ Error ingiriendo documento: %s", documentPath);
            throw new RuntimeException("Error en ingestión de documento", e);
        }
    }

    /**
     * Ingiere contenido de texto directamente.
     * Útil para ingestión programática sin archivos.
     * 
     * @param contenido Texto a ingerir
     * @param nombreDocumento Nombre descriptivo
     */
    public void ingerirTexto(String contenido, String nombreDocumento) {
        Log.infof("Iniciando ingestión de texto: %s", nombreDocumento);

        try {
            // Crear documento desde texto
            Document document = Document.from(contenido);

            // Segmentar
            DocumentSplitter splitter = DocumentSplitters.recursive(
                CHUNK_SIZE,
                CHUNK_OVERLAP
            );
            List<TextSegment> segments = splitter.split(document);

            Log.infof("Texto segmentado en %d fragmentos", segments.size());

            // Almacenar
            almacenarSegmentos(segments);

            Log.infof("✅ Texto ingerido exitosamente: %s", nombreDocumento);

        } catch (Exception e) {
            Log.errorf(e, "❌ Error ingiriendo texto: %s", nombreDocumento);
            throw new RuntimeException("Error en ingestión de texto", e);
        }
    }

    /**
     * Almacena segmentos en pgvector con sus embeddings.
     * 
     * @param segments Lista de segmentos de texto
     */
    private void almacenarSegmentos(List<TextSegment> segments) {
        // Generar embeddings para cada segmento
        for (TextSegment segment : segments) {
            Embedding embedding = embeddingModel.embed(segment).content();
            embeddingStore.add(embedding, segment);
        }
    }

    /**
     * Crea el parser apropiado según el tipo de archivo.
     * 
     * Comparativa (según arquitectura):
     * - TXT: TextDocumentParser (más rápido)
     * - PDF/DOCX: ApacheTikaDocumentParser (preserva estructura)
     * 
     * TODO Fase 2: Evaluar Docling para documentos con tablas complejas
     * 
     * @param filename Nombre del archivo
     * @return Parser apropiado
     */
    private DocumentParser crearParser(String filename) {
        String extension = filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();

        return switch (extension) {
            case "txt", "md" -> new TextDocumentParser();
            case "pdf", "docx", "doc" -> new ApacheTikaDocumentParser();
            default -> {
                Log.warnf("Extensión no reconocida: %s, usando Tika", extension);
                yield new ApacheTikaDocumentParser();
            }
        };
    }

    /**
     * Obtiene estadísticas del almacén vectorial.
     * Útil para monitoreo y debugging.
     */
    public String obtenerEstadisticas() {
        // TODO: Implementar consulta a pgvector para contar embeddings
        return "Estadísticas no disponibles aún";
    }
}
