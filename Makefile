# 奇幻酒馆 — 开发命令

.PHONY: dev-java dev-web dev-all clean

# 启动后端（Spring Boot + H2，端口 8080）
dev-java:
	cd server && ./mvnw spring-boot:run

# 启动前端（React + Vite，端口 5173）
dev-web:
	cd frontend && npm run dev

# 全部启动（需要先安装依赖）
dev-all:
	@echo "请在三个终端分别执行: make dev-java, make dev-ai, make dev-web"

# 清理
clean:
	cd server && ./mvnw clean
	cd frontend && rm -rf node_modules dist
