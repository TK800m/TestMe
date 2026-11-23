# Deurbel-server voor Raspberry Pi (arm64) of een andere Linux-computer (x86_64).
FROM eclipse-temurin:17-jre

RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg espeak-ng curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*

# Natuurlijke Nederlandse stem (piper). Lukt dit niet, dan gebruikt de server espeak-ng.
RUN set -e; case "$(uname -m)" in aarch64) P=aarch64 ;; x86_64) P=x86_64 ;; *) P= ;; esac; \
    if [ -n "$P" ]; then \
      (curl -fsSL "https://github.com/rhasspy/piper/releases/download/2023.11.14-2/piper_linux_${P}.tar.gz" | tar xz -C /opt \
       && mkdir -p /opt/voices \
       && curl -fsSL -o /opt/voices/nl.onnx "https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/nl/nl_NL/mls/medium/nl_NL-mls-medium.onnx" \
       && curl -fsSL -o /opt/voices/nl.onnx.json "https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/nl/nl_NL/mls/medium/nl_NL-mls-medium.onnx.json") \
      || (echo "piper niet beschikbaar; espeak-ng wordt gebruikt" && rm -rf /opt/piper /opt/voices); \
    fi

COPY deurbel-server.jar /app/deurbel-server.jar
ENV DATA_DIR=/data
VOLUME /data
CMD ["java", "-Xmx256m", "-jar", "/app/deurbel-server.jar"]
