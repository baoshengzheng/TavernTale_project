# 奇幻酒馆 — 项目总结

## 项目定位

> AI NPC 游戏引擎（Iteration 0）。玩家走进奇幻酒馆，在 2D 俯视图场景中自由移动，后续将与拥有记忆和性格的 AI NPC 自由对话。

当前阶段仅为**多人实时同步 + 场景展示**，AI 对话能力在 Iteration 1 接入。

---

## 技术栈

| 层 | 技术 | 版本 | 作用 |
|---|------|------|------|
| **后端** | Spring Boot (WebFlux) | 3.4.1 | Netty 非阻塞 HTTP/WebSocket 服务 |
| **数据库** | H2 | 2.3.232 | 内存模式，JPA 自动建表 |
| **ORM** | Spring Data JPA + Hibernate | — | 实体持久化 |
| **前端** | React + TypeScript | 19 / 5.6 | 函数组件 + 严格类型 |
| **构建** | Vite | 6.4.3 | 开发服务器 + 生产打包 |
| **通信** | WebSocket (原生 API) | — | 实时双向消息 |
| **Java** | Corretto JDK | 21.0.4 | LTS 版本 |
| **Maven** | Apache Maven | 3.9.9 | 依赖管理 |

---

## 前后端关系

```
┌──────────────────────────────────────────────────────────────┐
│                        浏览器 (前端)                           │
│                                                              │
│   ┌──────────────────────────────────────────────────────┐   │
│   │                   App.tsx (主组件)                     │   │
│   │                                                      │   │
│   │  ┌──────────────┐    ┌──────────────┐               │   │
│   │  │ WorldState    │    │ TavernMap    │               │   │
│   │  │ (状态管理)     │◄──→│ (地图渲染)    │               │   │
│   │  │              │    │              │               │   │
│   │  │ localPosition│    │ · WASD 移动   │               │   │
│   │  │ otherPlayers │    │ · 点击移动    │               │   │
│   │  │ roomObjects   │    │ · 家具/玩家   │               │   │
│   │  └──────┬───────┘    └──────────────┘               │   │
│   │         │                                                │
│   │         │ send({type, payload})                         │
│   │         ▼                                                │
│   │  ┌──────────────────────────────────────────────────┐    │
│   │  │           useWebSocket.ts (连接管理)               │    │
│   │  │                                                   │    │
│   │  │ · 自动连接/重连（3s 间隔，最多 5 次）               │    │
│   │  │ · 心跳保活（每 30s PING）                          │    │
│   │  │ · 消息分发（addListener 模式）                     │    │
│   │  │ · 连接状态指示（connecting/connected/...）          │    │
│   │  └──────────────────┬───────────────────────────────┘    │
│   │                     │                                     │
│   └─────────────────────┼─────────────────────────────────────┘
│                         │ WebSocket (ws://localhost:8080/ws/tavern)
│                         │ 消息格式：{version, requestId, type, timestamp, payload}
└─────────────────────────┼───────────────────────────────────────┘
                          │
┌─────────────────────────┼───────────────────────────────────────┐
│   Java 后端 (Spring Boot WebFlux + Netty)                       │
│                         │                                       │
│  ┌──────────────────────▼────────────────────────────────────┐  │
│  │              TavernWebSocketHandler.java                    │  │
│  │              WebSocket 消息入口 + 路由                       │  │
│  │                                                              │  │
│  │  接收: PLAYER_ENTER → 初始化世界                              │  │
│  │        PLAYER_MOVE  → 更新位置 + 广播                        │  │
│  │        PING         → 回复 PONG                              │  │
│  │                                                              │  │
│  │  发送: WORLD_STATE   → 全量世界（进入时）                      │  │
│  │        PLAYER_MOVED  → 广播位置变化                           │  │
│  │        PLAYER_LEFT   → 广播玩家离开                           │  │
│  │        SYSTEM        → 系统消息                               │  │
│  └──────────┬──────────────────────────────┬───────────────────┘  │
│             │                              │                      │
│      ┌──────▼──────┐              ┌───────▼───────┐              │
│      │ PlayerService│              │ WorldService   │              │
│      │ · 进入/离开   │              │ · 加载房间 JSON  │              │
│      │ · 位置更新    │              │ · 提供场景查询   │              │
│      │ · 在线状态    │              │ · 启动时初始化   │              │
│      └──────┬──────┘              └───────┬───────┘              │
│             │                             │                       │
│      ┌──────▼──────┐              ┌───────▼───────┐              │
│      │PlayerRepository│            │RoomRepository  │              │
│      │ (JPA)         │            │ (JPA)          │              │
│      └──────┬──────┘              └───────┬───────┘              │
│             │                             │                       │
│      ┌──────▼─────────────────────────────▼───────┐              │
│      │              H2 (内存数据库)                  │              │
│      │  表: players(id, name, x, y, online, ...)   │              │
│      │      rooms(id, name, width, height, ...)     │              │
│      └────────────────────────────────────────────┘              │
│                                                                  │
│  ┌──────────────────────────────┐                               │
│  │  WebSocketSessionManager     │  ← 会话管理（ConcurrentHashMap）│
│  │  · register / unregister     │    playerId → WebSocketSession │
│  │  · 广播 / 单播               │                               │
│  └──────────────────────────────┘                               │
└──────────────────────────────────────────────────────────────────┘
```

