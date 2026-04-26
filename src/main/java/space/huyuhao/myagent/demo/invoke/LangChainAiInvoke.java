//package space.huyuhao.myagent.demo.invoke;
//
//import dev.langchain4j.community.model.dashscope.QwenChatModel;
//import dev.langchain4j.model.chat.ChatLanguageModel;
//
//public class LangChainAiInvoke {
//
//    public static void main(String[] args) {
//        String apiKey = System.getenv("ALI_API_KEY");
//        ChatLanguageModel qwenModel = QwenChatModel.builder()
//                .apiKey(apiKey)
//                .modelName("qwen-max")
//                .build();
//        String answer = qwenModel.chat("我用的是晨光的笔,你们都是啥笔.");
//        System.out.println(answer);
//    }
//}
