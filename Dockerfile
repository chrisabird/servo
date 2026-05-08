FROM eclipse-temurin:21-jre

RUN apt-get update && \
    apt-get install -y --no-install-recommends f3d && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY target/servo.jar .

VOLUME ["/app/data/db", "/app/stl"]

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "servo.jar"]
