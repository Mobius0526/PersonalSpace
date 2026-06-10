package me.eigenraven.personalspace.world;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;

import net.minecraft.block.Block;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.init.Blocks;
import net.minecraft.util.IProgressUpdate;
import net.minecraft.util.MathHelper;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.gen.FlatLayerInfo;
import net.minecraft.world.gen.feature.WorldGenAbstractTree;
import net.minecraft.world.gen.feature.WorldGenTrees;
import net.minecraftforge.event.terraingen.DecorateBiomeEvent;
import net.minecraftforge.event.terraingen.TerrainGen;

import com.github.bsideup.jabel.Desugar;

import me.eigenraven.personalspace.PersonalSpaceMod;
import me.eigenraven.personalspace.config.Config;

public class PersonalChunkProvider implements IChunkProvider {

    private PersonalWorldProvider world;
    private long seed;
    private Random random;
    private WorldGenTrees treeGen = new WorldGenTrees(false, 4, 0, 0, false);
    private String savedBiomeName = null;
    private int savedBiomeId = -1;

    public PersonalChunkProvider(PersonalWorldProvider world, long seed) {
        this.world = world;
        this.seed = seed;
        this.random = new Random(seed);
        if (Config.debugLogging) {
            PersonalSpaceMod.LOG.info("PersonalChunkProvider created for world {}", world.dimensionId, new Throwable());
        }
    }

    @Override
    public boolean chunkExists(int x, int z) {
        return true;
    }

