# Vertex

Vertex 是一个**面向加密货币量化交易的一体化后台**，覆盖行情订阅 → 策略评估 → 信号产出 → 自动 / 手动下单 → 回测验证 → AI 二意见分析的完整闭环，外加链上代币筛选 / 告警。

后端按业务拆分多 service 模块，但所有 service 作为依赖被 `web/admin-web` 合并打包成**单一可执行 fat jar**，部署体验等同单体应用，不需要起多个进程。前端是独立的 React 19 + Vite 项目。

## 功能一览

策略管理（指标可视化配置、买卖条件组合、ATR / 分批 / 移动止损止盈）
信号监控（实时推送 + 历史查询、按交易所 / 标的 / 周期过滤、Telegram 通知）
回测引擎（按内容哈希做结果缓存、命中即毫秒返回；支持快速 7d/30d/90d 预设）
**AI 分析（Phase 1）**：异步对每条信号 / 每笔回测 trade 调用大模型给二意见，**不参与交易决策**，仅供事后审查；支持 Gemini / DeepSeek 双 provider 切换、中英文等多语言输出
交易执行（PAPER / 实盘双模式、Binance 现货 + 合约、滑点模拟、用户级最大单笔金额上限）
链上分析（BSC / Solana / Binance Alpha 多源筛选、自定义评分维度、告警 Telegram 推送）
基础设施（基于 RBAC 的菜单 / 角色 / 用户管理、动态权限、i18n 中英文切换、WebSocket 自动重连 + 异常 Telegram 告警）

## 项目结构

```
vertex/
├── api/                              # 跨模块 RPC 接口定义（无业务实现）
├── common/                           # 公共模块
│   ├── common-core/                  # 核心工具、基础实体、异常处理
│   └── common-web/                   # Web 公共组件（统一响应、全局异常）
├── model/                            # 数据模型（Entity / DTO / VO）
│   └── src/main/java/com/vertex/model/vo/ai/   # AI 分析相关 VO
├── service/                          # 业务服务（被 admin-web 打包为单体）
│   ├── user-service/                 # 用户 / 角色 / 菜单 / 通知配置 / 个人设置
│   ├── order-service/                # 交易执行 / 持仓 / 订单 / 止损止盈
│   ├── product-service/              # 产品服务（预留）
│   ├── quote-service/                # 行情订阅 / K 线存储（RocksDB）/ 重连补全
│   ├── strategy-service/             # 策略评估 / 信号 / 回测 / AI 分析
│   │   └── src/main/java/com/vertex/service/strategy/ai/  # AI Provider、Prompt、Store
│   └── chain-analysis-service/       # 链上代币 / 告警规则
├── framework/
│   └── socket-framework-starter/     # WebSocket 客户端 + 自动重连 + 告警 hook
├── web/
│   └── admin-web/                    # 管理后台入口（唯一可执行 fat jar）
├── vertex-ui/                        # 前端项目（React 19 + Vite）
│   └── src/pages/ai/                 # AI 仪表盘 / AI 运行状态页面
├── sql/                              # 数据库脚本（V1 ~ V19 增量迁移 + init_menus 等）
├── gradle/                           # Gradle wrapper + libs.versions.toml
├── build.gradle / settings.gradle    # 根构建配置
└── gradlew / gradlew.bat
```

## 后端

### 技术栈

Java 21 / Spring Boot 3.2.1 / MyBatis Plus / MySQL 8 / RocksDB（K 线 + AI 缓存）/ OkHttp（HTTP 客户端）/ fastjson2 / Lombok / Gradle 8.5（wrapper 自带）

### 构建

环境要求：JDK 21（`sourceCompatibility = 21`，低版本无法编译）。Gradle Wrapper 自带 8.5，首次构建会自动下载到 `~/.gradle/`；阿里云 Maven 镜像已在 `build.gradle` 配置，国内拉依赖无需额外配置。

```bash
# 完整构建（含单元测试，首次推荐）
./gradlew clean build

# 仅打包可执行 jar（跳过测试，CI / 生产推荐）
./gradlew clean :web:admin-web:bootJar -x test

# 增量重建（依赖未变动时最快）
./gradlew :web:admin-web:bootJar

# 仅编译验证不打包（最快，本地代码自查）
./gradlew :web:admin-web:compileJava
```

构建产出位于 `web/admin-web/build/libs/admin-web-1.0.0.jar`：Spring Boot fat jar，合并了所有 service 模块。子模块的 plain jar 在 `admin-web/build.gradle` 中已通过 `jar { enabled = false }` 禁用，避免无用空包。

