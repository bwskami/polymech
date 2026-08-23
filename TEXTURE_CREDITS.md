# 素材来源与许可署名

> ## ⚠️ 临时素材声明（发布前必须替换）
>
> 本文件第 1~3 节列出的所有素材都是**开发期临时占位素材**，直接从开源模组
> 仓库复制而来，**不保证会被保留在最终发布版中**。后续会逐步替换为自绘素材。
>
> 发布前需要替换/清理的临时目录：
> - `assets/poly_mech/textures/block/rock/raw/`（TFC 岩石贴图）
> - `assets/poly_mech/textures/block/ore/ore1~ore4*.png`（**自绘/用户替换的矿石形态贴图**，非外部素材）
> - `assets/poly_mech/textures/item/material_sets/raw_ore/raw1~raw4*.png`（**自绘/用户替换的粗矿形态贴图**）
> - `assets/poly_mech/textures/item/material_sets/crushed|purified|dust|gem/`
>   （GT 矿物加工链/宝石贴图，仍待替换）
>
> 替换后请同步删除本文件中对应的小节，或把剩余引用改为自绘素材的许可说明。

---

本模组 `poly_mech` 的部分贴图素材直接取自以下开源模组的资源文件。
在此致谢并保留许可信息，以符合各开源许可的署名要求。

## 1. TerraFirmaCraft (1.21.x) — 岩石贴图

- **来源仓库**: `TerraFirmaCraft-1.21.x`
- **许可**: [European Union Public Licence v1.2 (EUPL-1.2)](https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12)
- **使用的素材**: 21 种岩石的 `textures/block/rock/raw/*.png`
  （andesite / basalt / chalk / chert / claystone / conglomerate / dacite /
  diorite / dolomite / gabbro / gneiss / granite / limestone / marble /
  phyllite / quartzite / rhyolite / schist / shale / slate / tuff）
- **本模组路径**: `assets/poly_mech/textures/block/rock/raw/*.png`

> 依 EUPL-1.2，若将上述素材视作演绎作品（Derivative Work），
> 本模组需以 EUPL-1.2 或其兼容许可分发，并保留原始许可声明与版权信息。
> 完整许可文本见 TerraFirmaCraft 仓库的 `LICENSE.txt`。

## 2. GregTech CEu Modern (1.21) — 矿石/矿物/宝石类素材

- **来源仓库**: `GregTech-Modern-1.21`
- **许可**: [GNU Lesser General Public License v3 (LGPL-3.0)](https://www.gnu.org/licenses/lgpl-3.0.html)
- **使用的素材**: ~~8 组矿石图标集贴图~~ 已由用户替换为自绘的 4 种矿石形态贴图
  `block/ore/ore{1~4}.png|_secondary|_overlay`，不再来自 GT。
- **本模组路径**: `assets/poly_mech/textures/block/ore/ore{1~4}*.png`（自绘）

## 3. GregTech 物品素材（矿物加工链/宝石）

- **来源仓库**: 同上 `GregTech-Modern-1.21`
- **许可**: LGPL-3.0
- **使用的素材**（`textures/item/material_sets/`，均为原样复制）：
  - `dull/crushed.png`、`dull/crushed_secondary.png`、`dull/crushed_overlay.png` → 粉碎矿
  - `dull/crushed_purified.png`、`dull/crushed_purified_secondary.png` → 洗净矿
  - `dull/dust.png`、`dull/dust_secondary.png`、`sand/dust_overlay.png` → 粉
  - `dull/gem.png`、`dull/gem_secondary.png`、`dull/gem_overlay.png` → 宝石/水晶
- **本模组路径**: `assets/poly_mech/textures/item/material_sets/raw_ore|crushed|purified|dust|gem/...`

> 依 LGPL-3.0，重新分发时需保留原始许可声明与版权信息，
> 且允许用户以相同许可重新分发本模组中源自 GT 的部分。
> 完整许可文本见 GregTech-Modern 仓库的 `LICENSE` 文件。

---

**说明**: 以上素材仅作个人学习与模组开发用途，不构成对原始作品的任何权利主张。
若计划公开发布本模组，请再次核对上述许可的具体条款（特别是传播义务与源码公开义务）。
