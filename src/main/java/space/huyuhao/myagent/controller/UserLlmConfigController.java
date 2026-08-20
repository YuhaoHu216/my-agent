package space.huyuhao.myagent.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import space.huyuhao.myagent.dto.ResponseResult;
import space.huyuhao.myagent.dto.UserLlmConfigDto;
import space.huyuhao.myagent.dto.UserLlmConfigRequest;
import space.huyuhao.myagent.service.UserLlmConfigService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/llm-config")
public class UserLlmConfigController {

    @Autowired
    private UserLlmConfigService userLlmConfigService;

    @GetMapping("/list")
    @Operation(summary = "获取我的 LLM 配置列表（apiKey 脱敏）")
    public ResponseResult<List<UserLlmConfigDto>> list() {
        return userLlmConfigService.list();
    }

    @PostMapping("/save")
    @Operation(summary = "新增/修改 LLM 配置（编辑时 apiKey 留空表示保留旧值）")
    public ResponseResult<String> save(@Valid @RequestBody UserLlmConfigRequest request) {
        return userLlmConfigService.save(request);
    }

    @DeleteMapping("/{provider}")
    @Operation(summary = "删除指定提供商配置")
    public ResponseResult<String> delete(@PathVariable String provider) {
        return userLlmConfigService.delete(provider);
    }

    @PostMapping("/test")
    @Operation(summary = "测试连接（验证 apiKey + 模型名）")
    public ResponseResult<String> test(@RequestBody UserLlmConfigRequest request) {
        return userLlmConfigService.test(request);
    }

    @GetMapping("/presets")
    @Operation(summary = "获取预置模型名列表")
    public ResponseResult<Map<String, List<String>>> presets() {
        return userLlmConfigService.presets();
    }
}
