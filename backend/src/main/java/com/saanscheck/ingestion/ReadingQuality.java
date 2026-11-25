package com.saanscheck.ingestion;

/**
 * Data-quality flag stored with every reading.
 * <ul>
 *   <li>OK: used for AQI.</li>
 *   <li>MISSING: source reported no value ("NA", blank).</li>
 *   <li>INVALID: unparseable, negative or above the plausibility limit.</li>
 *   <li>STUCK: identical value for {@code stuck-hours} consecutive readings (sensor likely frozen).</li>
 * </ul>
 * Only OK readings feed the AQI; all readings are stored so the data-quality report is honest.
 */
public enum ReadingQuality {
    OK, MISSING, INVALID, STUCK
}
