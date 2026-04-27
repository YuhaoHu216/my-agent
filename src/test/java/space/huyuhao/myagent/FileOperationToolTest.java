package space.huyuhao.myagent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import space.huyuhao.myagent.tool.FileOperationTool;

import static org.junit.jupiter.api.Assertions.assertNotNull;

//@SpringBootTest
public class FileOperationToolTest {

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
}
