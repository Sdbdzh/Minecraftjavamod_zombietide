![Zombie Tide](https://github.com/Sdbdzh/Minecraftjavamod_zombietide/blob/main/%E8%B5%84%E6%BA%90/mod%E5%B0%81%E9%9D%A2/new/new%E5%B0%81%E9%9D%A2-1920x1200-mcmod.png)

# Zombie Tide（尸潮）

[![Build](https://github.com/Sdbdzh/Minecraftjavamod_zombietide/actions/workflows/build.yml/badge.svg)](https://github.com/Sdbdzh/Minecraftjavamod_zombietide/actions/workflows/build.yml)
![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-62b47a)
![NeoForge](https://img.shields.io/badge/NeoForge-21.1.x-ea8b1a)
![License](https://img.shields.io/badge/license-Apache--2.0-blue)

> Minecraft 1.21.1 · NeoForge 21.1.265 · Java 21

一个受《7 Days to Die》启发的周期性尸潮模组：每隔一段时间，成群的僵尸会在黄昏锁定你（或村民）穷追不舍；撑到日出它们会退散，没清掉的会变成下一波额外的压力。

## 特性

- **周期性尸潮**：玩家尸潮与村民尸潮；间隔、数量、半径、分批节奏均可配置
- **血债系统**：上一波幸存的僵尸会累积到下一波（累积制 / 只记上波 / 不结转三种模式）
- **按生物群系生成**：沙漠→尸壳、沼泽→溺尸/僵尸、其他→僵尸，另有 5% 概率僵尸村民
- **完整警告**：清晨提醒、黄昏触发标题与警报、日出四散音效、波次背景音乐（仅触发者能听到）
- **游戏内设置页**：6 个标签页（玩家 / 村民 / 生成性能 / 世界规则 / 警告提示 / HUD），即时生效，带整页壁纸；左下角显示模组封面与当前运行版本号
- **HUD**：左上角 `DAY X`，可显示附近村民的尸潮信息
- **管理命令**：`/zombietide`（OP Level 2），详见《设计文档》

## 环境要求

| 项目 | 版本 |
|------|------|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.265（21.1.x） |
| Java | 21 |

## 安装

从 [Releases](https://github.com/Sdbdzh/Minecraftjavamod_zombietide/releases) 下载最新 jar，或自行构建。

1. 安装 Minecraft 1.21.1 + NeoForge 21.1.x；
2. 把 `enik_zombietide-<版本号>.jar` 放进 `.minecraft/mods/`；
3. 启动游戏。先到 **选项 → 控制 → 按键绑定 → 「尸潮」分类**，给「打开尸潮设置」绑定一个按键（**默认未绑定**）；进入存档后按该键打开设置界面。

## 构建

需要 **JDK 21**。若系统默认 `java` 不是 21，任选一种方式指定：

- **环境变量**（推荐，CI 走这条）：`JAVA_HOME=/path/to/jdk-21`
- **用户级 Gradle 配置**（不改仓库文件）：在 `~/.gradle/gradle.properties` 中写 `org.gradle.java.home=/path/to/jdk-21`

```sh
# Windows
gradlew.bat build

# Linux / macOS
./gradlew build
```

产物：`build/libs/enik_zombietide-<版本号>.jar`

> 版本号只有一个来源：`gradle.properties` 的 `mod_version`。它同时决定 jar 文件名、`neoforge.mods.toml` 的 version 和设置页左下角显示的「版本号」，**不要在别处手写版本号**。

> ⚠️ **不要**把 `org.gradle.java.home` 写进仓库的 `gradle.properties` —— 那是本机绝对路径，会让别人 clone 后构建直接失败。

## 文档

- [玩法说明](玩法说明.md) —— 面向玩家：玩法、设置、常见问题
- [管理员手册](管理员手册.md) —— 面向服主 / OP：命令速查、推荐配置、故障排查
- [设计文档](设计文档.md) —— 面向开发者：代码结构、内部机制、命令与调参
- [版本历史](版本历史.md) —— 每个版本做了什么
- [尸潮生命周期全解析](尸潮生命周期全解析.md) —— 一次完整尸潮的时间线

## 项目结构

```
src/main/java/com/enik/zombietide/
├── EnikZombietide.java      # 主类 / 注册入口
├── attachments/             # 玩家 / 村民尸潮数据附件
├── client/                  # HUD、设置界面、客户端事件
├── config/                  # 配置 record、SavedData、客户端 HUD 配置
├── horde/                   # 计时、刷怪、警告、BGM 调度、僵尸 AI
├── mixin/                   # 白天燃烧开关
├── network/                 # 配置 / 状态同步
├── server/                  # 命令、服务端事件
└── sound/                   # 自定义音效注册
```

## 作者

Sdbdzh（Enik）

## 许可

[Apache License 2.0](LICENSE) —— 代码、美术与音频素材均适用。

你可以自由使用、修改、分发本模组，包括收录进整合包与商业用途，只需满足：

- 保留版权声明与 `LICENSE` 文件
- 修改过的文件注明改动
- 若原作品含 `NOTICE` 文件，需一并保留

> 本模组按「现状」提供，不含任何担保。详见 [LICENSE](LICENSE) 第 7、8 节。
