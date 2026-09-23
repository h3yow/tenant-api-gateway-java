# 多租户 API 网关（Java / Spring Boot，本地模拟）

在最小 Spring Boot 骨架上实现的一套多租户 API 网关能力。所有外部依赖（上游服务、限流存储、
幂等存储、余额台账）均为**本地内存实现/模拟**，不依赖任何外部服务、真实凭据或新增付费依赖。

- 按租户 + 固定时间窗口的调用配额与在途并发限流，并发下不超额、不丢计数
- 写操作安全重试：`Idempotency-Key` 幂等折叠，键冲突 / 重复请求 / 参数错误可区分，不重复扣款
- 上游异常归一化：超时、连接失败、畸形响应、业务错误 → 稳定可区分的错误码与 HTTP 状态码
- 每次请求写审计：可还原租户、入口、请求输入、时间窗口与最终判定依据

## 1. 快速开始

```bash
mvn spring-boot:run          # 启动，默认端口 8080
mvn test                     # 运行全部 16 个测试（保持骨架原有测试约定）
```

要求 JDK 21+（工程在 JDK 25 上同样可构建）。

## 2. 多入口与请求约定

所有接口通过 `X-Tenant-Id` 标识租户；写接口必须携带 `Idempotency-Key`。
`X-Request-Id` 可选，缺省由网关生成。

| 方法 | 路径 | 说明 |
| ---- | ---- | ---- |
| POST | `/gw/payments` | 写入口（支付），走幂等 + 配额 + 并发闸口 |
| POST | `/gw/queries`  | 读入口（查询），仅走配额 + 并发闸口 |
| GET  | `/gw/audit`    | 查看全部审计记录 |
| GET  | `/gw/audit/{tenantId}` | 按租户查看审计记录 |

请求体中的 `scenario` 字段用于驱动**本地模拟上游**：

| scenario | 模拟行为 |
| -------- | -------- |
| `ok`（缺省） | 正常成功（支付会实际扣减本地台账余额） |
| `slow` | 延迟 `gateway.upstream.delay-millis` 毫秒后成功，用于制造在途并发竞争 |
| `timeout` | 模拟上游读超时 |
| `connection_failed` | 模拟上游连接失败 |
| `malformed` | 模拟上游返回无法解析的畸形响应 |
| `business_error` | 模拟上游业务规则拒绝（余额不足同样触发该类） |

### curl 示例

```bash
# 成功支付（首次执行，真实扣减一次余额）
curl -s -X POST localhost:8080/gw/payments \
  -H 'X-Tenant-Id: tenant-a' -H 'Idempotency-Key: KEY-1' \
  -H 'Content-Type: application/json' \
  -d '{"orderId":"ORD-1","amount":1000,"scenario":"ok"}'

# 同键重复提交 → 折叠为重放 REPLAY_OK，不会再次扣款
curl -s -X POST localhost:8080/gw/payments \
  -H 'X-Tenant-Id: tenant-a' -H 'Idempotency-Key: KEY-1' \
  -H 'Content-Type: application/json' \
  -d '{"orderId":"ORD-1","amount":1000,"scenario":"ok"}'

# 上游超时 → 504 UPSTREAM_TIMEOUT（retryable=true，提示携带相同键安全重试）
curl -s -X POST localhost:8080/gw/payments \
  -H 'X-Tenant-Id: tenant-a' -H 'Idempotency-Key: KEY-2' \
  -H 'Content-Type: application/json' \
  -d '{"orderId":"ORD-2","amount":100,"scenario":"timeout"}'

# 查询入口（读）
curl -s -X POST localhost:8080/gw/queries \
  -H 'X-Tenant-Id: tenant-a' -H 'Content-Type: application/json' \
  -d '{"orderId":"ORD-1","scenario":"ok"}'

# 审计还原
curl -s localhost:8080/gw/audit/tenant-a
```

## 3. 配额与限流模型

- **固定时间窗口**：每个租户独立。窗口起点为该租户首个请求被处理时的整秒时刻，
  长度为 `window-seconds`；窗口结束后下一个请求开启新窗口并把窗口计数清零。
- **窗口调用配额** `quota-limit`：窗口内允许放行的请求总数（读写共享计数）。
  第 `limit+1` 个请求以 `429 QUOTA_EXCEEDED` 拒绝，`retryHint` 给出距窗口重置秒数与
  `已消耗 n/limit`。
