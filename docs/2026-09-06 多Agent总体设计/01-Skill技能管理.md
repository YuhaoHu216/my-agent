# 阶段 01：Skill 技能管理

> 日期：2026-09-06
> 依赖：无。目标：让用户能管理自己的技能知识包（提示词包）。
> 前置阅读：`00-多Agent机制-总体设计.md`

## 1. 背景与目标

SKILL = **提示词知识包**。本质是一段领域指导语（做什么、遵循什么规范、输出格式等），不含可执行代码。它自成一条「技能记录」，后续在阶段 02 由 agent 绑定引用，绑定后在 agent 对话时**拼接进该 agent 的 systemPrompt**（多条 skill 按序拼接）。

本阶段只做 Skill 本身的 CRUD，**不涉及 agent**。

## 2. 数据模型

### 2.1 DDL（追加到 `resources/my_agent.sql`）

```sql
-- 用户技能表（提示词知识包）
DROP TABLE IF EXISTS user_skill;
CREATE TABLE user_skill (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    skill_name VARCHAR(100) NOT NULL COMMENT '技能名称(同一用户内唯一)',
    skill_content TEXT NOT NULL COMMENT '技能提示词内容(知识包)',
    enabled TINYINT DEFAULT 1 COMMENT '是否启用: 0-禁用, 1-启用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_user_skill_name (user_id, skill_name),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户技能表';
```

### 2.2 实体 `entity/UserSkill.java`

仿 `entity/UserMcpServer.java`：

```java
@Data
@TableName("user_skill")
public class UserSkill {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("user_id")
    private Long userId;
    @TableField("skill_name")
    private String skillName;
    @TableField("skill_content")
    private String skillContent;
    private Integer enabled;
    @TableField("create_time")
    private LocalDateTime createTime;
    @TableField("update_time")
    private LocalDateTime updateTime;
}
```

## 3. 后端实现（8 处配套）

### 3.1 Mapper `mapper/UserSkillMapper.java`

```java
@Mapper
public interface UserSkillMapper extends BaseMapper<UserSkill> {
}
```

单表 CRUD 用 BaseMapper 即可，无需 XML。（如需列表按名称模糊搜索，可再加 XML，但本阶段非必需。）

### 3.2 Service `service/UserSkillService.java`

方法论照抄 `service/UserMcpServerService.java`：

```java
public interface UserSkillService {
    List<UserSkill> list();
    void add(UserSkillRequest request);
    void update(UserSkillRequest request);
    void delete(Long id);
    void toggle(Long id);
}
```

`ServiceImpl` 要点（仿 `UserMcpServerServiceImpl`）：
- 构造器注入 `UserSkillMapper userSkillMapper`。
- 每个方法首行：`Long userId = UserContext.getUserId();`
- `add`：查重 `skillName`（同用户唯一键 uk_user_skill_name），冲突抛业务异常（现有异常处理风格请沿用 json 返回）。
- `update`：`selectByUserIdAndId`**先做归属校验**防越权，再 `updateById`（只更新非空字段即可，注意 skillContent 允许为空需特殊处理或用 request 全量字段）。
- `delete`：归属校验后 `deleteById`。
- `toggle`：只翻转 `enabled`。

> 说明：`list` 也可直接返回 entity（无敏感字段）。为统一风格仍建议加 `UserSkillDto`，内容与 entity 一致即可。

### 3.3 Controller `controller/SkillController.java`

```java
@Slf4j
@RestController
@RequestMapping("/skill")
public class SkillController {

    @Resource
    private UserSkillService userSkillService;

    /** 技能列表 */
    @GetMapping("/list")
    public ResponseResult<List<UserSkillDto>> list() { ... }

    /** 新增技能 */
    @PostMapping("/add")
    public ResponseResult<Void> add(@RequestBody UserSkillRequest request) { ... }

    /** 更新技能 */
    @PutMapping("/update")
    public ResponseResult<Void> update(@RequestBody UserSkillRequest request) { ... }

    /** 删除技能 */
    @DeleteMapping("/{id}")
    public ResponseResult<Void> delete(@PathVariable Long id) { ... }

    /** 启停技能 */
    @PutMapping("/toggle/{id}")
    public ResponseResult<Void> toggle(@PathVariable Long id) { ... }
}
```

返回信封统一用 `dto/ResponseResult`（已有）。接口路径参考 `user_mcp_server` 的 `/mcp/list` 风格。

### 3.4 DTO

- `dto/UserSkillRequest.java`：`skillName`（`@NotBlank(message="技能名称不能为空")`）、`skillContent`（`@NotBlank`）、`enabled`。
- `dto/UserSkillDto.java`：`id/skillName/skillContent/enabled/createTime/updateTime`（或直接返回 entity）。

## 4. 前端实现

### 4.1 API `src/api/skill.js`（照 `src/api/mcp.js` 样板）

```js
import request from '@/utils/request'

const skillApi = {
  list: () => request.get('/skill/list'),
  add: (data) => request.post('/skill/add', data),
  update: (data) => request.put('/skill/update', data),
  deleteById: (id) => request.delete(`/skill/${id}`),
  toggle: (id) => request.put(`/skill/toggle/${id}`)
}
export default skillApi
```

### 4.2 页面 `src/views/SkillManage.vue`

参考 `McpConfig.vue` 结构（`glass-page` 容器 + `page-toolbar` 标题 + 「新增技能」按钮 + `el-table` + `el-dialog` 表单）：

- 表格列：技能名称 / 启停 switch / 创建时间 / 操作（编辑、删除，删除用 `ElMessageBox.confirm`）。
- 弹窗表单：技能名称（`el-input`）、提示词内容（`el-input type="textarea"`，autosize）。

### 4.3 路由 + 导航

- `router/index.js`：`/` 的 children 加 `{ path: 'skills', name: 'Skills', component: () => import('@/views/SkillManage.vue'), meta: { title: '技能管理' } }`。
- `layouts/AppLayout.vue` 的 `main-nav` 加一项导航。

## 5. 验证

1. 编译通过（`mvn compile`）。
2. 前端跑起，新增一个技能（如「邮件撰写专家」内容填领域指导语），编辑、启停、删除均正常。
3. 数据库 `user_skill` 有对应记录；越权测试可选（另一用户看不到别人的 skill）。

## 6. 注意点

- skillContent 是长文本，DDL 用 `TEXT`；`update` 时若允许清空需区分「未传」与会话里的空串（可让前端编辑时回填原内容，后端全量覆盖即可）。
- 本阶段不把 skill 注入任何对话，只做数据层 + 管理界面，独立验证。