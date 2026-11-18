# Multi-stage build: the first stage compiles and packages with a JDK and the Maven cache, the
# second keeps only a JRE and the jar, so the image that ships has no build tool, no sources and
# no dependency cache in it.

# ---------- build ----------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# The wrapper, its configuration and the pom are copied before the sources, so the dependency
# layer is only rebuilt when the pom changes. Editing a class does not re-download the world.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# A Windows checkout can leave CRLF in the wrapper, which makes the shebang unreadable to the
# shell inside the image, and git can drop the executable bit. Normalise both before using it.
RUN sed -i 's/\r$//' mvnw \
    && chmod +x mvnw \
    && ./mvnw -B dependency:go-offline

COPY src ./src
RUN ./mvnw -B -DskipTests package

# ---------- runtime ----------
FROM eclipse-temurin:21-jre

# A fixed, unprivileged uid/gid: the process that serves requests never owns the JVM binary or the
# filesystem it runs on, so a remote-code path cannot rewrite the jar it is running from.
RUN groupadd --gid 10001 app \
    && useradd --uid 10001 --gid app --create-home --shell /usr/sbin/nologin app

WORKDIR /app

# target/*.jar is the Spring Boot fat jar; the extra .jar.original produced by the plugin does not
# match the pattern, so exactly one artifact is copied.
COPY --from=build --chown=10001:10001 /workspace/target/*.jar app.jar

USER app
EXPOSE 8080

# Exec form, so the JVM is PID 1 and receives SIGTERM directly: docker stop becomes a graceful
# shutdown instead of a 10-second wait followed by SIGKILL.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
