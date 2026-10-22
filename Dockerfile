# Etapa 1: Build Frontend
FROM node:22-alpine AS frontend-build
WORKDIR /app/frontend
COPY frontend/package*.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# Etapa 2: Build Backend
FROM maven:3.9.6-eclipse-temurin-17 AS backend-build
WORKDIR /app
COPY pom.xml .
COPY src ./src
# Compilar y empaquetar
RUN mvn clean verify

# Etapa 3: Runtime con Java y herramientas oficiales de PostgreSQL (pg_dump/pg_restore)
FROM postgres:18-bookworm
RUN apt-get update \
    && apt-get install -y --no-install-recommends openjdk-17-jre-headless \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
# Copiar el JAR generado
COPY --from=backend-build /app/target/TorneosFlash-1.0.jar ./app.jar
# Conserva el runner y la prueba de integración DB; el CMD valida la BD de pruebas antes de iniciar el servidor.
COPY --from=backend-build /app/target/test-classes ./test-classes
COPY --from=backend-build /app/target/test-runtime ./test-runtime
COPY scripts/run-deploy-tests.sh ./run-deploy-tests.sh
RUN chmod 0555 ./run-deploy-tests.sh
# Copiar el frontend compilado (Vite emite a dist/) y colocarlo en public para que Javalin lo sirva
COPY --from=frontend-build /app/frontend/dist ./public
# Copiar admin-db.html (fuente única: public/admin-db.html) a la raíz de trabajo, donde RutasDbAdmin lo busca
COPY public/admin-db.html ./admin-db.html

# Ejecuta el servidor de aplicación sin privilegios; pg_dump y pg_restore usan las credenciales via PGPASSFILE.
USER postgres

# El contenedor ejecuta la aplicación Java; no arranca el servidor PostgreSQL de la imagen base.
ENTRYPOINT []

# Puerto por defecto
EXPOSE 10000

# Ejecutar acotando la memoria máxima de la JVM para instancias de 512 MB en Render
CMD ["/bin/sh", "-c", "/app/run-deploy-tests.sh && exec java -Dfile.encoding=UTF-8 -XX:MaxRAMPercentage=75.0 -jar /app/app.jar"]
