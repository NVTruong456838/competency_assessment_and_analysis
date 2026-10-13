FROM eclipse-temurin:21-jre
WORKDIR /app
COPY backend/target/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
