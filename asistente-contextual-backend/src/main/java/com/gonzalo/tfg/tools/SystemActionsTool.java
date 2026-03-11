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

    @Tool("Ejecuta una instrucción en la consola (shell) del sistema operativo anfitrión y devuelve el flujo de salida resultante. Debe emplearse con criterio técnico para labores de diagnóstico o consulta del entorno.")
    public String ejecutarComandoConsola(String comando) {
        Log.warn("EJECUCIÓN DE HERRAMIENTA DISCRECIONAL: El motor de IA solicita ejecución de: " + comando);
        try {
            ProcessBuilder constructorProcesos = new ProcessBuilder();
            // Compatibilidad con sistemas basados en POSIX (Mac/Linux)
            constructorProcesos.command("sh", "-c", comando);

            Process proceso = constructorProcesos.start();

            // Temporizador de seguridad de 10 segundos para evitar bloqueos
            boolean finalizado = proceso.waitFor(10, TimeUnit.SECONDS);
            if (!finalizado) {
                proceso.destroy();
                return "Error operacional: La ejecución de la instrucción excedió el tiempo límite y fue abortada.";
            }

            String salida = capturarFlujo(proceso.getInputStream());
            String error = capturarFlujo(proceso.getErrorStream());

            if (proceso.exitValue() != 0) {
                return "La instrucción finalizó con código de error " + proceso.exitValue() + ".\nDetalle del error:\n" + error
                        + "\nSalida estándar:\n" + salida;
            }

            return salida.isEmpty() ? "Instrucción completada exitosamente sin salida por consola."
                    : salida;
        } catch (Exception e) {
            Log.error("Fallo técnico al ejecutar comando: " + e.getMessage());
            return "Error interno del sistema al intentar procesar la instrucción: " + e.getMessage();
        }
    }

    @Tool("Consulta y devuelve el contenido íntegro de un fichero de texto en disco mediante su ruta absoluta. Recomendado para la inspección de registros (logs), ficheros de configuración o código fuente.")
    public String leerFichero(String rutaAbsoluta) {
        Log.info("EJECUCIÓN DE HERRAMIENTA: El motor de IA solicita lectura de: " + rutaAbsoluta);
        try {
            Path ruta = Paths.get(rutaAbsoluta);
            if (!Files.exists(ruta)) {
                return "Error de acceso: No se localiza el fichero en la ruta especificada.";
            }
            if (!Files.isRegularFile(ruta)) {
                return "Error de formato: La ruta no corresponde a un fichero común (posible directorio o dispositivo de bloque).";
            }
            if (!Files.isReadable(ruta)) {
                return "Error de privilegios: El sistema carece de permisos de lectura sobre el recurso.";
            }

            // Restricción de lectura para ficheros de gran volumen (protección de memoria y límites de ventana de contexto)
            long tamanio = Files.size(ruta);
            if (tamanio > 1024 * 1024) { // Límite de seguridad: 1 MB
                return "Error de volumen: El fichero excede la capacidad de lectura permitida (>" + (tamanio / 1024)
                        + " KB).";
            }

            return Files.readString(ruta);
        } catch (Exception e) {
            Log.error("Error de lectura en ruta " + rutaAbsoluta + ": " + e.getMessage());
            return "Fallo técnico al intentar acceder al fichero: " + e.getMessage();
        }
    }

    @Tool("Detalla el contenido de un directorio en el sistema mediante su ruta absoluta. Proporciona una visión jerárquica incluyendo nombres, tamaños y naturaleza (fichero/subdirectorio).")
    public String listarContenidoDirectorio(String rutaAbsoluta) {
        Log.info("EJECUCIÓN DE HERRAMIENTA: El motor de IA solicita exploración en: " + rutaAbsoluta);
        try {
            Path ruta = Paths.get(rutaAbsoluta);
            if (!Files.exists(ruta)) {
                return "Error de exploración: El directorio no existe.";
            }
            if (!Files.isDirectory(ruta)) {
                return "Error de tipo: La ruta proporcionada no es un directorio válido.";
            }

            return Files.list(ruta)
                    .map(p -> {
                        try {
                            String nombre = p.getFileName().toString();
                            boolean esDirectorio = Files.isDirectory(p);
                            long tamanio = esDirectorio ? 0 : Files.size(p);
                            return String.format("%s - %s (%.2f KB)", esDirectorio ? "[DIR]" : "[FICHERO]", nombre, tamanio / 1024.0);
                        } catch (IOException e) {
                            return p.getFileName().toString() + " - [Error en lectura de atributos]";
                        }
                    })
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            Log.error("Fallo de exploración en " + rutaAbsoluta + ": " + e.getMessage());
            return "Error interno durante la inspección del directorio: " + e.getMessage();
        }
    }

    @Tool("Genera un reporte técnico descriptivo del sistema operativo anfitrión, arquitectura del procesador y estado de recursos de la Máquina Virtual de Java (JVM).")
    public String obtenerEstadoSistema() {
        Log.info("EJECUCIÓN DE HERRAMIENTA: El motor de IA solicita métricas del sistema.");
        try {
            String nombreOS = System.getProperty("os.name");
            String versionOS = System.getProperty("os.version");
            String arquitectura = System.getProperty("os.arch");
            String usuario = System.getProperty("user.name");
            String directorioBase = System.getProperty("user.dir");

            Runtime entornoEjecucion = Runtime.getRuntime();
            long memoriaMaxima = entornoEjecucion.maxMemory() / (1024 * 1024);
            long memoriaTotal = entornoEjecucion.totalMemory() / (1024 * 1024);
            long memoriaLibre = entornoEjecucion.freeMemory() / (1024 * 1024);
            long memoriaUsada = memoriaTotal - memoriaLibre;

            return String.format(
                    "Estado Técnico del Sistema Anfitrión:\n" +
                            "- Usuario en ejecución: %s\n" +
                            "- Directorio de despliegue: %s\n" +
                            "- Entorno OS: %s (Versión: %s, Arquitectura: %s)\n" +
                            "- Métricas de Memoria JVM:\n" +
                            "  * Capacidad máxima: %d MB\n" +
                            "  * Reservada actualmente: %d MB\n" +
                            "  * Carga activa: %d MB\n" +
                            "  * Disponible: %d MB",
                    usuario, directorioBase, nombreOS, versionOS, arquitectura, memoriaMaxima, memoriaTotal, memoriaUsada, memoriaLibre);
        } catch (Exception e) {
            return "Excepción crítica durante la captura de métricas del sistema: " + e.getMessage();
        }
    }

    private String capturarFlujo(java.io.InputStream flujoEntrada) throws IOException {
        try (BufferedReader lector = new BufferedReader(new InputStreamReader(flujoEntrada))) {
            return lector.lines().collect(Collectors.joining("\n"));
        }
    }
}