- **在途并发上限** `concurrency-limit`：同时处于"已过闸口、上游调用未返回"的请求数上限。
  超出以 `429 CONCURRENCY_EXCEEDED` 拒绝，原因码与配额拒绝**可区分**，提示退避重试。
- **并发正确性**：每租户一个状态对象，"窗口滚动判定 → 配额检查 → 并发检查 → 计数占位"
  在该对象锁内一次完成；被拒绝的请求不增加任何计数；请求结束（成功或失败）在
  `finally` 中释放在途占位。因此同一窗口并发请求不会超额放行，也不会计数丢失。
  不同租户使用不同锁，互不阻塞。
- **写操作的闸口顺序**：先做幂等判定，再占用配额。因此同一幂等键的重复请求即使在配额
  耗尽后仍能折叠重放，且不会因为重放而消耗配额。

内置租户配置（`src/main/resources/application.properties`，窗口刻意设为 1 秒便于手动验证）：

| 租户 | 窗口 | 窗口配额 | 在途并发 | 初始余额 |
| ---- | ---- | -------- | -------- | -------- |
| tenant-a | 1s | 5 | 2 | 100000 |
| tenant-b | 1s | 3 | 1 | 50000 |

## 4. 写操作幂等与安全重试

- 幂等键作用域为 **(租户, Idempotency-Key)**。请求指纹由业务内容
  `pay:{orderId}:{amount}` 构成。
- 每个键的状态机（占用/落定均为原子操作，基于 `ConcurrentHashMap.compute`）：

  ```
  (无) ──首次请求──> IN_PROGRESS ──执行结束──> 终态(成功 / 上游拒绝)
                         │
                         └──并发的同键请求──> 409 IDEMPOTENCY_IN_PROGRESS（可稍后带同键重试）
  终态后的同键同指纹请求 ──> 结果重放（REPLAY_OK，或错误重放）
  同键但指纹不同         ──> 409 IDEMPOTENCY_KEY_CONFLICT（键被不同内容复用，禁止）
  ```

- 只有**首次**请求真正调用模拟上游并扣减余额；重放只返回首次的终态，不重复扣款、
  不重复落账（余额台账使用 CAS 原子扣减，测试断言多次重放后余额只减少一次）。
- 首次请求被上游拒绝也会落终态；之后同键请求得到 `422 UPSTREAM_REJECTED_BY_IDEMPOTENT_RETRY`
  的**错误重放**（不再次调用上游），消息中带归一化原因。可重试类上游故障（超时/连接失败）
  的正确恢复姿势是：携带**相同**幂等键重试以获取终态；业务参数类错误则应修正参数后换新键。
- 三类问题严格可区分：`IDEMPOTENCY_KEY_CONFLICT`（键冲突）、
  `IDEMPOTENCY_IN_PROGRESS` / `REPLAY_*`（重复/并发重复）、`INVALID_PARAMETER`
  （真正的参数错误，如 `amount<=0`、空 `orderId`）。

## 5. 上游异常归一化

模拟上游抛出的内部异常类型（`UpstreamException` + `UP_*` 原始 code）**绝不直接透出**，
统一在网关编排层映射：

| 上游情形 | 归一化错误码 | HTTP | retryable | 重试提示 |
| -------- | ------------ | ---- | --------- | -------- |
| 超时 TIMEOUT | `UPSTREAM_TIMEOUT` | 504 | true | 带相同幂等键安全重试 |
| 连接失败 CONNECTION_FAILED | `UPSTREAM_UNAVAILABLE` | 502 | true | 稍后带相同键重试 |
| 畸形响应 MALFORMED_RESPONSE | `UPSTREAM_BAD_RESPONSE` | 502 | false | 不盲目重试，核实后换新键 |
| 业务错误 BUSINESS_ERROR（含余额不足） | `UPSTREAM_BUSINESS_REJECTED` | 422 | false | 修正参数后换新键 |

完整错误码表：

