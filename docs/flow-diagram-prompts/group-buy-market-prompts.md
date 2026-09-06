# 以牌惠友（group-buy-market）流程图生成提示词

下面 4 份提示词基于 `D:\My-Study\iedaProjects\group-buy-market` 当前源码。执行任一提示词时，必须调用本地 `process-flow-diagram` skill，输出单个自包含 HTML 文件，并按 skill 要求预览和修正。项目内“库存”特指拼团队伍剩余名额，不是 SKU 实物库存。

## GBM-01：分层调用链路

### 提示词正文

请为“以牌惠友”生成一张“从客户端 HTTP 请求进入系统到领域、仓储和外部基础设施”的调用链流程图，输出 `outputs/group-buy-market-call-chain.html`。这不是静态包依赖拓扑，而是按请求方向展开的调用链。采用 6 条纵向泳道：① 浏览器/上游交易系统；② API DTO/Response 契约；③ Trigger 适配器；④ Domain 领域服务与规则框架；⑤ Repository/Port 接口及 Infrastructure 实现；⑥ MySQL/Redis/RabbitMQ/HTTP 外部资源。

顶端先画 Maven 模块职责卡：

- `group-buy-market-api`：`IMarketIndexService`、`IMarketTradeService` 与 DTO/Response 契约。
- `group-buy-market-trigger`：REST Controller、Rabbit listener、scheduled job，负责入站适配。
- `group-buy-market-domain`：activity/trade 领域模型、策略树、责任链、聚合、repository/port 接口，不直接依赖具体 MySQL/Redis。
- `group-buy-market-infrastructure`：Repository 实现、MyBatis DAO、Redisson、DCC、MQ publisher、HTTP gateway。
- `group-buy-market-app`：Spring Boot 启动、装配、配置、mapper XML。
- `group-buy-market-types`：通用枚举、异常、常量、事件基类。

主体必须画出 4 条真实入口链，并用不同颜色区分：

1. 首页查询链：浏览器 `POST /api/v1/gbm/index/query_group_buy_market_config` -> `MarketIndexController`（限流/参数校验）-> `IIndexGroupBuyMarketService.indexMarketTrial` -> `IndexGroupBuyMarketServiceImpl` -> XFG wrench 策略树 `RootNode -> SwitchNode -> MarketNode -> TagNode -> EndNode/ErrorNode` -> `IActivityRepository` -> `ActivityRepository` -> Redis 缓存/DCC/BitSet + MyBatis DAO/MySQL；返回后 Controller 继续调用进行中队伍查询与统计查询，组装 `GoodsMarketResponseDTO`。
2. 锁单链：上游商城 `POST /api/v1/gbm/trade/lock_market_pay_order` -> `MarketTradeController` 幂等查询/试算 -> `ITradeLockOrderService` -> `TradeLockOrderService` -> 责任链 `ActivityUsabilityRuleFilter -> UserTakeLimitRuleFilter -> TeamStockOccupyRuleFilter` -> `ITradeRepository` -> `TradeRepository` -> Redis 团队库存 + MySQL `group_buy_order`/`group_buy_order_list`。
3. 支付结算链：`POST .../settlement_market_pay_order` -> `TradeSettlementOrderService` -> 责任链 `SCRuleFilter -> OutTradeNoRuleFilter -> SettableRuleFilter -> EndRuleFilter` -> 聚合 `GroupBuyTeamSettlementAggregate` -> `TradeRepository.settlementMarketPayOrder` 事务 -> MySQL 状态/计数与 `notify_task` -> `TradeTaskService -> ITradePort/TradePort` -> HTTP 回调或 RabbitMQ。立即异步通知失败时由 `GroupBuyNotifyJob` 再扫本地消息表补偿。
4. 退单/补偿链：HTTP 退单或 `TimeoutRefundJob` -> `TradeRefundOrderService` -> `DataNodeFilter -> UniqueRefundNodeFilter -> RefundOrderNodeFilter` -> 三种 refund strategy -> `TradeRepository` 事务更新订单/团队并写 `notify_task` -> MQ -> `RefundSuccessTopicListener` -> `restoreTeamLockStock` -> Redis 恢复计数。

