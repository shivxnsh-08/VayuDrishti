package com.saanscheck.stations;

import java.io.Serializable;

/** Serializable because nearest-station results are cached in Redis (JDK serialization). */
public record NearestStation(String id, String name, String city, String state,
                             double latitude, double longitude, double distanceMeters) implements Serializable {
}
