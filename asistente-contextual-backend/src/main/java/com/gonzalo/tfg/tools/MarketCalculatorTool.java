package com.gonzalo.tfg.tools;

import dev.langchain4j.agent.tool.Tool;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;

/**
 * Calculadora financiera y de mercado para el rol de Investigador Senior.
 *
 * El LLM tiene limitaciones con cálculos numéricos precisos (alucinaciones
 * matemáticas). Esta tool delega la aritmética al código Java garantizando
 * resultados exactos y reproducibles, especialmente para análisis de mercado,
 * tasas de crecimiento y comparativas que el sistema RAG puede necesitar.
 */
@ApplicationScoped
public class MarketCalculatorTool {

    @Tool("Calcula la Tasa de Crecimiento Anual Compuesta (CAGR). REGLA ESTRICTA: Pasa los argumentos 'valorInicial' y 'valorFinal' como números puros, sin separadores de miles, sin símbolos de moneda y usando el punto como decimal (ej. 1500000.50).")
    public String calcularCAGR(double valorInicial, double valorFinal, int anios) {
        Log.infof("Calculando CAGR: %.2f → %.2f en %d años", valorInicial, valorFinal, anios);

        if (valorInicial <= 0)
            return "Error: el valor inicial debe ser positivo.";
        if (valorFinal <= 0)
            return "Error: el valor final debe ser positivo.";
        if (anios <= 0)
            return "Error: el número de años debe ser mayor que cero.";

        double cagr = (Math.pow(valorFinal / valorInicial, 1.0 / anios) - 1) * 100;

        return String.format(
                "CAGR calculado:\n" +
                        "- Valor inicial: %.2f\n" +
                        "- Valor final:   %.2f\n" +
                        "- Período:       %d años\n" +
                        "- CAGR:          %.2f%%\n\n" +
                        "Interpretación: el mercado %s a una tasa del %.2f%% anual acumulado.",
                valorInicial, valorFinal, anios, cagr,
                cagr >= 0 ? "creció" : "decreció",
                Math.abs(cagr));
    }

    @Tool("Calcula la cuota de mercado de una empresa o producto dado su valor y el valor total del mercado. Devuelve el porcentaje y una interpretación del posicionamiento competitivo.")
    public String calcularCuotaMercado(String nombreEntidad, double valorEntidad, double valorTotalMercado) {
        Log.infof("Calculando cuota de mercado para: %s", nombreEntidad);

        if (valorTotalMercado <= 0)
            return "Error: el valor total del mercado debe ser positivo.";
        if (valorEntidad < 0)
            return "Error: el valor de la entidad no puede ser negativo.";
        if (valorEntidad > valorTotalMercado)
            return "Error: el valor de la entidad supera el total del mercado.";

        double cuota = (valorEntidad / valorTotalMercado) * 100;

        String posicionamiento;
        if (cuota >= 40)
            posicionamiento = "líder dominante del mercado";
        else if (cuota >= 20)
            posicionamiento = "actor relevante con posición consolidada";
        else if (cuota >= 10)
            posicionamiento = "competidor significativo";
        else if (cuota >= 5)
            posicionamiento = "jugador nicho con presencia establecida";
        else
            posicionamiento = "actor minoritario o emergente";

        return String.format(
                "Cuota de mercado — %s:\n" +
                        "- Valor de la entidad:    %.2f\n" +
                        "- Tamaño total del mercado: %.2f\n" +
                        "- Cuota de mercado:       %.2f%%\n" +
                        "- Posicionamiento:        %s",
                nombreEntidad, valorEntidad, valorTotalMercado, cuota, posicionamiento);
    }

    @Tool("Proyecta el valor futuro de un mercado aplicando una tasa de crecimiento anual (CAGR) durante un número de años. Útil para estimar el tamaño de un mercado en un año objetivo.")
    public String proyectarMercado(double valorActual, double tasaCrecimientoAnual, int anios) {
        Log.infof("Proyectando mercado: %.2f al %.2f%% durante %d años", valorActual, tasaCrecimientoAnual, anios);

        if (valorActual <= 0)
            return "Error: el valor actual debe ser positivo.";
        if (anios <= 0)
            return "Error: el número de años debe ser mayor que cero.";
        if (anios > 30)
            return "Error: las proyecciones a más de 30 años tienen escasa fiabilidad.";

        double tasa = tasaCrecimientoAnual / 100.0;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Proyección de mercado al %.2f%% anual:\n\n", tasaCrecimientoAnual));
        sb.append(String.format("%-10s %15s %15s\n", "Año", "Valor", "Crecimiento"));
        sb.append("-".repeat(42)).append("\n");

        double valorPrevio = valorActual;
        for (int i = 1; i <= anios; i++) {
            double valorProyectado = valorActual * Math.pow(1 + tasa, i);
            double crecimientoAbsoluto = valorProyectado - valorPrevio;
            sb.append(String.format("%-10s %,15.2f %+,15.2f\n", "+" + i, valorProyectado, crecimientoAbsoluto));
            valorPrevio = valorProyectado;
        }

        double valorFinal = valorActual * Math.pow(1 + tasa, anios);
        sb.append(String.format("\nValor final proyectado: %.2f (×%.2f el valor actual)",
                valorFinal, valorFinal / valorActual));

        return sb.toString();
    }

