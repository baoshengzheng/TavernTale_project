# 通信协议

## WebSocket（客户端 ↔ Java 服务器）

### 端点
```
ws://localhost:8080/ws/tavern?playerId={playerId}
```

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
| `PLAYER_GIFT` | `{"npcId": "string", "itemId": "string"}` | 赠送物品给 NPC |
| `PLAYER_LEAVE` | `{}` | 玩家离开酒馆 |

### 服务器 → 客户端

| type | payload | 说明 |
|------|---------|------|
| `WORLD_STATE` | `{"players": [...], "npcs": [...], "rooms": [...]}` | 全量世界状态（进入时推送） |
| `WORLD_DIFF` | `{"changedPlayers": [...], "changedNpcs": [...], "removedIds": [...]}` | 增量世界变化（定时广播） |
| `DIALOGUE` | `{"npcId": "string", "message": "string", "emotion": "string", "relationshipDelta": number}` | NPC 对话回复 |
| `DIALOGUE_PENDING` | `{"npcId": "string"}` | AI 正在生成回复，请等待（1-3 秒内） |
| `SYSTEM` | `{"level": "info"|"warn"|"error", "message": "string"}` | 系统消息（通知/警告/错误） |
| `PLAYER_LEFT` | `{"playerId": "string"}` | 其他玩家离开酒馆 |

### 对话流程

```
玩家                   Java 服务器              Python AI
  │                      │                       │
  │── PLAYER_TALK ──────→│                       │
  │                      │── HTTP POST ────────→│
  │                      │   /api/dialogue       │
  │── DIALOGUE_PENDING ─→│                       │
  │   (显示"对方正在思考") │                       │── 调 DeepSeek API
  │                      │                       │
  │                      │←──── HTTP 200 ───────│
  │── DIALOGUE ─────────→│                       │
  │   (显示 NPC 回复)     │                       │
  │                      │                       │
  │          超时 3 秒无回复 → 走兜底模板回复        │
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
