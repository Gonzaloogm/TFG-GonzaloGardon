package com.gonzalo.tfg.tools;

import com.gonzalo.tfg.model.DocumentoDTO;
import com.gonzalo.tfg.service.DocumentIngestionService;
import dev.langchain4j.agent.tool.Tool;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Generación de informes estructurados en Markdown.
 *
 * Permite al LLM materializar el resultado de una investigación en un fichero
 * descargable, que el usuario puede abrir directamente o importar en
 * Word/Notion.
 * Útil especialmente para el rol de Investigador de Mercados Senior del
 * sistema.
 */
@ApplicationScoped
public class ReportGeneratorTool {

    @Inject
    DocumentIngestionService servicioIngestion;

    private static final String DIRECTORIO_INFORMES = "informes";
    private static final DateTimeFormatter FORMATO_FECHA = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm");

    @Tool("Genera un informe de investigación en formato Markdown y lo guarda en disco. Úsala cuando el usuario pida exportar, guardar o generar un documento con el resultado del análisis. Parámetros: título del informe, contenido en Markdown (con el análisis completo), y opcionalmente el sessionId de la conversación actual.")
    public String generarInformeMarkdown(String titulo, String contenidoMarkdown, String sessionId) {
        Log.infof("Generando informe: '%s'", titulo);

        if (titulo == null || titulo.isBlank()) {
            return "Error: el título del informe no puede estar vacío.";
        }
        if (contenidoMarkdown == null || contenidoMarkdown.isBlank()) {
            return "Error: el contenido del informe no puede estar vacío.";
        }

        try {
            Path dirInformes = Paths.get(DIRECTORIO_INFORMES);
            if (!Files.exists(dirInformes)) {
                Files.createDirectories(dirInformes);
            }

            String nombreFichero = titulo
                    .toLowerCase()
                    .replaceAll("[^a-z0-9áéíóúñ\\s]", "")
                    .replaceAll("\\s+", "_")
                    .substring(0, Math.min(50, titulo.length()))
                    + "_" + LocalDateTime.now().format(FORMATO_FECHA) + ".md";

            String encabezado = construirEncabezado(titulo, sessionId);
            String contenidoCompleto = encabezado + contenidoMarkdown + construirPieDePagina();

            Path rutaInforme = dirInformes.resolve(nombreFichero);
            Files.writeString(rutaInforme, contenidoCompleto);

            Log.infof("Informe generado en: %s", rutaInforme.toAbsolutePath());

            return String.format(
                    "Informe generado correctamente:\n" +
                            "- Fichero: %s\n" +
                            "- Ruta:    %s\n" +
                            "- Tamaño:  %d KB\n\n" +
                            "El informe está disponible en el directorio '%s' del servidor.",
                    nombreFichero,
                    rutaInforme.toAbsolutePath(),
                    Files.size(rutaInforme) / 1024 + 1,
                    DIRECTORIO_INFORMES);

        } catch (IOException e) {
            Log.errorf("Error al escribir el informe: %s", e.getMessage());
            return "Error al guardar el informe en disco: " + e.getMessage();
        }
    }

