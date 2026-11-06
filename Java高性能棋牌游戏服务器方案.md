# 凉山多人棋牌游戏 Java 服务端方案

> 编制日期：2026-11-06。设计容量：初期约 1,000 人同时在线。本文为开发设计，不代表已有 Java 服务端或通过性能测试。
>
> 本次修订：确定游戏服务端与 Admin 技术栈，新增第 11 节 Admin Web 管理工具设计，覆盖今日登录、实时在线、亲友圈排序及日常管理；同时保留全部游戏与功能模块设计。
>
> 阅读顺序：[功能模块说明书](../Client/功能模块说明书.md) → 本文 → [前端字段与源码证据](前端字段与源码证据.md) → [机器可读协议索引](protocol-evidence.json)。字段索引用于开发定位，不是已经验证的服务端协议规范。

## 1. 结论与依据

采用 **Java 21 LTS + Spring Boot 4.1.2 + Netty 4.2 WSS/JSON + MySQL 8.4 LTS/InnoDB + Redis 8**；Admin 使用 **Vue 3 + TypeScript + Vite + Element Plus + ECharts**。首期模块化单体，游戏房间常驻内存、同房间串行裁决；数据库负责可靠业务记录，Redis 负责会话及可重建数据。保持现有客户端协议，先完成真实多人闭环，再扩展部署。

客户端范围为大厅公共能力、19 个注册功能模块、11 款多人游戏及独立管理模块；但本地没有原服务端程序、数据库，也没有足以确定所有地方玩法的规则书。Creator 的 TypeScript 包含显示、可操作提示及部分牌型判断，不能把这些直接认定为完整服务端裁决。

### 1.1 证据分级

| 标记 | 含义 | 使用方式 |
| --- | --- | --- |
| S：源码确认 | 当前 TS 的发送、读取或绑定；共享业务仍执行的归档 JS | 固定字段名和实际封包；类型仍须追踪调用处 |
| R：历史实测记录 | 本地已有多人联机与异常记录 | 证明对应样本经过，不能推导全部分支 |
| D：本方案设计 | 新 Java 架构、库表、内部接口、性能目标 | 实施时落地并测试，不声称原站就是这样实现 |
| U：待确认 | 发牌规则、隐藏分值、缺少样本的枚举或 HTTP 认证细节 | 建立阻塞清单，不能凭通用棋牌规则补成原站事实 |

主要来源均在 `../Client` 下：

- [功能模块说明书](../Client/功能模块说明书.md)：模块业务范围、接口证据、历史入口状态及待确认事项；技术方案与其配套使用，原字段事实仍以源码为准。
- `creator/assets/runtime/scripts/network/`：WSS 适配、请求队列及回调处理。
- `creator/assets/runtime/scripts/games/`：11 款游戏发送入口、接收处理及模型；附录记录具体文件与行号。
- `reference/protocol/game-session-actions.json`、`manifests/game-behaviors.json`：房间动作、初始化、归档源映射。
- `creator/README.md`、`progress/lobby-protocol-audit.md`：已完成的整场记录、原关闭入口及协议遗留项。

本次只读取本地材料，没有向原后端发请求。历史 README 中的通过结论按 R 级引用，不视为本次重新实测。

## 2. 技术选型与部署

| 层 | 确定选型（D） | 用途与边界 |
| --- | --- | --- |
| Java / 构建 | Java 21 LTS、Maven Wrapper | 统一编译与运行基线 |
| 应用框架 | Spring Boot 4.1.2、Spring MVC | 业务组装、事务、HTTP 与 Admin REST API |
| 游戏长连接 | Netty 4.2、WSS、JSON 文本帧 | 兼容现有客户端 FIFO；网络线程不执行阻塞业务 |
| JSON | Boot 管理的 Jackson 版本、显式 DTO | 保留原字段与历史拼写；禁止任意类型反序列化 |
| 数据库 | MySQL 8.4 LTS / InnoDB | 账号、圈、房间日志、订单、账本和统计权威记录 |
| 数据访问 | MyBatis Spring Boot Starter 4.0、HikariCP | 显式 SQL、索引、事务与有界连接池 |
| 库表迁移 | Flyway，包含 MySQL 支持模块 | 版本化建表与兼容升级 |
| 缓存 | Redis 8、Lettuce | 会话、在线租约、限流、配置和排行缓存 |
| Admin 鉴权 | Spring Security、Spring Session Redis | 独立后台账号、服务端会话、操作和数据范围权限 |
| Admin 前端 | Vue 3、TypeScript、Vite、Element Plus | 独立电脑端管理网站；不整套引入若依框架 |
| 路由/状态/请求 | Vue Router、Pinia、Axios | 页面、登录态、权限展示和 HTTP 调用 |
| 图表 | Apache ECharts | 在线、登录、对局与房卡消耗趋势 |
| 文件与回放 | 开发期本地；生产阿里云 OSS | 回放、异步导出与运营图片；内部存储不改变旧回放协议 |
| 部署入口 | Linux、Docker Compose、Nginx | HTTPS、WSS、静态站点和反向代理 |
| 监控 | Actuator、Micrometer、Prometheus、Grafana、结构化日志 | JVM、连接、队列、SQL、统计延迟及业务异常 |
| 测试 | JUnit 5、Testcontainers、Vitest、Playwright | 游戏规则、真实数据库集成、前端组件与页面流程 |