    @Override
    public Chunk provideChunk(int chunkX, int chunkZ) {
        Chunk chunk = new Chunk(this.world.worldObj, chunkX, chunkZ);
        chunk.isModified = true;

        List<FlatLayerInfo> layers = world.getConfig().getLayers();
        int y = 0;
        int worldHeight = world.getHeight();
        for (FlatLayerInfo info : layers) {
            Block block = info.func_151536_b();
            if (block == null || block == Blocks.air) {
                y += info.getLayerCount();
                continue;
            }
            int meta = info.getFillBlockMeta();
            for (; y < info.getMinY() + info.getLayerCount() && y < worldHeight; ++y) {
                int yChunk = y >> 4;
                ExtendedBlockStorage ebs = chunk.getBlockStorageArray()[yChunk];
                if (ebs == null) {
                    ebs = new ExtendedBlockStorage(y & ~15, true);
                    chunk.getBlockStorageArray()[yChunk] = ebs;
                }
                for (int z = 0; z < 16; ++z) {
                    for (int x = 0; x < 16; ++x) {
                        ebs.func_150818_a(x, y & 15, z, block);
                        if (meta != 0) {
                            ebs.setExtBlockMetadata(x, y & 15, z, meta);
                        }
                    }
                }
            }
            if (y >= worldHeight) {
                break;
            }
        }

        DimensionConfig cfg = world.getConfig();

        int groundLevel = cfg.getGroundLevel() - 1;
        if (groundLevel >= 0 && groundLevel < worldHeight) {
            List<Integer> surfaceLevels = cfg.isApplyToAllSurfaceLayers() ? getExposedSurfaceLevels(layers, worldHeight)
                    : Collections.singletonList(groundLevel);

            int intervalX = MathHelper.clamp_int(cfg.getBoundaryChunkIntervalX(), 0, 20);
            int intervalZ = MathHelper.clamp_int(cfg.getBoundaryChunkIntervalZ(), 0, 20);

            int gapWidth = MathHelper.clamp_int(cfg.getGapWidth(), 0, 5);
            int periodX = intervalX + gapWidth;
            int periodZ = intervalZ + gapWidth;
            boolean isGapChunkX = gapWidth > 0 && intervalX > 0 && mod(chunkX, periodX) >= intervalX;
            boolean isGapChunkZ = gapWidth > 0 && intervalZ > 0 && mod(chunkZ, periodZ) >= intervalZ;

            if (isGapChunkX || isGapChunkZ) {
                for (int surfaceLevel : surfaceLevels) {
                    generateGapInChunk(
                            chunk,
                            chunkX,
                            chunkZ,
                            surfaceLevel,
                            cfg,
                            isGapChunkX,
                            isGapChunkZ,
                            periodX,
                            periodZ,
                            gapWidth,
                            intervalX,
                            intervalZ);
                }
            }

            // 最终版：单侧边界 + 奇数宽度区域
            boolean isBoundaryX = false;
            boolean isBoundaryZ = false;
            boolean prevBoundaryX = false;
            boolean prevBoundaryZ = false;

            if (gapWidth > 0) {
                isBoundaryX = intervalX > 0 && mod(chunkX, periodX) == 0;
                isBoundaryZ = intervalZ > 0 && mod(chunkZ, periodZ) == 0;
                prevBoundaryX = false;
                prevBoundaryZ = false;
            } else {
                isBoundaryX = intervalX > 0 && mod(chunkX, intervalX) == 0;
                isBoundaryZ = intervalZ > 0 && mod(chunkZ, intervalZ) == 0;
                prevBoundaryX = false;
                prevBoundaryZ = false;
            }

            Block boundaryBlockA = cfg.getBoundaryBlockAResolved();
            int boundaryMetaA = cfg.getBoundaryMetaA();
            Block boundaryBlockB = cfg.getBoundaryBlockBResolved();
            int boundaryMetaB = cfg.getBoundaryMetaB();

            boolean hasA = boundaryBlockA != null && boundaryBlockA != Blocks.air;
            boolean hasB = boundaryBlockB != null && boundaryBlockB != Blocks.air;

            boolean canDrawBoundary = hasA || hasB;

            if (canDrawBoundary && !isGapChunkX
                    && !isGapChunkZ
                    && (isBoundaryX || prevBoundaryX || isBoundaryZ || prevBoundaryZ)) {
                for (int surfaceLevel : surfaceLevels) {
                    int yChunk = surfaceLevel >> 4;
                    int yLocal = surfaceLevel & 15;
                    ExtendedBlockStorage ebs = chunk.getBlockStorageArray()[yChunk];
                    if (ebs == null) {
                        ebs = new ExtendedBlockStorage(surfaceLevel & ~15, true);
                        chunk.getBlockStorageArray()[yChunk] = ebs;
                    }

                    for (int localZ = 0; localZ < 16; localZ++) {
                        if (isBoundaryX) {
                            int localX = 0;
                            int worldX = (chunkX << 4) + localX;
                            int worldZ = (chunkZ << 4) + localZ;

                            StripeBlock stripe = getStripeBlock(
                                    worldX,
                                    worldZ,
                                    boundaryBlockA,
                                    boundaryMetaA,
                                    boundaryBlockB,
                                    boundaryMetaB);
                            if (stripe.block != null && stripe.block != Blocks.air) {
                                ebs.func_150818_a(localX, yLocal, localZ, stripe.block);
                                ebs.setExtBlockMetadata(localX, yLocal, localZ, stripe.meta);
                            }
                        }

                        if (prevBoundaryX) {
                            int localX = 15;
                            int worldX = (chunkX << 4) + localX;
                            int worldZ = (chunkZ << 4) + localZ;

                            StripeBlock stripe = getStripeBlock(
                                    worldX,
                                    worldZ,
                                    boundaryBlockA,
                                    boundaryMetaA,
                                    boundaryBlockB,
                                    boundaryMetaB);
                            if (stripe.block != null && stripe.block != Blocks.air) {
                                ebs.func_150818_a(localX, yLocal, localZ, stripe.block);
                                ebs.setExtBlockMetadata(localX, yLocal, localZ, stripe.meta);
                            }
                        }
                    }

                    for (int localX = 0; localX < 16; localX++) {
                        if (isBoundaryZ) {
                            int localZ = 0;
                            int worldX = (chunkX << 4) + localX;
                            int worldZ = (chunkZ << 4) + localZ;

                            StripeBlock stripe = getStripeBlock(
                                    worldX,
                                    worldZ,
                                    boundaryBlockA,
                                    boundaryMetaA,
                                    boundaryBlockB,
                                    boundaryMetaB);
                            if (stripe.block != null && stripe.block != Blocks.air) {
                                ebs.func_150818_a(localX, yLocal, localZ, stripe.block);
                                ebs.setExtBlockMetadata(localX, yLocal, localZ, stripe.meta);
                            }
                        }

                        if (prevBoundaryZ) {
                            int localZ = 15;
                            int worldX = (chunkX << 4) + localX;
                            int worldZ = (chunkZ << 4) + localZ;

                            StripeBlock stripe = getStripeBlock(
                                    worldX,
                                    worldZ,
                                    boundaryBlockA,
                                    boundaryMetaA,
                                    boundaryBlockB,
                                    boundaryMetaB);
                            if (stripe.block != null && stripe.block != Blocks.air) {
                                ebs.func_150818_a(localX, yLocal, localZ, stripe.block);
                                ebs.setExtBlockMetadata(localX, yLocal, localZ, stripe.meta);
                            }
                        }
                    }
                }
            }

            // --- Center block generation --- 适配奇数宽度完美正中心
            if (cfg.isCenterEnabled() && intervalX > 0 && intervalZ > 0) {
                Block centerBlock = cfg.getCenterBlockResolved();
                int centerMeta = cfg.getCenterMeta();
                if (centerBlock != null && centerBlock != Blocks.air) {
                    int modCX = mod(chunkX, periodX);
                    int modCZ = mod(chunkZ, periodZ);

                    if (modCX < intervalX && modCZ < intervalZ) {
                        int regionWidthBlocks = intervalX * 16;
                        int regionDepthBlocks = intervalZ * 16;

                        int centerRegionX = regionWidthBlocks / 2;
                        int centerRegionZ = regionDepthBlocks / 2;

                        int blockStartX = modCX * 16;
                        int blockStartZ = modCZ * 16;

                        if (centerRegionX >= blockStartX && centerRegionX < blockStartX + 16
                            && centerRegionZ >= blockStartZ && centerRegionZ < blockStartZ + 16) {

                            int lx = centerRegionX - blockStartX;
                            int lz = centerRegionZ - blockStartZ;

                            for (int surfaceLevel : surfaceLevels) {
                                int yChunk = surfaceLevel >> 4;
                                int yLocal = surfaceLevel & 15;
                                ExtendedBlockStorage ebs = chunk.getBlockStorageArray()[yChunk];
                                if (ebs == null) {
                                    ebs = new ExtendedBlockStorage(surfaceLevel & ~15, true);
                                    chunk.getBlockStorageArray()[yChunk] = ebs;
                                }
                                ebs.func_150818_a(lx, yLocal, lz, centerBlock);
                                ebs.setExtBlockMetadata(lx, yLocal, lz, centerMeta);
                            }
                        }
                    }
                }
            }
        }

        if (chunkX == 0 && chunkZ == 0) {
            int platformLevel = this.world.getAverageGroundLevel();
            int yChunk = platformLevel >> 4;
            ExtendedBlockStorage ebs = chunk.getBlockStorageArray()[yChunk];
            if (ebs == null) {
                ebs = new ExtendedBlockStorage(platformLevel & ~15, true);
                chunk.getBlockStorageArray()[yChunk] = ebs;
            }
            for (int z = 4; z < 13; z++) {
                for (int x = 4; x < 13; x++) {
                    ebs.func_150818_a(x, platformLevel & 15, z, Blocks.double_stone_slab);
                }
            }
        }

        if (savedBiomeId < 0 || !Objects.equals(savedBiomeName, world.getConfig().getBiomeId())) {
            savedBiomeName = world.getConfig().getBiomeId();
            savedBiomeId = world.getConfig().getRawBiomeId();
        }

        Arrays.fill(chunk.getBiomeArray(), (byte) savedBiomeId);
        chunk.generateSkylightMap();

        return chunk;
    }

