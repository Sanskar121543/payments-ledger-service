# Stage 1: build the jar with the Maven wrapper, so `docker compose up --build` needs no local JDK.
FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN sh mvnw -B -q dependency:go-offline
COPY src/ src/
RUN sh mvnw -B -q package -DskipTests && cp target/payments-ledger-service-*.jar app.jar

# Stage 2: a slim JRE image that runs as a non-root user.
FROM eclipse-temurin:21-jre-noble
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
