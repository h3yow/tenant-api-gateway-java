# Tenant API Gateway (Java / Spring Boot)

按租户隔离的 API 网关，提供固定窗口配额、在途并发控制、幂等折叠重放、上游异常归一化，
以及按租户隔离的熔断故障保护与冷静期满后的限量探测自动恢复。
同一幂等键并发到达时最多一笔真正调用上游，其余请求折叠为「处理中」提示，
且不消耗窗口配额与在途名额。

- 入口：`POST /api/gateway/invoke`
- 请求体：`{ "tenantId", "idempotencyKey", "payload", "data" }`
- 故障隔离规则、并发幂等语义、判定码、兼容边界与本地验证：见 [docs/circuit-breaker.md](docs/circuit-breaker.md)

## 本地运行 / 测试

```bash
mvn spring-boot:run   # 启动
mvn test              # 全部自动化测试
```
