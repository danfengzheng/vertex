# Vertex 项目

Vertex 是一个前后端统一管理的微服务项目。

## 项目结构

```
vertex/
├── api/                    # API 接口模块
├── common/                 # 公共模块
│   ├── common-core/        # 核心工具模块
│   └── common-web/         # Web 公共模块
├── model/                  # 数据模型模块
├── service/                # 服务模块
│   ├── user-service/       # 用户服务
│   ├── order-service/      # 订单服务
│   └── product-service/    # 产品服务
├── web/                    # Web 模块
│   └── admin-web/          # 管理后台
├── vertex-ui/              # 前端项目（React）
├── sql/                    # 数据库脚本
├── gradle/                 # Gradle 配置
└── settings.gradle         # Gradle 设置
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

### 运行后端
```bash
# 构建项目
./gradlew build

# 运行管理后台
cd web/admin-web
./gradlew bootRun
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
