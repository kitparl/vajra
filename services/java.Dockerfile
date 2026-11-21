# Shared image for the Spring Boot services. Build from the repo root:
#   docker build -f services/java.Dockerfile --build-arg SERVICE=brahma .
# SERVICE is the folder under services/ (Sanskrit); the jar inside is named by its role artifactId (core-service).
# glibc build image: the protoc binary fetched by protobuf-maven-plugin does not run on Alpine (musl).
FROM maven:3.10.0-eclipse-temurin-25-noble AS build
ARG SERVICE
WORKDIR /src
COPY contracts/proto contracts/proto
COPY contracts/java/pom.xml contracts/java/pom.xml
COPY services/pom.xml services/pom.xml
COPY services/common services/common
# The Maven reactor needs every module listed in services/pom.xml, so all are copied.
COPY services/brahma services/brahma
COPY services/maryada services/maryada
COPY services/dvara services/dvara
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -f services/pom.xml -pl ${SERVICE} -am package -DskipTests \
 && cp services/${SERVICE}/target/*.jar /app.jar

FROM eclipse-temurin:25.0.5.1_2-jre-alpine-3.23
RUN addgroup -S app && adduser -S app -G app
USER app
COPY --from=build /app.jar /app/app.jar
# Sized for a 384 MB container limit (0014); re-check with scripts/check-memory.sh.
# Heap ~192 MB + metaspace 96 + code cache 48 + direct 32 + stacks/internals leaves headroom below the limit,
# so a heap OOM is a Java error, not a kernel kill (exit 137). C1-only saves memory; revisit for Phase 2 hot paths.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50 -XX:MaxDirectMemorySize=32m -XX:MaxMetaspaceSize=96m -XX:ReservedCodeCacheSize=48m -Xss512k -XX:+UseSerialGC -XX:TieredStopAtLevel=1"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
