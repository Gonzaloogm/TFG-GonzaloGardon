# Imagen de la aplicación para docker compose.
# Compila desde el código fuente (no hace falta Java ni Maven en el equipo).
# Los Dockerfile de src/main/docker son los que genera Quarkus y esperan un
# "mvn package" previo; este es independiente y usa Dockerfile.dockerignore.

# --- Compilación ---------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Primero solo el pom: las dependencias quedan en la caché de Maven entre builds
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests

# --- Ejecución -----------------------------------------------------------------
FROM eclipse-temurin:21-jre

RUN groupadd --system app && useradd --system --gid app --home-dir /app app \
    && mkdir -p /app/data \
    && chown app:app /app/data

COPY --from=build --chown=app:app /build/target/quarkus-app /app/quarkus-app
COPY --chmod=755 scripts/generar-claves-jwt.sh scripts/docker-entrypoint.sh /app/scripts/

# Directorio de trabajo = volumen de datos: catálogo de documentos, informes,
# sandbox de herramientas y claves JWT sobreviven a recrear el contenedor.
WORKDIR /app/data
USER app

ENV JWT_PRIVATE_KEY_LOCATION=/app/data/keys/privateKey.pem \
    JWT_PUBLIC_KEY_LOCATION=/app/data/keys/publicKey.pem \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"

EXPOSE 8080
ENTRYPOINT ["/app/scripts/docker-entrypoint.sh"]
