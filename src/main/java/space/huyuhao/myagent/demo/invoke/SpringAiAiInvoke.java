package space.huyuhao.myagent.demo.invoke;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import jakarta.annotation.Resource;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

// 保留注解即可在 SpringBoot 项目启动时执行
//@Component
public class SpringAiAiInvoke implements CommandLineRunner {

    @Resource
    private ChatModel dashscopeChatModel;

    @Override
    public void run(String... args) throws Exception {
        AssistantMessage output = dashscopeChatModel.call(new Prompt("你好，我是逆蝶"))
                .getResult()
                .getOutput();
        System.out.println(output.getText());
    }
}

