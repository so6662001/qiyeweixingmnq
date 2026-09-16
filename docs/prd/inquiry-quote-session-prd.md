# 企微会话中的询价单 / 报价单归并与状态管理 · PRD 与技术设计

| 项目 | 内容 |
| --- | --- |
| 模块 | 普讯AI销售 · 询价会话（Inquiry Session） |
| 版本 | v1.0 草案 |
| 适用渠道 | 企业微信（入站：会话存档；出站：企微右侧栏发送报价卡片）。本版不含个人微信 |
| 配套效果图 | `docs/mockups/puxun-inquiry-quote-hifi.html` |
| 相关效果图 | 销售聊天台 `puxun-sales-desk-hifi.html`、企微右侧栏 `puxun-ai-sales-sidebar-hifi.html` |

---

## 1. 背景与问题

一个企业微信对话里客户会连续发起 N 次询价。典型场景：

> 09:00 客户问「100 方管多少钱」 → 09:01 销售回「3000」
> 09:02 客户又问「100×200×2.75 矩管呢」 → 09:03 销售回「3150」
> 09:05 客户说「下单」

业务上这是**一单**，不是两单。现有做法把每次询价当成一条独立记录，直接导致五个问题：

1. **单据数量错**：一次沟通拆成多张询价单 / 报价单，与最终一张订单对不上。
2. **状态无法表达**：第一条已报价，第二条刚问，整单到底是"已报价"还是"待报价"说不清。
3. **销售无提醒**：前面已报价，又来一个规格，销售不知道这单还有一项没报。
4. **边界不清**：多久算一次报价？隔 2 分钟算一单，隔 2 小时算不算？隔一天呢？
5. **数据错乱**：聊天记录重放导致重复抽取；销售回的「3000」不知道对应哪个规格。

## 2. 目标与非目标

### 2.1 目标

- 一次交易意向 = **一张询价单**，规格可追加；一张询价单最终对应 **≤ 1 张订单**（允许部分成交）。
- 每次报价形成**不可变版本**，订单只按**当前版本**生成；历史版本可审计。
- 询价单、询价行、报价版本三级状态机，任何时刻状态可解释。
- 关键节点**事件驱动提醒**销售：追加规格、意向下单但有未报项、报价过期、单据即将收口。
- 会话边界由 **语义 > 盘价 > 空闲时间** 三层规则决定，参数可配置，销售可人工拆单 / 合并。
- 所有 AI 抽取结果**锚定原始消息 ID**，报价必须配对到具体询价行；配不上就问人，不猜。

### 2.2 非目标

- 不做自动报价定价（价格由销售或现有报价引擎给出，本模块只负责归并与状态）。
- 不涉及个人微信；不引入企微之外的收发通道。
- 不改变会话存档拉取 / 解密链路本身，本模块只消费已解密的消息。
- 不处理客户群询价（v1 仅员工与外部联系人的单聊）。

## 3. 术语

| 术语 | 英文 | 定义 |
| --- | --- | --- |
| 询价单 | InquiryTicket | 一次交易意向的容器，同一「客户 × 销售」会话同时只能有一张活跃单 |
| 客户 | external_userid | 企微外部联系人 ID，会话存档消息中的客户侧标识 |
| 销售 | userid | 企微成员 ID，会话存档消息中的员工侧标识 |
| 会话存档 | Chat Archive | 企微官方能力，拉取员工与客户之间**双向**的全部消息（含销售在手机企微上回的内容），每条带唯一 `msgid` |
| 询价行 | InquiryLine | 客户问到的一个规格（品名 + 规格 + 壁厚 + 材质 + 数量） |
| 报价版本 | QuoteVersion | 销售一次报价形成的快照，含本单全部已报行的价格 |
| 报价行 | QuoteLine | 报价版本中某一询价行的价格 |
| 规格键 | spec_key | 归一化后的规格标识，用于同单内去重 |
| 盘价截止 | price_cutoff | 每日挂牌价更新时刻，跨过后旧报价失效 |
| 收口 | close-out | 询价单结束活跃状态（成交 / 流失 / 过期） |

