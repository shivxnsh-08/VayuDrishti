FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q package -DskipTests && cp target/saanscheck-backend-*.jar /src/app.jar

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && useradd --create-home appuser
WORKDIR /app
COPY --from=build /src/app.jar app.jar
USER appuser
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --retries=10 CMD curl -fs http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