箭头上必须标注跨层传递对象：`MarketProductEntity/TrialBalanceEntity`、`GroupBuyOrderAggregate/MarketPayOrderEntity`、`GroupBuyTeamSettlementAggregate/NotifyTaskEntity`、`GroupBuyRefundAggregate/TeamRefundSuccess`。Repository/Port 接口放在 Domain 泳道边界，具体实现放在 Infrastructure 泳道，清楚表达依赖倒置。

底部数据卡列出：MySQL 表 `sku/sc_sku_activity/group_buy_activity/group_buy_discount/group_buy_order/group_buy_order_list/notify_task/crowd_tags*`；Redis 用途为配置缓存、DCC、标签 BitSet、团队库存计数/幂等锁；RabbitMQ 用途为成团通知和退单库存恢复；OkHttp 用途为 HTTP callback。不要把 Controller 直接连到 DAO，也不要把 Dify proxy 画进拼团核心链，它是 `MarketIndexController` 中的旁路功能。

源码依据：根 `pom.xml` 与各模块 pom、`MarketIndexController`、`MarketTradeController`、`IndexGroupBuyMarketServiceImpl`、`TradeLockOrderService`、`TradeSettlementOrderService`、`TradeRefundOrderService`、`ActivityRepository`、`TradeRepository`、`TradePort`。

## GBM-02：首页营销试算

### 提示词正文

请为“以牌惠友”生成一张“拼团首页营销配置查询与优惠试算”的详细流程图，输出 `outputs/group-buy-market-market-trial.html`。采用 4 条泳道：浏览器/Controller、策略树、仓储与并行查询、返回组装。主链从 `query_group_buy_market_config` 到 `GoodsMarketResponseDTO`，同时展示 DCC 拦截、无活动错误、人群不可见/不可参与以及限流 fallback。

按以下真实顺序绘制：

1. `@RateLimiterAccessInterceptor(key=userId, permitsPerSecond=1, blacklistCount=1)` 先限流；命中 fallback 返回 `RATE_LIMITER`。Controller 校验 userId/source/channel/goodsId，非法返回 `ILLEGAL_PARAMETER`。
2. 构建 `MarketProductEntity` 调 `indexMarketTrial`，工厂返回 `RootNode` 作为 `StrategyHandler`；XFG wrench 的 `AbstractMultiThreadStrategyRouter.apply` 负责节点的 multiThread/doApply/router 调度。
3. `RootNode` 再校验核心参数，成功路由 `SwitchNode`。
4. `SwitchNode` 依次查询 DCC：`downgradeSwitch=true` -> E0003；`cutRange(userId)=false` -> E0004；通过后路由 `MarketNode`。
5. `MarketNode.multiThread` 用线程池并行启动两个 FutureTask，超时 5000ms：A `QueryGroupBuyActivityDiscountVOThreadTask`，若 request.activityId 为空先按 source+channel+goodsId 查 `sc_sku_activity` 得 activityId，再查活动+折扣；活动与折扣优先走 `AbstractRepository.getFromCacheOrDb`，缓存开关关闭则直查 MySQL。B `QuerySkuVOFromDBThreadTask` 按 goodsId 查 SKU。两者写入 DynamicContext。
6. 若活动/折扣/SKU 缺失，`MarketNode.get` 路由 `ErrorNode`，返回 E0002；注意当前实际 bean 是 `MarketNode` 的 FutureTask 版本，`MarketNode2CompletableFuture` 没有启用 `@Service`，只能在信息卡标成替代示例。
7. 根据 `groupBuyDiscount.marketPlan` 从 Spring map 选择折扣策略：`ZJ` 直减、`ZK` 折扣、`MJ` 满减、`N` N 元购。若 discountType=TAG，计算前先用 Redis BitSet 判定优惠人群，不在人群则 payPrice=originalPrice。缺少策略返回 E0001。写 deductionPrice 和 payPrice。
8. `TagNode` 处理活动可见/参与限制：无 tagId 时 visible=true、enable=true；有 tag 时 `isTagCrowdRange`，分别计算 `configuredVisible || isWithin` 与 `configuredEnable || isWithin`。然后 `EndNode` 组装 `TrialBalanceEntity`。
9. Controller 从试算结果取 activityId，继续查询：当前用户进行中队伍 ownerCount=1；随机其他队伍 randomCount=2（仓储先查 2 倍再 shuffle 截取）；再按 activityId 统计 allTeamCount、complete team count、lock_count 总参与人数。
10. 组装 goods 原价/优惠/支付价、team 列表与倒计时、TeamStatistic，返回 SUCCESS；任意未捕获异常返回 UN_ERROR。

