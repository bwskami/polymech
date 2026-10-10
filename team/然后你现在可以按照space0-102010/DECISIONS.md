# 决策留痕（DECISIONS）

> 记录「代你决策」与「你拍板」的每一项：选了什么 / 备选是什么 / 为什么不选 / 留痕时间。

## 方案确认门（2026-10-09）

| 决策点 | 选择 | 备选 | 理由（为何选/为何不选） |
|---|---|---|---|
| 是否执行方案 | **执行** | 修改方案 | 前置流水线（调研/澄清/设计/规格评审）全部通过，spec-review 修订后无 needs_revision |
| 决策⑦ 载速接线位置 | **载速在 move 输入前叠加（travel HEAD 注入）** | 本次先不接探针 | 载速作为 move 输入不经过 collide 二次夹取，语义最贴近参考 `space$addProbeVelocity` |
| 决策⑧ 扫掠数据源 | **切 mps PhysicalBody** | ClientBody 适配层 | 用户「照抄优先」：snapshotBodies 逐字照抄、扫掠器 100% 对齐参考；一次到位，将来 S6 二选一选 mps 层时扫掠器不用改 |

## lead 补充澄清（2026-10-09）

- **发现**：mps 客户端物理世界当前为**空**——服务端从未 `new ServerPhysicalBody`（全仓库仅 `ServerPhysicalBody.loadFromTag` 一处读存档，存档永远为空），物理体数据实际全走主动栈 `PhysicsBodyTracker → ClientPhysicsWorld.ClientBody`。
- **后果**：切 mps 需新增「主动栈 → mps 层」镜像链（P0 任务），代价 = 过渡期双份物理体数据 + 数据转换层（约 100–200 行自创适配）。
- **量化对比（告知用户后仍维持）**：切 mps = 照抄 snapshotBodies 约 50 行 + 自创镜像层约 100–200 行；ClientBody 适配 = 照抄核心扫掠算法约 300 行 + 仅重写 snapshotBodies 约 50 行。战略上切 mps 一次到位。
- **用户最终确认**：维持「切 mps PhysicalBody」。
- [2026-10-09T11:39:46.883Z] **快速档**
