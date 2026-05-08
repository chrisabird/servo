FROM eclipse-temurin:21-jre

# System libs for f3d headless rendering (EGL + OSMesa software fallback)
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
      curl \
      libgl1 \
      libegl1 \
      libosmesa6 \
      libglu1-mesa \
      libgomp1 \
    && rm -rf /var/lib/apt/lists/*

# Install f3d from GitHub releases (pre-built binary bundles VTK)
ARG F3D_VERSION=3.2.0
RUN curl -fsSL \
      "https://github.com/f3d-app/f3d/releases/download/v${F3D_VERSION}/f3d-${F3D_VERSION}-Linux-x86_64.tar.gz" \
      -o /tmp/f3d.tar.gz && \
    mkdir /tmp/f3d && \
    tar -xzf /tmp/f3d.tar.gz -C /tmp/f3d --strip-components=1 && \
    cp /tmp/f3d/bin/f3d /usr/local/bin/f3d && \
    rm -rf /tmp/f3d /tmp/f3d.tar.gz

WORKDIR /app
COPY target/servo.jar .
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "servo.jar"]
