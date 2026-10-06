# 按租户隔离的故障保护与自动恢复

## 背景

压测中发现：某个租户的上游持续不稳定时，该租户一路的请求会长时间占着在途名额，
慢慢拖慢甚至拖挂整个入口，殃及其他正常租户。本次在既有「窗口配额 / 在途并发 /
幂等折叠 / 上游异常归一化」能力之上，增加**按租户隔离的熔断器**与**冷静期满后的
限量自动探测恢复**。

## 熔断状态机（每个租户独立一份）

```
CLOSED  -- 连续失败达到 gateway.failure-threshold 次 -->  OPEN
OPEN    -- 冷静期 gateway.open-duration-millis 到期 -->    HALF_OPEN（仅放探测请求）
HALF_OPEN -- 探测成功 -->                                  CLOSED（整体恢复放行）
HALF_OPEN -- 任一探测失败 -->                              OPEN（重新计时）
```

- **CLOSED（正常）**：请求正常放行；成功会把连续失败计数清零。
- **OPEN（快速失败）**：该租户后续需要真正调用上游的请求立即失败返回，**不打上游**。
  响应为独立原因码 `CIRCUIT_OPEN`，`retryable=true`，并带 `retryAfterSeconds`。
- **HALF_OPEN（探测）**：冷静期到期后，只放行 `gateway.probe-permits` 个探测请求。
  - 探测成功 → 判定为 `PASS_PROBE`，熔断器整体回到 CLOSED，后续请求恢复为 `PASS`。
  - 探测失败 → 当次请求判定为 `REJECT_PROBE_FAILED`（原因码 `CIRCUIT_PROBE_FAILED`），
    熔断器重新回到 OPEN **并从头重新计时**；冷静期内被挡的其他请求同样标记为
    `REJECT_PROBE_FAILED`，审计上与普通熔断打开 `REJECT_CIRCUIT_OPEN` 可区分。
  - 探测名额放完但探测尚未落定时，其余并发请求继续快速失败，
    不会出现一瞬间大量请求同时穿透。

### 防抖动

- 成功的普通请求只清零连续失败计数；只有真正达到连续失败阈值才打开。
- 探测失败立即重新打开并重置冷静期计时，避免「半开 → 半开失败 → 立刻又放流量」的抖动。
- 配置多个探测名额时，若已有探测失败重新 OPEN，之后**迟到的探测成功**不会把熔断器
  恢复（只有在仍处于 HALF_OPEN 时的探测成功才能恢复），避免状态来回翻转误伤正常请求。
- 各租户熔断器完全独立：坏租户 OPEN/半开，不影响任何其他租户。

## 请求处理顺序与资源语义

网关对每次请求按以下顺序处理（入口 `POST /api/gateway/invoke` 与请求结构不变）：

1. **幂等折叠（原子“查询或占位”）**
   - 已落定终态且「同幂等键 + 同内容」→ 直接重放结果 `IDEMPOTENT_REPLAY`，
     **即使该租户处于熔断快速失败期间也正常返回**；不打上游、不占配额、不占在途。
   - 同键不同内容 → `REJECT_IDEMPOTENT_CONFLICT`（不重试）。
   - 同键同内容但已有请求在途 → `REJECT_IDEMPOTENT_IN_PROGRESS`（原因码
     `IDEMPOTENT_IN_PROGRESS`，`retryable=true`），不重复打上游。
   - 尚无任何记录 → 在同一原子动作里放置“进行中”占位，**同一租户同一幂等键
     同一时刻只有一笔请求能赢得占位**进入后续环节并真正调用上游。
2. **租户熔断准入**：OPEN/半开非探测请求在此被快速失败。
3. **窗口配额**：只有真正要调上游的请求才占名额。
4. **在途并发**：拿不到在途名额则拒绝，并**归还刚占用的窗口配额**。
5. **调用上游**
   - 成功：探测成功恢复熔断；有幂等键则写入终态，供后续折叠重放。
   - 失败：计入该租户连续失败（探测失败重新计时）；错误经归一化后返回
     `UPSTREAM_ERROR` / 原因 `UPSTREAM_UNAVAILABLE` 或 `UPSTREAM_ERROR`，
     **上游原始异常文本只进服务端日志，绝不出现在响应中**。

