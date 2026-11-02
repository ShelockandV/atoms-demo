FROM node:22-alpine AS frontend
WORKDIR /frontend
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /build
COPY backend/pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY backend/src ./src
COPY --from=frontend /frontend/dist ./src/main/resources/static
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre-jammy
RUN groupadd --gid 10001 atoms && useradd --uid 10001 --gid atoms --no-create-home atoms
WORKDIR /app
COPY --from=backend --chown=atoms:atoms /build/target/atoms-backend-0.1.0.jar app.jar
USER atoms
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
