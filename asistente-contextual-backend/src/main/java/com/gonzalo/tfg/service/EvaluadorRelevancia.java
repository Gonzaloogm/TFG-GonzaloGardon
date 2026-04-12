package com.gonzalo.tfg.service;

import io.quarkiverse.langchain4j.RegisterAiService;
import dev.langchain4j.service.UserMessage;

@RegisterAiService
public interface EvaluadorRelevancia {

    @UserMessage("""
        Actúa como un juez crítico de sistemas RAG (Generación Aumentada por Recuperación).
        Tu tarea es evaluar la calidad de la respuesta de un asistente virtual basándote únicamente en la pregunta del usuario y el contexto recuperado.

        CRITERIOS DE EVALUACIÓN:
        1. Fidelidad (Faithfulness): ¿Contradice la respuesta la información del contexto?
        2. Relevancia de respuesta: ¿Responde directamente a la pregunta planteada?
        3. Precisión de citas: ¿Menciona las fuentes presentes en el contexto?

        PUNTUACIÓN (Escala del 1 al 5):
        (1) Irrelevante, totalmente alucinada o plagada de errores.
        (2) Contiene información útil pero ignora el contexto o cita mal.
        (3) Respuesta aceptable, pero poco profunda o con omisiones menores.
        (4) Buena respuesta, fiel al contexto y bien estructurada.
        (5) Excelente, precisa, concisa y con todas las fuentes correctamente identificadas.

        DATOS A EVALUAR:
        - Pregunta: {pregunta}
        - Respuesta del Bot: {respuesta}
        - Contexto recuperado: {contexto}

        DEVOLUCIÓN: Responde ÚNICAMENTE con el número de la puntuación (ej: 4). No añadidas explicaciones.
        """)
    int evaluar(String pregunta, String respuesta, String contexto);
}
