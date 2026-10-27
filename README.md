# SuperBizAgent

> 基于 Spring Boot + AI Agent 的智能问答与运维系统

## 📖 项目简介

企业级智能业务代理系统，包含两大核心模块：

### 1. RAG 智能问答
集成 Milvus 向量数据库和阿里云 DashScope，提供基于检索增强生成的智能问答能力，支持多轮对话和流式输出。

### 2. AIOps 智能运维
基于 AI Agent 的自动化运维系统，采用 Planner-Executor-Replanner 架构，实现告警分析、日志查询、智能诊断和报告生成。

## 🚀 核心特性

- ✅ **RAG 问答**: 向量检索 + 多轮对话 + 流式输出
- ✅ **AIOps 运维**: 智能诊断 + 多 Agent 协作 + 自动报告
- ✅ **工具集成**: 文档检索、告警查询、日志分析、时间工具
- ✅ **会话管理**: 上下文维护、历史管理、自动清理
- ✅ **Web 界面**: 提供测试界面和 RESTful API


## 🛠️ 技术栈

| 技术 | 版本 | 说明 |
|------|------|------|
| Java | 17 | 开发语言 |
| Spring Boot | 3.2.0 | 应用框架 |
| Spring AI | - | AI Agent 框架 |
| DashScope | 2.17.0 | 阿里云 AI 服务 |
| Milvus | 2.6.10 | 向量数据库 |

## 📦 核心模块

```
SuperBizAgent/
├── src/main/java/org/example/
│   ├── controller/
│   │   └── ChatController.java        # 统一接口控制器 ⭐
│   ├── service/
│   │   ├── ChatService.java           # 对话服务 ⭐
│   │   ├── AiOpsService.java          # AIOps 服务 ⭐
│   │   ├── RagService.java            # RAG 服务
│   │   └── Vector*.java               # 向量服务
│   ├── agent/tool/                    # Agent 工具集
│   │   ├── DateTimeTools.java         # 时间工具
│   │   ├── InternalDocsTools.java     # 文档检索
│   │   ├── QueryMetricsTools.java     # 告警查询
│   │   └── QueryLogsTools.java        # 日志查询
│   └── config/                        # 配置类
├── src/main/resources/
│   ├── static/                        # Web 界面
│   └── application.yml                # 应用配置
└── aiops-docs/                        # 运维文档库
```


## 📡 核心接口

### 1. 智能问答接口

**流式对话（推荐）**
```bash
POST /api/chat_stream
Content-Type: application/json

{
  "Id": "session-123",
  "Question": "什么是向量数据库？"
}
```
支持 SSE 流式输出、自动工具调用、多轮对话。

**普通对话**
```bash
POST /api/chat
Content-Type: application/json

{
  "Id": "session-123",
  "Question": "什么是向量数据库？"
}
```
一次性返回完整结果，支持工具调用和多轮对话。

### 2. AIOps 智能运维接口

```bash
POST /api/ai_ops
```
自动执行告警分析流程，生成运维报告（SSE 流式输出）。

### 3. 会话管理

- `POST /api/chat/clear` - 清空会话历史
- `GET /api/chat/session/{sessionId}` - 获取会话信息

### 3.5 告警事件接入（新）

- `POST /api/v2/incidents` - 通用告警入站，自动落库并异步触发分析
- `POST /api/v2/integrations/alertmanager` - Alertmanager 告警入站
- `GET /api/v2/incidents` - 查询事件列表
- `GET /api/v2/incidents/{eventId}` - 查询事件详情，包含时间线和执行动作
- `POST /api/v2/services` - 注册可被执行器管理的 Docker 服务

### 4. 文件管理

- `POST /api/upload` - 上传文件并自动向量化
- `GET /milvus/health` - Milvus 健康检查


## ⚙️ 核心配置

### application.yml

```yaml
server:
  port: 9900

# Milvus 向量数据库
milvus:
  host: localhost
  port: 19530

# 阿里云 DashScope
spring:
  ai:
    dashscope:
      api-key: "${DASHSCOPE_API_KEY}" // 环境变量

# RAG 配置
rag:
  top-k: 3
  model: "qwen3-max"

# 文档分片
document:
  chunk:
    max-size: 800
    overlap: 100
```

### 环境变量

```bash
export DASHSCOPE_API_KEY=your-api-key
export INCIDENT_AUTOMATION_ENABLED=true
export INCIDENT_NOTIFY_WEBHOOK_URL=http://ops-callback/incident
export AIOPS_EMAIL_ENABLED=true
export AIOPS_EMAIL_FROM=aiops@example.com
export AIOPS_EMAIL_TO=ops@example.com
export SMTP_HOST=smtp.example.com
export SMTP_PORT=465
export SMTP_USERNAME=your-smtp-username
export SMTP_PASSWORD=your-smtp-password
export SMTP_AUTH=true
export SMTP_SSL_ENABLE=true
export SMTP_STARTTLS=false
```

### 告警邮件配置（可选）

