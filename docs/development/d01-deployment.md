# D01 升级与恢复操作边界

本文对应 V83→V84，只说明 D01 必需的维护流程。隔离演练通过不代表已在运行测试系统执行；实际停写、备份、迁移、切换只在批准的维护窗口运行。测试发布仍固定在 `18080→18081`，不操作 80 端口、其 Nginx 或 `/var/www/iot-platform/current`。

## 1. 前置核验

- 记录实际后端包、前端包、清单和 SHA256，不能只依据 `current` 目录名推断运行的后端版本；前后端可能分别发布。
- `d01_release.py prepare` 在旧系统运行时核验 V83 的 Flyway 历史与校验和，检查候选包包含 V84 和独立迁移入口，准备产物和冻结计划；不停止服务、不改 `current`、不改控制开关。
- 运行环境必须为 TEST，`MYSQL_FLYWAY_ENABLED=false`、`TDENGINE_INITIALIZATION_ENABLED=false`，迁移使用独立账号。配置文件、服务 unit、控制开关和 MQTT clientId 在各阶段保持原值。
- MySQL 主库、`iot_platform_archive` 和 TDengine 都必须有可恢复备份。旧的 `backup-server-compat.sh` 不作为 D01 备份依据；D01 使用 `d01_backup.py`，不在本任务中改动服务器既有定时备份任务。
- 先在新建隔离实例恢复备份、核对存量摘要。`SHA256SUMS` 只证明文件完整，不能代替恢复和业务验收。
- 核实磁盘余量、源数据规模、备份峰值临时空间和新账本增长量。备份工具默认至少需要 10GiB 空闲空间；按实测备份量上调 `--min-free-bytes`，这个默认值不代表已通过容量验收。待处理队列上限不等于全部 D01 表的行数或磁盘上限。
- 清点其他后端实例、直写程序、导入与重算、定时采集。明确所有写入均被隔离；暂停审核和配置提交。确认 Broker 会话、持久化、队列容量及保留时间，以及 HTTP 调用方的重试能力。QoS 1 本身不证明维护期间不丢数据；若无法保证保留，须由用户明确接受维护窗口内 HTTP/MQTT 数据可能缺失且无法补采，记录授权依据，不补造数据、不宣称无损。
- 若要求停写前完成恢复核验，先在线核验现有 V83 完整备份并在隔离实例实际恢复；没有可用备份时不得直接停机。此项只验证恢复能力，不能代替停写后本轮一致性备份及绑定它的恢复报告。恢复工具、镜像及传输入口提前就绪，记录耗时以评估停机预算；权限不通或预计明显超时应报告并保持原服务运行。

## 2. 凭据与发布包

脚本要求 Linux、Python 3.10+、Java 21、Docker CLI 和 systemd，维护入口使用 sudo/root。把 `d01_release.py`、`d01_backup.py`、`daikin_test_deployment_config.py` 放在同一受控目录；维护期间禁止其他发布流程或人工改动配置及 `current`。

发布包根目录包含 `building-energy-carbon-platform.jar`、`web/index.html` 和 `MANIFEST.txt`，清单填写 `commit`、`backendJarSha256`、`webIndexSha256`、`expectedMigration=84`。通用 `Deploy-BuildingEnergyCarbonTest.sh` 拒绝 D01 包，避免绕过冻结及独立迁移。

通过受保护渠道创建 root 所有的普通 0600 JSON 文件（须与 sudo 后的实际执行用户一致），不把密码写进命令行、日志、Git 或清单：

- schema 凭据：`{"url":"jdbc:mysql://127.0.0.1:<端口>/iot_platform?...","user":"<独立迁移账号>","password":"<秘密>"}`。URL 不得携带 user/password 参数。
- backup 凭据：`{"mysql":{"user":"<备份账号>","password":"<秘密>"},"tdengine":{"user":"<备份账号>","password":"<秘密>"}}`。必须使用核实有效的账号，不从容器初始化环境推断当前密码；MySQL 账号需具有双库、routines/events/triggers 所需备份权限。

