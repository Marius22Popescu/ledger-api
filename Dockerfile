FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY . .
RUN chmod +x mvnw && ./mvnw -B -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/target/ledger-api-0.0.1-SNAPSHOT.jar /app/ledger-api.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/ledger-api.jar"]
