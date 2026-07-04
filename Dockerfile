# Etapa 1: Build
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
# Compilar y empaquetar
RUN mvn clean package -DskipTests

# Etapa 2: Run
FROM eclipse-temurin:17-jre
WORKDIR /app
# Copiar el JAR generado
COPY --from=build /app/target/TorneosFlash-1.0.jar ./app.jar
# Copiar los archivos estáticos del frontend
COPY public ./public
COPY admin-db.html .

# Puerto por defecto (Javalin usará la variable de entorno PORT que Render inyecta, o 10000)
EXPOSE 10000

# Ejecutar
CMD ["java", "-jar", "app.jar"]
