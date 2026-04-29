package space.huyuhao.myagent;

import cn.hutool.core.lang.UUID;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import space.huyuhao.myagent.app.MyApp;
import space.huyuhao.myagent.tool.FileOperationTool;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
public class ToolTests {

    @Resource
    private MyApp myApp;
    @Test
    public void testReadFile() {
        FileOperationTool tool = new FileOperationTool();
        String fileName = "示例.txt";
        String result = tool.readFile(fileName);
        System.out.println(result);
        assertNotNull(result);
    }

    @Test
    public void testWriteFile() {
        FileOperationTool tool = new FileOperationTool();
        String fileName = "示例.txt";
        String content = "我会继续学习弹奏";
        String result = tool.writeFile(fileName, content);
        System.out.println(result);
        assertNotNull(result);
    }

    @Test
    void doChatWithTools() {
        // 测试联网搜索问题的答案
//        testMessage("周末想带女朋友去上海约会，推荐几个适合情侣的小众打卡地？");

        // 测试网页抓取：恋爱案例分析
//        testMessage("最近和对象吵架了，看看编程导航网站（codefather.cn）的其他情侣是怎么解决矛盾的？");

        // 测试资源下载：图片下载
        testMessage("下载这张图片,https://i0.hdslb.com/bfs/static/jinkela/long/images/512.png");

        // 测试终端操作：执行代码
//        testMessage("执行 Python3 脚本来生成数据分析报告");

        // 测试文件操作：保存用户档案
        testMessage("把对话过程(指的是你和我的对话)保存为一个文档,文档最后写入当前时间");

        // 测试 PDF 生成
//        testMessage("生成一份‘七夕约会计划’PDF，包含餐厅预订、活动流程和礼物清单");
    }

    private void testMessage(String message) {
        String chatId = UUID.randomUUID().toString();
        String answer = myApp.doChatWithTools(message, chatId);
        Assertions.assertNotNull(answer);
    }

    @Test
    void doChatWithMcp() {
        String chatId = UUID.randomUUID().toString();
        // 测试地图mcp
        String message = "四川农业大学(雅安校区)周围有什么好玩的地方";
        String answer =  myApp.doChatWithMcp(message, chatId);
        Assertions.assertNotNull(answer);
    }


}
