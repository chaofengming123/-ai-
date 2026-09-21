package com.example.aiknowledge.controller;

import com.example.aiknowledge.dto.RegisterRequest;
import com.example.aiknowledge.model.UserResponse;
import com.example.aiknowledge.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final UserService service;
    public AuthController(UserService service) { this.service = service; }

    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.register(request.username(), request.password()));
    }
}
