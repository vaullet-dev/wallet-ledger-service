# Kept for environments that require a Dockerfile. If yours does not, prefer
# `./mvnw spring-boot:build-image`, which builds an OCI image with Cloud Native
# Buildpacks — no base image to patch, no Dockerfile to review, and a CVE fix is a
# rebuild rather than an edit.

# ---------- build ----------
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

# Dependencies resolve in their own layer, so a source-only change does not re-download
# the world. This ordering is the single biggest win in Java image build times.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -q clean package -DskipTests

# Split the fat jar into layers that change at different rates.
RUN java -Djarmode=tools -jar target/*.jar extract --layers --launcher --destination extracted

# ---------- runtime ----------
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Never run as root. A container escape should not start with UID 0.
RUN addgroup -S app && adduser -S -G app app

# Ordered least- to most-frequently changed; only the last layer is rebuilt on a
# typical code change.
COPY --from=build --chown=app:app /workspace/extracted/dependencies/ ./
COPY --from=build --chown=app:app /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /workspace/extracted/application/ ./

USER app
EXPOSE 8080

# MaxRAMPercentage, not -Xmx: the JVM then sizes the heap from the container's cgroup
# limit, so changing the pod's memory request does not require changing this file.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"
ENV SPRING_PROFILES_ACTIVE=prod

# The orchestrator should use /actuator/health/readiness and /actuator/health/liveness.
# This HEALTHCHECK is a fallback for plain `docker run`.
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "-jar", "spring-boot-loader.jar"]
