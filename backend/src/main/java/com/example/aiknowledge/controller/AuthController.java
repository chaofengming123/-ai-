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
    private final com.example.aiknowledge.service.LoginService loginService;
    public AuthController(UserService service, com.example.aiknowledge.service.LoginService loginService) {
        this.service = service;
        this.loginService = loginService;
    }

    @PostMapping("/login")
    public ResponseEntity<com.example.aiknowledge.model.LoginResponse> login(
            @RequestBody com.example.aiknowledge.dto.LoginRequest request) {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(loginService.login(request.username(), request.password()));
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            org.springframework.security.oauth2.jwt.Jwt jwt) {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(loginService.currentUser(jwt));
    }

    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.register(request.username(), request.password()));
    }
}
