# Etapa 1: Build Frontend
FROM node:22-alpine AS frontend-build
WORKDIR /app/frontend
COPY frontend/package*.json ./
RUN npm install
COPY frontend/ ./
RUN npm run build

# Etapa 2: Build Backend
FROM maven:3.9.6-eclipse-temurin-17 AS backend-build
WORKDIR /app
COPY pom.xml .
COPY src ./src
# Compilar y empaquetar
RUN mvn clean package -DskipTests

# Etapa 3: Run
FROM eclipse-temurin:17-jre
WORKDIR /app
# Copiar el JAR generado
COPY --from=backend-build /app/target/TorneosFlash-1.0.jar ./app.jar
# Copiar el frontend compilado (Vite emite a dist/) y colocarlo en public para que Javalin lo sirva
COPY --from=frontend-build /app/frontend/dist ./public
# Copiar admin-db.html (fuente única: public/admin-db.html) a la raíz de trabajo, donde RutasDbAdmin lo busca
COPY public/admin-db.html ./admin-db.html

# Puerto por defecto
EXPOSE 10000

# Ejecutar
CMD ["java", "-jar", "app.jar"]
