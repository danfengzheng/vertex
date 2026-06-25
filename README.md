# Vertex 项目

Vertex 是一个前后端统一管理的微服务项目。

## 项目结构

```
vertex/
├── api/                              # 跨模块 RPC 接口定义（无业务实现）
├── common/                           # 公共模块
│   ├── common-core/                  # 核心工具、基础实体、异常处理
│   └── common-web/                   # Web 公共组件（统一响应、全局异常）
├── model/                            # 数据模型（Entity / DTO / VO）
├── service/                          # 业务服务（被 admin-web 打包为单体）
│   ├── user-service/                 # 用户 / 角色 / 菜单 / 通知配置 / 个人设置
│   ├── order-service/                # 交易执行 / 持仓 / 订单 / 止损止盈
│   ├── product-service/              # 产品服务（预留）
│   ├── quote-service/                # 行情订阅 / K 线存储 / 重连补全
│   ├── strategy-service/             # 策略评估 / 信号 / 回测
│   └── chain-analysis-service/       # 链上代币 / 告警规则
├── framework/                        # 基础框架
│   └── socket-framework-starter/     # WebSocket 客户端 + 自动重连
├── web/                              # Web 应用入口
│   └── admin-web/                    # 管理后台（唯一可执行 fat jar）
├── vertex-ui/                        # 前端项目（React 19 + Vite）
├── sql/                              # 数据库脚本（V1 ~ V18 增量迁移）
├── gradle/                           # Gradle wrapper + libs.versions.toml
├── build.gradle / settings.gradle    # 根构建配置
└── gradlew / gradlew.bat             # Gradle wrapper 启动脚本
```

## 后端项目

### 技术栈
- Java 21
- Spring Boot 3.2.1
- MyBatis Plus
- MySQL
- Gradle

### 模块说明
- **api**: 服务接口定义
- **model**: 数据模型（Entity、DTO、VO）
- **common-core**: 核心工具类、基础实体、异常处理
- **common-web**: Web 公共组件（统一响应、异常处理）
- **service**: 业务服务实现
- **web**: Web 应用入口

### 后端打包

后端采用 Gradle 多模块**单体部署**架构：所有 service 模块（user-service / order-service / quote-service / strategy-service / chain-analysis-service / product-service）作为依赖被 `web/admin-web` 合并打包成**单一可执行 fat jar**，无需单独部署各微服务。

#### 构建环境要求

- **JDK 21**（项目 `sourceCompatibility = 21`，低版本无法编译）
- **Gradle Wrapper 自带**：无需本地安装 Gradle，首次构建会自动下载 Gradle 8.5 与依赖到 `~/.gradle/`
- macOS / Linux 用 `./gradlew`，Windows 用 `gradlew.bat`
- 阿里云 Maven 镜像已配置（`build.gradle` 中），国内构建无需额外配置

#### 打包命令

```bash
# 完整构建（含单元测试，首次构建推荐）
./gradlew clean build

# 仅打包可执行 jar（跳过测试，CI / 生产构建推荐）
./gradlew clean :web:admin-web:bootJar -x test

# 增量重建（依赖未变动时最快）
./gradlew :web:admin-web:bootJar

# 仅编译验证不打包（最快，用于本地代码自查）
./gradlew :web:admin-web:compileJava
```

#### 产出位置

构建成功后产出位于：

```
web/admin-web/build/libs/admin-web-1.0.0.jar
```

- `admin-web-1.0.0.jar` — Spring Boot fat jar，**单一可执行包**，已合并所有 service 模块依赖
- 子模块（`model` / `common` / `service/*` / `framework/socket-framework-starter`）不会单独产出可执行 jar，仅生成 `*-1.0.0.jar` library 包供 admin-web 引用
- `admin-web/build.gradle` 中 `jar { enabled = false }` 已禁用 plain jar，避免产出无用的几百字节空包

### 后端运行

#### 本地开发运行

```bash
# 直接以 Gradle 任务运行（适合调试，热重启需配合 IDE）
./gradlew :web:admin-web:bootRun

# 指定 profile
./gradlew :web:admin-web:bootRun --args='--spring.profiles.active=dev'
```

默认使用 `web/admin-web/src/main/resources/application.yaml` 配置。

#### 生产环境运行

```bash
# 直接运行 fat jar
java -jar web/admin-web/build/libs/admin-web-1.0.0.jar

# 指定 profile（推荐：用 application-prod.yaml 覆盖默认配置）
java -jar web/admin-web/build/libs/admin-web-1.0.0.jar --spring.profiles.active=prod

# 自定义 JVM 内存
java -Xms512m -Xmx2g -jar web/admin-web/build/libs/admin-web-1.0.0.jar

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

#### 数据库迁移

升级版本时按 `sql/` 目录下 `V<N>_*.sql` 文件**升序执行**未应用过的迁移脚本（项目暂未集成 Flyway / Liquibase，需手工或自建脚本执行）：

```bash
# 示例：执行最新增量迁移
mysql -u<user> -p<password> <database> < sql/V18_user_setting.sql
```

## 前端项目 (vertex-ui)

### 技术栈
- React 19
- TypeScript
- Vite
- Ant Design
- React Router
- React i18next

### 功能特性
- ✅ 多语言支持（中文/英文）
- ✅ 多环境配置（开发/测试/生产）
- ✅ 用户管理
- ✅ 菜单管理
- ✅ 角色管理
- ✅ API 请求封装
- ✅ Token 认证

### 运行前端
```bash
cd vertex-ui

# 安装依赖
npm install

# 启动开发服务器
npm run dev

# 构建生产版本
npm run build
```

### 环境配置
前端项目支持多环境配置，配置文件位于 `vertex-ui/.env.*`：
- `.env.development` - 开发环境
- `.env.test` - 测试环境
- `.env.production` - 生产环境

## 数据库

数据库建表语句位于 `sql/system_tables.sql`，包含以下表：
- `sys_user` - 用户表
- `sys_menu` - 菜单表
- `sys_role` - 角色表
- `sys_role_menu` - 角色菜单关联表
- `sys_user_role` - 用户角色关联表

## 开发说明

### 后端开发
1. 使用 IntelliJ IDEA 或 Eclipse 导入项目
2. 配置数据库连接（`web/admin-web/src/main/resources/application.yaml`）
3. 执行 SQL 脚本创建表结构
4. 运行 `VertexApplication` 启动应用

### 前端开发
1. 进入 `vertex-ui` 目录
2. 运行 `npm install` 安装依赖
3. 运行 `npm run dev` 启动开发服务器
4. 访问 `http://localhost:5173`

## 后续规划

前端项目 `vertex-ui` 目前统一管理在 vertex 项目中，后续如需独立管理，可以：
1. 将 `vertex-ui` 目录移出到独立仓库
2. 通过 Git Submodule 或独立仓库方式管理
3. 保持 API 接口契约不变即可