图中必须画出数据库/缓存细节：活动与折扣为 cache-aside；SKU 当前直查 DB；人群标签读取 Redis RBitSet；进行中队伍先查 `group_buy_order_list` 再批量查 `group_buy_order` 并过滤 `status=0`、`target_count>lock_count`、未过期。

状态卡列出 DynamicContext 字段：activityDiscount、sku、deductionPrice、payPrice、visible、enable。不要把首页试算画成扣库存或创建订单，它是纯查询/计算；真正库存与订单发生在 GBM-03/04。

源码依据：`MarketIndexController.queryGroupBuyMarketConfig`、`DefaultActivityStrategyFactory`、`RootNode`、`SwitchNode`、`MarketNode`、`TagNode`、`EndNode`、`ErrorNode`、四个 DiscountCalculateService、`ActivityRepository`、相关 mapper XML。

## GBM-03：拼团交易全生命周期

### 提示词正文

请为“以牌惠友”生成一张“从锁单到支付结算、成团通知、退单和超时补偿”的端到端业务流程图，输出 `outputs/group-buy-market-trade-lifecycle.html`。采用三段式时间线：A 锁单（开团/参团）；B 支付结算与成团；C 逆向退单。泳道至少包含上游商城、Controller/Domain、MySQL 事务、Redis、通知任务/MQ。状态变化必须明确标注，不得把 lock_count 和 complete_count 混成一个计数。

A 段锁单按顺序绘制：

1. `POST lock_market_pay_order` 参数校验；HTTP notify 类型必须有 notifyUrl。
2. 用 userId+outTradeNo 查 `group_buy_order_list`。若已存在且明细状态 CREATE(0)，直接返回已有 order/价格，作为幂等成功。
3. 有 teamId 时先查进度；`targetCount == lockCount` 则 E0006，避免已满队伍继续下单。
4. 重新走 GBM-02 的营销试算；visible/enable 任一 false 返回 E0007。
5. `TradeLockOrderService` 执行规则责任链：活动状态 EFFECTIVE 与时间有效；用户参与次数未达到 takeLimit；有 teamId 时预占 Redis 团队名额，失败 E0008；无 teamId 表示首次开团，不走 Redis 预占。
6. 构建 `GroupBuyOrderAggregate` 并进入 `TradeRepository.lockMarketPayOrder` 事务。新团：生成 teamId，插入 `group_buy_order(target_count, complete_count=0, lock_count=1,status=PROGRESS,validEnd=now+validTime)`。老团：条件更新 `lock_count=lock_count+1 where lock_count<target_count`，影响行非 1 则 E0005。
7. 生成 orderId，插入 `group_buy_order_list(status=CREATE,outTradeNo,bizId=activityId_userId_(takeCount+1))`；唯一键冲突返回 INDEX_EXCEPTION。若 DB 锁单异常，服务层增加 recoveryTeamStock 以抵消先前 Redis 预占，然后抛错。

B 段支付结算按顺序绘制：