### 通信方式

**WebSocket**（非 HTTP 轮询），端点 `ws://localhost:8080/ws/tavern`，查询参数携带 `playerId` 和 `playerName`。

### 消息协议

所有消息共用统一外壳：

```typescript
{
  version: "1.0",       // 协议版本，用于向后兼容
  requestId: "uuid",    // 请求唯一 ID，回复时带回，用于追踪
  type: "消息类型",      // 见下方表格
  timestamp: 1712345678000,
  payload: {}            // 消息体，按 type 不同结构
}
```

### 消息类型清单

| 方向 | type | 触发时机 | 效果 |
|------|------|---------|------|
| 客户端→服务器 | `PLAYER_ENTER` | 连接后首次 | 请求全量世界状态 |
| | `PLAYER_MOVE` | WASD/点击 | 更新位置，触发广播 |
| | `PING` | 每 30s | 心跳保活 |
| 服务器→客户端 | `WORLD_STATE` | 连接成功 | 全量数据：房间+玩家+自己位置 |
| | `PLAYER_MOVED` | 任意玩家移动 | 增量更新其他玩家位置 |
| | `PLAYER_LEFT` | 玩家断连 | 从地图移除玩家 |
| | `PONG` | 收到 PING | 心跳回复 |
| | `SYSTEM` | 异常 | 系统级通知（错误/警告） |

### 位置同步策略

```
玩家按 W → 前端立即更新 localPosition（乐观） → 发送 PLAYER_MOVE → 服务器校验边界 → 
写入数据库 → 广播 PLAYER_MOVED 给其他玩家 → 其他玩家更新位置
                         ↑
          自己忽略这条广播（因为已乐观更新）
```

**为什么乐观更新？** 避免等待网络往返（RTT），WASD 手感流畅。服务器仍然执行边界校验确保安全。

---

## 后端内部模块关系