## 4. 用户故事

| 编号 | 角色 | 故事 | 验收 |
| --- | --- | --- | --- |
| US-1 | 销售 | 客户连续问两个规格后说下单，我希望系统只生成一张订单、两行明细 | 场景 A 步骤 6 |
| US-2 | 销售 | 已经报了方管，客户又问矩管，我希望右侧栏立刻提示"本单还有 1 项未报" | 场景 A 步骤 3 |
| US-3 | 销售 | 客户说下单时如果还有规格没报价，我希望系统拦住我先补报 | 场景 C 步骤 3 |
| US-4 | 销售 | 我只回了个"3000"，两个规格都在等价，我希望系统问我报的是哪个而不是乱配 | 场景 B 步骤 2 |
| US-5 | 销售 | 昨天报的价今天盘价变了，我希望系统提醒并帮我按新价重报 | 场景 C 步骤 2 |
| US-6 | 销售 | 客户下单后下午又问别的，我希望是新的一单，不要混进已成交的单里 | 场景 A 步骤 7 |
| US-7 | 销售主管 | 我希望能看到每一版报价是谁、什么时候、对哪条消息报的 | 时间线 + 审计字段 |
| US-8 | 销售 | 系统判断错了，我希望能手动拆单或把两单合并 | 人工拆合操作 |

## 5. 领域模型

```
Customer ──1:N──▶ InquiryTicket ──1:N──▶ InquiryLine
                       │                      ▲
                       │ 1:N                  │ N:1
                       ▼                      │
                  QuoteVersion ──1:N──▶ QuoteLine
                       │
                       │ 0..1（current）
                       ▼
                     Order（按 current 版本生成，≤1 张）
```

**约束**

- `InquiryTicket`：`UNIQUE (external_userid, owner_userid) WHERE status IN (活跃状态集)`。会话存档按「客户 × 员工」成对出现，询价单也按这一对归并。
- `InquiryLine`：`UNIQUE (source_msg_id)`（会话存档 `msgid` 全局唯一）；同单内 `spec_key` 唯一。
- `QuoteVersion`：同单内 `version_no` 递增，任意时刻 **最多一个** `CURRENT`。
- `QuoteLine.inquiry_line_id` 非空 —— 没有对应询价行的价格不允许落库。

## 6. 状态机

### 6.1 询价单 InquiryTicket

```
            +追加行                +追加行
  ┌──────────────┐        ┌──────────────┐
  ▼              │        ▼              │
OPEN ──报价──▶ QUOTING ──全部行已报──▶ QUOTED ──客户表意向──▶ CONFIRMING
 (收集中)       (部分已报)              (全部已报)              │
                                          ▲ 追加行 ─────────────┤ 追加行
                                          │                     ▼
                                       QUOTING            ORDERED / PARTIAL_ORDERED
                                                                │
任意活跃态 ──空闲超时──▶ EXPIRED（可复活）                       └─▶ 后续新询价开新单
任意活跃态 ──客户明确拒绝 / 销售标记──▶ LOST
```

| 状态 | 含义 | 进入条件 | 是否活跃 |
| --- | --- | --- | --- |
| OPEN | 收集中，尚无任何报价 | 首个询价行创建 | 是 |
| QUOTING | 部分行已报，仍有 PENDING 行 | 存在 QUOTED 行且存在 PENDING 行 | 是 |
| QUOTED | 全部行已报 | 无 PENDING 行且 ≥1 QUOTED 行 | 是 |
| CONFIRMING | 客户表达下单意向，等待销售确认转单 | 意图识别 = 确认 / 销售手动 | 是 |
| ORDERED | 全部行成交，已生成订单 | 销售确认转单 | 否 |
| PARTIAL_ORDERED | 部分行成交，其余行仍可成交 | 转单时有行未被选中 | **是**（直到收口） |
| EXPIRED | 空闲超时自动收口 | 超过 `auto_expire` 无活动且未成交 | 否，可复活 |
| LOST | 流失 | 客户拒绝 / 销售标记 | 否 |

