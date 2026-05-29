package net.irisshaders.iris.ambience;

import java.util.Locale;

public class AmbienceRegion {
	public String id = "";
	public String profile = "";
	public String dimension = "";
	public int priority = 0;
	public Shape shape = new Shape();
	public int falloffBlocks = 0;

	public boolean matches(String currentDimension, double x, double y, double z) {
		if (dimension != null && !dimension.isBlank() && !dimension.equals(currentDimension)) {
			return false;
		}

		return shape != null && shape.matches(x, y, z, Math.max(0, falloffBlocks));
	}

	public static class Shape {
		public String type = "box";
		public double[] min = new double[] {0, 0, 0};
		public double[] max = new double[] {0, 0, 0};
		public double[] center = new double[] {0, 0, 0};
		public double radius = 0;

		public boolean matches(double x, double y, double z, double falloffBlocks) {
			String shapeType = type == null ? "box" : type.toLowerCase(Locale.ROOT);
			if ("sphere".equals(shapeType)) {
				if (center == null || center.length < 3 || radius <= 0) {
					return false;
				}
				double dx = x - center[0];
				double dy = y - center[1];
				double dz = z - center[2];
				double effectiveRadius = radius + falloffBlocks;
				return dx * dx + dy * dy + dz * dz <= effectiveRadius * effectiveRadius;
			}

			if (min == null || max == null || min.length < 3 || max.length < 3) {
				return false;
			}

			double minX = Math.min(min[0], max[0]);
			double minY = Math.min(min[1], max[1]);
			double minZ = Math.min(min[2], max[2]);
			double maxX = Math.max(min[0], max[0]);
			double maxY = Math.max(min[1], max[1]);
			double maxZ = Math.max(min[2], max[2]);

			return x >= minX - falloffBlocks && x <= maxX + falloffBlocks
				&& y >= minY - falloffBlocks && y <= maxY + falloffBlocks
				&& z >= minZ - falloffBlocks && z <= maxZ + falloffBlocks;
		}
	}
}
