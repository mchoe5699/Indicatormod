package com.qwertys.billboard.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.qwertys.billboard.ModRegistry;
import com.qwertys.billboard.block.BillboardBlock;
import com.qwertys.billboard.block.BillboardBlockEntity;
import com.qwertys.billboard.data.ColorMode;
import com.qwertys.billboard.data.DisplayPart;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 전광판 렌더러.
 * 좌표계: 블록 중심 → 기준 방향(FACING) 회전 → 그룹 변형 → 오브젝트 변형.
 * 오브젝트 로컬 좌표에서 +Z 가 앞면, +X 가 오른쪽, +Y 가 위.
 */
public class BillboardRenderer implements BlockEntityRenderer<BillboardBlockEntity> {

    public BillboardRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public boolean shouldRenderOffScreen(BillboardBlockEntity be) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 256;
    }

    @Override
    public void render(BillboardBlockEntity be, float partialTick, PoseStack ps, MultiBufferSource buffers, int packedLight, int packedOverlay) {
        Level level = be.getLevel();
        if (level == null) return;
        BlockState state = be.getBlockState();
        Direction facing = state.hasProperty(BillboardBlock.FACING) ? state.getValue(BillboardBlock.FACING) : Direction.NORTH;

        if (be.parts.isEmpty() || isHoldingBillboard()) drawAnchor(ps, buffers, be.parts.isEmpty());

        float time = ((level.getGameTime() % 1_728_000L) + partialTick) / 20f;
        float facingDeg = -facing.toYRot();

        Quaternionf facingQ = new Quaternionf().rotateY(facingDeg * Mth.DEG_TO_RAD);
        Quaternionf groupQ = euler(be.gyaw, be.gpitch, be.groll);

        ps.pushPose();
        ps.translate(0.5, 0.5, 0.5);
        ps.mulPose(Axis.YP.rotationDegrees(facingDeg));
        ps.translate(be.gx, be.gy, be.gz);
        ps.mulPose(groupQ);
        ps.scale(be.gscale, be.gscale, be.gscale);

        for (DisplayPart p : be.parts) {
            if (!p.visible || p.opacity <= 0.004f) continue;
            ClientImageCache.Entry img = ClientImageCache.get(p);
            if (img == null) continue;
            int light = p.glow ? LightTexture.FULL_BRIGHT : lightFor(level, be, facingQ, groupQ, p, packedLight);
            ps.pushPose();
            ps.translate(p.x, p.y, p.z);
            ps.mulPose(euler(p.yaw, p.pitch, p.roll));
            renderPart(p, img, ps, buffers, light, time);
            ps.popPose();
        }
        ps.popPose();
    }

    /** Yaw(Y축) → Pitch(X축) → Roll(Z축) 순서. 분리 기능의 위치 계산과 반드시 같아야 함. */
    public static Quaternionf euler(float yaw, float pitch, float roll) {
        return new Quaternionf()
                .rotateY(yaw * Mth.DEG_TO_RAD)
                .rotateX(pitch * Mth.DEG_TO_RAD)
                .rotateZ(roll * Mth.DEG_TO_RAD);
    }

    /** 오브젝트 중심이 있는 위치의 밝기 (블록에서 멀리 떨어진 오브젝트도 자연스럽게) */
    private static int lightFor(Level level, BillboardBlockEntity be, Quaternionf facingQ, Quaternionf groupQ, DisplayPart p, int anchorLight) {
        Vector3f v = new Vector3f(p.x, p.y, p.z).mul(be.gscale).rotate(groupQ).add(be.gx, be.gy, be.gz).rotate(facingQ);
        BlockPos base = be.getBlockPos();
        BlockPos at = BlockPos.containing(base.getX() + 0.5 + v.x, base.getY() + 0.5 + v.y, base.getZ() + 0.5 + v.z);
        if (at.equals(base) || !level.isLoaded(at)) return anchorLight;
        int l = LevelRenderer.getLightColor(level, at);
        int block = Math.max(LightTexture.block(l), LightTexture.block(anchorLight));
        int sky = Math.max(LightTexture.sky(l), LightTexture.sky(anchorLight));
        return LightTexture.pack(block, sky);
    }

    private static void renderPart(DisplayPart p, ClientImageCache.Entry img, PoseStack ps, MultiBufferSource buffers, int light, float time) {
        ColorMode mode = p.colorMode;
        VertexConsumer vc = buffers.getBuffer(RenderType.entityTranslucentCull(mode.useMask ? img.mask : img.texture));
        PoseStack.Pose pose = ps.last();
        Matrix4f m = pose.pose();
        Matrix3f n = pose.normal();

        int w = img.width, h = img.height;
        float pix = p.size / w;
        float hw = w * pix / 2f, hh = h * pix / 2f;
        float zf = p.mode3d ? p.depth / 2f : 0f;
        float zb = -zf;
        int alpha = Mth.clamp(Math.round(p.opacity * 255f), 0, 255);

        ColorFn color = colorFn(p, time, w, alpha);
        boolean wave = mode == ColorMode.RAINBOW_WAVE;
        int strips = wave ? Mth.clamp((int) Math.ceil(Math.abs(p.spread) * 16f) + 1, 2, 64) : 1;

        // 앞면·뒷면 (물결 무지개는 세로 띠로 나눠 색을 다르게)
        for (int s = 0; s < strips; s++) {
            float px0 = w * s / (float) strips, px1 = w * (s + 1) / (float) strips;
            float x0 = px0 * pix - hw, x1 = px1 * pix - hw;
            float u0 = px0 / w, u1 = px1 / w;
            int c0 = color.at(px0), c1 = color.at(px1);
            // 앞면 (+Z)
            vertex(vc, m, n, x0, -hh, zf, c0, u0, 1, light, 0, 0, 1);
            vertex(vc, m, n, x1, -hh, zf, c1, u1, 1, light, 0, 0, 1);
            vertex(vc, m, n, x1, hh, zf, c1, u1, 0, light, 0, 0, 1);
            vertex(vc, m, n, x0, hh, zf, c0, u0, 0, light, 0, 0, 1);
            // 뒷면 (-Z)
            vertex(vc, m, n, x1, -hh, zb, c1, u1, 1, light, 0, 0, -1);
            vertex(vc, m, n, x0, -hh, zb, c0, u0, 1, light, 0, 0, -1);
            vertex(vc, m, n, x0, hh, zb, c0, u0, 0, light, 0, 0, -1);
            vertex(vc, m, n, x1, hh, zb, c1, u1, 0, light, 0, 0, -1);
        }

        if (!p.mode3d) return;

        // 3D 옆면 (이미지 외곽선을 따라 두께만큼 돌출)
        int[] sd = img.sides;
        for (int i = 0; i < sd.length; i += 4) {
            int type = sd[i], line = sd[i + 1], a = sd[i + 2], b = sd[i + 3];
            switch (type) {
                case 0, 1 -> { // 위 / 아래
                    float x0 = a * pix - hw, x1 = b * pix - hw;
                    float u0 = a / (float) w, u1 = b / (float) w;
                    float v = (line + 0.5f) / h;
                    int c0 = color.at(a), c1 = color.at(b);
                    if (type == 0) {
                        float y = hh - line * pix;
                        vertex(vc, m, n, x0, y, zf, c0, u0, v, light, 0, 1, 0);
                        vertex(vc, m, n, x1, y, zf, c1, u1, v, light, 0, 1, 0);
                        vertex(vc, m, n, x1, y, zb, c1, u1, v, light, 0, 1, 0);
                        vertex(vc, m, n, x0, y, zb, c0, u0, v, light, 0, 1, 0);
                    } else {
                        float y = hh - (line + 1) * pix;
                        vertex(vc, m, n, x0, y, zb, c0, u0, v, light, 0, -1, 0);
                        vertex(vc, m, n, x1, y, zb, c1, u1, v, light, 0, -1, 0);
                        vertex(vc, m, n, x1, y, zf, c1, u1, v, light, 0, -1, 0);
                        vertex(vc, m, n, x0, y, zf, c0, u0, v, light, 0, -1, 0);
                    }
                }
                default -> { // 왼쪽 / 오른쪽
                    float yt = hh - a * pix, yb = hh - b * pix;
                    float vt = a / (float) h, vb = b / (float) h;
                    float u = (line + 0.5f) / w;
                    int c = color.at(line + 0.5f);
                    if (type == 2) {
                        float x = line * pix - hw;
                        vertex(vc, m, n, x, yb, zb, c, u, vb, light, -1, 0, 0);
                        vertex(vc, m, n, x, yb, zf, c, u, vb, light, -1, 0, 0);
                        vertex(vc, m, n, x, yt, zf, c, u, vt, light, -1, 0, 0);
                        vertex(vc, m, n, x, yt, zb, c, u, vt, light, -1, 0, 0);
                    } else {
                        float x = (line + 1) * pix - hw;
                        vertex(vc, m, n, x, yb, zf, c, u, vb, light, 1, 0, 0);
                        vertex(vc, m, n, x, yb, zb, c, u, vb, light, 1, 0, 0);
                        vertex(vc, m, n, x, yt, zb, c, u, vt, light, 1, 0, 0);
                        vertex(vc, m, n, x, yt, zf, c, u, vt, light, 1, 0, 0);
                    }
                }
            }
        }
    }

    @FunctionalInterface
    private interface ColorFn {
        /** @param px 이미지 가로 픽셀 위치 → ARGB */
        int at(float px);
    }

    private static ColorFn colorFn(DisplayPart p, float time, int width, int alpha) {
        int a = alpha << 24;
        return switch (p.colorMode) {
            case ORIGINAL -> px -> a | 0xFFFFFF;
            case TINT, SOLID -> {
                int c = a | (p.color & 0xFFFFFF);
                yield px -> c;
            }
            case RAINBOW, RAINBOW_TINT -> {
                int c = a | hue(time * p.speed);
                yield px -> c;
            }
            case RAINBOW_WAVE -> {
                float base = time * p.speed;
                float spread = p.spread;
                yield px -> a | hue(base - (px / width) * spread);
            }
        };
    }

    private static int hue(float h) {
        h = h - (float) Math.floor(h);
        return Mth.hsvToRgb(h, 1f, 1f) & 0xFFFFFF;
    }

    private static void vertex(VertexConsumer vc, Matrix4f m, Matrix3f n, float x, float y, float z, int argb,
                               float u, float v, int light, float nx, float ny, float nz) {
        vc.vertex(m, x, y, z)
                .color((argb >> 16) & 255, (argb >> 8) & 255, argb & 255, argb >>> 24)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(n, nx, ny, nz)
                .endVertex();
    }

    private static boolean isHoldingBillboard() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return false;
        return player.getMainHandItem().is(ModRegistry.BILLBOARD_ITEM.get())
                || player.getOffhandItem().is(ModRegistry.BILLBOARD_ITEM.get());
    }

    /** 보이지 않는 기준 블록 위치 표시 (비어 있거나 전광판 아이템을 들고 있을 때) */
    private static void drawAnchor(PoseStack ps, MultiBufferSource buffers, boolean empty) {
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        float r = empty ? 1f : 0.3f, g = empty ? 0.8f : 0.9f, b = empty ? 0.2f : 1f;
        LevelRenderer.renderLineBox(ps, lines, 0.1875, 0.1875, 0.1875, 0.8125, 0.8125, 0.8125, r, g, b, 1f);
    }
}