凭据经独立进程环境或 stdin 使用；迁移不会启动 Spring 应用。独立入口是打包后的 `com.platform.tools.D01SchemaMigration`，由 PropertiesLauncher 加载，支持 `validate83`、`upgrade84`、`validate84`；不提供 baseline、repair、clean 或降级操作。

### 一次维护的临时授权

需要由维护执行者自动完成阶段操作时，使用 `Install-D01TemporaryAuthorization.py` 安装一次性入口。管理员须核对安装器和完整工具包的 SHA256；执行安装器时先读取文件内容、校验这些字节，再执行已校验的内容，不能把“先 sha256sum、再按可写路径执行”当作防替换保证。工具包只包含四个固定 Python 文件和一个固定发布包；包内候选提交、JAR、前端摘要均锁定，不允许后续上传新程序替换它们。

- 安装目录 `/opt/building-energy-d01-maintenance` 及祖先必须由 root 持有且不可被普通用户写入；状态、凭据、锁、备份和恢复报告保存在 `/var/lib/building-energy-d01-maintenance/private`，权限为 0700/0600。
- 精确 sudo 白名单只允许 `/usr/local/sbin/building-energy-d01-maintenance` 的固定动作；不放行 Python、shell、Docker、systemctl、通配参数或 `SETENV`。执行环境清空后重建，Python 使用 `-I`，模块仅从已校验的安装目录导入。
- 维护包发布到用户目录、解包及 `current` 切换由降权后的 `user1` 子进程执行；内部子进程仅接受 root 父进程创建的 socketpair 通道，通过内核对端 UID/PID 验证，绑定阶段和期限，不提供可直接调用的发布/切换动作。特权迁移只执行管理员保护的 JAR 副本，不执行用户可替换的发布目录 JAR。
- 安装时可提供现有 root 保护的凭据 JSON，也可在管理员终端隐藏输入既有数据库密码。不会新建账号或重置密码；安装成功不证明数据库登录有效，prepare 和在线备份实际检查失败时保持业务在线。
- 授权有效期为安装后 24 小时；入口自身检查期限，专用 systemd timer 到期移除本次 sudo 白名单和复制的凭据。结束时必须主动执行 `sudo /usr/local/sbin/building-energy-d01-maintenance revoke`，保留备份和审计证据。撤销不启停业务服务；到期或失败时仍处于停机状态，须报告管理员处理，不能为了恢复访问而续期或放宽权限。
- 失效 timer 只管理本次授权。安装仅新增这组维护文件和 timer，不停止、重启测试服务，也不改应用控制开关。已有同名授权拒绝覆盖；安装失败或中断会停止、禁用本次 timer 并按创建记录清理本轮文件和目录，保留预存文件；清理失败明确报告，由管理员核查对应路径，禁止递归清理测试部署目录。授权规则发布后仍须等待安装完成标志才可执行维护动作。

固定动作顺序：`prepare` → `online-backup` → `export-online` → 隔离实际恢复 → `record-online-restore` → `freeze` → `backup` → `export-backup` → 隔离实际恢复 → `record-restore` → `migrate` → `cutover` → 业务验收 → `revoke`。`status` 只输出阶段、时间和服务状态。导出是备份二进制流，应直接写入受保护文件，不能输出到聊天或普通日志。

`online-backup` 是业务仍在运行时分别导出各引擎的恢复能力检查，不保证跨库同一时刻一致，不能替代停写后的本轮备份。`freeze` 还要求 120 秒内的维护人员核查记录：`checked_at`（UTC 秒）、`no_other_deployment=true`、`external_writers_fenced=true` 和非敏感 `reference`，从 stdin 读取；这是已有维护者的现场确认，不是软件自动证明所有外部写入者不存在。

两类 `record-*-restore` 从 stdin 接收 `{"report": <恢复报告>, "evidence": <实际恢复证据对象>}`。报告采用下文 `D01_RESTORE_VERIFIED_V1` 字段，入口为证据生成固定文件名和哈希，校验具体备份及新实例身份。它是可信维护人员的结果记录，必须在实际恢复与数据核验完成后提交，不能用构造 JSON 代替恢复。任何状态变更动作失败都停止自动推进并保留现场，不提供通用命令、任意 SQL 或自动回退入口。

