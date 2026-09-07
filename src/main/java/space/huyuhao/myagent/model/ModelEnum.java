package space.huyuhao.myagent.model;

/**
 * 支持的模型枚举，用于前端 model 参数与后端 ChatModel 之间的路由。
 */
public enum ModelEnum {

    QWEN(Provider.DASHSCOPE, "qwen"),
    DEEPSEEK(Provider.DEEPSEEK, "deepseek");

    public enum Provider { DASHSCOPE, DEEPSEEK }

    private final Provider provider;
    private final String code;

    ModelEnum(Provider provider, String code) {
        this.provider = provider;
        this.code = code;
    }

    public Provider getProvider() {
        return provider;
    }

    public String getCode() {
        return code;
    }

    /**
     * 根据前端传入的 model code 解析枚举，未知值回退到 QWEN（通义千问默认）。
     */
    public static ModelEnum fromCode(String code) {
        if (code == null) {
            return QWEN;
        }
        for (ModelEnum model : values()) {
            if (model.code.equalsIgnoreCase(code)) {
                return model;
            }
        }
        return QWEN;
    }

    /**
     * 根据库中存储的供应商字符串（如 DASHSCOPE/DEEPSEEK）解析枚举，未知值回退 QWEN（与 fromCode 行为一致）。
     */
    public static ModelEnum fromProvider(String provider) {
        if (provider == null) {
            return QWEN;
        }
        String p = provider.trim().toUpperCase();
        if ("DASHSCOPE".equals(p) || "QWEN".equals(p)) {
            return QWEN;
        }
        if ("DEEPSEEK".equals(p)) {
            return DEEPSEEK;
        }
        return QWEN;
    }
}
