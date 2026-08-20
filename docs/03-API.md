# 03 API 文档

## 1. 通用约定

- Base URL：`http://localhost:8080/api/v1`
- 请求/响应格式：`application/json`
- 时间格式：ISO-8601 本地时间，如 `2026-08-11T09:30:00`
- 分页页码从 0 开始。
- 当前原型不包含鉴权。

统一响应：

```json
{
  "code": "0",
  "message": "success",
  "data": {},
  "traceId": "4f52e2b8..."
}
```

判断成功时应同时检查 HTTP 状态与 `code == "0"`。失败时 `data` 可能包含冲突对象、建议码或字段错误，`traceId` 用于服务端日志定位。

## 2. 包裹接口

### 2.1 入库

`POST /parcels`，成功返回 HTTP 201。

```json
{
  "trackingNo": "SF1234567890",
  "courier": "SF",
  "contactNo": "138****5678",
  "receiverName": "张",
  "codeMode": "AUTO",
  "scope": "ROW",
  "codePrefix": "15-1",
  "pickupCode": null,
  "manualSuffix": null,
  "operator": "站员A",
  "remark": null
}
```

`codeMode=AUTO` 时使用 `scope` 与 `codePrefix`：ROW 填完整排前缀（如 `15-1`），SHELF 填货架号（如 `15`），FULL 可不填。`codeMode=MANUAL` 时填写 `pickupCode`。

响应 `data` 为 `ParcelVO`，重要字段包括 `id`、`trackingNo`、`trackingTail`、`courier`、`contactMasked`、`contactType`、`realSuffix`、`needsSuffixPatch`、`pickupCode`、`codePrefix`、`codeSeq`、`status`、`inboundAt` 和滞留信息。

### 2.2 批量入库

`POST /parcels/batch`

请求体是最多若干个入库对象组成的数组。返回部分成功结果：

```json
{
  "code": "0",
  "data": {
    "total": 3,
    "succeeded": 2,
    "failed": 1,
    "success": [],
    "failures": []
  }
}
```

单条失败不回滚其他条目，调用方必须检查 `failures`。

### 2.3 分层查询

`GET /parcels`

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `keyword` | 否 | 查询内容；默认按形态自动选择通道 |
| `channel` | 否 | `AUTO`、`PICKUP_CODE`、`SUFFIX`、`TRACKING_NO`、`CONTACT_NO` |
| `status` | 否 | `PENDING`、`PICKED_UP`、`RETURNED` 等包裹状态 |
| `overdue` | 否 | `NORMAL`、`WARN`、`ALERT` |
| `codePrefix` | 否 | 排前缀，如 `15-1` |
| `page` | 否 | 默认 0 |
| `size` | 否 | 默认 20 |

自动通道规则：形如 `n-n-n` 的输入按取件码，4 位数字按真实尾号，11 位手机号按联系号，其他输入按运单号。无结果仍返回 HTTP 200 和空列表。

```json
{
  "code": "0",
  "data": {
    "content": [],
    "page": 0,
    "size": 20,
    "total": 0,
    "totalPages": 0
  }
}
```

### 2.4 详情与流水

| 方法 | 路径 | 返回 |
| --- | --- | --- |
| GET | `/parcels/{id}` | `ParcelVO` |
| GET | `/parcels/{id}/events` | 按发生时间排序的事件数组 |

### 2.5 确认取件

`POST /parcels/{id}/pickup`

```json
{ "operator": "站员A", "agent": "代取人李某", "requestId": "pickup-20260811-001" }
```

请求体可省略。传入 `requestId` 时，服务端使用 `requestId:parcelId` 作为幂等键；
同一请求重试返回原取件结果，不重复写流水。使用不同 `requestId` 重复取件仍返回 `P2005`。

### 2.6 批量取件

`POST /parcels/pickup-batch`

按真实尾号聚合：

```json
{ "realSuffix": "5678", "operator": "站员A", "requestId": "batch-20260811-001" }
```

或按前端勾选的 ID：

```json
{ "ids": [101, 102], "operator": "站员A", "requestId": "batch-20260811-001" }
```

`requestId` 必填。两种方式同时存在时 `ids` 优先；单次最多 200 个 ID。
服务端逐件调用单件取件，以 `requestId:parcelId` 派生各自的幂等键，并完全依赖状态 CAS 判定成败；
查询后已被其他请求取走的条目进入 `failures`，不影响其余条目成功。

### 2.7 状态操作

| 方法 | 路径 | 请求体 | 说明 |
| --- | --- | --- | --- |
| POST | `/parcels/{id}/cancel-pickup` | `OperationRequest` | 撤销取件并恢复 `PENDING` |
| POST | `/parcels/{id}/return` | `OperationRequest` | 拒收退回 |
| POST | `/parcels/{id}/undo-inbound` | `OperationRequest` | 撤销任一仍在库的入库记录，不物理删除 |
| POST | `/parcels/{id}/urge` | `OperationRequest` | 记录一次催取 |
| POST | `/parcels/{id}/remark` | `OperationRequest` | 添加/更新异常备注 |

`OperationRequest`：

```json
{ "operator": "站员A", "remark": "客户明日自取", "agent": null }
```

### 2.8 补录真实尾号

`PATCH /parcels/{id}/suffix`

```json
{ "realSuffix": "5678", "operator": "站员A" }
```

`realSuffix` 必须是 4 位数字。用于 AXB 虚拟号入库后的补录与检索。

## 3. 取件码接口

### 3.1 下一码预览

`GET /pickup-codes/preview?scope=ROW&codePrefix=15-1`