### 运行

**本地开发**

```bash
# Gradle 任务运行（适合调试，热重启需配合 IDE）
./gradlew :web:admin-web:bootRun

# 指定 profile
./gradlew :web:admin-web:bootRun --args='--spring.profiles.active=dev'
```

默认走 `web/admin-web/src/main/resources/application.yaml` + `application-dev.yaml`。

**生产部署**

```bash
java -Xms512m -Xmx2g \
     -jar web/admin-web/build/libs/admin-web-1.0.0.jar \
     --spring.profiles.active=prod

# Linux 后台启动 + 日志重定向
mkdir -p logs
nohup java -Xms512m -Xmx2g \
    -jar web/admin-web/build/libs/admin-web-1.0.0.jar \
    --spring.profiles.active=prod \
    > logs/admin-web.log 2>&1 &
echo $! > logs/admin-web.pid

# 停止
kill $(cat logs/admin-web.pid)
```

### 关键配置（application.yaml）

`spring.datasource.*` ─ MySQL 连接信息（`application-dev.yaml` 走本机 33306）
`spring.data.redis.*` ─ Redis 连接（仅 cache / session 用，K 线 / AI 不依赖 Redis）
`vertex.jwt.secret` / `expiration-seconds` ─ JWT 签名密钥与有效期
`vertex.quote.subscription.mode` ─ `kline` 仅订阅 K 线流 / `trade` 拉 trade 流再聚合（默认 `kline`）
`vertex.strategy.rocksdb.data-dir` ─ K 线 / AI RocksDB 数据目录（默认 `./data/rocksdb/strategy`）
`vertex.strategy.engine.max-kline-history` ─ 单 (exchange, symbol, interval) 内存窗口上限（默认 500）
`vertex.strategy.engine.only-closed-klines` ─ 是否只对已收盘 K 线触发评估（默认 true）
`vertex.strategy.telegram.*` ─ 信号 Telegram 推送 bot-token / chat-id / api-url
`vertex.ai.*` ─ 详见下文 **AI 模块** 一节
`vertex.trading.*` ─ Binance API 地址、滑点开关、默认执行模式（`PAPER` / `REAL`）
`vertex.chain.*` ─ 链上扫描相关（BSC / Solana / Binance Alpha / BSC Trending）

### 关键环境变量

下列变量可在生产部署时覆盖 `application.yaml` 默认值，避免改文件后重新打包：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `VERTEX_JWT_SECRET` | `vertex-admin-secret-key-change-in-production` | JWT HMAC 密钥，**生产必改** |
| `VERTEX_JWT_EXPIRATION` | `86400` | Token 有效期（秒） |
| `VERTEX_QUOTE_SUBSCRIPTION_MODE` | `kline` | 行情订阅模式 |
| `VERTEX_AI_PROVIDER` | `gemini` | AI provider：`gemini` / `deepseek` |
| `VERTEX_AI_LANGUAGE` | `zh-CN` | AI 自由文本输出语言 |
| `VERTEX_AI_GEMINI_ENABLED` | `false` | 是否启用 Gemini provider |
| `VERTEX_AI_GEMINI_API_KEY` | *(empty)* | Google AI Studio 申请 |
| `VERTEX_AI_GEMINI_MODEL` | `gemini-2.0-flash` | 默认 flash；可改 `gemini-2.5-pro` |
| `VERTEX_AI_GEMINI_BASE_URL` | `https://generativelanguage.googleapis.com` | 中国大陆可换 CF Worker 反代 |
| `VERTEX_AI_DEEPSEEK_ENABLED` | `false` | 是否启用 DeepSeek provider |
| `VERTEX_AI_DEEPSEEK_API_KEY` | *(empty)* | platform.deepseek.com 申请 |
| `VERTEX_AI_DEEPSEEK_MODEL` | `deepseek-chat` | `deepseek-chat` 通用 / `deepseek-reasoner` 推理 |
| `VERTEX_AI_DEEPSEEK_BASE_URL` | `https://api.deepseek.com` | OpenAI 兼容协议 |
| `VERTEX_STRATEGY_TG_BOT_TOKEN` | *(yaml 内默认)* | 策略信号 Telegram bot |
| `VERTEX_STRATEGY_TG_CHAT_ID` | *(yaml 内默认)* | 信号推送目标 chat |
| `VERTEX_CHAIN_TG_BOT_TOKEN` | *(yaml 内默认)* | 链上分析 Telegram bot |
| `VERTEX_CHAIN_TG_CHAT_ID` | *(yaml 内默认)* | 链上告警目标 chat |

