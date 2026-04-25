package com.bunq.javabackend.controller;

import com.bunq.javabackend.dto.response.AuthCheckResponseDTO;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    @PostMapping("/check")
    public ResponseEntity<AuthCheckResponseDTO> check() {
        return ResponseEntity.ok(new AuthCheckResponseDTO(true));
    }
}