> QUOTED 状态下再追加行会**退回 QUOTING**，而不是新开一单。这是本方案与"每次询价一条记录"最本质的区别。

### 6.2 询价行 InquiryLine

```
PENDING ──配对到报价──▶ QUOTED ──新版本改价──▶ REQUOTED（仍视为已报）
   │                       │
   │                       ├──客户确认──▶ ACCEPTED（进订单）
   │                       └──客户否决──▶ REJECTED
   └──销售删除 / 客户撤回──▶ CANCELLED
```

补充：`PENDING` 行在报价配对歧义时挂 `pairing_pending = true` 标记，不改变主状态。

### 6.3 报价版本 QuoteVersion

```
CURRENT ──生成新版本──▶ SUPERSEDED
CURRENT ──跨过盘价截止 / valid_until 到期──▶ EXPIRED
CURRENT ──客户确认并转单──▶ ACCEPTED（冻结）
```

规则：

- 每次销售报出至少一个新价格或改价，**生成新版本**，新版本包含本单**所有**已报行（全量快照，不是增量）。
- 旧版本不删除、不修改，只改状态。
- 订单只能从 `CURRENT` 或 `ACCEPTED` 版本生成；`EXPIRED` 版本必须重报后才可转单。

## 7. 会话边界规则：多久算一次报价

三层规则叠加，**优先级从高到低**，任一触发即生效：

### 7.1 语义边界（最高优先级）

| 客户 / 销售表达 | 意图标签 | 动作 |
| --- | --- | --- |
| "下单 / 开单 / 就这些 / 打款了" | `CONFIRM` | 单 → CONFIRMING |
| "先不要了 / 太贵 / 算了" | `REJECT` | 单 → LOST（需销售确认） |
| "另外一个项目 / 换个工地 / 下一批" | `NEW_TOPIC` | 当前单收口，新问句开新单 |
| "XX 多少钱 / 报个价" | `INQUIRY` | 追加行到活跃单，无活跃单则开新单 |
| "什么时候能发 / 有票吗" | `FOLLOW_UP` | 仅记时间线，不改状态 |

意图识别置信度低于 `intent_confidence_min`（默认 0.75）时，**不自动改状态**，只在右侧栏提示"疑似下单意向，请确认"。

### 7.2 盘价硬边界

- 配置 `price_cutoff_time`（默认 `08:30`）。跨过该时刻，所有 `CURRENT` 报价版本 → `EXPIRED`，询价单本身不关闭。
- 任何过期后的报价必须生成新版本，**禁止**新旧价格混在同一版本。
- 报价版本落库时写入 `valid_until = 下一个 price_cutoff_time`。

### 7.3 空闲时间边界

| 参数 | 默认值 | 含义 |
| --- | --- | --- |
| `idle_gap` | 2 小时 | 活跃单超过该时长无新消息 → 标记"待收口"，右侧栏提示 |
| `auto_expire` | 48 小时 | 待收口后继续无成交 → EXPIRED |
| `revive_window` | 7 天 | EXPIRED 单在此窗口内客户回来，提示销售"是否复活合并" |

### 7.4 人工兜底

- **拆单**：选中若干行 → 移入新询价单；原单版本重算（生成新 CURRENT，仅含剩余行）。
- **合并**：把 EXPIRED / OPEN 单并入当前活跃单；`spec_key` 冲突时保留最新数量。
- 人工操作写入 `ticket_event`，类型 `MANUAL_SPLIT / MANUAL_MERGE`。

## 8. 报价 ↔ 询价配对算法

输入：会话存档中一条 **员工 → 客户** 方向的消息（`from` 为成员 userid）。会话存档同时记录销售在电脑侧栏、手机企微上发出的全部内容，所以不论销售在哪里回复，都会进入此算法。输出：`(inquiry_line_id, price)` 列表，或"待确认"。

