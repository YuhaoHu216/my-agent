package space.huyuhao.myagent.service;

import space.huyuhao.myagent.dto.ResponseResult;

import java.util.List;
import java.util.Map;

public interface ChatMemoryService {
    /**
     * 获取用户所有历史会话ID列表
     * @return 历史会话ID列表
     */
    ResponseResult<List<String>> getUserConversationIds();

    /**
     * 获取用户指定会话的历史消息
     * @param conversationId 会话ID
     * @return 消息列表
     */
    ResponseResult<Object> getConversationMessages(String conversationId);

    /**
     * 删除用户指定会话
     * @param conversationId 会话ID
     * @return 操作结果
     */
    ResponseResult<String> deleteConversation(String conversationId);

    /**
     * 获取用户所有会话的概要信息
     * @return 会话ID及其概要信息的映射
     */
    ResponseResult<Map<String, Object>> getAllConversationsSummary();

    /**
     * 更新会话名称
     * @param conversationId 会话ID
     * @param name 新的会话名称
     * @return 操作结果
     */
    ResponseResult<String> updateConversationName(String conversationId, String name);
}