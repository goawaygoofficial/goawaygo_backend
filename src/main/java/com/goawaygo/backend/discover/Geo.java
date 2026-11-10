package com.goawaygo.backend.discover;

/** Distances on the Earth's surface. */
final class Geo {

	private static final double EARTH_RADIUS_KM = 6371;

	private Geo() {
	}

	/** Great-circle distance in kilometres. */
	static double km(double lat1, double lon1, double lat2, double lon2) {
		double dLat = Math.toRadians(lat2 - lat1);
		double dLon = Math.toRadians(lon2 - lon1);
		double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1))
				* Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
		return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(a));
	}

}
