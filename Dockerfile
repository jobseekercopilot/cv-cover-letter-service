FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -B --no-transfer-progress clean verify

FROM eclipse-temurin:17-jre-alpine

# The runtime base currently ships OpenSSL 3.5.7-r0. Refresh only the runtime
# packages to Alpine's fixed CVE-2026-14456 build.
RUN apk add --no-cache --upgrade \
    libcrypto3=3.5.8-r0 \
    libssl3=3.5.8-r0 \
    openssl=3.5.8-r0

WORKDIR /app
COPY --from=build /app/target/cv-cover-letter-service-1.0.0.jar app.jar
RUN apk add --no-cache curl \
    && addgroup -S cvservice \
    && adduser -S -G cvservice cvservice \
    && mkdir -p /var/lib/cv-cover-letter/rejected-generations \
    && chown -R cvservice:cvservice /app /var/lib/cv-cover-letter

USER cvservice

EXPOSE 8091
ENTRYPOINT ["java", "-jar", "app.jar"]
