# Builds the Java modules once and makes two images from it:
#   docker build -f infra/docker/java.Dockerfile --target ledger -t seatline-ledger .
#   docker build -f infra/docker/java.Dockerfile --target api    -t seatline-api .

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY ledger ledger
COPY backend backend
# the load test isn't part of the images, but Maven wants every module's pom to exist
COPY loadtest/pom.xml loadtest/pom.xml
RUN mvn -B -q -DskipTests -pl ledger,backend package \
 && mvn -B -q -pl ledger dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/lib

FROM eclipse-temurin:21-jre AS ledger
RUN useradd --system --uid 10001 seatline && mkdir /data && chown seatline /data
WORKDIR /app
COPY --from=build /src/ledger/target/lib lib
COPY --from=build /src/ledger/target/ledger-*.jar ledger.jar
USER seatline
VOLUME /data
ENV SEATLINE_DATA=/data SEATLINE_BIND=0.0.0.0
# client port, peer port (from SEATLINE_CLUSTER) and metrics
EXPOSE 8000 7000 9100
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-cp", "/app/ledger.jar:/app/lib/*", "dev.seatline.ledger.server.LedgerMain"]

FROM eclipse-temurin:21-jre AS api
RUN useradd --system --uid 10001 seatline
WORKDIR /app
COPY --from=build /src/backend/target/backend-*.jar api.jar
USER seatline
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/api.jar"]
