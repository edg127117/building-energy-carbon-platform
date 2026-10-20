# 数据服务与运行管理访问

> 当前实现与部署操作边界。服务器配置、凭据轮换和远端 Broker 变更必须在核对具体差异并获批准后执行。

## 应用边界

- 业务 API 保持原有端口和账号、角色、建筑范围规则。`/api/actuator/**` 不提供管理信息。
- 管理 HTTP 默认 `127.0.0.1:9091`，可用 `MANAGEMENT_SERVER_PORT` 改为其他独立端口；禁止与业务端口合用或绑定非回环地址。
- 管理路径为 `/actuator`，没有业务 `/api` 前缀。健康接口只返回状态，不返回组件、数据库地址或异常详情。
- 指标同时要求 S01 有效会话和 MySQL 当前 `OPERATIONS_OBSERVER` 职责。业务管理员不会自动获得该职责。
- V76 只增加职责目录，不给任何人授权。授权、撤销沿用 `GRANT_BACKEND_DUTY` / `REVOKE_BACKEND_DUTY` 的申请、审核、执行链；生产保持既有双人分离规则。
- 运行管理的职责判定使用主库独立 READ COMMITTED 事务，不继承调用方旧快照。默认生效、创建、撤销使用数据库时间；显式时间按数据库会话墙上时间解释，不使用 JVM 默认时区换算。到期或已提交撤销对下一次权限判定生效，不取消已获准的在途请求。
- 指标授权依赖故障返回脱敏 503；缺少身份为 401，缺职责为 403。线程转储、堆转储、环境、配置和其他端点默认关闭，专用安全链也不授权它们。

## 凭据与模板

应用在创建依赖客户端和运行迁移前拒绝缺失、公开示例或不足 16 字符的密码，MySQL/TDengine 运行账号不得为 root。实际值只通过受保护的服务器配置注入，不能提交仓库。`security.dependency-credentials-required=false` 仅供明确隔离的自动化夹具，不能用于部署。

MySQL 运行凭据与 Flyway 迁移凭据分开：`MYSQL_USER/PASSWORD` 和 `MYSQL_MIGRATION_USER/PASSWORD`。迁移必须覆盖 `iot_platform` 以及既有 V58 使用的 `iot_platform_archive`；现有归档、触发器和 Flyway 历史不得删除。运行账号只授予已核实的业务库 DML 权限。首次使用官方 MySQL 镜像时，其初始化账号默认得到业务库全部权限，不能当作已完成权限收敛；应在管理员核验后按上述职责拆分授权。已有卷中的账号不会因环境变量变化而自动改密或收权。 迁移也可在受控独立进程先执行，确认结构后设置 MYSQL_FLYWAY_ENABLED=false 并从运行进程移除迁移凭据；之后每次升级都必须先验证并执行迁移，不能只关闭迁移来绕过结构要求。

Redis 模板关闭匿名 default 用户，使用 `REDIS_USER/PASSWORD` 和 [权限清单](../../src/env/redis-app.permissions)。仅允许 `iot:*` 与实际缓存、健康检查、Lua 比较删除所需命令；不允许全库清理、配置、ACL 管理或其他项目 Key。健康检查和应用使用同一认证配置。预留的 Redisson 支持用户名，但当前最小 ACL 只覆盖默认 Lettuce 路径，开启 Redisson 前须另核验命令和频道权限。模板清单适用于固定的 Redis 7.2 镜像；目标已有 Redis 6.0 不支持 `CLIENT SETINFO`，不能原样套用，需先生成版本适配差异再确认。持久化目录和既有数据卷保持不变。

TDengine 运行账号限定本项目数据库的读取、写入及业务实际需要的子表操作。必须先用管理员完成结构初始化与验证，再配置非 root 运行账号并设置 `TDENGINE_INITIALIZATION_ENABLED=false`。后续结构升级继续单独审核，不能因关闭启动初始化而跳过。固定 3.2.3.0 的权限能力须用该版本验证，不套用新版企业版的授权选项；已有 root 密码也须单独核查、轮换，改应用环境变量不会修改 root。

Compose 数据服务使用专用桥接网络并关闭容器间直连，与 Broker 接入网络分开。应用在宿主机通过回环发布端口连接，不使用会禁用该发布路径的 internal:true。Compose 只将 MySQL、Redis、TDengine REST 和 Broker 管理台发布到回环地址；不再发布 TDengine 整段原生端口。设备 MQTT/TCP、WebSocket 默认也为回环，跨主机接入须单独确认绑定地址及来源规则。现有稳定 TDengine hostname、MySQL/Redis/TDengine 数据卷不变。Broker 新增固定数据卷以保留认证库，不能用改名或重建的方式替代迁移。

