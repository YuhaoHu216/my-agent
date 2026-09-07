package space.huyuhao.myagent.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import space.huyuhao.myagent.dto.ResponseResult;
import space.huyuhao.myagent.dto.UserSkillDto;
import space.huyuhao.myagent.dto.UserSkillRequest;
import space.huyuhao.myagent.service.UserSkillService;

import java.util.List;

@RestController
@RequestMapping("/skill")
public class SkillController {

    @Autowired
    private UserSkillService userSkillService;

    @GetMapping("/list")
    @Operation(summary = "获取我的技能列表")
    public ResponseResult<List<UserSkillDto>> list() {
        return userSkillService.list();
    }

    @PostMapping("/add")
    @Operation(summary = "新增技能")
    public ResponseResult<String> add(@Valid @RequestBody UserSkillRequest request) {
        return userSkillService.add(request);
    }

    @PutMapping("/update")
    @Operation(summary = "修改技能")
    public ResponseResult<String> update(@Valid @RequestBody UserSkillRequest request) {
        return userSkillService.update(request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除技能")
    public ResponseResult<String> delete(@PathVariable Long id) {
        return userSkillService.delete(id);
    }

    @PutMapping("/toggle/{id}")
    @Operation(summary = "启停切换技能")
    public ResponseResult<String> toggle(@PathVariable Long id) {
        return userSkillService.toggle(id);
    }
}