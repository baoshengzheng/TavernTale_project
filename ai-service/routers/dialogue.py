"""对话路由 — POST /api/dialogue。

为什么把 Prompt 拼接和 LLM 调用分开？
  prompt_builder 负责"把输入变成 prompt"，llm_client 负责"调 API"。
  两个职责独立演进：改模板不影响调用逻辑，换 LLM 提供商不影响模板。
"""

from fastapi import APIRouter
from models.schemas import DialogueRequest, DialogueResponse
from services.prompt_builder import build_system_prompt, build_user_message
from services.llm_client import chat

router = APIRouter(prefix="/api", tags=["dialogue"])


@router.post("/dialogue", response_model=DialogueResponse)
async def handle_dialogue(request: DialogueRequest):
    """
    处理一次对话请求。

    流程：
    1. 用 NPC 信息 + 玩家消息构建 System Prompt
    2. 调 DeepSeek API 生成回复
    3. 成功 → 返回 DIALOGUE（fallback=false）
    4. 失败/空回复 → 返回兜底 DIALOGUE（fallback=true）
    """
    system_prompt = build_system_prompt(request.model_dump())
    user_message = build_user_message(request.player_message)

    npc_response = chat(system_prompt, user_message)

    if npc_response:
        return DialogueResponse(
            npc_response=npc_response,
            emotion_change="joy: +5",
            new_memories=f"玩家说：{request.player_message[:50]}",
            actions=[],
            fallback=False,
        )
    else:
        return DialogueResponse(
            npc_response=f"…（{request.npc_name}沉默了片刻，没有回答）",
            emotion_change=None,
            new_memories=None,
            actions=[],
            fallback=True,
        )