Broker 管理密码使用独立 `EMQX_DASHBOARD_PASSWORD`；MQTT 客户端密码使用 `MQTT_PASSWORD`。模板启用内置认证库，初始无用户时拒绝客户端连接。通过受限管理台/API 创建非超级用户 `platform-backend`、`platform-device`，分别配置独立密码；[Topic 白名单](../../src/env/emqx-acl.conf)仅覆盖当前默认遥测和 ACK。自定义 Topic、适配器独立身份及远端 Broker 必须另行核对，禁止通过末尾全放行恢复连接。当前 ACK 使用共享 Topic，模板不承诺逐设备隔离；需要该能力时须先确认 ACK Topic 契约，不能把账号认证等同于逐设备数据权限。

## 目标测试环境待确认变更

以下是待审批提案，仓库修改及自动化测试不会自动应用它们：

1. 保持本项目 `18080 → 127.0.0.1:18081` 和原站点隔离，管理端口拟使用 `127.0.0.1:18082`。先核验端口空闲。
2. 只在本项目 Nginx server 块增加以下拒绝路由，其他代理和 TLS 入口原样保留：

   ```nginx
   location = /api/actuator { return 404; }
   location ^~ /api/actuator/ { return 404; }
   ```

3. 只给本项目 systemd 单元追加受保护的凭据文件和 `MANAGEMENT_SERVER_PORT=18082`，不改其他服务。凭据文件覆盖 TDengine 专用账号、独立迁移账号、Redis 认证及经批准替换的 MQTT 密码；不输出旧值、新值或哈希。密码文件仅由 systemd 读取，权限不宽于 0600。
4. MySQL 先创建/核验迁移账号的双库迁移权限，再核实运行账号的业务库 SELECT/INSERT/UPDATE/DELETE；在隔离副本验证完整迁移及业务读写后，才撤掉已确认不需要的旧授权。保留管理员恢复通道，禁止先撤权再补账号。
5. TDengine 先验证既有结构、专用账号权限、动态子表与保留清理操作，再切换运行凭据和初始化开关。Redis 先验证版本兼容 ACL 与 S01，再切换应用用户名；不能照搬新容器启动脚本替换现有服务。
6. 远端 Broker 先补认证链、Topic 授权、管理监听与网络来源的只读证据；核对设备/适配器依赖后，给出账号轮换及配置差异单独批准。尚不执行远端登录、改密或重启。
7. 补查主机生效的防火墙、IPv4/IPv6 和上级网关/NAT。现有证据只覆盖已核验客户端，不能据此声称全部网络来源均受限。
8. 确认变更后再备份、分步应用并独立验收，范围仅本项目测试目录和单元。服务器端口 80、其他站点、其他服务及生产部署不在范围。回退发布不能恢复示例弱密码或绕过新的认证边界。

授权运维通过 SSH 本地转发到管理端口，再使用自己的有效 Token 读取指标；不把 Token 写入命令历史、URL或共享脚本。

## 验证入口

- `ManagementAccessIntegrationTest`：真实 HTTP 双端口，四种业务角色默认拒绝、授权/撤销、到期、依赖故障、旧白名单、转发头及禁止端点。
- `BackendDutyTimeTest` / `BackendDutyMysqlIntegrationTest`：JVM 上海与数据库 UTC，不改写到期时间，验证默认授权、未来生效、到期、撤销与旧事务快照。
- `RedisAccessIntegrationTest`：一次性 Redis 上认证、密码错误、Key/命令限制及 S01 Lua 比较删除。
- `S02_INFRA_IT_ISOLATED=true python3 scripts/test_s02_infrastructure.py`：只新建随机标签的容器，验证 Compose 绑定及缺失凭据拒绝、实际 Redis 启动脚本与降权、EMQX 5.6 认证/Topic 权限和 TDengine 3.2.3.0 专用账号。结束按标签核对身份后清理自建容器，不使用现存部署服务。
- `AccountSecurityConcurrencyTest`、`AuthStateFailureFlowTest`、`AccountSessionCommitFailureTest` 和显式隔离的 `AccountSecurityMysqlRedisIntegrationTest`：S01 并发、事务失败、撤权、强杀重启、多 JVM 补偿及异时区恢复回归。
- 自动化通过只证明相应用例。防火墙、远端 Broker、目标专用账号切换和真实网络来源须在变更批准后独立验证，不能提前宣称 S02 部署验收完成。