```
1. 若消息是从侧栏发出的报价卡片（msgtype = link / miniprogram，URL 或 pagepath 带 qv=<quote_version_id>）
   → 解析出 quote_version_id，回填 sent_msg_id，直接确定，结束。
2. 否则视为自由文本（多为手机企微直接回复），抽取消息中的 (品名/规格片段, 价格) 对。
3. 取本单 status ∈ {PENDING, QUOTED} 的行作为候选。
4. 对每个价格：
   a. 若抽到品名/规格 → 按 spec_key 相似度匹配，score ≥ 0.85 唯一命中 → 配对
   b. 若未抽到品名，且候选中 PENDING 行恰好 1 条 → 配对到它
   c. 若未抽到品名，且 PENDING 行为 0、QUOTED 行恰好 1 条 → 视为改价
   d. 其余情况 → 标记 pairing_pending，右侧栏弹"这个 {price} 报的是哪一项？"
5. 全部价格配对成功 → 生成新 QuoteVersion（全量快照）；任一待确认 → 不生成版本，等销售选择。
```

**原则：宁可多问一次，不能配错一次。**

## 9. 规格归一化与去重

抽取字段：`product`（品名）、`spec`（截面尺寸）、`thickness`（壁厚）、`material`（材质，默认 Q235B）、`length`（定尺，默认 6m）、`qty`、`unit`。

```
"100的方管"            → {方管, 100×100, 壁厚:?, Q235B, 6m}
"100*200*2.75矩管"     → {矩管, 100×200, 2.75, Q235B, 6m}
"100×100×3.0 Q355方管" → {方管, 100×100, 3.0, Q355B, 6m}
```

`spec_key = hash(product, spec_normalized, thickness ?? "", material)`。

同单内相同 `spec_key` 再次出现：

| 情况 | 处理 |
| --- | --- |
| 无新数量 | 视为"催价 / 追问"，记时间线，不新增行 |
| 带新数量 | 更新该行 `qty`，记时间线 |
| 原行壁厚为空，本次带壁厚 | **补全**原行，不新增 |
| 壁厚不同 | 视为新规格，新增行 |

## 10. 提醒事件

所有提醒通过右侧栏"本单摘要卡"展示，并写入 `ticket_event`；重要事件同时推企微应用消息给销售。

| 事件 | 触发 | 文案模板 | CTA | 推送 |
| --- | --- | --- | --- | --- |
| `LINE_ADDED_AFTER_QUOTE` | QUOTED/CONFIRMING 单追加行 | 客户追加 {n} 项：{spec}，待报价。本单已报：{已报摘要} | 手动报价 / 按上次逻辑报价 | 是 |
| `CONFIRM_WITH_PENDING` | CONFIRMING 且存在 PENDING 行 | 客户意向下单，本单 {total} 项，{pending} 项未报价，请先补报 | 去报价 | 是，**阻断转单** |
| `CONFIRM_READY` | CONFIRMING 且全部已报 | 按 v{n} 转订单？{行摘要}。{缺数量提示} | 转订单 / 补数量 | 是 |
| `QUOTE_EXPIRED` | 报价跨过盘价截止 | 本单 v{n} 报价已过期（今日盘价已更新），客户未确认 | 按新盘价重报 | 是 |
| `PAIRING_AMBIGUOUS` | 配对算法步骤 4d | 你回复的 {price} 报的是哪一项？ | 选择行 | 否（仅侧栏） |
| `IDLE_WARNING` | 超过 `idle_gap` | 本单 {hours} 小时无动态，将于 {time} 自动收口 | 跟进 / 标记流失 | 否 |
| `REVIVE_SUGGEST` | EXPIRED 单客户回来 | 该客户 {days} 天前有一单未成交（{摘要}），是否合并到本单？ | 合并 / 忽略 | 否 |

## 11. 防重复、防错乱：五道防线