1. 上游支付完成调用 `POST settlement_market_pay_order`，携带 outTradeTime。
2. 结算责任链：SC source/channel 黑名单拦截 E0105；外部单不存在或已 CLOSE 拦截 E0104；查询 team 并要求 outTradeTime 早于 validEnd，否则 E0106；EndFilter 返回 team snapshot。
3. `TradeRepository.settlementMarketPayOrder` 事务：明细 `CREATE -> COMPLETE(1)` 且写 outTradeTime；团队 `complete_count + 1 where < target_count`。
4. 使用进入事务前 snapshot 判断 `targetCount - completeCount == 1`。若是最后一名支付者，团队 `PROGRESS -> COMPLETE(1)`；查询本团所有 COMPLETE 明细 outTradeNo；同事务插入 `notify_task(category=trade_settlement,status=0)`。否则只完成本名额，不建成团通知。
5. 有 notifyTask 时线程池立即调用 `TradeTaskService`；`TradePort` 先按 task lockKey 抢 Redisson 锁，再按 notifyType 分支：HTTP -> OkHttp callback；MQ -> EventPublisher/RabbitMQ。成功把任务设 1；失败重试设 2，notifyCount>4 后设 3。`GroupBuyNotifyJob` 定时扫描 0/2 状态再次补偿。

C 段逆向流程按顺序绘制：

1. 入口一是 `POST refund_market_pay_order`，入口二是 `TimeoutRefundJob` 每分钟抢分布式锁并扫描最多 10 条超时、未支付、CREATE 明细。
2. 退单责任链先加载 MarketPayOrder + GroupBuyTeam；明细已 CLOSE 时直接返回 REPEAT；否则按“团队状态 + 明细状态”选择策略。
3. `PROGRESS + CREATE -> Unpaid2Refund`：明细 CREATE->CLOSE；团队 lock_count-1；写 MQ notify_task(type=unpaid_unlock)。
4. `PROGRESS + COMPLETE -> Paid2Refund`：明细 COMPLETE->CLOSE；团队 lock_count-1、complete_count-1；写 MQ notify_task(type=paid_unformed)。
5. `COMPLETE/COMPLETE_FAIL + COMPLETE -> PaidTeam2Refund`：明细 CLOSE；团队 lock_count-1、complete_count-1；若退后仍有人，状态 COMPLETE_FAIL(3)，若最后一人退单，状态 FAIL(2)；写 MQ notify_task(type=paid_formed)。
6. 三种策略均尝试立即执行通知，失败由本地消息任务补偿。Refund MQ 由 `RefundSuccessTopicListener` 消费；未成团的两种类型恢复 Redis 可用名额，已成团类型明确不恢复，因为队伍生命周期已结束。

在图底部画两条状态机：明细 `CREATE(0) -> COMPLETE(1) -> CLOSE(2)` 或 `CREATE -> CLOSE`；团队 `PROGRESS(0) -> COMPLETE(1)`，成团后退部分订单 `-> COMPLETE_FAIL(3)`，最后一人也退 `-> FAIL(2)`。标注 notify_task `WAIT(0) -> SUCCESS(1) / RETRY(2) -> ERROR(3)`。

源码依据：`MarketTradeController`、锁单/结算/退单三个 Service 与 Factory/Filter、`TradeRepository`、`TradeTaskService`、`TradePort`、`GroupBuyNotifyJob`、`TimeoutRefundJob`、三个 mapper XML。

## GBM-04：团队库存占用与恢复

### 提示词正文

请为“以牌惠友”生成一张“拼团队伍名额库存的 Redis 预占、MySQL 确认和最终一致性恢复”流程图，输出 `outputs/group-buy-market-team-stock.html`。采用左右对称布局：左半部“占用/扣减”，右半部“失败与退单恢复”，中间放 Redis 与 MySQL 两套计数的关系公式。必须明确这不是 SKU 库存，也不是支付时才扣减。

先在顶部定义三个量：

