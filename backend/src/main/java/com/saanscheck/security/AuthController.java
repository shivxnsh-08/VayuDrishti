package com.saanscheck.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthService.TokenResponse register(@Valid @RequestBody Credentials body) {
        return auth.register(body.email(), body.password());
    }

    @PostMapping("/login")
    public AuthService.TokenResponse login(@Valid @RequestBody Credentials body) {
        return auth.login(body.email(), body.password());
    }

    /** BCrypt only uses the first 72 bytes, hence the upper bound. */
    public record Credentials(@NotBlank @Email @Size(max = 255) String email,
                              @NotBlank @Size(min = 8, max = 72) String password) {
    }
}
