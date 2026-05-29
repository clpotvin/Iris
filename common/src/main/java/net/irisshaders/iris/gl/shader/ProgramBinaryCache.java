package net.irisshaders.iris.gl.shader;

import com.mojang.blaze3d.opengl.GlStateManager;
import net.irisshaders.iris.gui.option.WynncraftDebugLog;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ARBGetProgramBinary;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL41C;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;

public final class ProgramBinaryCache {
	private static final int MAX_ENTRIES = 512;
	private static final long MAX_BYTES = 64L * 1024L * 1024L;
	private static final Map<String, CachedBinary> CACHE = new LinkedHashMap<>(128, 0.75f, true);
	private static Boolean supported;
	private static boolean useArb;
	private static int binaryFormatCount;
	private static long totalBytes;

	private ProgramBinaryCache() {
	}

	public static String key(String category, String name, String... parts) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			updateDigest(digest, category);
			updateDigest(digest, name);
			for (String part : parts) {
				updateDigest(digest, part);
			}
			return category + ":" + name + ":" + HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is required", e);
		}
	}

	private static void updateDigest(MessageDigest digest, String value) {
		if (value != null) {
			digest.update(value.getBytes(StandardCharsets.UTF_8));
		}
		digest.update((byte) 0);
	}

	public static OptionalInt tryCreateProgram(String key, String name) {
		if (!isSupported()) {
			return OptionalInt.empty();
		}

		CachedBinary cached = CACHE.get(key);
		if (cached == null) {
			return OptionalInt.empty();
		}

		int program = GlStateManager.glCreateProgram();
		ByteBuffer binary = cached.binary.asReadOnlyBuffer();
		binary.position(0);
		programBinary(program, cached.format, binary);

		int status = GlStateManager.glGetProgrami(program, GL20C.GL_LINK_STATUS);
		if (status != GL20C.GL_TRUE) {
			GlStateManager.glDeleteProgram(program);
			remove(key);
			WynncraftDebugLog.info("ambience-program-binary-rejected",
				"Rejected cached program binary {} ({})", name, key);
			return OptionalInt.empty();
		}

		WynncraftDebugLog.info("ambience-program-binary-hit",
			"Loaded cached program binary {} ({} bytes)", name, cached.binary.capacity());
		return OptionalInt.of(program);
	}

	public static void markRetrievable(int program) {
		if (isSupported()) {
			programParameteri(program, GL_PROGRAM_BINARY_RETRIEVABLE_HINT(), GL11C.GL_TRUE);
		}
	}

	public static void store(String key, String name, int program) {
		if (!isSupported() || CACHE.containsKey(key)) {
			return;
		}
		int status = GlStateManager.glGetProgrami(program, GL20C.GL_LINK_STATUS);
		if (status != GL20C.GL_TRUE) {
			return;
		}

		int length = GlStateManager.glGetProgrami(program, GL_PROGRAM_BINARY_LENGTH());
		if (length <= 0) {
			return;
		}

		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer lengthBuffer = stack.mallocInt(1);
			IntBuffer formatBuffer = stack.mallocInt(1);
			ByteBuffer binary = BufferUtils.createByteBuffer(length);
			getProgramBinary(program, lengthBuffer, formatBuffer, binary);

			int actualLength = lengthBuffer.get(0);
			if (actualLength <= 0) {
				return;
			}
			binary.limit(actualLength);
			binary.position(0);

			ByteBuffer stored = BufferUtils.createByteBuffer(actualLength);
			stored.put(binary);
			stored.flip();

			CachedBinary cached = new CachedBinary(formatBuffer.get(0), stored);
			CACHE.put(key, cached);
			totalBytes += actualLength;
			enforceBudget();
			WynncraftDebugLog.info("ambience-program-binary-store",
				"Stored program binary {} ({} bytes, {} entries, {} bytes total)",
				name, actualLength, CACHE.size(), totalBytes);
		}
	}

	public static int getEntryCount() {
		return CACHE.size();
	}

	public static long getTotalBytes() {
		return totalBytes;
	}

	public static boolean isAvailable() {
		return isSupported();
	}

	public static int getBinaryFormatCount() {
		isSupported();
		return binaryFormatCount;
	}

	private static void remove(String key) {
		CachedBinary removed = CACHE.remove(key);
		if (removed != null) {
			totalBytes -= removed.binary.capacity();
		}
	}

	private static void enforceBudget() {
		while ((CACHE.size() > MAX_ENTRIES || totalBytes > MAX_BYTES) && !CACHE.isEmpty()) {
			String eldest = CACHE.keySet().iterator().next();
			remove(eldest);
		}
	}

	private static boolean isSupported() {
		if (supported != null) {
			return supported;
		}
		if (GL.getCapabilities() == null) {
			supported = false;
			binaryFormatCount = 0;
			logSupport("none");
			return false;
		}

		useArb = !GL.getCapabilities().OpenGL41 && GL.getCapabilities().GL_ARB_get_program_binary;
		if (!GL.getCapabilities().OpenGL41 && !useArb) {
			supported = false;
			binaryFormatCount = 0;
			logSupport("unavailable");
			return false;
		}

		binaryFormatCount = GL11C.glGetInteger(GL_NUM_PROGRAM_BINARY_FORMATS());
		supported = binaryFormatCount > 0;
		logSupport(useArb ? "GL_ARB_get_program_binary" : "OpenGL 4.1");
		return supported;
	}

	private static void logSupport(String api) {
		WynncraftDebugLog.info("ambience-program-binary-support",
			"OpenGL program binary cache support: available={} api={} formats={}",
			supported, api, binaryFormatCount);
	}

	private static int GL_PROGRAM_BINARY_RETRIEVABLE_HINT() {
		return useArb ? ARBGetProgramBinary.GL_PROGRAM_BINARY_RETRIEVABLE_HINT : GL41C.GL_PROGRAM_BINARY_RETRIEVABLE_HINT;
	}

	private static int GL_PROGRAM_BINARY_LENGTH() {
		return useArb ? ARBGetProgramBinary.GL_PROGRAM_BINARY_LENGTH : GL41C.GL_PROGRAM_BINARY_LENGTH;
	}

	private static int GL_NUM_PROGRAM_BINARY_FORMATS() {
		return useArb ? ARBGetProgramBinary.GL_NUM_PROGRAM_BINARY_FORMATS : GL41C.GL_NUM_PROGRAM_BINARY_FORMATS;
	}

	private static void programBinary(int program, int format, ByteBuffer binary) {
		if (useArb) {
			ARBGetProgramBinary.glProgramBinary(program, format, binary);
		} else {
			GL41C.glProgramBinary(program, format, binary);
		}
	}

	private static void programParameteri(int program, int pname, int value) {
		if (useArb) {
			ARBGetProgramBinary.glProgramParameteri(program, pname, value);
		} else {
			GL41C.glProgramParameteri(program, pname, value);
		}
	}

	private static void getProgramBinary(int program, IntBuffer length, IntBuffer format, ByteBuffer binary) {
		if (useArb) {
			ARBGetProgramBinary.glGetProgramBinary(program, length, format, binary);
		} else {
			GL41C.glGetProgramBinary(program, length, format, binary);
		}
	}

	private record CachedBinary(int format, ByteBuffer binary) {
	}
}
