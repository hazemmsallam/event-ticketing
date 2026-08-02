package com.eventticketing.common.security;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolves {@link CurrentCustomer @CurrentCustomer} parameters from the {@code Authorization}
 * header, so a handler can never be written against a client-supplied identity by accident.
 */
@Component
public class CurrentCustomerArgumentResolver implements HandlerMethodArgumentResolver {

    private final TokenAuthenticator authenticator;

    public CurrentCustomerArgumentResolver(TokenAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentCustomer.class)
                && CustomerId.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                  NativeWebRequest request, WebDataBinderFactory binderFactory) {
        return authenticator.authenticate(request.getHeader(HttpHeaders.AUTHORIZATION));
    }
}
