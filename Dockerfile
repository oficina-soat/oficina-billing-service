FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace

ARG MAVEN_PROFILE=postgresql

COPY . .

RUN chmod +x mvnw && \
    ./mvnw -B -DskipTests package -P"${MAVEN_PROFILE}"

FROM eclipse-temurin:25-jre

WORKDIR /work

COPY --from=build /workspace/target/quarkus-app/ /work/quarkus-app/

EXPOSE 8080

ENTRYPOINT ["java", "-Dquarkus.http.host=0.0.0.0", "-jar", "/work/quarkus-app/quarkus-run.jar"]
