#!/usr/bin/env bash
# Genera el par de claves RSA con el que el backend firma y verifica los JWT.
#
# Uso (desde asistente-contextual-backend/):
#   ./scripts/generar-claves-jwt.sh            # crea keys/privateKey.pem y keys/publicKey.pem
#   ./scripts/generar-claves-jwt.sh otra/ruta  # en otro directorio
#
# La carpeta keys/ está en .gitignore: las claves nunca deben subirse al repositorio
# ni colocarse en src/main/resources/META-INF/resources (se servirían por HTTP).
set -euo pipefail

DIR="${1:-keys}"
mkdir -p "$DIR"

if [[ -f "$DIR/privateKey.pem" ]]; then
  echo "Ya existe $DIR/privateKey.pem; no se sobrescribe. Bórrala a mano si quieres regenerarla." >&2
  exit 1
fi

umask 077
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$DIR/privateKey.pem"
openssl rsa -pubout -in "$DIR/privateKey.pem" -out "$DIR/publicKey.pem" 2>/dev/null
chmod 600 "$DIR/privateKey.pem"
chmod 644 "$DIR/publicKey.pem"

echo "Claves generadas en $DIR/ (privateKey.pem con permisos 600)."
echo "En producción, apunta a ellas con JWT_PRIVATE_KEY_LOCATION y JWT_PUBLIC_KEY_LOCATION."
