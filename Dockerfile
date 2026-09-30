# Build any service from the repo root: docker build --build-arg SERVICE=wallet-service .
FROM maven:3.9-eclipse-temurin-25 AS build
ARG SERVICE
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -pl services/${SERVICE} -am -DskipTests package

FROM eclipse-temurin:25-jre
ARG SERVICE
RUN useradd --system --uid 10001 flowpay
USER flowpay
WORKDIR /app
COPY --from=build /workspace/services/${SERVICE}/target/${SERVICE}-*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
