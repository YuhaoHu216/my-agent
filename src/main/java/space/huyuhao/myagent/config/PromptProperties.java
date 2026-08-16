package space.huyuhao.myagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "my-agent.prompt")
@Data
public class PromptProperties {

    private Agent agent = new Agent();
    private App app = new App();
    private Rag rag = new Rag();

    @Data
    public static class Agent {
        private String system;
        private String nextStep;
    }

    @Data
    public static class App {
        private String system;
        private String reportSuffix;
    }

    @Data
    public static class Rag {
        private String prefix;
        private String suffix;
        private String separator;
    }
}
