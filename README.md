# 医保清算韧性服务（clearing-resilience-service）

面向院内环境部署的医保日清算韧性服务。每天接收医院结算清单、药店购药记录与异地结算回传，
在源系统重传、目录缺失、规则冲突、接口降级与网络故障同时发生时，保证：

- 少数故障接口**不会**耗尽连接与并发配额（依赖本地令牌桶 + 故障隔离）；
- 同一清单**不会**重复记账（幂等账本：清单版本 + 清算操作号唯一约束）；
- 每一次等待 / 限流 / 重投 / 升级人工都有**可复核的解释**（决策事件时间线）。

**月末积压容量治理**：月末清算积压时，运营需要在不压垮清算数据库、待遇规则服务与基金接口
的前提下，让金额风险高或临近清算截止的任务先获得**可证明的**处理机会，同时保证低风险来源
不被永久饿死。本服务以**预先声明的依赖容量上限 + 每来源最低服务份额**为骨干（路线 A），
并在声明上限之内用健康探针与近期成功率驱动闭环降级/恢复（绝不越界扩容）：

- 每个依赖的**声明容量上限是硬边界**，任何自动化调节都不会越过它（画像短暂滞后也安全）；
- 每个有等待任务的来源每周期保留**最低服务份额**，高优先级任务只能在公共池内"可追溯插队"；
- 故障依赖被**局部限流**（降级→熔断），健康依赖的队列照常推进；
- 恢复必须**保守**：短暂恢复信号不被追认，确认后从小上限逐步爬坡回声明上限。

技术栈：Java 21+ / Spring Boot 3.5 / Spring Data JPA / PostgreSQL / springdoc-openapi（Swagger UI）。

---

## 1. 组件与需求映射

| 需求组件 | 实现 | 说明 |
| --- | --- | --- |
| 任务登记器 | `TaskRegistrar` | 记录输入批次、清算日、清单版本、业务键、依赖版本快照、内容哈希、清算截止时间 |
| 失败判定器 | `FailureClassifier` | 技术瞬时故障 / 目录等待 / 规则冲突 / 内容不一致 / 人工裁定，五类可复核状态 |
| 依赖状态图 | `DependencyGraphService` | 目录、待遇规则、基金网关、清算数据库、来源批次节点 + 依赖边 + 恢复条件；人工健康度变更即操作员覆盖 |
| 幂等账本 | `LedgerService` | 唯一约束 `(bill_version, operation_no)` 与 `(operation_no)`；回传待确认只查不扣 |
| **容量画像库** | `CapacityProfileService` + `capacity_profile` | 每依赖：声明上限（硬边界）、生效上限、每来源最低份额、状态机、探针统计、近期成功率（EMA） |
| **队列配额控制器** | `QueueQuotaController` | 每周期：每来源保留最低服务份额 + 剩余入公共池按截止风险分授予；保留份额永不外借 |
| **依赖健康探针** | `DependencyHealthProbe` | 对 DOWN/RECOVERING 依赖主动探测；探测上限（每依赖 1 次/周期、全局 3 次/周期）防探测压垮依赖 |
| **截止风险估计器** | `DeadlineRiskEstimator` | 风险分 = 金额权重 × 临期系数，写入任务并进入调度排序与决策事件（可追溯优先级） |
| **降级编排器** | `DegradationOrchestrator` | 连续失败周期逐级收紧：NORMAL→DEGRADED（减半）→DOWN（置零冻结），只作用于故障依赖 |
| **恢复节流器** | `RecoveryThrottler` | 连续确认才进入恢复；从 1/周期保守爬坡；任何失败立即回落 DOWN |
| 依赖本地令牌桶 | `TokenBucketService` | 生效上限即补充速率；冻结不放行不补充；解冻不补发 |
| 调度器 | `ClearingScheduler` | 探针→编排→恢复→风险刷新→补充→配额计划→投放（配额+令牌+退避+健康四者同时满足） |
| 风暴观测器 | `StormObserver` | 按依赖检测队列增长、令牌消耗、失败聚集，输出 NORMAL/WATCH/STORM |
| 恢复解释器 | `RecoveryExplainer` | `GET /api/tasks/{id}/explanation` 给出人话结论 + 完整决策时间线 |
| 容量审计 | `capacity_event` + `quota_grant` | 探测/降级/熔断/爬坡/人工覆盖事件 + 每周期每来源保留与公共池授予证据 |

