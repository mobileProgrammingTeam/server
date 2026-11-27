package org.example.workersafety_server.service;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import tools.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;

@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ServiceCallLogger {
    private static final Logger log = LoggerFactory.getLogger(ServiceCallLogger.class);
    private final ObjectMapper json;

    public ServiceCallLogger(ObjectMapper json) { this.json = json; }

    @Around("execution(public * org.example.workersafety_server.service.SafetyService.*(..))")
    public Object logCall(ProceedingJoinPoint call) throws Throwable {
        try {
            Object result = call.proceed();
            write(call.getSignature().getName(), "SUCCESS");
            return result;
        } catch (Throwable error) {
            String code = error instanceof org.example.workersafety_server.exception.ApiException api ? api.code()
                    : error instanceof org.springframework.dao.DataAccessException data && org.example.workersafety_server.exception.DatabaseErrors.isBusy(data)
                    ? "DATABASE_BUSY" : "INTERNAL_ERROR";
            write(call.getSignature().getName(), code);
            throw error;
        }
    }

    private void write(String method, String outcome) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("requestId", org.slf4j.MDC.get("requestId"));
        entry.put("serviceMethod", "SafetyService." + method);
        entry.put("outcome", outcome);
        try { log.info("{}", json.writeValueAsString(entry)); }
        catch (Exception ex) { log.warn("Service log serialization failed"); }
    }
}
