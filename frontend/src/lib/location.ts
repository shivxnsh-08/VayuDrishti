/** Browser location helpers. Coordinates are never stored; they are rounded to ~1 km before they leave the page. */

export type LocationPermission = 'granted' | 'denied' | 'prompt' | 'unsupported';

export interface Coords {
  lat: number;
  lon: number;
}

/** Current permission state, WITHOUT prompting the user. */
export async function locationPermission(): Promise<LocationPermission> {
  if (typeof navigator === 'undefined' || !navigator.geolocation) return 'unsupported';
  try {
    const status = await navigator.permissions?.query({ name: 'geolocation' as PermissionName });
    return status ? (status.state as LocationPermission) : 'prompt';
  } catch {
    return 'prompt';   // Permissions API not available: we only know after asking
  }
}

export class LocationError extends Error {
  constructor(public kind: 'denied' | 'unavailable' | 'timeout', message: string) {
    super(message);
  }
}

/** One position fix (may show the browser's permission prompt if the state is "prompt"). Coarse accuracy only. */
export function currentPosition(): Promise<Coords> {
  return new Promise((resolve, reject) => {
    if (!navigator.geolocation) {
      reject(new LocationError('unavailable', 'Location is not available in this browser.'));
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (p) => resolve({ lat: p.coords.latitude, lon: p.coords.longitude }),
      (e) => reject(e.code === 1 ? new LocationError('denied', 'Location permission was denied.')
        : e.code === 3 ? new LocationError('timeout', 'Finding your location took too long.')
          : new LocationError('unavailable', 'Your location could not be determined.')),
      { enableHighAccuracy: false, timeout: 10_000, maximumAge: 5 * 60_000 },
    );
  });
}
