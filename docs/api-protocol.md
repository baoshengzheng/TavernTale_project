# 通信协议

## WebSocket（客户端 ↔ Java 服务器）

### 端点
```
ws://localhost:8080/ws/tavern
```

连接建立后，客户端**必须**立即发送 `PLAYER_ENTER` 完成身份注册：

```json
{ "type": "PLAYER_ENTER", "payload": { "playerId": "xxx", "playerName": "旅人" } }
```

首条消息必须是 `PLAYER_ENTER`，在此之前所有其他类型消息会被拒绝并关闭连接。

### 消息格式

所有消息都有统一外壳：

```json
{
  "version": "1.0",
  "requestId": "uuid",
  "type": "消息类型",
  "timestamp": 1712345678000,
  "payload": {}
}
```

- `requestId`: 客户端生成 UUID，服务器回复时带回，用于匹配请求和响应
- `version`: 消息版本号，后续协议变更时用于兼容判断

### 客户端 → 服务器

| type | payload | 说明 |
|------|---------|------|
| `PLAYER_ENTER` | `{"playerId": "string", "playerName": "string"}` | 玩家进入酒馆 |
| `PLAYER_MOVE` | `{"x": number, "y": number}` | 玩家移动 |
| `PLAYER_TALK` | `{"npcId": "string", "message": "string"}` | 玩家对 NPC 说话 |
| `PLAYER_TALK_END` | `{"npcId": "string"}` | 玩家主动结束对话 |
| `PLAYER_GIFT` | `{"npcId": "string", "itemId": "string"}` | 赠送物品给 NPC（Iteration 2） |
| `PLAYER_LEAVE` | `{}` | 玩家离开酒馆 |

### 服务器 → 客户端

| type | payload | 说明 |
|------|---------|------|
| `WORLD_STATE` | `{"players": [...], "npcs": [...], "rooms": [...]}` | 全量世界状态（进入时推送） |
| `WORLD_DIFF` | `{"changedPlayers": [...], "changedNpcs": [...], "removedIds": [...]}` | 增量世界变化（Iteration 2 引入 tick 后启用） |
| `DIALOGUE` | `{"npcId": "string", "message": "string", "emotion": "string", "relationshipDelta": number}` | NPC 对话回复 |
| `DIALOGUE_PENDING` | `{"npcId": "string"}` | AI 正在生成回复，请等待（1-3 秒内） |
| `DIALOGUE_BUSY` | `{"npcId": "string", "npcName": "string"}` | NPC 正和其他玩家交谈，暂时无法对话 |
| `NPC_IN_RANGE` | `{"npcId": "string", "npcName": "string", "x": number, "y": number}` | 玩家进入某 NPC 触发范围，可发起对话 |
| `NPC_OUT_OF_RANGE` | `{"npcId": "string"}` | 玩家离开 NPC 触发范围（若正在对话则自动结束） |
| `PLAYER_MOVED` | `{"playerId": "string", "x": number, "y": number}` | 其他玩家位置更新 |
| `PONG` | `{}` | 心跳回复 |
| `SYSTEM` | `{"level": "info"|"warn"|"error", "message": "string"}` | 系统消息（通知/警告/错误） |
| `PLAYER_LEFT` | `{"playerId": "string"}` | 其他玩家离开酒馆 |

### 对话流程（含感知 + 锁定）

```
玩家                    Java 服务器                     Python AI
 │                         │                              │
 │── PLAYER_MOVE ─────────→│                              │
 │                         │ 距离校验：玩家进入 NPC 范围     │
 │←── NPC_IN_RANGE ───────│                              │
 │   (高亮 NPC，显示"对话")  │                              │
 │                         │                              │
 │── PLAYER_TALK ─────────→│                              │
 │                         │ 检查 NPC 是否 FREE            │
 │                         │                              │
 │   ┌─ NPC 正在和他人对话：  │                              │
 │   │                      │                              │
 │   │← DIALOGUE_BUSY ─────│                              │
 │   │  ("正和别人交谈")     │                              │
 │   └─ NPC 空闲：           │                              │
 │                         │ NPC → TALKING                │
 │                         │ current_talk_target = A      │
 │                         │                              │
 │←── DIALOGUE_PENDING ───│                              │
 │   (显示"对方正在思考")   │── HTTP POST ────────────────→│
 │                         │   /api/dialogue              │── 调 DeepSeek API
 │                         │                              │
 │                         │←──── HTTP 200 ──────────────│
 │←── DIALOGUE ───────────│                              │
 │   (显示 NPC 回复)        │                              │
 │                         │                              │
 │          超时 3 秒无回复 → 走兜底：NPC 沉默               │
 │                         │                              │
 │  玩家走远 / PLAYER_TALK_END / 30s 超时 / 断连           │
 │                         │                              │
 │                         │ NPC → FREE                   │
 │                         │ current_talk_target = null   │
 │←── NPC_OUT_OF_RANGE ───│                              │
```

## HTTP（Java ↔ Python AI 服务）

Iteration 1 使用 HTTP，后续评估是否升级到 gRPC。

```
POST http://localhost:8000/api/dialogue
Content-Type: application/json

{
  "npc_id": "edith",
  "player_id": "player-001",
  "player_message": "今天有什么好吃的？",
  "personality": {"openness": 70, "conscientiousness": 80, "extraversion": 60, "agreeableness": 40, "neuroticism": 30},
  "relationship_score": 50,
  "current_emotion": "neutral",
  "recent_memories": "玩家第一次来酒馆",
  "world_context": "傍晚，酒馆里有几个客人"
}

Response 200:
{
  "npc_response": "今天有刚炖好的野猪肉，配黑麦面包——如果你能付得起的话。",
  "emotion_change": "joy: +5",
  "new_memories": "玩家询问了食物，似乎是个注重享受的人",
  "actions": ["npc_wipes_glass"]
}
```

### 兜底（AI 不可用时）

```
Response 503:
{
  "fallback": true,
  "npc_response": "…（她沉默地擦着杯子，没有回答）"
}
```

Java 侧检测到 HTTP 超时（3 秒）或 503，走本地模板回复。