因此熔断快速失败挡下的请求：**不消耗窗口配额、不占在途名额、不打上游、不泄露原始异常**。

## 并发下同幂等标识的处理语义

- **同一键同一时刻最多一笔真实上游调用**：占位动作（`lookupOrMarkInProgress`）是
  原子操作。极短时间里多笔「同租户 + 同幂等键 + 同内容」请求同时到达时，只有赢得
  占位的第一笔会走完熔断/配额/并发并调用上游；其余请求在第一步就被拦下。
- **“处理中”提示可明确区分**：被拦下的重复请求返回
  `decision=REJECT_IDEMPOTENT_IN_PROGRESS`、`reason=IDEMPOTENT_IN_PROGRESS`、
  `retryable=true`，与正常放行（`PASS`/`PASS_PROBE`）、配额用尽
  （`REJECT_QUOTA_EXHAUSTED`）、并发达上限（`REJECT_CONCURRENCY_LIMITED`）
  以及熔断快速失败（`REJECT_CIRCUIT_OPEN`/`REJECT_PROBE_FAILED`）都是不同的判定码。
- **被拦下请求的资源归还规则**：判定为“处理中”的请求**不消耗窗口配额、不占用
  在途名额、不触碰熔断器计数**——它在任何资源占用发生之前就被短路。赢得占位的
  请求若随后在熔断/配额/并发环节被挡下或上游调用失败，会**清除“进行中”占位**
  （同时按既有规则归还已占配额），同键请求之后可以正常重试；已落定的终态
  绝不会被清除。
- **与熔断的顺序**：幂等短路发生在熔断准入之前，因此熔断打开期间，同键在途的
  重复请求仍返回“处理中”而非熔断码；已落定的同键同内容请求仍折叠重放。
  只有确实需要发起新上游调用的请求（无终态、无在途占位）才受熔断约束。

## 判定码（审计与响应 `decision` 字段）

| decision | reason | 含义 | retryable |
| --- | --- | --- | --- |
| `PASS` | `OK` | 正常放行 | false |
| `PASS_PROBE` | `PASS_PROBE` | 冷静期后作为探测放行且成功 | false |
| `REJECT_CIRCUIT_OPEN` | `CIRCUIT_OPEN` | 熔断打开，快速失败 | true |
| `REJECT_PROBE_FAILED` | `CIRCUIT_PROBE_FAILED` | 探测失败继续拦截（或其重新计时窗口内被挡） | true |
| `REJECT_QUOTA_EXHAUSTED` | `QUOTA_EXHAUSTED` | 窗口配额用尽 | true |
| `REJECT_CONCURRENCY_LIMITED` | `CONCURRENCY_LIMITED` | 在途并发达上限 | true |
| `IDEMPOTENT_REPLAY` | `IDEMPOTENT_REPLAY` | 幂等命中，折叠重放终态 | false |
| `REJECT_IDEMPOTENT_IN_PROGRESS` | `IDEMPOTENT_IN_PROGRESS` | 同键请求在途 | true |
| `REJECT_IDEMPOTENT_CONFLICT` | `IDEMPOTENT_KEY_PAYLOAD_CONFLICT` | 同键不同内容 | false |
| `UPSTREAM_ERROR` | `UPSTREAM_UNAVAILABLE` / `UPSTREAM_ERROR` | 上游异常（已归一化） | true |

## 与既有能力的兼容边界

- **幂等优先于熔断**：只折叠「已落定/已有明确终态」与同键在途的请求；仍需发起
  新上游调用的请求（无终态、无幂等键）照常受熔断约束。
- **同键并发不放大资源消耗**：同一幂等键并发到达时上游只被调用一次；被判定为
  “处理中”的请求与其它在真正调用上游之前被挡下的请求一样，不消耗窗口配额、
  不占在途名额，后续正常请求的配额不会被这波重复请求提前耗尽。