| # | 防线 | 实现 |
| --- | --- | --- |
| 1 | 消息级幂等 | 询价行、报价行都记录会话存档 `msgid` 为 `source_msg_id`，加唯一索引。存档按 `seq` 断点续拉造成的重叠区间、任务重试都不会产生重复行 |
| 2 | 规格键去重 | 同单内 `spec_key` 唯一，见第 9 节 |
| 3 | 配对强约束 | `QuoteLine.inquiry_line_id NOT NULL`；配不上不落库，见第 8 节 |
| 4 | 出站结构化 | 侧栏发出的报价卡片链接携带 `quote_version_id`，存档回流时解析即可确定配对；手机企微自由文本走模糊配对并要求确认 |
| 5 | 单活跃单约束 | 部分唯一索引保证同一「客户 × 销售」只有一张活跃单，并发消息也不会开出两张 |

另：AI 抽取结果均带 `confidence` 与 `confirmed_by`，未经销售确认的行在 UI 上显示"AI 识别"角标，转订单前必须全部确认。

## 12. 数据模型

```sql
CREATE TABLE inquiry_ticket (
  id                        BIGSERIAL PRIMARY KEY,
  ticket_no                 VARCHAR(32) NOT NULL UNIQUE,      -- IQ-20260916-001
  external_userid           VARCHAR(64) NOT NULL,             -- 企微外部联系人
  owner_userid              VARCHAR(64) NOT NULL,             -- 归属销售（企微成员）
  status                    VARCHAR(20) NOT NULL,
  opened_at                 TIMESTAMPTZ NOT NULL,
  last_activity_at          TIMESTAMPTZ NOT NULL,
  current_quote_version_id  BIGINT,
  order_id                  BIGINT,
  closed_at                 TIMESTAMPTZ,
  close_reason              VARCHAR(32)
);
CREATE UNIQUE INDEX ux_ticket_active
  ON inquiry_ticket (external_userid, owner_userid)
  WHERE status IN ('OPEN','QUOTING','QUOTED','CONFIRMING','PARTIAL_ORDERED');

CREATE TABLE inquiry_line (
  id               BIGSERIAL PRIMARY KEY,
  ticket_id        BIGINT NOT NULL REFERENCES inquiry_ticket(id),
  line_no          SMALLINT NOT NULL,
  spec_key         VARCHAR(64) NOT NULL,
  product          VARCHAR(32) NOT NULL,
  spec             VARCHAR(64),
  thickness        NUMERIC(6,2),
  material         VARCHAR(16),
  length_m         NUMERIC(6,2),
  qty              NUMERIC(12,3),
  unit             VARCHAR(8),
  status           VARCHAR(16) NOT NULL,
  pairing_pending  BOOLEAN NOT NULL DEFAULT FALSE,
  source_msg_id    VARCHAR(128) NOT NULL,       -- 会话存档 msgid（一条消息抽出多行时加 #序号后缀）
  source_seq       BIGINT,                      -- 会话存档 seq，便于回溯
  raw_text         TEXT,
  confidence       NUMERIC(4,3),
  confirmed_by     VARCHAR(64),                 -- 确认人 userid
  created_at       TIMESTAMPTZ NOT NULL,
  UNIQUE (source_msg_id),
  UNIQUE (ticket_id, spec_key)
);

CREATE TABLE quote_version (
  id              BIGSERIAL PRIMARY KEY,
  ticket_id       BIGINT NOT NULL REFERENCES inquiry_ticket(id),
  version_no      SMALLINT NOT NULL,
  status          VARCHAR(16) NOT NULL,        -- CURRENT | SUPERSEDED | EXPIRED | ACCEPTED
  valid_until     TIMESTAMPTZ NOT NULL,
  created_by      VARCHAR(64) NOT NULL,        -- 报价人 userid
  created_at      TIMESTAMPTZ NOT NULL,
  sent_msg_id     VARCHAR(128),                -- 侧栏发出报价卡片后，存档回流解析到 qv 参数时回填
  UNIQUE (ticket_id, version_no)
);
CREATE UNIQUE INDEX ux_quote_current
  ON quote_version (ticket_id) WHERE status = 'CURRENT';

CREATE TABLE quote_line (
  id                 BIGSERIAL PRIMARY KEY,
  quote_version_id   BIGINT NOT NULL REFERENCES quote_version(id),
  inquiry_line_id    BIGINT NOT NULL REFERENCES inquiry_line(id),
  price              NUMERIC(12,2) NOT NULL,
  unit               VARCHAR(8) NOT NULL,      -- 元/吨 | 元/支
  note               VARCHAR(255),
  source_msg_id      VARCHAR(128),
  UNIQUE (quote_version_id, inquiry_line_id)
);

CREATE TABLE ticket_event (
  id          BIGSERIAL PRIMARY KEY,
  ticket_id   BIGINT NOT NULL REFERENCES inquiry_ticket(id),
  type        VARCHAR(40) NOT NULL,
  payload     JSONB,
  msg_id      VARCHAR(128),
  actor       VARCHAR(16) NOT NULL,             -- customer | sales | ai | system
  created_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_event_ticket ON ticket_event (ticket_id, created_at);

-- 会话存档拉取游标：按企业维度记录已消费到的 seq，重启后从此续拉
CREATE TABLE archive_cursor (
  corp_id     VARCHAR(64) PRIMARY KEY,
  last_seq    BIGINT NOT NULL,
  updated_at  TIMESTAMPTZ NOT NULL
);
```

