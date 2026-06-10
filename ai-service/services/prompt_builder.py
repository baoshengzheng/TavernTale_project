"""Prompt 构建器 — 将 NPC 信息和玩家消息渲染为完整的 System Prompt + User Message。

为什么用 Jinja2 而非 f-string？
  模板复杂度会随迭代增长（记忆、情绪、好感度规则）。
  Jinja2 支持条件渲染和循环，比 f-string 更易维护。
"""

from jinja2 import Environment, FileSystemLoader, TemplateNotFound
from pathlib import Path

_template_env = Environment(
    loader=FileSystemLoader(Path(__file__).parent.parent / "prompts"),
    autoescape=False,
)


def build_system_prompt(request: dict) -> str:
    """
    构建 System Prompt，描述 NPC 的身份和对话规则。
    回退策略：模板文件缺失时使用简单的 f-string 兜底。
    """
    try:
        template = _template_env.get_template("default.j2")
        return template.render(**request)
    except TemplateNotFound:
        return _build_fallback_prompt(request)


def build_user_message(player_message: str) -> str:
    """用户消息直接透传，不加修饰"""
    return player_message


def _build_fallback_prompt(req: dict) -> str:
    """模板文件缺失时的兜底 Prompt，确保 NPC 不会完全静默"""
    personality = req.get("personality", {})
    return (
        f"你是 {req.get('npc_name', 'NPC')}，{req.get('npc_title', '')}。\n"
        f"背景：{req.get('background', '无')}\n"
        f"说话风格：{req.get('speaking_style', '自然对话')}\n"
        f"当前情绪：{req.get('current_emotion', 'neutral')}\n"
        f"玩家好感度：{req.get('relationship_score', 50)}\n"
        f"回复不超过 60 字。符合你的性格。\n"
    )
