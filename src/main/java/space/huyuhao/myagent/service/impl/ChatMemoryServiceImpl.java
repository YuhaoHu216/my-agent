package space.huyuhao.myagent.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import space.huyuhao.myagent.chatmemory.RedisChatMemory;
import space.huyuhao.myagent.context.UserContext;
import space.huyuhao.myagent.dto.ResponseResult;
import space.huyuhao.myagent.service.ChatMemoryService;

import java.util.*;

@Service
public class ChatMemoryServiceImpl implements ChatMemoryService {

    @Autowired
    private RedisChatMemory redisChatMemory;

    @Override
    public ResponseResult<List<String>> getUserConversationIds() {
        try {
            List<String> conversationIds = redisChatMemory.getAllConversationIds();
            return ResponseResult.success(conversationIds);
        } catch (Exception e) {
            return ResponseResult.error("获取会话列表失败: " + e.getMessage());
        }
    }

    @Override
    public ResponseResult<Object> getConversationMessages(String conversationId) {
        try {
            List<RedisChatMemory.SlimMessage> messages = redisChatMemory.getConversationMessages(conversationId);
            return ResponseResult.success(messages);
        } catch (Exception e) {
            return ResponseResult.error("获取会话消息失败: " + e.getMessage());
        }
    }

    @Override
    public ResponseResult<String> deleteConversation(String conversationId) {
        try {
            boolean deleted = redisChatMemory.deleteConversation(conversationId);
            if (deleted) {
                return ResponseResult.success("删除成功");
            } else {
                return ResponseResult.success("会话不存在或删除失败");
            }
        } catch (Exception e) {
            return ResponseResult.error("删除会话失败: " + e.getMessage());
        }
    }

    @Override
    public ResponseResult<Map<String, Object>> getAllConversationsSummary() {
        try {
            List<RedisChatMemory.ConversationSummary> summaries = redisChatMemory.getAllConversationsSummary();
            
            Map<String, Object> conversationsSummary = new HashMap<>();
            for (RedisChatMemory.ConversationSummary summary : summaries) {
                Map<String, Object> summaryMap = new HashMap<>();
                summaryMap.put("messageCount", summary.messageCount());
                summaryMap.put("lastMessagePreview", summary.lastMessagePreview());
                summaryMap.put("lastMessageType", summary.lastMessageType());
                summaryMap.put("lastActivityTime", summary.lastActivityTime());
                
                conversationsSummary.put(summary.conversationId(), summaryMap);
            }

            return ResponseResult.success(conversationsSummary);
        } catch (Exception e) {
            return ResponseResult.error("获取会话摘要失败: " + e.getMessage());
        }
    }



}