# AreaTherm backend -- multi-stage build (Java 17, matching pom.xml's
# <java.version>). Build stage compiles and runs the full test suite (the
# golden-file physics tests included) so a broken image can never be built;
# runtime stage ships only the resulting jar on a slim JRE, not the JDK/Maven
# toolchain. See docker-compose.yml for how this pairs with the "docker"
# Spring profile (application-docker.yml) and the mysql service.

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Separate layer for dependency resolution -- only re-fetched when pom.xml
# itself changes, not on every source edit.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline || true

COPY src ./src
RUN mvn -B package

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /build/target/areatherm-backend.jar app.jar

ENV SPRING_PROFILES_ACTIVE=docker
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
