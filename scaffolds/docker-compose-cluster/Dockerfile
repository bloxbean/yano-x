# Minimal runtime image for the Yano node app-chain cluster scaffold.
# Build the integrated uber-jar first: ./gradlew :distribution:jvm:quarkusBuild
# Then (from the repo root):
#   docker build -t yano-node:local -f scaffolds/docker-compose-cluster/Dockerfile .
# Amazon Corretto 25 on Amazon Linux 2023, pinned by digest: 25.0.5.1 carries the CVE-2026-114938 (Java4Shell) fix,
# which Temurin 25 has no build for yet. Override with --build-arg BASE_IMAGE=... to use another Java 25 runtime.
ARG BASE_IMAGE=amazoncorretto:25.0.5-al2023-headless@sha256:d43cc2b87faf3e80d04659d315dd9f1dba2c1176cc9b48d9daed285856ac056c
FROM ${BASE_IMAGE}

WORKDIR /app
COPY distribution/jvm/build/yano.jar /app/yano-node.jar

# Config comes from YANO_* environment variables (see docker-compose.yml).
ENTRYPOINT ["java", "-jar", "/app/yano-node.jar"]
