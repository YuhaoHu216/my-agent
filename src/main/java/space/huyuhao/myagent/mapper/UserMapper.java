package space.huyuhao.myagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import space.huyuhao.myagent.entity.User;

@Mapper
public interface UserMapper extends BaseMapper<User> {
    User findByUsername(String username);
}