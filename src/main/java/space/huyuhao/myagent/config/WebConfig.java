package space.huyuhao.myagent.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import space.huyuhao.myagent.interceptor.JwtInterceptor;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private JwtInterceptor jwtInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 添加JWT拦截器，排除登录和注册接口
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/api/**")  // 所有API接口都需要验证
                .excludePathPatterns("/api/user/login")    // 登录接口不需要验证
                .excludePathPatterns("/api/user/register"); // 注册接口不需要验证
    }
}