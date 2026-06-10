"""Pydantic 数据模型 — 请求/响应结构定义。

为什么用 Pydantic 而非 dataclass？
  FastAPI 原生支持 Pydantic，自动生成 OpenAPI 文档和请求校验。
"""

from pydantic import BaseModel
from typing import Optional


class Personality(BaseModel):
    """OCEAN 五因素人格"""
    openness: int = 50
    conscientiousness: int = 50
    extraversion: int = 50
    agreeableness: int = 50
    neuroticism: int = 50


class DialogueRequest(BaseModel):
    """Java 服务发来的对话请求"""
    npc_id: str
    npc_name: str = ""
    npc_title: str = ""
    player_id: str
    player_name: str = "旅人"
    player_message: str
    background: str = ""
    speaking_style: str = ""
    personality: Personality = Personality()
    current_emotion: str = "{}"
    relationship_score: int = 50
    recent_memories: str = "{}"
    world_context: str = ""


class DialogueResponse(BaseModel):
    """返回给 Java 服务的对话响应"""
    npc_response: str
    emotion_change: Optional[str] = None
    new_memories: Optional[str] = None
    actions: list[str] = []
    fallback: bool = False
