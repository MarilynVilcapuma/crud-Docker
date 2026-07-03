# ─────────────────────────────────────────────────────────────
# ETAPA 1: COMPILACIÓN
# ─────────────────────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-17-alpine AS builder

WORKDIR /build

COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src ./src

RUN mvn clean package -DskipTests -q

# ─────────────────────────────────────────────────────────────
# ETAPA 2: GENERAR JRE MÍNIMO
# ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jdk-alpine AS jre-builder

RUN jlink \
    --add-modules java.base,java.desktop,java.instrument,java.sql,java.naming,java.logging,java.management,java.xml,java.security.jgss,jdk.crypto.ec,jdk.unsupported \
    --no-header-files \
    --no-man-pages \
    --compress=2 \
    --strip-debug \
    --output /jre-minimal

# MEDICIÓN TEMPORAL
RUN du -sh /jre-minimal

RUN mkdir -p /staging/lib && \
    cp /lib/ld-musl-*.so.1 /staging/lib/

RUN mkdir -p /staging/tmp && chmod 1777 /staging/tmp

# ─────────────────────────────────────────────────────────────
# ETAPA 3: SCRATCH
# ─────────────────────────────────────────────────────────────
FROM scratch

COPY --from=jre-builder /jre-minimal /opt/java
COPY --from=jre-builder /staging/lib /lib
COPY --chown=1000:1000 --from=jre-builder /staging/tmp /tmp

COPY --from=builder /build/target/*.jar /app.jar

ENV SERVER_PORT=8091

EXPOSE 8091

USER 1000:1000

ENTRYPOINT ["/opt/java/bin/java","-jar","/app.jar"]