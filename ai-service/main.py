"""奇幻酒馆 — AI 推理服务入口。

职责：接收 Java 服务器发来的对话请求，构建 Prompt 后调用 DeepSeek API，
将生成的 NPC 回复返回给 Java 服务。

为什么用 FastAPI？
  轻量、异步、类型安全（Pydantic），适合作为 Java 和 LLM 之间的薄层代理。
"""

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from routers import dialogue

app = FastAPI(title="Tavern Tales AI Service", version="0.1.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:5173"],
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(dialogue.router)


@app.get("/health")
async def health():
    return {"status": "ok", "service": "tavern-tales-ai"}
