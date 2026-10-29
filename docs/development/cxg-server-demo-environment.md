# 独立月度演示测试实例

本实例承载创新港电力月度测试数据，使用同一后端业务代码，数据库与局域网共享测试环境分别运行。它不作为生产环境，不接入真实设备。当前先提供后端接口，大屏 UI 和浏览器联调另行进行。

## 部署边界

| 项目 | 共享测试环境 | 独立月度测试实例 |
| --- | --- | --- |
| 局域网入口 | 18080 | 19080 |
| 根目录 | `/home/user1/deployments/building-energy-carbon-test` | `/home/user1/deployments/building-energy-carbon-demo` |
| 后端入口 | 宿主机回环 18081 | 容器内部 18081，无宿主机映射 |
| 数据存储 | 现有实例 | 独立 MySQL、TDengine、Redis 容器和持久化卷 |
| 发布入口 | `Deploy-BuildingEnergyCarbonTest.sh` | 本文限定目录的 Docker Compose |

`80`、`/var/www/iot-platform/current` 及共享实例的配置、服务、网络和卷不属于本入口。两个实例共用服务器硬件；容器资源配额降低相互争用，不能提供物理故障隔离。

[Compose 模板](../../src/env/cxg-demo/compose.yml)固定项目、容器、网络、卷和 19080 端口。`application` 与 `data` 网络仅供内部通信；仅 Nginx 加入入口网络。后端使用非 root 用户，数据库不发布宿主机端口，镜像由发布目录的 `.env` 固定为已核验的 digest 或 image ID。

Java 使用 `spring.config.additional-location` 保留包内默认配置，再覆盖为本实例的数据源与关闭项；包含逗号的配置位置必须作为同一个命令参数。TDengine 镜像的 `/corefile` 也使用专属命名卷，避免自动生成归属不明确的匿名卷。

## 目录及私密配置

- `releases/<发布名>/`：固定 JAR、Compose、Nginx、受控基础配置、镜像摘要及 `MANIFEST.json`。
- `current`：仅指向本实例中已完成验收的发布目录。
- `private/`：独立应用配置、数据库凭据、JWT 密钥及测试账号；仅实例运维用户可读，不进入 Git 或普通交接包。
- `backups/migration-<日期>/`：迁移前 MySQL 逻辑备份、TDengine 原生备份、源数据摘要和恢复证据。
- `source-artifacts/`：原始资料及已验证测试数据包，保留来源标识。
- `incoming/`：已校验的传输包。

Compose 中的私密文件使用相对于发布目录的 `../../private/`。不得把本模板放在共享目录运行，也不得从共享 `server.env` 继承数据源、设备或控制凭据。后台采集、MQTT、厂商客户端、控制、天气、自动补全、后台重算和清理继续关闭；导入、审核、封账与月度查询采用现有业务接口。

## 运维入口

以下命令仅在服务器执行。操作前确认 `current` 指向本实例的已验收发布；不要在其他目录替换执行对象。

```bash
cd /home/user1/deployments/building-energy-carbon-demo/current
docker compose --env-file .env -f compose.yml -p building-energy-carbon-demo ps
docker compose --env-file .env -f compose.yml -p building-energy-carbon-demo logs --tail 100 app
```

停止、启动及重启不删除数据卷：

```bash
docker compose --env-file .env -f compose.yml -p building-energy-carbon-demo stop
docker compose --env-file .env -f compose.yml -p building-energy-carbon-demo up -d --pull never
docker compose --env-file .env -f compose.yml -p building-energy-carbon-demo restart
```

重启后 Nginx 须解析当前应用容器地址；若后端容器被重新创建，应同时重新创建或重启 Nginx。不要使用 `down -v`、全局 prune 或共享部署脚本维护此实例。后续升级先备份、核对结构和数据兼容性，再独立发布，不能仅替换 JAR 并认定原验收仍有效。

## 数据保留及验收

Windows MySQL 通过逻辑备份迁移到 Linux 新实例；新卷首次初始化前配置 `lower_case_table_names=1`，保留字符集、业务 ID、审核、版本和封账快照。只恢复业务库，不复制系统账号库。运行账号仅授予业务库读写权限，数据库结构更新使用独立维护身份。TDengine 两端保持兼容版本并使用原生导出恢复；Redis 从空缓存启动，重新登录，应用使用独立 JWT 密钥。

数据集是 229 个点位、2025-09 至 2026-09 共 13 个月；月度当前结果和冻结快照各 2,977 条，TDengine 对应 5,954 条。原始日度表底及缺测、异常资料继续保留在源文件中，本次不扩大到日、小时接口或其他能源。

迁移恢复后、首次业务访问前，对两个 MySQL 业务库全部表执行逐列、逐行摘要核对；TDengine 核对表、行、精确十进制结果及证据摘要。随后验收现有月度封账、折标、碳排放及冻结面积强度查询，覆盖本期、上月和去年同期，并在独立实例重启后复验。登录会产生安全及审计记录，因此访问后的可变表不能直接与迁移前全库摘要比较。

环境隔离验收包括镜像和 JAR 摘要、所有网络及卷、唯一宿主机端口、运行配置关闭项、账号令牌跨实例拒绝，以及共享环境路径、进程、容器、数据卷和路由保持。集成验收独立执行；离线配置检查使用 `python -m unittest discover -s scripts/tests -p test_cxg_demo_compose.py -v`，不启动容器。

本机源副本和备份保留；服务器成为后续测试入口后，本机后端不再继续日常写入。需要回退时只停止独立服务器实例，保存其新增数据后再决定恢复源副本；不得覆盖共享库或丢弃已经产生的测试增量。正式生产环境将来使用独立部署和真实配置，不能把本实例数据库直接改名升级为生产库。
