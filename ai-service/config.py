"""服务配置 — 通过环境变量或 .env 文件注入。

为什么用 pydantic-settings 而不是 os.getenv？
  pydantic-settings 提供类型验证、默认值、.env 文件自动加载，
  避免启动后发现配置缺失导致的运行时错误。
"""

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    model_config = {"env_file": ".env", "env_file_encoding": "utf-8"}

    deepseek_api_key: str = "sk-your-api-key-here"
    deepseek_base_url: str = "https://api.deepseek.com"
    deepseek_model: str = "deepseek-chat"
    llm_timeout: int = 10  # DeepSeek API 超时秒数
    max_response_tokens: int = 150  # NPC 回复最大 token 数


settings = Settings()
