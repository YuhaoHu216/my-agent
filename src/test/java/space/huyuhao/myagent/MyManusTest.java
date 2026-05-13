package space.huyuhao.myagent;

import org.junit.jupiter.api.Assertions;
import space.huyuhao.myagent.agent.MyAgent;

//@SpringBootTest
class MyManusTest {

//    @Resource
    private MyAgent myAgent;

//    @Test
    void run() {
//        String userPrompt = """
//                我的外地朋友要来找我玩,我在雅安市雨城区四川农业大学，请帮我找到 3 公里内合适的地点，
//                制定一份详细的计划，
//                将内容保存到文件中""";
        String userPrompt = """
                总结一下我这个人,最后生成一个总结文件""";
        String answer = myAgent.run(userPrompt);
        Assertions.assertNotNull(answer);
    }
}