    @Tool("Genera un informe de inventario completo de la base de conocimiento: lista todos los documentos, sus metadatos, tecnologías detectadas y estadísticas de cobertura. Úsala cuando el usuario quiera un resumen del estado de la base de conocimiento.")
    public String generarInventarioBaseConocimiento() {
        Log.info("Generando inventario de la base de conocimiento.");

        List<DocumentoDTO> documentos = servicioIngestion.listarDocumentos();

        if (documentos.isEmpty()) {
            return "La base de conocimiento está vacía. No hay documentos para inventariar.";
        }

        StringBuilder md = new StringBuilder();
        md.append("# Inventario de la Base de Conocimiento\n\n");
        md.append("_Generado el ").append(LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))).append("_\n\n");
        md.append("---\n\n");
        md.append("## Resumen\n\n");
        md.append("| Métrica | Valor |\n|---|---|\n");
        md.append("| Documentos indexados | ").append(documentos.size()).append(" |\n");

        long totalKB = documentos.stream().mapToLong(d -> d.tamanioBytes() != null ? d.tamanioBytes() / 1024 : 0).sum();
        md.append("| Tamaño total | ").append(totalKB).append(" KB |\n");
        md.append("| Fragmentos estimados | ").append(totalKB * 1024 / (300 * 4)).append(" |\n\n");

        md.append("## Documentos\n\n");

        for (int i = 0; i < documentos.size(); i++) {
            DocumentoDTO doc = documentos.get(i);
            md.append("### ").append(i + 1).append(". ").append(doc.nombre()).append("\n\n");
            md.append("| Campo | Valor |\n|---|---|\n");
            md.append("| Tipo | ").append(doc.tipo()).append(" |\n");
            md.append("| Tamaño | ").append(doc.tamanioBytes() != null ? doc.tamanioBytes() / 1024 + " KB" : "N/D")
                    .append(" |\n");
            md.append("| Fecha de ingesta | ").append(
                    doc.fechaCarga() != null
                            ? doc.fechaCarga().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
                            : "N/D")
                    .append(" |\n");

            if (doc.metadatos() != null) {
                String techs = doc.metadatos().getOrDefault("tecnologias", "");
                String fechas = doc.metadatos().getOrDefault("fechas", "");
                if (!techs.isBlank())
                    md.append("| Tecnologías | ").append(techs).append(" |\n");
                if (!fechas.isBlank())
                    md.append("| Fechas detectadas | ").append(fechas).append(" |\n");
            }
            md.append("\n");
        }

        String contenido = md.toString();

        try {
            Path dirInformes = Paths.get(DIRECTORIO_INFORMES);
            if (!Files.exists(dirInformes))
                Files.createDirectories(dirInformes);

            String nombreFichero = "inventario_" + LocalDateTime.now().format(FORMATO_FECHA) + ".md";
            Files.writeString(dirInformes.resolve(nombreFichero), contenido);

            return contenido + "\n\n---\n_Informe guardado en: " + DIRECTORIO_INFORMES + "/" + nombreFichero + "_";
        } catch (IOException e) {
            return contenido + "\n\n_(Nota: no se pudo guardar el fichero en disco: " + e.getMessage() + ")_";
        }
    }

    @Tool("Lista todos los informes generados previamente que están disponibles en el servidor. Úsala cuando el usuario pregunte por informes anteriores o quiera recuperar un análisis previo.")
    public String listarInformesGenerados() {
        Log.info("El motor de IA solicita el listado de informes generados.");

        Path dirInformes = Paths.get(DIRECTORIO_INFORMES);

        if (!Files.exists(dirInformes)) {
            return "Aún no se ha generado ningún informe. El directorio '" + DIRECTORIO_INFORMES + "' no existe.";
        }

        try {
            List<Path> informes;
            try (var stream = Files.list(dirInformes)) {
                informes = stream
                        .filter(p -> p.toString().endsWith(".md"))
                        .sorted((a, b) -> {
                            try {
                                return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                            } catch (IOException e) {
                                return 0;
                            }
                        })
                        .collect(Collectors.toList());
            }

            if (informes.isEmpty()) {
                return "El directorio de informes existe pero no contiene ficheros .md.";
            }

            StringBuilder sb = new StringBuilder("Informes disponibles:\n\n");
            for (int i = 0; i < informes.size(); i++) {
                Path inf = informes.get(i);
                try {
                    long kb = Files.size(inf) / 1024 + 1;
                    sb.append(String.format("%d. %s (%d KB)\n",
                            i + 1, inf.getFileName(), kb));
                } catch (IOException e) {
                    sb.append(i + 1).append(". ").append(inf.getFileName()).append("\n");
                }
            }

            sb.append("\nPara leer un informe, usa la herramienta de lectura de ficheros con la ruta: ")
                    .append(dirInformes.toAbsolutePath()).append("/<nombre_fichero>");

            return sb.toString();

        } catch (IOException e) {
            return "Error al listar los informes: " + e.getMessage();
        }
    }

    private String construirEncabezado(String titulo, String sessionId) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("title: ").append(titulo).append("\n");
        sb.append("date: ").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\n");
        if (sessionId != null)
            sb.append("session_id: ").append(sessionId).append("\n");
        sb.append("role: Investigador de Mercados Senior\n");
        sb.append("---\n\n");
        sb.append("# ").append(titulo).append("\n\n");
        return sb.toString();
    }

    private String construirPieDePagina() {
        return "\n\n---\n\n" +
                "_Este informe fue generado automáticamente por el Asistente Contextual. " +
                "La información proviene de la base de conocimiento interna y/o búsquedas web. " +
                "Verifique los datos críticos con las fuentes originales._\n";
    }
}