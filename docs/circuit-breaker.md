# 按租户的故障隔离与自动恢复（熔断）

在既有「窗口配额 / 在途并发 / 幂等折叠 / 上游异常归一化」之上，本版新增**按租户独立**的
熔断器。某个租户的上游持续不稳定时，网关只对该租户快速失败，其他租户不受影响；冷静期后
通过限量探测自动发现上游恢复，避免把整个入口拖慢拖挂。

## 1. 状态机（每个租户独立一份）

```
CLOSED  --连续失败达到 gateway.failure-threshold-->  OPEN
OPEN    --冷静期到期，且仍有探测名额-->              HALF_OPEN（仅放探测请求）
HALF_OPEN --探测成功-->                               CLOSED（整体恢复）
HALF_OPEN --探测失败-->                               OPEN（重新计时，防抖动）
```

- **CLOSED**：正常放行。期间任何一次真正调用上游成功，连续失败计数清零。
- **OPEN（快速失败 / 冷静期）**：需要真正调用上游的请求在网关本地直接失败，
  **不调用上游、不消耗窗口配额、不占在途名额**；响应带独立原因码和 `retryAfterSeconds`。
- **HALF_OPEN（探测）**：冷静期结束后，最多放 `gateway.probe-permits` 个请求作为探测。
  探测名额之外、以及探测结果尚未落定期间涌入的请求一律继续快速失败，
  因此冷静期到期的一瞬间也不会出现大量请求同时穿透。

### 防抖动约定

- 探测一旦失败，**立即**回到 OPEN 并从头重新计算完整冷静期（不是在剩余时间上打折）。
- 探测失败重新 OPEN 后，仍在途的其他探测晚到回报的成功或失败一律视为**过期结果忽略**：
  只有下一轮冷静期后放行的新探测才能再次把熔断器关掉，避免 OPEN/CLOSED 来回横跳。
- 上游一直不好时，熔断器会在「探测失败 → 重新冷静 → 再探测」之间循环，直到探测成功。

## 2. 请求判定与原因码（审计、响应共用一套编码）

| decision                     | 含义                                   | retryable | retryAfterSeconds        |
|------------------------------|----------------------------------------|-----------|--------------------------|
| `PASS`                       | 正常放行，真正调用了上游               | false     | -                        |
| `PASS_PROBE`                 | 作为冷静期后的探测请求放行并成功       | false     | -                        |
| `REJECT_CIRCUIT_OPEN`        | 熔断打开（连续失败阈值触发），快速失败 | true      | 距冷静期结束的剩余秒数   |
| `REJECT_PROBE_FAILED`        | 探测调用上游失败，或处于探测失败后的重新冷静期 | true | 新冷静期剩余秒数 |
| `REJECT_QUOTA_EXHAUSTED`     | 窗口配额用尽（既有能力，原因码不变）   | true      | 窗口长度                 |
| `REJECT_CONCURRENCY_LIMITED` | 在途并发达上限（既有能力，原因码不变） | true      | 1                        |
| `IDEMPOTENT_REPLAY`          | 幂等终态命中折叠重放（既有能力）       | false     | -                        |
| `REJECT_IDEMPOTENT_CONFLICT` | 同幂等键不同内容（既有能力）           | false     | -                        |
| `REJECT_IDEMPOTENT_IN_PROGRESS` | 同键已有请求在途（既有能力）        | true      | 1                        |
| `UPSTREAM_ERROR`             | 上游异常已归一化（`UPSTREAM_UNAVAILABLE` / `UPSTREAM_ERROR`） | true | - |

熔断挡下与配额用尽、并发达上限的原因码、`retryAfterSeconds` 彼此独立，客户端可区分处理。
被挡响应的 `result` 为空，**上游原始异常的类型、消息、主机信息永远不会出现在响应里**，
原始细节只写服务端日志。

## 3. 与既有能力的兼容边界

