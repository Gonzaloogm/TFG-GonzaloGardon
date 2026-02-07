package com.gonzalo.tfg;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

@RegisterAiService
public interface AsistenteService {

    @SystemMessage("Eres un asistente útil y amable.")
    String chat(@UserMessage String message);
}