```
┌──────────────────────────────────────────────────────────────────┐
│                       config/                                    │
│              WebSocketConfig.java                                │
│  注册 /ws/tavern → TavernWebSocketHandler                        │
│  声明 WebSocketHandlerAdapter（WebFlux 必需）                     │
└────────────────────────┬─────────────────────────────────────────┘
                         │ 依赖注入
┌────────────────────────▼─────────────────────────────────────────┐
│                     controller/ws/                                │
│                                                                  │
│  ┌───────────────────────────────────────────────────────────┐   │
│  │         TavernWebSocketHandler.java                        │   │
│  │                                                           │   │
│  │  职责：WebSocket 生命周期管理 + 消息路由                    │   │
│  │                                                           │   │
│  │  handle(session) {                                        │   │
│  │    1. 解析 playerId                                       │   │
│  │    2. sessionManager.register()                           │   │
│  │    3. playerService.enterTavern()                         │   │
│  │    4. sendWorldState()   ← 进入时推送全量状态               │   │
│  │    5. session.receive()  ← 监听消息流                     │   │
│  │       → handleMessage() → switch(type)                    │   │
│  │          ├─ PLAYER_MOVE  → playerService.updatePosition() │   │
│  │          │                + broadcast(PLAYER_MOVED)        │   │
│  │          ├─ PLAYER_ENTER → sendWorldState()               │   │
│  │          └─ PING         → send(PONG)                     │   │
│  │    6. onClose/cancel → sessionManager.unregister()        │   │
│  │                         + playerService.leaveTavern()      │   │
│  │  }                                                        │   │
│  └──────────────────────────┬────────────────────────────────┘   │
│                             │                                     │
│  ┌──────────────────────────▼────────────────────────────────┐   │
│  │      WebSocketSessionManager.java                          │   │
│  │                                                           │   │
│  │  职责：维护 playerId ↔ WebSocketSession 映射               │   │
│  │                                                           │   │
│  │  数据结构：ConcurrentHashMap<String, WebSocketSession>     │   │
│  │                                                           │   │
│  │  · register():  存入新会话，关闭旧会话                       │   │
│  │  · unregister(): 移除会话                                  │   │
│  │  · getAllSessions(): 供 broadcast() 遍历                   │   │
│  │                                                           │   │
│  │  注意：当前单机用 HashMap，多节点时替换 Redis Pub/Sub        │   │
│  └───────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────┘
                         │ 依赖 Service 层
┌────────────────────────▼─────────────────────────────────────────┐
│                     domain/                                       │
│                                                                  │
│  ┌──────────────────────────────┐  ┌──────────────────────────┐  │
│  │      player/                  │  │      world/               │  │
│  │                              │  │                          │  │
│  │  Player.java (Entity)        │  │  Room.java (Entity)      │  │
│  │  ┌─────────────────────┐     │  │  ┌───────────────────┐   │  │
│  │  │ id: String (PK)     │     │  │  │ id: String (PK)   │   │  │
│  │  │ name: String         │     │  │  │ name: String       │   │  │
│  │  │ currentRoomId: String│     │  │  │ description: String│   │  │
│  │  │ x, y: int            │     │  │  │ width, height: int │   │  │
│  │  │ online: boolean      │     │  │  │ objectsJson: TEXT  │   │  │
│  │  │ lastActiveAt: DateTime│    │  │  │ defaultSpawnX/Y   │   │  │
│  │  └─────────────────────┘     │  │  └───────────────────┘   │  │
│  │                              │  │                          │  │
│  │  PlayerRepository (JPA)     │  │  RoomRepository (JPA)    │  │
│  │  → findByOnlineTrue()       │  │  → findAll()             │  │
│  │                              │  │                          │  │
│  │  PlayerService               │  │  WorldService            │  │
│  │  → enterTavern()            │  │  → initRooms() @PostConstruct│
│  │  → leaveTavern()            │  │    · 从 data/rooms.json   │  │
│  │  → updatePosition()         │  │      加载房间             │  │
│  │  → getOnlinePlayers()       │  │    · 兜底：创建默认房间    │  │
│  │                              │  │  → getAllRooms()         │  │
│  └──────────────────────────────┘  └──────────────────────────┘  │
└──────────────────────────────────────────────────────────────────┘
                         │
┌────────────────────────▼─────────────────────────────────────────┐
│                     dto/                                          │
│              WebSocketMessage.java                                │
│                                                                  │
│  统一消息外壳：version, requestId, type, timestamp, payload      │
│  payload 用 Map<String, Object> 而非强类型（Iteration 0 快速迭代）│
└──────────────────────────────────────────────────────────────────┘
```

