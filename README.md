# 末端驿站包裹管理系统

单站点末端驿站包裹管理原型。功能面刻意收敛，深度投向四条主线：
**取件码作为可循环资源的生命周期管理**、**隐私面单下的手机号双职责拆分**、
**并发与幂等的正确性**、**时间规则的可测试性**。

实现依据：`docs/01-需求与实施文档.md`（开发实施文档 v4 定稿）。

---

## 一、启动方式

### 环境要求

| 项目 | 版本 |
| ---- | ---- |
| JDK | 17+ |
| Maven | 3.9+ |
| 数据库 | 无需安装，内置 H2 文件模式 |

### 一条命令启动

```bash
mvn spring-boot:run
```

启动后浏览器直达：

| 入口 | 地址 |
| ---- | ---- |
| 前端主页 | http://localhost:8080/ |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| H2 控制台 | http://localhost:8080/h2-console |

H2 控制台连接串：`jdbc:h2:file:./data/station;MODE=MySQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=TRUE`，
用户名 `sa`，密码为空。

### 运行测试

```bash
mvn test                          # 全部用例
mvn test -Dgroups=showcase        # 建议评审的 10 条核心用例
```

测试全部基于 JUnit 5 + MockMvc + H2，一条命令可重复执行，
**不依赖外部环境、不使用 `Thread.sleep`**——时间相关的边界一律靠推进 `MutableClock` 验证。

