# Production image for the Goaway backend (deployed to Google Cloud Run; see docs/deployment.md).
#   Build:  docker build -t goaway-backend .
#   Run:    docker run --rm -p 8080:8080 --env-file .env goaway-backend   (local check only)
# Tests are not run here: CI runs the full test suite before anything is deployed.

# ---- Build: compile the Spring Boot jar and split it into cache-friendly layers ----
FROM eclipse-temurin:17-jdk-noble AS build
WORKDIR /workspace

# Dependencies first, so they're cached until pom.xml changes.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw --batch-mode --no-transfer-progress dependency:go-offline

COPY src src
RUN ./mvnw --batch-mode --no-transfer-progress -DskipTests package \
 && mkdir extracted \
 && java -Djarmode=tools -jar target/*.jar extract --layers --launcher --destination extracted

# ---- Runtime: JRE only, unprivileged user, no build tools or sources ----
FROM eclipse-temurin:17-jre-noble
WORKDIR /app

RUN groupadd --system goaway && useradd --system --gid goaway --no-create-home goaway

# Least-changing layers first, so a code change only replaces the small application layer.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

USER goaway

# Cloud Run sends traffic to $PORT (8080 by default); server.port reads it.
EXPOSE 8080

# Size the heap from the container's memory limit; the backend runs in UTC (see BackendApplication).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
