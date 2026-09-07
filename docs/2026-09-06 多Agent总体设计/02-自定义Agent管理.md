# 阶段 02：自定义 Agent 管理

> 日期：2026-09-06
> 依赖：阶段 01（Skill 数据已存在）。
> 目标：让用户创建自己的 agent（定义系统提示词、选择模型供应商+模型名、绑定 Skill 和 MCP server），并做 CRUD 管理与前端页面。
> 前置阅读：`00-多Agent机制-总体设计.md`、`01-Skill技能管理.md`

## 1. 背景与目标

本阶段完成 agent 的**定义与管理**（数据层 + 管理界面），**不含对话执行**（执行在阶段 03 做）。

一个 agent 由以下组成：
- **基础信息**：名称、系统提示词、下一步提示词（可选，默认用内置兜底）、启用开关。
- **模型**：`provider`（DASHSCOPE/DEEPSEEK）+ `modelName`。对话时用它构建 ChatModel（阶段 03）。
- **绑定 Skill**：多对多（`user_agent_skill`）。多条 skill 的提示词按序拼接进 systemPrompt。
- **绑定 MCP server**：多对多（`user_agent_mcp`），绑定的是「用户自己的 MCP server」（`user_mcp_server` 表的记录），执行时只加载这些 server 的工具。

> 决策：MCP 绑定按 **server 粒度**（不是按工具），与现有 `UserMcpToolManager.getToolsForUser(userId)`（返回全部启用 server）不同——阶段 03 会给该 Manager 新增「按 serverId 子集获取工具」的方法。

## 2. 数据模型

### 2.1 DDL（追加到 `resources/my_agent.sql`）

```sql
-- 用户自定义 Agent 表
DROP TABLE IF EXISTS user_agent;
CREATE TABLE user_agent (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    agent_name VARCHAR(100) NOT NULL COMMENT 'Agent名称(同一用户内唯一)',
    system_prompt TEXT NOT NULL COMMENT 'Agent系统提示词',
    next_step_prompt TEXT DEFAULT NULL COMMENT '下一步提示词(可空,为空用内置兜底)',
    provider VARCHAR(20) NOT NULL COMMENT '模型提供商: DASHSCOPE-通义千问, DEEPSEEK-DeepSeek',
    model_name VARCHAR(100) NOT NULL COMMENT '模型名称',
    enabled TINYINT DEFAULT 1 COMMENT '是否启用: 0-禁用, 1-启用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_user_agent_name (user_id, agent_name),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户自定义Agent表';

-- Agent 与 Skill 绑定表
DROP TABLE IF EXISTS user_agent_skill;
CREATE TABLE user_agent_skill (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    agent_id BIGINT NOT NULL COMMENT 'Agent ID',
    skill_id BIGINT NOT NULL COMMENT 'Skill ID',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_user_agent_skill (user_id, agent_id, skill_id),
    INDEX idx_user_id (user_id),
    INDEX idx_agent_id (agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent-Skill绑定表';

-- Agent 与 MCP Server 绑定表
DROP TABLE IF EXISTS user_agent_mcp;
CREATE TABLE user_agent_mcp (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    agent_id BIGINT NOT NULL COMMENT 'Agent ID',
    mcp_server_id BIGINT NOT NULL COMMENT 'MCP Server ID(对应user_mcp_server.id)',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_user_agent_mcp (user_id, agent_id, mcp_server_id),
    INDEX idx_user_id (user_id),
    INDEX idx_agent_id (agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent-MCP绑定表';
```

### 2.2 实体

- `entity/UserAgent.java`：`id/userId/agentName/systemPrompt/nextStepPrompt/provider/modelName/enabled/createTime/updateTime`。
- `entity/UserAgentSkill.java`：`id/userId/agentId/skillId/createTime`。
- `entity/UserAgentMcp.java`：`id/userId/agentId/mcpServerId/createTime`。

均 `@Data + @TableName + @TableId(type=IdType.AUTO) + @TableField("snake_case")`（create/update 字段风格同 `UserMcpServer`）。

## 3. 后端实现

### 3.1 Mapper（3 个）

```java
@Mapper
public interface UserAgentMapper extends BaseMapper<UserAgent> { }
@Mapper
public interface UserAgentSkillMapper extends BaseMapper<UserAgentSkill> { }
@Mapper
public interface UserAgentMcpMapper extends BaseMapper<UserAgentMcp> { }
```

绑定表查询（按 agentId 查 binding 列表）建议在 XML 里写联查拿全量信息（一次拿 skills/mcps 详情，避免 service 层多次 DB 往返）：

`resources/mapper/UserAgentSkillMapper.xml`：
```xml
<!-- 查某 agent 关联的 skill 详情 -->
<select id="selectSkillsByAgentId" resultType="space.huyuhao.myagent.dto.UserSkillDto">
    SELECT s.id, s.user_id AS userId, s.skill_name AS skillName,
           s.skill_content AS skillContent, s.enabled, s.create_time AS createTime, s.update_time AS updateTime
    FROM user_agent_skill b
    JOIN user_skill s ON s.id = b.skill_id
    WHERE b.user_id = #{userId} AND b.agent_id = #{agentId} AND s.enabled = 1
    ORDER BY b.id DESC
</select>
```
`resources/mapper/UserAgentMcpMapper.xml` 同理查 `user_mcp_server` 详情。

> 若嫌手写字段映射繁琐，也可在 service 层用 BaseMapper 查绑定行再逐个 `selectById` 拼装——数据量小，二选一即可，保持一致风格优先。

### 3.2 Service