## AI 模块

Phase 1 设计：**只做 AI 分析、不参与交易决策**；异步执行；失败 / 超时一律降级 no-op，不阻塞主流程。Phase 2（AI 参与下单决策）见 `AiAnalysisService` 中预留的扩展接口，目前未启用。

### Provider 切换

启用 Gemini（默认）：

```yaml
vertex:
  ai:
    provider: gemini
    gemini:
      enabled: true
      api-key: <your_key>
      model: gemini-2.0-flash
```

启用 DeepSeek（国内可直连，无需反代）：

```yaml
vertex:
  ai:
    provider: deepseek
    deepseek:
      enabled: true
      api-key: sk-...
      model: deepseek-chat
```

切换 provider 或 model 后**必须重启服务**才能生效（Spring 配置是启动期绑定）。启动后看日志：

```
[AiAnalysisService] activated: provider=deepseek, model=deepseek-chat, threads=2, queueCapacity=2000
```

也可访问 `GET /admin/ai/status` 或菜单「AI 分析 → AI 运行状态」校验。

### 输出语言

`vertex.ai.language=zh-CN`（默认）/ `en` / `ja` / `ko` / 任意 BCP-47 标签。仅影响 `summary` / `keyFactors` / `risks` / `entryFactors` / `exitFactors` / `improvements` 这些自由文本字段；`verdict` / `alignment` / `marketRegime` / `suggestedAction` 等枚举值始终是英文 key（前端 i18n 翻译展示）。

### 数据持久化

AI 分析结果统一落 RocksDB（与 K 线共用 ColumnFamily，按 key 前缀分区）：

```
ai:rt:{signalId}                          → AiSignalAnalysis JSON
ai:bt:res:{cacheKey}                      → BacktestResultVO JSON  （回测结果缓存）
ai:bt:trade:{cacheKey}:{tradeIdx 4位}     → AiTradeAnalysis JSON
ai:bt:prog:{cacheKey}                     → AiBacktestAnalysisProgress JSON
```

### 入口位置

「信号监控」每行的 🤖 列 ─ 单信号 inline 触发 / Popover 完整展示
「信号监控」详情 Modal 底部的 `SignalAiCard` ─ 长版完整分析
「策略配置 → 回测」面板顶部的 AI 进度条 + 每条 trade 的 🤖 Popover
**菜单「AI 分析 → AI 仪表盘」** ─ 跨策略 / 跨回测查看最近 AI 分析，按 alignment / suggestedAction / verdict 筛选
**菜单「AI 分析 → AI 运行状态」** ─ 查看 provider / model / 线程池堆积，每 5s 自动刷新

### 风险与设计权衡

**策略规则不发给 AI**：`AiPromptBuilder` 只读取 `exchange / symbol / interval / stopLossPct / takeProfitPct / takeProfitPct1/2/3 / takeProfitSize1/2/3`，以及指标的**计算值**（不是公式）和近期 K 线。`indicatorConfigs`、`exitIndicatorConfigs` 等含策略规则的字段**绝不外发**，避免策略逻辑泄漏。
**回测 trade 分析的价格可能与 K 线 close 不一致**：止损 / 分批止盈触发时 trade.exitPrice 用的是 stop 价 / TP 价而非 K 线 close，这是回测引擎设计，不是 bug；AI 的 narrative 应以 trade record 字段为准。
**实盘信号 AI 分析价格一致**：实盘信号 `price = signalKline.close`，AI 几秒内拉同一根 K 线对照，精度位以内严格一致；不会出现回测端的"价格漂移"现象。
**AI 故障降级**：LLM 超时 / API 5xx / 队列满 → 仅记录失败、不重试主流程，主路径（交易、信号入库、Telegram 推送）完全不受影响。

详细模型 / Prompt 设计见 `service/strategy-service/src/main/java/com/vertex/service/strategy/ai/AiPromptBuilder.java`。

## 数据库

### 表清单

系统表（`sql/system_tables.sql`）：

`sys_user` ─ 用户
`sys_role` ─ 角色
`sys_menu` ─ 菜单（动态权限 source of truth）
`sys_role_menu` ─ 角色-菜单关联
`sys_user_role` ─ 用户-角色关联

业务表（详见 `sql/strategy_tables.sql` / `sql/chain_analysis_tables.sql`）：