## 13. 接口草案

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/inquiry/tickets/active?external_userid=&owner_userid=` | 取当前活跃单（侧栏摘要卡数据源；侧栏通过 JS-SDK `getCurExternalContact` 取得 external_userid） |
| GET | `/api/inquiry/tickets/{id}` | 单详情：行、版本、事件、提醒 |
| POST | `/api/inquiry/tickets/{id}/lines` | 手动追加行 |
| PATCH | `/api/inquiry/lines/{id}` | 修改规格 / 数量 / 确认 AI 识别 |
| POST | `/api/inquiry/tickets/{id}/quotes` | 提交报价（行 + 价格），生成新版本，返回报价卡片参数（title / desc / url 含 `qv=`），由侧栏调用 JS-SDK `sendChatMessage` 发出 |
| POST | `/api/inquiry/lines/{id}/pair` | 解决配对歧义：`{ price, source_msg_id }` |
| POST | `/api/inquiry/tickets/{id}/confirm` | 转订单：`{ line_ids[], quote_version_id }` |
| POST | `/api/inquiry/tickets/{id}/split` | 拆单：`{ line_ids[] }` |
| POST | `/api/inquiry/tickets/{id}/merge` | 合并：`{ from_ticket_id }` |
| POST | `/api/inquiry/tickets/{id}/close` | 标记 LOST / 手动收口 |
| GET | `/api/inquiry/config` · PUT | 读写第 7 节参数 |

内部事件（由会话存档消费者触发）：

- `ArchiveMessageReceived(msgid, seq, from, tolist, msgtype, content, msgtime)`
  - `from` 为外部联系人 → 客户消息：意图识别 → 抽取 → 归并。
  - `from` 为成员 userid → 销售消息：走第 8 节配对（卡片直接确定，自由文本模糊配对）。
  - 群聊消息（`roomid` 非空）跳过。
- 定时任务：`price_cutoff` 到点批量过期版本；`idle_gap` / `auto_expire` 扫描。

## 14. 与企微会话存档 / 右侧栏的集成点

```
                    ┌──────────────── 企业微信 ────────────────┐