`service/UserAgentService.java` + `impl`，方法：`list / add / update / delete(id) / toggle(id) / bindSkills(agentId, List<Long> skillIds) / bindMcps(agentId, List<Long> mcpServerIds) / getDetail(agentId)`。

关键点（全部仿 `UserMcpServerServiceImpl`）：
- `add`：校验 `agentName` 唯一；`provider` 必须合法（用 `UserChatModelManager.normalizeProvider` 归一化成 `DASHSCOPE/DEEPSEEK` 后落库）；`modelName` 非空。
- `update`：归属校验（`selectById` + `user_id` 比对）后再 `updateById`。
- `bindSkills`：先 `delete` 该 agent 已绑定的 skill 关系（全量替换策略，参考 `UserLlmConfigServiceImpl.save` 的清空重插），再逐条 `insert`，并对 skillId 做归属校验（skill 必须属于该用户）防越权。
- `bindMcps`：同样全量替换 `user_agent_mcp`；注意同时校验 `mcpServerId` 属于该用户。
- 返回给前端的列表带「是否启用」即可；detail 返回绑定的 skills/models/mcps 详情（DTO 聚合）。

### 3.3 DTO

- `dto/UserAgentRequest.java`：`agentName(@NotBlank)/systemPrompt(@NotBlank)/nextStepPrompt/provider(@NotBlank)/modelName(@NotBlank)/enabled`。
- `dto/UserAgentDto.java`：id/agentName/systemPrompt/nextStepPrompt/provider/modelName/enabled/createTime/updateTime。
- `dto/UserAgentDetailDto.java`：`UserAgentDto` 基础上聚合 `List<UserSkillDto> skills` + `List<UserMcpServerDto> mcps`（供前端编排/详情页用）。
- `dto/UserAgentBindSkillRequest.java`：`agentId + List<Long> skillIds`；`UserAgentBindMcpRequest.java`：`agentId + List<Long> mcpServerIds`。

### 3.4 Controller `controller/AgentController.java`（前缀 `/agent`）

```java
@GetMapping("/list")                    // 我创建的 agent 列表
@PostMapping("/add")                    // 新增
@PutMapping("/update")                  // 更新
@DeleteMapping("/{id}")                 // 删除（联动删绑定关系）
@PutMapping("/toggle/{id}")             // 启停
@GetMapping("/{id}")                    // 详情含 skill/mcp
@PutMapping("/{id}/skills/bind")        // 绑定 skills（全量替换）
@PutMapping("/{id}/mcps/bind")          // 绑定 mcp servers（全量替换）
```

delete 时需手动删三张表的绑定关系（`user_agent_skill`、`user_agent_mcp`）。同样可注入 `UserSkillService`/`UserMcpServerService` 或直接在 mapper 层删除。

## 4. 前端实现

### 4.1 API `src/api/agent.js`

```js
const agentApi = {
  list: () => request.get('/agent/list'),
  add: (data) => request.post('/agent/add', data),
  update: (data) => request.put('/agent/update', data),
  deleteById: (id) => request.delete(`/agent/${id}`),
  toggle: (id) => request.put(`/agent/toggle/${id}`),
  detail: (id) => request.get(`/agent/${id}`),
  bindSkills: (id, skillIds) => request.put(`/agent/${id}/skills/bind`, { agentId: id, skillIds }),
  bindMcps: (id, mcpServerIds) => request.put(`/agent/${id}/mcps/bind`, { agentId: id, mcpServerIds })
}
```

### 4.2 页面 `src/views/AgentManage.vue`

基于 `McpConfig.vue` 的 表格+弹窗 模式，外加两个绑定弹窗：

- **列表页**：表格列 `agent_name / provider / model_name / 启停 switch / 创建时间 / 操作`；操作列「编辑」「绑定技能」「绑定MCP」「删除」。
- **新增/编辑弹窗**：名称、系统提示词（`el-input type="textarea"`）、下一步提示词（可空 textarea）、供应商下拉（`DASHSCOPE/DEEPSEEK`，同 `LlmConfig.vue` 的 providerOptions）+ 对应模型下拉（由 `llmConfigApi.presets()` 预置列表 + 该用户已配置模型的 `llm-config/list` 而来；模型选择依赖「该供应商已配置可用 LLM」，见注意点③）。
- **绑定技能弹窗**：`el-transfer` 或 `el-select multiple` 从 `skillApi.list()` 选取，保存调 `agentApi.bindSkills(agentId, skillIds)`。
- **绑定MCP弹窗**：同理从 `mcpApi.list()` 选取 server，保存调 `agentApi.bindMcps`。

### 4.3 路由 + 导航

- `router/index.js` children 加 `{ path: 'agents', name: 'Agents', component: ..., meta: { title: 'Agent 管理' } }`。
- `AppLayout.vue` `main-nav` 加导航项。

## 5. 验证

1. 编译通过。
2. 前端创建/编辑/删除一个 agent，能绑定/解绑技能和 MCP server；DB 三张表记录正确、越权 skill/server 传引用时被拒绝。
3. agent 列表、详情接口返回正确。

## 6. 注意点

1. **删除级联**：删除 agent 时同步删 `user_agent_skill`、`user_agent_mcp` 绑定行。
2. **provider 归一化**：落库统一用 `UserChatModelManager.normalizeProvider()`（QWEN→DASHSCOPE）。
3. **模型选择来源**：agent 绑定的 modelName 必须能在阶段 03 构建出 ChatModel——即该用户 `user_llm_config` 已配置对应供应商（且 enabled=1）。前端做友好提示「该供应商未配置 API Key 时不可选」。后端执行时（阶段 03）若未配置会抛 `LlmNotConfiguredException`，执行期再捕获返回 error 事件。
4. 本阶段不做对话执行，纯管理 + 校验，独立可验证。