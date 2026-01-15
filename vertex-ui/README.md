# Vertex UI

Vertex 管理系统前端项目

## 技术栈

- React 19
- TypeScript
- Vite
- React Router
- Ant Design
- React i18next (多语言)
- Axios

## 功能特性

- ✅ 多语言支持（中文/英文）
- ✅ 多环境配置（开发/测试/生产）
- ✅ 用户管理
- ✅ 菜单管理
- ✅ 角色管理（开发中）
- ✅ API 请求封装
- ✅ Token 认证

## 环境配置

### 开发环境
```bash
npm run dev
```
使用 `.env.development` 配置

### 测试环境
```bash
npm run build:test
```
使用 `.env.test` 配置

### 生产环境
```bash
npm run build
```
使用 `.env.production` 配置

## 环境变量

- `VITE_APP_TITLE` - 应用标题
- `VITE_API_BASE_URL` - API 基础地址
- `VITE_API_PREFIX` - API 前缀
- `VITE_TOKEN_HEADER` - Token 请求头
- `VITE_TOKEN_PREFIX` - Token 前缀

## 多语言

支持中文和英文，语言切换会自动保存到 localStorage。

语言文件位置：`src/i18n/locales/`

## 项目结构

```
src/
├── api/          # API 接口
├── components/   # 公共组件
├── config/       # 配置文件
├── hooks/        # 自定义 Hooks
├── i18n/         # 多语言配置
├── pages/        # 页面组件
├── router/       # 路由配置
├── types/        # TypeScript 类型
└── utils/        # 工具函数
```

## 开发

```bash
# 安装依赖
npm install

# 启动开发服务器
npm run dev

# 构建生产版本
npm run build

# 预览生产构建
npm run preview
```
