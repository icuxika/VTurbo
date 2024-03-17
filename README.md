# VTurbo

## 项目结构

```text
.
├─app 测试一些工具
├─commons 共用代码
├─proxy-client 客户端
└─proxy-server 服务端
```

## GraalVM

[Gradle Plugin for GraalVM Native Image](https://graalvm.github.io/native-build-tools/latest/gradle-plugin.html)
插件本身支持`.\gradlew.bat -Pagent :proxy-client:run`的时候生成配置文件到`${buildDir}/native/agent-output/${taskName}`
目录下，测试的时候在终端结束`.\gradlew.bat -Pagent run`
可能意外终端一些任务，导致最终配置文件，相关文档[Build a Native Executable by Detecting Resources with the Agent](https://graalvm.github.io/native-build-tools/latest/gradle-plugin-quickstart.html#build-a-native-executable-detecting-resources-with-the-agent)

下面先通过构建出一个可执行jar包，然后以手动执行`$JAVA_HOME/bin/java -agentlib:native-image-agent=config-output-dir=/path/to/config-dir/`
的方式来生成配置文件

服务端
----------

## 运行

```shell
.\gradlew.bat :proxy-server:run --args="-p 8882"
```

## jpackage 构建

```shell
.\gradlew.bat :proxy-server:jpackageImage
```

## GraalVM

> 替换`implementation(libs.bundles.log4j)`为`implementation(libs.bundles.logback)`

### 生成 GraalVM 所需要的配置文件

> 不需要每次构建都执行

```shell
.\gradlew.bat :proxy-server:shadowJar
java -agentlib:native-image-agent=config-merge-dir=proxy-server/src/main/resources/META-INF/native-image -jar .\proxy-server\build\libs\proxy-server-0.0.1-all.jar
```

### 构建

```shell
.\gradlew.bat :proxy-server:nativeBuild
```

### docker 中构建 Linux 版本（静态链接 musl 作为 C 标准库实现）

#### 拉取镜像

 ```shell
docker pull ghcr.io/graalvm/native-image-community:21-muslib
```

#### 构建

```shell
.\buildServerOnWindows.ps1
```

构建完成得到的`ProxyServer`上传到Linux后，需要`chmod +x ProxyServer`

### Windows 通过 Dockerfile 直接运行

```shell
cd .\proxy-server\
docker build . -t icuxika/vturbo-proxy-server:0.0.1
docker run --rm --init -p 8883:8882 icuxika/vturbo-proxy-server:0.0.1
```

客户端
----------

## 运行

```shell
.\gradlew.bat :proxy-client:run
```

## jpackage 构建

```shell
.\gradlew.bat :proxy-client:jpackageImage
```

## GraalVM

> 替换`implementation(libs.bundles.log4j)`为`implementation(libs.bundles.logback)`

### 生成GraalVM所需要的配置文件

> 不需要每次构建都执行

```shell
.\gradlew.bat :proxy-client:shadowJar
java -agentlib:native-image-agent=config-merge-dir=proxy-client/src/main/resources/META-INF/native-image -jar .\proxy-client\build\libs\proxy-client-0.0.1-all.jar
```

### 构建

```shell
.\gradlew.bat :proxy-client:nativeBuild
```

### docker 中构建 Linux 版本（静态链接 musl 作为 C 标准库实现）

#### 拉取镜像

 ```shell
docker pull ghcr.io/graalvm/native-image-community:21-muslib
```

#### 构建

```shell
.\buildClientOnWindows.ps1
```

构建完成得到的`ProxyClient`上传到Linux后，需要`chmod +x ProxyClient`