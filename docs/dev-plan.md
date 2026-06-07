# 开发计划

> 每次迭代结束时必须可演示。迭代周期不固定，完成即进入下一轮。

## Iteration 0 — 骨架搭建

**目标**：Spring Boot 启动，浏览器打开能看到酒馆地图，WebSocket 已连接。

### 交付物
- [ ] Spring Boot 3.4 项目初始化（Maven + pom.xml）
- [ ] WebSocket 端点（`/ws/tavern`），支持连接/心跳/断连
- [ ] 简单的世界模型：Room（房间）定义，酒馆地图 JSON
- [ ] 玩家实体：进入酒馆、获取初始位置
- [ ] H2 数据库配置 + JPA 实体
- [ ] React + Vite 前端脚手架，显示酒馆 CSS 俯视图
- [ ] WebSocket 连接指示器（页面显示连接状态）

### 演示标准
浏览器打开 `localhost:5173` → 看到酒馆俯视图（CSS 画的吧台/桌椅/壁炉）→ WebSocket 状态绿色 ✅ → 玩家角色显示在门口

### 不做的
- NPC（Iteration 1 做）
- 对话系统
- Python AI 服务

---

## Iteration 1 — 第一个 AI NPC

**目标**：Edith 老板娘站在吧台后，玩家点她能自由对话。

### 交付物
- [ ] NPC 数据模型 + JSON 配置（Edith 的 OCEAN 参数、背景 Prompt、位置）
- [ ] NpcFactory：启动时从配置目录加载所有 NPC
- [ ] NPC 感知系统：玩家进入 NPC 触发范围
- [ ] 对话触发 UI：点 NPC → 弹出对话面板
- [ ] Python FastAPI 项目：`POST /api/dialogue` 接受对话请求
- [ ] Java 侧 `LlmProvider` 接口 + HTTP 实现调 Python 服务
- [ ] AiIntegrationService：异步提交对话请求 + 超时兜底
- [ ] 好感度系统（H2 持久化）
- [ ] 兜底机制：AI 服务不可用时，NPC 沉默或返回模板回复

### 演示标准
走到 Edith 面前 → 点击 → 输入"你好，今天有什么好吃的？" → Edith 用 DeepSeek 生成回复 → 好感度 +1 → 所有对话 H2 有记录

---

## Iteration 2 — 动态酒馆

**目标**：3 个 NPC，各有性格，会在酒馆里走动。

### 交付物
- [ ] Roderick（醉汉）和 Luna（神秘旅人）的 NPC 配置
- [ ] Canvas 2D 渲染替代 CSS（WASD 移动 + 精灵渲染）
- [ ] NPC 状态机：IDLE / PATROL / DIALOGUE
- [ ] NPC 巡逻路径（JSON 配置路径点）
- [ ] OCEAN 性格注入 Prompt（Python 侧）
- [ ] 简单记忆系统：NPC 记住玩家说过的事（H2 存储 key-value）
- [ ] 物品系统原型（玩家可以送酒）

### 演示标准
WASD 在酒馆里走动 → 靠近 Edith 她抬头 → 靠近 Roderick 他醉醺醺搭话 → 送 Luna 一杯酒 → 她态度微妙变化

---

## Iteration 3+（待规划）

- NPC 三层记忆架构（短期/长期/向量）
- NPC 自主社交（NPC 之间会聊天）
- NPC 日程系统（起床→工作→社交→睡觉）
- 任务系统
- PostgreSQL/Redis 替换 H2
- gRPC 替换 HTTP