### 关键设计取舍（题目要求的架构选型）

**路线选择：以路线 A（预先声明容量上限 + 每来源最低服务份额）为骨干，闭环调节只在声明上限之内运行。**

- `declared_ceiling` 是预先声明的每周期安全速率（与本地桶配置一致），是**硬边界**：
  降级编排器只能向下调（减半、置零），恢复节流器只能爬坡**回到**声明上限，永不突破——
  即使画像短暂滞后也不会越过已知安全边界；不存在"按队列长度统一扩容"的路径；
- 探针与近期成功率（EMA）驱动闭环：连续失败周期 → DEGRADED → DOWN；
  连续探测成功 → RECOVERING 爬坡 → NORMAL。这提供了恢复期的利用率，同时：
  - **防抖动**：恢复需连续 2 次探测成功；单次恢复信号不被追认；
  - **防错误探测**：探测次数受每依赖/全局周期上限约束，超预算跳过并留审计事件；
  - **防高优先级独占**：每来源最低服务份额是保留槽位，高优先级任务只能消耗公共池；
- 代价：接口快速恢复时会保留部分可用吞吐（爬坡逐周期释放），这是保守恢复的有意取舍。

### 三条核心不变量（均有测试与场景断言）

1. **同业务键不同内容** → 登记器生成 `CONTENT_CONFLICT`，不发放任何令牌；
2. **待遇规则服务不可用** → 规则依赖桶停止放行；恢复后仅按剩余预算渐进投放，不补发；
3. **基金回传已受理但回执延迟** → 账本保持 `PENDING_CONFIRM`，按操作号查询，不取得新的扣款令牌。

### 月末积压五条容量不变量（新增，均有场景断言）

1. **局部限流**：基金接口错误时仅 FUND_GW 被降级/熔断，清算数据库与规则服务队列照常推进；
2. **最低服务**：低风险来源长期积压期间，每周期至少推进其保留份额（默认 1 笔），永不挂零；
3. **探测上限**：DOWN 依赖每周期探测 ≤ 1 次（全局 ≤ 3 次），超预算跳过并记录审计事件；
4. **插队保护**：高金额临期任务按风险分消耗公共池（证据可查），其他来源保留份额不可侵占；
5. **保守恢复**：单次恢复信号不放开；确认后从 1/周期爬坡，任一失败立即回落，全程不越声明上限。

---

## 2. 快速开始

### 方式 A：Docker Compose 一键启动（推荐）

```bash
docker compose up --build
```

启动后访问：

- 服务：`http://localhost:8080`
- Swagger UI：`http://localhost:8080/swagger-ui.html`
- OpenAPI 文档：`http://localhost:8080/v3/api-docs`

### 方式 B：本地开发启动

前置：PostgreSQL 已运行（本环境为 `127.0.0.1:5432`，库/用户/密码均为 `ccdev`）。

```bash
# 构建（含测试）
mvn package

# 运行
java -jar target/clearing-resilience-service.jar
```

可用环境变量覆盖数据源：`DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD`。

### 测试账号（HTTP Basic，仅用于本地/演示）

| 账号 | 密码 | 权限 |
| --- | --- | --- |
| `admin` | `admin123` | 读写（登记、调度、演练、健康度变更） |
| `viewer` | `viewer123` | 只读（查询、解释、风暴观测、容量画像与事件） |

### 调度模式

默认**手动调度**：通过 `POST /api/scheduler/tick` 逐周期驱动，保证验收确定性。
需要持续自动调度时设置环境变量 `CLEARING_SCHEDULER_AUTO=true`
（间隔 `CLEARING_SCHEDULER_INTERVAL_MS`，默认 3000ms）。

---

## 3. 验收方式（本题验证）

### 3.1 一键验收场景（11 个样本）

覆盖题目要求的医院批次、药店回传、目录冲突、接口降级、回执延迟、内容冲突、风暴观测，
以及月末积压容量治理四样本（局部限流 / 可追溯插队与插队保护 / 低风险最低服务 / 探测上限与保守恢复）。
每个场景通过真实服务执行并返回步骤轨迹与 PASS/FAIL 断言，可重复执行（演练复位会恢复目录基线）：

