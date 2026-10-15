package com.qwertys.billboard.client;

import com.qwertys.billboard.ModRegistry;
import com.qwertys.billboard.QwertysBillboard;
import com.qwertys.billboard.block.BillboardBlock;
import com.qwertys.billboard.block.BillboardBlockEntity;
import com.qwertys.billboard.data.ColorMode;
import com.qwertys.billboard.data.DisplayPart;
import com.qwertys.billboard.network.EditOps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 자동 동작 검사 (개발 환경 전용). JVM 속성 qwertys_billboard.selftest=true 일 때만 동작한다.
 * 월드에 전광판을 설치하고 모든 형식의 이미지를 불러와 3D/발광/무지개/분리/WorldEdit 식 복사·회전을 해 본 뒤
 * 스크린샷과 결과를 run/selftest/out 에 남기고 게임을 종료한다.
 */
@Mod.EventBusSubscriber(modid = QwertysBillboard.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientSelfTest {
    private static final boolean ENABLED = Boolean.getBoolean("qwertys_billboard.selftest");
    private static int tick = -1;
    private static BlockPos pos, copyPos, wallPos;
    private static final List<String> LOG = new ArrayList<>();
    private static boolean failed;

    private static void log(String s) {
        LOG.add(s);
        QwertysBillboard.LOGGER.info("[SELFTEST] {}", s);
    }

    private static void check(boolean ok, String what) {
        if (!ok) failed = true;
        log((ok ? "PASS " : "FAIL ") + what);
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent e) {
        if (!ENABLED || e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (mc.player == null || mc.level == null || server == null) return;
        tick++;
        try {
            step(mc, server);
        } catch (Throwable t) {
            failed = true;
            log("FAIL exception at tick " + tick + ": " + t);
            QwertysBillboard.LOGGER.error("[SELFTEST]", t);
            finish(mc);
        }
    }

    private static Path dir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("selftest");
    }

    private static void step(Minecraft mc, MinecraftServer server) throws Exception {
        switch (tick) {
            case 60 -> server.execute(() -> {
                ServerPlayer sp = server.getPlayerList().getPlayers().get(0);
                ServerLevel sl = sp.serverLevel();
                sp.setGameMode(GameType.CREATIVE);
                sl.setDayTime(6000);
                BlockPos base = sp.blockPosition();
                pos = base.offset(0, 2, -5);
                copyPos = base.offset(6, 2, -5);
                sl.setBlock(pos, ModRegistry.BILLBOARD.get().defaultBlockState().setValue(BillboardBlock.FACING, Direction.SOUTH), Block.UPDATE_ALL);
                sp.teleportTo(sl, base.getX() + 0.8, base.getY(), base.getZ() + 1.5, 180f, -20f);
            });
            case 100 -> {
                check(mc.level.getBlockEntity(pos) instanceof BillboardBlockEntity, "billboard block entity exists on client");
                ImageImporter.registerPlugins();
                ListTag list = new ListTag();
                List<Path> files;
                try (Stream<Path> s = Files.list(dir().resolve("in"))) {
                    files = s.sorted().toList();
                }
                float x = -3f;
                for (Path f : files) {
                    String name = f.getFileName().toString();
                    boolean bg = name.endsWith(".jpg") || name.endsWith(".bmp");
                    try {
                        ImageImporter.Result r = ImageImporter.load(f, new ImageImporter.Options(128, bg, 40));
                        log("PASS load " + name + " -> " + r.width() + "x" + r.height() + " " + r.png().length + "B");
                        DisplayPart p = new DisplayPart();
                        p.setImage(r.png(), r.width(), r.height());
                        p.name = r.name();
                        p.size = 0.8f;
                        p.x = x;
                        p.y = 1.0f;
                        x += 0.9f;
                        list.add(p.save(new CompoundTag(), true));
                    } catch (Exception ex) {
                        failed = true;
                        log("FAIL load " + name + ": " + ex);
                    }
                }
                CompoundTag op = new CompoundTag();
                op.put("Parts", list);
                int bytes = EditSender.send(pos, EditOps.ADD_PARTS, op);
                log("sent add_parts bytes=" + bytes + " parts=" + list.size());
            }
            case 160 -> {
                BillboardBlockEntity be = (BillboardBlockEntity) mc.level.getBlockEntity(pos);
                check(be != null && be.parts.size() == 9, "client received 9 parts (got " + (be == null ? -1 : be.parts.size()) + ")");
                // 분리: split.png 를 3개로
                int idx = -1;
                for (int i = 0; i < be.parts.size(); i++) if (be.parts.get(i).name.equals("split")) idx = i;
                DisplayPart base = be.parts.get(idx);
                List<ComponentSplitter.Piece> pieces = ComponentSplitter.split(base.image, 6, 64);
                check(pieces.size() == 3, "split.png splits into 3 (got " + pieces.size() + ")");
                ListTag list = new ListTag();
                float pix = base.size / base.imgW;
                for (ComponentSplitter.Piece pc : pieces) {
                    DisplayPart np = base.copy();
                    np.setImage(pc.png(), pc.width(), pc.height());
                    np.size = pc.width() * pix;
                    np.x = base.x + (pc.cx() - base.imgW / 2f) * pix;
                    np.y = base.y + (base.imgH / 2f - pc.cy()) * pix;
                    list.add(np.save(new CompoundTag(), true));
                }
                CompoundTag op = new CompoundTag();
                op.putInt("Index", idx);
                op.put("Parts", list);
                EditSender.send(pos, EditOps.REPLACE_PART, op);
            }
            case 220 -> {
                BillboardBlockEntity be = (BillboardBlockEntity) mc.level.getBlockEntity(pos);
                check(be.parts.size() == 11, "after split 11 parts (got " + be.parts.size() + ")");
                // 외형/변형 변경: 3D+무지개 물결, 발광, 회전, mm 크기
                for (int i = 0; i < be.parts.size(); i++) {
                    DisplayPart p = be.parts.get(i);
                    CompoundTag op = new CompoundTag();
                    op.putInt("Index", i);
                    if (i % 3 == 0) {
                        p.mode3d = true;
                        p.depth = 0.25f;
                        p.yaw = 30.5f;
                    }
                    if (i % 3 == 1) {
                        p.colorMode = ColorMode.RAINBOW_WAVE;
                        p.glow = true;
                        p.roll = 12.3f;
                    }
                    if (i == 2) p.size = 0.005f; // 5mm
                    op.put("Part", p.save(new CompoundTag(), false));
                    EditSender.send(pos, EditOps.SET_PART, op);
                }
                CompoundTag light = new CompoundTag();
                light.putInt("Light", 15);
                EditSender.send(pos, EditOps.SET_LIGHT, light);
            }
            case 280 -> {
                BillboardBlockEntity be = (BillboardBlockEntity) mc.level.getBlockEntity(pos);
                check(be.parts.get(0).mode3d && Math.abs(be.parts.get(0).yaw - 30.5f) < 1e-3, "3D + fine yaw synced");
                check(Math.abs(be.parts.get(2).size - 0.005f) < 1e-6, "5mm size synced");
                check(mc.level.getBlockState(pos).getValue(BillboardBlock.LIGHT) == 15, "block light 15");
                shot(mc, "1_world.png");
            }
            case 300 -> mc.setScreen(new BillboardEditScreen(pos));
            case 340 -> shot(mc, "2_editor.png");
            case 360 -> {
                mc.setScreen(null);
                // WorldEdit 의 복사+회전과 같은 동작: NBT 복사 + 블록 상태 rotate
                server.execute(() -> {
                    ServerLevel sl = server.getPlayerList().getPlayers().get(0).serverLevel();
                    BlockState st = sl.getBlockState(pos);
                    BlockEntity src = sl.getBlockEntity(pos);
                    CompoundTag nbt = src.saveWithFullMetadata();
                    sl.setBlock(copyPos, st.rotate(Rotation.CLOCKWISE_90), Block.UPDATE_ALL);
                    BlockEntity dst = sl.getBlockEntity(copyPos);
                    dst.load(nbt);
                    ((BillboardBlockEntity) dst).markUpdated();
                });
            }
            case 420 -> {
                BillboardBlockEntity a = (BillboardBlockEntity) mc.level.getBlockEntity(pos);
                BlockEntity b0 = mc.level.getBlockEntity(copyPos);
                check(b0 instanceof BillboardBlockEntity bb && bb.parts.size() == a.parts.size()
                        && bb.parts.get(0).yaw == a.parts.get(0).yaw, "NBT copy keeps all parts");
                check(mc.level.getBlockState(copyPos).getValue(BillboardBlock.FACING) == Direction.WEST, "rotated copy faces WEST");
                server.execute(() -> {
                    ServerPlayer sp = server.getPlayerList().getPlayers().get(0);
                    sp.teleportTo(sp.serverLevel(), pos.getX() + 3.5, pos.getY() - 1, pos.getZ() + 8.5, 200f, 10f);
                });
            }
            case 480 -> shot(mc, "3_copy_rotated.png");
            // 3D 옆면 검사: 부드러운 테두리(안티앨리어싱)가 있는 흰 글자를 돌벽 앞에 3D 로 세우고 옆에서 본다
            case 500 -> server.execute(() -> {
                ServerPlayer sp = server.getPlayerList().getPlayers().get(0);
                ServerLevel sl = sp.serverLevel();
                wallPos = pos.offset(-12, -2, 0);
                for (int dx = -3; dx <= 3; dx++)
                    for (int dy = 0; dy <= 3; dy++)
                        sl.setBlock(wallPos.offset(dx, dy, -1), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                sl.setBlock(wallPos.offset(0, 1, 0), ModRegistry.BILLBOARD.get().defaultBlockState().setValue(BillboardBlock.FACING, Direction.SOUTH), Block.UPDATE_ALL);
            });
            case 530 -> {
                BlockPos bp = wallPos.offset(0, 1, 0);
                ImageImporter.Result r = ImageImporter.load(dir().resolve("aa/aa_text.png"), new ImageImporter.Options(128, false, 40));
                DisplayPart p = new DisplayPart();
                p.setImage(r.png(), r.width(), r.height());
                p.name = "aa_text";
                p.size = 3f;
                p.mode3d = true;
                p.depth = 0.4f;
                p.y = 0.6f;
                p.z = -0.3f;
                ListTag list = new ListTag();
                list.add(p.save(new CompoundTag(), true));
                CompoundTag op = new CompoundTag();
                op.put("Parts", list);
                EditSender.send(bp, EditOps.ADD_PARTS, op);
                server.execute(() -> {
                    ServerPlayer sp = server.getPlayerList().getPlayers().get(0);
                    sp.teleportTo(sp.serverLevel(), bp.getX() + 2.6, bp.getY() - 0.4, bp.getZ() + 1.6, 120f, 10f);
                });
            }
            case 600 -> {
                BillboardBlockEntity be = (BillboardBlockEntity) mc.level.getBlockEntity(wallPos.offset(0, 1, 0));
                check(be != null && be.parts.size() == 1 && be.parts.get(0).mode3d, "anti-aliased 3D text placed");
                shot(mc, "4_3d_side.png");
            }
            case 620 -> server.execute(() -> {
                ServerPlayer sp = server.getPlayerList().getPlayers().get(0);
                BlockPos bp = wallPos.offset(0, 1, 0);
                sp.teleportTo(sp.serverLevel(), bp.getX() - 1.2, bp.getY() + 0.2, bp.getZ() + 2.2, -150f, 15f);
            });
            case 680 -> shot(mc, "5_3d_side2.png");
            case 700 -> finish(mc);
            default -> {
            }
        }
    }

    private static void shot(Minecraft mc, String name) {
        File out = dir().resolve("out").toFile();
        out.mkdirs();
        Screenshot.grab(out, name, mc.getMainRenderTarget(), c -> log("screenshot " + name + ": " + c.getString()));
    }

    private static void finish(Minecraft mc) {
        try {
            Path out = dir().resolve("out");
            Files.createDirectories(out);
            LOG.add(failed ? "RESULT: FAILED" : "RESULT: ALL PASSED");
            Files.write(out.resolve("result.txt"), LOG);
        } catch (Exception ignored) {
        }
        mc.stop();
    }

    private ClientSelfTest() {}
}
