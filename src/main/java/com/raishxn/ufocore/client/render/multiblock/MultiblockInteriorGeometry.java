package com.raishxn.ufocore.client.render.multiblock;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

final class MultiblockInteriorGeometry {
    private static final int RING_SEGMENTS = 48;
    private MultiblockInteriorGeometry() {}

    static void renderComputationLattice(PoseStack pose, VertexConsumer vertices, float time,
                                         float activity, Color primary, Color accent) {
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(time * 22.0F));
        pose.mulPose(Axis.XP.rotationDegrees(24.0F + time * 7.0F));
        float pulse = 1.0F + activity * 0.08F * (float) Math.sin(time * 2.4F);
        pose.scale(pulse, pulse, pulse);
        cube(pose, vertices, 0.42F, primary, 0.34F + activity * 0.20F);
        pose.mulPose(Axis.YP.rotationDegrees(45.0F));
        pose.mulPose(Axis.ZP.rotationDegrees(45.0F));
        cube(pose, vertices, 0.68F, accent, 0.16F + activity * 0.20F);
        pose.popPose();
        crossedRings(pose, vertices, time * 31.0F, 0.86F, primary, 0.28F + activity * 0.24F);
    }

    static void renderPatternOrbits(PoseStack pose, VertexConsumer vertices, float time,
                                    float activity, Color primary, Color accent) {
        float pulse = 1.0F + activity * 0.10F * (float) Math.sin(time * 3.0F);
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(time * 18.0F));
        pose.scale(pulse, pulse, pulse);
        cube(pose, vertices, 0.34F, primary, 0.42F + activity * 0.26F);
        pose.popPose();
        for (int orbit = 0; orbit < 3; orbit++) {
            pose.pushPose();
            pose.mulPose(Axis.YP.rotationDegrees(time * (18.0F + orbit * 9.0F)));
            pose.mulPose(Axis.XP.rotationDegrees(25.0F + orbit * 47.0F));
            float radius = 0.72F + orbit * 0.22F;
            ring(pose, vertices, radius - 0.018F, radius + 0.018F, 0.012F,
                    orbit == 1 ? accent : primary, 0.22F + activity * 0.24F);
            pose.translate(radius, 0.0F, 0.0F);
            cube(pose, vertices, 0.075F + orbit * 0.012F, accent, 0.62F + activity * 0.25F);
            pose.popPose();
        }
    }

    static void renderFabricationVortex(PoseStack pose, VertexConsumer vertices, float time,
                                        float activity, Color primary, Color accent) {
        float contraction = 1.0F - activity * 0.14F;
        for (int layer = 0; layer < 4; layer++) {
            pose.pushPose();
            pose.mulPose(Axis.YP.rotationDegrees(time * (26.0F + layer * 8.0F) + layer * 35.0F));
            pose.mulPose(Axis.XP.rotationDegrees(layer * 36.0F + time * 5.0F));
            float radius = (0.58F + layer * 0.19F) * contraction;
            ring(pose, vertices, radius - 0.025F, radius + 0.025F, 0.014F,
                    (layer & 1) == 0 ? primary : accent, 0.24F + activity * 0.28F);
            pose.popPose();
        }
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(-time * 34.0F));
        pose.mulPose(Axis.ZP.rotationDegrees(45.0F));
        cube(pose, vertices, 0.30F + activity * 0.06F, accent, 0.46F + activity * 0.32F);
        pose.popPose();
    }

    private static void crossedRings(PoseStack pose, VertexConsumer vertices, float rotation,
                                     float radius, Color color, float alpha) {
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(rotation));
        ring(pose, vertices, radius - 0.025F, radius + 0.025F, 0.012F, color, alpha);
        pose.mulPose(Axis.XP.rotationDegrees(60.0F));
        ring(pose, vertices, radius - 0.022F, radius + 0.022F, 0.012F, color, alpha * 0.84F);
        pose.mulPose(Axis.ZP.rotationDegrees(60.0F));
        ring(pose, vertices, radius - 0.019F, radius + 0.019F, 0.012F, color, alpha * 0.70F);
        pose.popPose();
    }

    private static void ring(PoseStack pose, VertexConsumer vertices, float innerRadius,
                             float outerRadius, float halfThickness, Color color, float alpha) {
        for (int segment = 0; segment < RING_SEGMENTS; segment++) {
            double a0 = segment * Math.PI * 2.0D / RING_SEGMENTS;
            double a1 = (segment + 1) * Math.PI * 2.0D / RING_SEGMENTS;
            float x0i = (float) Math.cos(a0) * innerRadius, z0i = (float) Math.sin(a0) * innerRadius;
            float x0o = (float) Math.cos(a0) * outerRadius, z0o = (float) Math.sin(a0) * outerRadius;
            float x1i = (float) Math.cos(a1) * innerRadius, z1i = (float) Math.sin(a1) * innerRadius;
            float x1o = (float) Math.cos(a1) * outerRadius, z1o = (float) Math.sin(a1) * outerRadius;
            quad(pose, vertices, x0i, halfThickness, z0i, x0o, halfThickness, z0o,
                    x1o, halfThickness, z1o, x1i, halfThickness, z1i, color, alpha);
            quad(pose, vertices, x1i, -halfThickness, z1i, x1o, -halfThickness, z1o,
                    x0o, -halfThickness, z0o, x0i, -halfThickness, z0i, color, alpha);
        }
    }

    private static void cube(PoseStack pose, VertexConsumer vertices, float half, Color color, float alpha) {
        float low = -half, high = half;
        quad(pose, vertices, low,low,high, high,low,high, high,high,high, low,high,high, color,alpha);
        quad(pose, vertices, high,low,low, low,low,low, low,high,low, high,high,low, color,alpha);
        quad(pose, vertices, high,low,high, high,low,low, high,high,low, high,high,high, color,alpha);
        quad(pose, vertices, low,low,low, low,low,high, low,high,high, low,high,low, color,alpha);
        quad(pose, vertices, low,high,high, high,high,high, high,high,low, low,high,low, color,alpha);
        quad(pose, vertices, low,low,low, high,low,low, high,low,high, low,low,high, color,alpha);
    }

    private static void quad(PoseStack pose, VertexConsumer vertices,
                             float x0,float y0,float z0, float x1,float y1,float z1,
                             float x2,float y2,float z2, float x3,float y3,float z3,
                             Color color, float alpha) {
        vertex(pose, vertices, x0,y0,z0,color,alpha); vertex(pose, vertices, x1,y1,z1,color,alpha);
        vertex(pose, vertices, x2,y2,z2,color,alpha); vertex(pose, vertices, x3,y3,z3,color,alpha);
    }

    private static void vertex(PoseStack pose, VertexConsumer vertices, float x,float y,float z,
                               Color color, float alpha) {
        vertices.addVertex(pose.last().pose(), x, y, z).setColor(color.red, color.green, color.blue, clamp(alpha));
    }

    static Color color(int rgb) {
        return new Color(((rgb >> 16) & 0xFF) / 255.0F, ((rgb >> 8) & 0xFF) / 255.0F,
                (rgb & 0xFF) / 255.0F);
    }
    private static float clamp(float value) { return Math.max(0.0F, Math.min(1.0F, value)); }
    record Color(float red, float green, float blue) {}
}
