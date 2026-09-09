FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml /workspace/pom.xml
COPY ops-contract/pom.xml /workspace/ops-contract/pom.xml
COPY aiops-server/pom.xml /workspace/aiops-server/pom.xml
COPY ops-executor/pom.xml /workspace/ops-executor/pom.xml
COPY demo-service/pom.xml /workspace/demo-service/pom.xml
RUN mvn -pl ops-executor -am dependency:go-offline
COPY ops-contract/src /workspace/ops-contract/src
COPY ops-executor/src /workspace/ops-executor/src
RUN mvn -pl ops-executor -am -DskipTests package

FROM eclipse-temurin:17-jre
RUN useradd --system --uid 10001 aiops
WORKDIR /app
COPY --from=build /workspace/ops-executor/target/ops-executor-*.jar app.jar
RUN mkdir -p /data && chown -R aiops /data
USER aiops
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
