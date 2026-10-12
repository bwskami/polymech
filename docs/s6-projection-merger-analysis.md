# S6 双投影系统合并方案分析

> 生成日期: 2026-10-11 · 关联: [mps-clone-plan.md](mps-clone-plan.md) §11 / §20

## 1. 背景

MEMORY 记录（2026 年用户拍定方案 A）：MPS 物理体层克隆**字面照抄**，含第二个 `ProjectionManager`，代价是仓库内并存两套投影系统，S6 必须二选一。

## 2. 现状对比

| 维度 | `physics.ProjectionManager` | `mps.ProjectionManager` |
|------|---------------------------|------------------------|
| **位置** | `com.mss.polymech.physics` | `com.mss.polymech.mps.physical.manger` |
| **代码行数** | ~948 行 | ~1439 行 |
| **来源** | 项目早期自制 | space 0.1.3 MPS 克隆 |
| **地皮槽位** | `SLOT_SIZE=128`, `SLOT_SPACING=192`, `SLOT_MIN_Y=-64` | 同左（slot*192-64） |
| **槽位分配** | TreeSet 复用最小空闲号 | ProjectionSlotData 存档，不复用 |
| **脏块标记** | `Long Set` + `MAX_FLUSH_PER_TICK=8` | 位压缩 `packDirtyKey` + tick 批量 flush |
| **方块复制** | 初步实现 | 完善：copyBlock / readProjectionData / 全生命周期 |
| **破坏/放置** | 通过 mixin 转发 | 直接实现 removeBlock / destroyBlock / handleInteraction |
| **交互转发** | 基础 | 三态（攻击/停手/使用）+ 服务端自身射线 |
| **BE 同步** | 基础 | 完善：SyncPhysicalBodyBlockEntity 网络包 |
| **挖掘进度** | 无 | SyncPhysicalBlockBreakProgress |
| **音效/事件搬运** | 有：SoundTarget / mapSoundPoint / 统计计数 | 无（由 mixin 处理） |
| **区块强制加载** | 直接强制加载 | 同左 |
| **Mixin 配套** | 5 个（Container / ChunkDirty / DropRelocation / LevelEvent） | 1 个（MixinLevelChunk） |
| **命令集成** | 完整 /polymech 子命令（状态/清槽位/声音/调试） | 无 |

### 引用方统计

**`physics.ProjectionManager`** 被以下文件引用（40 处）：
- `ModCommands.java` — 完整命令套件（状态查询/清除槽位/声音搬运调试）
- `ProjectionLevelEventMixin.java` — 音效/事件搬运
- `ProjectionDropRelocationMixin.java` — 掉落物/经验球搬运
- `ProjectionContainerMixin.java` — 容器交互
- `ProjectionChunkDirtyMixin.java` — 脏区块标记

**`mps.ProjectionManager`** 被以下文件引用（6 处）：
- `SpaceBlockPlacement.java` — 投影维度上放置方块
- `MixinLevelChunk.java` — 投影维度脏区块标记
- `CollisionPhysicalServerAction.java` — 开服初始化投影世界
- `ServerPhysicalBody.java` — 物理体关联投影槽位
- `PhysicalBodyInteractionPacket.java` — 交互包处理

## 3. 技术差异分析

### 3.1 槽位管理策略
- **旧系统**: `TreeSet<USED_SLOTS>` 复用最小空闲号。优点：节约地皮；缺点：客户端可能残留旧体镜像。
- **MPS 克隆**: 自增序号，不复用。优点：客户端镜像与槽位一对一无需担心残留；缺点：理论上限少 192×N 偏移空间。

### 3.2 脏块处理
- **旧系统**: 直接记 `Long Set<ChunkPos>`，每 tick 最多 flush 8 个。
- **MPS 克隆**: 位压缩 `packDirtyKey`，`slot<<9 | subchunk` 编码，批量 flush + 空地皮自动回收。

**结论**: MPS 版本更高效（位运算 vs HashSet），且自带空槽回收。

### 3.3 功能完备度
MPS 版本在以下方面明显胜过旧系统：
- 完整的破坏/放置管线（含原版事件触发、掉落、工具损耗、邻块更新）
- 挖掘进度同步
- 服务端自身射线做交互验证（防伪）
- 交互包客户端→服务端管线
- 方块实体同步网络包
- light update 处理

旧系统唯一胜过 MPS 版本的地方是**音效/事件搬运**和**命令集成**——但这两样是上层功能，不是投影核心逻辑。

## 4. 合并方案

### 方案 A（推荐）：保留 MPS 版，淘汰旧版

**步骤**：
1. 将 `physics.ProjectionManager` 的音效/事件搬运逻辑移植到 `mps.ProjectionManager`
2. 将命令套件（`ModCommands.java` 中引用旧系统的代码）改为引用 MPS 版
3. 将 5 个旧 mixin（ProjectionLevelEvent/ProjectionDropRelocation/ProjectionContainer/ProjectionChunkDirty）改为适配 MPS 版
4. 删除 `physics.ProjectionManager.java`

**预估工作量**：1-2 个 sprint
**风险**：中 — 音效搬运和掉落物搬运的接口需要移植到 MPS 版，这些逻辑在 MPS 版里不是投影核心职责，但旧系统做得完整

### 方案 B：保留旧版，淘汰 MPS 版

**步骤**：
1. 把 MPS 版的破坏/放置/交互/挖掘进度逻辑全部移植到旧版
2. 把 `SpaceBlockPlacement`、`CollisionPhysicalServerAction`、`ServerPhysicalBody` 的引用改为旧版
3. 删除 `mps.ProjectionManager.java`

**预估工作量**：2-4 个 sprint
**风险**：高 — MPS 版的交互/破坏管线是与 MPS 的物理体/网络系统深度耦合的，拆出来适配旧版的代价高，且有回归风险

### 推荐：方案 A

理由：
1. **功能更完备** — MPS 版已实现全部核心职责，旧版缺少破坏/放置/交互管线
2. **与 MPS 黏合度低** — MPS 版虽然被 MPS 子系统引用，但职责定义清晰；音效/命令是上层功能，容易移植
3. **符合"照抄优先"原则** — 用户明确要求照 space 的做法走

## 5. 迁移清单

### 必须移植的功能
- [ ] 音效搬运（`mapSoundPoint` / `SoundTarget` / 统计计数）
- [ ] 掉落物/经验球搬运
- [ ] 容器交互重定向
- [ ] 命令集成（`/polymech projection` 子命令）

### 必须更新的引用方
- [ ] `ModCommands.java` — 全量引用旧版
- [ ] 5 个旧 mixin — 改为调用 MPS 版或删除
- [ ] `SpaceBlockPlacement.java` — 已验证指向新版（无需改）
- [ ] `CollisionPhysicalServerAction.java` — 已验证指向新版（无需改）
- [ ] `ServerPhysicalBody.java` — 已验证指向新版（无需改）

### 合并后删除的文件
- [ ] `src/main/java/com/mss/polymech/physics/ProjectionManager.java`

## 6. 风险与对策

| 风险 | 等级 | 对策 |
|------|------|------|
| 音效搬运移植后行为不一致 | 中 | 命令输出对比 + 实机检验 |
| 槽位管理策略改变（不复用→自增） | 低 | 存档迁移脚本 |
| MPS 版无音效搬运代码→需新写 | 中 | 参考旧版逻辑提取公共工具类 |
| MPS 版 1439 行 vs 旧版 948 行=更多代码 | 低 | 压缩空间不大，核心功能需要这些行 |
