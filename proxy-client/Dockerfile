FROM ghcr.io/graalvm/native-image-community:21-muslib

WORKDIR /app

ENV JAR_FILE="build/libs/proxy-client-0.0.1-all.jar"
ENV METADATA_FILES="src/main/resources/META-INF/native-image"
ENV PROXY_SERVER_ADDRESS="127.0.0.1:8882"

RUN mkdir -p conf
COPY ${JAR_FILE} /app
COPY ${METADATA_FILES}/* conf/

RUN native-image -H:ConfigurationFileDirectories=conf --no-fallback --static --libc=musl -jar proxy-client-0.0.1-all.jar -o ProxyClient
RUN chmod +x ProxyClient

EXPOSE 8881
ENTRYPOINT ./ProxyClient -p 8881 -s ${PROXY_SERVER_ADDRESS}