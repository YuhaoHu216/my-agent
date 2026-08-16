package space.huyuhao.myagent;

import org.springframework.ai.autoconfigure.openai.OpenAiAutoConfiguration;
import org.springframework.ai.autoconfigure.vectorstore.pgvector.PgVectorStoreAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;


@SpringBootApplication(scanBasePackages = "space.huyuhao.myagent", exclude = {PgVectorStoreAutoConfiguration.class, OpenAiAutoConfiguration.class})
public class MyAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(MyAgentApplication.class, args);
    }

}