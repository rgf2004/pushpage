FROM maven:3-eclipse-temurin-24-alpine AS build
WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline -B -q
COPY src ./src
RUN mvn package -DskipTests -B -q

FROM eclipse-temurin:25.0.3_9-jre-alpine
WORKDIR /app
RUN mkdir -p /var/www/pages /data
COPY --from=build /build/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