```bash
# —— 既有样本 ——
# 医院结算批次正常清算
curl -u admin:admin123 -X POST http://127.0.0.1:8080/api/demo/scenario/hospital-batch
# 药店购药回传
curl -u admin:admin123 -X POST http://127.0.0.1:8080/api/demo/scenario/pharmacy-return
# 目录缺失 → 目录等待 → 目录同步后自动恢复
curl -u admin:admin123 -X POST http://127.0.0.1:8080/api/demo/scenario/catalog-conflict
# 接口降级 → 桶冻结 → 恢复后渐进投放、配额恢复（人工覆盖路径）
curl -u admin:admin123 -X POST http://127.0.0.1:8080/api/demo/scenario/rule-degradation
# 基金回执延迟 → 账本待确认、按操作号查询、不重复扣款
curl -u admin:admin123 -X POST http://127.0.0.1:8080/api/demo/scenario/fund-pending-confirm
# 同业务键不同内容 → 内容冲突、不发放令牌、人工裁定
curl -u admin:admin123 -X POST http://127.0.0.1:8080/api/demo/scenario/content-conflict
# 连续超时 → 失败聚集触发 STORM → 恢复后消化积压
curl -u admin:admin123 -X POST http://127.0.0.1:8080/api/demo/scenario/storm-watch

# —— 月末积压容量治理样本（本题新增） ——
# 基金接口错误而数据库健康 → 局部限流（仅 FUND_GW 熔断，DB/规则继续推进）
curl -u admin:admin123 -X POST http://127.0.0.1:8080/api/demo/scenario/month-end-local-throttle
# 高金额临期任务到达 → 可追溯插队 + 保留份额不被侵占（插队保护）
curl -u admin:admin123 -X POST http://127.0.0.1:8080/api/demo/scenario/month-end-priority-jump
# 低风险来源长期积压 → 每周期最低服务份额，永不挂零
curl -u admin:admin123 -X POST http://127.0.0.1:8080/api/demo/scenario/month-end-min-service
# 健康信号短暂恢复 → 探测上限 + 保守恢复（不追认单信号、爬坡回声明上限）
curl -u admin:admin123 -X POST http://127.0.0.1:8080/api/demo/scenario/month-end-conservative-recovery

# 演练复位（清空业务数据、恢复依赖健康、令牌桶、容量画像与目录基线）
curl -u admin:admin123 -X POST http://127.0.0.1:8080/api/demo/reset
```

返回示例（节选自 month-end-local-throttle）：

```json
{
  "name": "month-end-local-throttle",
  "ok": true,
  "steps": ["月末积压：基金网关开始报错（模拟器 DOWN），登记 6 笔月末医院清单（清算日 2026-11-30）", "..."],
  "assertions": [
    "PASS: 局部限流：FUND_GW 画像 DOWN、生效上限 0，实际=DOWN/0",
    "PASS: 基金熔断期间数据库与规则服务继续推进：RULE 消耗 +3、清算库消耗 +3（各应为 +3）",
    "PASS: 基金熔断期间 FUND_GW 零投放（令牌消耗保持 5），基金接口得到保护"
  ]
}
```

### 3.2 手工 API 流程（可对照 Swagger UI 操作）

```bash
# 1. 登记任务（payload 为结算报文 JSON；deadlineAt 可空，缺省为清算日 20:00 截止）
curl -u admin:admin123 -H 'Content-Type: application/json' -X POST http://127.0.0.1:8080/api/tasks \
  -d '{"batchId":"B-001","clearingDate":"2026-11-30","businessKey":"HOSP-001","billVersion":"v1",
       "sourceType":"HOSPITAL","amount":"88000.00","deadlineAt":"2026-11-30T12:00:00Z",
       "payload":"{\"items\":[{\"catalogCode\":\"A-001\",\"qty\":2,\"price\":100}]}"}'
# 响应含 riskScore / riskBand（截止风险估计器输出，如 9.0 / CRITICAL）

# 2. 执行一个调度周期
curl -u admin:admin123 -X POST http://127.0.0.1:8080/api/scheduler/tick

# 3. 查看任务恢复解释（为何等待/限流/重投/升级人工，含风险分与配额来源）
curl -u viewer:viewer123 http://127.0.0.1:8080/api/tasks/1/explanation

# 4. 依赖降级/恢复（人工覆盖：DOWN 冻结桶，HEALTHY 解冻不补发；覆盖期间自动化不接管）
curl -u admin:admin123 -H 'Content-Type: application/json' \
  -X POST http://127.0.0.1:8080/api/dependencies/RULE_ENGINE/health \
  -d '{"health":"DOWN","reason":"规则服务不可用演练"}'

# 5. 查看容量画像 / 容量事件 / 令牌桶 / 配额证据 / 风暴观测 / 账本
curl -u viewer:viewer123 http://127.0.0.1:8080/api/capacity/profiles
curl -u viewer:viewer123 "http://127.0.0.1:8080/api/capacity/events?limit=50"
curl -u viewer:viewer123 http://127.0.0.1:8080/api/buckets
curl -u viewer:viewer123 http://127.0.0.1:8080/api/quota/evidence
curl -u viewer:viewer123 http://127.0.0.1:8080/api/storm/latest
curl -u viewer:viewer123 http://127.0.0.1:8080/api/ledger

# 6. 基金回执延迟演练：网关设为 DELAYED 后登记+调度，任务进入 PENDING_CONFIRM；
#    回执到达后按操作号确认
curl -u admin:admin123 -H 'Content-Type: application/json' \
  -X POST http://127.0.0.1:8080/api/simulation/fund-gateway \
  -d '{"mode":"DELAYED","delaySeconds":300}'
curl -u admin:admin123 -X POST http://127.0.0.1:8080/api/ledger/FR-HOSP-001-v1/confirm
```

