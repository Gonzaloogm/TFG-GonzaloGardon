#!/usr/bin/env bash
# Arranque del contenedor: genera las claves JWT la primera vez (se guardan en
# el volumen de datos, no en la imagen) y lanza la aplicación.
set -euo pipefail

DIR_CLAVES="$(dirname "$JWT_PRIVATE_KEY_LOCATION")"

if [[ ! -f "$JWT_PRIVATE_KEY_LOCATION" ]]; then
  # openssl escribe su progreso en stderr: solo se muestra si algo falla
  if ! salida=$(/app/scripts/generar-claves-jwt.sh "$DIR_CLAVES" 2>&1); then
    echo "$salida" >&2
    exit 1
  fi
  echo "Claves JWT generadas en $DIR_CLAVES"
fi

# shellcheck disable=SC2086
exec java $JAVA_OPTS -jar /app/quarkus-app/quarkus-run.jar
