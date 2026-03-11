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
    DocumentIngestionService servicioIngestion;

    @Tool("Recupera la relación detallada de todos los ficheros, manuales y documentos técnicos integrados en la base de conocimiento del sistema.")
    public String listarDocumentosDisponibles()
    {
        Log.info("EJECUCIÓN DE HERRAMIENTA: El motor de IA solicita el inventario de ficheros.");

        List<DocumentoDTO> ficheros = servicioIngestion.listarDocumentos();

        if (ficheros.isEmpty())
            return "Actualmente no se han identificado ficheros cargados en el sistema.";

        String listado = ficheros.stream()
                .map(f -> "- " + f.nombre() + " (Categoría: " + f.tipo() + ")")
                .collect(Collectors.joining("\n"));

        return "Los ficheros actualmente disponibles en la base de conocimiento son:\n" + listado;
    }
}