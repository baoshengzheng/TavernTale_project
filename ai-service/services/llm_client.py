"""LLM 客户端 — 封装 DeepSeek API 调用。

为什么用 openai Python SDK 而非 httpx？
  DeepSeek API 兼容 OpenAI 格式，openai 库可以直接切换 base_url 使用。
  避免手写 HTTP 请求和 Bearer token 管理。
"""

from openai import OpenAI
from config import settings

_client = OpenAI(
    api_key=settings.deepseek_api_key,
    base_url=settings.deepseek_base_url,
    timeout=settings.llm_timeout,
)


def chat(system_prompt: str, user_message: str) -> str:
    """
    调用 DeepSeek Chat API 生成 NPC 回复。

    Returns:
        NPC 回复文本。异常时返回空字符串，由调用方走兜底逻辑。
    """
    try:
        response = _client.chat.completions.create(
            model=settings.deepseek_model,
            messages=[
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_message},
            ],
            max_tokens=settings.max_response_tokens,
            temperature=0.8,
        )
        return response.choices[0].message.content.strip()
    except Exception as e:
        # 不在这里做兜底，让 router 根据空返回值决定是否返回 503
        print(f"[LLM] DeepSeek API 调用失败: {e}")
        return ""
