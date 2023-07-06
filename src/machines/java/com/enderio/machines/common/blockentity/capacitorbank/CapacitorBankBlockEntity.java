package com.enderio.machines.common.blockentity.capacitorbank;

import com.enderio.api.capacitor.FixedScalable;
import com.enderio.api.io.energy.EnergyIOMode;
import com.enderio.core.common.sync.*;
import com.enderio.machines.common.blockentity.base.MultiConfigurable;
import com.enderio.machines.common.blockentity.base.PoweredMachineBlockEntity;
import com.enderio.machines.common.blockentity.multienergy.ICapacityTier;
import com.enderio.machines.common.blockentity.multienergy.MultiEnergyNode;
import com.enderio.machines.common.blockentity.multienergy.MultiEnergyStorageWrapper;
import com.enderio.machines.common.blockentity.sync.LargeMachineEnergyDataSlot;
import com.enderio.machines.common.blockentity.sync.MachineEnergyDataSlot;
import com.enderio.machines.common.io.energy.MachineEnergyStorage;
import com.enderio.machines.common.menu.CapacitorBankMenu;
import dev.gigaherz.graph3.Graph;
import dev.gigaherz.graph3.GraphObject;
import dev.gigaherz.graph3.Mergeable;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class CapacitorBankBlockEntity extends PoweredMachineBlockEntity implements MultiConfigurable {

    public final ICapacityTier tier;

    private final MultiEnergyNode node;

    private long addedEnergy = 0;
    private long removedEnergy = 0;
    public static final int AVERAGE_IO_OVER_X_TICKS = 10;
    private final List<BlockPos> clientConfigurables = new ArrayList<>();

    private static final String DISPLAY_MODES = "displaymodes";

    private final Map<Direction, DisplayMode> displayModes = Util.make(() -> {
       Map<Direction, DisplayMode> map = new EnumMap<>(Direction.class);
       for (Direction direction: new Direction[] {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
           map.put(direction, DisplayMode.NONE);
       }
       return map;
    });

    public CapacitorBankBlockEntity(BlockEntityType<?> type, BlockPos worldPosition, BlockState blockState, ICapacityTier tier) {
        super(EnergyIOMode.Both, new FixedScalable(tier::getStorageCapacity), new FixedScalable(tier::getStorageCapacity), type, worldPosition, blockState);
        this.tier = tier;
        this.node = new MultiEnergyNode(() -> energyStorage, () -> (MultiEnergyStorageWrapper) getExposedEnergyStorage(), worldPosition);
        addDataSlot(new LongDataSlot(() -> addedEnergy, syncAddedEnergy -> addedEnergy = syncAddedEnergy, SyncMode.WORLD));
        addDataSlot(new LongDataSlot(() -> removedEnergy, syncRemovedEnergy-> removedEnergy = syncRemovedEnergy, SyncMode.WORLD));
        addDataSlot(new ConfigurablesDataSlot(SyncMode.WORLD));
        addDataSlot(new NBTSerializingDataSlot<>(() -> displayModes, modes -> saveDisplayModes(), (modes, nbt) -> loadDisplayModes(nbt), SyncMode.WORLD));

        addClientDecidingDataSlot(new NBTSerializingDataSlot<>(this::getConfigurables, list -> {
            CompoundTag nbt = new CompoundTag();
            ListTag listNbt = new ListTag();
            for (BlockPos pos: list) {
                if (pos.equals(worldPosition))
                    continue;
                if (level.getBlockEntity(pos) instanceof CapacitorBankBlockEntity capacitorBank) {
                    CompoundTag e = capacitorBank.getIOConfig().serializeNBT();
                    e.putLong("capPos", pos.asLong());
                    listNbt.add(e);
                }
            }
            nbt.put("list", listNbt);
            return nbt;
        }, (list, nbt) -> {
            if (node.getGraph() == null)
                return;
            if (nbt.contains("list", Tag.TAG_LIST)) {
                ListTag listNbt = nbt.getList("list", Tag.TAG_COMPOUND);
                for (Tag tag: listNbt) {
                    if (tag instanceof CompoundTag e) {
                        if (!e.contains("capPos", Tag.TAG_LONG))
                            continue;
                        BlockPos pos = BlockPos.of(e.getLong("capPos"));
                        if (pos.equals(worldPosition))
                            continue;
                        if (!node.getGraph().getObjects().stream().map(MultiEnergyNode.class::cast).map(node -> node.pos).toList().contains(pos))
                            continue;
                        if (level.getBlockEntity(pos) instanceof CapacitorBankBlockEntity capacitorBank) {
                            capacitorBank.getIOConfig().deserializeNBT(e);
                        }
                    }
                }
            }
        }, SyncMode.GUI));
    }

    @Override
    public EnderDataSlot<?> createEnergyDataSlot() {
        return new LargeMachineEnergyDataSlot(this::getExposedEnergyStorage, storge -> clientEnergyStorage = storge, getEnergySyncMode());
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int pContainerId, Inventory pPlayerInventory, Player pPlayer) {
        return new CapacitorBankMenu(this, pPlayerInventory, pContainerId);
    }

    @Override
    public @Nullable MachineEnergyStorage createExposedEnergyStorage() {
        return new MultiEnergyStorageWrapper(getIOConfig(), EnergyIOMode.Both, () -> tier);
    }

    @Override
    public void serverTick() {
        super.serverTick();
        if (level.getGameTime() % AVERAGE_IO_OVER_X_TICKS == 0 && node.getWrapper().get().getLastResetTime() != level.getGameTime()) {
            if (node.getGraph() != null) {
                addedEnergy = 0;
                removedEnergy = 0;
                List<GraphObject<Mergeable.Dummy>> nodes = new ArrayList<>(node.getGraph().getObjects());
                for (GraphObject<Mergeable.Dummy> object : nodes) {
                    if (object instanceof MultiEnergyNode graphNode) {
                        addedEnergy += graphNode.getWrapper().get().getAddedEnergy();
                        removedEnergy += graphNode.getWrapper().get().getRemovedEnergy();
                        graphNode.getWrapper().get().resetEnergyStats(level.getGameTime());
                    }
                }
                //Sync it back to other capacitor bank in this graph, only one can do this calculation, because each node is reset at once
                for (GraphObject<Mergeable.Dummy> object : nodes) {
                    if (object instanceof MultiEnergyNode graphNode && level.getBlockEntity(graphNode.pos) instanceof CapacitorBankBlockEntity capacitorBank) {
                        capacitorBank.addedEnergy = addedEnergy;
                        capacitorBank.removedEnergy = removedEnergy;
                    }
                }
            }
        }
        if (level.getGameTime() % 200 == hashCode() % 200 && node.getGraph() != null && List.copyOf(node.getGraph().getObjects()).indexOf(node) == 0) {
            long cumulativeEnergy = 0;
            for (GraphObject<Mergeable.Dummy> object : node.getGraph().getObjects()) {
                if (object instanceof MultiEnergyNode otherNode) {
                    cumulativeEnergy += otherNode.getInternal().get().getEnergyStored();
                }
            }
            int energyPerNode = (int)(cumulativeEnergy / node.getGraph().getObjects().size());

            for (GraphObject<Mergeable.Dummy> object : node.getGraph().getObjects()) {
                if (object instanceof MultiEnergyNode otherNode) {
                    ((MachineEnergyStorage)(otherNode.getInternal().get())).setEnergyStored(Math.min(energyPerNode, (int)Math.min(cumulativeEnergy, Integer.MAX_VALUE)));
                    cumulativeEnergy-=energyPerNode;
                }
            }
            int remainingEnergy = (int)cumulativeEnergy;
            if (remainingEnergy <= 0)
                return;
            for (GraphObject<Mergeable.Dummy> object : node.getGraph().getObjects()) {
                if (object instanceof MultiEnergyNode otherNode) {
                    int received = otherNode.getInternal().get().receiveEnergy(remainingEnergy, false);
                    remainingEnergy-=received;
                    if (remainingEnergy <= 0)
                        return;
                }
            }
        }
    }

    @Override
    protected boolean isActive() {
        return true;
    }

    @Override
    public void saveAdditional(CompoundTag pTag) {
        super.saveAdditional(pTag);
        pTag.put(DISPLAY_MODES, saveDisplayModes());
    }

    public CompoundTag saveDisplayModes() {
        CompoundTag nbt = new CompoundTag();
        for (var entry : displayModes.entrySet()) {
            nbt.putInt(entry.getKey().getName(), entry.getValue().ordinal());
        }
        return nbt;
    }

    @Override
    public void load(CompoundTag pTag) {
        super.load(pTag);
        if (pTag.contains(DISPLAY_MODES, Tag.TAG_COMPOUND))
            loadDisplayModes(pTag.getCompound(DISPLAY_MODES));
    }

    public void loadDisplayModes(CompoundTag nbt) {
        displayModes.clear();
        for (String key : nbt.getAllKeys()) {
            @Nullable
            Direction dir = Direction.byName(key);
            if (dir != null) {
                displayModes.put(dir, DisplayMode.values()[nbt.getInt(key)]);
            }
        }
    }

    @Override
    protected boolean shouldPushEnergyTo(Direction direction) {
        if (node.getGraph() == null)
            return true;
        for (GraphObject<Mergeable.Dummy> neighbour : node.getGraph().getObjects()) {
            if (neighbour instanceof MultiEnergyNode node) {
                if (node.pos.equals(worldPosition.relative(direction))) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    protected SyncMode getEnergySyncMode() {
        return SyncMode.WORLD;
    }

    @Override
    public void setRemoved() {
        if (node.getGraph() != null)
            node.getGraph().remove(node);
        super.setRemoved();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (node.getGraph() == null)
            Graph.integrate(node, List.of());
        for (Direction direction: Direction.values()) {
            if (level.getBlockEntity(worldPosition.relative(direction)) instanceof CapacitorBankBlockEntity capacitor && capacitor.tier == tier) {
                Graph.connect(node, capacitor.node);
            }
        }
    }

    @Override
    public List<BlockPos> getConfigurables() {
        return clientConfigurables;
    }

    private void setPositions(List<BlockPos> list) {
        clientConfigurables.clear(); clientConfigurables.addAll(list);
    }

    private List<BlockPos> getPositions() {
        if (node.getGraph() == null)
            return List.of();
        List<BlockPos> positions = new ArrayList<>();
        for (GraphObject<Mergeable.Dummy> object : node.getGraph().getObjects()) {
            if (object instanceof MultiEnergyNode otherNode) {
                positions.add(otherNode.pos);
            }
        }
        return positions;
    }

    private class ConfigurablesDataSlot extends EnderDataSlot<List<BlockPos>> {

        public ConfigurablesDataSlot(SyncMode mode) {
            super(CapacitorBankBlockEntity.this::getPositions, CapacitorBankBlockEntity.this::setPositions, mode);
        }

        @Override
        public CompoundTag toFullNBT() {
            CompoundTag nbt = new CompoundTag();
            ListTag list = new ListTag();
            getter().get().forEach(pos -> list.add(LongTag.valueOf(pos.asLong())));
            nbt.put("positions", list);
            return nbt;
        }

        @Override
        protected List<BlockPos> fromNBT(CompoundTag nbt) {
            if (!nbt.contains("positions", Tag.TAG_LIST))
                return List.of();
            ListTag list = nbt.getList("positions", Tag.TAG_LONG);
            List<BlockPos> positions = new ArrayList<>();
            for (Tag tag: list) {
                if (tag instanceof LongTag longTag) {
                    positions.add(BlockPos.of(longTag.getAsLong()));
                }
            }
            return positions;
        }
    }

    public boolean onShiftRightClick(Direction direction, Player player) {
        if (direction.getAxis().getPlane() == Direction.Plane.VERTICAL)
            return false;
        if (player.getMainHandItem().is(getBlockState().getBlock().asItem()) || player.getOffhandItem().is(getBlockState().getBlock().asItem()))
            return false;
        displayModes.put(direction, DisplayMode.values()[(displayModes.get(direction).ordinal()+1)%DisplayMode.values().length]);
        return true;
    }

    public long getAddedEnergy() {
        return addedEnergy / AVERAGE_IO_OVER_X_TICKS;
    }

    public long getRemovedEnergy() {
        return removedEnergy / AVERAGE_IO_OVER_X_TICKS;
    }

    public DisplayMode getDisplayMode(Direction direction) {
        if (getLevel() == null || !Block.shouldRenderFace(getBlockState(), getLevel(), worldPosition, direction, worldPosition.relative(direction)))
            return DisplayMode.NONE;
        return displayModes.get(direction);
    }
    public void setDisplayMode(Direction direction, DisplayMode mode) {
        displayModes.put(direction, mode);
    }

    @Override
    public AABB getRenderBoundingBox() {
        return AABB.ofSize(new Vec3(worldPosition.getX(), worldPosition.getY(), worldPosition.getZ()), 32, 32, 32);
    }
}