策略：`strategy`、`signal`、`exchange_account`、`exchange_symbol`、`user_setting`
交易：`order_record`、`position`、`trade_record`
链上：`chain_token`、`chain_alert_rule`、`chain_source_config`

### 增量迁移

`sql/V<N>_<description>.sql` 是增量脚本，**按版本号升序**执行未应用过的部分（项目暂未集成 Flyway / Liquibase，需手工或自建工具）。当前迁移列表：

| 版本 | 内容 |
|---|---|
| V1 | 系统基础表（`system_tables.sql` / `strategy_tables.sql`） |
| V2_trading | 交易模块表 |
| V2_user_lock | 用户锁定字段 |
| V3_signal_unique | 信号唯一约束 |
| V4_notify_config | 通知配置 |
| V5_futures | 合约扩展 |
| V6_atr_stop | ATR 止损 |
| V7_min_signal_strength | 最低信号强度阈值 |
| V8_trailing_atr_stop | ATR 移动止损 |
| V9_exit_conditions | 自定义出场条件 |
| V10_trailing_drop_stop | 峰值回撤止损 |
| V11_exchange_symbol | 交易所交易对管理 |
| V12_fix_soft_delete | 软删除字段统一 |
| V13_widen_symbol_columns | 拓宽 symbol 列 |
| V14_daily_loss_limit | 日亏损限制 |
| V15_pause_on_stop_loss | 触发止损后暂停 |
| v16 | SuperTrend 止损偏移百分比 + 持仓字段扩展（文件名 `v16.sql` 历史小写遗留） |
| V17_staged_take_profit | 分批止盈 |
| V18_user_setting | 个人设置 |
| **V19_ai_menus** | **AI 仪表盘 / AI 运行状态菜单纳入 sys_menu**；自动给 `code='administrator'` 角色授权 |

执行示例：

```bash
mysql -u<user> -p<password> <database> < sql/V19_ai_menus.sql
```

初始化菜单 / 权限码：先执行 `sql/init_menus.sql`，再执行 `sql/update_menu_permissions.sql`，最后按版本号增量执行 V 脚本。AI 菜单已在 V19 自动绑定到管理员角色，自定义角色需在「系统管理 → 角色管理」勾选菜单 6 / 61 / 62。

## 前端

### 技术栈

React 19 / TypeScript / Vite / Ant Design 5 / React Router 6 / React i18next / dayjs / fastjson 兼容的 JSON 处理

### 功能特性

多语言（zh-CN / en-US，键名 `text.<module>.<key>`）
多环境（`.env.development` / `.env.test` / `.env.production`）
RBAC 菜单 + 路由守卫（`PermissionGuard`）：未授权访问直接 403
统一 API 封装（`utils/request.ts`，自动带 `Authorization` 头）
WebSocket 客户端复用、不重复 reconnect

### 运行

```bash
cd vertex-ui

# 安装依赖
npm install

# 启动开发服务器（默认 http://localhost:5173）
npm run dev

# 构建生产版本（含 tsc -b 严格类型检查）
npm run build

# 仅类型检查（不打包，用于 CI / 提交前自查）
./node_modules/.bin/tsc --noEmit
```

### 目录约定

```
vertex-ui/src/
├── api/             # 与后端 REST 接口一一对应的 wrapper
├── components/      # 通用组件（Layout / PermissionGuard / NotificationBell / 等）
├── contexts/        # React Context（PermissionContext 等）
├── hooks/           # 自定义 hooks
├── i18n/            # 多语言（zh-CN.json / en-US.json + index.ts）
├── pages/
│   ├── ai/          # AI 仪表盘 + AI 运行状态
│   ├── strategy/    # 策略配置 / 信号监控 / 回测面板
│   ├── trading/     # 交易账户 / 订单 / 持仓 / 盈亏 / 币对
│   ├── chain/       # 链上代币 / 告警规则 / 源配置
│   ├── quote/       # 行情源 / K 线查询
│   ├── user/role/menu/   # 系统管理
│   └── guide/       # 策略指南
├── router/index.tsx # 路由表
├── types/           # 全局 TypeScript 类型
└── utils/           # 通用工具（date / request / 等）
```

## 菜单与权限

权限完全由 `sys_menu` 表驱动：

后端 `getUserMenus` 返回当前用户可访问的菜单树（按 `sys_user_role` → `sys_role_menu` → `sys_menu` 关联）。
前端 `PermissionContext` 在登录后加载这棵树，把所有 `path` 收集成 `allowedPaths: Set<string>`。
`MainLayout` 用 `allowedPaths` 过滤左侧菜单；`PermissionGuard` 用 `allowedPaths` 拦截路由。
菜单类型：`type=0` 目录、`type=1` 叶子页面、`type=2` 按钮级权限。叶子菜单的 `permission` 字段对应后端 `@RequiresPermission(...)` 注解。

