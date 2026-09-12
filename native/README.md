# Poly Mech 物理原生层（Rust + Rapier）

Java 侧 `com.mss.polymech.physics` 通过 JNI 调用本目录的 Rust crate 完成刚体仿真。

## 为什么用 Rust + Rapier

- **旋转刚体**：原版 Minecraft 只有网格对齐 AABB，无法表达飞船的任意旋转/翻滚/接舷；
- **f64 精度**：太空维度坐标可达 ±20 亿格，f32 在 1e11 处的分辨率约 1 万米，完全不可用；
- **零 GC 抖动**：物理跑独立 100Hz 定步长，且体素/建筑快照体量巨大，不适合进 Java 堆；
- **成熟引擎**：Java 生态没有等价的现代 3D 求解器（JBullet 停更且仅 f32、dyn4j 仅 2D）。

## 许可证（重要）

| 组件 | 协议 | 说明 |
|---|---|---|
| `rapier3d-f64` / `parry3d-f64` / `nalgebra` / `glam` / `jni` | Apache-2.0 / MIT | 开源依赖，可商用；分发时需保留版权与许可声明 |
| 本 crate（`polymech_physics`） | 本项目所有 | 由 Poly Mech 自行编写 |

> 本项目**不包含**任何第三方闭源产物。特别地，未使用、未反编译、未再分发 space 模组的 `mps_rigid_body.dll`
> 或 `org.polymech2023.mps` 相关代码（该模组为 All Rights Reserved）。

## 构建

### Windows（MinGW-w64，免管理员）

```bash
# 1) 安装 Rust（若未安装）
curl -LO https://static.rust-lang.org/rustup/dist/x86_64-pc-windows-msvc/rustup-init.exe
./rustup-init.exe -y --profile minimal --no-modify-path
rustup toolchain install stable-x86_64-pc-windows-gnu --profile minimal

# 2) 准备 MinGW-w64（示例：从 MSYS2 镜像解包到 ~/.polymech-toolchain/mingw64）
#    需要包：gcc, gcc-libs, binutils, crt, headers, libwinpthread,
#            windows-default-manifest, gmp, mpfr, mpc, isl, zlib, zstd, libiconv, gettext-runtime

# 3) 编译并复制到资源目录
./gradlew copyNativePhysics
```

可用环境变量覆盖工具链位置：

- `POLYMECH_MINGW`：MinGW 前缀目录（含 `bin/x86_64-w64-mingw32-gcc.exe`）
- `JAVA_HOME`：用于向链接器提供 `jvm.lib`（JNI 需要）

### Linux / macOS

```bash
rustup target add x86_64-unknown-linux-gnu      # 或 aarch64-apple-darwin
cd native/polymech-physics && cargo build --release
# 产物复制到 src/main/resources/natives/<linux_amd64|macos_amd64>/libpolymech_physics.so|dylib
```

## ABI 约定

Rust 侧 `ABI_VERSION` 与 Java 侧 `PhysicsNatives.EXPECTED_ABI` 必须一致，否则加载器拒绝启用物理层。

导出符号命名规则：`Java_com_mss_polymech_physics_NativePhysics_<方法名>`。

## 加载与降级

`PhysicsNatives` 会依次尝试：

1. `System.loadLibrary("polymech_physics")`（开发环境 / `-Djava.library.path`）；
2. 从 mod 资源 `/natives/<os>_<arch>/` 解压到临时目录并加载。

任何一步失败都只会让物理功能不可用（日志警告），**不会导致游戏崩溃**。

自检命令：`/polymech physics status`、`/polymech physics selftest`
