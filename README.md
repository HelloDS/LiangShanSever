# 凉山棋牌游戏 Java 服务端

按 `Java高性能棋牌游戏服务器方案.md` 分阶段实现。游戏 HTTP/WS 与若依后台同进程运行，Creator 现有协议是兼容依据。

当前已实现持久游客登录与恢复、大厅资料/公告/匿名举报、玩家与统计后台，以及亲友圈成员申请/审批/退出/权限与组织后台。圈创建、兑换和资金操作仍受未确认规则约束，房间与斗地主尚未接通。实际验收范围见 `docs/开发进度.md`，不将页面可用等同于三个版本全部完成。

## 本地启动

需要 Java 21、Node 24.19.0、pnpm 11.25.0、Docker 与 Compose。Maven 3.9.16 由 Wrapper 下载。macOS 可以使用 Colima；Docker 引擎须先启动。MySQL/Redis/Nginx 镜像已锁定摘要。

```bash
python3 scripts/init-local.py
docker-compose --env-file .local/local.env up -d --wait
scripts/build.sh
scripts/run.sh
```

若使用 Docker CLI 的 Compose 插件，将 `docker-compose` 换成 `docker compose`。配套容器默认只在本机发布端口，不要另启同端口的系统 MySQL/Redis/Nginx。

- 统一入口：<http://localhost:8080>
- 后台：<http://localhost:8080/admin/>，用户名 `admin`
- 初始随机密码：本机 `.local/local.env` 的 `ADMIN_INITIAL_PASSWORD`，该文件权限为 600，不提交 Git。
- Java HTTP：127.0.0.1:8082；Netty WS：127.0.0.1:8081；浏览器连接统一入口的 `/`。
- 健康：<http://localhost:8080/actuator/health>；详细运维指标需后台认证。

首次迁移的管理员处于禁用状态，只在提供初始密码且尚未初始化时启用。后续启动不覆盖已设置密码。数据库持久数据保存在命名 volume；普通停止不会删除。

举报联系方式使用 `.local/local.env` 中的 `GAME_PII_KEY` 加密保存；该密钥与数据库一起保留，丢失后无法解密已有联系方式。匿名举报不保存联系方式。

```bash
docker-compose --env-file .local/local.env stop
```

Flyway 通过自己的无池 JDBC 连接迁移，运行期使用若依 Druid 数据源。localhost 开发 URL 的非 TLS 认证参数只适用于本地环境。

## 验证

```bash
scripts/test.sh
npm ci --prefix tools --ignore-scripts
node tools/smoke.mjs
node tools/admin-smoke.mjs
node tools/report-smoke.mjs
node tools/wallet-smoke.mjs
node tools/transport-smoke.mjs
node tools/outbox-smoke.mjs
python3 scripts/collect-test-evidence.py
```

`build.sh` 先执行严格 `vue-tsc` 和 Vite 生产构建，再执行 Java 打包及测试；也可单独运行 `scripts/pnpm.sh --dir admin-ui typecheck`。

`test.sh` 使用独立 Testcontainers MySQL/Redis 验证迁移、回滚、身份恢复与并发幂等；不会清空本地运行数据库。smoke 创建真实的本地测试游客并校验 HTTP/WS/Admin，不调用旧服务器。结果只保存检查项，不写凭据、原始认证报文或密码。完整范围与实际通过项见 `docs/开发进度.md` 和 `docs/evidence/`。

官方后端与前端固定提交及 MIT 来源见 `docs/upstream.json`，保留 `LICENSE-RuoYi` 与 `admin-ui/LICENSE`。已有设计文档与前端证据不代表实现已通过所有验收。

亲友圈组织联调使用明确标记的本地测试夹具，需显式执行：

```bash
RUN_ORGANIZATION_FIXTURES=1 node tools/club-smoke.mjs
```

该测试建立已知上限的“既有圈”，成员通过真实申请/审批加入，结束后停用测试圈并保留审计。它不代表创建圈费用和资金转入已确认或通过验收。规则缺口与原消费者结构见 `docs/亲友圈协议与规则缺口.md`。

统一钱包事务和只读账本/核对后台已经接入；兑换、转卡、补偿发放与圈费用尚未开放。资金防重、期初覆盖和剩余规则见 `docs/钱包账本与经济规则缺口.md`。

大厅真实连接负载仅在本机显式运行，凭据文件仅写入忽略目录，测试账号不进入运营统计：

```bash
RUN_LOAD_FIXTURES=1 node tools/prepare-load.mjs 1000
source scripts/java-env.sh
RUN_LOBBY_LOAD=1 LOAD_SECONDS=3600 /opt/homebrew/opt/node@24/bin/node tools/lobby-load.mjs
```

默认保持 1000 个已认证连接，其中 800 个空闲、200 个活跃，执行每秒 200 次大厅查询；查询 P95 目标 300ms。仅按实际运行时长和结果保存证据，不代表斗地主动作、结算、WSS 或 24 小时稳定性通过。

管理撤销通知已使用事务 outbox 和有界 worker；后台“持久任务”提供独立查询/重试权限。运行时可用 `GAME_OUTBOX_ENABLED=false` 暂停领取，已提交任务保留在 MySQL；恢复领取后按租约与原事件继续。详见 `docs/持久任务与投递边界.md`。

本机 Colima 千连接测试使用 `colima start --port-forwarder grpc`。本次 SSH 转发主进程退出导致宿主端口及 Docker socket 丢失，而 VM 内服务仍正常，故切换后重新验收；没有把环境中断算作服务容量通过。转发方式见 [Lima 官方说明](https://lima-vm.io/docs/config/port/)。重启 Colima 后用 Compose 恢复原命名卷，并核验持久游客身份。

持久任务正常重启验收使用两步脚本：以 `scripts/run.sh --game.outbox.worker-enabled=false` 启动后，在另一个终端运行 `RUN_RECOVERY_FIXTURES=1 node tools/outbox-recovery.mjs prepare`；正常停止并默认重启 Java 后执行同样变量的 `verify`。该测试创建标记为测试的真实游客与管理命令，公共记录不含认证材料。