    private List<Integer> getExposedSurfaceLevels(List<FlatLayerInfo> layers, int worldHeight) {
        boolean[] solid = new boolean[worldHeight];

        for (FlatLayerInfo info : layers) {
            Block block = info.func_151536_b();
            if (block == null || block == Blocks.air) {
                continue;
            }

            int startY = MathHelper.clamp_int(info.getMinY(), 0, worldHeight);
            int endY = MathHelper.clamp_int(info.getMinY() + info.getLayerCount(), 0, worldHeight);
            for (int y = startY; y < endY; y++) {
                solid[y] = true;
            }
        }

        List<Integer> surfaceLevels = new ArrayList<>();
        for (int y = 0; y < worldHeight; y++) {
            if (solid[y] && (y + 1 >= worldHeight || !solid[y + 1])) {
                surfaceLevels.add(y);
            }
        }
        return surfaceLevels;
    }

    @Desugar
    private record StripeBlock(Block block, int meta) {}

    private StripeBlock getStripeBlock(int worldX, int worldZ, Block blockA, int metaA, Block blockB, int metaB) {
        boolean useA = ((worldX + worldZ) & 1) == 0;

        if (useA) {
            if (blockA != null && blockA != Blocks.air) {
                return new StripeBlock(blockA, metaA);
            }
            if (blockB != null && blockB != Blocks.air) {
                return new StripeBlock(blockB, metaB);
            }
        } else {
            if (blockB != null && blockB != Blocks.air) {
                return new StripeBlock(blockB, metaB);
            }
            if (blockA != null && blockA != Blocks.air) {
                return new StripeBlock(blockA, metaA);
            }
        }

        return new StripeBlock(null, 0);
    }