## 3. 维护阶段

以下是操作模板，不是本次交付已经执行的命令。先填写经核验的包、凭据、容器、卷和备份目录；不使用自动探测结果替代目标确认。

```bash
sudo python3 d01_release.py prepare --release <发布名> \
  --archive <测试目录/incoming/包.tar.gz> --sha256 <包哈希> \
  --schema-credentials <受保护schema凭据.json>
```

记录输出的状态文件路径。确认上游保留/重试及外部停写后，在维护窗口冻结：

```bash
sudo python3 d01_release.py freeze --state <状态.json> --writers-fenced --ingress-retained
sudo python3 d01_backup.py create --freeze-state <状态.json> \
  --output-root <备份目录> --credentials-file <受保护backup凭据.json> \
  --mysql-container <MySQL容器> --mysql-volume <MySQL卷> \
  --tdengine-container <TDengine容器> --tdengine-volume <TDengine卷> \
  --tdengine-database iot_telemetry --min-free-bytes <容量核验后的字节数>
sudo python3 d01_backup.py validate --backup-dir <本轮备份目录>
```

如果已明确批准接受维护数据缺失，将上述 `freeze` 命令替换为下列命令。两种入口策略互斥；未选择策略、缺少授权依据或未确认外部停写均拒绝停止服务：

```bash
sudo python3 d01_release.py freeze --state <状态.json> --writers-fenced \
  --accept-ingress-loss --loss-acceptance-reference '<本次维护授权记录标识>'
```

此路径在冻结状态和备份清单中记录 `ingress_retained=false`、`ingress_loss_accepted=true` 及授权依据，不降低备份、实际恢复、独立迁移或业务审核门禁。授权依据只记录非敏感标识。维护记录须分别记下发出停写命令、确认停写、服务恢复和采集恢复的实际时间；服务恢复不等于缺测已补齐，也不能仅凭停机时长推算实际缺失条数。发现失败或预计明显超出已沟通预算时报告当前阶段和数据状态，不擅自扩大操作范围。

将本轮完整备份复制至独立环境，按已演练流程恢复到两个新容器，核对主库/归档库逐表内容、TDengine 时间/值/质量及源数据未被覆盖；保存实际执行证据。由执行此验证的同一维护人员形成 root 所有的 0600 `restore-report.json`，不是新增角色或业务审批。报告必须绑定本轮 `manifest.json` SHA256、新实例 ID 和执行证据文件哈希，不能用上一轮演练结果替代；未完成真实恢复不得填写通过标志。格式为：

```json
{"format":"D01_RESTORE_VERIFIED_V1","backup_manifest_sha256":"<本轮清单SHA256>","mysql_verified":true,"tdengine_verified":true,"source_preserved":true,"restored_container_ids":["<新MySQL容器ID>","<新TDengine容器ID>"],"evidence_file":"restore-evidence.json","evidence_sha256":"<执行证据SHA256>"}
```

报告是受信维护者的恢复验收记录，程序校验绑定及完整性，不把布尔值本身当作恢复动作。证据文件与报告放在同一受保护目录，迁移和切换都要求提供：

```bash
sudo python3 d01_release.py migrate --state <状态.json> --backup-dir <本轮备份目录> \
  --schema-credentials <受保护schema凭据.json> --restore-report <恢复验证报告.json>
sudo python3 d01_release.py cutover --state <状态.json> --backup-dir <本轮备份目录> \
  --schema-credentials <受保护schema凭据.json> --restore-report <恢复验证报告.json>
```

`freeze` 显式停止测试 Nginx 和后端；后续备份、迁移、切换均检查两者 inactive，备份绑定本轮冻结身份和文件哈希，迁移/切换还校验该份备份的恢复报告。迁移失败保持停写，不能继续切换。`cutover` 再验证 V84，然后记录不可自动回退标记、切换测试软链接、分别启动后端和测试 Nginx并校验两者 active。失败停止候选，不启动旧包；停止失败单独报错。80 入口同时比较原始软链接文本、解析目标、master PID 与 systemd reload 记录。

