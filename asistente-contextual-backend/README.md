# 🧠 Asistente Contextual para Gestión de Conocimiento Interno (Backend)

![Quarkus](https://img.shields.io/badge/Quarkus-3.17.0-blue?logo=quarkus)
![Java](https://img.shields.io/badge/Java-21-orange?logo=java)
![LangChain4j](https://img.shields.io/badge/LangChain4j-1.0.2-green)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-%2B%20pgvector-336791?logo=postgresql)

Este repositorio contiene el código fuente del backend para el Trabajo de Fin de Grado (TFG) en Ingeniería Informática por la Universidad Pontificia de Salamanca.

El proyecto implementa un Asistente Contextual Inteligente capaz de razonar sobre documentos privados mediante la arquitectura **Retrieval-Augmented Generation (RAG)** y ejecutar acciones en el sistema a través del **Model Context Protocol (MCP)**.

## 🏗️ Arquitectura y Stack Tecnológico

El sistema ha sido diseñado bajo un enfoque reactivo y orientado a microservicios utilizando el siguiente stack:

* **Framework Backend:** [Quarkus](https://quarkus.io/) ("Supersonic Subatomic Java") para un arranque ultra-rápido y bajo consumo de memoria.
* **Orquestador de IA:** [LangChain4j](https://github.com/langchain4j/langchain4j) integrado de forma nativa con el ecosistema CDI de Quarkus.
* **Modelos de Lenguaje (LLM):** * *Chat / Razonamiento:* Google Gemini (`gemini-2.0-flash`) vía API.
    * *Embeddings:* Modelo local/In-Process `bge-small-en-q` (Dimensión: 384) para garantizar la privacidad en la vectorización de documentos.
* **Base de Datos Vectorial:** PostgreSQL con la extensión `pgvector` para el almacenamiento de embeddings y búsqueda de similitud.
* **Procesamiento Documental:** Apache Tika / Docling para la extracción de texto estructurado desde PDF, DOCX y TXT.
* **Comunicación:** WebSockets (Quarkus WebSockets Next) para streaming de respuestas en tiempo real.

## ✨ Características Principales

- **Ingestión de Documentos:** Pipeline automatizado que recibe documentos, extrae su contenido, lo divide en fragmentos semánticos (chunks de 300 tokens con overlap de 30) y los vectoriza.
- **Búsqueda Semántica:** Recuperación de contexto de alta precisión utilizando pgvector y el índice de búsqueda aproximada HNSW.
- **Memoria Conversacional:** Aislamiento de sesiones mediante `@MemoryId`, permitiendo al asistente recordar el hilo de la conversación por cada usuario conectado.
- **Agencia (MCP):** Capacidad de ejecutar herramientas (Tools) del sistema de forma dinámica y segura, pasando de ser un chat pasivo a un agente activo.

## 🚀 Requisitos Previos

Para ejecutar este proyecto en tu entorno local, necesitarás:

1.  **Java 21** o superior.
2.  **Maven** (o usar el wrapper incluido `./mvnw`).
3.  **Docker y Docker Compose** (para levantar la base de datos PostgreSQL con pgvector).
4.  Una API Key válida de **Google Gemini** (Google AI Studio).

## 🛠️ Instalación y Ejecución en Modo Desarrollo

1. **Clonar el repositorio:**
   ```bash
   git clone [https://github.com/tu-usuario/asistente-contextual-backend.git](https://github.com/tu-usuario/asistente-contextual-backend.git)
   cd asistente-contextual-backend
