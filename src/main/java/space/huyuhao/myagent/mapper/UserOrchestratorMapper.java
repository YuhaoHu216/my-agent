package space.huyuhao.myagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import space.huyuhao.myagent.entity.UserOrchestrator;

import java.util.List;

@Mapper
public interface UserOrchestratorMapper extends BaseMapper<UserOrchestrator> {

    List<UserOrchestrator> selectByUserId(Long userId);

    UserOrchestrator selectByUserIdAndId(@Param("userId") Long userId, @Param("id") Long id);

    UserOrchestrator selectByUserIdAndName(@Param("userId") Long userId, @Param("orchestratorName") String orchestratorName);
}