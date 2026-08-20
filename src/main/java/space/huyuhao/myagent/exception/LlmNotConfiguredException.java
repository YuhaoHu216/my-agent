package space.huyuhao.myagent.exception;

/**
 * 用户未配置 LLM 模型时抛出，携带可直接展示给用户的提示文案。
 */
public class LlmNotConfiguredException extends RuntimeException {

    public LlmNotConfiguredException(String message) {
        super(message);
    }
}
