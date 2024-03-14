FROM ghcr.io/graalvm/native-image-community:21-muslib

WORKDIR /app

ENV JAR_FILE="build/libs/proxy-server-0.0.1-all.jar"
ENV METADATA_FILES="src/main/resources/META-INF/native-image"

RUN mkdir -p conf
COPY ${JAR_FILE} /app
COPY ${METADATA_FILES}/* conf/

RUN native-image -H:ConfigurationFileDirectories=conf --no-fallback --static --libc=musl -jar proxy-server-0.0.1-all.jar -o ProxyServer
RUN chmod +x ProxyServer

EXPOSE 8882
ENTRYPOINT ./ProxyServer -p 8882