客户 ◀── 单聊 ──▶ 销售（电脑 / 手机企微）                          │
                    │                                            │
                    │ 会话存档 SDK 拉取（seq 游标）→ RSA 解密       │
                    ▼                                            │
             询价会话服务 ── 意图 / 抽取 / 归并 / 配对 / 版本 ──┐   │
                    │                                        │   │
                    │ 提醒事件                                │   │
                    ▼                                        │   │
        企微右侧栏「本单摘要卡」（JS-SDK 取当前客户）            │   │
                    │                                        │   │
        销售点「报价卡片」→ 服务生成版本 vN + 卡片链接（qv=vN）   │   │
                    │                                        │   │
        JS-SDK sendChatMessage 发卡片 ─────────────────────▶ 客户  │
                    │                                            │
        该卡片消息进入会话存档 → 回流 → 解析 qv → 回填 sent_msg_id  │
                    └────────────────────────────────────────────┘
```

- 会话存档是**双向**的：客户消息和销售消息（无论电脑侧栏还是手机企微）都经同一条链路进入服务，不需要单独的出站上报。
- 摘要卡挂在企微右侧栏"报价"页签顶部，替代原"报价清单"的顶部区域；原报价清单成为"当前版本编辑器"。
- 报价卡片链接形如 `https://<域名>/q/<ticket_no>?qv=<version_no>`，客户点开是报价 H5 / 小程序页；服务端从存档回流的 `link.url` 解析 `qv`，形成闭环。
- 前置条件：企业已开通会话存档并为相关销售开启；客户端首次会收到企微官方的存档提示，这是合规要求，不可绕过。

## 15. 配置项

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `inquiry.idle_gap` | `PT2H` | 空闲提醒阈值 |
| `inquiry.auto_expire` | `PT48H` | 自动收口阈值 |
| `inquiry.revive_window` | `P7D` | 复活合并建议窗口 |
| `inquiry.price_cutoff_time` | `08:30` | 盘价更新时刻（Asia/Shanghai） |
| `inquiry.intent_confidence_min` | `0.75` | 意图自动生效阈值 |
| `inquiry.spec_match_min` | `0.85` | 配对相似度阈值 |
| `inquiry.default_material` | `Q235B` | 未提材质时默认 |
| `inquiry.default_length_m` | `6` | 未提定尺时默认 |
| `inquiry.block_order_on_pending` | `true` | 有未报行时阻断转单 |

## 16. 验收场景

### 场景 A · 主流程（两次询价一单成交，后续新单）

| 步 | 时间 | 事件 | 期望 |
| --- | --- | --- | --- |
| 1 | 09:00 | 客户：100 的方管多少钱一吨 | 该「客户 × 销售」无活跃单 → 新建 IQ-…-001 OPEN；行1 方管 100×100 PENDING |
| 2 | 09:01 | 销售：3000 | 唯一 PENDING → 配对行1；v1 CURRENT {行1 3000}；单 QUOTED |
| 3 | 09:02 | 客户：100\*200\*2.75 的矩管呢 | 行2 PENDING；单 QUOTED→QUOTING；事件 `LINE_ADDED_AFTER_QUOTE` |
| 4 | 09:03 | 销售：矩管 3150 | 品名命中行2；v2 CURRENT {3000, 3150}；v1 SUPERSEDED；单 QUOTED |
| 5 | 09:05 | 客户：行，下单吧 | 意图 CONFIRM ≥0.75；单 CONFIRMING；事件 `CONFIRM_READY`（提示缺数量） |
| 6 | 09:06 | 销售补数量并点转订单，侧栏发出订单确认卡片 | 订单 1 张 2 行；v2 ACCEPTED；单 ORDERED；卡片经存档回流，解析 `qv=2` 回填 `sent_msg_id` |
| 7 | 15:30 | 客户：再要点 50 角钢 | 无活跃单 → 新建 IQ-…-002 OPEN，不混入 001 |

### 场景 B · 配对歧义

| 步 | 事件 | 期望 |
| --- | --- | --- |
| 1 | 客户：100 方管和 80 方管各多少 | 行1、行2 PENDING |
| 2 | 销售在手机企微回：3000（经存档回流） | 两个 PENDING 且无品名 → 不生成版本；行1、行2 `pairing_pending`；事件 `PAIRING_AMBIGUOUS` |
| 3 | 销售在侧栏选"100 方管" | v1 CURRENT {行1 3000}；行2 仍 PENDING；单 QUOTING |

