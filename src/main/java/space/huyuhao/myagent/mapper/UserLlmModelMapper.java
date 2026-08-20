package space.huyuhao.myagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import space.huyuhao.myagent.entity.UserLlmModel;

import java.util.List;

@Mapper
public interface UserLlmModelMapper extends BaseMapper<UserLlmModel> {

    List<UserLlmModel> selectByUserId(Long userId);

    List<UserLlmModel> selectByUserIdAndProvider(@Param("userId") Long userId, @Param("provider") String provider);

    int deleteByUserIdAndProvider(@Param("userId") Long userId, @Param("provider") String provider);
}
