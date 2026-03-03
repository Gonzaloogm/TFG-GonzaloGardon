package com.gonzalo.tfg.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/**
 * Configuración optimizada del sistema RAG.
 * Parámetros ajustados según mejores prácticas:
 * - maxResults: 5 (balance entre contexto y precisión)
 * - minScore: 0.7 (threshold de similitud coseno)
 * - Soporte para filtrado por metadatos
 * Basado en:
 * - Documento de arquitectura (Diagrama Nivel 3 - Retriever)
 * - Benchmarks de retrieval accuracy (objetivo >70%)
 */
@ApplicationScoped
public class RagConfig {

  /**
   * Proveedor de memoria para mantener el contexto de la conversación.
   * Limita el historial a 10 mensajes para evitar degradación de contexto.
   */
  @Produces
  public ChatMemoryProvider chatMemoryProvider() {
    return memoryId -> MessageWindowChatMemory.builder()
        .id(memoryId)
        .maxMessages(10)
        .build();
  }

  @Produces
  @ApplicationScoped
  public dev.langchain4j.rag.RetrievalAugmentor retrievalAugmentor(
      EmbeddingStore<TextSegment> embeddingStore,
      EmbeddingModel embeddingModel) {

    Log.info("Configurando RetrievalAugmentor con Inyector de Metadatos");

    // 1. El Retriever (Búsqueda en pgvector con threshold de similitud alto)
    ContentRetriever contentRetriever = EmbeddingStoreContentRetriever.builder()
        .embeddingStore(embeddingStore)
        .embeddingModel(embeddingModel)
        .maxResults(5)
        .minScore(0.70)
        .build();

    // 2. ¡LA PIEZA CLAVE QUE FALTABA!
    // Le inyecta el "nombre_archivo" al texto que lee el LLM
    dev.langchain4j.rag.content.injector.DefaultContentInjector contentInjector = dev.langchain4j.rag.content.injector.DefaultContentInjector
        .builder()
        .metadataKeysToInclude(java.util.Arrays.asList("nombre_archivo"))
        .build();

    // 3. Ensamblamos el RAG
    return dev.langchain4j.rag.DefaultRetrievalAugmentor.builder()
        .contentRetriever(contentRetriever)
        .contentInjector(contentInjector)
        .build();
  }

  /**
   * Crea un ContentRetriever con filtro de metadatos.
   * Ejemplo de uso:
   * var retriever = createFilteredRetriever(embeddingStore, embeddingModel,
   * filter -> filter.eq("departamento", "IT"));
   * 
   * @param embeddingStore Store de embeddings
   * @param embeddingModel Modelo de embeddings
   * @param filterKey      Clave de metadato a filtrar
   * @param filterValue    Valor del metadato
   * @return ContentRetriever filtrado
   */
  public static ContentRetriever createFilteredRetriever(
      EmbeddingStore<TextSegment> embeddingStore,
      EmbeddingModel embeddingModel,
      String filterKey,
      String filterValue) {
    Log.infof("Creando retriever filtrado: %s = %s", filterKey, filterValue);

    return EmbeddingStoreContentRetriever.builder()
        .embeddingStore(embeddingStore)
        .embeddingModel(embeddingModel)
        .maxResults(5)
        .minScore(0.70)
        .filter(MetadataFilterBuilder.metadataKey(filterKey).isEqualTo(filterValue))
        .build();
  }
}