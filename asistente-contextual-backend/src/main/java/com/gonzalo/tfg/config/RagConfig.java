package com.gonzalo.tfg.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/*
 Configuración avanzada del motor de Generación Aumentada por Recuperación (RAG).
 Parámetros de rendimiento calibrados:
 - maxResults: 5 (equilibrio óptimo entre exhaustividad y precisión).
 - minScore: 0.7 (umbral de relevancia mediante similitud de coseno).
 - Soporte nativo para el filtrado semántico por metadatos.
 
 Basado en las especificaciones del diseño de arquitectura nivel 3.
 */
@ApplicationScoped
public class RagConfig {

  @Produces
  @ApplicationScoped
  public dev.langchain4j.rag.RetrievalAugmentor configuradorRAG(
      EmbeddingStore<TextSegment> storeVectores,
      EmbeddingModel modeloVectores) {

    Log.info("Inicializando RetrievalAugmentor con motor de inyección de metadatos");

    // 1. Definición del Recuperador (Búsqueda semántica sobre pgvector con umbral
    // de filtrado)
    ContentRetriever recuperadorContenido = EmbeddingStoreContentRetriever.builder()
        .embeddingStore(storeVectores)
        .embeddingModel(modeloVectores)
        .maxResults(5)
        .minScore(0.70)
        .build();

    // 2. Motor de inyección de metadatos para enriquecer el contexto del LLM
    // Asocia el nombre del fichero original a cada fragmento recuperado.
    dev.langchain4j.rag.content.injector.DefaultContentInjector inyectorContenido = dev.langchain4j.rag.content.injector.DefaultContentInjector
        .builder()
        .metadataKeysToInclude(java.util.Arrays.asList("nombre_archivo"))
        .build();

    // 3. Ensamblado del motor RAG
    return dev.langchain4j.rag.DefaultRetrievalAugmentor.builder()
        .contentRetriever(recuperadorContenido)
        .contentInjector(inyectorContenido)
        .build();
  }

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