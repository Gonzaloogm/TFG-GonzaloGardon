package com.gonzalo.tfg.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FiltroThinkingTest {

    /** Pasa los fragmentos por un filtro nuevo y concatena todo lo visible. */
    private static String filtrar(String... fragmentos) {
        FiltroThinking filtro = new FiltroThinking();
        StringBuilder sb = new StringBuilder();
        for (String f : fragmentos) {
            sb.append(filtro.procesar(f));
        }
        sb.append(filtro.finalizar());
        return sb.toString();
    }

    @Test
    void sinThinkingPasaTodoIgual() {
        assertEquals("Hola, ¿qué tal?", filtrar("Hola, ", "¿qué tal?"));
    }

    @Test
    void eliminaUnBloqueCompletoEnUnFragmento() {
        assertEquals("Respuesta final", filtrar("<thinking>razono</thinking>\n\nRespuesta final"));
    }

    @Test
    void eliminaEtiquetasPartidasEntreFragmentos() {
        assertEquals("Respuesta", filtrar("<thin", "king>paso 1", " paso 2</thi", "nking>  ", "Respuesta"));
    }

    @Test
    void conservaTextoAntesYDespues() {
        assertEquals("Antes Después", filtrar("Antes <thinking>x</thinking>Después"));
    }

    @Test
    void noRetieneTextoQueNoEsEtiqueta() {
        FiltroThinking filtro = new FiltroThinking();
        // "a < b" no es el comienzo de <thinking>: debe salir en cuanto se sabe
        assertEquals("a < b", filtro.procesar("a < b"));
        assertEquals("", filtro.finalizar());
    }

    @Test
    void textoParecidoAEtiquetaSeEmiteAlFinal() {
        assertEquals("ver <thin", filtrar("ver <thin"));
    }

    @Test
    void bloqueSinCerrarSeDescarta() {
        assertEquals("Hola ", filtrar("Hola ", "<thinking>esto nunca se cierra"));
    }

    @Test
    void variosBloques() {
        assertEquals("AB", filtrar("<thinking>1</thinking>A", "<thinking>2</thinking>B"));
    }

    @Test
    void prefijoParcial() {
        for (var caso : List.of(
                new Object[] { "hola <thi", 4 },
                new Object[] { "hola <", 1 },
                new Object[] { "hola", 0 },
                new Object[] { "<thinking", 9 })) {
            assertEquals((int) caso[1], FiltroThinking.prefijoParcial((String) caso[0], "<thinking>"), (String) caso[0]);
        }
    }
}