| 错误码 | HTTP | 含义 |
| ------ | ---- | ---- |
| `MISSING_TENANT` | 400 | 缺少 `X-Tenant-Id` |
| `UNKNOWN_TENANT` | 403 | 未知/未开通租户 |
| `MISSING_IDEMPOTENCY_KEY` | 400 | 写操作缺少幂等键 |
| `INVALID_PARAMETER` | 400 | 请求参数非法 |
| `QUOTA_EXCEEDED` | 429 | 窗口配额用尽 |
| `CONCURRENCY_EXCEEDED` | 429 | 在途并发达上限 |
| `IDEMPOTENCY_IN_PROGRESS` | 409 | 同键请求处理中 |
| `IDEMPOTENCY_KEY_CONFLICT` | 409 | 幂等键被不同内容复用 |
| `UPSTREAM_TIMEOUT` | 504 | 上游超时 |
| `UPSTREAM_UNAVAILABLE` | 502 | 上游连接失败 |
| `UPSTREAM_BAD_RESPONSE` | 502 | 上游畸形响应 |
| `UPSTREAM_BUSINESS_REJECTED` | 422 | 上游业务拒绝 |
| `UPSTREAM_REJECTED_BY_IDEMPOTENT_RETRY` | 422 | 幂等错误重放 |
| `INTERNAL_ERROR` | 500 | 兜底（不泄漏内部细节） |

错误响应体统一为：`code / message / retryable / retryHint / requestId / tenantId / timestamp`。

## 6. 审计

每个请求（放行、拒绝、重放）都产生一条 `AuditRecord`，保存在内存并输出 `gateway.audit`
结构化日志，字段包括：租户、入口、幂等键、请求指纹、窗口起止、请求输入摘要、
最终判定（`ALLOWED_UPSTREAM_OK / REPLAY_OK / REPLAY_ERROR / REJECTED`）、
归一化原因码、归一化前上游故障类别、上游交易号、耗时、时间戳。
可通过 `/gw/audit/{tenantId}` 还原"哪个租户在什么窗口以什么输入得到了什么判定及依据"。

## 7. 本地存储与重启一致性语义（重要）

限流计数、在途占位、幂等记录、余额台账、审计历史**全部保存在单进程内存**中：

- **进程重启后所有运行时状态清空并回到初始配置**：配额窗口重新开始计数；幂等键全部消失，
  重启后使用旧键会被当作全新请求；余额回到配置的 `initial-balance`；审计历史清空。
- 该实现刻意**不提供跨进程/重启持久化**。因此安全边界是：单实例进程生命周期内，
  配额精确、幂等精确；重启即"新会话"。生产级跨重启一致性需要外置存储（数据库/Redis），
  本工程按要求不引入此类外部依赖。
- 单进程内即使发生异常，在途并发占位也会在 `finally` 中释放，不会因上游故障泄漏槽位。

## 8. 测试与本地验证

```bash
mvn test
```

| 测试类 | 覆盖内容 |
| ------ | -------- |
| `QuotaServiceTest` | 配额边界（恰好放行 limit、limit+1 拒绝、拒绝不计数）、窗口滚动、并发拒绝与配额拒绝可区分 |
| `QuotaConcurrencyTest` | 50 线程冲击配额=10（恰好 10 放行 / 40 拒绝 / 计数=10）、慢上游下在途并发=2 的竞争 |
| `IdempotencyTest` | 同键 3 次提交只扣一次款且后两次 REPLAY、键冲突/参数错误/缺键可区分、同键并发 IN_PROGRESS→终态 REPLAY |
| `UpstreamNormalizationTest` | 四类上游故障映射为四种稳定码与状态码、原始 `UP_*` 不外泄、错误重放不二次调用上游、读入口归一化 |
| `GatewayHttpIntegrationTest` | HTTP 多入口：成功/重放/缺租户/未知租户/参数错误、504 超时与重试提示、跨租户配额隔离、审计可还原 |
| `ApplicationTests` | 保留骨架原有的 Spring 上下文加载测试 |

测试日志会打印**租户、窗口起止、输入与判定依据**（例如
`并发配额汇总 tenant=tenant-a window=[...) 输入=50线程 判定=放行10 拒绝40 最终计数=10/10`）。
集成测试使用 `src/test/resources/application.properties`（60 秒窗口）以避免真实时钟跨秒抖动，
并通过测试专用的 `GatewayStateResetter` 在每个用例前重置本地状态。
