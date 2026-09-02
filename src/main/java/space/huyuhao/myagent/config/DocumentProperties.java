package space.huyuhao.myagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 文档上传 / 向量化规则配置。
 * 规定格式（如 md、txt）上传时分块向量化入知识库；其余扩展名文件仅存储到磁盘，作为文件中转站使用。
 */
@Configuration
@ConfigurationProperties(prefix = "my-agent.document")
@Data
public class DocumentProperties {

    /** 规定格式（扩展名小写、不带点） */
    private List<String> vectorizableExtensions = new ArrayList<>(List.of("md", "txt"));

    /** 归一化为带点小写的扩展名集合，如 {".md", ".txt"} */
    public Set<String> vectorizableExtensionSet() {
        Set<String> set = new HashSet<>();
        for (String ext : vectorizableExtensions) {
            String e = ext == null ? "" : ext.trim().toLowerCase();
            if (!e.isEmpty()) {
                set.add(e.startsWith(".") ? e : "." + e);
            }
        }
        return set;
    }
}
