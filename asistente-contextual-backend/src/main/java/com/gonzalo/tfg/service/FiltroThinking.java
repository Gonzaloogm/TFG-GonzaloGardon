package com.gonzalo.tfg.service;

/**
 * Elimina los bloques {@code <thinking>...</thinking>} de una respuesta que
 * llega troceada en streaming.
 *
 * Con la respuesta completa bastaba una expresión regular. En streaming una
 * etiqueta puede llegar partida entre fragmentos ("<thin" + "king>"), así que
 * el filtro retiene el texto que podría ser el inicio de una etiqueta hasta
 * saber si lo es.
 *
 * Tiene estado: se crea una instancia por respuesta y no es thread-safe.
 */
public class FiltroThinking {

    private static final String APERTURA = "<thinking>";
    private static final String CIERRE = "</thinking>";

    private final StringBuilder pendiente = new StringBuilder();
    private boolean dentroDeThinking = false;
    /** Tras cerrar un bloque se descartan los espacios iniciales (como hacía la regex). */
    private boolean saltarEspacios = false;

    /**
     * Procesa un fragmento y devuelve el texto que ya se puede mostrar.
     *
     * @param fragmento Trozo de respuesta recibido del modelo.
     * @return Texto visible (puede ser vacío si todo queda retenido o era razonamiento).
     */
    public String procesar(String fragmento) {
        if (fragmento == null || fragmento.isEmpty()) {
            return "";
        }
        pendiente.append(fragmento);
        StringBuilder salida = new StringBuilder();

        while (true) {
            if (!dentroDeThinking) {
                int inicio = pendiente.indexOf(APERTURA);
                if (inicio >= 0) {
                    emitir(salida, pendiente.substring(0, inicio));
                    pendiente.delete(0, inicio + APERTURA.length());
                    dentroDeThinking = true;
                    continue;
                }
                // Se retiene solo un posible comienzo de etiqueta al final
                int retener = prefijoParcial(pendiente, APERTURA);
                emitir(salida, pendiente.substring(0, pendiente.length() - retener));
                pendiente.delete(0, pendiente.length() - retener);
                return salida.toString();
            } else {
                int fin = pendiente.indexOf(CIERRE);
                if (fin >= 0) {
                    pendiente.delete(0, fin + CIERRE.length());
                    dentroDeThinking = false;
                    saltarEspacios = true;
                    continue;
                }
                // El razonamiento se descarta; solo se guarda un posible cierre parcial
                int retener = prefijoParcial(pendiente, CIERRE);
                pendiente.delete(0, pendiente.length() - retener);
                return salida.toString();
            }
        }
    }

    /**
     * Llamar al terminar la respuesta: devuelve lo que quedaba retenido.
     * Si un bloque de razonamiento no llegó a cerrarse, se descarta.
     */
    public String finalizar() {
        StringBuilder salida = new StringBuilder();
        if (!dentroDeThinking) {
            emitir(salida, pendiente.toString());
        }
        pendiente.setLength(0);
        return salida.toString();
    }

    private void emitir(StringBuilder salida, String texto) {
        if (saltarEspacios) {
            texto = texto.stripLeading();
            if (texto.isEmpty()) {
                return;
            }
            saltarEspacios = false;
        }
        salida.append(texto);
    }

    /**
     * Longitud del sufijo más largo de {@code texto} que es prefijo propio de
     * {@code etiqueta} (p. ej. "...hola <thi" con "<thinking>" devuelve 4).
     */
    static int prefijoParcial(CharSequence texto, String etiqueta) {
        int max = Math.min(texto.length(), etiqueta.length() - 1);
        for (int n = max; n > 0; n--) {
            boolean coincide = true;
            for (int i = 0; i < n; i++) {
                if (texto.charAt(texto.length() - n + i) != etiqueta.charAt(i)) {
                    coincide = false;
                    break;
                }
            }
            if (coincide) {
                return n;
            }
        }
        return 0;
    }
}
