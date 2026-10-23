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

echo "[deploy-tests] Iniciando las pruebas de PostgreSQL y aceptación antes de arrancar el servidor."

if java -jar "$1" execute \
  --disable-banner \
  --details=verbose \
  --class-path "/app/app.jar:/app/test-classes" \
  --select-class com.torneosflash.dao.RenderDeploymentDatabaseCheck \
  --select-class com.torneosflash.dao.RenderAcceptanceCheck; then
  echo "[deploy-tests] Todas las pruebas de arranque pasaron."
else
  resultado=$?
  echo "[deploy-tests] ERROR: falló la suite de arranque (código $resultado). Busca arriba el nombre de la prueba fallida y su excepción/motivo; el servidor no iniciará." >&2
  exit "$resultado"
fi
