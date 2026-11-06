# 凉山棋牌游戏 Java 服务端

按 `Java高性能棋牌游戏服务器方案.md` 分阶段实现。游戏 HTTP/WS 与若依后台同进程运行，Creator 现有协议是兼容依据。

当前完成最小工程：持久游客凭据、WS 注册与恢复、按连接顺序响应、独立游戏 Jackson 3、若依原生登录、数据库迁移及统一 Nginx 入口。大厅周边、圈、房间与玩法仍按 `docs/开发进度.md` 推进，不将工程启动等同于全部业务完成。

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

```bash
docker-compose --env-file .local/local.env stop
```

Flyway 通过自己的无池 JDBC 连接迁移，运行期使用若依 Druid 数据源。localhost 开发 URL 的非 TLS 认证参数只适用于本地环境。

## 验证

```bash
source scripts/java-env.sh
./mvnw test
npm ci --prefix tools --ignore-scripts
node tools/smoke.mjs
```

smoke 创建真实的本地游客并校验 HTTP/WS/Admin，不调用旧服务器。结果只保存检查项，不写凭据、原始认证报文或密码。完整范围与实际通过项见 `docs/开发进度.md` 和 `docs/evidence/`。

官方后端与前端固定提交及 MIT 来源见 `docs/upstream.json`，保留 `LICENSE-RuoYi` 与 `admin-ui/LICENSE`。已有设计文档与前端证据不代表实现已通过所有验收。
