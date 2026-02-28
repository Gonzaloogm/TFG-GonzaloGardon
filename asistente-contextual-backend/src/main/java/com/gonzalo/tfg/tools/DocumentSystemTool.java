package com.gonzalo.tfg.tools;

import com.gonzalo.tfg.model.DocumentoDTO;
import com.gonzalo.tfg.service.DocumentIngestionService;
import dev.langchain4j.agent.tool.Tool;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class DocumentSystemTool
{

    @Inject
    DocumentIngestionService ingestionService;

    @Tool("Obtiene la lista exacta de todos los documentos, archivos y manuales que están cargados actualmente en la base de conocimiento del sistema.")
    public String listarDocumentosDisponibles()
    {
        Log.info("EJECUTANDO TOOL: El LLM ha solicitado la lista de documentos.");

        List<DocumentoDTO> documentos = ingestionService.listarDocumentos();

        if (documentos.isEmpty())
            return "Actualmente no hay ningún documento cargado en el sistema.";

        String listaNombres = documentos.stream()
                .map(doc -> "- " + doc.nombre() + " (Tipo: " + doc.tipo() + ")")
                .collect(Collectors.joining("\n"));

        return "Los documentos disponibles actualmente en el sistema son:\n" + listaNombres;
    }
}