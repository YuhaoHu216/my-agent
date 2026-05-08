package space.huyuhao.myagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import space.huyuhao.myagent.entity.UserDocument;

import java.util.List;

@Mapper
public interface UserDocumentMapper extends BaseMapper<UserDocument> {

    List<UserDocument> selectByUserId(Long userId);

    UserDocument selectByUserIdAndId(@Param("userId") Long userId, @Param("id") Long id);
}
