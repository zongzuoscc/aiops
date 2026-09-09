FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /workspace

COPY pom.xml /workspace/pom.xml
COPY ops-contract/pom.xml /workspace/ops-contract/pom.xml
COPY aiops-server/pom.xml /workspace/aiops-server/pom.xml
COPY ops-executor/pom.xml /workspace/ops-executor/pom.xml
COPY demo-service/pom.xml /workspace/demo-service/pom.xml
RUN mvn -pl aiops-server -am dependency:go-offline

COPY ops-contract/src /workspace/ops-contract/src
COPY aiops-server/src /workspace/aiops-server/src
RUN mvn -B -pl aiops-server -am -DskipTests package

FROM eclipse-temurin:17-jre

WORKDIR /app
RUN useradd --create-home --shell /bin/bash appuser
USER appuser

COPY --from=builder /workspace/aiops-server/target/aiops-server-*.jar /app/app.jar

EXPOSE 9900
ENV JAVA_TOOL_OPTIONS="-Xms256m -Xmx1024m"

ENTRYPOINT ["sh", "-c", "java $JAVA_TOOL_OPTIONS -jar /app/app.jar"]
