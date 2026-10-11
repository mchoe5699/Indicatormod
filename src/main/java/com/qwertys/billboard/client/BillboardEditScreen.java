package com.qwertys.billboard.client;

import com.qwertys.billboard.block.BillboardBlock;
import com.qwertys.billboard.block.BillboardBlockEntity;
import com.qwertys.billboard.data.DisplayPart;
import com.qwertys.billboard.network.EditOps;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/**
 * 전광판 편집 창. 게임을 멈추지 않고, 바꾸는 즉시 월드에서 미리 보인다.
 * 왼쪽: 오브젝트 목록과 이미지 추가/교체/분리/복제/삭제
 * 가운데: 위치·회전·크기·두께 (선택한 오브젝트 또는 [전체])
 * 오른쪽: 2D/3D, 발광, 색상 모드(무지개 등), 투명도, 블록 빛, 기준 방향
 */
public class BillboardEditScreen extends Screen {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "QWERTY's Billboard image worker");
        t.setDaemon(true);
        return t;
    });

    private static final double[] MOVE_STEPS = {0.001, 0.01, 0.0625, 0.1, 0.5, 1.0};
    private static final double[] ROT_STEPS = {0.1, 1, 5, 15, 45, 90};
    private static final int[] RESOLUTIONS = {64, 128, 256, 512};
    private static final int[] TOLERANCES = {10, 25, 40, 60, 90};
    private static final int SPLIT_MIN_AREA = 6;
    private static final int SPLIT_MAX_PIECES = 64;

    // 창을 다시 열어도 유지되는 설정
    private static int moveStep = 1, rotStep = 1, resIdx = 1, tolIdx = 2;
    private static boolean useMm = false, removeBg = false;

    private final BlockPos pos;
    private BillboardBlockEntity be;
    private int sel = -1;         // -1 = [전체]
    private int scroll = 0;
    private int lastRevision = Integer.MIN_VALUE, lastCount = -1;
    private int pendingSelect = -1;

    private boolean dirtyPart, dirtyGroup, dirtyLight;
    private int dirtyPartIndex = -1, dirtyTimer, pendingLight;
    private final CompoundTag pendingAll = new CompoundTag();
    private boolean busy;
    private Component status = Component.empty();
    private int statusColor = 0xFFFFFF;
    private long statusUntil;

    private final List<NumField> fields = new ArrayList<>();
    private final List<Button> listButtons = new ArrayList<>();
    private NumField fX, fY, fZ, fYaw, fPitch, fRoll, fSize, fScale, fDepth, fSpeed, fSpread, fOpacity, fLight;
    private EditBox nameBox, colorBox;
    private Button btn3d, btnGlow, btnVisible, btnColorMode, btnFacing;
    private Button btnMoveStep, btnRotStep, btnUnit, btnReset, btnRes, btnBg, btnTol;
    private Button btnAdd, btnReplace, btnSplit, btnDup, btnDel, btnUp, btnDown;
    private boolean suppress;

    // 레이아웃
    private static final int ROW = 19, H = 16, LABEL_W = 44;
    private int leftX = 4, leftW = 124, colAX, colBX, colW, listTop, listBottom;

    public BillboardEditScreen(BlockPos pos) {
        super(tr("title"));
        this.pos = pos;
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("gui.qwertys_billboard." + key, args);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Nullable
    private BillboardBlockEntity fetch() {
        if (minecraft == null || minecraft.level == null) return null;
        return minecraft.level.getBlockEntity(pos) instanceof BillboardBlockEntity b ? b : null;
    }

    @Nullable
    private DisplayPart part() {
        if (be == null || sel < 0 || sel >= be.parts.size()) return null;
        return be.parts.get(sel);
    }

    /** 외형 값을 보여줄 오브젝트 ([전체] 선택 시 첫 번째) */
    @Nullable
    private DisplayPart appearanceSource() {
        if (be == null || be.parts.isEmpty()) return null;
        DisplayPart p = part();
        return p != null ? p : be.parts.get(0);
    }

    // ───────────────────────── 초기화 ─────────────────────────

    @Override
    protected void init() {
        be = fetch();
        if (be == null) {
            onClose();
            return;
        }
        fields.clear();
        listButtons.clear();

        leftW = Mth.clamp(width / 4, 110, 140);
        colW = Math.min(160, (width - leftW - 20) / 2);
        colAX = leftX + leftW + 6;
        colBX = colAX + colW + 6;
        int y0 = 16;
        int half = colW / 2 - 1;

        // ── 가운데: 변형 ──
        fX = addField(colAX, y0, "x", Kind.LEN, -DisplayPart.MAX_OFFSET, DisplayPart.MAX_OFFSET,
                () -> part() != null ? part().x : be.gx, v -> setTransform(p -> p.x = (float) v, () -> be.gx = (float) v));
        fY = addField(colAX, y0 + ROW, "y", Kind.LEN, -DisplayPart.MAX_OFFSET, DisplayPart.MAX_OFFSET,
                () -> part() != null ? part().y : be.gy, v -> setTransform(p -> p.y = (float) v, () -> be.gy = (float) v));
        fZ = addField(colAX, y0 + ROW * 2, "z", Kind.LEN, -DisplayPart.MAX_OFFSET, DisplayPart.MAX_OFFSET,
                () -> part() != null ? part().z : be.gz, v -> setTransform(p -> p.z = (float) v, () -> be.gz = (float) v));
        fYaw = addField(colAX, y0 + ROW * 3, "yaw", Kind.ROT, -180, 180,
                () -> part() != null ? part().yaw : be.gyaw, v -> setTransform(p -> p.yaw = (float) v, () -> be.gyaw = (float) v));
        fPitch = addField(colAX, y0 + ROW * 4, "pitch", Kind.ROT, -180, 180,
                () -> part() != null ? part().pitch : be.gpitch, v -> setTransform(p -> p.pitch = (float) v, () -> be.gpitch = (float) v));
        fRoll = addField(colAX, y0 + ROW * 5, "roll", Kind.ROT, -180, 180,
                () -> part() != null ? part().roll : be.groll, v -> setTransform(p -> p.roll = (float) v, () -> be.groll = (float) v));
        fSize = addField(colAX, y0 + ROW * 6, "size", Kind.LEN, DisplayPart.MIN_SIZE, DisplayPart.MAX_SIZE,
                () -> part() != null ? part().size : 1, v -> setTransform(p -> p.size = (float) v, () -> {}));
        fScale = addField(colAX, y0 + ROW * 6, "scale", Kind.SCALE, 0.001, 64,
                () -> be.gscale, v -> setTransform(p -> {}, () -> be.gscale = (float) v));
        fDepth = addField(colAX, y0 + ROW * 7, "depth", Kind.LEN, DisplayPart.MIN_DEPTH, DisplayPart.MAX_DEPTH,
                () -> part() != null ? part().depth : 0.0625, v -> setTransform(p -> p.depth = (float) v, () -> {}));

        btnMoveStep = addRenderableWidget(Button.builder(Component.empty(), b -> {
            moveStep = (moveStep + 1) % MOVE_STEPS.length;
            refreshButtons();
        }).bounds(colAX, y0 + ROW * 8, half, H).tooltip(Tooltip.create(tr("tip.move_step"))).build());
        btnRotStep = addRenderableWidget(Button.builder(Component.empty(), b -> {
            rotStep = (rotStep + 1) % ROT_STEPS.length;
            refreshButtons();
        }).bounds(colAX + half + 2, y0 + ROW * 8, half, H).tooltip(Tooltip.create(tr("tip.rot_step"))).build());
        btnUnit = addRenderableWidget(Button.builder(Component.empty(), b -> {
            useMm = !useMm;
            refreshFields();
        }).bounds(colAX, y0 + ROW * 9, half, H).build());
        btnReset = addRenderableWidget(Button.builder(tr("reset"), b -> resetTransform())
                .bounds(colAX + half + 2, y0 + ROW * 9, half, H).tooltip(Tooltip.create(tr("tip.reset"))).build());

        // ── 오른쪽: 외형 ──
        nameBox = new EditBox(font, colBX + LABEL_W, y0, colW - LABEL_W, H, tr("name"));
        nameBox.setMaxLength(64);
        nameBox.setResponder(s -> {
            if (suppress) return;
            DisplayPart p = part();
            if (p != null) {
                p.name = s;
                markPartDirty();
                updateListLabels();
            }
        });
        addRenderableWidget(nameBox);

        btn3d = addRenderableWidget(Button.builder(Component.empty(), b -> {
            boolean v = !current().mode3d;
            setAppearance("Mode3D", p -> p.mode3d = v);
        })
                .bounds(colBX, y0 + ROW, half, H).tooltip(Tooltip.create(tr("tip.mode3d"))).build());
        btnVisible = addRenderableWidget(Button.builder(Component.empty(), b -> {
            boolean v = !current().visible;
            setAppearance("Visible", p -> p.visible = v);
        })
                .bounds(colBX + half + 2, y0 + ROW, half, H).build());
        btnGlow = addRenderableWidget(Button.builder(Component.empty(), b -> {
            boolean v = !current().glow;
            setAppearance("Glow", p -> p.glow = v);
        })
                .bounds(colBX, y0 + ROW * 2, half, H).tooltip(Tooltip.create(tr("tip.glow"))).build());
        btnFacing = addRenderableWidget(Button.builder(Component.empty(), b -> rotateFacing())
                .bounds(colBX + half + 2, y0 + ROW * 2, half, H).tooltip(Tooltip.create(tr("tip.facing"))).build());
        fLight = addField(colBX, y0 + ROW * 3, "light", Kind.INT, 0, 15, () -> dirtyLight ? pendingLight : blockLight(), v -> {
            pendingLight = (int) Math.round(v);
            dirtyLight = true;
            dirtyTimer = 0;
        });
        btnColorMode = addRenderableWidget(Button.builder(Component.empty(), b -> {
            var next = current().colorMode.next();
            setAppearance("ColorMode", p -> p.colorMode = next);
        }).bounds(colBX, y0 + ROW * 4, colW, H).tooltip(Tooltip.create(tr("tip.color_mode"))).build());
        colorBox = new EditBox(font, colBX + LABEL_W, y0 + ROW * 5, colW - LABEL_W - 18, H, tr("color"));
        colorBox.setMaxLength(7);
        colorBox.setFilter(s -> s.matches("#?[0-9a-fA-F]{0,6}"));
        colorBox.setResponder(s -> {
            if (suppress) return;
            String hex = s.startsWith("#") ? s.substring(1) : s;
            if (hex.length() == 6) {
                int c = Integer.parseInt(hex, 16);
                setAppearance("Color", p -> p.color = c);
            }
        });
        addRenderableWidget(colorBox);
        fSpeed = addField(colBX, y0 + ROW * 6, "speed", Kind.PLAIN, -20, 20,
                () -> current() != null ? current().speed : 0.25, v -> setAppearance("Speed", p -> p.speed = (float) v));
        fSpread = addField(colBX, y0 + ROW * 7, "spread", Kind.PLAIN, -16, 16,
                () -> current() != null ? current().spread : 1, v -> setAppearance("Spread", p -> p.spread = (float) v));
        fOpacity = addField(colBX, y0 + ROW * 8, "opacity", Kind.PERCENT, 0, 1,
                () -> current() != null ? current().opacity : 1, v -> setAppearance("Opacity", p -> p.opacity = (float) v));

        // ── 왼쪽: 목록과 이미지 작업 ──
        int bh = H + 2;
        int third = (leftW - 4) / 3;
        int by = height - 4 - bh * 4;
        btnAdd = addRenderableWidget(Button.builder(tr("add"), b -> openDialog(false))
                .bounds(leftX, by, leftW / 2 - 1, H).tooltip(Tooltip.create(tr("tip.add"))).build());
        btnReplace = addRenderableWidget(Button.builder(tr("replace"), b -> openDialog(true))
                .bounds(leftX + leftW / 2 + 1, by, leftW / 2 - 1, H).tooltip(Tooltip.create(tr("tip.replace"))).build());
        btnSplit = addRenderableWidget(Button.builder(tr("split"), b -> splitSelected())
                .bounds(leftX, by + bh, leftW / 2 - 1, H).tooltip(Tooltip.create(tr("tip.split"))).build());
        btnDup = addRenderableWidget(Button.builder(tr("duplicate"), b -> simpleOp(EditOps.DUPLICATE_PART, 0, sel + 1))
                .bounds(leftX + leftW / 2 + 1, by + bh, leftW / 2 - 1, H).build());
        btnDel = addRenderableWidget(Button.builder(tr("delete"), b -> deleteSelected())
                .bounds(leftX, by + bh * 2, third, H).tooltip(Tooltip.create(tr("tip.delete"))).build());
        btnUp = addRenderableWidget(Button.builder(Component.literal("▲"), b -> simpleOp(EditOps.MOVE_PART, -1, sel - 1))
                .bounds(leftX + third + 2, by + bh * 2, third, H).build());
        btnDown = addRenderableWidget(Button.builder(Component.literal("▼"), b -> simpleOp(EditOps.MOVE_PART, 1, sel + 1))
                .bounds(leftX + third * 2 + 4, by + bh * 2, leftW - third * 2 - 4, H).build());
        btnRes = addRenderableWidget(Button.builder(Component.empty(), b -> {
            resIdx = (resIdx + 1) % RESOLUTIONS.length;
            refreshButtons();
        }).bounds(leftX, by + bh * 3, third, H).tooltip(Tooltip.create(tr("tip.resolution"))).build());
        btnBg = addRenderableWidget(Button.builder(Component.empty(), b -> {
            removeBg = !removeBg;
            refreshButtons();
        }).bounds(leftX + third + 2, by + bh * 3, third, H).tooltip(Tooltip.create(tr("tip.remove_bg"))).build());
        btnTol = addRenderableWidget(Button.builder(Component.empty(), b -> {
            tolIdx = (tolIdx + 1) % TOLERANCES.length;
            refreshButtons();
        }).bounds(leftX + third * 2 + 4, by + bh * 3, leftW - third * 2 - 4, H).tooltip(Tooltip.create(tr("tip.tolerance"))).build());

        listTop = 16;
        listBottom = by - 4;

        lastRevision = be.revision;
        lastCount = be.parts.size();
        if (sel >= lastCount) sel = lastCount - 1;
        rebuildList();
        refreshFields();
    }

    // ───────────────────────── 숫자 입력칸 ─────────────────────────

    private enum Kind {LEN, ROT, SCALE, PLAIN, PERCENT, INT}

    private final class NumField {
        final String key;
        final Kind kind;
        final double min, max;
        final DoubleSupplier get;
        final DoubleConsumer set;
        final int x, y;
        final Button minus, plus;
        final EditBox box;

        NumField(int x, int y, String key, Kind kind, double min, double max, DoubleSupplier get, DoubleConsumer set) {
            this.x = x;
            this.y = y;
            this.key = key;
            this.kind = kind;
            this.min = min;
            this.max = max;
            this.get = get;
            this.set = set;
            int bx = x + LABEL_W;
            int boxW = colW - LABEL_W - 26;
            minus = Button.builder(Component.literal("-"), b -> nudge(-1)).bounds(bx, y, 12, H).build();
            box = new EditBox(font, bx + 13, y, boxW, H, tr("field." + key));
            plus = Button.builder(Component.literal("+"), b -> nudge(1)).bounds(bx + 14 + boxW, y, 12, H).build();
            box.setMaxLength(16);
            box.setFilter(s -> s.matches("[-0-9.,]*"));
            box.setResponder(s -> {
                if (suppress || be == null) return;
                try {
                    double v = Double.parseDouble(s.replace(',', '.'));
                    if (Double.isFinite(v)) set.accept(clamp(fromDisplay(v)));
                } catch (NumberFormatException ignored) {
                }
            });
        }

        double toDisplay(double v) {
            return switch (kind) {
                case LEN -> useMm ? v * 1000.0 : v;
                case PERCENT -> v * 100.0;
                default -> v;
            };
        }

        double fromDisplay(double v) {
            return switch (kind) {
                case LEN -> useMm ? v / 1000.0 : v;
                case PERCENT -> v / 100.0;
                default -> v;
            };
        }

        double step() {
            return switch (kind) {
                case LEN -> MOVE_STEPS[moveStep];
                case ROT -> ROT_STEPS[rotStep];
                case SCALE -> 0.05;
                case PLAIN -> 0.05;
                case PERCENT -> 0.05;
                case INT -> 1;
            };
        }

        double clamp(double v) {
            if (kind == Kind.ROT) return Mth.wrapDegrees(v);
            if (kind == Kind.INT) v = Math.round(v);
            return Mth.clamp(v, min, max);
        }

        void nudge(int dir) {
            if (be == null) return;
            double v = get.getAsDouble() + dir * step();
            // 단계 배수로 깔끔하게 맞춤 (부동소수 오차 제거)
            double s = step();
            v = Math.round(v / s) * s;
            set.accept(clamp(v));
            refresh();
        }

        void refresh() {
            suppress = true;
            box.setValue(format(toDisplay(get.getAsDouble()), kind == Kind.INT ? 0 : (kind == Kind.LEN && useMm ? 2 : 4)));
            box.moveCursorToStart();
            suppress = false;
        }

        void setVisible(boolean v) {
            minus.visible = plus.visible = box.visible = v;
        }

        void setActive(boolean a) {
            minus.active = plus.active = a;
            box.setEditable(a);
        }

        boolean visible() {
            return box.visible;
        }

        Component label() {
            Component base = tr("field." + key);
            if (kind == Kind.LEN) return Component.empty().append(base).append(Component.literal(useMm ? " mm" : ""));
            return base;
        }
    }

    private NumField addField(int x, int y, String key, Kind kind, double min, double max, DoubleSupplier get, DoubleConsumer set) {
        NumField f = new NumField(x, y, key, kind, min, max, get, set);
        addRenderableWidget(f.minus);
        addRenderableWidget(f.box);
        addRenderableWidget(f.plus);
        fields.add(f);
        return f;
    }

    private static String format(double v, int decimals) {
        if (!Double.isFinite(v)) return "0";
        return new BigDecimal(v).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    // ───────────────────────── 값 변경 ─────────────────────────

    private DisplayPart current() {
        DisplayPart p = appearanceSource();
        return p != null ? p : new DisplayPart();
    }

    private void setTransform(Consumer<DisplayPart> partSetter, Runnable groupSetter) {
        if (be == null) return;
        DisplayPart p = part();
        if (p != null) {
            partSetter.accept(p);
            markPartDirty();
        } else {
            groupSetter.run();
            dirtyGroup = true;
            dirtyTimer = 0;
        }
        be.invalidateRenderBox();
    }

    /** 외형 변경: 오브젝트 하나 또는 [전체] 선택 시 모든 오브젝트에 적용 */
    private void setAppearance(String key, Consumer<DisplayPart> setter) {
        if (be == null || be.parts.isEmpty()) return;
        DisplayPart p = part();
        if (p != null) {
            setter.accept(p);
            markPartDirty();
        } else {
            for (DisplayPart each : be.parts) setter.accept(each);
            CompoundTag full = be.parts.get(0).saveAppearance(new CompoundTag());
            if (full.contains(key)) pendingAll.put(key, full.get(key).copy());
            dirtyTimer = 0;
        }
        refreshButtons();
    }

    private void markPartDirty() {
        if (dirtyPart && dirtyPartIndex != sel) flush();
        dirtyPart = true;
        dirtyPartIndex = sel;
        dirtyTimer = 0;
    }

    /** 대기 중인 변경 사항을 서버로 보냄 */
    private void flush() {
        if (be == null) return;
        if (dirtyPart) {
            dirtyPart = false;
            if (dirtyPartIndex >= 0 && dirtyPartIndex < be.parts.size()) {
                CompoundTag op = new CompoundTag();
                op.putInt("Index", dirtyPartIndex);
                DisplayPart p = be.parts.get(dirtyPartIndex);
                CompoundTag t = p.save(new CompoundTag(), false);
                op.put("Part", t);
                EditSender.send(pos, EditOps.SET_PART, op);
            }
        }
        if (dirtyGroup) {
            dirtyGroup = false;
            CompoundTag op = new CompoundTag();
            op.put("Group", be.saveGroup(new CompoundTag()));
            EditSender.send(pos, EditOps.SET_GROUP, op);
        }
        if (!pendingAll.isEmpty()) {
            CompoundTag op = new CompoundTag();
            op.put("Appearance", pendingAll.copy());
            for (String k : new ArrayList<>(pendingAll.getAllKeys())) pendingAll.remove(k);
            EditSender.send(pos, EditOps.SET_APPEARANCE_ALL, op);
        }
        if (dirtyLight) {
            dirtyLight = false;
            CompoundTag op = new CompoundTag();
            op.putInt("Light", pendingLight);
            EditSender.send(pos, EditOps.SET_LIGHT, op);
        }
    }

    private int blockLight() {
        BlockState s = be != null ? be.getBlockState() : null;
        return s != null && s.hasProperty(BillboardBlock.LIGHT) ? s.getValue(BillboardBlock.LIGHT) : 0;
    }

    private Direction facing() {
        BlockState s = be != null ? be.getBlockState() : null;
        return s != null && s.hasProperty(BillboardBlock.FACING) ? s.getValue(BillboardBlock.FACING) : Direction.NORTH;
    }

    private void rotateFacing() {
        Direction next = facing().getClockWise();
        CompoundTag op = new CompoundTag();
        op.putInt("Facing", next.get2DDataValue());
        EditSender.send(pos, EditOps.SET_FACING, op);
        btnFacing.setMessage(tr("facing", tr("dir." + next.getName())));
    }

    private void resetTransform() {
        if (be == null) return;
        DisplayPart p = part();
        if (p != null) {
            p.x = p.y = p.z = 0;
            p.yaw = p.pitch = p.roll = 0;
            markPartDirty();
        } else {
            be.gx = be.gy = be.gz = 0;
            be.gyaw = be.gpitch = be.groll = 0;
            be.gscale = 1;
            dirtyGroup = true;
            dirtyTimer = 0;
        }
        be.invalidateRenderBox();
        refreshFields();
    }

    // ───────────────────────── 오브젝트 작업 ─────────────────────────

    private void select(int index) {
        flush();
        sel = index;
        rebuildList();
        refreshFields();
    }

    private void simpleOp(String op, int dir, int selectAfter) {
        if (part() == null) return;
        flush();
        CompoundTag t = new CompoundTag();
        t.putInt("Index", sel);
        t.putInt("Dir", dir);
        EditSender.send(pos, op, t);
        if (op.equals(EditOps.MOVE_PART)) {
            sel = Mth.clamp(selectAfter, 0, be.parts.size() - 1);
            rebuildList();
        } else {
            pendingSelect = selectAfter;
        }
    }

    private void deleteSelected() {
        flush();
        if (part() != null) {
            CompoundTag t = new CompoundTag();
            t.putInt("Index", sel);
            EditSender.send(pos, EditOps.REMOVE_PART, t);
        } else if (hasShiftDown()) {
            EditSender.send(pos, EditOps.CLEAR);
        } else {
            setStatus(tr("status.shift_clear"), 0xFFD27F);
        }
    }

    private void openDialog(boolean replace) {
        if (busy) return;
        if (replace && part() == null) {
            setStatus(tr("status.select_first"), 0xFFD27F);
            return;
        }
        busy = true;
        setStatus(tr("status.choosing"), 0xFFFFFF);
        String title = tr("dialog_title").getString();
        String desc = tr("dialog_filter").getString();
        CompletableFuture.supplyAsync(() -> FileDialogs.openImages(title, desc), WORKER)
                .whenComplete((paths, err) -> Minecraft.getInstance().execute(() -> {
                    busy = false;
                    if (err != null || paths == null || paths.isEmpty()) {
                        setStatus(err != null ? tr("status.dialog_failed") : Component.empty(), 0xFF8080);
                        return;
                    }
                    importPaths(paths, replace);
                }));
    }

    @Override
    public void onFilesDrop(List<Path> paths) {
        importPaths(paths, false);
    }

    private void importPaths(List<Path> paths, boolean replace) {
        if (busy || paths.isEmpty()) return;
        busy = true;
        setStatus(tr("status.loading", paths.size()), 0xFFFFFF);
        ImageImporter.Options opt = new ImageImporter.Options(RESOLUTIONS[resIdx], removeBg, TOLERANCES[tolIdx]);
        final int replaceIndex = sel;
        final BlockPos at = pos;
        CompletableFuture.supplyAsync(() -> {
            List<Object> results = new ArrayList<>();
            for (Path p : paths) {
                try {
                    results.add(ImageImporter.load(p, opt));
                } catch (Exception e) {
                    results.add(p.getFileName() + ": " + describe(e));
                }
            }
            return results;
        }, WORKER).whenComplete((results, err) -> Minecraft.getInstance().execute(() -> {
            busy = false;
            if (err != null || results == null) {
                setStatus(tr("status.load_failed", String.valueOf(err)), 0xFF8080);
                return;
            }
            List<ImageImporter.Result> ok = new ArrayList<>();
            List<String> failed = new ArrayList<>();
            for (Object o : results) {
                if (o instanceof ImageImporter.Result r) ok.add(r);
                else failed.add(String.valueOf(o));
            }
            if (!ok.isEmpty()) sendImported(at, ok, replace, replaceIndex);
            if (!failed.isEmpty()) setStatus(tr("status.load_failed", String.join(", ", failed)), 0xFF8080);
        }));
    }

    private static String describe(Exception e) {
        String m = e.getMessage();
        if ("unsupported".equals(m)) return tr("err.unsupported").getString();
        if ("empty".equals(m)) return tr("err.empty").getString();
        return m == null ? e.getClass().getSimpleName() : m;
    }

    private void sendImported(BlockPos at, List<ImageImporter.Result> results, boolean replace, int replaceIndex) {
        ListTag list = new ListTag();
        float cursor = 0;
        float totalW = 0;
        for (ImageImporter.Result r : results) totalW += defaultWidth(r) + 0.1f;
        totalW -= 0.1f;
        for (ImageImporter.Result r : results) {
            DisplayPart p = new DisplayPart();
            p.setImage(r.png(), r.width(), r.height());
            p.name = r.name();
            p.size = defaultWidth(r);
            // 여러 장을 동시에 추가하면 옆으로 나란히 배치
            p.x = results.size() > 1 ? cursor + p.size / 2f - totalW / 2f : 0;
            cursor += p.size + 0.1f;
            list.add(p.save(new CompoundTag(), true));
        }
        CompoundTag op = new CompoundTag();
        op.put("Parts", list);
        int bytes;
        if (replace) {
            op.putInt("Index", replaceIndex);
            bytes = EditSender.send(at, EditOps.REPLACE_IMAGE, op);
        } else {
            if (be != null) pendingSelect = be.parts.size();
            bytes = EditSender.send(at, EditOps.ADD_PARTS, op);
        }
        if (bytes < 0) setStatus(tr("status.too_big"), 0xFF8080);
        else setStatus(tr("status.sent", results.size(), Math.max(1, bytes / 1024)), 0x80FF80);
    }

    /** 긴 변이 1블록이 되도록 */
    private static float defaultWidth(ImageImporter.Result r) {
        return r.width() >= r.height() ? 1f : r.width() / (float) r.height();
    }

    private void splitSelected() {
        DisplayPart src = part();
        if (src == null || busy) {
            if (src == null) setStatus(tr("status.select_first"), 0xFFD27F);
            return;
        }
        flush();
        busy = true;
        setStatus(tr("status.splitting"), 0xFFFFFF);
        final int index = sel;
        final DisplayPart base = src.copy();
        final BlockPos at = pos;
        CompletableFuture.supplyAsync(() -> {
            try {
                return ComponentSplitter.split(base.image, SPLIT_MIN_AREA, SPLIT_MAX_PIECES);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, WORKER).whenComplete((pieces, err) -> Minecraft.getInstance().execute(() -> {
            busy = false;
            if (err != null || pieces == null) {
                setStatus(tr("status.split_failed"), 0xFF8080);
                return;
            }
            if (pieces.size() <= 1) {
                setStatus(tr("status.nothing_to_split"), 0xFFD27F);
                return;
            }
            ListTag list = new ListTag();
            float pix = base.size / base.imgW;
            var rot = BillboardRenderer.euler(base.yaw, base.pitch, base.roll);
            int i = 1;
            for (ComponentSplitter.Piece piece : pieces) {
                DisplayPart np = base.copy();
                np.setImage(piece.png(), piece.width(), piece.height());
                np.size = Math.max(DisplayPart.MIN_SIZE, piece.width() * pix);
                // 원래 이미지 안에서의 위치를 그대로 유지 (오브젝트 회전 반영)
                Vector3f d = new Vector3f((piece.cx() - base.imgW / 2f) * pix, (base.imgH / 2f - piece.cy()) * pix, 0).rotate(rot);
                np.x = base.x + d.x;
                np.y = base.y + d.y;
                np.z = base.z + d.z;
                np.name = (base.name.isEmpty() ? "obj" : base.name) + " #" + i++;
                list.add(np.save(new CompoundTag(), true));
            }
            CompoundTag op = new CompoundTag();
            op.putInt("Index", index);
            op.put("Parts", list);
            int bytes = EditSender.send(at, EditOps.REPLACE_PART, op);
            if (bytes < 0) setStatus(tr("status.too_big"), 0xFF8080);
            else {
                pendingSelect = index;
                setStatus(tr("status.split_done", pieces.size()), 0x80FF80);
            }
        }));
    }

    // ───────────────────────── 목록 ─────────────────────────

    private int visibleRows() {
        return Math.max(1, (listBottom - listTop) / 18);
    }

    private void rebuildList() {
        for (Button b : listButtons) removeWidget(b);
        listButtons.clear();
        if (be == null) return;
        int entries = be.parts.size() + 1;
        int rows = visibleRows();
        scroll = Mth.clamp(scroll, 0, Math.max(0, entries - rows));
        for (int r = 0; r < rows && scroll + r < entries; r++) {
            int entry = scroll + r;
            int index = entry - 1;
            Button b = Button.builder(Component.empty(), btn -> select(index))
                    .bounds(leftX, listTop + r * 18, leftW, H).build();
            listButtons.add(addRenderableWidget(b));
        }
        updateListLabels();
    }

    private void updateListLabels() {
        if (be == null) return;
        for (int r = 0; r < listButtons.size(); r++) {
            int index = scroll + r - 1;
            Component label;
            if (index < 0) label = tr("all", be.parts.size());
            else if (index < be.parts.size()) {
                DisplayPart p = be.parts.get(index);
                String name = p.name.isEmpty() ? tr("unnamed").getString() : p.name;
                String text = (index + 1) + ". " + name;
                text = font.plainSubstrByWidth(text, leftW - 14);
                label = Component.literal(text).withStyle(p.visible ? ChatFormatting.WHITE : ChatFormatting.GRAY);
            } else label = Component.empty();
            if (index == sel) label = Component.literal("▶ ").withStyle(ChatFormatting.YELLOW).append(label);
            listButtons.get(r).setMessage(label);
        }
    }

    // ───────────────────────── 화면 갱신 ─────────────────────────

    private void refreshFields() {
        if (be == null) return;
        boolean group = part() == null;
        boolean hasParts = !be.parts.isEmpty();
        fSize.setVisible(!group);
        fScale.setVisible(group);
        fDepth.setVisible(!group);
        nameBox.visible = !group;
        for (NumField f : fields) if (f.visible()) f.refresh();
        suppress = true;
        DisplayPart p = part();
        nameBox.setValue(p != null ? p.name : "");
        colorBox.setValue(String.format(Locale.ROOT, "#%06X", current().color & 0xFFFFFF));
        suppress = false;
        for (NumField f : List.of(fSpeed, fSpread, fOpacity)) f.setActive(hasParts);
        refreshButtons();
    }

    private void refreshButtons() {
        if (be == null) return;
        DisplayPart c = current();
        boolean hasParts = !be.parts.isEmpty();
        boolean single = part() != null;
        btn3d.setMessage(tr("mode", c.mode3d ? "3D" : "2D"));
        btnGlow.setMessage(tr("glow", onOff(c.glow)));
        btnVisible.setMessage(tr("visible", onOff(c.visible)));
        btnColorMode.setMessage(tr("color_mode", tr("color_mode." + c.colorMode.key)));
        btnFacing.setMessage(tr("facing", tr("dir." + facing().getName())));
        double ms = MOVE_STEPS[moveStep];
        btnMoveStep.setMessage(tr("move_step", ms < 1 ? format(ms * 1000, 1) + "mm" : format(ms, 2) + tr("block_unit").getString()));
        btnRotStep.setMessage(tr("rot_step", format(ROT_STEPS[rotStep], 1) + "°"));
        btnUnit.setMessage(tr("unit", useMm ? Component.literal("mm") : tr("block_unit")));
        btnRes.setMessage(Component.literal(RESOLUTIONS[resIdx] + "px"));
        btnBg.setMessage(tr("bg", onOff(removeBg)));
        btnTol.setMessage(tr("tol", TOLERANCES[tolIdx]));
        btnTol.active = removeBg;
        for (Button b : List.of(btn3d, btnGlow, btnVisible, btnColorMode)) b.active = hasParts;
        colorBox.setEditable(hasParts);
        btnReplace.active = single;
        btnSplit.active = single;
        btnDup.active = single;
        btnUp.active = single && sel > 0;
        btnDown.active = single && sel < be.parts.size() - 1;
        btnDel.active = single || hasParts;
        btnDel.setMessage(single ? tr("delete") : tr("delete_all"));
    }

    private static Component onOff(boolean v) {
        return v ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF;
    }

    private void setStatus(Component c, int color) {
        status = c;
        statusColor = color;
        statusUntil = System.currentTimeMillis() + 8000;
    }

    private boolean anyFocused() {
        if (nameBox.isFocused() || colorBox.isFocused()) return true;
        for (NumField f : fields) if (f.box.isFocused()) return true;
        return false;
    }

    @Override
    public void tick() {
        be = fetch();
        if (be == null) {
            onClose();
            return;
        }
        if (be.revision != lastRevision) {
            lastRevision = be.revision;
            if (be.parts.size() != lastCount) {
                lastCount = be.parts.size();
                if (pendingSelect >= 0) {
                    sel = Math.min(pendingSelect, lastCount - 1);
                    pendingSelect = -1;
                }
                if (sel >= lastCount) sel = lastCount - 1;
                // 선택한 항목이 보이도록 스크롤
                int rows = visibleRows();
                if (sel + 1 >= scroll + rows) scroll = sel + 2 - rows;
                if (sel + 1 < scroll) scroll = sel + 1;
                rebuildList();
                refreshFields();
            } else {
                updateListLabels();
                if (!anyFocused()) refreshFields();
                else refreshButtons();
            }
        }
        if ((dirtyPart || dirtyGroup || dirtyLight || !pendingAll.isEmpty()) && ++dirtyTimer >= 3) flush();
        nameBox.tick();
        colorBox.tick();
        for (NumField f : fields) f.box.tick();
    }

    @Override
    public void removed() {
        flush();
        super.removed();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX >= leftX && mouseX < leftX + leftW && mouseY >= listTop && mouseY < listBottom) {
            scroll -= (int) Math.signum(delta);
            rebuildList();
            return true;
        }
        for (NumField f : fields) {
            if (f.visible() && f.box.isMouseOver(mouseX, mouseY) && f.minus.active) {
                f.nudge(delta > 0 ? 1 : -1);
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 월드가 보이도록 전체 화면을 어둡게 하지 않고 패널만 그림
        int panel = 0xB0101018;
        g.fill(leftX - 2, 2, leftX + leftW + 2, height - 2, panel);
        g.fill(colAX - 2, 2, colAX + colW + 2, 16 + ROW * 10 + 2, panel);
        g.fill(colBX - 2, 2, colBX + colW + 2, 16 + ROW * 9 + 2, panel);

        g.drawString(font, tr("objects"), leftX, 5, 0xFFD27F);
        g.drawString(font, part() == null ? tr("transform_group") : tr("transform"), colAX, 5, 0xFFD27F);
        g.drawString(font, part() == null ? tr("appearance_all") : tr("appearance"), colBX, 5, 0xFFD27F);

        for (NumField f : fields) {
            if (f.visible()) g.drawString(font, f.label(), f.x, f.y + 4, 0xE0E0E0);
        }
        if (nameBox.visible) g.drawString(font, tr("name"), colBX, nameBox.getY() + 4, 0xE0E0E0);
        g.drawString(font, tr("color"), colBX, colorBox.getY() + 4, 0xE0E0E0);
        int sx = colBX + colW - 16, sy = colorBox.getY();
        g.fill(sx, sy, sx + 16, sy + H, 0xFFFFFFFF);
        g.fill(sx + 1, sy + 1, sx + 15, sy + H - 1, 0xFF000000 | (current().color & 0xFFFFFF));

        super.render(g, mouseX, mouseY, partialTick);

        boolean showStatus = System.currentTimeMillis() < statusUntil && !status.getString().isEmpty();
        if (be != null && be.parts.isEmpty() && !showStatus) {
            g.drawWordWrap(font, tr("hint_empty"), colAX, 16 + ROW * 10 + 8, colW * 2, 0xFFFFFF);
        }
        if (showStatus) {
            g.drawWordWrap(font, status, colAX, height - 30, width - colAX - 6, statusColor);
        }
        if (be != null) {
            long kb = be.totalImageBytes() / 1024;
            g.drawString(font, tr("usage", be.parts.size(), kb), colBX, 16 + ROW * 9 + 6, 0xA0A0A0);
        }
    }
}