    @Tool("Calcula la variación porcentual entre dos valores (útil para comparar resultados entre períodos, versiones de un producto o métricas de rendimiento).")
    public String calcularVariacionPorcentual(String etiqueta, double valorAnterior, double valorActual) {
        if (valorAnterior == 0)
            return "Error: el valor anterior no puede ser cero (división por cero).";

        double variacion = ((valorActual - valorAnterior) / Math.abs(valorAnterior)) * 100;

        return String.format(
                "Variación porcentual — %s:\n" +
                        "- Valor anterior: %.2f\n" +
                        "- Valor actual:   %.2f\n" +
                        "- Variación:      %+.2f%%\n" +
                        "- Tendencia:      %s",
                etiqueta, valorAnterior, valorActual, variacion,
                variacion > 0 ? "▲ Crecimiento" : variacion < 0 ? "▼ Descenso" : "→ Sin cambio");
    }

    @Tool("Compara múltiples cuotas de mercado. Pasa los 'nombresCSV' separados por comas. REGLA ESTRICTA para 'valoresCSV' y 'totalMercado': Usa números puros absolutos (sin 'M', 'K', '%' ni símbolos de moneda) separados por comas, usando punto para decimales (ej: 1500.5, 3000, 450.2).")
    public String compararCuotasMercado(String nombresCSV, String valoresCSV, double totalMercado) {
        Log.infof("Comparando cuotas de mercado para: %s", nombresCSV);

        if (totalMercado <= 0)
            return "Error: el total del mercado debe ser positivo.";

        String[] nombres = nombresCSV.split(",\\s*");
        String[] valoresStr = valoresCSV.split(",\\s*");

        if (nombres.length != valoresStr.length) {
            return "Error: el número de nombres y valores no coincide.";
        }

        List<double[]> datos = new ArrayList<>();
        try {
            for (int i = 0; i < nombres.length; i++) {
                datos.add(new double[] { i, Double.parseDouble(valoresStr[i].trim()) });
            }
        } catch (NumberFormatException e) {
            return "Error: alguno de los valores no es un número válido.";
        }

        datos.sort((a, b) -> Double.compare(b[1], a[1]));

        double sumaTotal = datos.stream().mapToDouble(d -> d[1]).sum();
        double otrosMercado = totalMercado - sumaTotal;

        if (sumaTotal > totalMercado) {
            return String.format(
                    "Error analítico: La suma de los valores de las entidades (%.2f) es mayor que el mercado total indicado (%.2f). Revisa los datos extraídos del contexto.",
                    sumaTotal, totalMercado);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Análisis de cuotas de mercado (total: %.2f):\n\n", totalMercado));
        sb.append(String.format("%-5s %-25s %12s %10s\n", "Pos.", "Empresa/Producto", "Valor", "Cuota"));
        sb.append("-".repeat(55)).append("\n");

        for (int pos = 0; pos < datos.size(); pos++) {
            int idx = (int) datos.get(pos)[0];
            double val = datos.get(pos)[1];
            double cuota = (val / totalMercado) * 100;
            sb.append(String.format("%-5d %-25s %12.2f %9.2f%%\n",
                    pos + 1, nombres[idx].trim(), val, cuota));
        }

        if (otrosMercado > 0.01) {
            sb.append(String.format("%-5s %-25s %12.2f %9.2f%%\n",
                    "-", "Otros", otrosMercado, (otrosMercado / totalMercado) * 100));
        }

        sb.append("-".repeat(55)).append("\n");
        sb.append(String.format("Líder de mercado: %s con %.2f%% de cuota.",
                nombres[(int) datos.get(0)[0]].trim(),
                (datos.get(0)[1] / totalMercado) * 100));

        return sb.toString();
    }
}