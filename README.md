# VTurbo

## 项目结构

```text
.
├─app 测试一些工具
├─commons 共用代码
├─proxy-client 客户端
└─proxy-server 服务端
```

## 服务端

### 运行

```shell
.\gradlew.bat :proxy-server:run --args="-p 8882"
```

### jpackage 构建

```shell
.\gradlew.bat :proxy-server:jpackageImage
```

## 客户端

### 运行

```shell
.\gradlew.bat :proxy-client:run
```

### jpackage 构建

```shell
.\gradlew.bat :proxy-client:jpackageImage
```