    private int mod(int a, int b) {
        int m = a % b;
        return m < 0 ? m + b : m;
    }

    private void generateGapInChunk(Chunk chunk, int chunkX, int chunkZ, int surfaceLevel, DimensionConfig cfg,
            boolean isGapX, boolean isGapZ, int periodX, int periodZ, int gapWidth, int intervalX, int intervalZ) {
        int yChunk = surfaceLevel >> 4;
        ExtendedBlockStorage ebs = chunk.getBlockStorageArray()[yChunk];
        if (ebs == null) {
            ebs = new ExtendedBlockStorage(surfaceLevel & ~15, true);
            chunk.getBlockStorageArray()[yChunk] = ebs;
        }

        DimensionConfig.GapPreset preset = cfg.getGapPreset();
        Block gapBlockA = cfg.getGapBlockAResolved();
        int gapMetaA = cfg.getGapMetaA();
        Block gapBlockB = cfg.getGapBlockBResolved();
        int gapMetaB = cfg.getGapMetaB();
        Block gapBlockC = cfg.getGapBlockCResolved();
        int gapMetaC = cfg.getGapMetaC();

        if (gapBlockA == null || gapBlockA == Blocks.air) return;

        int gapWidthBlocks = gapWidth * 16;
        int yLocal = surfaceLevel & 15;

        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                int worldX = (chunkX << 4) + localX;
                int worldZ = (chunkZ << 4) + localZ;

                Block block = gapBlockA;
                int meta = gapMetaA;

                if (preset == DimensionConfig.GapPreset.ROAD) {
                    boolean isIntersection = isGapX && isGapZ;
                    boolean hasStripe = gapBlockB != null && gapBlockB != Blocks.air;
                    if (isIntersection) {
                        if (hasStripe) {
                            int gapOffsetX = mod(chunkX, periodX) - intervalX;
                            int offsetX = gapOffsetX * 16 + localX;
                            int gapOffsetZ = mod(chunkZ, periodZ) - intervalZ;
                            int offsetZ = gapOffsetZ * 16 + localZ;
                            boolean onEdgeX = offsetX == 0 || offsetX == gapWidthBlocks - 1;
                            boolean onEdgeZ = offsetZ == 0 || offsetZ == gapWidthBlocks - 1;
                            if (onEdgeX && onEdgeZ) {
                                block = gapBlockB;
                                meta = gapMetaB;
                            }
                        }
                    } else {
                        if (isGapX && periodX > 0) {
                            int gapChunkOffset = mod(chunkX, periodX) - intervalX;
                            int offsetInGap = gapChunkOffset * 16 + localX;
                            boolean isMainAxisRoad = (chunkZ == 0);
                            StripeBlock road = getRoadBlock(
                                    offsetInGap, worldZ, gapWidthBlocks,
                                    gapBlockA, gapMetaA, gapBlockB, gapMetaB, gapBlockC, gapMetaC,
                                    true, isMainAxisRoad);
                            block = road.block;
                            meta = road.meta;
                        } else if (isGapZ && periodZ > 0) {
                            int gapChunkOffset = mod(chunkZ, periodZ) - intervalZ;
                            int offsetInGap = gapChunkOffset * 16 + localZ;
                            boolean isMainAxisRoad = (chunkX == 0);
                            StripeBlock road = getRoadBlock(
                                    offsetInGap, worldX, gapWidthBlocks,
                                    gapBlockA, gapMetaA, gapBlockB, gapMetaB, gapBlockC, gapMetaC,
                                    false, isMainAxisRoad);
                            block = road.block;
                            meta = road.meta;
                        }
                    }
                }

                if (block != null && block != Blocks.air) {
                    ebs.func_150818_a(localX, yLocal, localZ, block);
                    ebs.setExtBlockMetadata(localX, yLocal, localZ, meta);
                }
            }
        }
    }

    // 新版：奇数宽度公路 + 主轴(XZ)双线 + 普通道路单线
    private StripeBlock getRoadBlock(int offsetInGap, int alongRoad, int gapWidthBlocks,
                                     Block blockA, int metaA, Block blockB, int metaB,
                                     Block blockC, int metaC,
                                     boolean isXRoad, boolean isMainAxisRoad) {
        boolean hasStripe = blockB != null && blockB != Blocks.air;
        boolean hasDash = blockC != null && blockC != Blocks.air;

        int roadWidth = (gapWidthBlocks % 2 == 0) ? gapWidthBlocks - 1 : gapWidthBlocks;
        offsetInGap = Math.min(offsetInGap, roadWidth - 1);

        if (isMainAxisRoad) {
            if (hasStripe) {
                if (offsetInGap == 0 || offsetInGap == 1 ||
                    offsetInGap == roadWidth - 1 || offsetInGap == roadWidth - 2) {
                    return new StripeBlock(blockB, metaB);
                }
            }
            if (hasDash && roadWidth >= 5) {
                int center = roadWidth / 2;
                if ((offsetInGap == center || offsetInGap == center + 1) && mod(alongRoad + 2, 8) < 5) {
                    return new StripeBlock(blockC, metaC);
                }
            }
            return new StripeBlock(blockA, metaA);
        }

        if (hasStripe && (offsetInGap == 0 || offsetInGap == roadWidth - 1)) {
            return new StripeBlock(blockB, metaB);
        }
        if (hasDash && roadWidth >= 3) {
            int center = roadWidth / 2;
            if (offsetInGap == center && mod(alongRoad + 2, 8) < 4) {
                return new StripeBlock(blockC, metaC);
            }
        }

        return new StripeBlock(blockA, metaA);
    }

    @Override
    public Chunk loadChunk(int x, int z) {
        return this.provideChunk(x, z);
    }

    @Override
    public void populate(IChunkProvider provider, int chunkX, int chunkZ) {
        BiomeGenBase biome = this.world.worldObj.getBiomeGenForCoords(chunkX * 16 + 16, chunkZ * 16 + 16);
        this.random.setSeed(this.seed);
        long i1 = this.random.nextLong() / 2L * 2L + 1L;
        long j1 = this.random.nextLong() / 2L * 2L + 1L;
        this.random.setSeed((long) chunkX * i1 + (long) chunkZ * j1 ^ this.seed);
        if (this.world.getConfig().isGeneratingVegetation()) {
            biome.decorate(this.world.worldObj, this.random, chunkX * 16, chunkZ * 16);
        }
        if (this.world.getConfig().isGeneratingTrees() && TerrainGen.decorate(
                world.worldObj,
                random,
                chunkX * 16,
                chunkZ * 16,
                DecorateBiomeEvent.Decorate.EventType.TREE)) {
            int x = chunkX * 16 + random.nextInt(16) + 8;
            int z = chunkZ * 16 + random.nextInt(16) + 8;
            int y = this.world.worldObj.getHeightValue(x, z);
            WorldGenAbstractTree worldgenabstracttree = BiomeGenBase.plains.func_150567_a(random);
            worldgenabstracttree.setScale(1.0D, 1.0D, 1.0D);

            if (worldgenabstracttree.generate(world.worldObj, random, x, y, z)) {
                worldgenabstracttree.func_150524_b(world.worldObj, random, x, y, z);
            }
        }
    }

    @Override
    public boolean saveChunks(boolean saveAllChunks, IProgressUpdate progress) {
        return true;
    }

    @Override
    public boolean unloadQueuedChunks() {
        return false;
    }

    @Override
    public boolean canSave() {
        return true;
    }

    @Override
    public String makeString() {
        return "PersonalWorldSource";
    }

    @Override
    public List<BiomeGenBase.SpawnListEntry> getPossibleCreatures(EnumCreatureType type, int x, int y, int z) {
        return Collections.emptyList();
    }

    @Override
    public ChunkPosition func_147416_a(World world, String structureType, int x, int y, int z) {
        return null;
    }

    @Override
    public int getLoadedChunkCount() {
        return 0;
    }

    @Override
    public void recreateStructures(int x, int z) {}

    @Override
    public void saveExtraData() {}
}