package com.raishxn.ufocore.client.render.multiblock;

import com.mojang.blaze3d.vertex.PoseStack;
import com.raishxn.ufocore.api.multiblock.visual.MultiblockInteriorVisualHost;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Generic emissive procedural renderer for formed multiblock interiors. */
public final class MultiblockInteriorRenderer<T extends BlockEntity & MultiblockInteriorVisualHost>
        implements BlockEntityRenderer<T> {
    public MultiblockInteriorRenderer(BlockEntityRendererProvider.Context context) {}

    @Override public void render(T controller, float partialTick, PoseStack pose, MultiBufferSource buffers,
                                 int packedLight, int packedOverlay) {
        if (!controller.isInteriorVisualFormed() || controller.getLevel() == null) return;
        var visual = controller.getInteriorVisual();
        var center = controller.getInteriorVisualCenter();
        float time = controller.getLevel().getGameTime() + partialTick;
        float activity = controller.isInteriorVisualActive() ? 1.0F : 0.18F;
        var vertices = buffers.getBuffer(RenderType.lightning());
        var primary = MultiblockInteriorGeometry.color(visual.primaryRgb());
        var accent = MultiblockInteriorGeometry.color(visual.accentRgb());
        pose.pushPose();
        pose.translate(center.x - controller.getBlockPos().getX(), center.y - controller.getBlockPos().getY(),
                center.z - controller.getBlockPos().getZ());
        pose.scale(visual.scale(), visual.scale(), visual.scale());
        switch (visual.effect()) {
            case COMPUTATION_LATTICE -> MultiblockInteriorGeometry.renderComputationLattice(
                    pose, vertices, time, activity, primary, accent);
            case PATTERN_ORBITS -> MultiblockInteriorGeometry.renderPatternOrbits(
                    pose, vertices, time, activity, primary, accent);
            case FABRICATION_VORTEX -> MultiblockInteriorGeometry.renderFabricationVortex(
                    pose, vertices, time, activity, primary, accent);
        }
        pose.popPose();
    }

    @Override public AABB getRenderBoundingBox(T controller) {
        var visual = controller.getInteriorVisual();
        Vec3 center = controller.getInteriorVisualCenter();
        return new AABB(center, center).inflate(visual.renderRadius()).minmax(new AABB(controller.getBlockPos()));
    }
    @Override public int getViewDistance() { return 256; }
}
