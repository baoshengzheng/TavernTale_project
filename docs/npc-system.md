# NPC 系统设计

## 核心概念：定义 vs 状态

NPC 有两层数据，**永远分离**：

```
NPC 定义（JSON，不可变）              NPC 运行时状态（H2，可变）
─────────────────────────            ─────────────────────────
· id, name, title, race              · current_x, current_y
· OCEAN 性格参数                     · current_emotion (JSON)
· background（背景故事）              · current_state (FREE / TALKING)
· speakingStyle（说话风格）           · current_talk_target (playerId)
· initialRelationship（初始好感度）   · player_relationships (JSON)
· position（初始位置）                · player_memories (JSON)
· triggerRadius（触发半径）           · last_active_at
```

- **定义**：从 `server/src/main/resources/npcs/{npcId}.json` 加载，NpcFactory 在启动时读取。增加 NPC = 新增一个 JSON 文件
- **状态**：存在 H2 `npc_states` 表。启动时 NpcFactory 合并定义和状态，生成内存中的 `NpcInstance`
- **为什么分离**：定义是静态的"NPC 是什么人"，状态是动态的"NPC 现在怎么样"。修改 NPC 性格不会丢失他在游戏中的记忆和关系

## 对话锁定：一个 NPC 同时只和一个人说话

模拟真实世界社交规则。NPC 状态机只有两个状态：

```
FREE ──玩家点击对话──→ TALKING
  ↑                      │
  └── 玩家离开/超时/结束 ──┘
```

| 场景 | 行为 |
|------|------|
| 玩家 A 点击空闲 NPC | NPC → TALKING，`current_talk_target = A` |
| 玩家 B 点击正在对话的 NPC | 后端返回 `DIALOGUE_BUSY`，前端提示"正在和别人交谈" |
| 对话中玩家断连 | WebSocket `onClose` → 释放该玩家所有 NPC 锁定 |
| 对话中玩家走远（> triggerRadius） | `handlePlayerMove` 检测距离 → 自动结束对话 |
| 对话中玩家 30 秒不发言 | 超时自动结束对话 |

**并发收益**：同一时刻只有一个玩家能写 NPC 状态，不存在竞争写入，不需要锁。

## 感知机制：前端视觉 + 后端权威

| 职责 | 谁做 | 用途 |
|------|------|------|
| 距离计算 + NPC 高亮 | 前端 | 纯视觉反馈，玩家改 JS 也只会影响高亮 |
| 距离校验 + 对话权限 | 后端 | `handlePlayerMove` 中做权威校验，前端绕不过去 |

流程：
```
玩家按 W → 前端立即更新位置 + 高亮附近 NPC
       → 发送 PLAYER_MOVE
              → 后端校验位置 + 算距离
              → 在范围内 → 推 NPC_IN_RANGE → 前端可展示对话按钮
              → 超出范围 → 推 NPC_OUT_OF_RANGE（如果之前有在对话则自动结束）
```

## 性格模型：OCEAN 五因素

每个 NPC 由 5 个维度定义，范围 0-100。这 5 个值影响 Prompt 生成、情感变化方向和对话风格。

| 维度 | 含义 | 高分倾向 | 低分倾向 |
|------|------|---------|---------|
| O — Openness | 开放性 | 好奇、创新、接受新事物 | 保守、传统、偏好熟悉 |
| C — Conscientiousness | 尽责性 | 有条理、可靠、自律 | 随性、散漫、灵活 |
| E — Extraversion | 外向性 | 热情、健谈、社交导向 | 安静、独处、内省 |
| A — Agreeableness | 宜人性 | 友善、合作、信任他人 | 挑剔、竞争、怀疑 |
| N — Neuroticism | 神经质 | 敏感、情绪化、易焦虑 | 情绪稳定、冷静、抗压 |

### MVP 3 个 NPC

