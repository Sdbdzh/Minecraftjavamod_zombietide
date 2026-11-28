# DAY X HUD 优化方案

> 状态：**已实现（v0.2.138）** ✅
> 起草日期：2026-11-29
> 方向：用户已确认「A 信息分层 + B 状态语义」，**明确不要进度条**

---

## 一、现状与问题

### 1.1 当前显示

左上角单行 `DAY X`：

| 状态 | 表现 |
|---|---|
| 平静 | 白色 |
| 预兆期 | 白 → 深红 `0xFF3333` 线性渐变（由 `hordeProgress` 驱动） |
| 尸潮日 / 波次中 | 强制深红 |
| 关闭渐进红时 | 二态：尸潮日亮红 / 其余白 |

村民列表里的 DAY 段用同样逻辑（每个村民按各自的 `hordeProgress` 渐变）。

### 1.2 问题

1. **信息量低**：只能从颜色深浅猜"快来了"，不知道具体血债、僵尸数、波次状态
2. **数据浪费**：客户端其实已同步 8 类数据，DAY X 只用了 3 类
3. **视觉单薄**：和重构后现代化的设置页相比，HUD 显得朴素

### 1.3 已同步但没用起来的数据

`PlayerModDayPayload` 已下发（`ModMessages.java` L102）：

| 字段 | 当前用途 | 本次是否使用 |
|---|---|---|
| `modDay` | DAY X 数字 | 沿用 |
| `hordeProgress` | 渐进红 | 沿用（不再画进度条） |
| `waveActive` | 强制全红 | 沿用 |
| `hordeDayToday` | 二态红 | 沿用 |
| **`bloodDebt`** | 只在 debug 面板显示 | ✅ 新增到 DAY X |
| **`aliveHordeZombies`** | 只在 debug 面板显示 | ✅ 新增到 DAY X |
| `activePlayerHordes` | 只在 debug 面板 | ⚠️ 可选 |
| `activeVillagerHordes` | 只在 debug 面板 | ⚠️ 可选 |
| `hordeDay` | 雾色判定 | 不用 |

**结论：零协议改动**——所有数据现成，只改渲染。

---

## 二、目标设计

### 2.1 主行（沿用，不动）

```
DAY 12
```

- 位置 / 缩放 / 粗体继续由 `hudX` / `hudY` / `hudScale` / `hudBold` 控制
- **渐进红逻辑完全保留**（白 → 深红，波次中强制全红）
- ❌ **不画进度条**（用户明确不要）

### 2.2 次行（新增，状态 + 关键数值）

```
血债 8 · 僵尸 15
```

**只在有意义时显示**，避免平时刷屏：

| 条件 | 次行内容 | 说明 |
|---|---|---|
| 血债 > 0 且 波次中 | `血债 N · 僵尸 M` | 最有信息量的时刻 |
| 血债 > 0 且 非波次 | `血债 N` | 提醒还有债没清 |
| 血债 = 0 且 波次中 | `僵尸 M` | 正在被围攻 |
| 血债 = 0 且 非波次 | **不显示** | 平时保持极简，只有一行 DAY X |

### 2.3 状态语义强化（B 方案，不加图标）

| 状态 | 主行颜色 | 次行 |
|---|---|---|
| 平静 | 白 | 无 |
| 预兆 | 白→深红渐变（沿用） | 有债时显示债 |
| 尸潮中 | 深红（沿用） | 债 + 僵尸数 |
| 清算后 | 深红→白过渡 | 债（如果有） |

**不加状态图标**（`☀/⚔/☠`）——MC 默认字体对这些符号支持不稳，容易显示成方框。状态靠"次行是否出现 + 颜色"表达，已经够。

---

## 三、配置（纯本机，不进协议）

沿用 `ClientHudConfig.dayGradientRed()` 的模式（`config/enik_zombietide-hud.properties`，不进存档、不进网络 payload）：

| 新增开关 | 默认 | 作用 |
|---|---|---|
| `hudShowDebt` | `true` | 是否显示血债 |
| `hudShowAliveCount` | `true` | 是否显示存活尸潮僵尸数 |

- 两个都关 = 回到现在的纯 `DAY X`，老玩家无感
- 设置页 HUD 分类加两个 checkbox（`item15` / `item16`）

---

## 四、村民列表的处理

**本次不改村民列表的 DAY 段**。

理由：
- 村民的 payload 里**没有血债 / 存活数**（`VillagerModDayPayload` 只有天数/坐标/波次/进度），要改就得动协议
- 用户要求零协议改动 + 极简
- 历史教训（v0.2.96）：玩家侧和村民侧逻辑一旦不同步，会出现"同一面板两种表现"

如果后续想统一，那属于独立的第二阶段（需要扩 `VillagerModDayPayload`），本次不做。

---

## 五、实现范围（预计改动）

| 文件 | 改动 |
|---|---|
| `client/ZombietideHud.java` | 次行渲染：读 `bloodDebtFor` / `aliveHordeZombiesFor`，新增一个 `drawSubLine` 方法 |
| `config/ClientHudConfig.java` | 加两个 boolean 开关（load / save / getter） |
| `client/ZombietideSettingsScreen.java` | HUD 分类加两个 checkbox（`SettingsRegistry` 加两条声明） |
| `lang/zh_cn.json` / `en_us.json` | 4 个新键（2 个设置项 × name/desc/tip） |

**不动**：`ModMessages`、`ZombietideConfig`（HudConfig record）、`VillagerModDayPayload`、服务端任何代码。

---

## 六、验收标准

1. 平时（血债 0、无波次）：左上角只有 `DAY X`，和现在完全一样
2. 欠血债时：多出一行 `血债 N`；关掉开关后消失
3. 波次中：多出一行 `血债 N · 僵尸 M`（或只有 `僵尸 M`）
4. 尸潮当天仍强制深红，渐进红逻辑未变
5. 设置页 HUD 分类能找到两个新开关，改了立即生效
6. 村民列表表现**完全不变**
7. `gradlew build` 通过；无协议变更

---

## 七、明确不做

- ❌ 进度条
- ❌ 状态图标（☀/⚔/☠，字体兼容风险）
- ❌ 卡片背景 / 半透明底板
- ❌ 呼吸 / 闪烁 / 淡出等动效
- ❌ 改村民列表
- ❌ 改网络协议
- ❌ 动服务端
