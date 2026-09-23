FROM public.ecr.aws/docker/library/eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY backend/target/ai-knowledge-backend-0.0.1-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70.0", "-jar", "/app/app.jar"]
