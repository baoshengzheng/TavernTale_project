# 奇幻酒馆 — 开发命令

.PHONY: dev-java dev-web dev-all clean

# 启动后端（Spring Boot + H2，端口 8080）
dev-java:
	cd server && ./mvnw spring-boot:run

# 启动 AI 服务（Python FastAPI，端口 8000）
dev-ai:
	cd ai-service && pip install -r requirements.txt -q && python -m uvicorn main:app --reload --port 8000

# 启动前端（React + Vite，端口 5173）
dev-web:
	cd frontend && npm run dev

# 全部启动（在各自终端执行）
dev-all:
	@echo "请分别在三个终端中执行:"
	@echo "  make dev-java"
	@echo "  make dev-ai"
	@echo "  make dev-web"

# 清理
clean:
	cd server && ./mvnw clean
	cd frontend && rm -rf node_modules dist
