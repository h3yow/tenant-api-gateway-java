# Tenant API Gateway (Java / Spring Boot)

按租户隔离的 API 网关，提供固定窗口配额、在途并发控制、幂等折叠重放（含同键并发
到达时“同一键同一时刻最多一笔真实上游调用”的折叠语义）、上游异常归一化，
以及按租户隔离的熔断故障保护与冷静期满后的限量探测自动恢复。

- 入口：`POST /api/gateway/invoke`
- 请求体：`{ "tenantId", "idempotencyKey", "payload", "data" }`
- 故障隔离规则、判定码、兼容边界与本地验证：见 [docs/circuit-breaker.md](docs/circuit-breaker.md)

## 本地运行 / 测试

```bash
mvn spring-boot:run   # 启动
mvn test              # 全部自动化测试
```