- 启用开关：`AIOPS_EMAIL_ENABLED=true`
- SMTP 服务：`SMTP_HOST`、`SMTP_PORT`、`SMTP_USERNAME`、`SMTP_PASSWORD`
- 发件人与收件人：`AIOPS_EMAIL_FROM`、`AIOPS_EMAIL_TO`
- 多个收件人使用英文逗号分隔，例如 `ops@example.com,oncall@example.com`
- `AIOPS_EMAIL_RETRY_ATTEMPTS` 控制单次通知的发送次数，默认 3 次。
- 当前只发送人工介入和自动修复恢复通知，不发送完整日志，避免泄露敏感信息。
- 邮件未启用时应用仍可正常启动；邮件发送失败会记录在事件时间线，不阻塞自动修复流程。


## 🚀 快速开始

日常使用自主运维栈：`bash scripts/start-autonomous.sh`，后端地址为 `http://127.0.0.1:29900`，详见 [自主运维 Docker 启动说明](docs/operations/autonomous-docker.md)。社区与 AIOps 仅复用镜像，容器、网络、库、卷和鉴权配置保持独立；AIOps 的 Milvus 宿主机端口为 `29531`，容器内部仍为 `19530`。

下方普通 Compose 与 `make init` 是兼容入口，不与自主运维栈同时启动。旧数据须先确认升级与恢复方案，不能仅因镜像版本统一就删除旧卷或直接套用新版本。

### 一、独立 Vue 前端（新增）

本次升级后，前端已从 `src/main/resources/static` 中拆出为独立目录 `frontend/`，采用 Vue 3。

#### 本地开发（热更新）

```bash
cd aiops-upgrade/frontend
npm install
npm run dev
```

访问：`http://localhost:5173`

可通过环境变量注入后端地址（默认 `/api`）：

```bash
VITE_API_BASE_URL=http://127.0.0.1:29900/api npm run dev
```

#### 旧版前端镜像启动（兼容保留）

前端会由 Nginx 托管，已在 `docker-compose.yml` 中新增 `aiops-web-ui` 服务。

```bash
cd aiops-upgrade
docker compose --env-file .env.aiops up -d aiops-app aiops-web-ui
```

默认访问：`http://localhost:38080`（端口可在 `.env.aiops` 中通过 `WEB_UI_HOST_PORT` 调整）。

### 二、后端 API 与容器启动

### 1. 环境准备

```bash
# 设置 API Key
export DASHSCOPE_API_KEY=your-api-key
```

### 2. 旧版 Docker 启动（兼容保留）

```bash
cd aiops-upgrade
cp .env.aiops .env.aiops.local   # 如需本地化可复制改名后编辑
# 编辑 .env.aiops.local 设置 DASHSCOPE_API_KEY / AI_OPS_API_KEYS
DASHSCOPE_API_KEY=...
APP_HOST_PORT=9910
MYSQL_HOST_PORT=33306
REDIS_HOST_PORT=6380
ETCD_HOST_PORT=23379
MINIO_API_HOST_PORT=19100
MINIO_CONSOLE_HOST_PORT=19101
MILVUS_HOST_PORT=29531
MILVUS_METRICS_HOST_PORT=19091
docker compose --env-file .env.aiops.local up -d --build
```

默认服务映射端口（已避开你当前环境现有端口）：
- 前端 UI: `38080`
- 应用 API: `9910`
- MySQL: `33306`
- Redis: `6380`
- Milvus: `29531`
- Etcd: `23379`
- MinIO API: `19100`
- MinIO Console: `19101`
- Milvus Metrics: `19091`

启动前可先检查宿主端口是否已被占用（确保和你现有两个容器不冲突）：
```bash
docker ps --format '{{.Names}}\\t{{.Ports}}' | rg '9910|33306|6380|23379|19100|19101|29531|19091'
```
也可通过如下端口占用一键自检：
```bash
lsof -i :38080 -i :9910 -i :33306 -i :6380 -i :23379 -i :19100 -i :19101 -i :29531 -i :19091
```

### 3. 本地直接启动（可选）

```bash
# 先启动中间件（如需）
docker compose up -d mysql milvus etcd minio redis

# 启动应用
export MILVUS_PORT=29531
mvn clean install
mvn spring-boot:run
```

### 4. 兼容原启动方式

```bash
make init  # 仍可使用现有项目脚本逻辑
```


### 5. 使用示例

**Web 界面**
```
http://localhost:38080
```

**命令行**
```bash
# 上传文档
curl -X POST http://localhost:9900/api/upload \
  -F "file=@document.txt"

# 智能问答
curl -X POST http://localhost:9900/api/chat \
  -H "Content-Type: application/json" \
  -d '{"Id":"test","Question":"什么是向量数据库？"}'

# 告警事件推送
curl -X POST http://localhost:9900/api/incident/webhook \
  -H "Content-Type: application/json" \
  -d '{"alertname":"HighCPUUsage","severity":"P1","service":"payment-service","status":"firing","summary":"CPU > 90% 持续 5 分钟","description":"支付服务 CPU 异常高"}'

# 健康检查
curl http://localhost:9900/milvus/health
```


**版本**: v1.0.0  
**作者**: chief  
**许可证**: MIT
