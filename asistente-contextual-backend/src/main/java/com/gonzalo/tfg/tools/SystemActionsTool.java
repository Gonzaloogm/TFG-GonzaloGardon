package com.gonzalo.tfg.tools;

import dev.langchain4j.agent.tool.Tool;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@ApplicationScoped
public class SystemActionsTool {

    @Tool("Ejecuta un comando de la terminal/shell en el sistema operativo anfitrión y devuelve el resultado. Usar con precaución. Útil para obtener información del entorno, procesos, o utilidades del sistema.")
    public String ejecutarComandoShell(String comando) {
        Log.warn("EJECUTANDO TOOL [SEGURIDAD - ALERTA]: El LLM ha solicitado ejecutar el comando shell: " + comando);
        try {
            ProcessBuilder builder = new ProcessBuilder();
            // Para Mac/Linux
            builder.command("sh", "-c", comando);

            Process process = builder.start();

            // Timeout de 10 segundos
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroy();
                return "Error: La ejecución del comando excedió el tiempo límite de 10 segundos y fue terminada prematuramente.";
            }

            String output = capturarSalida(process.getInputStream());
            String error = capturarSalida(process.getErrorStream());

            if (process.exitValue() != 0) {
                return "El comando falló con código de salida " + process.exitValue() + ".\nError:\n" + error
                        + "\nSalida Estándar:\n" + output;
            }

            return output.isEmpty() ? "Comando ejecutado con éxito, pero no produjo ninguna salida en consola."
                    : output;
        } catch (Exception e) {
            Log.error("Error al ejecutar comando shell: " + e.getMessage());
            return "Error interno crítico al intentar ejecutar el comando: " + e.getMessage();
        }
    }

    @Tool("Lee y devuelve el contenido de un archivo de texto en el sistema anfitrión dado su ruta absoluta. Útil para inspeccionar logs, configuraciones o código. No usar para archivos binarios (ej. PDF, imágenes).")
    public String leerArchivo(String rutaAbsoluta) {
        Log.info("EJECUTANDO TOOL: El LLM ha solicitado leer el archivo: " + rutaAbsoluta);
        try {
            Path path = Paths.get(rutaAbsoluta);
            if (!Files.exists(path)) {
                return "Error: El archivo no existe en la ruta especificada.";
            }
            if (!Files.isRegularFile(path)) {
                return "Error: La ruta especificada no corresponde a un archivo regular (puede ser un directorio o fichero especial).";
            }
            if (!Files.isReadable(path)) {
                return "Error: No hay suficientes permisos de lectura para acceder al archivo.";
            }

            // Limitar lectura de archivos muy grandes para prevenir saturacion de memoria o
            // limites de token del LLM
            long size = Files.size(path);
            if (size > 1024 * 1024) { // 1 MB límite
                return "Error: El archivo es demasiado grande (>" + (size / 1024)
                        + " KB). No está permitido leer archivos mayores a 1MB completos.";
            }

            return Files.readString(path);
        } catch (Exception e) {
            Log.error("Error al leer el archivo en la ruta " + rutaAbsoluta + ": " + e.getMessage());
            return "Error interno al intentar leer el archivo: " + e.getMessage();
        }
    }

    @Tool("Lista el contenido de un directorio en el sistema anfitrión dada su ruta absoluta. Muestra de forma concisa los nombres de archivos presentes en la carpeta, su tamaño y si corresponde a un directorio.")
    public String listarDirectorio(String rutaAbsoluta) {
        Log.info("EJECUTANDO TOOL: El LLM ha solicitado listar el directorio: " + rutaAbsoluta);
        try {
            Path path = Paths.get(rutaAbsoluta);
            if (!Files.exists(path)) {
                return "Error: El directorio no existe en la ruta especificada.";
            }
            if (!Files.isDirectory(path)) {
                return "Error: La ruta proporcionada no corresponde a un directorio válido.";
            }

            return Files.list(path)
                    .map(p -> {
                        try {
                            String name = p.getFileName().toString();
                            boolean isDir = Files.isDirectory(p);
                            long size = isDir ? 0 : Files.size(p);
                            return String.format("%s - %s (%.2f KB)", isDir ? "[DIR]" : "[FILE]", name, size / 1024.0);
                        } catch (IOException e) {
                            return p.getFileName().toString() + " - [Error al leer propiedades]";
                        }
                    })
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            Log.error("Error al listar el directorio de la ruta " + rutaAbsoluta + ": " + e.getMessage());
            return "Error interno al intentar listar el directorio: " + e.getMessage();
        }
    }

    @Tool("Devuelve información técnica básica del sistema operativo anfitrión (OS), incluyendo el nombre, versión, arquitectura y estado de la memoria RAM de la Máquina Virtual de Java (JVM).")
    public String obtenerInformacionSistema() {
        Log.info("EJECUTANDO TOOL: El LLM ha solicitado un reporte de información del sistema anfitrión.");
        try {
            String osName = System.getProperty("os.name");
            String osVersion = System.getProperty("os.version");
            String osArch = System.getProperty("os.arch");
            String userName = System.getProperty("user.name");
            String userDir = System.getProperty("user.dir");

            Runtime runtime = Runtime.getRuntime();
            long maxMemory = runtime.maxMemory() / (1024 * 1024);
            long totalMemory = runtime.totalMemory() / (1024 * 1024);
            long freeMemory = runtime.freeMemory() / (1024 * 1024);
            long usedMemory = totalMemory - freeMemory;

            return String.format(
                    "Información del Sistema Anfitrión:\n" +
                            "- Usuario Actual en el OS: %s\n" +
                            "- Directorio de Trabajo: %s\n" +
                            "- Sistema Operativo: %s (Versión: %s, Arquitectura: %s)\n" +
                            "- Rendimiento de Memoria JVM:\n" +
                            "  * Máxima Memoria Configurada: %d MB\n" +
                            "  * Memoria Total Asignada al Proceso: %d MB\n" +
                            "  * Memoria Usada: %d MB\n" +
                            "  * Memoria Libre: %d MB",
                    userName, userDir, osName, osVersion, osArch, maxMemory, totalMemory, usedMemory, freeMemory);
        } catch (Exception e) {
            return "Se produjo un error crítico al obtener la información del sistema: " + e.getMessage();
        }
    }

    private String capturarSalida(java.io.InputStream inputStream) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }
}