### 场景 C · 盘价过期 + 未报阻断

| 步 | 时间 | 事件 | 期望 |
| --- | --- | --- | --- |
| 1 | 昨日 16:40 | 客户问 100 方管，销售报 3000 | v1 CURRENT，`valid_until` = 今日 08:30 |
| 2 | 今日 08:30 | 盘价更新 | v1 EXPIRED；单仍 QUOTED；事件 `QUOTE_EXPIRED` |
| 3 | 今日 09:12 | 客户：那就来 10 吨 | 单 CONFIRMING；`block_order_on_pending` 生效：无 CURRENT 版本，**阻断转单**，提示重报 |
| 4 | 今日 09:13 | 销售按新盘价重报 3080 | v2 CURRENT {3080}；行1 数量 10 吨；`CONFIRM_READY` |

### 场景 D · 幂等

- 同一 `msgid` 重复消费 3 次（存档按 seq 断点续拉出现重叠区间）→ 仅 1 条询价行、1 个版本。
- 服务重启后从 `archive_cursor.last_seq` 续拉，期间到达的消息不丢、不重。
- 一条客户消息抽出 2 行 → `source_msg_id` 分别为 `<msgid>#1`、`<msgid>#2`，重放同样只保留 2 行。

## 17. 指标

| 指标 | 目标 |
| --- | --- |
| 一次沟通被拆成多单的比例 | < 3% |
| 配对歧义率（需人工选择的报价 / 全部报价） | < 15%，随报价卡片使用率上升下降 |
| 配对错误率（人工纠正 / 自动配对） | < 1% |
| 转订单时存在未确认 AI 行的比例 | 0（前端强制） |
| 提醒到成交中位时长 | 观察指标 |

## 18. 风险与开放问题

| 风险 / 问题 | 应对 |
| --- | --- |
| 手机企微自由文本报价配对不准 | 产品上引导在侧栏用报价卡片；模糊配对全部要求确认 |
| 会话存档有秒级延迟，销售刚回完价侧栏可能晚几秒才显示配对结果 | 侧栏轮询 / 推送刷新；提示"正在同步会话记录" |
| 会话存档未对某销售开启，其会话无法归并 | 预检自查列出未开启成员；摘要卡显示"该会话未开通存档" |
| 盘价截止时间因品类不同 | v1 全局一个时刻；v2 支持按品类配置 |
| 同一客户同时和两个销售聊 | 按「客户 × 销售」分单是设计使然；v2 在摘要卡提示"该客户在同事 X 处有活跃单" |
| 意图识别误判"下单" | 阈值 0.75 + 销售确认，CONFIRMING 不自动转单 |
| 部分成交后剩余行长期挂着 | PARTIAL_ORDERED 同样受 `auto_expire` 约束 |
| 价格单位混用（元/吨 vs 元/支） | `QuoteLine.unit` 必填；抽取不到单位时默认按品类配置并标黄 |

## 19. 实施拆分（按组件）

1. **领域与存储**：5 张表 + 状态机 + 唯一约束；单元测试覆盖 6.1–6.3 全部迁移。
2. **抽取与归一化**：规格解析、`spec_key`、意图分类；用真实聊天样本做回归集。
3. **配对与版本**：第 8 节算法 + 全量快照生成；场景 A/B/D 测试。
4. **边界与定时**：盘价过期、空闲扫描、复活建议；场景 C 测试。
5. **提醒与 UI**：企微右侧栏摘要卡、配对确认弹层、拆合操作；对齐效果图。
6. **会话存档与侧栏集成**：存档消费者按 `from` 分流到归并 / 配对；侧栏 JS-SDK 发报价卡片，存档回流解析 `qv` 回填 `sent_msg_id`。
