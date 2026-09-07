package space.huyuhao.myagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import space.huyuhao.myagent.entity.UserAgent;

import java.util.List;

@Mapper
public interface UserAgentMapper extends BaseMapper<UserAgent> {

    List<UserAgent> selectByUserId(Long userId);

    List<UserAgent> selectEnabledByUserId(Long userId);

    UserAgent selectByUserIdAndId(@Param("userId") Long userId, @Param("id") Long id);

    UserAgent selectByUserIdAndName(@Param("userId") Long userId, @Param("agentName") String agentName);
}