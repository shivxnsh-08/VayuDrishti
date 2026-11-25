package com.saanscheck.alerts;

import com.saanscheck.security.CurrentUser;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/alerts")
@Validated
public class AlertController {

    private final AlertRepository alerts;

    public AlertController(AlertRepository alerts) {
        this.alerts = alerts;
    }

    @GetMapping
    public List<AlertRepository.AlertView> list(@AuthenticationPrincipal Jwt jwt,
                                                @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {
        return alerts.findByUser(CurrentUser.id(jwt), limit);
    }
}
