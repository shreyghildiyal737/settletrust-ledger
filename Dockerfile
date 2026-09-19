# Two stages, because the image that runs this should not contain a compiler, a Maven
# cache or the source. Those are build inputs, and shipping them is extra attack surface
# for no benefit.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies resolve in their own layer, so editing a source file does not re-download
# the world. jOOQ generates from the migration scripts rather than from a live database,
# so this build needs no Postgres and no network beyond Maven Central.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine AS runtime

# Runs as nobody in particular. A ledger that is rooted in its own container has given
# away the one boundary the container was there to provide.
#
# The id is fixed at 1000 rather than left to adduser, because the Kubernetes manifest
# sets runAsUser and the two have to name the same user. Letting the image pick and the
# manifest guess is how a pod ends up unable to read its own jar.
RUN addgroup -S -g 1000 ledger && adduser -S -u 1000 -G ledger ledger
USER 1000

WORKDIR /app
COPY --from=build --chown=ledger:ledger /build/target/settletrust-ledger-*.jar app.jar

EXPOSE 8080

# Container memory, not the host's. Without this the JVM sizes its heap against the node
# and the pod is killed by the kernel rather than by anything that will tell you why.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# The "$@" and the trailing -- are not decoration: without them the shell form swallows
# anything passed after the image name, and "docker run ... --server.port=9090" would be
# silently ignored rather than applied.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar \"$@\"", "--"]
