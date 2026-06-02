package net.irisshaders.iris.ambience;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AmbienceRegion {
	private static final int REGION_MATCH_PADDING_BLOCKS = 10;

	public String id = "";
	public String profile = "";
	public String dimension = "";
	public int priority = 0;
	public Shape shape = new Shape();

	public boolean matches(String currentDimension, double x, double y, double z) {
		if (dimension != null && !dimension.isBlank() && !dimension.equals(currentDimension)) {
			return false;
		}

		return shape != null && shape.matches(x, y, z, REGION_MATCH_PADDING_BLOCKS);
	}

	public static class Shape {
		public String type = "box";
		public double[] min;
		public double[] max;
		public double[] center;
		public double radius = 0;
		public List<Point> points;

		public boolean matches(double x, double y, double z, double boundaryPaddingBlocks) {
			String shapeType = type == null ? "box" : type.toLowerCase(Locale.ROOT);
			if ("sphere".equals(shapeType)) {
				if (center == null || center.length < 2 || radius <= 0) {
					return false;
				}
				double dx = x - center[0];
				double dz = z - center[1];
				double effectiveRadius = radius + boundaryPaddingBlocks;
				return dx * dx + dz * dz <= effectiveRadius * effectiveRadius;
			}

			if ("polygon".equals(shapeType)) {
				return matchesPolygon(x, z, boundaryPaddingBlocks);
			}

			if (min == null || max == null || min.length < 2 || max.length < 2) {
				return false;
			}

			double minX = Math.min(min[0], max[0]);
			double minZ = Math.min(min[1], max[1]);
			double maxX = Math.max(min[0], max[0]);
			double maxZ = Math.max(min[1], max[1]);

			return x >= minX - boundaryPaddingBlocks && x <= maxX + boundaryPaddingBlocks
				&& z >= minZ - boundaryPaddingBlocks && z <= maxZ + boundaryPaddingBlocks;
		}

		private boolean matchesPolygon(double x, double z, double boundaryPaddingBlocks) {
			if (points == null || points.size() < 3) {
				return false;
			}

			double[] bounds = polygonBounds();
			if (bounds == null) {
				return false;
			}
			double minX = bounds[0];
			double minZ = bounds[1];
			double maxX = bounds[2];
			double maxZ = bounds[3];
			if (x < minX - boundaryPaddingBlocks || x > maxX + boundaryPaddingBlocks || z < minZ - boundaryPaddingBlocks || z > maxZ + boundaryPaddingBlocks) {
				return false;
			}

			if (containsPoint(x, z)) {
				return true;
			}
			if (boundaryPaddingBlocks > 0) {
				double paddingSquared = boundaryPaddingBlocks * boundaryPaddingBlocks;
				for (int i = 0, j = points.size() - 1; i < points.size(); j = i++) {
					Point a = points.get(i);
					Point b = points.get(j);
					if (a != null && b != null && distanceToSegmentSquared(x, z, a, b) <= paddingSquared) {
						return true;
					}
				}
			}

			return false;
		}

		private double[] polygonBounds() {
			if (min != null && max != null && min.length >= 2 && max.length >= 2) {
				return new double[] {
					Math.min(min[0], max[0]),
					Math.min(min[1], max[1]),
					Math.max(min[0], max[0]),
					Math.max(min[1], max[1])
				};
			}

			double minX = Double.POSITIVE_INFINITY;
			double minZ = Double.POSITIVE_INFINITY;
			double maxX = Double.NEGATIVE_INFINITY;
			double maxZ = Double.NEGATIVE_INFINITY;
			for (Point point : points) {
				if (point == null) {
					continue;
				}
				minX = Math.min(minX, point.x);
				minZ = Math.min(minZ, point.z);
				maxX = Math.max(maxX, point.x);
				maxZ = Math.max(maxZ, point.z);
			}
			if (!Double.isFinite(minX) || !Double.isFinite(minZ) || !Double.isFinite(maxX) || !Double.isFinite(maxZ)) {
				return null;
			}
			return new double[] {minX, minZ, maxX, maxZ};
		}

		private boolean containsPoint(double x, double z) {
			boolean inside = false;
			int count = points.size();
			for (int i = 0, j = count - 1; i < count; j = i++) {
				Point a = points.get(i);
				Point b = points.get(j);
				if (a == null || b == null) {
					continue;
				}
				boolean crosses = (a.z > z) != (b.z > z);
				if (crosses) {
					double xAtZ = (b.x - a.x) * (z - a.z) / (b.z - a.z) + a.x;
					if (x < xAtZ) {
						inside = !inside;
					}
				}
			}
			return inside;
		}

		private double distanceToSegmentSquared(double x, double z, Point a, Point b) {
			double dx = b.x - a.x;
			double dz = b.z - a.z;
			double lengthSquared = dx * dx + dz * dz;
			if (lengthSquared == 0) {
				double pointDx = x - a.x;
				double pointDz = z - a.z;
				return pointDx * pointDx + pointDz * pointDz;
			}

			double t = ((x - a.x) * dx + (z - a.z) * dz) / lengthSquared;
			t = Math.max(0.0, Math.min(1.0, t));
			double projectedX = a.x + t * dx;
			double projectedZ = a.z + t * dz;
			double pointDx = x - projectedX;
			double pointDz = z - projectedZ;
			return pointDx * pointDx + pointDz * pointDz;
		}

		public Shape copy() {
			Shape copy = new Shape();
			copy.type = type;
			copy.min = min == null ? null : min.clone();
			copy.max = max == null ? null : max.clone();
			copy.center = center == null ? null : center.clone();
			copy.radius = radius;
			if (points != null) {
				copy.points = new ArrayList<>(points.size());
				for (Point point : points) {
					copy.points.add(point == null ? null : point.copy());
				}
			}
			return copy;
		}
	}

	public AmbienceRegion copy() {
		AmbienceRegion copy = new AmbienceRegion();
		copy.id = id;
		copy.profile = profile;
		copy.dimension = dimension;
		copy.priority = priority;
		copy.shape = shape == null ? null : shape.copy();
		return copy;
	}

	public static class Point {
		public double x;
		public double z;

		public Point() {
		}

		public Point(double x, double z) {
			this.x = x;
			this.z = z;
		}

		public Point copy() {
			return new Point(x, z);
		}
	}
}
