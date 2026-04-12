package com.gonzalo.tfg.service;

import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.data.document.Metadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.agroal.api.AgroalDataSource;
import io.quarkus.logging.Log;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import com.fasterxml.jackson.databind.ObjectMapper;

@ApplicationScoped
public class PostgresKeywordRetriever {

    @Inject
    AgroalDataSource dataSource;
    
    @Inject
    ObjectMapper objectMapper;

    public ContentRetriever crearRetriever(String archivoFiltro, String customSearchTerm) {
        return query -> {
            try {
                String searchTerm = (customSearchTerm != null) ? customSearchTerm : query.text();
                List<Content> contents = new ArrayList<>();

                String sql = "SELECT text, metadata FROM embeddings " +
                             "WHERE websearch_to_tsquery('spanish', ?) @@ to_tsvector('spanish', text) ";
                
                if (archivoFiltro != null) {
                    sql += "AND metadata @> ?::jsonb ";
                }
                
                sql += "ORDER BY ts_rank(to_tsvector('spanish', text), websearch_to_tsquery('spanish', ?)) DESC " +
                       "LIMIT 15";

                try (Connection conn = dataSource.getConnection();
                     PreparedStatement stmt = conn.prepareStatement(sql)) {
                     
                    int paramIndex = 1;
                    stmt.setString(paramIndex++, searchTerm);
                    
                    if (archivoFiltro != null) {
                        Map<String, String> f = new HashMap<>();
                        f.put("nombre_archivo", archivoFiltro);
                        stmt.setString(paramIndex++, objectMapper.writeValueAsString(f));
                    }
                    
                    stmt.setString(paramIndex++, searchTerm);

                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            String text = rs.getString("text");
                            String metadataJson = rs.getString("metadata");
                            
                            Metadata langChain4jMetadata = new Metadata();
                            if (metadataJson != null && !metadataJson.isEmpty()) {
                                Map<String, String> metaMap = objectMapper.readValue(metadataJson,
                                    objectMapper.getTypeFactory().constructMapType(HashMap.class, String.class, String.class));
                                metaMap.forEach(langChain4jMetadata::put);
                            }
                            
                            contents.add(Content.from(TextSegment.from(text, langChain4jMetadata)));
                        }
                    }
                }
                Log.infof("Keyword Search '%s' arrojó %d resultados.", searchTerm, contents.size());
                return contents;
            } catch (Exception e) {
                Log.error("Error en Keyword Search de Postgres", e);
                return new ArrayList<>();
            }
        };
    }
}