- **配额、并发只统计真实上游调用**：幂等重放、同键在途短路、熔断快速失败均不计数；
  被并发达上限挡下时归还窗口名额。
- **上游异常归一化不变**：熔断不改变异常对客户端的呈现方式，只额外提供快速失败这一条
  独立原因码与重试提示。
- 入口路径、请求/响应字段约定保持不变。

## 配置项（`src/main/resources/application.properties`）

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `gateway.window-seconds` | 60 | 配额窗口长度（秒） |
| `gateway.window-limit` | 100 | 每租户每窗口真实调用上限 |
| `gateway.max-concurrent` | 20 | 每租户最大在途并发 |
| `gateway.failure-threshold` | 5 | 连续失败多少次后熔断 |
| `gateway.open-duration-millis` | 30000 | 快速失败冷静期（毫秒） |
| `gateway.probe-permits` | 1 | 冷静期后可同时放行的探测数 |
| `gateway.probe-wait-millis` | 5000 | 探测进行中给被挡请求的等待提示 |

## 审计日志

每次请求都会输出一条 `gateway-audit` 日志，包含：租户 `tenant`、窗口 `windowSeconds`、
输入（`idempotencyKey` / `payload` / `data`）以及最终判定 `decision` / `reason` /
`retryable` / `retryAfterSeconds`。熔断器自身的状态迁移（OPEN / HALF_OPEN / CLOSED、
阈值触发、探测成功/失败）也有独立日志，便于追查一次请求是正常放行、被快速失败挡下、
作为探测放行，还是因探测失败被继续拦截。

## 本地验证

运行全部自动化测试：

```bash
mvn test
```

测试覆盖（共 51 个用例，0 失败）：

- `QuotaLimiterTest`：窗口上限、窗口滚动重置、租户隔离、名额归还。
- `ConcurrencyLimiterTest`：在途上限与释放、租户隔离。
- `IdempotencyStoreTest`：终态重放、内容冲突、进行中占位、失败后可重试、租户隔离。
- `UpstreamExceptionNormalizerTest`：超时/连接异常归一化、异常消息不外泄。
- `TenantCircuitBreakerTest`：阈值打开、冷静期快速失败、单探测放行与并发不穿透、
  探测成功恢复、探测失败重新计时、反复探测失败不抖动、多探测名额边界、租户独立。
- `GatewayServiceTest`：编排级覆盖——熔断/探测行为与配额、并发、幂等、异常
  归一化的全部不回归场景，包括「熔断期间幂等终态照常重放」「快速失败不消耗配额」
  「探测在途期间其余请求一律快速失败」（阻塞式上游 + 门闩，确定性用例，
  不依赖线程调度，重复运行结果稳定）。
- `IdempotentConcurrencyTest`：同键并发到达回归——「同键同内容并发到达时上游
  只被调用一次」「处理中判定不消耗配额也不占在途名额」「第一笔失败后同键可重试」
  「在途期间同键不同内容为冲突」「熔断打开期间同键在途重复请求仍短路为处理中」。
  全部用例用阻塞式上游保证时序确定，可反复运行。
- `GatewayControllerTest`：HTTP 入口契约（判定码、重试提示、重放与探测恢复）。

测试日志可观测性：

- 每次请求输出 `gateway-audit` 日志：租户 `tenant`、窗口 `windowSeconds`、
  输入（`idempotencyKey` / `payload` / `data`）与最终判定
  （`decision` / `reason` / `retryable` / `retryAfterSeconds`）。
- 每个测试用例结束输出 `test-outcome case=<类>#<方法> result=PASSED/FAILED`，
  可直接看出各业务用例的通过情况。

手工冒烟：

```bash
mvn spring-boot:run
curl -s -X POST http://localhost:8080/api/gateway/invoke \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":"t1","idempotencyKey":null,"payload":"p","data":"hello"}'
```
