# 问题定位手册

常见问题的诊断步骤和解决方案。

---

## 1. NPC 始终回复"沉默了片刻，没有回答"

> 发现时间：2026-06-14
> 影响范围：所有 NPC 对话

### 症状

- 玩家与 NPC 对话，始终收到 `…（Edith沉默了片刻，没有回答）`
- 前端对话面板正常显示，但 NPC 回复永远是兜底文案
- `DIALOGUE` WebSocket 消息中 `fallback: true`

### 原因

Python AI 服务的 uvicorn 进程处于**过期状态**。Python 模块级单例对象（`llm_client.py` 中的 `_client`、`config.py` 中的 `settings`）在长时间运行后状态不一致，导致 DeepSeek API 调用在模块内部抛异常，`chat()` 返回空字符串，router 走兜底回复。

具体可能触发因素：
- 在 uvicorn 运行时执行了 `pip install`（openai 包版本变更）
- 配置文件修改后 `--reload` 未正确重新初始化模块级状态
- uvicorn 进程运行了数天，经历了多次代码变更但没有完全重启

### 诊断步骤

按顺序执行：

#### 第 1 步：检查 Python 服务是否存活

```bash
curl -s http://localhost:8000/health
```

- 期望：`{"status":"ok","service":"tavern-tales-ai"}`
- 如果无响应 → Python 服务没启动，执行 `make dev-ai`

#### 第 2 步：检查 API 响应中的 fallback 标记

```bash
curl -s -X POST http://localhost:8000/api/dialogue \
  -H "Content-Type: application/json" \
  -d '{"npc_id":"edith","npc_name":"Edith","player_id":"test","player_message":"你好"}' \
  | python -c "import sys,json; d=json.load(sys.stdin); print('fallback:', d['fallback'], '| response:', d['npc_response'][:50])"
```

- `fallback: False` → AI 调用正常
- `fallback: True` → AI 调用失败，进入第 3 步

#### 第 3 步：直接测试 DeepSeek API 连通性

```bash
curl -s https://api.deepseek.com/v1/models \
  -H "Authorization: Bearer sk-09748d2e44a04be6b1bd202fd7fddda2" \
  --connect-timeout 5
```

- 返回模型列表 → API Key 有效，网络正常
- 返回 401 → API Key 过期/无效，更新 `ai-service/config.py` 中的 `deepseek_api_key`
- 连接超时 → 网络问题（代理/VPN/防火墙）

#### 第 4 步：用 venv Python 模拟完整调用链路

```bash
cd ai-service
venv/Scripts/python -c "
from services.llm_client import chat
from services.prompt_builder import build_system_prompt
from models.schemas import DialogueRequest

req = DialogueRequest(npc_id='edith', npc_name='Edith', player_id='t', player_message='你好')
sp = build_system_prompt(req.model_dump())
result = chat(sp, '你好')
print('Result:', repr(result), '| Empty:', result == '')
"
```

- 返回正常中文回复 → 代码逻辑没问题，是 uvicorn 进程状态问题，进入"解决方案"
- 返回空字符串或报错 → 代码层面有问题（API Key、网络、包版本）

### 解决方案

#### 方案 A：完全重启（推荐，100% 有效）

```bash
# Windows
taskkill /f /im python.exe
make dev-ai

# macOS / Linux
pkill -9 -f uvicorn
make dev-ai
```

#### 方案 B：温和重启（如果 Python 进程还正常）

```bash
# 在运行 uvicorn 的终端中按 Ctrl+C 停止，然后重新执行
make dev-ai
```

### 验证

重启后执行第 2 步的 curl 测试，确认 `fallback: False`。

---

## 2. 待补充

> 后续发现新问题时按此格式追加。
