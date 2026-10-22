#!/bin/sh
set -eu

if [ -z "${TEST_DATABASE_URL:-}" ]; then
  echo "Falta TEST_DATABASE_URL. El servidor no iniciará sin la base de datos aislada de pruebas." >&2
  exit 1
fi

set -- /app/test-runtime/junit-platform-console-standalone-*.jar
if [ ! -f "$1" ]; then
  echo "No se encontró el launcher de JUnit en la imagen." >&2
  exit 1
fi

java -jar "$1" execute \
  --disable-banner \
  --details=summary \
  --class-path "/app/app.jar:/app/test-classes" \
  --select-class com.torneosflash.dao.RenderDeploymentDatabaseCheck