### 3.3 单元/集成测试

```bash
mvn test
```

25 个测试覆盖：令牌桶冻结/解冻不补发、内容冲突不发令牌、重复登记幂等吸收、
账本操作号幂等、回执延迟不重复扣款、依赖降级恢复渐进投放、目录等待恢复、
规则冲突升级人工、安全边界（401/403/200）、截止风险估计分层、
队列配额（保留份额/公共池/拒绝）、探测预算上限、短暂恢复不追认与保守爬坡。

---

## 4. API 一览（详见 Swagger UI）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/tasks` | 登记清算任务（幂等：重复吸收 / 内容冲突；可指定 deadlineAt） |
| GET | `/api/tasks` | 任务列表（可按状态/依赖过滤），含 riskScore/riskBand/deadlineAt |
| GET | `/api/tasks/{id}/explanation` | 恢复解释（结论 + 决策时间线 + 失败记录） |
| POST | `/api/tasks/{id}/manual-resolve` | 人工裁定（REQUEUE/CANCEL/KEEP_ORIGINAL/OVERRIDE） |
| GET/POST | `/api/dependencies[/{code}/health]` | 依赖状态图 / 健康度变更（人工覆盖） |
| GET | `/api/capacity/profiles[/{code}]` | 容量画像库（声明/生效上限、最低份额、状态机、探针统计、成功率） |
| GET | `/api/capacity/events[/{code}]` | 容量事件审计（探测/跳过/降级/熔断/爬坡/恢复/人工覆盖） |
| GET | `/api/buckets`、`/api/quota/evidence` | 本地令牌桶 / 队列配额证据（保留+公共池授予） |
| GET/POST | `/api/ledger[/{operationNo}[/confirm]]` | 幂等账本查询 / 回执确认 |
| POST | `/api/scheduler/tick` | 手动调度周期 |
| GET | `/api/storm/latest`、`/api/storm/reports` | 风暴观测 |
| GET/POST | `/api/catalog/items` | 医保目录查询 / 同步 |
| GET/POST | `/api/simulation[...]` | 下游模拟器（规则超时、基金延迟/宕机） |
| POST | `/api/demo/reset`、`/api/demo/scenario/{name}` | 验收演练（11 个场景） |

---

