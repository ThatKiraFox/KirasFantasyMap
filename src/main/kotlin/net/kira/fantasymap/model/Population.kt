package net.kira.fantasymap.model

fun Biomes.arableFraction(): Float {
    return when (this) {
        Biomes.TUNDRA -> 0.02f
        Biomes.STEPPE -> 0.6f
        Biomes.GRASSLAND -> 0.7f
        Biomes.SAVANNA -> 0.35f
        Biomes.DESERT -> 0.02f
        Biomes.TROPICAL_FOREST -> 0.15f
        Biomes.TAIGA -> 0.05f
        Biomes.COLD_STEPPE -> 0.4f
        Biomes.ICE_DESERT -> 0.01f
        Biomes.BROADLEAF_FOREST -> 0.35f
        Biomes.RAINFOREST -> 0.1f
        Biomes.FOREST_STEPPE -> 0.55f
        Biomes.MIXED_FOREST -> 0.4f
        Biomes.SAVANNA_FOREST -> 0.25f
        Biomes.OCEAN -> 0f
        Biomes.SEA -> 0f
        Biomes.REEF -> 0f
        Biomes.SHALLOWS -> 0f
        Biomes.SWAMP -> 0.05f
        Biomes.FLOODPLAIN -> 0.85f
        Biomes.DRIED_LAKEBED -> 0.1f
        Biomes.OASIS -> 0.7f
        Biomes.GALLERY_FOREST -> 0.6f
        Biomes.GLACIER -> 0f
    }
}