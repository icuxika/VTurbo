.\gradlew.bat :proxy-client:clean
.\gradlew.bat :proxy-client:shadowJar

if (!(Test-Path -Path .\build\client)) {
    New-Item -ItemType Directory -Path .\build\client
}

Remove-Item -Recurse -Force .\build\client\*
Copy-Item -Recurse .\proxy-client\src\main\resources\META-INF\native-image\ .\build\client\conf
Copy-Item .\proxy-client\build\libs\proxy-client-0.0.1-all.jar .\build\client

docker run --rm -it -v ${PWD}\build\client:/app --entrypoint /bin/bash ghcr.io/graalvm/native-image-community:21-muslib -c "native-image -H:ConfigurationFileDirectories=conf --no-fallback --static --libc=musl -jar proxy-client-0.0.1-all.jar -o ProxyClient"