当前应用没有统一的只读启动模式：启动即可能接入消息、运行恢复、聚合和保留任务，也可能恢复既有控制任务。不能把 HTTP 健康检查之前的时间视为“尚无业务写入”。D01 不擅自改变既有控制开关；维护前须依据既有控制安全流程核实待执行任务和设备下行链路，必要隔离由维护窗口安排。脚本完成状态仅为 `AWAITING_BUSINESS_ACCEPTANCE`，不等于业务验收完成；上游入口恢复由维护人员按核验结果控制。

## 4. 验收和回退

迁移验收：仅 V84 一条新成功记录，Flyway validate 通过，九张新表与五个预算种子存在；既有业务表内容不变，旧角色/职责不变，指定角色获得预期菜单授权；历史原始值不会由升级自动回填或修正。

业务验收在隔离数据或明确授权的验收数据上执行：重复合并、原始证据保留、普通更正与既有能源审核衔接、后到正确值进入历史查询和分钟/能源重算、撤销后恢复正确来源、任务积压/重试/到期处理；未审核的正式结果不得发布。验收数据不得混入原有正式结果。

现场恢复验收：测试页面/API、MQTT 重新订阅和积压排空、停机时间段的数据数量/身份/质量、既有查询与审核、控制配置与安全配置不变；80 端口站点软链接和进程不变。Redis 属于既有缓存：不在此工具自动整库恢复或清空，恢复前须另核验缓存与双库一致性及登录会话影响。

- 冻结前失败：旧系统继续运行，`prepare` 失败只保留未发布产物。
- 已停写、尚未启动候选：保留现场。V84 完整且尚无新业务写入时，只有核实旧包与增量结构兼容、运行时 Flyway 关闭后才可人工启动原包；本文不提供自动回退命令。
- 迁移部分失败：MySQL DDL 可留下已提交对象，重复 migrate 不等于恢复。保留失败历史和残留结构，使用隔离恢复演练确定恢复步骤；不得盲目 repair、clean、删除历史或直接重试。
- 候选开始启动后：即按“可能已有写入”处理，禁止直接返回旧包。优先兼容修复；若必须恢复备份，先保存升级后的 MySQL/TDengine 数据和新增证据，明确备份时刻后的损失范围及重放方案，经确认后才能执行。旧备份恢复不是无损回退。

## 5. 可重复的隔离验证

在本机 Windows Docker Desktop Linux 引擎运行，先使用 Java 21 执行 `mvnw.cmd -DskipTests package` 和 `mvnw.cmd dependency:build-classpath -DincludeScope=test -Dmdep.outputFile=target/d01-classpath.txt`，再执行 `python scripts/Verify-D01UpgradeIsolated.py`。脚本只使用本机新容器、回环端口、随机所有权标签及独立卷；不会接受现有服务器地址。覆盖完整 V83 非空合成库、主库与归档库逐表摘要、V84 升级、非默认 TD 密码备份、新实例恢复和部分 DDL 失败；systemd 与外部停写证明是离线夹具。

本轮隔离演练已通过：MySQL 8.0.46 / TDengine 3.2.3.0，V83 的主库/归档库 233 张原有表内容摘要一致，既有待审/已执行记录保留；新增菜单授权正确。真实备份恢复到新实例后旧数据一致，源卷保留备份后的新增 D01 证据；部分 V84 DDL 失败后重试和切换校验拒绝，篡改备份拒绝，自建资源按所有权清理。这是合成非空库证据，不能称为现网数据库副本或现场切换验收。

另在断网 Linux 临时容器运行 `scripts/tests/test_d01_release.py`、`test_d01_backup.py` 和既有部署回归。它们验证阶段门禁及失败保护，不证明真实 Broker 的消息保留、现网备份可恢复、现场吞吐或实际维护切换已完成。