```json
{
  "code": "0",
  "data": {
    "nextCode": "15-1-732",
    "prefix": "15-1",
    "seq": 732,
    "exhausted": false,
    "note": "预览值不占位，实际码以入库返回为准"
  }
}
```

预览不具备占位效力，不能把预览码改为 MANUAL 后提交以模拟占位。

### 3.2 码空间可用性

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/pickup-codes/availability` | 返回全部排，可用 `prefix` 过滤 |
| GET | `/code-spaces` | 返回全部启用排 |
| GET | `/code-spaces/{prefix}` | 返回单排 |

每排包含 `capacity`、`inStock`、`cooling`、`available`、`availableRatio`、`cooldownDays`、`cooldownMode`、`tier`、日均进出和 `nextCode`。

## 4. 冷却策略接口

### 4.0 站点货架设置

设置页使用以下接口管理货架排：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/code-spaces/all` | 返回全部货架排，包含已停用排 |
| POST | `/code-spaces` | 新增货架排 |
| PUT | `/code-spaces/{prefix}/settings` | 修改容量与启用状态 |

新增 18 号货架第 1 排：

```json
{
  "shelfNo": "18",
  "rowNo": "1",
  "capacity": 9999,
  "cooldownDays": null,
  "operator": "站长李"
}
```

`cooldownDays=null` 表示初始使用 AUTO；填写 3~90 则创建为 MANUAL。前缀由货架号和排号归一化生成，如 `018` + `01` 最终为 `18-1`。

修改基础设置：

```json
{ "capacity": 8000, "enabled": true }
```

不允许把容量缩到仍被占用的最大序号以下；存在在库或冷却槽位时不允许停用。货架排不提供物理删除，确保历史包裹始终可以还原其货位。

### 4.1 设置冷却天数

`PUT /code-spaces/{prefix}/cooldown`

```json
{ "days": 7, "operator": "站长" }
```

`days=null` 表示切回 AUTO。手动值超过当前安全上限返回 `P3001`，错误数据包含 `requested` 和 `maxAllowed`。

### 4.2 全局自动冷却规则

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/settings/cooldown` | 读取当前全站冷却参数 |
| PUT | `/settings/cooldown` | 保存参数并立即重算全部自动货架 |

```json
{
  "minDays": 3,
  "maxDays": 90,
  "bufferDays": 3,
  "defaultDays": 7,
  "tightThreshold": 0.30,
  "emergencyThreshold": 0.10,
  "ewmaAlpha": 0.30,
  "statWindowDays": 14,
  "operator": "站长李"
}
```

`defaultDays` 必须在 `[minDays, maxDays]` 内，`emergencyThreshold` 必须低于
`tightThreshold`。如果新边界会使已有 MANUAL 货架越界，返回 `P1001` 并在
`conflictingSpaces` 中列出冲突货架。

### 4.3 立即重算

`POST /code-spaces/{prefix}/recompute`

返回 `newDays`、`tier`、`changed`、`reason` 和重算后的 `availability`。

### 4.4 策略日志

`GET /code-spaces/{prefix}/policy-logs?limit=50`

`limit` 默认 50、上限 500。返回指标快照、决策和原因组成的日志数组。

## 5. 今日看板

`GET /stats/today`

```json
{
  "code": "0",
  "data": {
    "date": "2026-08-11",
    "statAt": "2026-08-11T16:30:00",
    "inboundToday": 128,
    "outboundToday": 96,
    "inStock": 417,
    "overdueWarn": 12,
    "overdueAlert": 3,
    "overdueTotal": 15,
    "couriers": [{ "courier": "SF", "count": 120 }],
    "spaces": []
  }
}
```

`statAt` 是本次统计的基准时刻。前端长时间停留时应定期刷新，不应自行推测后端冷却与滞留数据。

## 6. 错误码

| 业务码 | HTTP | 含义 | 常见 `data` |
| --- | ---: | --- | --- |
| `P1001` | 400 | 参数校验失败 | 字段错误映射 |
| `P1002` | 400 | 取件码格式非法 | `input`, `expectedPattern` |
| `P2001` | 409 | 运单号未完结重复入库 | `existingParcelId`, `inboundAt` |
| `P2002` | 409 | 取件码被在库包裹占用 | `trackingNo`, `inboundAt`, `suggestedCode` |
| `P2003` | 409 | 取件码处于冷却期 | `outboundAt`, `reusableAt`, `suggestedCode` |
| `P2004` | 409 | 该排码空间耗尽 | `prefix`, `alternatives` |
| `P2005` | 409 | 包裹已取件 | `outboundAt`, `operator` |
| `P2006` | 409 | 撤销失败，码已被复用 | `currentHolderTrackingNo` |
| `P2007` | 409 | 非法状态流转 | `currentStatus`, `expected` |
| `P2008` | 409 | 撤销失败，同运单已有新活动记录 | `activeParcelId`, `inboundAt` |
| `P2009` | 409 | 货位繁忙，重试耗尽 | — |
| `P3001` | 400 | 手动冷却值超过安全上限 | `requested`, `maxAllowed` |
| `P3002` | 409 | 货架排已存在 | `prefix` |
| `P3003` | 409 | 容量缩减或停用不安全 | `minAllowedCapacity` 或 `heldSlots` |
| `P4004` | 404 | 资源不存在 | — |
| `P5000` | 500 | 系统内部错误 | — |

## 7. 在线接口文档

服务启动后可访问：

- Swagger UI：`http://localhost:8080/swagger-ui.html`
- OpenAPI JSON：`http://localhost:8080/v3/api-docs`
