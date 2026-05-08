package space.huyuhao.myagent.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import space.huyuhao.myagent.dto.ResponseResult;
import space.huyuhao.myagent.service.ChatMemoryService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chat-memory")
public class ChatMemoryController {

    @Autowired
    private ChatMemoryService chatMemoryService;

    /**
     * 获取用户所有历史会话ID列表
     */
    @GetMapping("/conversations")
    public ResponseResult<List<String>> getUserConversationIds() {
        return chatMemoryService.getUserConversationIds();
    }

    /**
     * 获取指定会话的消息记录
     */
    @GetMapping("/conversations/{conversationId}")
    public ResponseResult<Object> getConversationMessages(@PathVariable String conversationId) {
        return chatMemoryService.getConversationMessages(conversationId);
    }

    /**
     * 删除指定会话
     */
    @DeleteMapping("/conversations/{conversationId}")
    public ResponseResult<String> deleteConversation(@PathVariable String conversationId) {
        return chatMemoryService.deleteConversation(conversationId);
    }

    /**
     * 获取所有会话的摘要信息
     */
    @GetMapping("/conversations-summary")
    public ResponseResult<Map<String, Object>> getAllConversationsSummary() {
        return chatMemoryService.getAllConversationsSummary();
    }
}