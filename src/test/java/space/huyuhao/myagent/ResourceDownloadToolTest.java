package space.huyuhao.myagent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import space.huyuhao.myagent.tool.ResourceDownloadTool;

import static org.junit.jupiter.api.Assertions.assertNotNull;

//@SpringBootTest
public class ResourceDownloadToolTest {

    @Test
    public void testDownloadResource() {
        ResourceDownloadTool tool = new ResourceDownloadTool();
        String url = "https://i2.hdslb.com/bfs/archive/87a5d03581e326f4f818cab3212ce471d1f6a064.png";
//        String url = "https://i0.hdslb.com/bfs/static/jinkela/long/images/512.png";
        String fileName = "bilibili.png";
        String result = tool.downloadResource(url, fileName);
        System.out.println(result);
        assertNotNull(result);
    }
}
