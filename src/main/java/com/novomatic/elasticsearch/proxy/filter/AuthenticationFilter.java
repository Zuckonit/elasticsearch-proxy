package com.novomatic.elasticsearch.proxy.filter;

import com.netflix.zuul.context.RequestContext;
import com.novomatic.elasticsearch.proxy.ElasticsearchRequest;
import com.novomatic.elasticsearch.proxy.Principal;
import com.novomatic.elasticsearch.proxy.UnauthorizedException;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.adapters.AdapterDeploymentContext;
import org.keycloak.adapters.BearerTokenRequestAuthenticator;
import org.keycloak.adapters.KeycloakDeployment;
import org.keycloak.adapters.spi.AuthOutcome;
import org.keycloak.adapters.spi.HttpFacade;
import org.keycloak.adapters.springsecurity.facade.SimpleHttpFacade;
import org.springframework.http.HttpStatus;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StreamUtils;

import javax.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.List;

import static org.springframework.cloud.netflix.zuul.filters.support.FilterConstants.REQUEST_ENTITY_KEY;

@Slf4j
public class AuthenticationFilter extends ElasticsearchApiFilter {

    private final AdapterDeploymentContext adapterDeploymentContext;

    public AuthenticationFilter(AdapterDeploymentContext adapterDeploymentContext) {
        this.adapterDeploymentContext = adapterDeploymentContext;
    }

    @Override
    public int filterOrder() {
        return Constants.AUTHENTICATION_FILTER_ORDER;
    }

    @Override
    public Object run() {
        RequestContext currentContext = RequestContext.getCurrentContext();
        HttpServletRequest request = currentContext.getRequest();
        logRequest(currentContext);
        SimpleHttpFacade httpFacade = new SimpleHttpFacade(currentContext.getRequest(), currentContext.getResponse());
        KeycloakDeployment keycloakDeployment = adapterDeploymentContext.resolveDeployment(httpFacade);
        BearerTokenRequestAuthenticator authenticator = new BearerTokenRequestAuthenticator(keycloakDeployment);
        AuthOutcome outcome = authenticator.authenticate(httpFacade);
        switch (outcome) {
            case AUTHENTICATED:
                Principal principal = new Principal(authenticator.getToken());
                ElasticsearchRequest elasticsearchRequest = new ElasticsearchRequest(currentContext.getRequest(), principal);
                currentContext.setRequest(elasticsearchRequest);
                break;
            case NOT_ATTEMPTED:
                if (usesOtherAuthorization(httpFacade)) {
                    if (shouldLog(currentContext)) {
                        log.debug("The {} {} request uses authorization other than bearer token. Request will be passed straight to upstream Elasticsearch", request.getMethod(), request.getRequestURI());
                    }
                    setPassThrough(true);
                } else {
                    log.info("The authentication for the {} {} was not attempted.", request.getMethod(), request.getRequestURI(), authenticator.getChallenge());
                    RequestContextExtensions.respondWith(currentContext, HttpStatus.UNAUTHORIZED);
                }
                break;
            default:
                log.info("Authorization failed. Invalid bearer token for the {} {} request.", request.getMethod(), request.getRequestURI(), authenticator.getChallenge());
                throw new UnauthorizedException("The user is unauthorized to perform this operation. Invalid bearer token.");
        }
        return null;
    }

    private static boolean usesOtherAuthorization(HttpFacade httpFacade) {
        List<String> authorization = httpFacade.getRequest().getHeaders("Authorization");
        return authorization != null && authorization.size() > 0;
    }

    private boolean shouldLog(RequestContext context) {
        String requestUri = context.getRequest().getRequestURI();
        return !requestUri.startsWith("/_nodes") && !requestUri.startsWith("/.kibana/_mapping") && !requestUri.equals("/");
    }

    private void logRequest(RequestContext context) {
        if (!log.isTraceEnabled()) {
            return;
        }
        if (!shouldLog(context)) {
            return;
        }
        log.trace("A request has been received:\n" + getRequestContent(context));
    }

    private String getRequestContent(RequestContext context) {
        HttpServletRequest request = context.getRequest();
        StringBuilder sb = new StringBuilder();
        sb.append(request.getMethod().toUpperCase());
        sb.append(" ");
        sb.append(request.getRequestURL());
        if (request.getQueryString() != null) {
            sb.append("?");
            sb.append(request.getQueryString());
        }
        sb.append(" HTTP/1.1\n");
        List<String> headerNames = Collections.list(request.getHeaderNames());
        for (String headerName : headerNames) {
            sb.append(headerName);
            sb.append(": ");
            sb.append(request.getHeader(headerName));
            sb.append("\n");
        }
        sb.append("\n");
        try {
            sb.append(readRequestBody(context));
        } catch (IOException e) {
            ReflectionUtils.rethrowRuntimeException(e);
        }
        return sb.toString();
    }

    private String readRequestBody(RequestContext context) throws IOException {
        InputStream in = (InputStream) context.get(REQUEST_ENTITY_KEY);
        if (in == null) {
            in = context.getRequest().getInputStream();
        }
        String body = StreamUtils.copyToString(in, Charset.forName("UTF-8"));
        context.set(REQUEST_ENTITY_KEY, new ByteArrayInputStream(body.getBytes("UTF-8")));
        return body;
    }
}
