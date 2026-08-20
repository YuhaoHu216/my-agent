package space.huyuhao.myagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import space.huyuhao.myagent.entity.UserLlmConfig;

import java.util.List;

@Mapper
public interface UserLlmConfigMapper extends BaseMapper<UserLlmConfig> {

    List<UserLlmConfig> selectByUserId(Long userId);

    UserLlmConfig selectByUserIdAndProvider(@Param("userId") Long userId, @Param("provider") String provider);
}
