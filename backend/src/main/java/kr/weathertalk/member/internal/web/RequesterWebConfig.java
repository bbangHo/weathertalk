package kr.weathertalk.member.internal.web;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import kr.weathertalk.member.MemberApi;

@Configuration(proxyBeanMethods = false)
class RequesterWebConfig implements WebMvcConfigurer {

	private final MemberApi memberApi;

	RequesterWebConfig(MemberApi memberApi) {
		this.memberApi = memberApi;
	}

	@Override
	public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
		resolvers.add(new RequesterArgumentResolver(memberApi));
	}

}
