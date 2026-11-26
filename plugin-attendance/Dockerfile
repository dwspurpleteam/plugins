FROM eclipse-temurin:25-jre

RUN apt-get update && apt-get install -y --no-install-recommends wget && rm -rf /var/lib/apt/lists/*

LABEL org.opencontainers.image.source="https://github.com/dwspurpleteam/plugins"

WORKDIR /app

COPY backend/build/libs/plugin-attendance.jar application.jar

RUN mkdir -p /app/data/attendance && chown -R 1000:1000 /app/data

ENV ATTENDANCE_REPORTS_DIRECTORY=/app/data/attendance

USER 1000:1000

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "/app/application.jar"]
