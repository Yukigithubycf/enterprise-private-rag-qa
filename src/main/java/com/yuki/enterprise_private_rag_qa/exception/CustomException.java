package com.yuki.enterprise_private_rag_qa.exception;

import org.springframework.http.HttpStatus;

public class CustomException  extends RuntimeException{
    private final HttpStatus status;

    public CustomException(String message, HttpStatus status) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