### 各层依赖关系（自上而下）

```
TavernWebSocketHandler
  ├── WebSocketSessionManager   （同层，无状态服务类）
  ├── PlayerService             （domain/player 层）
  │     └── PlayerRepository    （JPA → H2）
  └── WorldService              （domain/world 层）
        └── RoomRepository      （JPA → H2）

WebSocketConfig
  └── 注册 TavernWebSocketHandler 到 /ws/tavern
```

所有外部依赖（数据库、LLM）都通过接口注入，`@ConditionalOnProperty` 切换实现。当前数据库是 H2 内存模式，切换到 PostgreSQL 只需改配置+加驱动，不涉及业务代码修改。

---

## 文件级职责说明

### 后端（11 个 Java 文件）

| 文件 | 行数 | 核心职责 |
|------|------|---------|
| `TavernTalesApplication.java` | 16 | Spring Boot 启动入口 |
| `WebSocketConfig.java` | 48 | 注册 WebSocket 路由 + HandlerAdapter |
| `WebSocketMessage.java` | 55 | 统一消息 DTO，含 version/requestId 协议字段 |
| `TavernWebSocketHandler.java` | 300 | **核心**：连接生命周期 + 消息路由 + 广播 |
| `WebSocketSessionManager.java` | 79 | 会话注册/注销/查询（ConcurrentHashMap） |
| `Room.java` | 55 | 房间实体（宽高、家具 JSON、出生点） |
| `RoomRepository.java` | 10 | JPA 接口 |
| `WorldService.java` | 98 | 启动时从 JSON 加载房间，提供查询 |
| `Player.java` | 58 | 玩家实体（位置、在线状态、活跃时间） |
| `PlayerRepository.java` | 15 | JPA 接口，含 `findByOnlineTrue()` |
| `PlayerService.java` | 97 | 玩家进入/离开/移动业务逻辑 |

### 前端（6 个源文件）

| 文件 | 行数 | 核心职责 |
|------|------|---------|
| `App.tsx` | 378 | **核心**：游戏主页面 + TavernMap 子组件 |
| `useWebSocket.ts` | 157 | WebSocket Hook（连接/重连/心跳/消息分发） |
| `game.ts` | 35 | 前后端共享类型定义 |
| `tavern.css` | 180 | 奇幻酒馆 UI 样式（暖色调木质风格） |
| `main.tsx` | 8 | React DOM 渲染入口 |
| `vite-env.d.ts` | 1 | Vite 类型声明 |

---

## 数据流全景

### 玩家进入酒馆

```
浏览器                          WebSocket                        服务器
  │                               │                               │
  │  new WebSocket(url)            │                               │
  │──────────────────────────────>│                               │
  │                               │  TavernWebSocketHandler       │
  │                               │  .handle(session)             │
  │                               │                               │
  │                               │  sessionManager.register()    │
  │                               │  playerService.enterTavern()  │
  │                               │  (创建或恢复 Player 记录)      │
  │                               │                               │
  │                               │  sendWorldState()             │
  │                               │  → 查询 rooms + players       │
  │                               │                               │
  │  ◄── WORLD_STATE ────────────│                               │
  │  { rooms, players,            │                               │
  │    yourPlayerId,              │                               │
  │    yourPosition }             │                               │
  │                               │                               │
  │  App.tsx 解析 worldState      │                               │
  │  → rooms[0] 渲染 TavernMap    │                               │
  │  → yourPosition → localPosition                               │
  │  → otherPlayers → 显示其他角色  │                               │
```

### 玩家移动