## 5. 配置项（`application.yml`，均可环境变量覆盖）

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `clearing.buckets.CLEARING_DB.capacity/refill-per-tick` | 12 / 6 | 清算数据库桶（保护账本写入，待遇计算入账时消耗） |
| `clearing.buckets.RULE_ENGINE.capacity/refill-per-tick` | 6 / 2 | 待遇规则依赖桶 |
| `clearing.buckets.FUND_GW.capacity/refill-per-tick` | 3 / 1 | 基金网关依赖桶（容量更小，保护基金接口） |
| `clearing.capacity.profiles.*.declared-ceiling` | DB 6 / RULE 2 / FUND 1 | **预先声明的容量上限（硬边界）**，自动化调节永不越过 |
| `clearing.capacity.profiles.*.min-share-per-source` | 1 | 每来源最低服务份额（保留槽位，不被外来源侵占） |
| `clearing.capacity.probe.max-per-tick-per-dep` / `max-per-tick-total` | 1 / 3 | 探测上限：每依赖/全局每周期探测次数 |
| `clearing.capacity.probe.consecutive-ok-to-recover` | 2 | 连续探测成功确认数（短暂恢复信号不被追认） |
| `clearing.capacity.escalator.failure-ticks-to-degrade` / `-to-down` | 2 / 3 | 连续失败周期 → 降级 / 熔断 |
| `clearing.capacity.escalator.degraded-factor` | 0.5 | 降级时生效上限折算（至少保留 1） |
| `clearing.capacity.recovery.initial-ceiling` / `step` / `dwell-ticks` | 1 / 1 / 1 | 保守恢复爬坡：起点、步长、驻留周期 |
| `clearing.capacity.deadline.cutoff-hour` / `urgent-window-minutes` | 20 / 120 | 缺省清算截止时刻与临期窗口 |
| `clearing.backoff.*` | 1000ms 起，指数，上限 10s，最多 5 次 | 瞬时故障退避 |
| `clearing.storm.*` | 窗口 300s，失败≥5 STORM，≥3 或队列增长≥5 WATCH | 风暴阈值 |
| `clearing.scheduler.auto-enabled` | false | 自动调度开关 |

---

## 6. 已实际执行的验证（本环境）

以下验证均在本工作区真实执行通过：

1. `mvn test`：25 个测试全部通过（H2 内存库）；
2. `mvn package` 构建成功，产出 `target/clearing-resilience-service.jar`；
3. 以本地 PostgreSQL 18（`ccdev`）启动服务，11 个验收场景（7 个既有 + 4 个月末积压新增）
   **连续两轮全量执行**全部返回 `ok: true`，断言全 PASS（验证场景可重复执行）；
4. 月末积压四样本实测结论：
   - `month-end-local-throttle`：基金报错被逐级降级→熔断（DOWN/上限 0/桶冻结），
     期间 RULE 与清算库令牌消耗各 +3 继续推进，FUND 消耗保持 5 不再增长；
   - `month-end-priority-jump`：3 笔 90000 元临期（30 分钟）任务风险分 9.0/CRITICAL，
     当周期全部完成待遇计算（医院保留 1 + 公共池 2，配额证据 maxRiskScore=9.0），
     药店来源保留份额 1 笔未被侵占；
   - `month-end-min-service`：高风险竞争下药店来源连续 5 周期每周期 +1 笔，保留授予累计 5；
   - `month-end-conservative-recovery`：故障期 3 周期恰好 3 次探测（上限 1/周期），
     单次恢复信号未放开（保持 DOWN/0），连续确认后从 1/周期爬坡至声明上限 2 恢复 NORMAL；
5. 手工 API 验证：带 `deadlineAt` 登记返回风险分 9.0/CRITICAL；调度后解释接口给出
   含风险分与配额来源的决策时间线；容量画像/容量事件/配额证据均可查询；
6. 安全边界：未认证 401、viewer 写操作 403、admin 读写 200；
7. Swagger UI `/swagger-ui.html` 与 OpenAPI `/v3/api-docs`（3.1.0，25 个路径）可访问。

说明：本环境未安装 Docker CLI，Docker 镜像构建未在本环境执行；
`Dockerfile` 与 `docker-compose.yml` 已提供并静态核对（多阶段构建：Maven 构建 → JRE 运行，
Compose 含 postgres:18 健康检查与依赖编排）。

## 7. 已知边界

- 下游系统（目录/规则/基金网关）由内置模拟器承载，便于院内联调前演练；接入真实接口时
  替换 `DownstreamSimulator` 的调用实现即可，令牌桶、画像、配额、账本与调度逻辑不变；
- 清算数据库的健康探针使用真实 `SELECT 1`；画像与桶保护其写入容量，
  数据库自身故障时整个调度事务无法提交（与既有事务边界一致）；
- 认证为演示级 HTTP Basic 内存账号，生产应对接院内统一认证；
- 令牌桶按调度周期补充（非时间窗滑动），周期长度即补发粒度；
- 人工健康度变更即操作员覆盖：覆盖期间自动化降级编排与探针不接管该依赖，
  演练复位（`/api/demo/reset`）后恢复自动化管理；
- 恢复爬坡逐周期释放吞吐（`recovery.step`/`dwell-ticks` 可调），
  接口快速恢复时会暂时保留部分可用吞吐——这是保守恢复的有意取舍。
