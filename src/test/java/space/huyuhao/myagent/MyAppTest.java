package space.huyuhao.myagent;

import cn.hutool.core.lang.UUID;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import space.huyuhao.myagent.app.MyApp;

@SpringBootTest
class MyAppTest {

    @Resource
    private MyApp myApp;

    @Test
    void testChat() {
        String chatId = UUID.randomUUID().toString();
        // 第一轮
        String message = "总结一下我的高中经历";
        String answer = myApp.doChat(message, chatId);
        Assertions.assertNotNull(answer);
//        // 第二轮
//        message = "找不到对象怎么办";
//        answer = myApp.doChat(message, chatId);
//        Assertions.assertNotNull(answer);
//        // 第三轮
//        message = "我上一个问题是什么";
//        answer = myApp.doChat(message, chatId);
//        Assertions.assertNotNull(answer);
    }

    @Test
    void doChatWithReport() {
        String chatId = UUID.randomUUID().toString();
        // 第一轮
//        String message = "我的出生日期,星座和mbti是什么";
        String message = "总结一下我的高中经历";
        MyApp.MyReport myReport = myApp.doChatWithReport(message, chatId);
        Assertions.assertNotNull(myReport);
    }

}