```
浏览器                          WebSocket                        服务器
  │                               │                               │
  │  用户按 W                     │                               │
  │  ┌─────────────────┐          │                               │
  │  │ 立即更新          │          │                               │
  │  │ localPosition    │          │                               │
  │  │ {x, y-10}        │          │                               │
  │  │ 角色向右移动      │          │                               │
  │  └─────────────────┘          │                               │
  │                               │                               │
  │  ── PLAYER_MOVE ────────────>│                               │
  │  { x, y }                    │                               │
  │                               │  handlePlayerMove()           │
  │                               │  playerService.updatePosition │
  │                               │  → 边界校验 (0 ≤ x ≤ width)   │
  │                               │  → 写入 H2                    │
  │                               │                               │
  │  忽略（自己发的）              │  ◄── PLAYER_MOVED ────────   │
  │                               │  (广播给所有其他在线玩家)      │
  │                               │                               │
  │                               │  其他浏览器                    │
  │                               │  → otherPlayers 更新位置       │
  │                               │  → 角色移动到新位置             │
```

### 玩家断开

```
浏览器                          WebSocket                        服务器
  │                               │                               │
  │  关闭页面 / 断网              │                               │
  │                               │  doOnCancel / doOnComplete    │
  │                               │  sessionManager.unregister()  │
  │                               │  playerService.leaveTavern()  │
  │                               │  broadcastPlayerLeft()        │
  │                               │                               │
  │                               │  ◄── PLAYER_LEFT ────────    │
  │                               │  (广播给其他玩家)              │
  │                               │                               │
  │  如果只是断网：                │                               │
  │  3s 后自动重连                  │                               │
  │  (最多 5 次重试)               │                               │
```

---

## 关键设计决策

| 决策 | 选择 | 理由 |
|------|------|------|
| **Web 框架** | Spring WebFlux (Netty) | 游戏需要大量并发长连接，Netty 事件循环模型比 Tomcat 线程池更适合 |
| **数据库** | H2 内存模式 | Iteration 0 无需持久化，启动即用零配置。后续通过 `@ConditionalOnProperty` 切换到 PostgreSQL，**业务代码零改动** |
| **通信协议** | WebSocket (非 HTTP 轮询) | 实时性要求高。WebSocket 全双工，服务器可主动推送 |
| **消息格式** | JSON (非 Protobuf) | Iteration 0 开发效率优先，JSON 可读性强。后续性能敏感时切换 Protobuf |
| **消息路由** | Handler 内 switch-case | 当前只有 3 种消息类型，switch 最直观。消息 >10 种时拆分为独立 Router |
| **位置同步** | 前端乐观更新 + 服务器校验 | 避免 WAD 操作等网络往返，手感流畅。服务器仍执行边界校验保证安全 |
| **前端架构** | 函数组件 + Hook | React 19 推荐模式，useWebSocket 封装连接逻辑可复用 |
| **状态管理** | React useState (无 Redux) | Iteration 0 状态简单（位置+玩家列表），useState 足够。后续复杂时引入 Zustand |

---

## 上线文件清单

```
cool_project/
├── CLAUDE.md
├── Makefile
├── docs/
│   ├── dev-plan.md                  # 迭代路线图
│   ├── npc-system.md                # NPC 性格/记忆/情感设计
│   ├── api-protocol.md              # 通信协议
│   └── project-summary.md           ← 本文件
│
├── server/                          # Java 后端 (11 个源文件)
│   ├── pom.xml
│   ├── mvnw
│   └── src/main/java/com/taverntales/
│       ├── TavernTalesApplication.java
│       ├── config/WebSocketConfig.java
│       ├── dto/WebSocketMessage.java
│       ├── domain/
│       │   ├── player/
│       │   │   ├── Player.java
│       │   │   ├── PlayerRepository.java
│       │   │   └── PlayerService.java
│       │   └── world/
│       │       ├── Room.java
│       │       ├── RoomRepository.java
│       │       └── WorldService.java
│       └── controller/ws/
│           ├── TavernWebSocketHandler.java
│           └── WebSocketSessionManager.java
│
└── frontend/                        # React 前端 (6 个源文件)
    └── src/
        ├── main.tsx
        ├── App.tsx
        ├── hooks/useWebSocket.ts
        ├── types/game.ts
        └── styles/tavern.css
```