### 切换 MySQL

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=mysql
```

前置仅需手工建库。由于 H2 已以 `MODE=MySQL` 运行，`db/migration` 下的 Flyway
脚本无需任何改动即可在 MySQL 执行。详见 `src/main/resources/application-mysql.yml`。

---

## 二、技术选型理由

| 选型 | 理由 |
| ---- | ---- |
| Spring Boot 3 + JDK 17 | 文档指定；record、文本块、switch 表达式让纯函数与 DTO 写得更干净 |
| H2 文件模式 + `MODE=MySQL` | 免安装，`mvn spring-boot:run` 即可跑；同时让 Flyway 脚本在两种库上通用 |
| Flyway | schema 版本化。配合 `ddl-auto: validate`，实体与脚本一旦漂移<b>启动即失败</b> |
| Spring Data JPA | 派生查询省样板；关键路径用 `@Query` 手写 JPQL 保持可控 |
| Vue 3 CDN 版 | 零 npm 构建，评审者不需要装 Node |

一个有意的取舍：`spring.jpa.hibernate.ddl-auto` 设为 `validate` 而非 `none`。
开发期这一设置已经真实拦下一次事故——实体把 `tinyint` 列映射成了 `INTEGER`，
启动直接报 `Schema-validation: wrong column type`，而不是等到运行时数据异常才发现。

---

## 三、已实现与未实现范围

### 已实现

| 编号 | 功能 | 状态 |
| ---- | ---- | ---- |
| F1 | 包裹入库（AUTO / MANUAL、归一化、冲突诊断） | ✅ |
| F5 | 取件码生成与循环复用（next-fit、ROW/SHELF/FULL） | ✅ |
| F6 | 冷却期派生判定与按需自愈 | ✅ 判定与自愈完成，回炉定时任务待接 |
| F14 | 联系方式解析（真实号 / 掩码号 / AXB 虚拟号） | ✅ |
| INV-1~6 | 六条核心不变量 | ✅ 已落地并有用例守护 |

### 进行中 / 未完成

| 编号 | 功能 | 说明 |
| ---- | ---- | ---- |
| F2 | 待取件查询（分层检索） | ⬜ 仓储方法已备齐，缺 QueryService 与 Controller |
| F3 | 确认取件 | ⬜ `markPickedUp` 原子更新已写，缺 PickupService |
| F4 | 滞留标识 | ✅ 计算逻辑已在 `ParcelVO` 完成，随查询接口一并暴露 |
| F7 | 冷却自适应调节 | ⬜ 纯函数 `CooldownPolicy` 已完成并可单测，缺 Applier 与定时触发 |
| F8 | 扫码连续入库 | ⬜ 后端预览接口逻辑已备（`CodeAllocationService.preview`），缺 Controller 与前端 |
| F9~F12 | 批量取件 / 撤销 / 催取 / 看板 | ⬜ |
| F13 | 22 条测试用例 | 🔶 已完成 9 条 |
| P2 | 拒收退回、异常件标记 | 🔶 `markReturned` 已写，缺接口 |
| P3 | 短信、多站点、鉴权、密文盲索引 | ⬜ 不实现，见演进方向 |

### 不实现（仅写入演进方向）

短信通知、多站点码空间隔离、账号鉴权、手机号密文与盲索引、
ShedLock 分布式互斥、计数器表取号。

---

## 四、六条核心不变量的落地位置

| 不变量 | 落地位置 |
| ------ | -------- |
| **INV-1** 两个生命周期相互独立 | `Parcel.activeFlag` / `Parcel.codeSlotFlag` 两个字段 + `V1__init.sql` 两条唯一索引 |
| **INV-2** 唯一性由数据库兜底 | `InboundTxService` 直接 insert 撞索引，查询只用于生成友好提示 |
| **INV-3** 只落原始事实 | `ParcelRepository.findOccupiedSeqs` 以 `outboundAt` 与 boundary 实时比较判定冷却 |
| **INV-4** 时间取自注入的 Clock | `ClockConfig`；测试用 `MutableClock` 覆盖 |
| **INV-5** 带前置条件的原子更新 | `markPickedUp` / `markCancelPickup` 等一律 `where id=? and status=?` |
| **INV-6** 状态变更写流水且不覆盖 | `EventRecorder`；撤销追加 `CANCEL_PICKUP` 而非改回原值 |

---

## 五、开发期的三个发现

### 1. H2 的多 NULL 唯一索引语义已实测验证

整套 INV-1 建立在"唯一索引允许多个 NULL"之上。动手前先用 H2 2.2.224
（`MODE=MySQL`）直接验证：两行 `('SF1', NULL)` 可共存，
第二行 `('SF1', 1)` 与第二个 `('15-1-2', 1)` 均被 `23505` 拒绝。结论成立，方案可行。

### 2. 并发重试会锁步撞号（文档未覆盖）

文档要求"重试时重新加载位图，不要把序号简单加一"。照此实现后，
2 线程通过，**8 线程必然失败**。

原因：并发线程加载到的是同一份位图，next-fit 必然算出**同一个**序号；
重试后重新加载位图，又会再次算出同一个新序号——冲突以锁步方式持续，
重试次数很快耗尽。

解法：重试轮次 > 0 时给 next-fit 的**搜索起点**叠加随机偏移（跨度 64），
把并发线程在码空间上散开。位图仍整体重新加载，不假设任何具体序号可用，
因此不违反"禁止序号加一"这条禁令。见 `CodeAllocationService.allocateSeq(space, now, attempt)`。

### 3. 掩码号不能用统一的号码归一化

若在解析前统一去掉连字符，`138----5678` 会被压成 `1385678` 从而漏判。
故拆成两级：`stripBlank` 保留掩码字符供掩码号判定，
`normalize` 再去连字符供真实号与虚拟号判定。见 `PhoneNormalizer`。

---

## 六、仓库结构

```
webapp/
├── README.md
├── pom.xml
├── docs/
│   └── 01-需求与实施文档.md          原始开发实施文档 v4
├── src/main/java/com/sf/station/
│   ├── common/                      ApiResponse BizException ErrorCode
│   │                                GlobalExceptionHandler ClockConfig TraceIdFilter AppProperties
│   ├── parcel/
│   │   ├── domain/                  Parcel ParcelStatus ParcelEvent EventType OverdueLevel CodeSource
│   │   ├── repository/              ParcelRepository ParcelEventRepository
│   │   ├── application/             InboundAppService  ← 事务外层，承载重试
│   │   │                            InboundTxService   ← @Transactional 事务单元
│   │   │                            EventRecorder ParcelAssembler
│   │   └── api/                     ParcelController + dto/
│   ├── code/
│   │   ├── domain/                  CodeSpace PickupCodeVO PickupCodeNormalizer CodeAllocator
│   │   │                            CooldownPolicy SpaceMetrics CooldownDecision Tier AllocScope
│   │   ├── repository/              CodeSpaceRepository CooldownPolicyLogRepository
│   │   └── application/             CodeAllocationService CooldownQueryService
│   ├── contact/                     ContactResolver PhoneNormalizer ContactInfo
│   └── stats/
├── src/main/resources/
│   ├── db/migration/V1__init.sql
│   ├── static/                      Vue 3 CDN 单页
│   ├── application.yml  application-mysql.yml
└── src/test/java/com/sf/station/
    ├── support/                     BaseIntegrationTest MutableClock TestClockConfig
    ├── unit/                        纯函数单测
    └── integration/                 MockMvc 用例
```

分层规则：Controller 只做参数绑定与 DTO 转换；事务边界一律在 `*TxService` 或 `*Service`，
**不允许出现在 Controller**；`CodeAllocator` 与 `CooldownPolicy` 是纯函数，
不注入任何 Repository 和 Clock，时间以参数传入。

---

## 七、当前测试清单

| 编号 | 场景 | 层次 | 状态 |
| ---- | ---- | ---- | ---- |
| TC-01 | 扫码入库自动生成码 | MockMvc | ✅ showcase |
| TC-02 | 连续入库三件 | MockMvc | ✅ |
| TC-07 | 同排双线程并发入库 | SpringBootTest | ✅ showcase |
| TC-07b | 同排八线程并发入库 | SpringBootTest | ✅ |
| TC-08 | 运单号未完结重复入库 | MockMvc | ✅ showcase |
| — | 联系号无法识别 → P1001 | MockMvc | ✅ |
| — | AXB 虚拟号入库待补录 | MockMvc | ✅ |
| — | MANUAL 归一化 `15-1-0731` → `15-1-731` | MockMvc | ✅ |
| — | MANUAL 撞在库包裹 → P2002 含建议码 | MockMvc | ✅ |

`mvn test` 当前 **9 条全绿**。剩余 TC-03/04/05/06/09~22 待补。
