# Minimal runtime image for the Yano node app-chain cluster scaffold.
# Build the integrated uber-jar first: ./gradlew :distribution:jvm:quarkusBuild
# Then (from the repo root):
#   docker build -t yano-node:local -f scaffolds/docker-compose-cluster/Dockerfile .
FROM eclipse-temurin:25-jre

WORKDIR /app
COPY distribution/jvm/build/yano.jar /app/yano-node.jar

# Config comes from YANO_* environment variables (see docker-compose.yml).
ENTRYPOINT ["java", "-jar", "/app/yano-node.jar"]
