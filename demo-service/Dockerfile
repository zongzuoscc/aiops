FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml /workspace/pom.xml
COPY ops-contract/pom.xml /workspace/ops-contract/pom.xml
COPY aiops-server/pom.xml /workspace/aiops-server/pom.xml
COPY ops-executor/pom.xml /workspace/ops-executor/pom.xml
COPY demo-service/pom.xml /workspace/demo-service/pom.xml
RUN mvn -pl demo-service -am dependency:go-offline
COPY demo-service/src /workspace/demo-service/src
RUN mvn -pl demo-service -am -DskipTests package

FROM eclipse-temurin:17-jre
RUN useradd --system --uid 10002 demo
WORKDIR /app
COPY --from=build /workspace/demo-service/target/demo-service-*.jar app.jar
USER demo
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/app.jar"]
