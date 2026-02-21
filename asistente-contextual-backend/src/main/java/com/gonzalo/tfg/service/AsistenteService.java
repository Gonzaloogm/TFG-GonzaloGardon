package com.gonzalo.tfg.service;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.SessionScoped;

/**
 Servicio de IA principal del Asistente Contextual.

 Este servicio actúa como orquestador principal siguiendo la arquitectura
 definida en el documento de diseño (Nivel 3 - Componentes del Backend).

 Características:
 - SessionScoped: Mantiene memoria conversacional por sesión WebSocket
 - RegisterAiService: Integración automática con LangChain4j
 - System Message: Define el comportamiento base del asistente para evitar alucinaciones

 Flujo RAG (se activará en Fase 3):
 1. Vectorización de la consulta del usuario
 2. Recuperación de fragmentos relevantes de pgvector
 3. Aumento del prompt con el contexto recuperado
 4. Inferencia con el LLM (Gemini)
 */
@RegisterAiService
public interface AsistenteService
{
    
    /**
     Método principal de chat que procesa las consultas del usuario.

     IMPORTANTE: El System Message define el comportamiento del asistente
     para minimizar alucinaciones y asegurar respuestas fundamentadas.

     @param userMessage Consulta del usuario
     @return Respuesta del asistente fundamentada en el contexto (cuando RAG esté activo)
     */
    @SystemMessage("""
        Eres un asistente contextual experto diseñado para la gestión de conocimiento empresarial.
        
        INSTRUCCIONES CRÍTICAS:
        - Solo responde basándote en el contexto proporcionado
        - Si no encuentras información relevante en el contexto, indícalo claramente
        - Cita las fuentes cuando sea posible
        - Sé preciso y profesional
        - No inventes información (evita alucinaciones)
        
        Tu objetivo es ayudar a los empleados a acceder eficientemente al conocimiento interno
        de la organización.
        """)
    @UserMessage("{{userMessage}}")
    String chat(String userMessage);
}
