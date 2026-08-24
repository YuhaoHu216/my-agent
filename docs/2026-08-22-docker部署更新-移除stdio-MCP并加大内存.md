# Docker 部署更新：移除 stdio MCP 残留 + 加大内存 + 加 Caddy 域名

> 日期：2026-08-22
> 背景：项目移除 stdio 方式 MCP（原 Node.js/npx 高德地图 MCP 子进程），MCP 改为用户前端自行配置 SSE（URL）服务；部署从 8.156.66.24 迁移到 47.108.217.78（刚重装，内存 1.72G，OS 约 0.35G，可用约 1.37G）。

## 改动内容

### 1. `src/main/resources/docker/docker-compose.yml`

- **删除 stdio MCP 残留**：移除 `NODE_OPTIONS=--max-old-space-size=192` 及其注释（Node.js 高德地图 MCP 子进程已不存在）。
- **加大 JVM 堆**：`JAVA_OPTS` 由 `-Xmx512m` → `-Xmx640m`，`-XX:MaxMetaspaceSize` 128m → 160m，其余（`-Xms512m`、`MaxDirectMemorySize 256m`、G1GC）保留。
- **加大 Docker 硬限制**：`mem_limit` 1024m → **1.3g**。
- **新增 caddy 服务**：`caddy:2-alpine`，端口 80/443，挂载 `./caddy/Caddyfile` + data/config（TLS 证书），`depends_on agent-web`。

### 2. `src/main/resources/docker/caddy/Caddyfile`（新增）

```
agent.huyuhao.top {
	reverse_proxy agent-web:5173
}
```

## 内存分配（47 服务器，可用 ≈1.37G）

| 项 | 上限/占用 |
|---|---|
| JVM 堆 `-Xmx` | 640M |
| Metaspace | 160M |
| 直接内存 | 256M |
| code cache + 线程栈 + 原生 | ~100-150M |
| **JVM 最坏合计** | **≈1.2G** |
| agent-api `mem_limit` | **1.3g** |
| agent-web(nginx) / caddy | ~10M / ~30M |
| **合计** | **1.34G < 1.37G** ✓ |

## 未改文件

- `dockerfile`（已无 stdio 残留）、`docker-compose-infra.yml`（8.156 上已运行）、`docker-compose-milvus.yml`（未启用）、`application-prod.yml`（工作区已指向 8.156 infra）、redis.conf。

## 部署执行步骤（供后续操作）

1. 47 重加公钥 `ssh-copy-id root@47.108.217.78`；装 docker + `docker network create shared-infra`。
2. 打包新 jar（含指向 8.156 的 `application-prod.yml`），按 8.156 `/root/my-agent` 目录结构上传：compose、dockerfile、`caddy/`、`backend/`（jar+application-prod.yml）、`frontend/nginx/`（nginx.conf+html）、`uploads/`。
3. `docker compose up -d --build`。
4. DNS：`agent.huyuhao.top` A 记录从 8.156 改到 47.108.217.78；caddy 自动签 TLS。
5. 8.156 收尾：停旧 agent-api/agent-web，从 `/root/caddy/Caddyfile` 删除 `agent.huyuhao.top` 条目（DNS 切完再动）。

## 验证结果

- `docker compose config -q` 校验通过。
