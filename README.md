# enik-zombietide

enik-zombietide — a NeoForge mod for Minecraft 1.21.1 (NeoForge 21.1.249), scaffolded by minecraft-dev.

## Build

```sh
# Windows
gradlew.bat build

# Linux / macOS
./gradlew build
```

产物 `build/libs/enik-zombietide-0.1.0.jar` 拖入客户端 `mods/` 启动测试；`gradlew runClient`/`runServer`/`runData` 可用（runServer 需改 eula.txt 与 online-mode=false）。

## Notes

- 首次构建 NeoForm 反编译可达一小时（R9），请设足够超时。
- 1.21.1 走 NeoForge 21.1.x：`neoforge.mods.toml` **必须**声明 `modLoader="javafml"` 与 `loaderVersion`，
  否则客户端加载即报 `Missing ModLoader`（javafml 成为默认值是更晚的 21.x 才有的）。
- 本线不启用 Parchment（少一个版本猜测点；需要参数名可自行加回）。
- 无 pack.mcmeta（官方 MDK 现状）。

## Layout

- `src/main/java/com/enik/zombietide/EnikZombietide.java` — `@Mod` 主类 + mod bus / game bus 注册点
- `src/main/resources/META-INF/neoforge.mods.toml` — 1.21.1 形态（含 modLoader / loaderVersion，license 必填）
- `build.gradle` — moddev 2.0.144 + Java 21 toolchain
