package com.qwertys.billboard;

import com.qwertys.billboard.block.BillboardBlock;
import com.qwertys.billboard.block.BillboardBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModRegistry {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, QwertysBillboard.MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, QwertysBillboard.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, QwertysBillboard.MODID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, QwertysBillboard.MODID);

    public static final RegistryObject<BillboardBlock> BILLBOARD = BLOCKS.register("billboard", BillboardBlock::new);

    public static final RegistryObject<Item> BILLBOARD_ITEM = ITEMS.register("billboard",
            () -> new BlockItem(BILLBOARD.get(), new Item.Properties()));

    @SuppressWarnings("DataFlowIssue")
    public static final RegistryObject<BlockEntityType<BillboardBlockEntity>> BILLBOARD_BE = BLOCK_ENTITIES.register("billboard",
            () -> BlockEntityType.Builder.of(BillboardBlockEntity::new, BILLBOARD.get()).build(null));

    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.qwertys_billboard"))
            .icon(() -> new ItemStack(BILLBOARD_ITEM.get()))
            .displayItems((params, out) -> out.accept(BILLBOARD_ITEM.get()))
            .build());

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
        TABS.register(bus);
    }

    private ModRegistry() {}
}
