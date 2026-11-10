package com.gonzalo.tfg.tools;

import dev.langchain4j.agent.tool.Tool;
import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Herramientas de consulta del entorno para el LLM.
 *
 * Seguridad:
 * - No existe ejecución de comandos de shell. Una lista negra de comandos no
 *   es una defensa suficiente (se esquiva con python -c, perl, find -delete...),
 *   y un documento con instrucciones ocultas podría provocar la llamada.
 * - Lectura y listado confinados a un único directorio sandbox configurable
 *   (app.tools.sandbox-dir). Las rutas se resuelven con toRealPath(), de modo
 *   que ni "../" ni los enlaces simbólicos permiten salir del sandbox.
 * - Solo lectura: ninguna herramienta escribe ni borra.
 */
@ApplicationScoped
public class SystemActionsTool {

    private static final int MAX_ENTRADAS_DIRECTORIO = 100;
    private static final long MAX_BYTES_FICHERO = 1024 * 1024L; // 1 MB

    @ConfigProperty(name = "app.tools.sandbox-dir", defaultValue = "sandbox")
    String directorioSandbox;

    /** Raíz real (canónica) del sandbox, calculada al arrancar. */
    private Path raizSandbox;

    @PostConstruct
    void inicializar() {
        try {
            Path ruta = Paths.get(directorioSandbox).toAbsolutePath().normalize();
            Files.createDirectories(ruta);
            raizSandbox = ruta.toRealPath();
            Log.infof("Sandbox de herramientas de sistema: %s", raizSandbox);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo preparar el sandbox de herramientas: " + directorioSandbox, e);
        }
    }

    @Tool("Devuelve el contenido de un fichero de texto del directorio sandbox de la aplicación. "
            + "Acepta una ruta relativa al sandbox (ej. 'logs/app.log').")
    public String leerFichero(String ruta) {
        Path real;
        try {
            real = resolverEnSandbox(ruta);
        } catch (SecurityException e) {
            return e.getMessage();
        }

        Log.infof("El motor de IA solicita lectura de: %s", real);

        try {
            if (!Files.isRegularFile(real))
                return "Error: la ruta no corresponde a un fichero regular.";

            long tamanio = Files.size(real);
            if (tamanio > MAX_BYTES_FICHERO)
                return "Error: el fichero excede el límite de lectura (" + (tamanio / 1024) + " KB).";

            return Files.readString(real, StandardCharsets.UTF_8);

        } catch (Exception e) {
            Log.errorf("Error de lectura en %s: %s", real, e.getMessage());
            return "Fallo al acceder al fichero.";
        }
    }

    @Tool("Lista el contenido de un directorio dentro del sandbox de la aplicación. "
            + "Acepta una ruta relativa al sandbox; vacía o '.' lista la raíz del sandbox.")
    public String listarContenidoDirectorio(String ruta) {
        Path real;
        try {
            real = resolverEnSandbox(ruta == null || ruta.isBlank() ? "." : ruta);
        } catch (SecurityException e) {
            return e.getMessage();
        }

        Log.infof("El motor de IA solicita exploración de: %s", real);

        try {
            if (!Files.isDirectory(real))
                return "Error: la ruta no es un directorio válido.";

            List<Path> todas;
            try (Stream<Path> stream = Files.list(real)) {
                todas = stream.collect(Collectors.toList());
            }

            List<String> entradas = todas.stream()
                    .limit(MAX_ENTRADAS_DIRECTORIO)
                    .map(SystemActionsTool::describirEntrada)
                    .collect(Collectors.toList());

            String aviso = todas.size() > MAX_ENTRADAS_DIRECTORIO
                    ? "\n[Listado truncado: se muestran " + MAX_ENTRADAS_DIRECTORIO + " de " + todas.size() + " entradas]"
                    : "";

            return entradas.isEmpty() ? "El directorio está vacío." : String.join("\n", entradas) + aviso;

        } catch (Exception e) {
            Log.errorf("Fallo de exploración en %s: %s", real, e.getMessage());
            return "Error al inspeccionar el directorio.";
        }
    }

    @Tool("Genera un reporte del sistema operativo anfitrión, arquitectura del procesador y estado de la JVM.")
    public String obtenerEstadoSistema() {
        Log.info("El motor de IA solicita métricas del sistema.");
        Runtime rt = Runtime.getRuntime();
        long memoriaMaxima = rt.maxMemory() / (1024 * 1024);
        long memoriaTotal = rt.totalMemory() / (1024 * 1024);
        long memoriaLibre = rt.freeMemory() / (1024 * 1024);

        // No se exponen usuario del SO ni rutas del servidor.
        return String.format(
                "Estado del sistema anfitrión:\n" +
                        "- OS: %s (versión %s, arquitectura %s)\n" +
                        "- CPUs lógicas: %d\n" +
                        "- Memoria JVM — máxima: %d MB | reservada: %d MB | usada: %d MB | libre: %d MB",
                System.getProperty("os.name"),
                System.getProperty("os.version"),
                System.getProperty("os.arch"),
                rt.availableProcessors(),
                memoriaMaxima,
                memoriaTotal,
                memoriaTotal - memoriaLibre,
                memoriaLibre);
    }

    // -------------------------------------------------------------------------

    /**
     * Resuelve una ruta (relativa al sandbox o absoluta) a su ruta real y
     * comprueba que queda dentro del sandbox.
     *
     * @throws SecurityException con un mensaje apto para devolver al LLM si
     *                           la ruta no existe o está fuera del sandbox.
     */
    Path resolverEnSandbox(String ruta) {
        if (ruta == null || ruta.isBlank())
            throw new SecurityException("Error: la ruta no puede estar vacía.");

        try {
            Path candidata = raizSandbox.resolve(ruta).normalize();
            // toRealPath sigue los enlaces simbólicos: la comprobación se hace
            // sobre el destino real, no sobre el nombre.
            Path real = candidata.toRealPath();
            if (!real.startsWith(raizSandbox)) {
                Log.warnf("Acceso denegado a ruta fuera del sandbox: %s", ruta);
                throw new SecurityException("Acceso denegado: la ruta está fuera del directorio permitido.");
            }
            return real;
        } catch (IOException | java.nio.file.InvalidPathException e) {
            throw new SecurityException("Error: la ruta no existe dentro del directorio permitido.");
        }
    }

    private static String describirEntrada(Path p) {
        try {
            boolean esDir = Files.isDirectory(p);
            long tamanio = esDir ? 0L : Files.size(p);
            return String.format("%s - %s (%.2f KB)",
                    esDir ? "[DIR]" : "[FICHERO]",
                    p.getFileName(),
                    tamanio / 1024.0);
        } catch (IOException e) {
            return p.getFileName() + " - [Error al leer atributos]";
        }
    }
}
