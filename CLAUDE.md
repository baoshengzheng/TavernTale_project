# 奇幻酒馆 — Tavern Tales

> AI NPC 游戏引擎。Java + Python + React 混合架构，玩家和拥有记忆性格的 AI NPC 自由互动。
> 技术栈：Java 21 / Spring Boot 3.4 / Maven / Python 3.11 / FastAPI / DeepSeek API / React 19 + Vite / H2

## 已决策的设计取舍

> 记录经过讨论确认的架构选择。新增设计决策时在此追加，避免反复争论。

| 决策 | 选择 | 为什么 | 重审时机 |
|------|------|--------|---------|
| **为什么有 Python 层** | Java → Python HTTP → DeepSeek | 人因决策：团队后续有 Python 工程师，且 Iteration 3 的 embedding/RAG 需 Python ML 生态。技术层面 Java 直接调 DeepSeek 完全可行，引入 Python 增加一跳延迟和一个故障点，是用运维复杂度换未来开发效率 | Iteration 3 评估 |
| **前端渲染** | Iteration 0-1 CSS，Iteration 2 Canvas 2D | CSS 开发快，先验证通信逻辑。Canvas 需要渲染引擎，NPC 系统稳定后再替换 | Iteration 2 |
| **H2 持久化** | 内存模式，重启全丢 | Iteration 0-1 只验证流程，数据丢了可接受。Iteration 2 有 NPC 记忆后再切文件模式或 PostgreSQL | Iteration 2 |
| **游戏循环** | Iteration 0-1 事件驱动，Iteration 2 上 tick | 当前 NPC 只在玩家互动时响应，不需要独立巡逻。`game.tick-rate: 200` 配置已预留 | Iteration 2 |
| **NPC 对话并发** | 一个 NPC 同时只能和一个人对话 | 模拟真实世界社交规则。NPC 被锁定时其他玩家点击收到 `DIALOGUE_BUSY`。玩家断连/走远/超时自动释放 | — |
| **NPC 感知检测** | 前端视觉提示 + 后端权威判定 | 前端算距离高亮 NPC（纯 UI），后端在 `handlePlayerMove` 中做权威距离校验并决定对话是否允许 | — |

## 铁律

1. **NPC 是数据不是代码** — 新增 NPC = 新增配置文件，不改 Java 代码。NpcFactory 读取配置目录动态加载。
   - **NPC 定义**（JSON，不可变）：OCEAN 性格、背景故事、说话风格。放 `server/src/main/resources/npcs/`
   - **NPC 运行时状态**（H2 `npc_states` 表，可变）：当前位置、情绪值、FSM 状态、好感度、记忆。先塞一张表，字段多了再拆
2. **游戏循环不阻塞 AI** — AI 调用必须通过异步队列提交，超时走兜底（NPC 沉默或模板回复）。游戏主循环只管发请求、收结果，不等。
   - Iteration 0-1：事件驱动，无 tick 循环。玩家移动事件触发 NPC 感知检测
   - Iteration 2+：上 tick 循环（`game.tick-rate: 200`），轮询 NPC 巡逻/感知
3. **外部依赖有接口抽象** — LLM 提供商、向量存储器定义为 Java 接口，`@ConditionalOnProperty` 切换实现。数据库已有 JPA 抽象，无需额外包装。当前只有 LLM 提供商需要接口（DeepSeek / Mock）
4. **注释写"为什么"不写"是什么"** — 代码本身说明做什么。注释写：为什么选这个方案、有什么坑、调用方是谁

## 当前范围（Iteration 0-1）

### 做
- Spring Boot + Netty WebSocket 做服务端
- NPC 以 JSON 文件定义，代码动态加载
- 前端用 React + Vite + TypeScript，CSS 渲染酒馆俯视图（Iteration 2 切换 Canvas 2D）
- Java ↔ Python 通过 HTTP（FastAPI）通信，不用 gRPC
- 数据库用 H2（内存模式），不接 PostgreSQL/Redis
- NPC 对话从第一天直接调 DeepSeek API，不做硬编码对话树

### 不做
- ❌ 不做 gRPC（Iteration 2 后再评估）
- ❌ 不做 PostgreSQL/Redis/ChromaDB（用 H2 平替）
- ❌ 不做 NPC 复杂行为树（有限状态机够用）
- ❌ 不做 NPC 自主社交/日程系统
- ❌ 不做多玩家复杂交互（碰撞、交易、组队）

## 目录

- `server/` — Spring Boot 游戏服务器后端
- `ai-service/` — Python FastAPI AI 推理服务
- `frontend/` — React + Vite + TypeScript 前端
- `docs/` — 详细设计文档（按需读取）

## 启动

```bash
make dev-java   # Spring Boot + H2 WebSocket 服务
make dev-ai     # Python FastAPI AI 服务（Iteration 1 实现，当前未就绪）
make dev-web    # React 前端
```

## 代码要求

- Java: `com.taverntales.*`，每个方法保持职责单一。50 行是检查信号——超过时审视是否做了太多事，而不是机械拆分
- 每个新 `public` 方法必须有注释块：说明理由和注意事项
- NPC 配置 JSON 每个字段需有说明（用 `_comment` 字段或维护独立字段说明文档）。注意：标准 JSON 不支持 `//` 注释，若使用需配置 Jackson `JsonParser.Feature.ALLOW_COMMENTS`
- 前端组件遵循 React 函数组件 + TypeScript 类型定义

## 更多设计细节

- [docs/dev-plan.md](docs/dev-plan.md) — 完整迭代路线图
- [docs/npc-system.md](docs/npc-system.md) — NPC 性格/记忆/情感设计
- [docs/api-protocol.md](docs/api-protocol.md) — WebSocket 消息格式
