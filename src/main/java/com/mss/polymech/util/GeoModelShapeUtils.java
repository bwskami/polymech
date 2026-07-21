package com.mss.polymech.util;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class GeoModelShapeUtils {

    public record CubeData(double originX, double originY, double originZ,
                           double sizeX, double sizeY, double sizeZ,
                           double inflate) {
        public double minX() { return originX - inflate; }
        public double minY() { return originY - inflate; }
        public double minZ() { return originZ - inflate; }
        public double maxX() { return originX + sizeX + inflate; }
        public double maxY() { return originY + sizeY + inflate; }
        public double maxZ() { return originZ + sizeZ + inflate; }
    }

    public static class ModelShapeData {
        public final List<CubeData> cubes;
        public final double minX, minY, minZ, maxX, maxY, maxZ;

        public ModelShapeData(List<CubeData> cubes, double minX, double minY, double minZ,
                              double maxX, double maxY, double maxZ) {
            this.cubes = cubes;
            this.minX = minX; this.minY = minY; this.minZ = minZ;
            this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
        }

        public int minBlockX() { return floorDiv((int) Math.floor(minX), 16); }
        public int maxBlockX() { return floorDiv((int) Math.ceil(maxX) - 1, 16); }
        public int minBlockY() { return floorDiv((int) Math.floor(minY), 16); }
        public int maxBlockY() { return floorDiv((int) Math.ceil(maxY) - 1, 16); }
        public int minBlockZ() { return floorDiv((int) Math.floor(minZ), 16); }
        public int maxBlockZ() { return floorDiv((int) Math.ceil(maxZ) - 1, 16); }

        private static int floorDiv(int a, int b) {
            int r = a / b;
            if ((a ^ b) < 0 && r * b != a) r--;
            return r;
        }
    }

    @Nullable
    public static ModelShapeData parseGeoModel(ResourceManager resourceManager, ResourceLocation modelPath) {
        try {
            Resource resource = resourceManager.getResource(modelPath).orElse(null);
            if (resource == null) return null;
            try (InputStreamReader reader = new InputStreamReader(resource.open(), StandardCharsets.UTF_8)) {
                return parseGeoModelFromReader(reader);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @Nullable
    public static ModelShapeData parseGeoModelStream(InputStream inputStream) {
        try (InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
            return parseGeoModelFromReader(reader);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @Nullable
    private static ModelShapeData parseGeoModelFromReader(InputStreamReader reader) {
        JsonObject root = new Gson().fromJson(reader, JsonObject.class);

        JsonArray geometries = root.getAsJsonArray("minecraft:geometry");
        if (geometries == null || geometries.isEmpty()) return null;

        JsonObject geometry = geometries.get(0).getAsJsonObject();
        JsonArray bones = geometry.getAsJsonArray("bones");
        if (bones == null) return null;

        List<CubeData> cubes = new ArrayList<>();
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;

        for (JsonElement boneElem : bones) {
            JsonObject bone = boneElem.getAsJsonObject();
            JsonArray cubesArray = bone.getAsJsonArray("cubes");
            if (cubesArray == null) continue;

            for (JsonElement cubeElem : cubesArray) {
                JsonObject cube = cubeElem.getAsJsonObject();
                JsonArray origin = cube.getAsJsonArray("origin");
                JsonArray size = cube.getAsJsonArray("size");
                double inflate = cube.has("inflate") ? cube.get("inflate").getAsDouble() : 0;

                double ox = origin.get(0).getAsDouble();
                double oy = origin.get(1).getAsDouble();
                double oz = origin.get(2).getAsDouble();
                double sx = size.get(0).getAsDouble();
                double sy = size.get(1).getAsDouble();
                double sz = size.get(2).getAsDouble();

                cubes.add(new CubeData(ox, oy, oz, sx, sy, sz, inflate));

                double cMinX = ox - inflate, cMaxX = ox + sx + inflate;
                double cMinY = oy - inflate, cMaxY = oy + sy + inflate;
                double cMinZ = oz - inflate, cMaxZ = oz + sz + inflate;
                minX = Math.min(minX, cMinX); minY = Math.min(minY, cMinY); minZ = Math.min(minZ, cMinZ);
                maxX = Math.max(maxX, cMaxX); maxY = Math.max(maxY, cMaxY); maxZ = Math.max(maxZ, cMaxZ);
            }
        }

        if (cubes.isEmpty()) return null;
        return new ModelShapeData(cubes, minX, minY, minZ, maxX, maxY, maxZ);
    }

    public static VoxelShape buildShape(ModelShapeData data, int rotationSteps) {
        VoxelShape shape = Shapes.empty();
        for (CubeData cube : data.cubes) {
            // Blockbench geo.json坐标转Minecraft方块坐标：
            // Y-up坐标系一致，无需Z翻转
            // X轴镜像翻转：Blockbench +X 对应 Minecraft -X 方向
            // Z轴直接+8偏移
            double ox = -cube.maxX() + 8;
            double oy = cube.minY();
            double oz = cube.minZ() + 8;
            double ex = -cube.minX() + 8;
            double ey = cube.maxY();
            double ez = cube.maxZ() + 8;
            
            double x1, y1, z1, x2, y2, z2;
            switch (rotationSteps) {
                case 1 -> { x1 = -ez + 16; y1 = oy; z1 = ox;
                             x2 = -oz + 16; y2 = ey; z2 = ex; }
                case 2 -> { x1 = -ex + 16; y1 = oy; z1 = -ez + 16;
                             x2 = -ox + 16; y2 = ey; z2 = -oz + 16; }
                case 3 -> { x1 = oz; y1 = oy; z1 = -ex + 16;
                             x2 = ez; y2 = ey; z2 = -ox + 16; }
                default -> { x1 = ox; y1 = oy; z1 = oz;
                             x2 = ex; y2 = ey; z2 = ez; }
            }
            shape = Shapes.joinUnoptimized(shape,
                    net.minecraft.world.level.block.Block.box(x1, y1, z1, x2, y2, z2),
                    BooleanOp.OR);
        }
        return shape.optimize();
    }

    public static int facingToRotationSteps(net.minecraft.core.Direction facing) {
        return switch (facing) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        };
    }
}
