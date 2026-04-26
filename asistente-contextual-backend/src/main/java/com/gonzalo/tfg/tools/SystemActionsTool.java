package com.gonzalo.tfg.tools;

import dev.langchain4j.agent.tool.Tool;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@ApplicationScoped
public class SystemActionsTool {

    // SEC1: Patrones cuya presencia en el comando provoca rechazo inmediato.
    private static final List<String> PATRONES_BLOQUEADOS = List.of(
            "rm ", "rmdir", "mkfs", "dd ", "shred", "wipefs",
            "> /dev", "curl", "wget", "nc ", "netcat",
            "chmod 777", "sudo", "su ", "|bash", "| bash",
            "eval ", "exec ", "/dev/sd", "/dev/nvme");

    // SEC2: Únicas raíces del sistema de ficheros a las que el LLM puede acceder.
    private static final Set<String> DIRECTORIOS_PERMITIDOS = Set.of(
            System.getProperty("user.dir"),
            System.getProperty("java.io.tmpdir"));

    private static final int MAX_ENTRADAS_DIRECTORIO = 100;
    private static final long MAX_BYTES_FICHERO = 1024 * 1024L; // 1 MB
    private static final int TIMEOUT_SEGUNDOS = 10;

    @Tool("Ejecuta una instrucción en la consola del sistema operativo anfitrión y devuelve la salida resultante. Para uso diagnóstico del entorno.")
    public String ejecutarComandoConsola(String comando) {

        // SEC1: Rechazo preventivo de comandos destructivos o de red
        String comandoLower = comando.toLowerCase();
        for (String patron : PATRONES_BLOQUEADOS) {
            if (comandoLower.contains(patron)) {
                Log.warnf("Comando bloqueado por política de seguridad: '%s'", comando);
                return "Ejecución denegada: el comando contiene una operación no permitida («" + patron + "»).";
            }
        }

        Log.warnf("El motor de IA solicita ejecución de: %s", comando);

        try {
            ProcessBuilder pb = new ProcessBuilder();

            // F1: Selección del intérprete según el sistema operativo
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                pb.command("cmd", "/c", comando);
            } else {
                pb.command("sh", "-c", comando);
            }

            // SEC4: Un único stream para stdout + stderr elimina el riesgo de deadlock
            pb.redirectErrorStream(true);

            Process proceso = pb.start();

            boolean finalizado = proceso.waitFor(TIMEOUT_SEGUNDOS, TimeUnit.SECONDS);

            if (!finalizado) {
                // SEC3: destroyForcibly() garantiza que el PID desaparezca del SO
                proceso.destroyForcibly();
                proceso.waitFor(2, TimeUnit.SECONDS);
                return "Error: la instrucción superó el límite de " + TIMEOUT_SEGUNDOS + " segundos y fue abortada.";
            }

            String salida = capturarFlujo(proceso.getInputStream());

            if (proceso.exitValue() != 0) {
                return "Instrucción finalizada con código de error " + proceso.exitValue() + ".\n" + salida;
            }

            return salida.isEmpty() ? "Instrucción completada sin salida." : salida;

        } catch (Exception e) {
            Log.errorf("Fallo al ejecutar comando: %s", e.getMessage());
            return "Error interno al procesar la instrucción: " + e.getMessage();
        }
    }

    @Tool("Devuelve el contenido de un fichero de texto mediante su ruta absoluta. Para inspección de logs, configuración o código fuente.")
    public String leerFichero(String rutaAbsoluta) {

        // SEC2: Canonicalización + validación de ruta base
        String rechazo = validarRuta(rutaAbsoluta);
        if (rechazo != null)
            return rechazo;

        Log.infof("El motor de IA solicita lectura de: %s", rutaAbsoluta);

        try {
            Path ruta = Paths.get(rutaAbsoluta).toRealPath();

            if (!Files.isRegularFile(ruta))
                return "Error: la ruta no corresponde a un fichero regular.";
            if (!Files.isReadable(ruta))
                return "Error: sin permisos de lectura sobre el recurso.";

            long tamanio = Files.size(ruta);
            if (tamanio > MAX_BYTES_FICHERO)
                return "Error: el fichero excede el límite de lectura (" + (tamanio / 1024) + " KB).";

            // ROB4: Charset explícito para evitar problemas con locales no-UTF-8
            return Files.readString(ruta, StandardCharsets.UTF_8);

        } catch (Exception e) {
            Log.errorf("Error de lectura en %s: %s", rutaAbsoluta, e.getMessage());
            return "Fallo al acceder al fichero: " + e.getMessage();
        }
    }

    @Tool("Lista el contenido de un directorio mediante su ruta absoluta. Indica nombres, tamaños y tipo (fichero/directorio).")
    public String listarContenidoDirectorio(String rutaAbsoluta) {

        // SEC2: Validación de ruta base antes de listar
        String rechazo = validarRuta(rutaAbsoluta);
        if (rechazo != null)
            return rechazo;

        Log.infof("El motor de IA solicita exploración de: %s", rutaAbsoluta);

        try {
            Path ruta = Paths.get(rutaAbsoluta).toRealPath();

            if (!Files.isDirectory(ruta))
                return "Error: la ruta no es un directorio válido.";

            // ROB2: try-with-resources cierra el DirectoryStream subyacente
            List<String> entradas;
            try (Stream<Path> stream = Files.list(ruta)) {
                entradas = stream
                        .limit(MAX_ENTRADAS_DIRECTORIO)
                        .map(p -> {
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
                        })
                        .collect(Collectors.toList());
            }

            // F2: Aviso de truncado si el directorio tiene más entradas que el límite
            long total = Files.list(ruta).count();
            String aviso = total > MAX_ENTRADAS_DIRECTORIO
                    ? "\n[Listado truncado: se muestran " + MAX_ENTRADAS_DIRECTORIO + " de " + total + " entradas]"
                    : "";

            return String.join("\n", entradas) + aviso;

        } catch (Exception e) {
            Log.errorf("Fallo de exploración en %s: %s", rutaAbsoluta, e.getMessage());
            return "Error al inspeccionar el directorio: " + e.getMessage();
        }
    }

    @Tool("Genera un reporte del sistema operativo anfitrión, arquitectura del procesador y estado de la JVM.")
    public String obtenerEstadoSistema() {
        Log.info("El motor de IA solicita métricas del sistema.");
        try {
            Runtime rt = Runtime.getRuntime();
            long memoriaMaxima = rt.maxMemory() / (1024 * 1024);
            long memoriaTotal = rt.totalMemory() / (1024 * 1024);
            long memoriaLibre = rt.freeMemory() / (1024 * 1024);

            return String.format(
                    "Estado del sistema anfitrión:\n" +
                            "- Usuario: %s\n" +
                            "- Directorio de trabajo: %s\n" +
                            "- OS: %s (versión %s, arquitectura %s)\n" +
                            "- CPUs lógicas: %d\n" + // F3: nuevo campo útil para diagnóstico
                            "- Memoria JVM — máxima: %d MB | reservada: %d MB | usada: %d MB | libre: %d MB",
                    System.getProperty("user.name"),
                    System.getProperty("user.dir"),
                    System.getProperty("os.name"),
                    System.getProperty("os.version"),
                    System.getProperty("os.arch"),
                    rt.availableProcessors(), // F3
                    memoriaMaxima,
                    memoriaTotal,
                    memoriaTotal - memoriaLibre,
                    memoriaLibre);
        } catch (Exception e) {
            return "Error al capturar métricas del sistema: " + e.getMessage();
        }
    }

    // -------------------------------------------------------------------------

    /**
     * SEC2: Canonicaliza la ruta y verifica que esté dentro de
     * DIRECTORIOS_PERMITIDOS.
     * 
     * @return null si la ruta es válida; mensaje de error si debe rechazarse.
     */
    private String validarRuta(String rutaAbsoluta) {
        if (rutaAbsoluta == null || rutaAbsoluta.isBlank())
            return "Error: la ruta no puede estar vacía.";

        try {
            Path candidata = Paths.get(rutaAbsoluta).normalize().toAbsolutePath();

            boolean permitida = DIRECTORIOS_PERMITIDOS.stream()
                    .map(base -> Paths.get(base).normalize().toAbsolutePath())
                    .anyMatch(base -> candidata.startsWith(base));

            if (!permitida) {
                Log.warnf("Acceso denegado a ruta fuera de zona permitida: %s", rutaAbsoluta);
                return "Acceso denegado: la ruta está fuera de los directorios permitidos.";
            }

            return null;

        } catch (Exception e) {
            return "Error al resolver la ruta: " + e.getMessage();
        }
    }

    // ROB1: UncheckedIOException preserva el tipo de error sin forzar checked
    // exceptions
    private String capturarFlujo(java.io.InputStream flujo) {
        try (BufferedReader lector = new BufferedReader(new InputStreamReader(flujo, StandardCharsets.UTF_8))) {
            return lector.lines().collect(Collectors.joining("\n"));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Error al leer el flujo de salida del proceso", e);
        }
    }
}