package com.gonzalo.tfg.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClasificadorConsultaTest {

    @Test
    void reconoceSaludosCortos() {
        assertTrue(ClasificadorConsulta.esSaludo("Hola"));
        assertTrue(ClasificadorConsulta.esSaludo("¡Buenas tardes!"));
        assertTrue(ClasificadorConsulta.esSaludo("muchas gracias"));
    }

    @Test
    void unaPreguntaLargaConSaludoNoEsSaludo() {
        assertFalse(ClasificadorConsulta.esSaludo("Hola, ¿qué penalización hay en el contrato Alpha?"));
    }

    @Test
    void reconoceConsultasDeCatalogoConTildesYPuntuacion() {
        assertTrue(ClasificadorConsulta.esConsultaCatalogo("¿Qué documentos tienes?"));
        assertTrue(ClasificadorConsulta.esConsultaCatalogo("Enséñame el catálogo"));
        assertTrue(ClasificadorConsulta.esConsultaCatalogo("lista los ficheros"));
    }

    @Test
    void unaPreguntaSobreElContenidoNecesitaBusqueda() {
        assertFalse(ClasificadorConsulta.noNecesitaBusqueda("¿Cuál es la penalización por retraso en el contrato Alpha?"));
    }

    @Test
    void textoVacioONuloNecesitaBusquedaNormal() {
        assertFalse(ClasificadorConsulta.esSaludo(""));
        assertFalse(ClasificadorConsulta.esSaludo(null));
    }
}
