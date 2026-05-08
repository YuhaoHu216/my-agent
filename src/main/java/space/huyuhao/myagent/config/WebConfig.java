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
        // 添加JWT拦截器，排除登录、注册和健康检查接口
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/user/login",
                                    "/user/register",
                                    "/health",
                                    "/error",
                                    "/milvus/health",
                                    // Swagger / Knife4j
                                    "/doc.html",
                                    "/swagger-ui/**",
                                    "/swagger-resources/**",
                                    "/v3/api-docs/**",
                                    "/webjars/**",
                                    "/favicon.ico"
                );
    }
}