Spring Boot 4.1.2 支持 Java 21；MyBatis Starter 4.0 面向 Boot 4.0 及以上。依据：[Boot 官方系统要求](https://docs.spring.io/spring-boot/system-requirements.html)、[MyBatis 官方兼容表](https://mybatis.org/spring-boot-starter/mybatis-spring-boot-autoconfigure/)。Spring Security 等组件由 Boot BOM 管理；测试工具及独立 Netty 模块必须运行兼容性门禁，不随意覆盖 Spring 依赖版本。支持维护和安全修复时，允许在同系列内有记录地更新补丁版本。

Vue TypeScript/Vite 工具链与组件使用参考 [Vue 官方文档](https://vuejs.org/guide/typescript/overview)、[Element Plus 官方文档](https://element-plus.org/en-US/guide/quickstart)。Netty 4.2 参考[迁移说明](https://netty.io/wiki/netty-4.2-migration-guide.html)，MySQL 事务参考[官方说明](https://dev.mysql.com/doc/refman/8.4/en/commit.html)，Redis 持久化参考[官方说明](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/)。这些资料支撑组件能力和兼容关系，不代表本项目容量已通过验证。

Java 工程初始化时锁定具体依赖补丁和容器镜像摘要；前端采用 pnpm，提交 pnpm-lock.yaml，Node.js 构建环境使用届时受维护的 LTS 并固定版本。生产只部署前端编译产物，不需要 Node.js 常驻。首期不用 Kafka、RabbitMQ、Elasticsearch、ClickHouse、Kubernetes 或分库分表；异步工作由 MySQL outbox 和任务表承担。

### 2.1 首期部署

```mermaid
flowchart LR
  C[Creator 多人客户端] -->|WSS JSON| N[Nginx TLS]
  N --> A[Java 应用：接入 / 房间玩法 / 圈组织 / 账本 / 运营 / 管理]
  A -->|有界异步 I/O| M[(MySQL)]
  A --> R[(Redis)]
  B[Admin 浏览器] -->|HTTPS| N
  N --> S[Admin 静态资源]
  N -->|Admin REST| A
  A --> O[阿里云 OSS：回放 / 导出 / 图片]
  A --> P[指标与日志]
```

- 本地：Docker Compose 启动 MySQL、Redis，IDE 启动 Java，Vite 启动 Admin 开发服务器并代理 API；所有测试账号和数据自建。
- 生产：Admin 独立主机名下 `/` 提供静态资源、`/admin/api/v1` 代理 Spring MVC；游戏主机名保留原 WSS 路径。二者首期可共用 Nginx 和 Java 进程，但 Admin Cookie 不跨主机名共享。Prometheus/Grafana 与数据库只走管理网络；后台静态部署和游戏应用发布独立执行。
- 初期生产建议应用 4 vCPU / 8 GB，独立数据节点 4 vCPU / 8 GB + SSD；这是压测起点，不能据此承诺千人容量。Redis 与 MySQL 初期可同数据节点，明确其共同故障域。
- 数据库、Redis 不暴露公网；生产使用远端备份。此布局没有应用或数据节点高可用，节点故障时按第 8 节恢复，不承诺无感切换。
- CPU 连续 15 分钟超过 65%、动作 P99 超过目标、邮箱积压或 GC 停顿持续上升时，先定位瓶颈，再拆接入网关与房间服务；不能只增加连接数上限。
- 扩容后每个房间固定归属一个房间节点。目录记录 owner 节点与 epoch；持久化版本检查拒绝旧 owner 写入。首期不做运行中房间迁移；发布停止建房并等待房间退出。

## 3. 网络契约：必须兼容现有前端

### 3.1 外层封包（S）

业务请求示例（卡牌/坐标值仅为形状演示，不能作为有效操作样本）：

```json
{"path":"logic","reqData":{"api":"room","c":"gameMessage","tag":"chess","point":[7,7],"sessionToken":"<会话令牌>"}}
```

`requestUser` 使用 `path="login"`；`requestLogic` 使用 `path="logic"` 并补充 `sessionToken`。不能把所有 `api=user` 都派发到 logic，例如 `user/resetfd` 经 login 通道发送。源码中部分会话字面量会被运行时覆盖；新系统不得复制或信任那些归档值。

| 消息 | 判别 | 客户端行为与服务端要求 |
| --- | --- | --- |
| 普通响应 | 没有 truthy `cmd` | 按 FIFO 消费一个回调；`state=0` 成功；每条已接收合法请求只回复一次 |
| 业务错误 | 普通响应且 `state!=0` | 失败回调或错误提示；`state=-5` 有网络错误特殊处理 |
| 共享推送 | `cmd` 如 `joinRoom`、`voteEndGame` | 按共享命令处理；不得包装为 gameMessage tag |
| 游戏推送 | `cmd="gameMessage"`，带 `tag` | 由游戏处理器按 tag 分派 |

最小成功 ACK 为 `{"state":0}`，需要结果字段的接口必须按接收处补齐，不能所有接口只返回 ACK。推送示意：`{"cmd":"gameMessage","tag":"ready","uid":1001,"readys":{"1001":1}}`，具体 `readys` 形状按游戏保留，不一律转 Map。

### 3.2 顺序与连接管理（D，约束来自 S）

1. 每连接入站请求依次处理、出站统一排队；业务完成后先 ACK，再发送该动作产生的推送。同一房间事件保持相同提交顺序。
2. 客户端在普通响应未完成时缓存推送；遗漏 ACK 会使游戏表现卡住。服务端不能用一条推送替代响应，不能给同一请求再发送第二条普通响应。
3. WebSocket Ping/Pong 用于保活，不发送没有 `cmd` 的 JSON 心跳，否则会消耗业务回调。初始设计 Ping 30 秒、90 秒未活跃断开，真机后台行为待测。
4. 初始聚合消息上限 256 KiB、JSON 深度上限 32；回放、圈成员和管理列表均按实际响应大小验证。回放仍优先兼容 `movie/getMovie`，对象存储仅为内部归档；客户端未支持 URL/分页时，不得直接改为 HTTP 下载或拆成多条普通响应。超限场景先形成客户端兼容方案或有界调整专用上限，再开放。支持 WebSocket 分片聚合，拒绝二进制业务帧。
5. 慢客户端输出队列限定 1 MiB；牌局事件不丢弃，超限断开后使用快照重连。按 IP 与账号分别限流，初始连接业务限额 20 次/秒、突发 40，超限返回兼容错误并计数。
6. 网络 EventLoop 只解析、校验基础封包与投递；SQL、文件、外部接口与复杂规则计算移交有界执行器。邮箱满时在入队前拒绝，禁止无界排队。

### 3.3 类型、错误与身份

- ID 源码存在 `string | number`；内部采用统一字符串标识，旧协议输出遵照具体接收处，不机械地把全部 UID 转字符串。旧协议数值 ID 限于 JS 安全整数，不能直接下发任意 Java long 雪花 ID。
- 布尔值与 `0/1`、缺省与 `null`、数组与 UID 字典均不得凭风格统一。各 DTO 按字段契约序列化；数值枚举没有依据时列 U。
- 扑克牌多为 `[suit,rank]`，麻将和斗十四动作使用数字牌值；不能把二者统一成一种前端格式。服务端使用自有编码，通过各游戏适配器转换。
- 用户身份、所在房间与座位来自已验证会话；请求中的 `uid`、`targetUid` 等只作为特定操作对象，不能用于冒充操作者。
- 历史 `-4`（参数非法）与 `-10`（房间不存在）的含义来自验收记录；完整错误码须对照 ApiState 源码登记。不要把新错误码随意塞给旧客户端。无兼容映射的错误，先补前端映射与契约测试再开放。
- 原协议无动作 ID，跨断线重发无法普遍区分“重试”与“新的同样动作”。首期仅用轮次、状态版本和可操作窗口拒绝过期操作；结算靠业务唯一键幂等。不能承诺所有动作 exactly-once。未来协议升级可新增可选 `requestId/actionId`，本次不作为客户端必填条件。

## 4. 登录、大厅与房间接口

### 4.1 启动和登录

启动包含 HTTP 配置/认证，再注册或绑定 WebSocket 会话；`backendUrl` 不是唯一地址来源。迁移需检查场景配置、宿主注入、入口补丁与 AppConfig 中的游戏列表、资源、认证、客服及回放地址，不能只修改一个 WSS 域名。

源码确认的 `user/reg(account)` 通过 login 请求，请求还设置 `plat_id`，按入口条件补充 `recommend`；响应读取 `user`、`sessionToken`、`loginData`；其中 `loginData.role` 被缓存，并读取公告、跑马灯等配置。`user/resetfd` 将现有会话重新绑定连接。新系统令牌由服务端安全随机生成，仅保存其摘要或受控会话记录；不能接受任意客户端 `account` 作为可信第三方身份。第三方认证/访客 account 的确切形状与 HTTP 返回结构须沿宿主及归档入口进一步形成样本测试，在接入外部身份前保持 U。

### 4.2 核心路由表（S）

下表只列业务字段，logic 请求还包含会话。响应栏为接收方明确读取的字段，不代表仅有这些字段。

| api / c | 业务请求字段 | 响应/后续处理 |
| --- | --- | --- |
| cfg / getGame | `game_id` | 11 款建房配置，形状按面板与源码附录核对 |
| room / createRoom | `game_id, createData`；后者加入 `groupid` | `room`；可选 `role.card, role.diamond` |
| room / joinRoom | `room_id` | `room, roles, leaveGames`；即使无人离线也应返回空数组 |
| room / leaveRoom | 无 | ACK 后本地清空房间；服务端广播离开 |
| room / disbandRoom | 可选 `room_id` | 可选 `role`；检查房主及未开局权限 |
| room / voteEndGame | `result`：同意 1、反对 0 | ACK + 独立 `cmd=voteEndGame` |
| room / checkVoteEndGame | 无 | 当前会话房间的投票同步 |
| room / kick | `targetUid` | 房主权限与开局状态校验 |
| room / gameMessage | `tag` + 游戏字段 | ACK + 游戏推送；服务端从房间确定游戏编号 |
| game / returnGame | 见附录方法证据 | 查未结束房间并恢复；不能强制创建新房 |
| game / loginGame | 无 | 游戏进入通知；与 checkGameCreate 区分 |
| game / checkGameCreate | 无 | ACK 后 `gameMessage/initGame` 初始化 |
| movie / getMovie | `movie_id` | 回放契约按各游戏解析器输出 |
| role / getRoles | `uids` | 按 UID 查询的角色资料 |
| role / getRoleInfo | `targetUid` | 对外可见角色信息 |
| role / getZhanji | 见附录方法证据 | 各游戏战绩视图消费结果 |

公共房间数据至少涉及 `id, owner, players, data.isStart`；游戏标识读取 `room.data.game_id`，部分游戏还读 `max_player`。座位列表与 UID 字典必须区分，不能用在线连接顺序推断座位。

共享推送：`joinRoom` 读取 `sender, room, roles`；`leaveRoom` 读取 `sender, room`；`leaveGame` 标记断线；`returnGame` 取消离线标记；`disbandRoom` 退出大厅。`voteEndGame` 读取 `result, allChoose, sender, voteEndGameTime`，这是外层 cmd，不是游戏 tag。已开局通过投票解散，单局结束不等于整房尚未开始；旁观者走离房流程。投票时间单位和结果枚举按消费者核实后锁定，不凭字段名猜秒/毫秒。

### 4.3 大厅其他模块

角色、公告、聊天、亲友圈、成员/权限、代理/管理、战绩和回放均纳入兼容清单。附录按源码路由列出查询与修改操作；修改操作必须检查会话、角色权限及目标资源归属。

原入口的商城/背包/任务等未开放，比赛/活动等存在本地关闭提示，隐藏入口不得因服务端重写而自动开放。斗十四战绩当前配置未开放，不能为了“补全”擅自改变客户端入口。短信、实名、外部支付/兑付、客服及实物履约通过独立适配器接入；内部钻石/房卡兑换、道具购买、奖励领取由统一账本和订单实现，不能仅预留外部接口。外部集成使用独立测试适配器，不伪造支付成功。缺失充值二维码登记为资源/配置缺口，不归入牌局规则。

### 4.4 全模块覆盖与归属（D，范围依据功能说明书）

19 个前端模块不等于 19 个部署服务。首期保持一个应用进程；下表为业务边界，查询和修改共享同一权威数据源。接口原名、参数及证据编号查 [功能模块说明书](../Client/功能模块说明书.md) 第 4 节，不另起一套网络协议。

| 前端模块 ID | Java 业务归属 | 必须覆盖的能力 |
| --- | --- | --- |
| club | club | 圈、成员、申请/退圈审批、管理员、圈配置、圈房间和圈战绩 |
| openroom | room + club | 本人代开房间、批量详情、代开战绩；代开人/房主/付款人分别记录 |
| joinGame | room | 房号查询及加入；每用户当前房间唯一占用、入座竞争校验 |
| shiming | identity | 认证状态、提交与供应商结果映射；受控保存和脱敏 |
| jubao | report | 分类与内容提交、匿名展示及后台处理；匿名字段仍须核对源码 |
| match | tournament | 报名/取消、赛程、分桌、排名、奖励领取；具体赛制待确认 |
| proppay | economy + inventory | 道具/专属表情配置、拥有状态、购买、扣币和发放 |
| activity | activity | 活动配置、时间/角色可见性、子活动跳转 |
| yuxiactivity | activity | activityCompleteGamesRank 的参与、有效对局统计、排行 |
| lsanactivity | activity | activityCompleteGamesDeuce 的参与与成绩；计算规则待确认 |
| phonefangka | identity + reward | 手机绑定、短信验证、赠卡资格、红包查询与领取 |
| clubbuild | club-build | ore/strength 资源、申请审批、调整、开采、自动建设及流水 |
| diamond | economy | 个人/圈钻石房卡兑换及划转、汇率和双边账本 |
| qiandao | reward | 签到配置、记录、日期唯一性及发奖 |
| invite | promotion | 邀请关系、有效奖励、储备、提取申请 |
| koi | promotion + reward | 锦鲤信息/规则/排行、邀请、按日期和类型领取或提取 |
| freephone | fulfillment + reward | 活动计划/机型、按月赠卡及实物履约 |
| sapling | activity + reward | 种植、浇水/好友帮助、耗卡积分、排行与领奖 |
| clubpartner | club-partner | 合伙人权限、成员归属、审批范围、业绩及成员战绩 |

公共能力另有 auth/role/config、聊天、公告、设置、战绩与回放；管理模块另归 admin/agent/reporting。音量等纯本地设置不新增服务端接口。管理模块需要覆盖 manager、role、roleInfo、sms 中已确认的管理路由，不能仅实现健康检查或公告查询就称“后台完成”。

功能开关区分“服务端已实现”“入口可见”“账号有权限”“业务规则已确认”。维持原关闭/隐藏入口的兼容默认；配置关闭后已有订单、提取申请仍可查询并完成处理，不能用关入口掩盖未结清业务。锦鲤头像入口与侧栏入口状态分别保留，不把侧栏关闭扩大成整个模块禁止查询。

## 5. 全部游戏动作契约（S）

以下都封装为 `api=room,c=gameMessage,tag=...`。`—` 表示无额外字段。字段是否可省略以方法逻辑为准；数组元素和枚举值需结合对应模型及常量。所有规则判断由服务端重算，不信任客户端 `opCardType` 等声明。

### 5.1 跑得快：6008、6014、6038、6073

| tag | 字段 | 差异与校验 |
| --- | --- | --- |
| ready | — | 未开局、座位有效；重复准备不重复开局 |
| chupai | `outCards, rounds, opCardType` | 自有牌、牌型、轮次、压制关系、首出约束 |
| buchu | — | 此发送入口仅 6014 发包；另外三款方法为空，不能假设它们一定发过牌包 |
| qiangZhuang / buQiangZhuang | — | 抢庄阶段及资格 |
| applyAuto | `type` | 托管枚举按源码消费者核实 |
| teShuPaixin | `teShuPaiXin` | 非 6014；注意 tag 和字段大小写不同 |
| use3D | — | 仅 6008/6073 入口；表现偏好不影响牌局裁决 |

接收 tag 包括 `ready,startGame,chupai,buchu,GameOver,endGame,DanBanEnd,QiangZhuang,BuQiangZhuang,applyAuto`，注意请求与推送的大小写差异。

快照涉及 `cardsInfo,outCards,cards,chuPaiList,currentUid,perUid,lastChuUid,currentState,currentCount,maxCount,rounds,autoList,money` 及庄家、抢庄、特殊牌型字段。`cards`、`allCards` 等模型存在不等于可向所有玩家公开。建房选项包括 `magicCard,banXin,sandai1,sandai2,sidai2,sidaidui,isMustQiang,isQiangZhuang,zhaDanType,boomFenType,autoDelay,paiZhang,delType,delCardType` 等，全部索引见附录；按游戏配置白名单校验，不能将四款视为仅 ID 不同。

### 5.2 斗地主 6003 与重炸斗地主 6009

| tag | 字段 | 范围 |
| --- | --- | --- |
| ready | — | 两款 |
| score | `score:number` | 两款 API 均有入口；实际启用阶段按玩法 |
| jiabei | `jiabei:number` | 两款 API 均有入口 |
| chupai | `outCards:[number,number][],opCardType:number` | 6003 |
| chupai | 上述字段 + `magicCard` | 6009；默认空数组，也接受方法显式传 null 的形状 |
| buchu | — | 两款 |
| auto | — | 6003；不能误写成 applyAuto |
| seeCards | `seeUid:UID` | 6009；检查允许查看范围 |
| drawCards | `drawType:number` | 6009 |
| daopai | `daoType:number` | 6009 |

接收 tag 两款共有 `ready,startGame,chupai,buchu,GameOver,endGame`；6003 额外绑定 `score,jiabei,auto`，6009 额外绑定 `seeCards,drawCards,daopai`。发送 API 存在某方法不代表该玩法接收端会处理同名推送。

建房基础选项 `cardType:number,aa:boolean,playNumber:number`，重炸扩展 `maxFan:number,daoBiGen:boolean`。快照涉及 `cardsInfo`（含 `dizhuCards`）、`ready,currentUid,perUid,dizhuUid,seeUid,currentState,currentCount,maxCount,cardsType,opCardType,difen,beishu,maxFan,isSpring,score,drawType,reverse,money,currentMoney,autoList`。6003 托管列表是 UID 数组，不要直接套麻将的字典格式。

### 5.3 五子棋 6055

| tag | 字段 | 服务端逻辑 |
| --- | --- | --- |
| ready | — | 准备 |
| chess | `point:[x,y]`，两个数字 | 坐标为整数、棋盘边界、轮到本人、落点为空、胜负与禁手配置 |
| applyHuiqi | — | 剩余次数、双方状态与未完成申请 |
| huiqi | `uid:UID,isAgree:boolean` | uid 是操作关联对象，操作者从会话确定；对应有效悔棋申请 |
| giveUp | — | 本人认输且未终局 |

推送精确字段示例：`ready(uid,readys)`；`startGame(game)`；`chess(game,sender,point)`；`updateCurrentUid(game)`；`applyHuiqi(game,sender,huiqiList)`；`huiqi(sender,applyUid,huiqiList,game?,removePoint?)`；`gameOver(game,result:number[],winPoint?)`；`endGame`。`result` 数组各位置、棋盘大小及禁手定义须从常量/配置确定，不能假设 result 直接等于胜者 UID。

### 5.4 张张牌 6060

| tag | 字段 | 特别说明 |
| --- | --- | --- |
| ready | — | 准备 |
| chupai | `outCards:[number,number][],opCardType:number,conType:number` | 方法第一个 rounds 参数未发送，不能添加为必填 |
| bupengzha | — | `buchu()` 与 `bupengzha()` 都发送此 tag |

快照 `cardsInfo,cards,outCards,currentUid,perUid,currentState,currentCount,maxCount,opCardType,opConType,opPlayers,teshuPlayers,overPlayers,curChuPaiList,perBuYaoUid,scoreCards,money,currentMoney`。首出黑桃 3 的客户端校验已有修复记录；服务端仍需依配置验证，不信任前端按钮状态。

### 5.5 凉山斗十四 6061

| tag | 字段 | 服务端逻辑 |
| --- | --- | --- |
| ready | — | 准备 |
| operate | `operatingType:number,cards:number[]` | cards 默认 []；碰吃胡等含义按枚举核实，服务端判断操作资格 |
| playCard | `card:number` | 数字牌编码、所有权、阶段、轮次 |
| applyAuto | `type:number` | 默认 1；与麻将固定 0 不同 |

初始化除 `game` 外读取 `pkList,needShowOp,pks?`，不可错误套用扑克的 `card` 字段。接收 tag 包括 `ready,startGame,moCard,playCard,beforeFanCard,fanCard,operate,operating,hupai,huangzhuang,endGame,applyAuto,joinRoom`。

快照涉及 `playerNewPokers,notBaList,passHuList,currentUid,zhuang,newPoker,lastPoker,totalPoker,curPoker,scoreData,oneWinScoreData,comScore,comPoint,chiList,dyList,bgList,hitList,huList,autoList,cardState,limitPoint,limitScore,maxFan,baseScore`。不得把斗十四数字牌直接解释为麻将牌值。

### 5.6 麻将 6056、6057

| tag | 字段 | 缺省规则 |
| --- | --- | --- |
| ready | — | — |
| chupai | `mjVal:number,ting?:boolean` | ting 只有非 null 才发送 |
| lacking | `lackType:number` | — |
| operating | `operatingType:number,chiMjs?:number[]` | 仅 type=1 且非 null 才发 chiMjs |
| zimo | — | — |
| qiangganghu | `type:number|string` | 按消费端确定可接受值 |
| gangSelf / anGang | `mjVal:number` | callGangApi 是本地选择函数，不是 tag |
| ting | `remove:number[],tingVal:number[],tingTarget:UID` | 声明听牌结果须服务端重算 |
| tingOperate | `type?:number|string` | 默认 0 且等于 0 时省略 |
| applyAuto | `type:0` | 当前 API 忽略参数强制发 0；不能要求发 1 才响应 |
| applyAutoHu | — | — |
| applyTang | `type:number|string` | 本地方法名 applyTangpai，默认 0 |
| tangOperate | `cards:number[],type?:number|string` | 默认 0 时省略 type |
| touThrOperate | `cards:number[]` | 保留历史拼写 |
| changeThree | `cards:number[]` | 换三张数量、花色、所有权由规则校验 |

接收 tag 包括 `ready,startGame,changeThree,touThrOperate,tingOperate,tangOperate,applyTang,lacking,chupai,getNewMjCard,optError,endGame,qiangganghu,updateOperation,hupai,operating,huangzhuang,applyAuto,applyAutoHu`。

两款共享动作入口不等于规则相同。快照涉及 `currentUid,lastUid,firstHuUid,zhuang,totalMj,currentMj,currentMj2,newMj,lastMj,mjCount,lackTypes,changeList,tthrList,alreadyHus,tingList,canTingList,applyTangList,tangList,totalFan,autoList,autoTangList,currentCount,maxCount`。动作窗口须处理多家碰杠胡优先级、抢杠胡、过胡限制与过期操作，优先级依据地方规则确认。其他人的新摸牌、待换手牌不能随公共快照泄露。

## 6. Java 业务实现方式（D）

### 6.1 模块和最小内部接口

模块按 `transport / auth / role / config / lobby / room / game-spi / games / club / club-build / club-partner / economy / inventory / reward / activity / promotion / tournament / identity / report / fulfillment / agent / admin / reporting / persistence / replay / observability` 划分业务包，首期不要求每个业务包拆成独立 Maven 工程或进程。`games` 下按游戏族实现并按游戏 ID 注册；共享牌型工具不得把独立玩法合并成一个配置开关堆叠类。

```java
// 内部设计示意，不是客户端新增字段，也不是已实现代码。
record WireRequest(String path, JsonNode reqData) {}
record ActorIdentity(String uid, String sessionId, long connectionEpoch) {}
record RoomCommand(ActorIdentity actor, String tag, JsonNode payload) {}
interface GameEngine {
    Validation validate(RoomState state, RoomCommand command);
    Transition apply(RoomState state, RoomCommand command);
    JsonNode snapshotFor(RoomState state, String viewerUid);
}
interface ProtocolAdapter {
    RoomCommand decode(ActorIdentity actor, JsonNode reqData);
    List<OutboundMessage> encode(CommittedTransition transition);
}
```

`Transition` 包含新状态、内部事件和结算意图；不直接写 socket 或 SQL。协议适配器负责按游戏输出旧字段；内部事件有 `roomId,roundNo,version,eventSeq`，这些不自动写入客户端报文。JSON 入站在适配器边界转显式 DTO，不能让游戏引擎到处处理任意 JsonNode。

### 6.2 房间串行与状态机

- 使用固定数量分片执行器，按 roomId hash 投递；同房间动作、定时器、掉线通知和解散操作都进入相同邮箱。禁止“一房间一条常驻线程”。初始 worker 数为可用处理器数，邮箱每分片上限 4096，压测调整。
- 通用状态：等待入座 → 准备 → 发牌/选项阶段 → 对局 → 单局结算 → 下一局准备 → 总结算 → 关闭。数字 `currentState` 通过每款适配器映射，内部枚举不直接替代旧数值。
- 定时器携带房间 epoch、局号、版本与阶段标识；触发时重新入邮箱并验证。客户端旧操作/迟到计时器不应作用于下一局。
- 初始规则检查：身份和座位 → 房间/局状态 → 当前轮次或响应窗口 → 自有牌与合法参数 → 牌型/胡型重算 → 计分 → 持久化 → ACK/推送。未通过验证不得修改任何共享状态。
- 洗牌使用服务端 SecureRandom，无放回发牌；随机种子或完整发牌结果仅存在受控恢复日志，不能暴露给客户端。测试可注入固定种子，不得开启生产“指定手牌”接口。

### 6.3 操作确认与数据库 I/O

首期采用可靠优先的提交方式：动作验证生成候选状态，写入房间版本与事件日志，提交成功后才更新可见状态、ACK 并广播。I/O 放有界线程池；该房间在提交中暂停处理后续动作并有界缓冲，其他房间不等待它。提交失败丢弃候选状态，返回兼容失败；提交结果未知时按唯一键查询确认，不直接重复执行副作用。

每条状态变化事务更新 `room_runtime(version)`，检查旧版本及 owner_epoch，并插入唯一事件 `(room_id,event_seq)`；snapshot_json 仅在第 8 节的快照点更新。事件需包含可重放的状态增量及确定性数据（如发牌结果），恢复不能再次洗牌或再次调用账本、领奖等外部副作用。事务争用只在同房间或同账户上发生；持久化延迟纳入动作延迟目标。每动作提交增加数据库开销，千人活跃压测必须真实包含这部分，不能只测内存引擎。

### 6.4 权限与组织范围

权限判定统一为“会话身份 + 操作权限 + 资源归属 + 当前业务状态”，不把圈主、圈管理员、合伙人、代理及平台管理混为一个 admin 标记。

| 身份 | 允许范围与实现要求 |
| --- | --- |
| 普通玩家 | 本人资料/余额/订单/奖励、本人参与的房间和允许访问的回放 |
| 圈成员 | 本圈可见成员、房间及配置允许的战绩；不能操作圈资金或审批 |
| 圈管理员 | 仅明确授予的成员审批/房间/配置操作；敏感圈资产权限单列 |
| 圈主 | 管理本圈授权范围；不能操作其他圈，也不自动具有平台管理权 |
| 合伙人 | 本圈授权层级与成员归属范围；审核权和业绩范围分别检查 |
| 代理/平台管理 | 按管理模块实际角色映射操作和数据范围；后台凭证不复用普通客户端可伪造标记 |

`id/club_id`、`uid/targetUid/member_uid/partner_uid` 通过各路由 DTO 映射为明确资源对象后鉴权，禁止按字段同名推断含义。批量 uids/room_ids 必须逐项校验；权限撤销在下一请求生效，首期修改操作以数据库权限为准，缓存仅做提示与加速。审批/成员转移使用条件更新并记录原归属及操作时间，业绩按结算时归属与规则版本留痕，避免转移后重复计入。

`joinRoom` 同时竞争用户当前房间和目标房间座位，不能只锁目标房间：用 `player_room_binding` 的 UID 唯一约束及事务预占保证跨房间并发唯一，失败回滚预占。代开但不入座不占用该绑定；建房不能把代开人默认为玩家或付款人。

### 6.5 牌局与运营任务的资源隔离

- Netty EventLoop、房间分片 worker、普通业务执行器、后台任务执行器分别有界。房间提交 I/O 采用高优先级调度，报表/回放/奖励扫描采用低优先级队列；禁止在房间串行线程中等待排行榜、外部认证、短信或实物接口。
- 首期 MySQL 共库；房间提交与普通业务/报表用不同有上限的连接池，总连接数按数据库承载能力压测确定。重查询设置超时、分页及并发上限；池满及时返回兼容忙碌错误，不能累积无界等待。
- 圈列表、战绩和管理页查询有分页；配置可短期缓存，余额和资格判定读权威记录。排行榜可用 Redis 有序集合加速，数据来自持久化统计并可重建；奖励资格不得只看缓存排名。
- 赛事分桌、自动开房、自动建设等任务只能调用 room/club 等业务服务，不能绕过身份、幂等、容量和数据库版本检查；关闭建房的发布开关也必须阻止自动开房任务。

### 6.6 结算事件与后台任务

正式对局结算事务写 outbox，异步驱动活动局数/成绩、耗卡积分、合伙人业绩、排行榜及管理汇总。活动按其确认的统计事件处理，不把所有动作都广播给全部运营模块。房间结果与钱包是强一致事实；排行榜/汇总允许最终一致，响应格式仍兼容原客户端。

每个消费者在同一数据库事务中插入唯一 `consumer_receipt(consumer,event_id)` 并更新统计；失败整体回滚。多 worker 用带租期的数据库任务领取、条件更新及重试，不只靠本机定时器或 Redis 锁。超过重试阈值进入人工处理状态并告警；重复发事件、任务重启或超时夺取不能再次加分/发奖。

内部事件至少携带 event_id、业务实体 ID、业务发生时间、规则/配置版本；只有需要统计归属的事件才附圈与成员归属快照。按发生时间归入活动周期，避免延迟消费算到下一天。业务时区首期统一 Asia/Shanghai，周期和刷新时点待各活动规则确认；数据库统一保存 UTC 时间。新增周期不是自动获得发奖资格。

外部短信、认证、兑付和履约通过适配器 + 订单状态处理，不跨网络持有数据库事务。回调验证来源、关联原请求和状态，重复/乱序回调做幂等检查；结果未知先查询对账，不能直接换新单再扣币或发奖。

## 7. 数据库模型与一致性（D）

以下是新库设计，列名采用 snake_case；通过 DTO 映射到前端原字段，不要求客户端修改命名。

| 表 | 主要字段 | 关键约束/索引 |
| --- | --- | --- |
| user_account | id、provider、external_id、status、created_at | UNIQUE(provider,external_id) |
| player_role | uid、account_id、name、head、sex、version | UNIQUE(account_id)；uid 不直接使用客户端传值 |
| wallet_account | uid、currency、balance、version | PK(uid,currency)；非浮点金额 |
| wallet_ledger | id、account_key、currency、delta、balance_after、biz_key、created_at | account_key 含个人/圈主体类型；UNIQUE(account_key,currency,biz_key)；INDEX(account_key,created_at,id) |
| club / club_member | club_id、uid、role、status 等 | 成员 UNIQUE(club_id,uid)；INDEX(uid,status) |
| room_record | room_id、game_id、owner_uid、options_json、status、created_at、ended_at | INDEX(status,created_at)；房号活跃期唯一 |
| room_player | room_id、uid、seat、joined_at | UNIQUE(room_id,uid)、UNIQUE(room_id,seat) |
| room_runtime | room_id、owner_epoch、version、snapshot_seq、snapshot_json、updated_at | PK(room_id)；乐观版本更新 |
| room_event | room_id、event_seq、round_no、kind、payload_json、created_at | PK(room_id,event_seq)；可按时间归档 |
| round_result | room_id、round_no、result_json、settlement_key | UNIQUE(room_id,round_no)、UNIQUE(settlement_key) |
| player_record | uid、room_id、round_no、score、ended_at | UNIQUE(uid,room_id,round_no)；INDEX(uid,ended_at,room_id) |
| replay_index | movie_id、room_id、round_no、object_key、checksum、status、schema_version | UNIQUE(room_id,round_no) |
| outbox_event | id、biz_key、type、payload、status、retry_at | UNIQUE(biz_key,type)；INDEX(status,retry_at) |
| admin_audit | operator_uid、action、target、before_json、after_json、created_at | INDEX(operator_uid,created_at) |

JSON 用于玩法差异数据，账号、时间、状态和查询关联仍用明确列。战绩分页优先时间+ID 游标，旧前端若为页码则适配，不直接更换请求形状。分数以整数/明确精度 DECIMAL 保存，客户端数字范围另行约束。

**结算事务**：锁定受影响账户（按 UID 排序减少死锁）→ 检查 settlement_key → 写单局结果与玩家战绩 → 更新余额/房卡并写账本 → 更新房间状态和事件 → 写 outbox → 提交。超时重试时唯一约束判断已完成，不重复扣费/加分。房卡扣费时点、AA 与房主支付、未开局解散退款规则必须来自配置及业务确认；只确定技术幂等方式，不假设原站收费规则。

**回放归档**：从有序持久化事件构建各游戏旧回放格式，写对象存储并校验后将索引标为 READY；失败重试，不能给客户端返回损坏对象。outbox 不依赖额外消息中间件，由单体后台 worker 抢占与重试。存储内部事件不能直接当作前端旧回放格式，特别注意历史字段 `opreate` 的拼写。

**Redis 键建议**：`game:session:<tokenHash>`、`game:online:<uid>`、`game:online:index`、`admin:session:*`、`rate:<scope>:<id>`、`cache:game-config:<gameId>`。玩家与 Admin 会话分命名空间、不同 Cookie/鉴权入口，不能互认。会话初始 TTL 24 小时，按活跃续期；最终期限须与登录流程联调。Redis 重启可导致重新登录，房间与结算不能因此丢失。启用 AOF everysec 也不意味着零丢失；Redis 缓存不可作为唯一账本。

### 7.1 功能模块补充数据模型

基础表之外，按功能启用下列逻辑表族；首期按模块分批建表，不提前写入未经确认的经济规则。下表为新设计，不声称是原数据库。完整请求字段见功能模块说明书。

| 业务域 | 建议表族 | 核心约束 |
| --- | --- | --- |
| 圈与代开 | club_application、club_config、club_room_link、room_creator_relation、player_room_binding | 申请条件状态迁移；当前占座 PK(uid)；代开人/房主/付款方分离 |
| 圈建设 | club_build_account、club_build_ledger、club_build_application、club_build_config、club_member_comment | 资源变动与流水同事务；申请只允许一次终审；禁止负资源 |
| 圈合伙人 | club_partner、club_partner_member、club_partner_permission、partner_achievement、partner_change_log | 成员当前归属唯一到其圈内关系；统计保留归属和规则版本 |
| 圈资产与兑换 | club_wallet_account、exchange_rate_config、exchange_order | 账户类型 + 主体 ID + 币种唯一；保存成交汇率、精度、双边流水和状态 |
| 道具 | prop_catalog、player_prop、purchase_order | 权威价格与有效期；扣币和发放原子提交；同订单不重复发货 |
| 手机/认证 | phone_binding、sms_challenge、identity_verification、provider_request_log | 挑战场景绑定、期限/次数、一次消费；手机号唯一规则待确认；敏感字段受控保存 |
| 举报 | user_report、report_processing_log | 提交者与展示匿名分离；处理状态条件更新，接口匿名字段待核实 |
| 签到/通用奖励 | checkin_config、player_checkin、reward_entitlement、reward_claim | entitlement 定义资格；资格 + UID 唯一，领取和奖励流水关联 |
| 活动统计 | activity_config、activity_visibility_rule、activity_participation、activity_game_progress、activity_grade、activity_rank、activity_award_ledger | 配置版本固定；参与唯一到活动/用户；统计事件去重 |
| 邀请与锦鲤 | invite_relation、invite_reward_ledger、koi_config、koi_participation、koi_rank、koi_reward_ledger | 自邀/循环关系检查；奖励来源唯一；提取指向具体成熟奖励 |
| 提取与履约 | withdrawal_request、fulfillment_order、freephone_plan、freephone_model、freephone_participation、monthly_card_claim | 冻结额非负；奖励资格仅一笔有效申请；月领唯一到计划/用户/月份 |
| 种树 | sapling_tree、sapling_water_log、sapling_points_ledger、sapling_reward_claim、sapling_rank | 次数键依据活动规则；消费来源事件唯一，奖励不可重复领取 |
| 赛事 | tournament、tournament_registration、tournament_stage、tournament_table、tournament_rank、award_claim | 报名/分桌阶段约束；同赛事用户关系唯一；奖励资格唯一 |
| 管理与代理 | agent_relation、agent_permission、agent_change_log、admin_audit | 数据范围与操作授权分离；财务统计来源可追溯，修改留审计 |
| 异步基础设施 | consumer_receipt、scheduled_job、job_execution | PK(consumer,event_id)；任务业务键唯一，租期/尝试次数/下次重试时间可查询 |

业务列表按其前端 page/limit 兼容分页并设置服务端上限，历史轨迹按实体+时间+ID 建索引。关系数据库承载权威记录；JSON 存玩法及配置差异，不把成员关系、余额、任务状态塞进一张大 JSON 表。

### 7.2 钱包、兑换与奖励生命周期

个人/圈资产遵循同一账户与流水规则，区分账户所有者类型、币种、可用额和冻结额；不同币种不得直接相加。`wallet_account`、`club_wallet_account` 采用相同事务服务，账本业务键必须包含操作种类和订单/资格 ID。

- **兑换/划转**：校验双方身份和权限 → 锁定所涉账户（统一账户键排序）→ 按服务端当前配置生成订单并冻结成交汇率/规则版本 → 检查余额 → 原子扣入双方账户并写关联流水 → 提交后按旧响应刷新。规则与汇率后改不影响已提交订单。
- **道具购买**：创建或定位订单，读取商品权威价格；扣费、player_prop 发放和订单完成同事务。道具续期/重复购买是否允许必须先确认，不能用永久唯一键误阻止合法重复购买。
- **签到/月领/活动奖励**：以服务端业务周期计算资格，唯一约束作用于资格键而非“今天全部请求”；领取、奖励流水与完成标记同事务。关闭活动不能重复生成旧资格。
- **提取/外部兑付**：申请并冻结可提额 → 审核 → 提交外部渠道 → 按确认结果完成或失败释放。外部处理中/结果未知保持冻结并对账；仅明确拒绝或确认失败后释放，不能因超时就退款并另发一笔。不同模块奖品性质仍按确认规则区分。

旧协议的重复兑换、转送或购买若没有自然唯一资格键，也没有客户端可复用订单号，无法通用识别“重试”和“第二笔合法同额操作”。首期对明确非重复业务使用自然键；可重复经济操作在契约冻结阶段补齐可查询、可复用的业务订单机制，并验证当前客户端适配，未解决前不开放相应写操作。不能仅按 UID + 数量 + 短时间窗口宣称精确幂等，也不能生成每次全新的服务端 ID 就视为已防重。

### 7.3 数据保留、对账与恢复

按账本/订单、房间事件/回放、活动明细、短信/认证/举报分别制定保留和访问策略；具体期限作为上线配置与业务确认项，不复刻旧接口 expired 就当作统一政策。敏感字段不进入普通日志、事件广播或回放。

每日执行账户余额与账本、资格与领取、结算与战绩、外部订单与实际渠道结果的对账；差异生成待处理记录及告警，不后台静默改余额。所有修正通过补偿流水和审计完成。清理 room_event 前必须确认所需快照、回放归档和恢复范围，不能只按事件年龄删除未完成房间日志。

### 7.4 Admin 与统计补充表

| 表 | 关键字段/索引 | 一致性要求 |
| --- | --- | --- |
| player_login_event | event_id、uid、occurred_at、business_date、login_kind、is_test；UNIQUE(event_id)，INDEX(business_date,uid) | 登录/重连成功事件留痕，不记录原始令牌 |
| player_login_daily | business_date、uid、first_login_at、last_login_at、login_count、is_test；PK(business_date,uid) | 事件去重与日记录更新同事务；跨日不按消费时间归日 |
| online_stats_minute | sampled_at、game_id、online_count、playing_count、data_status；UNIQUE(sampled_at,game_id) | 每分钟记录有效采样，数据源异常保存状态，不伪造零值 |
| club_stats | club_id、member_count、created_at、updated_at；PK(club_id)，INDEX(member_count DESC,club_id ASC) | 成员计数与正式成员加入/退出同事务；定期核对，不能数申请列表 |
| club_stats_daily | business_date、club_id、completed_round_count、participant_count、card_consumed | PK(business_date,club_id)，按事件幂等更新；distinct 参与人数需明细去重 |
| admin_user | id、username、password_hash、status、permission_version；UNIQUE(username) | 与玩家账户独立，禁用即时生效 |
| admin_role / admin_permission / admin_user_role / admin_role_permission | 角色、操作权限及关联唯一键 | 权限必须在 API 层执行，不能只控制菜单 |
| admin_data_scope | principal_id、scope_type、scope_id | 平台/指定圈/代理团队数据范围；所有列表及导出使用同一限制 |
| admin_operation / admin_approval | operation_id、actor_id、action、target、reason、expected_version、status、reviewer_id | 请求业务 ID 唯一；审批防自审；执行与审计可追踪 |
| config_version | config_key、version、payload、status、created_by、published_by、published_at | UNIQUE(config_key,version)；草稿、发布、回滚保留历史 |
| export_job | id、actor_id、filters、permission_version、status、object_key、expires_at | 任务和文件不公开；下载再次鉴权、权限变更后重新判定 |

后台统计使用 MySQL 的真实记录及统计表，Redis 只加速。不要另建一份可由后台直接修改的玩家余额或圈成员数据。登录日去重记录是权威计数来源，Redis 缓存丢失后从 MySQL 重建。后台大报表和导出不得在列表查询中同步扫描所有房间事件。

## 8. 重连、恢复和发布（D）

### 8.1 重连

校验会话 → `resetfd` 绑定新连接并递增连接 epoch → 旧连接失效 → 查当前未结束房间 → 经现有 returnGame/loginGame/checkGameCreate 流程恢复 → 推送按本人裁剪的 initGame。initGame 的外层字段要按各 Root 消费器输出：不能只返回通用 `game`，还需对应的手牌 `card`、准备 `readys`、角色 `roles` 或操作数据。

切换连接时旧连接关闭事件必须带 epoch，禁止将新连接再次标记离线。恢复期间先入房间邮箱取得一致快照，再从快照版本之后发送事件，避免“旧快照覆盖新推送”。没有客户端可识别序号时，服务端完成有序输出，不要求旧客户端去重。

### 8.2 进程崩溃

从 MySQL 最新快照加载，再按 event_seq 重放后续事件，重建牌堆、手牌、座位、局号、分数、操作窗口和计时截止时间。每 50 个事件及单局结束生成快照；快照记录 schema_version、engine_version。提交前动作没有成功 ACK，恢复后允许客户端重新查询；已提交但未推送的动作通过快照反映。

定时截止时间保存绝对时间，重启后过期任务立即排入对应房间队列，但先验证当前阶段；不能重启就重新给完整倒计时。初始恢复目标 RTO ≤ 5 分钟，已提交动作设计 RPO=0（以数据库可用和提交持久性为前提）；实测故障注入后才能作为上线承诺。禁止使用不兼容引擎版本恢复旧房间，升级先排空旧房间或提供明确迁移。

### 8.3 发布和回滚

健康检查区分进程存活、可接入及数据库写入可用。发布先停止建房与新入场，再等待现有房间完成或保存后维护恢复。Flyway 先加后删，至少保留前一版本可读字段；回滚前验证事件格式与快照兼容性。数据库备份需定期执行恢复演练，记录恢复时间；不能把备份任务成功等同于可恢复。

后台恢复还需重新扫描未完成 outbox、租期到期任务、处理中订单与待对账外部请求；不重新生成签到/月领资格，也不把“进程重启”当作再次发奖依据。

## 9. 压测与验收

### 9.1 设计目标，不是已测结果

| 负载/指标 | 首期目标与方法 |
| --- | --- |
| 连接 | 1,000 个真实 WSS 连接持续 60 分钟，不能只打开 TCP |
| 大厅空闲 | 800 空闲 + 200 活跃，独立统计心跳和业务吞吐 |
| 满活跃 | 1,000 玩家按各游戏合法人数组成房间；各玩法单测，再混合运行 |
| 动作负载 | 常态 200 次/秒，1 分钟突发 500 次/秒；遵守轮次并包含合法和拒绝路径 |
| 服务端动作延迟 | 接到完整帧至响应进入输出队列，P95 ≤ 50ms、P99 ≤ 100ms；含正常持久化 |
| 网络体验 | 独立记录同地域端到端延迟，不能用服务端延迟代替公网体验 |
| 稳定性 | 无回调缺失、重复结算、手牌泄露；队列有界，压测后内存无持续增长 |
| 故障 | 数据库延迟/断连、Redis 重启、杀 Java 进程、慢客户端和重连风暴；核对恢复数据 |

若方案不达标，记录硬件、数据集、版本、活跃房间、牌局阶段和耗时分布，优先消除阻塞、索引缺陷和广播重复序列化，再考虑合并事件写入或拆服务。任何性能优化都须保持 ACK 持久化语义，不能暗中变为“先成功后落盘”。

### 9.2 自动测试与真实多人联调

- 协议契约：黄金请求/响应覆盖每个路由、每个 tag、可选字段缺省、number/string UID、空数组/空对象、错误响应及推送顺序；样本全部脱敏。静态字段索引不能代替这些测试。
- 规则单测：使用真实牌编码及经确认的计分样例；检验牌数守恒、无重复发牌、非法操作不改状态、计分守恒（按收费/抽成规则另核对）。不以客户端牌型提示作为唯一正确答案。
- 集成测试：真实 MySQL/Redis 验证结算唯一约束、并发账户更新、事件版本冲突、恢复与 outbox 重试。使用故障注入覆盖“提交成功但 ACK 丢失”。
- 多人自然整场：11 款分别以足额独立客户端从登录、建房、加入、准备到总结束并返回大厅；中途断开一人并重连。多局验证不能仅手动指定终局状态。
- 专项回归：6003 终局自然成功却响应 `state=-10`、历史末手 `-4`；6009 跨局选牌；6060 首出黑桃 3；6061 发牌阶段操作；麻将普通弃牌与抢杠窗口；6038 五人八局及结算一致。
- 安全与权限：伪造会话、跨房间操作、重复投票、非房主踢人、负数/超长牌数组、查看他人手牌及越权获取回放。

客户端已有测试工具如 `reference/protocol/verify-socket.cjs` 验证网络适配器，不验证新服务端规则正确。本次文档交付不需要执行原客户端联机测试，不把历史原站结果当作 Java 验收。

### 9.3 全模块负载与业务验收

除纯牌桌负载外，增加混合场景：1,000 个 WSS 玩家保持连接，持续 200 次/秒合法牌局动作，同时执行 50 次/秒圈列表/战绩/活动/管理分页查询、10 次/秒资格领取/审批等写入，并处理按 200 次/秒投递的结算衍生事件。事件投递为可重复的独立测试负载，不伪造同等数量的真实终局；各类业务读写分布和数据库写放大分别记录。该组参数是待测设计目标，不是测得容量。

混合负载下仍检验第 9.1 节动作 P95/P99；普通查询初始目标 P95 ≤ 300ms，已提交统计事件到可见结果 P95 ≤ 5 秒。大报表作为有界后台任务，单独记录完成耗时。供应商时延从服务器本地入单耗时中分开统计，不能把外部超时掩盖为服务器成功。

必须覆盖：

- 普通成员、圈管理员、圈主、合伙人、代理和平台管理的交叉权限矩阵，以及批量请求中夹带其他圈目标；撤权后下一请求立即失效。
- 兑换并发、购买重试、领取/审批重复、业务日/月切换、成员转移、汇率/活动配置变更、结算事件重复消费及排行榜重建。
- 外部回调重复/乱序、渠道结果未知、超时后重启、冻结资金对账、后台补偿与审计记录完整性。
- 重报表或排行榜重建期间，房间队列、SQL 池等待、outbox 最老事件年龄、消费者失败/重复数、后台任务积压、账本差异数均有指标和告警；故障时允许统计滞后，不能拖垮牌局提交。
- 19 个模块逐项标记“已实现、查询通过、修改通过、权限通过、事务通过、入口状态”；关闭/隐藏/规则待确认单独登记，不用总通过率遮盖缺口。管理模块单列，不混入 19 项。

## 10. 开发顺序与当前阻塞

| 阶段 | 交付 | 通过条件 |
| --- | --- | --- |
| P0 契约冻结 | 11 游戏、19 模块、公共与管理路由的 DTO/响应消费者、配置枚举及经济订单机制 | 每个 S 字段可追溯；U 项有影响范围；可重复经济写操作解决订单防重 |
| P1 公共框架 | 登录适配、WSS FIFO、房间串行、重连、账本、权限；Admin 登录/布局、玩家查询、登录/在线看板 | 联机队列正常；用户占座唯一；Admin 会话隔离及统计去重通过 |
| P2 五子棋 | 6055 完整玩法与回放 | 两人自然多局、悔棋、认输、总结束及恢复 |
| P3 扑克族 | 6003 → 6014 → 其余跑得快 → 6009 → 6060 | 每款规则样例及自然整场通过；共享代码差异测试 |
| P4 地方规则 | 6056/6057、6061 | 规则确认后实现换牌/听躺/抢杠/多方操作及计分 |
| P5 圈组织业务 | club/openroom/joinGame、clubbuild、clubpartner；Admin 圈列表/成员排序、圈详情、房间及战绩管理 | 圈计数排序、成员/资产权限、审批幂等、统计和房间一致性 |
| P6 经济与运营 | diamond/proppay、activity/yuxiactivity/lsanactivity、qiandao/invite/koi/sapling；Admin 财务/订单/补偿审批、配置版本发布 | 经济规则确认；账本防重、补偿防自审、配置回滚及统计去重通过 |
| P7 条件与外部功能 | shiming/jubao、phonefangka、freephone、match；管理/代理模块 | 查询与修改分别验收；供应商/赛制/授权资料齐备；原关闭入口仅按明确配置开放 |
| P8 上线验收 | 千人游戏 + 10 个 Admin 会话混合压测，告警/备份/对账、恢复与发布排空 | 看板/排序/导出不破坏牌局延迟目标；权限、资金、任务恢复一致 |

当前阻塞需逐项关闭：

1. **认证与 HTTP 配置**：获取本地可追踪的宿主调用链和返回样本，确认 account 的身份来源；没有可信认证前不能开放生产账户注册。
2. **规则与枚举**：11 款实际牌库、人数/局数、首出/必压、特殊牌型、分数与封顶、禁手、胡型和多方响应优先级。尽量继续从本地源码/配置确认，仍无依据的需业务方给规则或授权测试证据。
3. **精确响应结构**：共享 JS 多处仅将响应转交调用方，附录会保留函数证据；必须继续跟踪调用方，不能因 API 函数未读取字段就输出空结果。
4. **回放和战绩**：部分原接口返回 expired，且不同游戏的格式不同。需自建可重复的服务端数据，覆盖当前前端解析器；不要复制过期行为作为默认。
5. **资源和外部服务**：充值二维码等缺失资源、支付/短信/实名凭证及管理权限配置，不影响独立房间内核开发，但相应功能不可标记完成。
6. **全模块业务规则**：圈建设 ore/strength 及自动任务、合伙人/代理范围与业绩、各活动奖励及周期、赛事晋级/奖品、赠手机计划等需逐项确认；不从路由名称推导收益公式。
7. **经济操作幂等**：旧协议未提供订单重试标识的兑换/购买/划转，必须明确兼容的业务订单方案再开放写操作；不能把内部生成 UUID 误认为跨请求防重已完成。

后续编写服务器逻辑时，每个 tag 的完成标准均为：请求 DTO → 权限和状态检查 → 规则计算 → 事务提交 → 兼容 ACK → 分玩家推送 → 重连快照 → 契约/规则/多人测试。禁止只按字段做转发或返回固定成功。

## 11. Admin Web 管理工具（D）

### 11.1 产品范围与技术结构

这是新建的电脑端平台管理工具，不是游戏内代理界面的简单放大版，也不是 Spring Boot Admin 之类仅用于应用监测的控制台。后台复用现有 room、club、economy、activity 等服务，不直接修改数据库绕过业务规则。

前端单独工程采用 Vue 3 + TypeScript + Vite，Element Plus 提供表格/表单/筛选/弹窗，Vue Router 管理页面，Pinia 保存当前账号与权限展示状态，Axios 统一请求与错误处理，ECharts 显示趋势。Spring MVC 提供 REST API，Spring Security + Spring Session Redis 维护后台会话；日常功能不依赖游戏 WSS。

| 菜单 | 查询、筛选和展示 | 管理操作及边界 |
| --- | --- | --- |
| 首页看板 | 今日登录/新增、当前在线/游戏中、活跃房间、当前圈数量、今日对局/房卡消耗；日期与游戏筛选、趋势图 | 默认每 10 秒刷新；每项显示时间与可用状态 |
| 玩家管理 | UID/昵称、注册/登录时间、在线与封禁状态；本人圈、房间、战绩、资产流水 | 封禁/解禁、强制下线；需原因与权限，不展示密码和令牌 |
| 在线监控 | 去重在线玩家、按游戏分布、大厅/牌桌状态、节点、最近活跃时间 | 强制下线同时撤销对应会话并解除连接；不能仅删 Redis 在线项 |
| 房间管理 | 房号/游戏/圈/房主、人数、阶段、局号、离线状态、创建时间 | 授权强制解散进入房间邮箱并走正常结算/退款规则；不能直接删除房间行 |
| 亲友圈管理 | 全部可授权圈；按 ID/名称/圈主/状态筛选，按人数/在线人数/创建时间/对局数排序 | 状态和默认配置调整、授权管理员管理、成员审批/移除；已开局成员变动遵循房间规则 |
| 圈详情 | 成员列表、角色、加入/最近登录时间、在线；房间、战绩、圈资产、建设、合伙人 | 圈配置、成员、建设/归属操作分别鉴权，禁止以“圈管理”单一权限覆盖资金调整 |
| 财务与订单 | 个人/圈账户、账本、兑换/划转/购买/奖励/提取、外部状态、对账差异 | 经审批的补偿/退款；禁止直接编辑余额或删除原账本 |
| 运营配置 | 公告、活动、签到、道具、汇率、游戏建房配置和功能开关 | 草稿→校验→发布；可回滚至历史配置；规则版本不覆盖旧订单 |
| 客服与审核 | 举报、实名状态、绑定奖励、提取、实物履约及处理历史 | 处理、通过/拒绝与备注；显示脱敏信息，证件查看单独授权 |
| 代理与合伙人 | 关系、成员、授权、业绩、团队统计和变更日志 | 按圈/团队范围操作；返利/业绩口径仍需业务确认 |
| 系统运维 | 健康状态、任务队列、失败请求、outbox 延迟、对账告警和导出任务 | 有界重试/处理任务；按原业务键重试，不得创建新奖励规避防重 |
| 系统权限与审计 | 后台账户、角色、操作权限、数据范围、登录及修改记录 | 账号启停、撤权、会话失效；所有敏感动作留痕 |

不提供进行中隐藏手牌查询或指定发牌功能。维护工具不能绕过正常规则改变玩家胜负。

### 11.2 统计口径

统一业务时区为 Asia/Shanghai；数据库保存 UTC 时间。查询日期转换为左闭右开的时间区间，服务端计算边界，不能直接使用浏览器时区。正常看板刷新间隔 10 秒，登录/历史统计缓存最长 10 秒；实时人数使用采样结果，不承诺毫秒一致。

| 指标 | 明确口径与来源 |
| --- | --- |
| 今日登录玩家个数 | 当日成功认证并关联角色的去重 UID；reg/resetfd 成功都可产生事件，同 UID 重连/多设备只计一人；裸 socket 建立、失败登录不计；查询 player_login_daily |
| 今日新增玩家 | 当日首次创建正式 player_role 的去重 UID，与首次登录事件区分 |
| 当前在线人数 | 存在有效已认证连接租约的去重 UID；会话令牌未过期但没有连接不算在线；Redis 在线索引采样 |
| 当前游戏中人数 | 有效在线 UID 中，所在房间处于已开局且未总结算阶段的正式座位玩家；旁观者不计；单局结算等待仍属该房间 |
| 活跃房间数 | 当前 room 状态为已开局且未关闭的房间；另列未开局等待房间；不通过在线连接数推算 |
| 当前亲友圈数量 | 未解散且未停用的圈数；全部/停用/解散分别可筛选，不等于今日有对局的圈 |
| 亲友圈人数 | 正式有效成员数，圈主/管理员作为成员只计一次；不计待审批、已退出/移除成员；来源 club_member 与事务维护的 club_stats |
| 圈在线人数 | 圈有效成员 UID 与同批在线 UID 的交集；一人属于多个圈时可分别计入，跨圈合计不等于全平台在线人数 |
| 今日对局数 | 今日正式提交的单局结算数，按 room_id + round_no 去重；解散未结算不算一局，不用前端动画数统计 |
| 今日房卡消耗 | 当日已提交账本中业务分类为房卡消耗的出账量；退款、赠送、充值和兑换另列，净消耗另算，不能视为现金收入 |
| 亲友圈对局排序 | 默认选定自然日的正式完成局数；筛选日期随查询固定，不与历史累计混用 |

后台账号不进入玩家统计；压测账号通过明确 is_test 标记排除，筛选规则贯穿登录、游戏和圈统计。登录人数不等于每日活跃人数：昨日起保持连接且今天未重新认证的玩家可在线但不计今日登录；如需 DAU 另立活动事件口径。

**在线租约实现**：遵循第 3 节保活参数，连接认证后按最多 30 秒批量刷新 UID/连接 epoch 租约（有效期 90 秒），维护 `game:online:index` ZSET 的过期时间分值。租约到期时刻取最近一次已验证入站活动/Pong 时间加 90 秒；定时刷新只能同步该值，不能凭 socket 尚未关闭就延长失联连接的存活时间。统计先按时间过滤有效项，不用 SCARD 或索引原始长度当实时在线；旧连接关闭只能移除其 epoch，不能误删新连接状态。首期延续同 UID 新连接替换旧连接策略，展示连接数与 UID 数为不同指标。节点崩溃后最多 90 秒收敛。

Redis 重启后在线索引从仍存活的已认证连接重建；若会话丢失按登录流程重新认证。重建中或 Redis 不可用时返回 `REBUILDING/UNAVAILABLE` 及采样时间，数值为 null，不能显示“0 人在线”。在线图历史按每分钟采样持久化，重启前的原始连接无法从 MySQL 日登录记录推断。

### 11.3 亲友圈和成员列表

圈列表字段至少包括 clubId、name、ownerUid、ownerName、status、memberCount、onlineCount、todayRoundCount、createdAt、updatedAt；圈详情链接到成员、房间、账本、建设及合伙人分页页签。

- 默认按 `memberCount DESC, clubId ASC`；支持 memberCount、onlineCount、createdAt、roundCount 白名单排序，拒绝任意 SQL 列/表达式。默认 pageSize=50，最大 200。
- 正式成员加入/退出与 club_stats.member_count 更新同一事务；日对局来源幂等结算消费。成员列表按 UID、角色、加入时间、最近登录时间、在线状态筛选和排序，默认 joinedAt DESC、UID ASC。
- 人数/创建时间查询使用对应索引；圈名称包含搜索在首期有界查询，不宣称普通 B-tree 可优化任意包含匹配。统计异常时可按正式成员关系重算并记录修复审计。
- 按 onlineCount 排序必须先在授权候选圈范围完成一次批量聚合后再分页，不能“数据库先取一页再按在线数排”。千人起步按全批在线 UID 与有效圈成员关系聚合，10 秒采样缓存；本次采样的圈结果按 snapshotId 保留 60 秒供翻页，过期明确要求刷新。其他实时列表允许刷新后数量变化，不承诺跨时刻分页快照一致。
- onlineCount 不可用时不伪造为零、不静默换排序，页面提示暂不可用；成员计数和其他 MySQL 字段仍可查询。响应携带 asOf、数据状态及快照标识，前端展示统计时效。

### 11.4 Admin API 与命令流程

独立路径 `/admin/api/v1`，新 API 使用 JSON，数值范围可能超过 JS 安全整数的实体 ID 输出字符串。下表为新增设计，与旧游戏 WSS 字段分开；同一业务事实仍由共享服务产生。

| API 资源 | 用途 |
| --- | --- |
| `/auth/csrf`、`/auth/login`、`/auth/logout`、`/auth/me` | CSRF 初始化、登录/退出、当前身份与权限；登录/退出用 POST |
| `GET /dashboard/overview`、`GET /dashboard/trends` | 概览及日期范围趋势，返回统计日期/时区/采样时间与可用状态 |
| `GET /players`、`GET /players/{uid}` | 玩家分页、详情；关联圈/战绩/订单用各子资源分页 |
| `GET /online/players`、`GET /rooms`、`GET /rooms/{roomId}` | 在线及房间查询 |
| `GET /clubs`、`GET /clubs/{clubId}`、`GET /clubs/{clubId}/members` | 圈筛选、人数排序及成员分页 |
| `/accounts`、`/ledger`、`/orders`、`/approvals` | 财务查询、审批；写操作要求专属权限和请求 ID |
| `/configs`、`/configs/{key}/versions` | 草稿、发布和回滚命令，版本号必须参与并发校验 |
| `/reports`、`/reviews`、`/agents`、`/partners` | 举报、审核、代理/合伙人及其授权操作 |
| `POST /commands`、`GET /commands/{operationId}` | 封禁/解禁、强制下线、强制解散等命令的提交和结果查询 |
| `POST /exports`、`GET /exports/{jobId}`、`GET /exports/{jobId}/download` | 异步导出、任务状态、重新鉴权下载 |
| `/system/users`、`/system/roles`、`/system/jobs`、`GET /audit-logs` | 后台账户权限、任务查询/授权重试与审计 |

列表请求使用 page/pageSize、sortBy/sortOrder 和该资源的白名单筛选字段，返回 items、total、page、pageSize、asOf；依赖实时数据时额外给 dataStatus 和 snapshotId。错误使用相应 HTTP 状态及 code/message/traceId；401 会话失效、403 越权、409 版本或状态冲突，不将 Admin 错误码写回旧游戏协议。后台 DTO 单独定义，禁止输出数据库内部敏感字段。

新增后台写 API 要求客户端生成可复用的 `Idempotency-Key`，超时重试复用原值；服务端将账号、动作、请求体摘要与结果绑定，相同键不同内容拒绝。命令携带 target、reason、expectedVersion；强制解散先返回 202/operationId 表示已受理，不表示已完成，页面轮询结果。命令入房间邮箱，完成结算与状态持久化后更新结果，重复命令不重复退款。新 Admin 可定义业务 ID，不改变旧客户端经济防重仍待解决的边界。

### 11.5 会话、权限和管理操作

- Spring Security + Spring Session Redis 使用后台独立会话；Nginx 同源部署，Cookie 使用 HttpOnly、Secure、SameSite=Lax，登录轮换 Session ID。保留 CSRF 防护，Axios 按约定携带 CSRF token；token 仅用于 CSRF，不是身份凭证。认证令牌不进入 localStorage。
- 初期会话空闲 30 分钟失效、登录绝对期限 8 小时；退出、禁用或撤权后清理相关会话并在 API 校验账号/权限版本。密码仅保存安全哈希，首次管理员通过受控初始化创建，不内置默认密码。
- 预设超级管理员、运营、客服、财务、技术运维、只读审计角色；权限以操作码定义，例如 player.read、player.ban、club.read、room.disband、finance.read、finance.compensate、config.publish。菜单隐藏只是界面体验，API 必须重新校验操作和数据范围。
- 财务查询、资产补偿与审核分开授权；资金补偿需申请人与复核人不同，包含原订单/账本依据、币种数量和原因，批准后通过 economy 事务写正式补偿流水。禁止直接改 balance、删除历史流水或后台“指定胜者”。
- 公告/活动/汇率/开关使用配置草稿、校验、发布和版本回滚；影响已开局规则的配置只对新房间生效。回滚生成新版本，不修改已经成交订单和历史局结果。
- 封禁策略默认阻止新登录/加入，并撤销有效会话、断开连接；已参与牌局按正常离线/托管处理，不直接没收余额。强制解散必须按已确认规则执行结算/退款。
- 手机号、实名材料默认脱敏，查看完整信息需额外权限并审计；后台不提供当前隐藏手牌查看。审计记录 actor、action、target、reason、时间、请求/操作 ID、前后业务摘要与结果，禁止记录密码、会话令牌和完整证件。

会话持久化机制参考 [Spring Security 官方文档](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html)。Redis 中后台会话丢失时重新登录，不回退为免认证访问。

### 11.6 查询、导出与性能

MySQL 为成员、财务和登录统计权威源；Redis 负责低成本刷新。后台 HTTP 执行器、普通查询/报表连接池与房间提交资源有界隔离。圈/玩家分页不 JOIN 全部账本历史，订单流水使用时间索引及限定范围；收益报表从可追溯汇总读取。

导出通过数据库任务表执行，最大并发初始为 1，采用流式读取与 CSV 写入；防止以 `= + - @` 等开头的用户文本被表格工具解释为公式。文件保存到私有 OSS，完成后展示有效期 24 小时的下载入口；下载校验当前身份和数据范围，再签发短效链接或代理返回。任务生成和下载两次校验权限，撤权后不能继续领取旧任务文件。不得把过滤器放宽为全平台导出。

前端静态构建单独发布；API 与页面只传业务数据，不向浏览器暴露数据库、Redis 或 OSS 永久凭证。Actuator 和 Grafana 属于受控运维面，业务 Admin 展示授权聚合指标，不直接暴露原始环境与任意管理端点。

### 11.7 验收与交付边界

- 统计：同 UID 多次登录/重连不增加今日人数；失败登录不计；跨日按发生时间；今天在线但未重登与今日登录区分；测试账号排除。日登录数据重放/Redis 重启后可恢复。
- 在线：正常离线、异常断线、旧连接关闭晚于新连接、节点崩溃和 Redis 重建；90 秒内收敛，异常显示不可用而非零；人数与在线列表同采样口径。
- 圈列表：申请不计成员，加入/移除/解散状态正确；人数排序含同值稳定顺序；在线排序先聚合再分页，快照过期可恢复；多人同时入退圈不出现负计数。
- 权限：菜单/API 双重检查、跨圈/跨代理查询、财务补偿防自审、撤权后下一请求和导出下载失效；Session/CSRF 保护及重复写请求均验证。
- 管理：命令受理与完成状态区分；重复强制解散不重复退费；配置回滚不改历史订单；禁止直接编辑资产；审计能关联命令、审批和流水。
- 混合负载：在第 9.3 节基础上增加 10 个并发 Admin 会话，每 10 秒刷新看板，同时进行圈人数/在线排序、玩家查询和异步导出；仍保持原牌局 P95/P99 目标。Admin 普通查询 P95 ≤ 300ms，超出重报表单独记录，不用查询缓存掩盖统计时效。
- 自动检查：JUnit 5 验证指标与权限规则，Testcontainers 使用 MySQL/Redis 验证事务、统计重建和幂等；Vitest 覆盖组件数据状态，Playwright 验证登录、看板、筛选排序、管理命令与导出权限。工程初始化先验证这些工具与 Boot BOM 的兼容组合再固定版本。

交付顺序为 Admin 会话/权限/布局 → 玩家与登录在线只读看板 → 圈/成员/房间查询及排序 → 日常管理命令 → 财务审批和运营配置 → 审核/代理/运维完善。本文仅补充设计，尚未生成可运行 Admin 网站或 Java 实现，也未执行上述容量和联机测试。

## 12. 本次对照修订结论

技术选型与千人起步部署保留；原方案对 11 款游戏已有较细字段设计，本次按功能模块说明书补齐了全部 19 个注册模块、公共能力与独立管理模块的架构归属。新增圈/合伙人/代理权限、运营表族、统一经济状态、结算事件消费、后台任务恢复与混合压测，并细化实施里程碑。

同时修正两个容易导致实现偏差的边界：内部兑换不能仅当成外部接口占位；对象存储回放不能要求旧客户端未经适配就改用 URL 下载。本次仅修订设计文档，未实现 Java 服务端、未运行容量测试，新增设计不代表未确认业务规则已被补齐。

本轮进一步确定 Java/Netty/MySQL/Redis 与 Vue/Element Plus Admin 技术栈，新增 Admin 功能矩阵、统计口径、REST API、鉴权、统计数据表、在线恢复、排序导出及验收；对应设计集中在第 2、7.4、10、11 节。
