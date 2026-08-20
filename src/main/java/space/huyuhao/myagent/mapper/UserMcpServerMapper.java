package space.huyuhao.myagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import space.huyuhao.myagent.entity.UserMcpServer;

import java.util.List;

@Mapper
public interface UserMcpServerMapper extends BaseMapper<UserMcpServer> {

    List<UserMcpServer> selectByUserId(Long userId);

    List<UserMcpServer> selectEnabledByUserId(Long userId);

    UserMcpServer selectByUserIdAndId(@Param("userId") Long userId, @Param("id") Long id);

    UserMcpServer selectByUserIdAndName(@Param("userId") Long userId, @Param("serverName") String serverName);
}
