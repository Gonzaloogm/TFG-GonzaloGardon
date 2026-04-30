# Asistente Contextual Corporativo (TFG)

![Quarkus](https://img.shields.io/badge/Quarkus-3.17.0-blue?logo=quarkus)
![Java](https://img.shields.io/badge/Java-21-orange?logo=java)
![LangChain4j](https://img.shields.io/badge/LangChain4j-1.0.2-green)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-%2B%20pgvector-336791?logo=postgresql)
![JavaScript](https://img.shields.io/badge/Frontend-Vanilla_JS-f7df1e?logo=javascript)

Este repositorio contiene el código fuente completo (Backend y Frontend) para el Trabajo de Fin de Grado (TFG) en Ingeniería Informática por la Universidad Pontificia de Salamanca.

El proyecto implementa un **Asistente Contextual Inteligente de grado empresarial** capaz de razonar sobre documentos privados corporativos mediante la arquitectura **RAG (Retrieval-Augmented Generation)**, interactuar con el sistema operativo a través de herramientas nativas e interfaces inspiradas en **MCP (Model Context Protocol)**, y mantener un estado persistente de la información.

---

## Caso de Uso y Viabilidad de Mercado

Este proyecto nace como respuesta a un problema crítico en el mercado B2B: la ineficiencia en la gestión del conocimiento interno y la revisión de contratos en empresas *mid-market*. 

Frente a soluciones tradicionales de *Intelligent Document Processing* (IDP) que requieren costosos procesos de entrenamiento (*Fine-Tuning*) o extracción de entidades rígidas (NER), este Asistente Contextual propone un enfoque radicalmente ágil:
* **Cero tiempos de entrenamiento:** Al usar RAG, un documento (ej. un contrato de proveedores o un manual de normativas) es consultable en el mismo segundo en que se sube al sistema.
* **Privacidad garantizada:** La vectorización de documentos críticos se realiza localmente (*In-Process*) mediante modelos ONNX, asegurando que la información sensible nunca abandone la infraestructura de la empresa.
* **Explicabilidad (XAI):** Para combatir las alucinaciones inherentes a los LLMs, el sistema inyecta metadatos dinámicos, forzando a la IA a proporcionar referencias cruzadas y citar visualmente sus "Fuentes consultadas".

---

## Arquitectura y Stack Tecnológico

El sistema ha sido diseñado bajo un enfoque reactivo, *local-first* para embeddings y orientado a la eficiencia transaccional:

* **Backend:** [Quarkus](https://quarkus.io/) ("Supersonic Subatomic Java") para un arranque ultra-rápido y bajo consumo de memoria.
* **Orquestador de IA:** [LangChain4j](https://github.com/langchain4j/langchain4j) integrado de forma nativa con el ecosistema CDI de Quarkus.
* **Modelos de Inteligencia Artificial:** * *Razonamiento (LLM):* Google Gemini (`gemini-2.0-flash`) vía API.
  * *Embeddings:* Modelo local cuantizado `bge-small-en-q` (ONNX, Dimensión: 384) para garantizar la privacidad en la generación de vectores topológicos.
* **Base de Datos Vectorial:** PostgreSQL con la extensión `pgvector` para el almacenamiento de embeddings y búsqueda de similitud semántica.
* **Capa de Presentación (Frontend):** *Single Page Application* (SPA) con Vanilla JS, servida estáticamente desde Quarkus. Diseño inspirado en IDEs modernos con soporte para renderizado Markdown (`marked.js`).
* **Comunicación:** WebSockets (Quarkus WebSockets Next) para interacciones reactivas bidireccionales en tiempo real.

---

## Características Técnicas Destacadas

1. **RAG Avanzado con Filtrado Estricto:** El *pipeline* de ingestión calibra el motor de recuperación con parámetros matemáticos precisos (`maxResults = 5`, `minScore = 0.70`) para eliminar el ruido semántico antes de inyectar el contexto al LLM.
2. **Razonamiento "Chain of Thought" Invisible:** Se obliga a la red neuronal a estructurar su razonamiento paso a paso utilizando etiquetas `<thinking>`. El backend intercepta el flujo mediante expresiones regulares (Regex) y depura estos pensamientos internos, mostrando al usuario únicamente la respuesta final limpia y directa.
3. **Agencia y Herramientas (Tools):** Más allá de un chat pasivo, el LLM posee "agencia". Utilizando la anotación `@Tool`, puede interrumpir su generación de texto para ejecutar métodos Java nativos (ej. inspeccionar el disco duro, listar el catálogo real de documentos en RAM) operando bajo estrictas barreras de seguridad.
4. **Persistencia de Estado Total ("Triple Borrado"):** Implementación de sincronización bidireccional entre la memoria RAM, un catálogo persistente en disco (`documents_catalog.json` gestionado vía Jackson `ObjectMapper`) y la base de datos PostgreSQL. Esto asegura consistencia frente a reinicios del servidor.
5. **Aislamiento de Sesiones Reactivas:** La segregación de sesiones se gestiona extrayendo el identificador de red del socket (`connection.id()`), inyectándolo en la memoria conversacional de LangChain4j para evitar la exfiltración de contextos entre usuarios concurrentes.

---

## Guía de Instalación

Para preparar tu entorno local y descargar el proyecto, sigue estos pasos:

### 1. Requisitos Previos
Asegúrate de tener instalado en tu sistema:
* **Java 21** o superior (JDK).
* **Docker y Docker Compose** (necesario para levantar PostgreSQL + pgvector).
* Una **API Key de Google Gemini** válida.

### 2. Clonar el repositorio
Descarga el código fuente en tu máquina local:
```bash
git clone [https://github.com/tu-usuario/asistente-contextual-backend.git](https://github.com/Gonzaloogm/asistente-contextual-backend.git)
cd asistente-contextual-backend
```

### 3. Configurar variables de entorno
El sistema necesita tu clave de Gemini para funcionar. Expórtala en tu terminal para no subirla al código fuente de forma accidental:

**En Linux / macOS:**
```bash
export QUARKUS_LANGCHAIN4J_AI_GEMINI_CHAT_MODEL_API_KEY="tu_clave_api_aqui"
```

**En Windows (PowerShell):**
```powershell
$env:QUARKUS_LANGCHAIN4J_AI_GEMINI_CHAT_MODEL_API_KEY="tu_clave_api_aqui"
```

### 4. Levantar la Base de Datos
Inicia el contenedor de PostgreSQL con la extensión pgvector configurada en el proyecto:
```
bash
docker run -d \
  --name pgvector-postgres \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=postgres \
  -p 5432:5432 \
  pgvector/pgvector:pg16
```
*(Nota: Asegúrate de que las credenciales y el puerto coincidan con tu configuración de `application.properties`. Si prefieres usar los Dev Services integrados de Quarkus, este paso se realiza automáticamente al iniciar la aplicación).*

---

## Guía de Ejecución

Una vez instalado, tienes dos formas principales de ejecutar el proyecto dependiendo de tu necesidad:

### Opción A: Ejecución en Modo Desarrollo (Live Coding)
Ideal para programar y hacer pruebas. Quarkus recargará los cambios en el código en tiempo real sin necesidad de reiniciar el servidor.

**En Linux / macOS:**
```bash
./mvnw quarkus:dev
```

**En Windows:**
```cmd
mvnw.cmd quarkus:dev
```

### Opción B: Ejecución en Modo Producción (Compilado)
Si deseas desplegar la aplicación o probar su rendimiento real empaquetada como un archivo `.jar` ejecutable:

1. **Compilar el proyecto:**
```bash
./mvnw clean package -DskipTests
```
*(En Windows usa `mvnw.cmd clean package -DskipTests`)*

2. **Ejecutar:**
```bash
java -jar target/quarkus-app/quarkus-run.jar
```

###  Acceso a la Interfaz Web
Independientemente del modo de ejecución que elijas, una vez que el servidor haya arrancado, abre tu navegador web y entra en:

**http://localhost:8080**

Desde ahí podrás acceder al portal corporativo, realizar el *Mock Login* y empezar a interactuar en tiempo real con la base de conocimiento del sistema.
