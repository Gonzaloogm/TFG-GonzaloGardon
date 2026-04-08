package com.gonzalo.tfg.service;

import io.quarkiverse.langchain4j.RegisterAiService;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.SystemMessage;

@RegisterAiService
public interface MetadataExtractor {
    
    @SystemMessage("Eres un analista de datos experto en extraer entidades de texto.")
    @UserMessage("Extrae de este fragmento las 3 entidades clave (tecnologías, personas, fechas) y devuélvelas como una lista separada por comas:\n\n{text}")
    String extraerEntidades(String text);
}
