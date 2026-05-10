package space.huyuhao.myagent.service;

import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import space.huyuhao.myagent.dto.*;

import java.util.List;

public interface UserDocumentService {

    ResponseResult<DocumentUploadResultDto> upload(MultipartFile file);

    ResponseResult<List<DocumentInfoDto>> list();

    ResponseResult<List<DocumentSearchResultDto>> search(DocumentSearchRequestDto request);

    ResponseResult<String> delete(Long documentId);

    ResponseEntity<?> download(Long documentId);
}
