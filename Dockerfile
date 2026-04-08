# syntax=docker/dockerfile:1.6
# Single-pass image: clone graphhopper-maps (branch), npm pack, then Maven -Pprebuilt-maps.
# Build:
#   docker build -t ghcr.io/flowride/graphhopper:tomtom_trafic .
# Optional: --build-arg GRAPHHOPPER_MAPS_REPO=... GRAPHHOPPER_MAPS_BRANCH=tomtom_trafic
# Local stack: docker compose -f docker-compose.yml build

FROM node:24-bookworm AS maps-bundle
ARG GRAPHHOPPER_MAPS_REPO=https://github.com/flowride/graphhopper-maps.git
ARG GRAPHHOPPER_MAPS_BRANCH=tomtom_trafic
RUN apt-get update && apt-get install -y --no-install-recommends git ca-certificates \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /maps
RUN git clone --depth 1 --branch "${GRAPHHOPPER_MAPS_BRANCH}" "${GRAPHHOPPER_MAPS_REPO}" .
RUN node -e "\
const fs=require('fs');\
const p=require('./package.json');\
p.version='0.0.0-local';\
p.files=['dist/'];\
p.name='@graphhopper/graphhopper-maps-bundle';\
fs.writeFileSync('package.json', JSON.stringify(p,null,4));" \
 && npm ci \
 && npm run build \
 && node -e "\
const fs=require('fs');\
const p=require('./package.json');\
p.scripts={}; p.dependencies={}; p.devDependencies={};\
fs.writeFileSync('package.json', JSON.stringify(p,null,4));" \
 && npm pack

FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /src

# Keep dependency download cached
COPY pom.xml ./
COPY core/pom.xml core/pom.xml
COPY web-api/pom.xml web-api/pom.xml
COPY reader-gtfs/pom.xml reader-gtfs/pom.xml
COPY map-matching/pom.xml map-matching/pom.xml
COPY client-hc/pom.xml client-hc/pom.xml
COPY tools/pom.xml tools/pom.xml
COPY navigation/pom.xml navigation/pom.xml
COPY web-bundle/pom.xml web-bundle/pom.xml
COPY web/pom.xml web/pom.xml
COPY example/pom.xml example/pom.xml

RUN mvn -q -DskipTests -pl web -am dependency:go-offline

COPY . .
RUN mkdir -p /src/graphhopper-maps-bundle
COPY --from=maps-bundle /maps/graphhopper-graphhopper-maps-bundle-0.0.0-local.tgz /src/graphhopper-maps-bundle/
RUN mvn -q -DskipTests -pl web -am -Pprebuilt-maps package

FROM eclipse-temurin:17-jre

WORKDIR /opt/graphhopper

ENV JAVA_OPTS=""
ENV DW_GRAPHOPPER_DATAREADER_FILE=""
# Override at deploy time (PBF path and graph folder depend on the region).
ENV DW_GRAPHOPPER_GRAPH_LOCATION="/data/graph-cache"

COPY --from=build /src/web/target/graphhopper-web-*.jar /opt/graphhopper/graphhopper-web.jar
COPY --from=build /src/custom_models_fr_ch /opt/graphhopper/custom_models_fr_ch

EXPOSE 8989 8990

ENTRYPOINT ["bash","-lc","exec java $JAVA_OPTS -Ddw.graphhopper.datareader.file=$DW_GRAPHOPPER_DATAREADER_FILE -Ddw.graphhopper.graph.location=$DW_GRAPHOPPER_GRAPH_LOCATION -jar /opt/graphhopper/graphhopper-web.jar server /run/configs/graphhopper.yml"]
