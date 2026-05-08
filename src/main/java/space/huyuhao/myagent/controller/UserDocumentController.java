package space.huyuhao.myagent.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import space.huyuhao.myagent.dto.*;
import space.huyuhao.myagent.service.UserDocumentService;

import java.util.List;

@RestController
@RequestMapping("/document")
public class UserDocumentController {

    @Autowired
    private UserDocumentService userDocumentService;

    @PostMapping("/upload")
    @Operation(summary = "上传文档到知识库")
    public ResponseResult<DocumentUploadResultDto> upload(@RequestParam("file") MultipartFile file) {
        return userDocumentService.upload(file);
    }

    @GetMapping("/list")
    @Operation(summary = "获取我的文档列表")
    public ResponseResult<List<DocumentInfoDto>> list() {
        return userDocumentService.list();
    }

    @PostMapping("/search")
    @Operation(summary = "搜索我的文档")
    public ResponseResult<List<DocumentSearchResultDto>> search(@Valid @RequestBody DocumentSearchRequestDto request) {
        return userDocumentService.search(request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除文档")
    public ResponseResult<String> delete(@PathVariable Long id) {
        return userDocumentService.delete(id);
    }
}
