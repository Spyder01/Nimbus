# Runtime image only. The jar (with the UI embedded) is built by CI / `./gradlew bootJar` beforehand.
FROM eclipse-temurin:25-jre

RUN groupadd --system nimbus && useradd --system --gid nimbus --no-create-home nimbus
WORKDIR /app
COPY --chown=nimbus:nimbus build/libs/app.jar app.jar

USER nimbus
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
