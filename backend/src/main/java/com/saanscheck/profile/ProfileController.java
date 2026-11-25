package com.saanscheck.profile;

import com.saanscheck.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
public class ProfileController {

    private final ProfileService profiles;

    public ProfileController(ProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping("/profile")
    public ProfileView get(@AuthenticationPrincipal Jwt jwt) {
        return profiles.get(CurrentUser.id(jwt));
    }

    @PutMapping("/profile")
    public ProfileView put(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ProfileUpdateRequest body) {
        return profiles.update(CurrentUser.id(jwt), body);
    }

    /** Deletes the account and all personal data (spec section 18: deletion endpoint). */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt) {
        profiles.deleteAccount(CurrentUser.id(jwt));
    }
}