```json
{
  "id": "edith",
  "name": "Edith",
  "title": "老板娘",
  "race": "human",
  "personality": { "openness": 70, "conscientiousness": 80, "extraversion": 60, "agreeableness": 40, "neuroticism": 30 },
  "initialRelationship": 50,
  "description": "眼神锐利的中年女性，擦杯子的动作很利落",
  "background": "曾是冒险者，因某次事故隐退开酒馆。知道很多秘密但从不主动说。",
  "speakingStyle": "简洁、有力、偶尔带刺。不会说废话。",
  "position": { "room": "tavern", "x": 200, "y": 300 },
  "triggerRadius": 80
}
```

```json
{
  "id": "roderick",
  "name": "Roderick",
  "title": "老醉汉",
  "race": "human",
  "personality": { "openness": 40, "conscientiousness": 20, "extraversion": 90, "agreeableness": 70, "neuroticism": 60 },
  "initialRelationship": 60,
  "description": "脸红红的，说话带着酒气，但眼神偶尔闪过一丝精明",
  "background": "曾是一名情报官员，因知道太多被"退休"。用醉汉身份做掩护。",
  "speakingStyle": "话多、爱吹牛、看似醉话实则话里有话。喜欢让人请喝酒。",
  "position": { "room": "tavern", "x": 400, "y": 450 },
  "triggerRadius": 60
}
```

```json
{
  "id": "luna",
  "name": "Luna",
  "title": "神秘旅人",
  "race": "elven",
  "personality": { "openness": 50, "conscientiousness": 70, "extraversion": 10, "agreeableness": 30, "neuroticism": 40 },
  "initialRelationship": 30,
  "description": "披着深色斗篷的精灵，坐在角落阴影里，杯子几乎没动过",
  "background": "正在追踪某种危险的存在。不信任陌生人，但消息极为灵通。",
  "speakingStyle": "沉默寡言、简短。每句话都有分量。会用问题回答问题。",
  "position": { "room": "tavern", "x": 600, "y": 200 },
  "triggerRadius": 50
}
```

## 情感模型

6 种基本情绪，强度 0-100：

joy（快乐）, sadness（悲伤）, anger（愤怒）, fear（恐惧）, surprise（惊讶）, disgust（厌恶）

### 影响因素
1. **性格**（OCEAN 决定情绪变化倾向和幅度）
2. **事件**（玩家行为、环境变化）
3. **好感度变化**
4. **记忆唤醒**

### 性格对情绪的影响

```
示例：玩家送礼物
Edith (N:30 → 稳定):
  joy +15, 好感度 +5
  情绪变化平缓

Roderick (N:60 → 敏感):
  joy +25, surprise +20
  情绪波动大，容易大喜大悲

Luna (A:30 → 不信任):
  joy +5, 好感度 +2
  同时触发 suspicion -10
```

## 记忆系统（设计目标，Iteration 2 实现）

### 三层架构

```
短期记忆 (Session内) → 长期记忆 (H2) → 向量记忆 (后续迭代)
    10条最近交互         重要事件持久化     语义检索用
```

### Iteration 1 的简化实现

所有 NPC 运行时数据存在一张表 `npc_states`，后续字段多了再拆：

```
H2 表 npc_states:
  npc_id (PK) | current_x | current_y | current_emotion (JSON) |
  current_state (FREE/TALKING) | current_talk_target |
  player_relationships (JSON) | player_memories (JSON) | last_active_at
```

- `player_relationships`：`{ "player_001": { "score": 65, "last_updated": "..." }, ... }`
- `player_memories`：`{ "player_001": [{"event": "...", "importance": 5, "time": "..."}, ...], ... }`

对话时按 `npc_id + player_id` 取出对应的记忆和好感度，拼接进对话 Prompt。

## Prompt 模板结构（Python 侧，Iteration 1 实现）

```
[SYSTEM]
你是 {npc_name}，{npc_title}。
性格：{personality_description}
背景：{background}
说话风格：{speaking_style}
当前情绪：{current_emotion}
玩家 {player_name} 对你的好感度：{relationship_score}

你对 {player_name} 的印象：{recent_memories}

[对话规则]
1. 回复不超过 60 字
2. 符合你的性格和情绪
3. 如果玩家提到你记得的事，反映在回复中
4. 不要主动提及你不知道的信息

[玩家]
{player_message}

[你]
```
