package org.example.workersafety_server.exception;

import org.example.workersafety_server.dto.ApiModels.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackages = "org.example.workersafety_server.controller.api")
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> api(ApiException ex) { return ResponseEntity.status(ex.status()).body(new ErrorResponse(ex.code(), ex.getMessage())); }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    ResponseEntity<ErrorResponse> invalid(Exception ex) { return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_REQUEST", "요청 내용을 확인하세요.")); }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ErrorResponse> database(DataAccessException ex) {
        if (DatabaseErrors.isBusy(ex)) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ErrorResponse("DATABASE_BUSY", "데이터베이스가 사용 중입니다."));
        log.error("Database operation failed", ex);
        return ResponseEntity.internalServerError().body(new ErrorResponse("INTERNAL_ERROR", "요청을 처리하지 못했습니다."));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception ex) {
        log.error("Request processing failed", ex);
        return ResponseEntity.internalServerError().body(new ErrorResponse("INTERNAL_ERROR", "요청을 처리하지 못했습니다."));
    }
}