新增菜单流程：

1. 在 `sql/` 下加一条 `V<N>_xxx.sql`，按 `init_menus.sql` 风格 INSERT 一行 `sys_menu`，配上 `permission` 字段。
2. 同时 INSERT 一行 `sys_role_menu`，给至少 `administrator` 角色授权（参考 `V19_ai_menus.sql`）。
3. 在 `vertex-ui/src/components/Layout/MainLayout.tsx` 的 `menuItems` 数组里加对应 Antd 菜单节点，key 与 sys_menu.path 一致。
4. 在 `vertex-ui/src/router/index.tsx` 加 Route，用 `<PermissionGuard path="/...">` 包一层。
5. 后端 controller 上加 `@RequiresPermission("xx:yy")`，permission 码与 sys_menu.permission 一致。

## 常见问题

**Q: 启动后 AI 不工作 / `[AiAnalysisService] No AI provider registered`**

检查 `vertex.ai.provider` 与对应 `vertex.ai.{provider}.enabled=true` 是否都配上了，且 `api-key` 非空。两个条件缺一个 Bean 都不会注册，所有 AI 调用静默降级。访问 `/admin/ai/status` 看 `aiEnabled` 字段确认。

**Q: 修改了 `vertex.ai.model` 但日志里还是旧 model**

Spring 配置是启动期绑定。要么重启服务，要么接 Spring Cloud Config + `@RefreshScope`（项目暂未集成）。`AiClient.currentModel()` 内部每次都直接读 `aiProperties.getXxx().getModel()`，所以重启后改了立刻反映在新调用上、新结果的 `analysis.model` 字段、`/admin/ai/status` 接口。

**Q: 回测 AI 分析里看到的价格跟 trade record 对不上**

正常现象，主要原因：止损 / 分批止盈触发的 `exitPrice` 是 stop / TP 价而非 K 线 close；策略 interval 在回测后被改过；KLineStore 缓存窗口外的老回测查不到原始 K 线。详见 **AI 模块 → 风险与设计权衡** 一节。实盘信号 AI 分析不会出现此问题。

**Q: 信号生成正常但 Telegram 没推送**

检查 `vertex.strategy.telegram.enabled=true` 且 `bot-token` / `chat-id` 已填。bot-token 必须包含冒号（格式 `数字:字母数字串`）；chat-id 群组以 `-100` 开头。日志里搜 `[SignalTelegramNotifier]` 看发送结果。

**Q: WebSocket 一直断线 / 反复重连**

`framework/socket-framework-starter` 会自动重连，最多 N 次后停止。同时 `WebSocketAlertNotifier` 会按"首次断线" / "重连失败"两种状态分别发 Telegram 告警（复用信号 TG 配置）。如果反复重连，多半是 Binance Websocket 端 ban IP 或网络抖动；检查 `logs/admin-web.log` 里 `[WebSocketClient]` 行。

**Q: 前端 `npm run build` 报 `noUnusedParameters`**

我们前端开了 strict 模式。未用参数前缀加下划线（`_foo`）或直接删掉；render 函数尾部参数可省略。

**Q: AI 队列堆积 / `queueSize` 越来越大**

进「AI 分析 → AI 运行状态」看堆积。可能原因：信号产生速度 > AI 调用速度（提高 `vertex.ai.worker-threads` 从 2 调到 4-8）；LLM 经常超时（提高 `vertex.ai.{provider}.timeout-seconds` 或换 provider）；API 限流（按 provider 文档调用上限节流）。`rejectedTaskCount > 0` 说明队列满后丢弃了任务，影响事后审查但不影响交易。

## 后续规划

短期：把回测时使用的 `interval` 持久化到 `BacktestResultVO`，AI 分析时优先用它，避免策略 interval 改动后 K 线时间窗口错位（已识别但未实现）。
中期：Phase 2 AI 参与信号决策 —— 接入 `AiAnalysisService.shouldAutoTrade(...)` 做"AI 一票否决自动下单"（设计文档见 issue / 内部 PLAN）。
长期：vertex-ui 拆离独立仓库；后端 service 模块按需做独立部署（拆 `admin-web` fat jar 为多 jar）。

## 许可证

内部项目，未发行。
