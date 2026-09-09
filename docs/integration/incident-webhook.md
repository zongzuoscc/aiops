# 外部项目接入 AIOps 告警

本文说明其他项目如何把故障告警接入 AIOps。接入方只需要发送 HTTP 请求，不需要引入 AIOps 的 Java 代码。

## 1. 接入流程

```text
业务项目 / 监控系统
        |
        | POST 告警事件
        v
AIOps /api/v2/incidents
        |
        v
证据采集 -> 知识库诊断 -> 策略判断 -> Docker 修复
        |
        v
健康验证 -> 邮件通知或人工升级
```

## 2. 通用 HTTP 接入

接口：`POST http://AIOPS_HOST:29900/api/v2/incidents`

请求体：

```json
{
  "source": "payment-service",
  "eventId": "payment-api-5xx-20260827-001",
  "serviceId": "demo-service",
  "severity": "P1",
  "title": "支付接口 5xx 持续升高",
  "description": "过去 5 分钟错误率超过 30%",
  "labels": {
    "environment": "dev",
    "region": "cn-shanghai",
    "alertname": "PaymentApiHigh5xx"
  }
}
```

字段说明：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `source` | 是 | 告警来源系统或项目名称 |
| `eventId` | 是 | 稳定的事件标识，同一告警重试时保持不变 |
| `serviceId` | 是 | AIOps 中已登记的服务 ID |
| `severity` | 是 | 建议使用 `P0`、`P1`、`P2`、`P3` |
| `title` | 是 | 告警标题 |
| `description` | 否 | 告警详细描述 |
| `labels` | 否 | 便于诊断和筛选的标签 |

示例：

```bash
curl -X POST http://127.0.0.1:29900/api/v2/incidents \
  -H 'Content-Type: application/json' \
  -d '{
    "source": "demo-service",
    "eventId": "demo-http-503-001",
    "serviceId": "demo-service",
    "severity": "P1",
    "title": "Demo service health check failed",
    "description": "HTTP health check returned 503",
    "labels": {"environment": "local"}
  }'
```

接口返回 `202 Accepted`，返回内容包含事件 ID。AIOps 会异步执行诊断和修复。

## 3. 注册可自动修复的服务

自动修复前需要同时满足两层授权：

1. 在 `config/executor-targets.yml` 中添加容器、健康检查地址和允许动作。
2. 调用服务注册接口，把该服务登记到 AIOps 数据库。

注册示例：

```bash
curl -X POST http://127.0.0.1:29900/api/v2/services \
  -H 'Content-Type: application/json' \
  -d '{
    "id": "demo-service",
    "displayName": "Demo Service",
    "containerName": "aiops-demo-service",
    "healthUrl": "http://aiops-demo-service:18080/health",
    "stableImage": "aiops-demo-service:stable",
    "allowedActions": [
      "GET_CONTAINER_STATE",
      "GET_CONTAINER_LOGS",
      "GET_CONTAINER_STATS",
      "CHECK_HTTP_HEALTH",
      "RESTART_CONTAINER",
      "RECREATE_WITH_STABLE_IMAGE",
      "RESTORE_SNAPSHOT",
      "DISCARD_SNAPSHOT"
    ],
    "automationEnabled": true,
    "protectedTarget": false,
    "minConfidence": 0.9,
    "writeLimit": 1,
    "cooldownSeconds": 300
  }'
```

默认执行器只授权 `demo-service`。其他项目接入时，先复制该配置块，再按实际容器名、镜像名和健康检查地址修改。没有执行器授权的服务不能注册成功。

## 4. Prometheus Alertmanager 接入

Alertmanager 配置：

```yaml
receivers:
  - name: aiops
    webhook_configs:
      - url: http://aiops-server:9900/api/v2/integrations/alertmanager
        send_resolved: true

route:
  receiver: aiops
```

AIOps 会从 Alertmanager 告警中读取：

- `labels.service`，没有时回退到 `labels.container`
- `annotations.summary`，没有时回退到 `labels.alertname`
- `annotations.description`
- `labels.severity`
- `fingerprint` 作为稳定事件标识

## 5. Spring Boot 项目接入

```java
RestClient client = RestClient.create("http://aiops-server:9900");

Map<String, Object> event = Map.of(
        "source", "order-service",
        "eventId", "order-db-timeout-20260827",
        "serviceId", "order-service",
        "severity", "P1",
        "title", "订单数据库连接超时",
        "description", "连续 3 次数据库连接超时",
        "labels", Map.of("environment", "dev")
);

client.post()
        .uri("/api/v2/incidents")
        .body(event)
        .retrieve()
        .toBodilessEntity();
```

## 6. Node.js 项目接入

```javascript
await fetch('http://aiops-server:9900/api/v2/incidents', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    source: 'node-api',
    eventId: 'node-api-health-20260827',
    serviceId: 'node-api',
    severity: 'P1',
    title: 'Node API health check failed',
    description: 'The health endpoint returned 503',
    labels: { environment: 'dev' }
  })
});
```

## 7. 查询处理结果

```bash
# 查询最近事件
curl http://127.0.0.1:29900/api/v2/incidents

# 查询单个事件的时间线、诊断和动作
curl http://127.0.0.1:29900/api/v2/incidents/{incidentId}
```

事件状态通常按如下顺序变化：

`RECEIVED -> COLLECTING_EVIDENCE -> DIAGNOSING -> PLANNING -> EXECUTING -> VERIFYING -> RESOLVED`

如果证据不足、策略拒绝、修复失败或健康检查仍未恢复，最终会进入 `MANUAL_REQUIRED`。