- `target_count`：活动目标人数，团队容量。
- MySQL `group_buy_order.lock_count`：已经成功创建锁单明细的名额数；新团初始为 1，老团用条件 SQL +1。
- Redis `teamStockKey = group_buy_market_team_stock_key_{activityId}_{teamId}`：老团并发预占序号计数；`recoveryTeamStockKey = teamStockKey + _recovery`：失败/退单释放量。源码计算 `occupy = INCR(teamStockKey) + 1`，额外 +1 对应开团者已经占用的首个名额；允许条件是 `occupy <= target + recoveryCount`。

占用主链按顺序绘制：

1. 首次开团 `teamId blank`：`TeamStockOccupyRuleFilter` 直接通过，不操作 Redis；DB 事务插入团队，`lock_count=1`。
2. 加入老团：先读 recoveryCount（不存在按 0）；原子 `INCR teamStockKey` 后 +1 得 occupy；若 `occupy > target + recoveryCount`，返回 E0008。注意 INCR 已发生，源码不回减，而是通过上限与 recoveryCount 模型吸收历史失败量。
3. 未超限时创建兜底唯一 lockKey `teamStockKey_{occupy}`，`SETNX`，TTL 为 `validTime + 60` 分钟。加锁失败返回 false/E0008。
4. Redis 预占成功后才进入 MySQL 锁单事务：`update group_buy_order set lock_count=lock_count+1 where team_id=? and lock_count<target_count`，影响行必须为 1；再插入明细。这里是数据库最终确认。

失败恢复分两类绘制：

1. Redis 已预占、随后 DB update/insert/唯一键失败：`TradeLockOrderService.catch -> recoveryTeamStock(recoveryTeamStockKey)`，即 recoveryCount +1；首次开团 recovery key 为空，跳过。此路径是同步失败补偿。
2. 已成功锁单后发生业务退单：数据库事务先把明细设 CLOSE，并按退款类型把团队 lock_count（必要时 complete_count）减 1，同时同事务写 `notify_task`；任务通过 MQ 发布 `TeamRefundSuccess`。消费者 `RefundSuccessTopicListener -> restoreTeamLockStock -> refund strategy.reverseStock`。

MQ 恢复分支必须区分：

- `unpaid_unlock` 和 `paid_unformed`：生成 recoveryTeamStockKey；用 `refund_lock_{orderId}` 做幂等 SETNX，成功后 recoveryCount +1；处理异常删除幂等锁并抛错，让 MQ 重试。图中 TTL 参数按源码标注为“意图 30 天，实际 setNx 传值/TimeUnit 需单独审计”，不要擅自改成已修复实现。
- `paid_formed`：已成团退单只改 MySQL 团队/明细状态，`PaidTeam2RefundStrategy.reverseStock` 明确不增加 Redis recoveryCount，因为这个团队已结束，不再接纳新成员。

用一条一致性时间线举例 `target=3`：开团者 DB lock=1；第一个参团者 Redis INCR 得 1、occupy=2，DB lock=2；第二个参团者 occupy=3，DB lock=3；下一请求 occupy=4>3 被拒；若前一锁单 DB 失败，recovery=1，后续上限变为 4；若未成团退单，DB lock-1 且通过本地消息+MQ最终 recovery+1。示例只用于解释公式，不能替代主链。

图中用粗实线表示同步扣减/确认，用虚线表示补偿和最终一致性，用玫红表示超限/SETNX/SQL 失败。底部风险边界卡写明：Redis 只保护老团并发入口，MySQL 条件更新仍是最终防线；recoveryCount 不是把 INCR 回滚，而是扩大可接受预占上限；`notify_task` 保证消息可补偿，`refund_lock_{orderId}` 保证重复消费不重复恢复。

源码依据：`TeamStockOccupyRuleFilter`、`TradeLockRuleFilterFactory`、`TradeRepository.occupyTeamStock/recoveryTeamStock/refund2AddRecovery`、`TradeLockOrderService.lockMarketPayOrder`、`group_buy_order_mapper.xml`、三个 refund strategy、`RefundSuccessTopicListener`、`TradeTaskService/notify_task`。
