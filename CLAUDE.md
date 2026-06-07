# 奇幻酒馆 — Tavern Tales

> AI NPC 游戏引擎。Java + Python + React 混合架构，玩家和拥有记忆性格的 AI NPC 自由互动。
> 技术栈：Java 21 / Spring Boot 3.4 / Maven / Python 3.11 / FastAPI / DeepSeek API / React 19 + Vite / H2

## 铁律

1. **NPC 是数据不是代码** — 新增 NPC = 新增配置文件，不改 Java 代码。NpcFactory 读取配置目录动态加载
2. **游戏循环不阻塞 AI** — AI 调用必须通过异步队列提交，超时走兜底（NPC 沉默或模板回复）。游戏主循环只管发请求、收结果，不等
3. **外部依赖有接口抽象** — LLM 提供商、数据库、向量存储器都定义为 Java 接口，`@ConditionalOnProperty` 切换实现
4. **注释写"为什么"不写"是什么"** — 代码本身说明做什么。注释写：为什么选这个方案、有什么坑、调用方是谁

## 当前范围（Iteration 0-1）

### 做
- Spring Boot + Netty WebSocket 做服务端
- NPC 以 JSON 文件定义，代码动态加载
- 前端用 React + Vite + TypeScript，Canvas 2D 渲染
- Java ↔ Python 通过 HTTP（FastAPI）通信，不用 gRPC
- 数据库用 H2（内存模式），不接 PostgreSQL/Redis
- NPC 对话从第一天直接调 DeepSeek API，不做硬编码对话树

### 不做
- ❌ 不做 gRPC（Iteration 2 后再评估）
- ❌ 不做 PostgreSQL/Redis/ChromaDB（用 H2 平替）
- ❌ 不做 NPC 复杂行为树（有限状态机够用）
- ❌ 不做 NPC 自主社交/日程系统
- ❌ 不做多玩家同屏

## 目录

- `server/` — Spring Boot 游戏服务器后端
- `ai-service/` — Python FastAPI AI 推理服务
- `frontend/` — React + Vite + TypeScript 前端
- `docs/` — 详细设计文档（按需读取）

## 启动

```bash
make dev-java   # Spring Boot + H2 WebSocket 服务
make dev-ai     # Python FastAPI AI 服务
make dev-web    # React 前端
```

## 代码要求

- Java: `com.taverntales.*`，方法 >20 行考虑拆分
- 每个新 `public` 方法必须有注释块：说明理由和注意事项
- NPC 配置 JSON 每个字段必须有 `//` 注释
- 前端组件遵循 React 函数组件 + TypeScript 类型定义

## 更多设计细节

- [docs/dev-plan.md](docs/dev-plan.md) — 完整迭代路线图
- [docs/npc-system.md](docs/npc-system.md) — NPC 性格/记忆/情感设计
- [docs/api-protocol.md](docs/api-protocol.md) — WebSocket 消息格式
