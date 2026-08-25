# 隔离 MQTT TLS 测试环境

该目录只用于软件模拟，不是生产证书或生产 Broker 验收环境。

```powershell
.\New-TestCertificates.ps1
docker compose up -d --wait
$env:MQTT_TEST_BROKER_ENABLED='true'
$env:MQTT_TEST_TRUST_STORE=(Resolve-Path .\certs\truststore.p12)
$env:MQTT_TEST_TRUST_STORE_PASSWORD='changeit-test-only'
..\..\..\mvnw.cmd -Dtest=MqttTlsBrokerIntegrationTest test
```

证书脚本优先使用本地 OpenSSL；未安装时自动调用 Docker 中的
`alpine/openssl:latest`，首次运行需要拉取该测试镜像。

测试证书有效期 30 天且只包含 `localhost` 与 `127.0.0.1`。生成目录被 Git 忽略；不得复制到生产环境。测试完成后运行 `docker compose down`。
