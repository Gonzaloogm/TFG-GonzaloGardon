package com.gonzalo.tfg.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests unitarios del confinamiento de SystemActionsTool a su sandbox.
 * No necesitan arrancar Quarkus ni base de datos.
 */
class SystemActionsToolTest {

    @TempDir
    Path tmp;

    private SystemActionsTool herramienta;
    private Path fueraDelSandbox;

    @BeforeEach
    void preparar() throws Exception {
        Path sandbox = Files.createDirectories(tmp.resolve("sandbox"));
        Files.writeString(sandbox.resolve("permitido.txt"), "contenido permitido");
        Files.createDirectories(sandbox.resolve("sub"));

        fueraDelSandbox = tmp.resolve("secreto.properties");
        Files.writeString(fueraDelSandbox, "app.admin.key=no-deberias-verme");

        herramienta = new SystemActionsTool();
        herramienta.directorioSandbox = sandbox.toString();
        herramienta.inicializar();
    }

    @Test
    void leeFicherosDentroDelSandbox() {
        assertEquals("contenido permitido", herramienta.leerFichero("permitido.txt"));
    }

    @Test
    void rechazaSubirConPuntoPunto() {
        String r = herramienta.leerFichero("../secreto.properties");
        assertTrue(r.startsWith("Acceso denegado"), r);
    }

    @Test
    void rechazaRutasAbsolutasFueraDelSandbox() {
        String r = herramienta.leerFichero(fueraDelSandbox.toString());
        assertTrue(r.startsWith("Acceso denegado"), r);
    }

    @Test
    void rechazaEnlacesSimbolicosQueSalenDelSandbox() throws Exception {
        Path sandbox = Path.of(herramienta.directorioSandbox);
        Files.createSymbolicLink(sandbox.resolve("atajo"), fueraDelSandbox);
        String r = herramienta.leerFichero("atajo");
        assertTrue(r.startsWith("Acceso denegado"), r);
    }

    @Test
    void rutaInexistenteNoRevelaInformacion() {
        String r = herramienta.leerFichero("no-existe.txt");
        assertTrue(r.startsWith("Error: la ruta no existe"), r);
    }

    @Test
    void listaLaRaizDelSandbox() {
        String r = herramienta.listarContenidoDirectorio(".");
        assertTrue(r.contains("permitido.txt") && r.contains("sub"), r);
    }

    @Test
    void noListaDirectoriosFueraDelSandbox() {
        String r = herramienta.listarContenidoDirectorio("..");
        assertTrue(r.startsWith("Acceso denegado"), r);
    }
}
