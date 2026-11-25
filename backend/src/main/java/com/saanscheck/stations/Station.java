package com.saanscheck.stations;

import java.io.Serializable;

public record Station(String id, String name, String city, String state,
                      double latitude, double longitude, boolean active) implements Serializable {
}
