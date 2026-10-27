# 自主运维 Docker 一键启动

自主运维栈与普通开发栈分开，使用 `docker-compose.autonomous.yml`。它包含：

- AIOps 后端
- MySQL
- Milvus、etcd、MinIO
- RabbitMQ
- Redis
- Docker 执行器
- Demo 服务
- Prometheus
- Grafana

前端不放进容器，继续在本地使用 Vue 开发服务器运行。

社区与 AIOps 只复用相同版本的镜像；容器、网络、数据库、数据卷和鉴权配置仍各自独立。AIOps 的 Milvus 宿主机端口为 `29531`，社区使用 `29530`，容器内部端口仍为 `19530`。

## 1. 第一次启动

```bash
cd /Users/yangyiming/Desktop/项目改进/运维项目相关/aiops-upgrade
bash scripts/start-autonomous.sh
```

脚本第一次执行时会从 `.env.autonomous.example` 创建 `.env.autonomous`，之后不会覆盖已有文件。

如果需要启用邮件通知，编辑 `.env.autonomous`：

```env
AIOPS_EMAIL_ENABLED=true
AIOPS_EMAIL_FROM=zongzuo_aiops@yeah.net
AIOPS_EMAIL_TO=2651575456@qq.com
SMTP_HOST=smtp.yeah.net
SMTP_PORT=465
SMTP_USERNAME=zongzuo_aiops@yeah.net
SMTP_PASSWORD=你的客户端授权密码
SMTP_AUTH=true
SMTP_SSL_ENABLE=true
SMTP_STARTTLS=false
```

## 2. 服务地址

| 服务 | 地址 |
| --- | --- |
| AIOps API | `http://127.0.0.1:29900` |
| Prometheus | `http://127.0.0.1:19091` |
| Grafana | `http://127.0.0.1:13001` |
| RabbitMQ 管理台 | `http://127.0.0.1:25674` |
| Vue 前端 | `http://localhost:5173` |

## 3. 启动前端

```bash
cd frontend
npm install
VITE_API_BASE_URL=http://127.0.0.1:29900/api npm run dev
```

## 4. 停止服务

```bash
bash scripts/stop-autonomous.sh
```

停止脚本不会删除数据卷。需要清理本地数据时，再手动执行：

```bash
docker compose --env-file .env.autonomous -f docker-compose.autonomous.yml down -v
```

## 5. 端口隔离

自主运维栈使用独立端口：

- AIOps API：`29900`
- MySQL：`23306`
- etcd：`22379`
- MinIO：`29100`、`29101`
- Milvus：`29531`、`29091`
- RabbitMQ：`25673`、`25674`
- Redis：`16380`
- Prometheus：`19091`
- Grafana：`13001`

前端仍使用本地 `5173`，不新增前端容器。
