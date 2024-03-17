.\gradlew.bat :proxy-server:clean
.\gradlew.bat :proxy-server:shadowJar

if (!(Test-Path -Path .\build\server)) {
    New-Item -ItemType Directory -Path .\build\server
}

Remove-Item -Recurse -Force .\build\server\*
Copy-Item -Recurse .\proxy-server\src\main\resources\META-INF\native-image\ .\build\server\conf
Copy-Item .\proxy-server\build\libs\proxy-server-0.0.1-all.jar .\build\server

docker run --rm -it -v ${PWD}\build\server:/app --entrypoint /bin/bash ghcr.io/graalvm/native-image-community:21-muslib -c "native-image -H:ConfigurationFileDirectories=conf --no-fallback --static --libc=musl -jar proxy-server-0.0.1-all.jar -o ProxyServer"