处理顺序（HTTP 入口 `POST /api/gateway/invoke`、请求/响应结构均保持不变）：

1. **幂等折叠优先于一切，包括熔断**。已落定终态的写请求，携带同一幂等键 **且相同内容**
   再来时，无论该租户熔断器处于什么状态，都直接折叠重放：不调上游、不经过熔断/配额/并发。
   - 同键不同内容：仍按 `REJECT_IDEMPOTENT_CONFLICT` 拒绝（熔断期间也一样）。
   - 同键已有请求在途：按 `REJECT_IDEMPOTENT_IN_PROGRESS` 处理，不重复打上游。
   - 只有**确实需要发起新上游调用**的请求才受熔断约束。
2. **熔断准入**。OPEN / HALF_OPEN 下非探测请求快速失败，不占任何网关资源。
3. **窗口配额**。仅真正要调上游的请求占名额；之后若被并发或幂等占位挡下则归还。
4. **在途并发**。拿不到名额快速失败并归还刚占用的配额；调用结束（成功或失败）必释放。
5. **调用上游**。成功：探测成功则整体恢复 CLOSED，并写幂等终态；
   失败：记一笔熔断计数（探测失败立即重开冷静期），清掉幂等“进行中”占位，
   异常经归一化后返回 `UPSTREAM_ERROR` / `REJECT_PROBE_FAILED`。

配置项（`src/main/resources/application.properties`，均可按环境覆盖）：

```properties
gateway.window-seconds=60        # 配额窗口
gateway.window-limit=100         # 每窗口每租户上限
gateway.max-concurrent=20        # 每租户在途并发上限
gateway.failure-threshold=5      # 连续失败多少次后打开熔断
gateway.open-duration-millis=30000  # 快速失败冷静期
gateway.probe-permits=1          # 冷静期后允许同时放行的探测数
gateway.probe-wait-millis=5000    # 探测进行中给被挡请求的等待提示
```

## 4. 审计日志

每次请求都会输出一行 `gateway-audit`，包含租户、窗口、幂等键、输入内容和最终判定，
据此可区分正常放行 / 探测放行 / 熔断快速失败 / 探测失败继续拦截：

```
gateway-audit tenant=t1 windowSeconds=60 idempotencyKey=k payload=p data=d decision=PASS_PROBE reason=PASS_PROBE retryable=false retryAfterSeconds=null
gateway-audit tenant=t1 ... decision=REJECT_CIRCUIT_OPEN reason=CIRCUIT_OPEN retryable=true retryAfterSeconds=10
gateway-audit tenant=t1 ... decision=REJECT_PROBE_FAILED reason=CIRCUIT_PROBE_FAILED retryable=true retryAfterSeconds=10
```

熔断器自身的状态流转、配额、并发、幂等、异常归一化也各有 INFO/WARN 日志。

## 5. 本地验证

```bash
# 全量自动化测试（单测 + 网关编排集成测试 + Spring 上下文加载）
mvn test

# 只看新增熔断与探测边界
mvn test -Dtest=TenantCircuitBreakerTest
mvn test -Dtest=GatewayServiceIntegrationTest

# 启动服务后手工验证
mvn spring-boot:run
curl -s -X POST http://localhost:8080/api/gateway/invoke \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":"t1","idempotencyKey":null,"payload":null,"data":"hello"}'
```

测试覆盖的关键行为：

- 连续失败达阈值后 OPEN，冷静期内快速失败、不调上游、不消耗配额、不占在途、不外泄原始异常；
- 冷静期后**恰好**放行配置数量的探测：成功整体恢复；失败立即重开并重新计时，
  涌入流量只有 1 个能真正触达上游，迟到探测结果不会造成状态抖动；
- OPEN 期间同键同内容的已落定写请求仍正常重放，同键不同内容仍报冲突；
- 一个租户熔断不影响其他租户；
- 配额、并发（含名额归还）、幂等折叠/在途、上游异常归一化等既有用例全部回归通过。
