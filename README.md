# Poly Mech

> **Minecraft 1.21.1 NeoForge 科技模组** — 太空物理 · 天体力学 · 多方块机器 · 电网物流 · 化学流体

Poly Mech 是一个以**真实物理与工程自动化**为核心的 Minecraft NeoForge 模组。它引入了基于 Rapier f64 引擎的 6DOF 原生物理系统、完整的太阳系天体力学模拟、以及一套深度集成的工业生产线。

---

## 核心特性

| 领域 | 内容 |
|------|------|
| 🚀 **太空物理** | 6DOF 自由旋转与推进、地球↔太空无缝过渡、投影世界分块加载 |
| 🌌 **天体力学** | 基于真实轨道力学的 kelvin 系统、行星/恒星/黑洞/流星体种类、引力场 |
| 🏭 **多方块机器** | 蜂巢焦炉、原始高炉、蒸汽锤、辊磨机、矿浆选矿机、灌装机、反射炉、蒸汽/燃气涡轮发电机等 17+ 台生产机器 + 3 种蒸汽锅炉 |
| ⚡ **电网系统** | 全局世界级电力网络、节点/连接器/电线、电压层级、FE 适配 |
| 🔌 **管道物流** | 世界级流体管网（4 种口径）、传送带 3 向运输、路径计算 |
| 🧪 **化学流体** | CDK 驱动元素/分子解析、SMILES 结构式、tooltip 饼图与化学式渲染、聚合物验证 |
| 📐 **蓝图系统** | 客户端蓝图输入、HUD 覆盖层、预览状态 |
| 🔫 **牵引枪** | 物理体抓取/拖拽/旋转，Rapier 实时碰撞交互 |
| 🪨 **世界生成** | 自定义矿脉系统（26 种矿物）、岩层区域生成、FastNoise 驱动地形 |
| 🎨 **材质集系统** | 管道/传送带/锭类按材质复用纹理模板，颜色由 JSON 配置驱动 |
| 🌍 **多语言** | 内置中英文数据生成（`zh_cn` / `en_us`） |

---

## 技术栈

| 组件 | 版本 |
|------|------|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.228 |
| JDK | 21 |
| 映射 | Parchment 2024.11.17 |
| GeckoLib | 4.8.3 |
| LDLib | 2.2.26 |
| CDK (Chemistry) | 2.9 |
| 原生物理引擎 | Rust + Rapier f64（x86_64-pc-windows-gnu） |
| JEI | 19.25.0.322（开发时） |

---

## 开发环境搭建

### 前置要求

- **JDK 21**（Mojang 分发版所需）
- **Git**
- （可选）**Rust 工具链** + **MinGW-w64** 用于编译原生物理库，详见 `native/README.md`

### 快速开始

```bash
# 克隆仓库
git clone <repository-url>
cd polymech-template-1.21.1

# 构建（不含原生库，自动降级）
./gradlew build

# 启动游戏客户端（开发环境）
./gradlew runClient

# 启动专用游戏测试服务器
./gradlew runGameTestServer

# 运行数据生成器（生成配方/模型/语言文件）
./gradlew runData

# 编译并复制原生物理库（需要 Rust 工具链）
./gradlew copyNativePhysics

# 清理构建
./gradlew clean
```

> 注意：`runClient` / `runServer` 使用开发环境运行配置，默认从 `run/` 目录加载存档与配置。

---

## 架构速览

详细的项目结构、包职责、分层架构图见 [`docs/repo-structure.md`](docs/repo-structure.md)。

```
src/main/java/com/mss/polymech/
├── Polymech.java             ← @Mod 主类
├── PolymechClient.java       ← 客户端入口
├── block/                    ← 方块定义
├── machine/                  ← 机器框架与实现（production / boiler / common）
├── pipenet/                  ← 世界级流体管网
├── powergrid/                ← 世界级电网系统
├── physics/                  ← 原生碰撞物理（Rapier f64）
├── mps/                      ← 克隆层物理（space 0.1.3 方法对齐）
├── space/                    ← 太空/维度/6DOF 玩家
├── fluid/                    ← 化学/元素流体系统
├── client/                   ← GUI / 模型 / 渲染 / 蓝图
├── network/                  ← C2S / S2C 数据包
├── datagen/                  ← 数据生成（配方/战利品/标签/语言）
├── worldgen/                 ← 自定义矿脉/岩层生成
└── tooltip/                  ← 化学式 / 结构式 / 饼图渲染
```

---

## 构建产物

```
build/libs/poly_mech-<version>.jar
```

该 JAR 包含全部运行时内容（含 jar-in-jar 打包的 CDK 库），可直接放入 Minecraft 实例的 `mods/` 目录使用。

---

## 许可证

### 代码

本模组**代码**部分 © MSS Team，保留所有权利（All Rights Reserved），除非文件中另有声明。

### 素材

本模组使用了以下开源模组的贴图素材，具体条款见 [`TEXTURE_CREDITS.md`](TEXTURE_CREDITS.md)：

- **TerraFirmaCraft** — 岩石贴图（EUPL-1.2）
- **GregTech CEu Modern** — 矿物加工链/宝石贴图（LGPL-3.0）
- **Ad Astra** — 行星地表贴图（许可详情见素材声明）

第三方素材的许可义务由各原始许可约束，与 Poly Mech 自身代码许可无关。

---

## 团队 & 贡献

- **MSS Team** — 开发与维护
- 贡献指南与任务看板见 `team/`

---

*Built with [NeoForge](https://neoforged.net/) · [ModDevGradle](https://github.com/neoforged/ModDevGradle) · JDK 21*
