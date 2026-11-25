package com.saanscheck.profile;

import com.saanscheck.alerts.AlertProperties;
import com.saanscheck.common.NotFoundException;
import com.saanscheck.stations.NearestStation;
import com.saanscheck.stations.StationRepository;
import com.saanscheck.security.UserRepository;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Profile rules:
 * <ul>
 *   <li>Coordinates are rounded before storage (privacy: coarse location only).</li>
 *   <li>If no station is chosen but coordinates are given, the nearest station is assigned.</li>
 *   <li>Quiet hours need both start and end.</li>
 * </ul>
 */
@Service
public class ProfileService {

    private final ProfileRepository profiles;
    private final StationRepository stations;
    private final UserRepository users;
    private final AlertProperties alertProps;
    private final int decimals;
    private final com.saanscheck.alerts.AlertStateRepository alertStates;

    public ProfileService(ProfileRepository profiles, StationRepository stations, UserRepository users,
                          AlertProperties alertProps,
                          @Value("${saanscheck.profile.coordinate-decimals:2}") int decimals,
                          com.saanscheck.alerts.AlertStateRepository alertStates) {
        this.alertStates = alertStates;
        this.profiles = profiles;
        this.stations = stations;
        this.users = users;
        this.alertProps = alertProps;
        this.decimals = decimals;
    }

    public ProfileView get(long userId) {
        return profiles.find(userId).map(this::toView)
                .orElseThrow(() -> new NotFoundException("Profile not set yet; PUT /api/v1/me/profile first"));
    }

    @Transactional
    public ProfileView update(long userId, ProfileUpdateRequest req) {
        if ((req.quietStart() == null) != (req.quietEnd() == null)) {
            throw new IllegalArgumentException("quietStart and quietEnd must be given together");
        }
        if ((req.latitude() == null) != (req.longitude() == null)) {
            throw new IllegalArgumentException("latitude and longitude must be given together");
        }
        Double lat = req.latitude() == null ? null : round(req.latitude());
        Double lon = req.longitude() == null ? null : round(req.longitude());

        String stationId = req.stationId();
        if (stationId != null && !stations.exists(stationId)) {
            throw new IllegalArgumentException("Unknown station: " + stationId);
        }
        if (stationId == null && lat != null) {
            List<NearestStation> nearest = stations.findNearest(lat, lon, 1);
            stationId = nearest.isEmpty() ? null : nearest.getFirst().id();
        }
        Profile existing = profiles.find(userId).orElse(null);
        Profile p = new Profile(userId, req.healthProfile(), lat, lon, req.homeCity(), stationId,
                req.language() == null ? "en" : req.language(), req.alertMinCategory(),
                req.quietStart(), req.quietEnd(), Boolean.TRUE.equals(req.severeOverridesQuiet()),
                existing == null ? null : existing.telegramChatId());
        profiles.upsert(p);
        if (req.aqiAlertsEnabled() != null) {
            profiles.setAqiAlertsEnabled(userId, req.aqiAlertsEnabled());
            if (!req.aqiAlertsEnabled()) {
                alertStates.delete(userId);   // switching on again starts from a fresh baseline
            }
        }
        if (req.morningBriefingEnabled() != null || req.morningBriefingTime() != null) {
            profiles.setBriefingSettings(userId, req.morningBriefingEnabled(),
                    req.morningBriefingTime() == null ? null : req.morningBriefingTime().withSecond(0).withNano(0));
        }
        return toView(p);
    }

    /** Right to deletion: cascades to profile, alerts and link codes via foreign keys. */
    @Transactional
    public void deleteAccount(long userId) {
        users.deleteById(userId);
    }

    double round(double v) {
        double f = Math.pow(10, decimals);
        return Math.round(v * f) / f;
    }

    private ProfileView toView(Profile p) {
        ProfileRepository.BriefingSettings briefing = profiles.briefingSettings(p.userId());
        return new ProfileView(p.healthProfile(), p.latitude(), p.longitude(), p.homeCity(), p.stationId(),
                p.language(), p.alertMinCategory(), alertProps.minCategoryFor(p.healthProfile(), p.alertMinCategory()),
                p.quietStart(), p.quietEnd(), p.severeOverridesQuiet(), p.telegramChatId() != null,
                briefing.enabled(), briefing.time(), profiles.aqiAlertsEnabled(p.userId()));
    }
}
