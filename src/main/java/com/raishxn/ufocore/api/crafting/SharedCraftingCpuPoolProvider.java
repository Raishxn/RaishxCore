package com.raishxn.ufocore.api.crafting;

import appeng.api.networking.IGridNodeService;
import org.jetbrains.annotations.Nullable;

/** Grid-node service that publishes a dynamically linked shared crafting pool. */
public interface SharedCraftingCpuPoolProvider extends IGridNodeService {
    @Nullable SharedCraftingCpuPool getSharedCraftingCpuPool();
}
