package net.irisshaders.iris.gui.screen;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.ambience.AmbiencePack;
import net.irisshaders.iris.ambience.AmbiencePackManager;
import net.irisshaders.iris.ambience.AmbienceProfile;
import net.irisshaders.iris.ambience.AmbienceRegion;
import net.irisshaders.iris.ambience.AmbienceRuntime;
import net.irisshaders.iris.gui.GuiUtil;
import net.irisshaders.iris.gui.element.screen.IrisButton;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.transforms.SmoothedFloat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class AmbienceRegionEditorScreen extends Screen {
	private static final int TOP = 32;
	private static final int BOTTOM_CONTROLS_HEIGHT = 58;
	private static final int SIDE_MARGIN = 8;
	private static final int GAP = 8;
	private static final int PROFILE_ROW_HEIGHT = 36;
	private static final int MAX_HISTORY = 50;
	// Vertex budget for a single freeform/polygon region. Runtime point-in-polygon (AmbienceRegion.Shape.containsPoint)
	// is O(n) but bounding-box gated and runs at most once per client tick, so a few thousand points is negligible.
	private static final int MAX_FREEFORM_POINTS = 1500;
	// Freeform drag samples a new vertex roughly every this many *screen* pixels, so capture density tracks zoom
	// instead of a fixed world distance (which over-samples zoomed out and under-samples zoomed in).
	private static final double FREEFORM_CAPTURE_SPACING_PX = 2.0;
	// Ramer-Douglas-Peucker tolerance, expressed in *screen* pixels at draw-time zoom and converted to world units.
	// Tying it to what the user can actually see preserves the border detail they zoomed in to draw.
	private static final double FREEFORM_SIMPLIFY_TOLERANCE_PX = 1.5;
	// Polygon clicks within this many screen pixels of the previous vertex are treated as the same point.
	private static final double POLYGON_MERGE_TOLERANCE_PX = 1.0;
	// Clicking within this many screen pixels of the first vertex closes an in-progress freeform shape.
	private static final double FREEFORM_CLOSE_RADIUS_PX = 12.0;
	// Outline segments whose WCAG contrast against the map pixels behind them falls below this get a dark casing,
	// so light/bluish lines stay readable over snow and other light terrain.
	private static final double LINE_CASING_MIN_CONTRAST = 3.0;
	private static final int LINE_CASING_COLOR = 0xFF000000;
	private static final Component EDIT_TITLE = Component.translatable("pack.iris.ambience.region.editor.title").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);

	private final Screen parent;
	private final String packId;
	private final AmbiencePackManager manager = AmbiencePackManager.getInstance();
	private final FrameUpdateNotifier notifier = new FrameUpdateNotifier();
	private final ArrayDeque<List<AmbienceRegion>> undoStack = new ArrayDeque<>();
	private final ArrayDeque<List<AmbienceRegion>> redoStack = new ArrayDeque<>();
	private final List<AmbienceRegion.Point> activePoints = new ArrayList<>();
	private Optional<AmbiencePackManager.LoadedAmbiencePack> loadedPack = Optional.empty();
	private List<AmbienceProfile> profiles = List.of();
	private List<AmbienceRegion> regions = new ArrayList<>();
	private AmbienceMapTileLoader mapTiles;
	private Tool tool = Tool.FREEFORM;
	private int selectedRegionIndex = -1;
	private String selectedProfileId = "";
	private Component status = Component.empty();
	private double sidebarScroll;
	private double zoom = 1.0;
	private double panX;
	private double panY;
	private boolean drawing;
	private boolean panning;
	private AmbienceRegion.Point boxStart;
	private AmbienceRegion.Point boxEnd;
	private Button undoButton;
	private Button redoButton;
	private final List<Button> toolButtons = new ArrayList<>();
	private boolean loadedInitialPack;
	private float backgroundInit = 0.0f;

	public final SmoothedFloat buttonTransition = new SmoothedFloat(1, 1, () -> backgroundInit, notifier);

	public AmbienceRegionEditorScreen(Screen parent, String packId) {
		super(Component.translatable("options.iris.wynncraftAmbienceRegionEditor.title"));
		this.parent = parent;
		this.packId = packId;
	}

	@Override
	protected void init() {
		super.init();
		if (!loadedInitialPack) {
			reloadPack();
			loadedInitialPack = true;
		}
		if (mapTiles == null) {
			mapTiles = new AmbienceMapTileLoader(this.minecraft);
			mapTiles.start();
		}

		this.clearWidgets();
		layoutButtons();
		updateUndoRedoButtons();
	}

	private void layoutButtons() {
		toolButtons.clear();
		MapViewport viewport = viewport();
		int buttonCount = 9;
		int spacing = 4;
		int buttonWidth = Math.max(46, Math.min(74, (viewport.width - spacing * (buttonCount - 1)) / buttonCount));
		int totalWidth = buttonWidth * buttonCount + spacing * (buttonCount - 1);
		int x = viewport.x + Math.max(0, (viewport.width - totalWidth) / 2);
		int y = this.height - 27;

		addToolButton(Component.translatable("options.iris.wynncraftAmbienceRegionToolFreeform"), Tool.FREEFORM, x, y, buttonWidth);
		x += buttonWidth + spacing;
		addToolButton(Component.translatable("options.iris.wynncraftAmbienceRegionToolPolygon"), Tool.POLYGON, x, y, buttonWidth);
		x += buttonWidth + spacing;
		addToolButton(Component.translatable("options.iris.wynncraftAmbienceRegionToolBox"), Tool.BOX, x, y, buttonWidth);
		x += buttonWidth + spacing;
		addToolButton(Component.translatable("options.iris.wynncraftAmbienceRegionToolPriority"), Tool.PRIORITY, x, y, buttonWidth);
		x += buttonWidth + spacing;
		undoButton = this.addRenderableWidget(IrisButton.iris$builder(Component.translatable("options.iris.undo"), button -> undo(), buttonTransition)
			.bounds(x, y, buttonWidth, 20)
			.build());
		x += buttonWidth + spacing;
		redoButton = this.addRenderableWidget(IrisButton.iris$builder(Component.translatable("options.iris.redo"), button -> redo(), buttonTransition)
			.bounds(x, y, buttonWidth, 20)
			.build());
		x += buttonWidth + spacing;
		addToolButton(Component.translatable("options.iris.wynncraftAmbienceRegionToolErase"), Tool.ERASE, x, y, buttonWidth);
		x += buttonWidth + spacing;
		this.addRenderableWidget(IrisButton.iris$builder(Component.translatable("options.iris.apply"), button -> saveAndClose(), buttonTransition)
			.bounds(x, y, buttonWidth, 20)
			.build());
		x += buttonWidth + spacing;
		this.addRenderableWidget(IrisButton.iris$builder(CommonComponents.GUI_CANCEL, button -> cancelAndClose(), buttonTransition)
			.bounds(x, y, buttonWidth, 20)
			.build());
		if (profiles.isEmpty()) {
			int sidebarButtonWidth = Math.max(80, sidebarWidth() - 12);
			this.addRenderableWidget(IrisButton.iris$builder(Component.translatable("options.iris.wynncraftAmbienceProfileCreate"), button -> openAddPreset(), buttonTransition)
				.bounds(sidebarX() + 6, this.height - 27, sidebarButtonWidth, 20)
				.build());
		}
	}

	private void addToolButton(Component label, Tool targetTool, int x, int y, int width) {
		Button toolButton = this.addRenderableWidget(IrisButton.iris$builder(label, button -> setTool(targetTool), buttonTransition)
			.bounds(x, y, width, 20)
			.build());
		toolButton.active = !profiles.isEmpty();
		toolButtons.add(toolButton);
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
		notifier.onNewFrame();
		backgroundInit = 1.0f;

		renderSidebar(guiGraphics, mouseX, mouseY);
		renderMap(guiGraphics, mouseX, mouseY);
		super.render(guiGraphics, mouseX, mouseY, delta);

		drawCenteredTruncated(guiGraphics, this.title, 8, 0xFFFFFFFF);
		Component subtitle = status != null && !status.getString().isBlank() ? status : EDIT_TITLE;
		drawCenteredTruncated(guiGraphics, subtitle, 21, 0xFFFFFFFF);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean repeat) {
		if (super.mouseClicked(event, repeat)) {
			return true;
		}
		double mouseX = event.x();
		double mouseY = event.y();
		if (event.button() == GLFW.GLFW_MOUSE_BUTTON_1 && handleSidebarClick(mouseX, mouseY)) {
			return true;
		}
		if (mapAreaContains(mouseX, mouseY)) {
			if (profiles.isEmpty() || selectedProfileId.isBlank()) {
				status = Component.translatable("options.iris.wynncraftAmbienceRegionNoProfiles").withStyle(ChatFormatting.YELLOW);
				return false;
			}
			if (event.button() == GLFW.GLFW_MOUSE_BUTTON_2) {
				panning = true;
				return true;
			}
			if (event.button() != GLFW.GLFW_MOUSE_BUTTON_1) {
				return false;
			}
			MapViewport viewport = viewport();
			AmbienceRegion.Point point = viewport.toWorld(mouseX, mouseY);
			return switch (tool) {
				case FREEFORM -> freeformPress(point, mouseX, mouseY);
				case POLYGON -> addPolygonPoint(point, mouseX, mouseY);
				case BOX -> beginBox(point);
				case PRIORITY -> adjustPriorityAt(mouseX, mouseY);
				case ERASE -> eraseAt(mouseX, mouseY);
			};
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		double mouseX = event.x();
		double mouseY = event.y();
		if (panning) {
			panX += dragX;
			panY += dragY;
			return true;
		}
		if (drawing && mapAreaContains(mouseX, mouseY)) {
			MapViewport viewport = viewport();
			AmbienceRegion.Point point = viewport.toWorld(mouseX, mouseY);
			if (tool == Tool.FREEFORM) {
				addFreeformPoint(point);
				return true;
			}
			if (tool == Tool.BOX) {
				boxEnd = point;
				return true;
			}
		}
		if (tool == Tool.ERASE && event.button() == GLFW.GLFW_MOUSE_BUTTON_1 && mapAreaContains(mouseX, mouseY)) {
			return eraseAt(mouseX, mouseY);
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	private void openAddPreset() {
		AmbienceProfileSelectionScreen profileScreen = new AmbienceProfileSelectionScreen(parent, packId);
		this.minecraft.setScreen(new AmbiencePresetCreateScreen(profileScreen, packId));
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (event.button() == GLFW.GLFW_MOUSE_BUTTON_2 && panning) {
			panning = false;
			return true;
		}
		if (event.button() == GLFW.GLFW_MOUSE_BUTTON_1 && drawing) {
			if (tool == Tool.BOX) {
				finishBox();
			}
			// Releasing only ends the current freeform stroke; the path persists so the next press appends to it
			// (with a straight connector). The shape is only committed when the user closes or presses Enter.
			drawing = false;
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (sidebarContains(mouseX, mouseY)) {
			sidebarScroll = clamp(sidebarScroll - scrollY * PROFILE_ROW_HEIGHT, 0, maxSidebarScroll());
			return true;
		}
		if (mapAreaContains(mouseX, mouseY)) {
			MapViewport oldViewport = viewport();
			AmbienceRegion.Point anchoredPoint = oldViewport.toWorld(mouseX, mouseY);
			zoom = clamp(zoom * (scrollY > 0 ? 1.15 : 1.0 / 1.15), 1.0, 12.0);
			MapViewport newViewport = viewport();
			panX += mouseX - newViewport.screenX(anchoredPoint.x);
			panY += mouseY - newViewport.screenY(anchoredPoint.z);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.isEscape() && (!activePoints.isEmpty() || drawing)) {
			clearActiveDrawing();
			return true;
		}
		if ((event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER)
			&& tool == Tool.FREEFORM && activePoints.size() >= 3) {
			finishFreeform();
			return true;
		}
		if (tool == Tool.PRIORITY && selectedRegionIndex >= 0 && selectedRegionIndex < regions.size()) {
			int key = event.key();
			if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_EQUAL || key == GLFW.GLFW_KEY_KP_ADD) {
				changeRegionPriority(selectedRegionIndex, 1);
				return true;
			}
			if (key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_MINUS || key == GLFW.GLFW_KEY_KP_SUBTRACT) {
				changeRegionPriority(selectedRegionIndex, -1);
				return true;
			}
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		if (!undoStack.isEmpty() && minecraft != null) {
			minecraft.setScreen(new net.minecraft.client.gui.screens.ConfirmScreen(confirmed -> {
				minecraft.setScreen(this);
				if (confirmed) {
					saveAndClose();
				} else {
					cancelAndClose();
				}
			}, Component.translatable("options.iris.wynncraftAmbienceRegionUnsaved"),
				Component.translatable("options.iris.wynncraftAmbienceRegionUnsavedDesc"),
				CommonComponents.GUI_YES, CommonComponents.GUI_NO));
		} else {
			cancelAndClose();
		}
	}

	@Override
	public void removed() {
		super.removed();
		if (mapTiles != null) {
			mapTiles.close();
			mapTiles = null;
		}
	}

	private void reloadPack() {
		loadedPack = manager.getPack(packId);
		if (loadedPack.isEmpty()) {
			profiles = List.of();
			regions = new ArrayList<>();
			selectedProfileId = "";
			status = Component.translatable("options.iris.wynncraftAmbienceSelectedMissing", packId).withStyle(ChatFormatting.YELLOW);
			return;
		}

		AmbiencePack pack = loadedPack.get().pack();
		profiles = pack.profiles == null ? List.of() : pack.profiles.stream()
			.filter(profile -> profile != null && profile.id != null && !profile.id.isBlank())
			.toList();
		regions = copyRegions(pack.regions);
		if (selectedProfileId == null || selectedProfileId.isBlank() || profiles.stream().noneMatch(profile -> profile.id.equals(selectedProfileId))) {
			selectedProfileId = profiles.isEmpty() ? "" : profiles.get(0).id;
		}
		status = Component.translatable("options.iris.wynncraftAmbienceEditingPack", pack.displayName());
	}

	private void renderSidebar(GuiGraphics guiGraphics, int mouseX, int mouseY) {
		int x = sidebarX();
		int y = TOP;
		int width = sidebarWidth();
		int height = listBottom() - TOP;
		GuiUtil.drawPanel(guiGraphics, x, y, width, height);
		guiGraphics.drawCenteredString(font, Component.translatable("options.iris.wynncraftAmbienceRegionProfiles"), x + width / 2, y + 8, 0xFFFFFFFF);

		int listTop = y + 24;
		int listHeight = height - 28;
		guiGraphics.enableScissor(x + 1, listTop, x + width - 1, listTop + listHeight);
		for (int i = 0; i < profiles.size(); i++) {
			int rowY = listTop + i * PROFILE_ROW_HEIGHT - (int) sidebarScroll;
			if (rowY + PROFILE_ROW_HEIGHT < listTop || rowY > listTop + listHeight) {
				continue;
			}
			renderProfileRow(guiGraphics, profiles.get(i), x + 6, rowY + 2, width - 12, PROFILE_ROW_HEIGHT - 4, mouseX, mouseY);
		}
		guiGraphics.disableScissor();
	}

	private void renderProfileRow(GuiGraphics guiGraphics, AmbienceProfile profile, int x, int y, int width, int height, int mouseX, int mouseY) {
		boolean selected = profile.id.equals(selectedProfileId);
		boolean hovered = mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
		if (selected || hovered) {
			GuiUtil.bindIrisWidgetsTexture();
			GuiUtil.drawButton(guiGraphics, x - 2, y - 2, width + 4, height + 4, hovered, false);
		}

		String profileName = profile.id;
		if (font.width(profileName) > width - 8) {
			profileName = font.plainSubstrByWidth(profileName, width - 20) + "...";
		}
		int regionCount = countRegions(profile.id);
		Optional<AmbiencePackManager.ResolvedProfile> resolved = manager.resolveProfile(packId, profile.id);
		String shaderName = resolved.map(AmbiencePackManager.ResolvedProfile::resolvedShaderPack).orElse("missing shader");
		if (font.width(shaderName) > width - 74) {
			shaderName = font.plainSubstrByWidth(shaderName, width - 86) + "...";
		}

		guiGraphics.drawString(font, Component.literal(profileName), x + 4, y + 4, selected ? 0xFFFFF263 : 0xFFFFFFFF);
		guiGraphics.drawString(font, Component.literal(shaderName).withStyle(ChatFormatting.GRAY), x + 4, y + 16, resolved.isPresent() ? 0xFFAAAAAA : 0xFFFFAAAA);
		Component count = Component.translatable("options.iris.wynncraftAmbienceRegionCount", regionCount);
		guiGraphics.drawString(font, count, x + width - font.width(count) - 4, y + 16, 0xFFCCCCCC);
	}

	private void renderMap(GuiGraphics guiGraphics, int mouseX, int mouseY) {
		MapViewport viewport = viewport();
		GuiUtil.drawPanel(guiGraphics, viewport.x, viewport.y, viewport.width, viewport.height);
		guiGraphics.enableScissor(viewport.x + 1, viewport.y + 1, viewport.x + viewport.width - 1, viewport.y + viewport.height - 1);
		renderMapBackground(guiGraphics, viewport);
		renderMapTiles(guiGraphics, viewport);
		renderRegions(guiGraphics, viewport);
		renderActiveDrawing(guiGraphics, viewport, mouseX, mouseY);
		guiGraphics.disableScissor();
		guiGraphics.drawString(font, mapStatus(), viewport.x + 6, viewport.y + viewport.height - 14, 0xFFFFFFFF);
		if (mapAreaContains(mouseX, mouseY)) {
			AmbienceRegion.Point point = viewport.toWorld(mouseX, mouseY);
			Component coords = Component.literal(String.format(Locale.ROOT, "x %.0f, z %.0f", point.x, point.z)).withStyle(ChatFormatting.GRAY);
			guiGraphics.drawString(font, coords, viewport.x + viewport.width - font.width(coords) - 6, viewport.y + viewport.height - 14, 0xFFFFFFFF);
		}
	}

	private void renderMapBackground(GuiGraphics guiGraphics, MapViewport viewport) {
		guiGraphics.fill(RenderPipelines.GUI, viewport.x + 1, viewport.y + 1, viewport.x + viewport.width - 1, viewport.y + viewport.height - 1, 0xFF111318);
		int gridColor = 0x334E6A7A;
		for (int worldX = -3000; worldX <= 3000; worldX += 512) {
			int x = (int) Math.round(viewport.screenX(worldX));
			guiGraphics.fill(RenderPipelines.GUI, x, viewport.y + 1, x + 1, viewport.y + viewport.height - 1, gridColor);
		}
		for (int worldZ = -6500; worldZ <= 500; worldZ += 512) {
			int y = (int) Math.round(viewport.screenY(worldZ));
			guiGraphics.fill(RenderPipelines.GUI, viewport.x + 1, y, viewport.x + viewport.width - 1, y + 1, gridColor);
		}
	}

	private void renderMapTiles(GuiGraphics guiGraphics, MapViewport viewport) {
		if (mapTiles == null) {
			return;
		}
		for (AmbienceMapTileLoader.Tile tile : mapTiles.tiles()) {
			if (!tile.ready()) {
				continue;
			}
			// Snap every edge to the rounded screen position of its *shared* world boundary. Because neighbouring
			// tiles meet at the same world coordinate (one tile's x2+1 is the next tile's x1), both round it to the
			// identical pixel, so the mosaic has no 1px gaps (the dark background showing through as seams) or overlaps.
			int x = (int) Math.round(viewport.screenX(tile.x1()));
			int y = (int) Math.round(viewport.screenY(tile.z1()));
			int x2 = (int) Math.round(viewport.screenX(tile.x2() + 1));
			int y2 = (int) Math.round(viewport.screenY(tile.z2() + 1));
			int width = Math.max(1, x2 - x);
			int height = Math.max(1, y2 - y);
			guiGraphics.blit(RenderPipelines.GUI_TEXTURED, tile.identifier(), x, y, 0, 0, width, height, tile.textureWidth(), tile.textureHeight(), tile.textureWidth(), tile.textureHeight());
		}
	}

	private void renderRegions(GuiGraphics guiGraphics, MapViewport viewport) {
		List<AmbienceRegion> sorted = regions.stream()
			.sorted(Comparator.comparing(region -> selectedProfileId.equals(region.profile) ? 1 : 0))
			.toList();
		for (AmbienceRegion region : sorted) {
			boolean selected = selectedProfileId.equals(region.profile);
			int color = selected ? 0xFFFFF263 : 0x8899C7FF;
			drawRegion(guiGraphics, viewport, region, color, selected ? 2 : 1);
		}
		if (tool == Tool.PRIORITY) {
			renderPriorityLabels(guiGraphics, viewport);
		}
	}

	private void renderPriorityLabels(GuiGraphics guiGraphics, MapViewport viewport) {
		for (int i = 0; i < regions.size(); i++) {
			AmbienceRegion region = regions.get(i);
			if (region == null || region.shape == null) {
				continue;
			}
			double[] center = regionCenterWorld(region);
			if (center == null) {
				continue;
			}
			int sx = (int) Math.round(viewport.screenX(center[0]));
			int sy = (int) Math.round(viewport.screenY(center[1]));
			if (sx < viewport.x || sx > viewport.x + viewport.width || sy < viewport.y || sy > viewport.y + viewport.height) {
				continue;
			}
			boolean isSelected = i == selectedRegionIndex;
			if (isSelected) {
				// Re-draw the region being adjusted with a distinct highlight so it is obvious which one will change.
				drawRegion(guiGraphics, viewport, region, 0xFF63FF8A, 2);
			}
			Component label = Component.literal(Integer.toString(region.priority));
			guiGraphics.drawString(font, label, sx - font.width(label) / 2, sy - 4, isSelected ? 0xFF63FF8A : 0xFFFFFFFF);
		}
	}

	private double[] regionCenterWorld(AmbienceRegion region) {
		AmbienceRegion.Shape shape = region.shape;
		String type = shape.type == null ? "box" : shape.type.toLowerCase(Locale.ROOT);
		if ("polygon".equals(type) && shape.points != null && !shape.points.isEmpty()) {
			double sumX = 0.0;
			double sumZ = 0.0;
			int count = 0;
			for (AmbienceRegion.Point point : shape.points) {
				if (point != null) {
					sumX += point.x;
					sumZ += point.z;
					count++;
				}
			}
			return count == 0 ? null : new double[] {sumX / count, sumZ / count};
		}
		if ("sphere".equals(type) && shape.center != null && shape.center.length >= 2) {
			return new double[] {shape.center[0], shape.center[1]};
		}
		if (shape.min != null && shape.max != null && shape.min.length >= 2 && shape.max.length >= 2) {
			return new double[] {(shape.min[0] + shape.max[0]) * 0.5, (shape.min[1] + shape.max[1]) * 0.5};
		}
		return null;
	}

	private void renderActiveDrawing(GuiGraphics guiGraphics, MapViewport viewport, int mouseX, int mouseY) {
		if (tool == Tool.POLYGON && !activePoints.isEmpty()) {
			drawAdaptivePolyline(guiGraphics, viewport, activePoints, 0xFFFFFFFF, false, 2);
		}
		// Freeform now persists across strokes, so its path is drawn whenever it has points -- not only mid-drag.
		if (tool == Tool.FREEFORM && !activePoints.isEmpty()) {
			drawAdaptivePolyline(guiGraphics, viewport, activePoints, 0xFFFFFFFF, false, 2);
			// Between strokes, preview the straight connector the next press will create, from the last vertex to
			// the cursor, so the multi-stroke behaviour is visible.
			if (!drawing && mapAreaContains(mouseX, mouseY)) {
				AmbienceRegion.Point last = activePoints.get(activePoints.size() - 1);
				AmbienceRegion.Point cursor = viewport.toWorld(mouseX, mouseY);
				maybeDrawCasing(guiGraphics, viewport, last, cursor, 0x80FFFFFF, 1);
				drawWorldLine(guiGraphics, viewport, last, cursor, 0x80FFFFFF, 1);
			}
			// Mark the start vertex once the shape can be closed, and highlight it when the cursor is in range.
			if (activePoints.size() >= 3) {
				boolean canClose = !drawing && nearFirstPoint(mouseX, mouseY);
				drawVertexMarker(guiGraphics, viewport, activePoints.get(0), canClose ? 0xFF63FF8A : 0xFFFFF263, canClose);
			}
		}
		if (tool == Tool.BOX && drawing && boxStart != null && boxEnd != null) {
			List<AmbienceRegion.Point> points = boxPoints(boxStart, boxEnd);
			drawAdaptivePolyline(guiGraphics, viewport, points, 0xFFFFFFFF, true, 2);
		}
	}

	private void drawVertexMarker(GuiGraphics guiGraphics, MapViewport viewport, AmbienceRegion.Point point, int color, boolean large) {
		int cx = (int) Math.round(viewport.screenX(point.x));
		int cy = (int) Math.round(viewport.screenY(point.z));
		int half = large ? 4 : 3;
		guiGraphics.fill(RenderPipelines.GUI, cx - half, cy - half, cx + half, cy + half, color);
		guiGraphics.fill(RenderPipelines.GUI, cx - half + 1, cy - half + 1, cx + half - 1, cy + half - 1, 0xFF1A1C22);
	}

	private void drawRegion(GuiGraphics guiGraphics, MapViewport viewport, AmbienceRegion region, int color, int thickness) {
		if (region == null || region.shape == null) {
			return;
		}
		String shapeType = region.shape.type == null ? "box" : region.shape.type.toLowerCase(Locale.ROOT);
		if ("polygon".equals(shapeType) && region.shape.points != null && region.shape.points.size() >= 3) {
			drawAdaptivePolyline(guiGraphics, viewport, region.shape.points, color, true, thickness);
		} else if ("sphere".equals(shapeType) && region.shape.center != null && region.shape.center.length >= 2 && region.shape.radius > 0) {
			drawCircle(guiGraphics, viewport, region.shape.center[0], region.shape.center[1], region.shape.radius, color, thickness);
		} else if (region.shape.min != null && region.shape.max != null && region.shape.min.length >= 2 && region.shape.max.length >= 2) {
			drawAdaptivePolyline(guiGraphics, viewport, boxPoints(
				new AmbienceRegion.Point(region.shape.min[0], region.shape.min[1]),
				new AmbienceRegion.Point(region.shape.max[0], region.shape.max[1])
			), color, true, thickness);
		}
	}

	// Draws a polyline with a dark casing under any segment whose colour would be hard to read against the map
	// behind it. The casing is a separate pass so the dark underlay never covers the coloured line at vertices.
	private void drawAdaptivePolyline(GuiGraphics guiGraphics, MapViewport viewport, List<AmbienceRegion.Point> points, int color, boolean closed, int thickness) {
		if (points.size() < 2) {
			return;
		}
		for (int i = 1; i < points.size(); i++) {
			maybeDrawCasing(guiGraphics, viewport, points.get(i - 1), points.get(i), color, thickness);
		}
		if (closed) {
			maybeDrawCasing(guiGraphics, viewport, points.get(points.size() - 1), points.get(0), color, thickness);
		}
		drawPolyline(guiGraphics, viewport, points, color, closed, thickness);
	}

	private void maybeDrawCasing(GuiGraphics guiGraphics, MapViewport viewport, AmbienceRegion.Point a, AmbienceRegion.Point b, int color, int thickness) {
		if (segmentNeedsCasing(color, a, b)) {
			drawWorldLine(guiGraphics, viewport, a, b, LINE_CASING_COLOR, thickness + 2);
		}
	}

	// A segment is cased if the line (blended over the map by its own alpha) is too low-contrast against the map at
	// any of its endpoints or midpoint -- the multi-point sample catches segments that straddle a terrain boundary.
	private boolean segmentNeedsCasing(int color, AmbienceRegion.Point a, AmbienceRegion.Point b) {
		if (mapTiles == null) {
			return false;
		}
		return sampleLowContrast(color, a.x, a.z)
			|| sampleLowContrast(color, (a.x + b.x) * 0.5, (a.z + b.z) * 0.5)
			|| sampleLowContrast(color, b.x, b.z);
	}

	private boolean sampleLowContrast(int color, double worldX, double worldZ) {
		int background = mapTiles.sampleArgb(worldX, worldZ);
		if ((background >>> 24) == 0) {
			return false; // no loaded map tile behind this point -> the dark editor background already provides contrast
		}
		return contrastRatio(blendOver(background, color), background) < LINE_CASING_MIN_CONTRAST;
	}

	private static int blendOver(int backgroundArgb, int lineArgb) {
		double a = (lineArgb >>> 24) / 255.0;
		int r = (int) Math.round(((backgroundArgb >> 16) & 0xFF) * (1.0 - a) + ((lineArgb >> 16) & 0xFF) * a);
		int g = (int) Math.round(((backgroundArgb >> 8) & 0xFF) * (1.0 - a) + ((lineArgb >> 8) & 0xFF) * a);
		int b = (int) Math.round((backgroundArgb & 0xFF) * (1.0 - a) + (lineArgb & 0xFF) * a);
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	private static double contrastRatio(int argbA, int argbB) {
		double la = relativeLuminance(argbA);
		double lb = relativeLuminance(argbB);
		return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
	}

	private static double relativeLuminance(int argb) {
		double r = srgbToLinear(((argb >> 16) & 0xFF) / 255.0);
		double g = srgbToLinear(((argb >> 8) & 0xFF) / 255.0);
		double b = srgbToLinear((argb & 0xFF) / 255.0);
		return 0.2126 * r + 0.7152 * g + 0.0722 * b;
	}

	private static double srgbToLinear(double channel) {
		return channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
	}

	private void drawPolyline(GuiGraphics guiGraphics, MapViewport viewport, List<AmbienceRegion.Point> points, int color, boolean closed, int thickness) {
		if (points.size() < 2) {
			return;
		}
		for (int i = 1; i < points.size(); i++) {
			drawWorldLine(guiGraphics, viewport, points.get(i - 1), points.get(i), color, thickness);
		}
		if (closed) {
			drawWorldLine(guiGraphics, viewport, points.get(points.size() - 1), points.get(0), color, thickness);
		}
	}

	private void drawCircle(GuiGraphics guiGraphics, MapViewport viewport, double centerX, double centerZ, double radius, int color, int thickness) {
		List<AmbienceRegion.Point> points = new ArrayList<>(48);
		for (int i = 0; i < 48; i++) {
			double angle = Math.PI * 2.0 * i / 48.0;
			points.add(new AmbienceRegion.Point(centerX + Math.cos(angle) * radius, centerZ + Math.sin(angle) * radius));
		}
		drawAdaptivePolyline(guiGraphics, viewport, points, color, true, thickness);
	}

	private void drawWorldLine(GuiGraphics guiGraphics, MapViewport viewport, AmbienceRegion.Point start, AmbienceRegion.Point end, int color, int thickness) {
		ScreenLine line = clipLine(
			viewport,
			viewport.screenX(start.x),
			viewport.screenY(start.z),
			viewport.screenX(end.x),
			viewport.screenY(end.z)
		);
		if (line == null) {
			return;
		}

		drawLine(guiGraphics,
			(int) Math.round(line.x1),
			(int) Math.round(line.y1),
			(int) Math.round(line.x2),
			(int) Math.round(line.y2),
			color,
			thickness);
	}

	private ScreenLine clipLine(MapViewport viewport, double x1, double y1, double x2, double y2) {
		double minX = viewport.x + 1.0;
		double minY = viewport.y + 1.0;
		double maxX = viewport.x + viewport.width - 1.0;
		double maxY = viewport.y + viewport.height - 1.0;
		double dx = x2 - x1;
		double dy = y2 - y1;
		double start = 0.0;
		double end = 1.0;
		double[] p = {-dx, dx, -dy, dy};
		double[] q = {x1 - minX, maxX - x1, y1 - minY, maxY - y1};

		for (int i = 0; i < 4; i++) {
			if (p[i] == 0.0) {
				if (q[i] < 0.0) {
					return null;
				}
				continue;
			}
			double ratio = q[i] / p[i];
			if (p[i] < 0.0) {
				start = Math.max(start, ratio);
			} else {
				end = Math.min(end, ratio);
			}
			if (start > end) {
				return null;
			}
		}

		return new ScreenLine(x1 + start * dx, y1 + start * dy, x1 + end * dx, y1 + end * dy);
	}

	private void drawLine(GuiGraphics guiGraphics, int x1, int y1, int x2, int y2, int color, int thickness) {
		// Centre the stroke on the path (offset by half its width) so a thicker casing grows symmetrically around the
		// line instead of extending entirely to one side.
		int half = thickness / 2;
		int dx = x2 - x1;
		int dy = y2 - y1;
		int steps = Math.max(Math.abs(dx), Math.abs(dy));
		if (steps == 0) {
			guiGraphics.fill(RenderPipelines.GUI, x1 - half, y1 - half, x1 - half + thickness, y1 - half + thickness, color);
			return;
		}
		for (int i = 0; i <= steps; i++) {
			double t = (double) i / steps;
			int x = (int) Math.round(x1 + dx * t);
			int y = (int) Math.round(y1 + dy * t);
			guiGraphics.fill(RenderPipelines.GUI, x - half, y - half, x - half + thickness, y - half + thickness, color);
		}
	}

	private Component mapStatus() {
		Component toolName = Component.translatable(tool.translationKey);
		Component mapStatus = mapTiles == null ? Component.empty() : mapTiles.status();
		if (tool == Tool.POLYGON && activePoints.size() >= 3) {
			return Component.translatable("options.iris.wynncraftAmbienceRegionMapStatusPolygon", toolName, mapStatus);
		}
		return Component.translatable("options.iris.wynncraftAmbienceRegionMapStatus", toolName, mapStatus);
	}

	private boolean freeformPress(AmbienceRegion.Point point, double mouseX, double mouseY) {
		// Clicking the start vertex closes the shape, so a multi-stroke freeform never has to be finished by a drag.
		if (activePoints.size() >= 3 && nearFirstPoint(mouseX, mouseY)) {
			finishFreeform();
			return true;
		}
		drawing = true;
		if (activePoints.isEmpty()) {
			// Fresh shape: lay down the first vertex.
			activePoints.add(point);
		} else {
			// Continuing an existing shape: the previous stroke's end is already a vertex, so appending this point
			// implicitly draws the straight connector to where the new stroke begins.
			addFreeformPoint(point);
		}
		status = Component.translatable("options.iris.wynncraftAmbienceRegionDrawingFreeform");
		return true;
	}

	private boolean nearFirstPoint(double mouseX, double mouseY) {
		if (activePoints.isEmpty()) {
			return false;
		}
		MapViewport viewport = viewport();
		AmbienceRegion.Point first = activePoints.get(0);
		double dx = mouseX - viewport.screenX(first.x);
		double dy = mouseY - viewport.screenY(first.z);
		return dx * dx + dy * dy <= FREEFORM_CLOSE_RADIUS_PX * FREEFORM_CLOSE_RADIUS_PX;
	}

	private void addFreeformPoint(AmbienceRegion.Point point) {
		if (activePoints.isEmpty()) {
			activePoints.add(point);
			return;
		}
		double minWorld = FREEFORM_CAPTURE_SPACING_PX / Math.max(viewport().scale, 1.0e-4);
		if (distanceSquared(activePoints.get(activePoints.size() - 1), point) >= minWorld * minWorld) {
			activePoints.add(point);
		}
	}

	private void finishFreeform() {
		// The stroke is closed automatically on release: requiring the user to drag all the way back to the start
		// is impossible once they have panned away to keep tracing, so any sufficiently large stroke becomes a region.
		if (activePoints.size() < 3) {
			clearActiveDrawing();
			return;
		}
		addPolygonRegion(simplifyFreeform(activePoints));
		clearActiveDrawing();
	}

	private boolean addPolygonPoint(AmbienceRegion.Point point, double mouseX, double mouseY) {
		if (activePoints.size() >= 3) {
			AmbienceRegion.Point first = activePoints.get(0);
			MapViewport viewport = viewport();
			double dx = mouseX - viewport.screenX(first.x);
			double dy = mouseY - viewport.screenY(first.z);
			if (dx * dx + dy * dy <= 144.0) {
				addPolygonRegion(simplifyPolygon(activePoints));
				clearActiveDrawing();
				return true;
			}
		}
		activePoints.add(point);
		status = Component.translatable("options.iris.wynncraftAmbienceRegionDrawingPolygon", activePoints.size());
		return true;
	}

	private boolean beginBox(AmbienceRegion.Point point) {
		clearActiveDrawing();
		drawing = true;
		boxStart = point;
		boxEnd = point.copy();
		status = Component.translatable("options.iris.wynncraftAmbienceRegionDrawingBox");
		return true;
	}

	private void finishBox() {
		if (boxStart == null || boxEnd == null || Math.abs(boxStart.x - boxEnd.x) < 1.0 || Math.abs(boxStart.z - boxEnd.z) < 1.0) {
			clearActiveDrawing();
			return;
		}

		pushUndo();
		AmbienceRegion region = baseRegion();
		region.id = nextRegionId();
		region.shape = new AmbienceRegion.Shape();
		region.shape.type = "box";
		region.shape.min = new double[] {Math.min(boxStart.x, boxEnd.x), Math.min(boxStart.z, boxEnd.z)};
		region.shape.max = new double[] {Math.max(boxStart.x, boxEnd.x), Math.max(boxStart.z, boxEnd.z)};
		regions.add(region);
		clearActiveDrawing();
		status = Component.translatable("options.iris.wynncraftAmbienceRegionAdded", selectedProfileId);
	}

	private void addPolygonRegion(List<AmbienceRegion.Point> points) {
		if (points.size() < 3) {
			status = Component.translatable("options.iris.wynncraftAmbienceRegionTooSmall").withStyle(ChatFormatting.YELLOW);
			return;
		}
		pushUndo();
		AmbienceRegion region = baseRegion();
		region.id = nextRegionId();
		region.shape = new AmbienceRegion.Shape();
		region.shape.type = "polygon";
		region.shape.points = copyPoints(points);
		double[] bounds = polygonBounds(points);
		region.shape.min = new double[] {bounds[0], bounds[1]};
		region.shape.max = new double[] {bounds[2], bounds[3]};
		regions.add(region);
		status = Component.translatable("options.iris.wynncraftAmbienceRegionAdded", selectedProfileId);
	}

	private AmbienceRegion baseRegion() {
		AmbienceRegion region = new AmbienceRegion();
		region.profile = selectedProfileId;
		region.priority = regions.stream().mapToInt(existing -> existing == null ? 0 : existing.priority).max().orElse(0) + 1;
		return region;
	}

	private boolean eraseAt(double mouseX, double mouseY) {
		int index = findRegionNear(mouseX, mouseY);
		if (index < 0) {
			return false;
		}
		pushUndo();
		regions.remove(index);
		status = Component.translatable("options.iris.wynncraftAmbienceRegionErased");
		return true;
	}

	private boolean adjustPriorityAt(double mouseX, double mouseY) {
		int index = findRegionNear(mouseX, mouseY);
		if (index < 0) {
			selectedRegionIndex = -1;
			status = Component.translatable("options.iris.wynncraftAmbienceRegionPriorityHint");
			return false;
		}
		selectedRegionIndex = index;
		// Left-click raises priority (overrides more), Shift+click lowers it.
		changeRegionPriority(index, Minecraft.getInstance().hasShiftDown() ? -1 : 1);
		return true;
	}

	private void changeRegionPriority(int index, int delta) {
		if (index < 0 || index >= regions.size()) {
			return;
		}
		AmbienceRegion region = regions.get(index);
		if (region == null) {
			return;
		}
		pushUndo();
		region.priority += delta;
		status = Component.translatable("options.iris.wynncraftAmbienceRegionPrioritySet", region.priority);
	}

	private int findRegionNear(double mouseX, double mouseY) {
		MapViewport viewport = viewport();
		for (int i = regions.size() - 1; i >= 0; i--) {
			AmbienceRegion region = regions.get(i);
			if (regionNear(viewport, region, mouseX, mouseY)) {
				return i;
			}
		}
		return -1;
	}

	private boolean regionNear(MapViewport viewport, AmbienceRegion region, double mouseX, double mouseY) {
		if (region == null || region.shape == null) {
			return false;
		}
		AmbienceRegion.Point worldPoint = viewport.toWorld(mouseX, mouseY);
		if (region.shape.matches(worldPoint.x, 0.0, worldPoint.z, 0.0)) {
			return true;
		}
		String shapeType = region.shape.type == null ? "box" : region.shape.type.toLowerCase(Locale.ROOT);
		if ("polygon".equals(shapeType) && region.shape.points != null && region.shape.points.size() >= 3) {
			return polylineNear(viewport, region.shape.points, true, mouseX, mouseY);
		}
		if ("sphere".equals(shapeType) && region.shape.center != null && region.shape.center.length >= 2 && region.shape.radius > 0) {
			List<AmbienceRegion.Point> points = new ArrayList<>();
			for (int i = 0; i < 48; i++) {
				double angle = Math.PI * 2.0 * i / 48.0;
				points.add(new AmbienceRegion.Point(region.shape.center[0] + Math.cos(angle) * region.shape.radius, region.shape.center[1] + Math.sin(angle) * region.shape.radius));
			}
			return polylineNear(viewport, points, true, mouseX, mouseY);
		}
		if (region.shape.min != null && region.shape.max != null && region.shape.min.length >= 2 && region.shape.max.length >= 2) {
			return polylineNear(viewport, boxPoints(
				new AmbienceRegion.Point(region.shape.min[0], region.shape.min[1]),
				new AmbienceRegion.Point(region.shape.max[0], region.shape.max[1])
			), true, mouseX, mouseY);
		}
		return false;
	}

	private boolean polylineNear(MapViewport viewport, List<AmbienceRegion.Point> points, boolean closed, double mouseX, double mouseY) {
		for (int i = 1; i < points.size(); i++) {
			if (distanceToScreenSegment(viewport, points.get(i - 1), points.get(i), mouseX, mouseY) <= 7.0) {
				return true;
			}
		}
		return closed && distanceToScreenSegment(viewport, points.get(points.size() - 1), points.get(0), mouseX, mouseY) <= 7.0;
	}

	private double distanceToScreenSegment(MapViewport viewport, AmbienceRegion.Point start, AmbienceRegion.Point end, double mouseX, double mouseY) {
		double x1 = viewport.screenX(start.x);
		double y1 = viewport.screenY(start.z);
		double x2 = viewport.screenX(end.x);
		double y2 = viewport.screenY(end.z);
		double dx = x2 - x1;
		double dy = y2 - y1;
		double lengthSquared = dx * dx + dy * dy;
		if (lengthSquared == 0) {
			return Math.sqrt((mouseX - x1) * (mouseX - x1) + (mouseY - y1) * (mouseY - y1));
		}
		double t = clamp(((mouseX - x1) * dx + (mouseY - y1) * dy) / lengthSquared, 0.0, 1.0);
		double px = x1 + t * dx;
		double py = y1 + t * dy;
		return Math.sqrt((mouseX - px) * (mouseX - px) + (mouseY - py) * (mouseY - py));
	}

	private void setTool(Tool tool) {
		this.tool = tool;
		selectedRegionIndex = -1;
		clearActiveDrawing();
		if (tool == Tool.PRIORITY) {
			status = Component.translatable("options.iris.wynncraftAmbienceRegionPriorityHint");
		} else {
			status = Component.translatable("options.iris.wynncraftAmbienceRegionSelectedTool", Component.translatable(tool.translationKey));
		}
	}

	private void clearActiveDrawing() {
		activePoints.clear();
		drawing = false;
		boxStart = null;
		boxEnd = null;
	}

	private void saveAndClose() {
		try {
			manager.saveRegions(packId, regions);
			manager.reload();
			AmbienceRuntime.invalidateActiveProfile();
			undoStack.clear();
			redoStack.clear();
			if (minecraft != null) {
				minecraft.setScreen(parent);
			}
		} catch (IOException e) {
			Iris.logger.warn("Failed to save ambience regions", e);
			status = Component.literal(e.getMessage() == null ? "Save failed" : e.getMessage()).withStyle(ChatFormatting.RED);
		}
	}

	private void cancelAndClose() {
		undoStack.clear();
		redoStack.clear();
		clearActiveDrawing();
		if (minecraft != null) {
			minecraft.setScreen(parent);
		}
	}

	private void undo() {
		if (undoStack.isEmpty()) {
			return;
		}
		pushRedo();
		regions = undoStack.removeLast();
		updateUndoRedoButtons();
		status = Component.translatable("options.iris.undo");
	}

	private void redo() {
		if (redoStack.isEmpty()) {
			return;
		}
		pushUndoWithoutClearingRedo();
		regions = redoStack.removeLast();
		updateUndoRedoButtons();
		status = Component.translatable("options.iris.redo");
	}

	private void pushUndo() {
		pushUndoWithoutClearingRedo();
		redoStack.clear();
		updateUndoRedoButtons();
	}

	private void pushUndoWithoutClearingRedo() {
		undoStack.addLast(copyRegions(regions));
		while (undoStack.size() > MAX_HISTORY) {
			undoStack.removeFirst();
		}
	}

	private void pushRedo() {
		redoStack.addLast(copyRegions(regions));
		while (redoStack.size() > MAX_HISTORY) {
			redoStack.removeFirst();
		}
	}

	private void updateUndoRedoButtons() {
		if (undoButton != null) {
			undoButton.active = !undoStack.isEmpty();
		}
		if (redoButton != null) {
			redoButton.active = !redoStack.isEmpty();
		}
	}

	private boolean handleSidebarClick(double mouseX, double mouseY) {
		if (!sidebarContains(mouseX, mouseY)) {
			return false;
		}
		int listTop = TOP + 24;
		int index = (int) ((mouseY - listTop + sidebarScroll) / PROFILE_ROW_HEIGHT);
		if (index >= 0 && index < profiles.size()) {
			selectedProfileId = profiles.get(index).id;
			clearActiveDrawing();
			status = Component.translatable("options.iris.wynncraftAmbienceRegionSelectedProfile", selectedProfileId);
			GuiUtil.playButtonClickSound();
			return true;
		}
		return true;
	}

	private int countRegions(String profileId) {
		int count = 0;
		for (AmbienceRegion region : regions) {
			if (region != null && profileId.equals(region.profile)) {
				count++;
			}
		}
		return count;
	}

	private String nextRegionId() {
		String base = selectedProfileId == null || selectedProfileId.isBlank() ? "region" : selectedProfileId;
		base = base.replaceAll("[^A-Za-z0-9._-]", "_");
		int next = 1;
		String candidate;
		do {
			candidate = base + "_" + next++;
		} while (regionIdExists(candidate));
		return candidate;
	}

	private boolean regionIdExists(String id) {
		for (AmbienceRegion region : regions) {
			if (region != null && id.equals(region.id)) {
				return true;
			}
		}
		return false;
	}

	// Freeform paths are densely sampled, so simplify them with RDP -- but at a tolerance tied to the draw-time zoom
	// (a fixed screen-space distance) rather than a fixed world distance, so fine borders survive when zoomed in.
	private List<AmbienceRegion.Point> simplifyFreeform(List<AmbienceRegion.Point> points) {
		List<AmbienceRegion.Point> copied = copyPoints(points);
		if (copied.size() <= 3) {
			return copied;
		}
		double scale = Math.max(viewport().scale, 1.0e-4);
		double epsilon = FREEFORM_SIMPLIFY_TOLERANCE_PX / scale;
		List<AmbienceRegion.Point> simplified = ramerDouglasPeucker(copied, epsilon);
		// Only coarsen past the chosen tolerance if we blow the budget, and do it by re-running RDP (which keeps the
		// most significant corners) rather than blindly dropping every Nth point. With the budget raised this should
		// be unreachable for normal hand-drawn regions.
		int guard = 0;
		while (simplified.size() > MAX_FREEFORM_POINTS && guard++ < 40) {
			epsilon *= 1.5;
			simplified = ramerDouglasPeucker(copied, epsilon);
		}
		if (simplified.size() > MAX_FREEFORM_POINTS) {
			simplified = decimateByStride(simplified, MAX_FREEFORM_POINTS);
		}
		return simplified;
	}

	// Polygon vertices are placed one click at a time, so every vertex is intentional -- keep them all and only drop
	// coincident clicks. No RDP here: simplifying hand-placed corners is exactly the detail loss we want to avoid.
	private List<AmbienceRegion.Point> simplifyPolygon(List<AmbienceRegion.Point> points) {
		List<AmbienceRegion.Point> copied = copyPoints(points);
		if (copied.size() <= 3) {
			return copied;
		}
		double mergeWorld = POLYGON_MERGE_TOLERANCE_PX / Math.max(viewport().scale, 1.0e-4);
		double mergeSquared = mergeWorld * mergeWorld;
		List<AmbienceRegion.Point> out = new ArrayList<>();
		for (AmbienceRegion.Point point : copied) {
			if (out.isEmpty() || distanceSquared(out.get(out.size() - 1), point) >= mergeSquared) {
				out.add(point);
			}
		}
		if (out.size() > MAX_FREEFORM_POINTS) {
			out = decimateByStride(out, MAX_FREEFORM_POINTS);
		}
		return out;
	}

	private List<AmbienceRegion.Point> decimateByStride(List<AmbienceRegion.Point> points, int maxPoints) {
		if (points.size() <= maxPoints) {
			return points;
		}
		List<AmbienceRegion.Point> decimated = new ArrayList<>();
		int stride = (int) Math.ceil((double) points.size() / maxPoints);
		for (int i = 0; i < points.size(); i += stride) {
			decimated.add(points.get(i));
		}
		return decimated;
	}

	private List<AmbienceRegion.Point> ramerDouglasPeucker(List<AmbienceRegion.Point> points, double epsilon) {
		if (points.size() < 3) {
			return copyPoints(points);
		}
		double maxDistance = 0.0;
		int index = 0;
		AmbienceRegion.Point start = points.get(0);
		AmbienceRegion.Point end = points.get(points.size() - 1);
		for (int i = 1; i < points.size() - 1; i++) {
			double distance = perpendicularDistance(points.get(i), start, end);
			if (distance > maxDistance) {
				index = i;
				maxDistance = distance;
			}
		}
		if (maxDistance <= epsilon) {
			List<AmbienceRegion.Point> result = new ArrayList<>();
			result.add(start.copy());
			result.add(end.copy());
			return result;
		}
		List<AmbienceRegion.Point> result = ramerDouglasPeucker(points.subList(0, index + 1), epsilon);
		result.remove(result.size() - 1);
		result.addAll(ramerDouglasPeucker(points.subList(index, points.size()), epsilon));
		return result;
	}

	private double perpendicularDistance(AmbienceRegion.Point point, AmbienceRegion.Point start, AmbienceRegion.Point end) {
		double dx = end.x - start.x;
		double dz = end.z - start.z;
		double lengthSquared = dx * dx + dz * dz;
		if (lengthSquared == 0.0) {
			return Math.sqrt(distanceSquared(point, start));
		}
		double t = ((point.x - start.x) * dx + (point.z - start.z) * dz) / lengthSquared;
		double projectedX = start.x + t * dx;
		double projectedZ = start.z + t * dz;
		double diffX = point.x - projectedX;
		double diffZ = point.z - projectedZ;
		return Math.sqrt(diffX * diffX + diffZ * diffZ);
	}

	private double[] polygonBounds(List<AmbienceRegion.Point> points) {
		double minX = Double.POSITIVE_INFINITY;
		double minZ = Double.POSITIVE_INFINITY;
		double maxX = Double.NEGATIVE_INFINITY;
		double maxZ = Double.NEGATIVE_INFINITY;
		for (AmbienceRegion.Point point : points) {
			minX = Math.min(minX, point.x);
			minZ = Math.min(minZ, point.z);
			maxX = Math.max(maxX, point.x);
			maxZ = Math.max(maxZ, point.z);
		}
		return new double[] {minX, minZ, maxX, maxZ};
	}

	private List<AmbienceRegion.Point> boxPoints(AmbienceRegion.Point start, AmbienceRegion.Point end) {
		double minX = Math.min(start.x, end.x);
		double maxX = Math.max(start.x, end.x);
		double minZ = Math.min(start.z, end.z);
		double maxZ = Math.max(start.z, end.z);
		List<AmbienceRegion.Point> points = new ArrayList<>();
		points.add(new AmbienceRegion.Point(minX, minZ));
		points.add(new AmbienceRegion.Point(maxX, minZ));
		points.add(new AmbienceRegion.Point(maxX, maxZ));
		points.add(new AmbienceRegion.Point(minX, maxZ));
		return points;
	}

	private List<AmbienceRegion> copyRegions(List<AmbienceRegion> source) {
		List<AmbienceRegion> copies = new ArrayList<>();
		if (source != null) {
			for (AmbienceRegion region : source) {
				if (region != null) {
					copies.add(region.copy());
				}
			}
		}
		return copies;
	}

	private List<AmbienceRegion.Point> copyPoints(List<AmbienceRegion.Point> source) {
		List<AmbienceRegion.Point> copies = new ArrayList<>();
		for (AmbienceRegion.Point point : source) {
			if (point != null) {
				copies.add(point.copy());
			}
		}
		return copies;
	}

	private double distanceSquared(AmbienceRegion.Point a, AmbienceRegion.Point b) {
		double dx = a.x - b.x;
		double dz = a.z - b.z;
		return dx * dx + dz * dz;
	}

	private boolean sidebarContains(double mouseX, double mouseY) {
		return mouseX >= sidebarX() && mouseX <= sidebarX() + sidebarWidth() && mouseY >= TOP && mouseY <= listBottom();
	}

	private boolean mapAreaContains(double mouseX, double mouseY) {
		MapViewport viewport = viewport();
		return mouseX >= viewport.x && mouseX <= viewport.x + viewport.width && mouseY >= viewport.y && mouseY <= viewport.y + viewport.height;
	}

	private double maxSidebarScroll() {
		int visibleHeight = Math.max(0, listBottom() - TOP - 28);
		int contentHeight = profiles.size() * PROFILE_ROW_HEIGHT;
		return Math.max(0, contentHeight - visibleHeight);
	}

	private int sidebarX() {
		return SIDE_MARGIN;
	}

	private int sidebarWidth() {
		return Math.min(Math.max(this.width / 4, 150), 360);
	}

	private int listBottom() {
		return this.height - BOTTOM_CONTROLS_HEIGHT;
	}

	private MapViewport viewport() {
		int x = sidebarX() + sidebarWidth() + GAP;
		int y = TOP;
		int width = Math.max(1, this.width - x - SIDE_MARGIN);
		int height = Math.max(1, this.height - TOP - BOTTOM_CONTROLS_HEIGHT);
		AmbienceMapTileLoader.Bounds bounds = mapTiles == null ? AmbienceMapTileLoader.Bounds.empty() : mapTiles.bounds();
		double fitScale = Math.min(width / bounds.width(), height / bounds.height());
		double scale = Math.max(0.0001, fitScale * zoom);
		double renderedWidth = bounds.width() * scale;
		double renderedHeight = bounds.height() * scale;
		double mapX = x + (width - renderedWidth) / 2.0 + panX;
		double mapY = y + (height - renderedHeight) / 2.0 + panY;
		return new MapViewport(x, y, width, height, bounds, scale, mapX, mapY);
	}

	private double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	private void drawCenteredTruncated(GuiGraphics guiGraphics, Component component, int y, int color) {
		Component rendered = component;
		if (this.font.width(component) > this.width - 20) {
			rendered = Component.literal(this.font.plainSubstrByWidth(component.getString(), this.width - 32) + "...").setStyle(component.getStyle());
		}
		guiGraphics.drawCenteredString(this.font, rendered, (int) (this.width * 0.5), y, color);
	}

	private enum Tool {
		FREEFORM("options.iris.wynncraftAmbienceRegionToolFreeform"),
		POLYGON("options.iris.wynncraftAmbienceRegionToolPolygon"),
		BOX("options.iris.wynncraftAmbienceRegionToolBox"),
		PRIORITY("options.iris.wynncraftAmbienceRegionToolPriority"),
		ERASE("options.iris.wynncraftAmbienceRegionToolErase");

		private final String translationKey;

		Tool(String translationKey) {
			this.translationKey = translationKey;
		}
	}

	private record MapViewport(int x, int y, int width, int height, AmbienceMapTileLoader.Bounds bounds, double scale,
							   double mapX, double mapY) {
		double screenX(double worldX) {
			return mapX + (worldX - bounds.minX()) * scale;
		}

		double screenY(double worldZ) {
			return mapY + (worldZ - bounds.minZ()) * scale;
		}

		AmbienceRegion.Point toWorld(double screenX, double screenY) {
			return new AmbienceRegion.Point((screenX - mapX) / scale + bounds.minX(), (screenY - mapY) / scale + bounds.minZ());
		}
	}

	private record ScreenLine(double x1, double y1, double x2, double y2) {
	}
}
