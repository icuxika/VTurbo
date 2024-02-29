# Windows下使用openssl生成RSA私钥和公钥

```shell
openssl genrsa -out rsa_private_key.pem 2048
openssl rsa -in rsa_private_key.pem -pubout -out rsa_public_key.pem
```

> 不同版本的Windows提供的openssl工具使用`openssl genrsa`在不指定参数的情况下生成的如果是`PKCS#1`
> 格式的私钥文件，当前的代码进行RSA加密会产生问题。`PKCS#1格式的私钥文件以-----BEGIN RSA PRIVATE KEY-----开头，以-----END RSA PRIVATE KEY-----结尾`；`PKCS#8格式的私钥文件以-----BEGIN PRIVATE KEY-----开头，以-----END PRIVATE KEY-----结尾`。

> rsa加密对数据长度有要求，暂时将每次`InputStream.read`的buffer大小设置128字节防止超出长度限制

> RSA密钥长度为1024位时，加密的数据块大小最多为117字节；当密钥长度为2048位时，加密的数据块大小最多为245字节