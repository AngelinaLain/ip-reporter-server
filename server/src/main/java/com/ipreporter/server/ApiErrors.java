package com.ipreporter.server;

import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> api(ApiException error) { return ResponseEntity.status(error.status()).body(Map.of("message",error.getMessage())); }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<?> validation(Exception error) { return ResponseEntity.badRequest().body(Map.of("message","请求内容不符合格式或字段限制")); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> conflict(Exception error) { return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message","记录被引用或 DNS 目标已存在，请检查关联规则")); }
    @ExceptionHandler(ConcurrencyFailureException.class)
    ResponseEntity<?> concurrentChange(Exception error) { return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message","相关记录正在更新，请刷新后重试")); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> internal(Exception error) {
        // 不把 SDK 异常、SQL 或凭据内容返回浏览器。
        return ResponseEntity.internalServerError().body(Map.of("message","服务暂时无法完成请求，请检查配置或稍后重试"));
    }
}
