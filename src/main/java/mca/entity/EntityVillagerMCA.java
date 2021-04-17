package mca.entity;

import static mca.core.Constants.EMPTY_UUID;
import static net.minecraftforge.fml.common.ObfuscationReflectionHelper.getPrivateValue;
import static net.minecraftforge.fml.common.ObfuscationReflectionHelper.setPrivateValue;

import java.util.ArrayList;
import java.util.List;

import io.netty.buffer.ByteBuf;
import mca.actions.AbstractAction;
import mca.actions.ActionAttackResponse;
import mca.actions.ActionCombat;
import mca.actions.ActionSleep;
import mca.actions.ActionUpdateMood;
import mca.core.Constants;
import mca.core.MCA;
import mca.core.minecraft.ItemsMCA;
import mca.data.NBTPlayerData;
import mca.data.PlayerMemory;
import mca.data.TransitiveVillagerData;
import mca.enums.EnumBabyState;
import mca.enums.EnumDialogueType;
import mca.enums.EnumGender;
import mca.enums.EnumMarriageState;
import mca.enums.EnumMovementState;
import mca.enums.EnumProfession;
import mca.enums.EnumProfessionSkinGroup;
import mca.enums.EnumRelation;
import mca.items.ItemBaby;
import mca.items.ItemMemorial;
import mca.items.ItemVillagerEditor;
import mca.packets.PacketOpenGUIOnEntity;
import mca.util.Either;
import mca.util.Utilities;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.block.Block;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.EntityAIMoveIndoors;
import net.minecraft.entity.ai.EntityAIOpenDoor;
import net.minecraft.entity.ai.EntityAIRestrictOpenDoor;
import net.minecraft.entity.ai.EntityAISwimming;
import net.minecraft.entity.ai.EntityAITasks;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.init.MobEffects;
import net.minecraft.init.SoundEvents;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.pathfinding.PathNavigateGround;
import net.minecraft.potion.PotionEffect;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EnumHand;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.village.MerchantRecipe;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;
import net.minecraftforge.fml.common.registry.IEntityAdditionalSpawnData;
import net.minecraftforge.fml.common.registry.VillagerRegistry;
import net.minecraftforge.fml.common.registry.VillagerRegistry.VillagerProfession;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import radixcore.constant.Font.Color;
import radixcore.math.Point3D;
import radixcore.modules.RadixLogic;

/**
 * The main class of MCA's villager. The class itself handles events, getters, setters, etc.
 * overridden from Minecraft. Also any events/actions that can be performed on a villager.
 * 
 * To avoid an absurdly large class, the rest of the villager is split into 2 components:
 * 
 * The VillagerBehaviors object handles custom villager behaviors that run each tick.
 * 
 * The VillagerAttributes object holds all villager data and their getters/setters.
 */
public class EntityVillagerMCA extends EntityVillager implements IEntityAdditionalSpawnData
{
	@SideOnly(Side.CLIENT)
	public boolean isInteractionGuiOpen;

	private int swingProgressTicks;
	public final VillagerAttributes attributes;
	private final VillagerBehaviors behaviors;
	private final Profiler profiler;

	// Used for hooking into vanilla trades
	private int vanillaProfessionId;
	private static final int FIELD_INDEX_BUYING_PLAYER = 6;
	private static final int FIELD_INDEX_TIME_UNTIL_RESET = 8;
	private static final int FIELD_INDEX_NEEDS_INITIALIZATION = 9;
	private static final int FIELD_INDEX_IS_WILLING_TO_MATE = 10;
	private static final int FIELD_INDEX_WEALTH = 11;
	private static final int FIELD_INDEX_LAST_BUYING_PLAYER = 12;
	
	public EntityVillagerMCA(World world) 
	{
		super(world);
		
		profiler = world.profiler;
		attributes = new VillagerAttributes(this);
		attributes.initialize();
		behaviors = new VillagerBehaviors(this);
		
		addAI();
	}

	public void addAI()
	{
		this.tasks.taskEntries.clear();

        ((PathNavigateGround)this.getNavigator()).setCanSwim(true);
		this.tasks.addTask(0, new EntityAISwimming(this));
		this.tasks.addTask(3, new EntityAIRestrictOpenDoor(this));
		this.tasks.addTask(4, new EntityAIOpenDoor(this, true));

		int maxHealth = attributes.getProfessionSkinGroup() == EnumProfessionSkinGroup.Guard ? MCA.getConfig().guardMaxHealth : MCA.getConfig().villagerMaxHealth;
		getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH).setBaseValue(maxHealth);

		if (this.getHealth() > maxHealth || attributes.getProfessionSkinGroup() == EnumProfessionSkinGroup.Guard)
		{
			this.setHealth(maxHealth);
		}

		if (attributes.getProfessionSkinGroup() != EnumProfessionSkinGroup.Guard)
		{
			this.tasks.addTask(2, new EntityAIMoveIndoors(this));
		}
	}

	private void updateSwinging()
	{
		if (attributes.getIsSwinging())
		{
			swingProgressTicks++;

			if (swingProgressTicks >= 8)
			{
				swingProgressTicks = 0;
				attributes.setIsSwinging(false);
			}
		}

		else
		{
			swingProgressTicks = 0;
		}

		swingProgress = (float) swingProgressTicks / (float) 8;
	}

	@Override
    protected void entityInit()
    {
        super.entityInit();
    }
    
	@Override
	public void onUpdate()
	{
		super.onUpdate();

		profiler.startSection("MCA Villager Update");
		behaviors.onUpdate();
		updateSwinging();
		
		if (!world.isRemote)
		{
			attributes.incrementTicksAlive();

			//Tick player memories
			for (PlayerMemory memory : attributes.getPlayerMemories().values())
			{
				memory.doTick();
			}

			//Tick babies in attributes.getInventory().
			for (int i = 0; i < attributes.getInventory().getSizeInventory(); i++)
			{
				ItemStack stack = attributes.getInventory().getStackInSlot(i);

				if (stack.getItem() instanceof ItemBaby)
				{
					ItemBaby item = (ItemBaby)stack.getItem();
					item.onUpdate(stack, world, this, 1, false);
				}
			}

			//Check if inventory should be opened for player.
			if (attributes.getDoOpenInventory())
			{
				final EntityPlayer player = world.getClosestPlayerToEntity(this, 10.0D);

				if (player != null)
				{
					player.openGui(MCA.getInstance(), Constants.GUI_ID_INVENTORY, world, (int)posX, (int)posY, (int)posZ);
				}

				attributes.setDoOpenInventory(false);
			}
		}
		
		profiler.endSection();
	}
	
	@Override
	public boolean processInteract(EntityPlayer player, EnumHand hand)
	{
		if (getRidingEntity() == player) //Dismounts from a player on right-click
		{
			dismountRidingEntity();
			dismountEntity(player);
			return true;
		}

		if (!world.isRemote)
		{
			ItemStack heldItem = player.getHeldItem(hand);
			Item item = heldItem.getItem();
			
			if (player.capabilities.isCreativeMode && item instanceof ItemMemorial && !heldItem.hasTagCompound())
			{
				TransitiveVillagerData transitiveData = new TransitiveVillagerData(attributes);
				NBTTagCompound stackNBT = new NBTTagCompound();
				stackNBT.setUniqueId("ownerUUID", player.getUniqueID());
				stackNBT.setString("ownerName", player.getName());
				stackNBT.setInteger("relation", attributes.getPlayerMemory(player).getRelation().getId());
				transitiveData.writeToNBT(stackNBT);
				
				heldItem.setTagCompound(stackNBT);
				
				this.setDead();
			}
			
			else
			{
				int guiId = item instanceof ItemVillagerEditor ? Constants.GUI_ID_EDITOR : Constants.GUI_ID_INTERACT;
				MCA.getPacketHandler().sendPacketToPlayer(new PacketOpenGUIOnEntity(this.getEntityId(), guiId), (EntityPlayerMP) player);
			}
		}

		return true;
	}

	@Override
	public void onDeath(DamageSource damageSource) 
	{
		super.onDeath(damageSource);

		if (!world.isRemote)
		{
			//Switch to the sleeping skin and disable all chores/toggle AIs so they won't move
			behaviors.disableAllToggleActions();
			getBehavior(ActionSleep.class).transitionSkinState(true);
			
			//The death of a villager negatively modifies the mood of nearby villagers
			for (EntityVillagerMCA human : RadixLogic.getEntitiesWithinDistance(EntityVillagerMCA.class, this, 20))
			{
				human.getBehavior(ActionUpdateMood.class).modifyMoodLevel(-2.0F);
			}

			//Drop all items in the inventory
			for (int i = 0; i < attributes.getInventory().getSizeInventory(); i++)
			{
				ItemStack stack = attributes.getInventory().getStackInSlot(i);

				if (stack != null)
				{
					entityDropItem(stack, 1.0F);
				}
			}
			
			//Reset the marriage stats of the player/villager this one was married to
			//If married to a player, this player takes priority in receiving the memorial item for revival.
			boolean memorialDropped = false;
			
			if (attributes.isMarriedToAPlayer()) 	
			{
				NBTPlayerData playerData = MCA.getPlayerData(world, attributes.getSpouseUUID());
				
				playerData.setMarriageState(EnumMarriageState.NOT_MARRIED);
				playerData.setSpouseName("");
				playerData.setSpouseUUID(EMPTY_UUID);
				
				//Just in case something is added here later, be sure we're not false
				if (!memorialDropped)
				{
					createMemorialChest(attributes.getPlayerMemoryWithoutCreating(attributes.getSpouseUUID()), ItemsMCA.BROKEN_RING);
					memorialDropped = true;
				}
			}

			else if (attributes.isMarriedToAVillager())
			{
				EntityVillagerMCA partner = attributes.getVillagerSpouseInstance();

				if (partner != null)
				{
					partner.endMarriage();
				}
			}

			//Alert parents/spouse of the death if they are online and handle dropping memorials
			//Test against new iteration of player memory list each time to ensure the proper order
			//of handling notifications and memorial spawning
			
			for (PlayerMemory memory : attributes.getPlayerMemories().values())
			{
				//Alert parents and spouse of the death.
				if (memory.getUUID().equals(attributes.getSpouseUUID()) || attributes.isPlayerAParent(memory.getUUID()))
				{
					EntityPlayer player = world.getPlayerEntityByUUID(memory.getUUID());
					
					//If we hit a parent
					if (attributes.isPlayerAParent(memory.getUUID()) && !memorialDropped)
					{
						createMemorialChest(memory, attributes.getGender() == EnumGender.MALE ? ItemsMCA.TOY_TRAIN : ItemsMCA.CHILDS_DOLL);
						memorialDropped = true;
					}
					
					if (player != null) //The player may not be online
					{
						player.sendMessage(new TextComponentString(Color.RED + attributes.getTitle(player) + " has died."));
					}
				}
			}
		}
	}

	private void createMemorialChest(PlayerMemory memory, ItemMemorial memorialItem)
	{
		Point3D nearestAir = RadixLogic.getNearestBlock(this, 3, Blocks.AIR);
    	
    	if (nearestAir == null)
    	{
    		MCA.getLog().warn("No available location to spawn villager death chest for " + this.getName());
    	}
    	
    	else
    	{
    		int y = nearestAir.iY();
    		Block block = Blocks.AIR;
    		
    		while (block == Blocks.AIR)
    		{
    			y--;
    			block = world.getBlockState(new BlockPos(nearestAir.iX(), y, nearestAir.iZ())).getBlock();
    		}
    		
    		y += 1;
    		world.setBlockState(new BlockPos(nearestAir.iX(), y, nearestAir.iZ()), Blocks.CHEST.getDefaultState());
    		
    		try
    		{
    			TileEntityChest chest = (TileEntityChest) world.getTileEntity(nearestAir.toBlockPos());
    			TransitiveVillagerData data = new TransitiveVillagerData(attributes);
    			ItemStack memorialStack = new ItemStack(memorialItem);
    			NBTTagCompound stackNBT = new NBTTagCompound();
    			
    			stackNBT.setString("ownerName", memory.getPlayerName());
    			stackNBT.setUniqueId("ownerUUID", memory.getUUID());
    			stackNBT.setInteger("ownerRelation", memory.getRelation().getId());
    			data.writeToNBT(stackNBT);
    			memorialStack.setTagCompound(stackNBT);
    			
    			chest.setInventorySlotContents(0, memorialStack);
    			MCA.getLog().info("Spawned villager death chest at: " + nearestAir.iX() + ", " + y + ", " + nearestAir.iZ());
    		}
    		
    		catch (Exception e)
    		{
    			MCA.getLog().error("Error spawning villager death chest: " + e.getMessage());
    			return;
    		}
    	}
	}
    
	@Override
	protected void updateAITasks()
	{
		ActionSleep sleepAI = getBehavior(ActionSleep.class);
		EnumMovementState moveState = attributes.getMovementState();
		boolean isSleeping = sleepAI.getIsSleeping();

		if (isSleeping)
		{
			// Minecraft 1.8 moved the execution of tasks out of updateAITasks and into EntityAITasks.updateTasks().
			// Get the 'tickCount' value per tick and set it to 1 when we don't want tasks to execute. This prevents
			// The AI tasks from ever triggering an update.
			ObfuscationReflectionHelper.setPrivateValue(EntityAITasks.class, tasks, 1, 4);
		}

		if (!isSleeping && (moveState == EnumMovementState.MOVE || moveState == EnumMovementState.FOLLOW))
		{
			super.updateAITasks();
		}

		if (moveState == EnumMovementState.STAY && !isSleeping)
		{
			tasks.onUpdateTasks();
			getLookHelper().onUpdateLook();
		}

		if (moveState == EnumMovementState.STAY || isSleeping)
		{
			getNavigator().clearPathEntity();
		}
	}
	
	@Override
	protected void damageEntity(DamageSource damageSource, float damageAmount)
	{
		super.damageEntity(damageSource, damageAmount);
		
		behaviors.getAction(ActionAttackResponse.class).startResponse(damageSource.getImmediateSource());
		behaviors.getAction(ActionSleep.class).onDamage();
	}

	@Override
	public void writeEntityToNBT(NBTTagCompound nbt) 
	{
		super.writeEntityToNBT(nbt);
		behaviors.writeToNBT(nbt);
		attributes.writeToNBT(nbt);
	}

	@Override
	public void readEntityFromNBT(NBTTagCompound nbt)
	{
		super.readEntityFromNBT(nbt);
		behaviors.readFromNBT(nbt);
		attributes.readFromNBT(nbt);
		addAI();
	}

	@Override
	public void writeSpawnData(ByteBuf buffer) 
	{
		attributes.writeSpawnData(buffer);
	}

	@Override
	public void readSpawnData(ByteBuf buffer) 
	{
		attributes.readSpawnData(buffer);
	}

	@Override
	public ITextComponent getDisplayName()
	{
		return new TextComponentString(getName());
	}

	@Override
	protected SoundEvent getAmbientSound()
	{
		return null;
	}

	@Override
	protected SoundEvent getHurtSound(DamageSource source) 
	{
		return attributes.getIsInfected() ? SoundEvents.ENTITY_ZOMBIE_HURT : null;
	}

	@Override
	protected SoundEvent getDeathSound() 
	{
		return attributes.getIsInfected() ? SoundEvents.ENTITY_ZOMBIE_DEATH : null;
	}

	@Override
	public boolean canBePushed()
	{
		final ActionSleep sleepAI = behaviors.getAction(ActionSleep.class);		
		return !sleepAI.getIsSleeping();
	}

	@Override
	protected boolean canDespawn() 
	{
		return false;
	}

	public void sayRaw(String text, EntityPlayer target)
	{
		final StringBuilder sb = new StringBuilder();

		if (MCA.getConfig().villagerChatPrefix != null && !MCA.getConfig().villagerChatPrefix.equals("null"))
		{
			sb.append(MCA.getConfig().villagerChatPrefix);
		}

		sb.append(attributes.getTitle(target));
		sb.append(": ");
		sb.append(text);

		if (target != null)
		{
			target.sendMessage(new TextComponentString(sb.toString()));
		}

		behaviors.onSay();
	}

	public void say(String phraseId, EntityPlayer target, Object... arguments)
	{
		if (target == null)
		{
			return;
		}

		if (attributes.getIsInfected()) //Infected villagers moan when they speak, and will not say anything else.
		{
			String zombieMoan = RadixLogic.getBooleanWithProbability(33) ? "Raagh..." : RadixLogic.getBooleanWithProbability(33) ? "Ughh..." : "Argh-gur...";
			target.sendMessage(new TextComponentString(attributes.getTitle(target) + ": " + zombieMoan));
			this.playSound(SoundEvents.ENTITY_ZOMBIE_AMBIENT, 0.5F, rand.nextFloat() + 0.5F);
		}

		else
		{
			final StringBuilder sb = new StringBuilder();

			//Handle chat prefix.
			if (MCA.getConfig().villagerChatPrefix != null && !MCA.getConfig().villagerChatPrefix.equals("null"))
			{
				sb.append(MCA.getConfig().villagerChatPrefix);
			}

			//Add title and text.
			sb.append(attributes.getTitle(target));
			sb.append(": ");
			sb.append(MCA.getLocalizer().getString(phraseId, arguments));

			target.sendMessage(new TextComponentString(sb.toString()));

			behaviors.onSay();
		}
	}

	public void say(String phraseId, EntityPlayer target)
	{
		say(phraseId, target, this, target);
	}

	/**
	 * Sets the given entity to be the spouse of the current villager. This is symmetric against the provided entity.
	 * If null is provided, this villager's spouse information will be reset. This is **NOT** symmetric.
	 * 
	 * @param 	either	Either object containing an MCA villager or a player.
	 */
	public void startMarriage(Either<EntityVillagerMCA, EntityPlayer> either)
	{
		if (either.getLeft() != null)
		{
			EntityVillagerMCA spouse = either.getLeft();

			attributes.setSpouseName(spouse.attributes.getName());
			attributes.setSpouseUUID(spouse.getUniqueID());
			attributes.setSpouseGender(spouse.attributes.getGender());
			attributes.setMarriageState(EnumMarriageState.MARRIED_TO_VILLAGER);

			spouse.attributes.setSpouseName(this.attributes.getName());
			spouse.attributes.setSpouseUUID(this.getUniqueID());
			spouse.attributes.setSpouseGender(this.attributes.getGender());
			spouse.attributes.setMarriageState(EnumMarriageState.MARRIED_TO_VILLAGER);
			
			getBehaviors().onMarriageToVillager();
		}

		else if (either.getRight() != null)
		{
			EntityPlayer player = either.getRight();
			NBTPlayerData playerData = MCA.getPlayerData(player);
			PlayerMemory memory = attributes.getPlayerMemory(player);
			
			attributes.setSpouseName(player.getName());
			attributes.setSpouseUUID(player.getUniqueID());
			attributes.setSpouseGender(playerData.getGender());
			attributes.setMarriageState(EnumMarriageState.MARRIED_TO_PLAYER);
			memory.setDialogueType(EnumDialogueType.SPOUSE);
			memory.setRelation(attributes.getGender() == EnumGender.MALE ? EnumRelation.HUSBAND : EnumRelation.WIFE);
			
			playerData.setSpouseName(this.getName());
			playerData.setSpouseGender(attributes.getGender());
			playerData.setSpouseUUID(this.getUniqueID());
			playerData.setMarriageState(EnumMarriageState.MARRIED_TO_VILLAGER);

			getBehaviors().onMarriageToPlayer();
		}
		
		else
		{
			throw new IllegalArgumentException("Marriage target cannot be null");
		}
	}

	public void endMarriage()
	{
		//Reset spouse information back to default
		attributes.setSpouseName("");
		attributes.setSpouseUUID(EMPTY_UUID);
		attributes.setSpouseGender(EnumGender.UNASSIGNED);
		attributes.setMarriageState(EnumMarriageState.NOT_MARRIED);

		getBehaviors().onMarriageEnded();
	}
	
	public void halt()
	{
		getNavigator().clearPathEntity();
	
		moveForward = 0.0F;
		moveStrafing = 0.0F;
		motionX = 0.0D;
		motionY = 0.0D;
		motionZ = 0.0D;
	}

	public void facePosition(Point3D position)
	{
		double midX = position.dX() - this.posX;
	    double midZ = position.dZ() - this.posZ;
	    double d1 = 0;
	
	    double d3 = (double)MathHelper.sqrt(midX * midX + midZ * midZ);
	    float f2 = (float)(Math.atan2(midZ, midX) * 180.0D / Math.PI) - 90.0F;
	    float f3 = (float)(-(Math.atan2(d1, d3) * 180.0D / Math.PI));
	    this.rotationPitch = this.updateRotation(this.rotationPitch, f3, 16.0F);
	    this.rotationYaw = this.updateRotation(this.rotationYaw, f2, 16.0F);
	}

	private float updateRotation(float p_70663_1_, float p_70663_2_, float p_70663_3_)
	{
	    float f3 = MathHelper.wrapDegrees(p_70663_2_ - p_70663_1_);
	
	    if (f3 > p_70663_3_)
	    {
	        f3 = p_70663_3_;
	    }
	
	    if (f3 < -p_70663_3_)
	    {
	        f3 = -p_70663_3_;
	    }
	
	    return p_70663_1_ + f3;
	}

	public VillagerBehaviors getBehaviors() 
	{
		return behaviors;
	}

	public <T extends AbstractAction> T getBehavior(Class<T> clazz)
	{
		return this.behaviors.getAction(clazz);
	}
	
	@Override
	public ItemStack getHeldItem(EnumHand hand)
	{
		EnumBabyState babyState = attributes.getBabyState();
		EnumProfession profession = attributes.getProfessionEnum();
		
		if (attributes.getIsInfected())
		{
			return ItemStack.EMPTY;
		}

		else if (babyState != EnumBabyState.NONE)
		{
			return new ItemStack(babyState == EnumBabyState.MALE ? ItemsMCA.BABY_BOY : ItemsMCA.BABY_GIRL);
		}

		else if (profession == EnumProfession.Guard)
		{
			return new ItemStack(Items.IRON_SWORD);
		}

		else if (profession == EnumProfession.Archer)
		{
			return new ItemStack(Items.BOW);
		}

		else if (attributes.getHeldItemSlot() != -1 && behaviors.isToggleActionActive())
		{
			return attributes.getInventory().getStackInSlot(attributes.getHeldItemSlot());
		}

		else if (attributes.getInventory().contains(ItemsMCA.BABY_BOY) || attributes.getInventory().contains(ItemsMCA.BABY_GIRL))
		{
			int slot = attributes.getInventory().getFirstSlotContainingItem(ItemsMCA.BABY_BOY);
			slot = slot == -1 ? attributes.getInventory().getFirstSlotContainingItem(ItemsMCA.BABY_GIRL) : slot;

			if (slot != -1)
			{
				return attributes.getInventory().getStackInSlot(slot);
			}
		}

		//Warriors, spouses, and player children all use weapons from the combat AI.
		else if (profession == EnumProfession.Warrior || attributes.isMarriedToAPlayer() || profession == EnumProfession.Child)
		{
			return getBehavior(ActionCombat.class).getHeldItem();
		}
		
		return ItemStack.EMPTY;
	}
	
	public void setHeldItem(Item item)
	{
		setHeldItem(EnumHand.MAIN_HAND, new ItemStack(item));
	}
	
	public boolean damageHeldItem(int amount)
	{
		try
		{
			ItemStack heldItem = getHeldItem(EnumHand.MAIN_HAND);

			if (heldItem != null)
			{
				Item item = heldItem.getItem();
				int slot = attributes.getInventory().getFirstSlotContainingItem(item);

				ItemStack itemInSlot = attributes.getInventory().getStackInSlot(slot);

				if (itemInSlot != null)
				{
					itemInSlot.damageItem(amount, this);

					if (itemInSlot.getCount() == 0)
					{
						behaviors.disableAllToggleActions();
						attributes.getInventory().setInventorySlotContents(slot, ItemStack.EMPTY);
						return true;
					}

					else
					{
						attributes.getInventory().setInventorySlotContents(slot, itemInSlot);
						return false;
					}
				}
			}
		}

		catch (Exception e)
		{
			e.printStackTrace();
		}

		return false;
	}
	
	@Override
	public Iterable<ItemStack> getHeldEquipment()
	{
		List<ItemStack> heldEquipment = new ArrayList<ItemStack>();
		heldEquipment.add(getHeldItem(EnumHand.MAIN_HAND));
		return heldEquipment;
	}

	@Override
	public Iterable<ItemStack> getArmorInventoryList()
	{
		List<ItemStack> armorInventory = new ArrayList<ItemStack>();
		armorInventory.add(attributes.getInventory().getStackInSlot(39));
		armorInventory.add(attributes.getInventory().getStackInSlot(38));
		armorInventory.add(attributes.getInventory().getStackInSlot(37));
		armorInventory.add(attributes.getInventory().getStackInSlot(36));

		return armorInventory;
	}

	@Override
	public ItemStack getItemStackFromSlot(EntityEquipmentSlot slotIn)
	{
		switch (slotIn)
		{
		case HEAD: return attributes.getInventory().getStackInSlot(36);
		case CHEST: return attributes.getInventory().getStackInSlot(37);
		case LEGS: return attributes.getInventory().getStackInSlot(38);
		case FEET: return attributes.getInventory().getStackInSlot(39);
		case MAINHAND: return getHeldItem(EnumHand.MAIN_HAND);
		case OFFHAND: return ItemStack.EMPTY;
		}

		return ItemStack.EMPTY;
	}

	@Override
	public int getTotalArmorValue()
	{
		int value = 0;

		for (int i = 36; i < 40; i++)
		{
			final ItemStack stack = attributes.getInventory().getStackInSlot(i);

			if (stack != null && stack.getItem() instanceof ItemArmor)
			{
				value += ((ItemArmor)stack.getItem()).damageReduceAmount;
			}
		}

		return value;
	}

	@Override
	public void damageArmor(float amount)
	{
		for (int i = 36; i < 40; i++)
		{
			final ItemStack stack = attributes.getInventory().getStackInSlot(i);

			if (stack != null && stack.getItem() instanceof ItemArmor)
			{
				stack.damageItem((int) amount, this);
			}
		}	
	}
	
	public void swingItem() 
	{
		this.swingArm(EnumHand.MAIN_HAND);
	}
	
	@Override
	public void swingArm(EnumHand hand)
	{
		if (!attributes.getIsSwinging() || swingProgressTicks >= 8 / 2 || swingProgressTicks < 0)
		{
			swingProgressTicks = -1;
			attributes.setIsSwinging(true);
		}
	}
	
	public void cureInfection()
	{
		attributes.setIsInfected(false);
		addPotionEffect(new PotionEffect(MobEffects.REGENERATION, 200, 0));
        world.playEvent((EntityPlayer)null, 1027, new BlockPos((int)this.posX, (int)this.posY, (int)this.posZ), 0);
		Utilities.spawnParticlesAroundEntityS(EnumParticleTypes.VILLAGER_HAPPY, this, 16);
	}

	public boolean isInOverworld()
	{
		return world.provider.getDimension() == 0;
	}

	public Profiler getProfiler()
	{
		return profiler;
	}

	public void setHitboxSize(float width, float height)
	{
		this.setSize(width, height);
	}
	
	@Override
	public String getName()
	{
		return this.attributes.getName();
	}
	
	//
	// Overrides from EntityVillager that allow trades to work.
	// Issues arose from the profession not being set properly.
	//
	@Override
	public void setProfession(int professionId) 
	{
		this.vanillaProfessionId = professionId;
	}
	
    @Deprecated
    @Override
    public int getProfession()
    {
    	return this.vanillaProfessionId;
    }

    @Override
    public void setProfession(net.minecraftforge.fml.common.registry.VillagerRegistry.VillagerProfession prof)
    {
    	this.vanillaProfessionId = VillagerRegistry.getId(prof);
    }

    @Override
    public VillagerRegistry.VillagerProfession getProfessionForge()
    {
    	VillagerRegistry.VillagerProfession profession = VillagerRegistry.getById(this.vanillaProfessionId); 
    	
    	if (profession == null) {
    		return VillagerRegistry.getById(0);
    	}
    	
    	return profession;
    }

    @Override
	public void useRecipe(MerchantRecipe recipe)
	{
        recipe.incrementToolUses();
        int i = 3 + this.rand.nextInt(4);

        EntityPlayer buyingPlayer = getPrivateValue(EntityVillager.class, this, FIELD_INDEX_BUYING_PLAYER);
        
        if (recipe.getToolUses() == 1 || this.rand.nextInt(5) == 0)
        {
        	//timeUntilReset = 40;
        	setEntityVillagerField(FIELD_INDEX_TIME_UNTIL_RESET, Integer.valueOf(40));
        	//needsInitialization = true;
        	setEntityVillagerField(FIELD_INDEX_NEEDS_INITIALIZATION, true);
        	//isWillingToMate = true; (replaced with false to prevent any possible vanilla villager mating)
        	setEntityVillagerField(FIELD_INDEX_IS_WILLING_TO_MATE, false);

            if (buyingPlayer != null) //this.buyingPlayer != null
            {
                //this.lastBuyingPlayer = this.buyingPlayer.getUniqueID();
            	setEntityVillagerField(FIELD_INDEX_LAST_BUYING_PLAYER, buyingPlayer.getUniqueID());
            }
            else
            {
            	//this.lastBuyingPlayer = null;
            	setEntityVillagerField(FIELD_INDEX_LAST_BUYING_PLAYER, null);
            }

            i += 5;
        }

        if (recipe.getItemToBuy().getItem() == Items.EMERALD)
        {
        	//wealth += recipe.getItemToBuy().getCount();
        	int wealth = getEntityVillagerField(FIELD_INDEX_WEALTH);
        	setEntityVillagerField(FIELD_INDEX_WEALTH, wealth + recipe.getItemToBuy().getCount());
        }

        if (recipe.getRewardsExp())
        {
            this.world.spawnEntity(new EntityXPOrb(this.world, this.posX, this.posY + 0.5D, this.posZ, i));
        }

        if (buyingPlayer instanceof EntityPlayerMP)
        {
            CriteriaTriggers.VILLAGER_TRADE.trigger((EntityPlayerMP)buyingPlayer, this, recipe.getItemToSell());
        }
	}
	
	private <T, E> void setEntityVillagerField(int fieldIndex, Object value) {
		setPrivateValue(EntityVillager.class, this, value, fieldIndex);
	}
	
	private <T, E> T getEntityVillagerField(int fieldIndex) {
		return getPrivateValue(EntityVillager.class, this, fieldIndex);
	}
}
package mca.entity;

import static mca.core.Constants.EMPTY_UUID;
import static mca.core.Constants.EMPTY_UUID_OPT;

import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.google.common.base.Optional;

import io.netty.buffer.ByteBuf;
import mca.actions.ActionStoryProgression;
import mca.core.Constants;
import mca.core.MCA;
import mca.data.NBTPlayerData;
import mca.data.PlayerMemory;
import mca.data.TransitiveVillagerData;
import mca.enums.EnumBabyState;
import mca.enums.EnumDialogueType;
import mca.enums.EnumGender;
import mca.enums.EnumMarriageState;
import mca.enums.EnumMovementState;
import mca.enums.EnumPersonality;
import mca.enums.EnumProfession;
import mca.enums.EnumProfessionSkinGroup;
import mca.enums.EnumProgressionStep;
import mca.inventory.VillagerInventory;
import mca.packets.PacketSetSize;
import mca.util.Either;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import radixcore.modules.RadixNettyIO;

public class VillagerAttributes
{
	private final EntityVillagerMCA villager;
	private final EntityDataManager dataManager;

	private static final DataParameter<String> NAME = EntityDataManager.<String>createKey(EntityVillagerMCA.class, DataSerializers.STRING);
	private static final DataParameter<String> HEAD_TEXTURE = EntityDataManager.<String>createKey(EntityVillagerMCA.class, DataSerializers.STRING);
	private static final DataParameter<String> CLOTHES_TEXTURE = EntityDataManager.<String>createKey(EntityVillagerMCA.class, DataSerializers.STRING);
	private static final DataParameter<Integer> PROFESSION = EntityDataManager.<Integer>createKey(EntityVillagerMCA.class, DataSerializers.VARINT);
	private static final DataParameter<Integer> PERSONALITY = EntityDataManager.<Integer>createKey(EntityVillagerMCA.class, DataSerializers.VARINT);
	private static final DataParameter<Integer> GENDER = EntityDataManager.<Integer>createKey(EntityVillagerMCA.class, DataSerializers.VARINT);
	private static final DataParameter<String> SPOUSE_NAME = EntityDataManager.<String>createKey(EntityVillagerMCA.class, DataSerializers.STRING);
	private static final DataParameter<Optional<UUID>> SPOUSE_UUID = EntityDataManager.<Optional<UUID>>createKey(EntityVillagerMCA.class, DataSerializers.OPTIONAL_UNIQUE_ID);
	private static final DataParameter<Integer> SPOUSE_GENDER = EntityDataManager.<Integer>createKey(EntityVillagerMCA.class, DataSerializers.VARINT);
	private static final DataParameter<String> MOTHER_NAME = EntityDataManager.<String>createKey(EntityVillagerMCA.class, DataSerializers.STRING);
	private static final DataParameter<Optional<UUID>> MOTHER_UUID = EntityDataManager.<Optional<UUID>>createKey(EntityVillagerMCA.class, DataSerializers.OPTIONAL_UNIQUE_ID);
	private static final DataParameter<Integer> MOTHER_GENDER = EntityDataManager.<Integer>createKey(EntityVillagerMCA.class, DataSerializers.VARINT);
	private static final DataParameter<String> FATHER_NAME = EntityDataManager.<String>createKey(EntityVillagerMCA.class, DataSerializers.STRING);
	private static final DataParameter<Optional<UUID>> FATHER_UUID = EntityDataManager.<Optional<UUID>>createKey(EntityVillagerMCA.class, DataSerializers.OPTIONAL_UNIQUE_ID);
	private static final DataParameter<Integer> FATHER_GENDER = EntityDataManager.<Integer>createKey(EntityVillagerMCA.class, DataSerializers.VARINT);
	private static final DataParameter<Integer> BABY_STATE = EntityDataManager.<Integer>createKey(EntityVillagerMCA.class, DataSerializers.VARINT);
	private static final DataParameter<Integer> MOVEMENT_STATE = EntityDataManager.<Integer>createKey(EntityVillagerMCA.class, DataSerializers.VARINT);
	private static final DataParameter<Boolean> IS_CHILD = EntityDataManager.<Boolean>createKey(EntityVillagerMCA.class, DataSerializers.BOOLEAN);
	private static final DataParameter<Integer> AGE = EntityDataManager.<Integer>createKey(EntityVillagerMCA.class, DataSerializers.VARINT);
	private static final DataParameter<Float> SCALE_HEIGHT = EntityDataManager.<Float>createKey(EntityVillagerMCA.class, DataSerializers.FLOAT);
	private static final DataParameter<Float> SCALE_WIDTH = EntityDataManager.<Float>createKey(EntityVillagerMCA.class, DataSerializers.FLOAT);
	private static final DataParameter<Boolean> DO_DISPLAY = EntityDataManager.<Boolean>createKey(EntityVillagerMCA.class, DataSerializers.BOOLEAN);
	private static final DataParameter<Boolean> IS_SWINGING = EntityDataManager.<Boolean>createKey(EntityVillagerMCA.class, DataSerializers.BOOLEAN);
	private static final DataParameter<Integer> HELD_ITEM_SLOT = EntityDataManager.<Integer>createKey(EntityVillagerMCA.class, DataSerializers.VARINT);
	private static final DataParameter<Boolean> IS_INFECTED = EntityDataManager.<Boolean>createKey(EntityVillagerMCA.class, DataSerializers.BOOLEAN);
	private static final DataParameter<Boolean> DO_OPEN_INVENTORY = EntityDataManager.<Boolean>createKey(EntityVillagerMCA.class, DataSerializers.BOOLEAN);
	private static final DataParameter<Integer> MARRIAGE_STATE = EntityDataManager.<Integer>createKey(EntityVillagerMCA.class, DataSerializers.VARINT);

	private int timesWarnedForLowHearts;
	private int ticksAlive;
	private Map<UUID, PlayerMemory> playerMemories;
	private final VillagerInventory inventory;
	
	public VillagerAttributes(EntityVillagerMCA villager)
	{
		this.villager = villager;
		this.dataManager = villager.getDataManager();
		playerMemories = new HashMap<UUID, PlayerMemory>();
		inventory = new VillagerInventory();
	}

	public VillagerAttributes(NBTTagCompound nbt) 
	{
		this.villager = null;
		this.dataManager = null;
		this.inventory = null;
		playerMemories = new HashMap<UUID, PlayerMemory>();
		readFromNBT(nbt);
	}

	public void initialize()
	{
		dataManager.register(NAME, "Steve");
		dataManager.register(HEAD_TEXTURE, "");
		dataManager.register(CLOTHES_TEXTURE, "");
		dataManager.register(PROFESSION, EnumProfession.Farmer.getId());
		dataManager.register(PERSONALITY, EnumPersonality.FRIENDLY.getId());
		dataManager.register(GENDER, EnumGender.MALE.getId());
		dataManager.register(SPOUSE_NAME, "N/A");
		dataManager.register(SPOUSE_UUID, Constants.EMPTY_UUID_OPT);
		dataManager.register(SPOUSE_GENDER, EnumGender.UNASSIGNED.getId());
		dataManager.register(MOTHER_NAME, "N/A");
		dataManager.register(MOTHER_UUID, Constants.EMPTY_UUID_OPT);
		dataManager.register(MOTHER_GENDER, EnumGender.UNASSIGNED.getId());
		dataManager.register(FATHER_NAME, "N/A");
		dataManager.register(FATHER_UUID, Constants.EMPTY_UUID_OPT);
		dataManager.register(FATHER_GENDER, EnumGender.UNASSIGNED.getId());
		dataManager.register(BABY_STATE, EnumBabyState.NONE.getId());
		dataManager.register(MOVEMENT_STATE, EnumMovementState.MOVE.getId());
		dataManager.register(IS_CHILD, Boolean.valueOf(false));
		dataManager.register(AGE, Integer.valueOf(0));
		dataManager.register(SCALE_HEIGHT, Float.valueOf(0));
		dataManager.register(SCALE_WIDTH, Float.valueOf(0));
		dataManager.register(DO_DISPLAY, Boolean.valueOf(false));
		dataManager.register(IS_SWINGING, Boolean.valueOf(false));
		dataManager.register(HELD_ITEM_SLOT, Integer.valueOf(0));
		dataManager.register(IS_INFECTED, Boolean.valueOf(false));
		dataManager.register(DO_OPEN_INVENTORY, Boolean.valueOf(false));
		dataManager.register(MARRIAGE_STATE, Integer.valueOf(0));
	}

	/*
	 * Copies all data from a given transitive villager data object.
	 */
	public void copyFrom(TransitiveVillagerData data)
	{
		setName(data.getName());
		setHeadTexture(data.getHeadTexture());
		setClothesTexture(data.getClothesTexture());
		setProfession(data.getProfession());
		setPersonality(data.getPersonality());
		setGender(data.getGender());
		setSpouseUUID(data.getSpouseUUID());
		setSpouseGender(data.getSpouseGender());
		setSpouseName(data.getSpouseName());
		setMotherUUID(data.getMotherUUID());
		setMotherGender(data.getMotherGender());
		setMotherName(data.getMotherName());
		setFatherUUID(data.getFatherUUID());
		setFatherGender(data.getFatherGender());
		setFatherName(data.getFatherName());
		setBabyState(data.getBabyState());
		setMovementState(data.getMovementState());
		setIsChild(data.getIsChild());
		setAge(data.getAge());
		setScaleHeight(data.getScaleHeight());
		setScaleWidth(data.getScaleWidth());
		setDoDisplay(data.getDoDisplay());
		setIsSwinging(data.getIsSwinging());
		setHeldItemSlot(data.getHeldItemSlot());
		setIsInfected(data.getIsInfected());
		setDoOpenInventory(data.getDoOpenInventory());
		setMarriageState(data.getMarriageState());
	}
	
	public String getName()
	{
		return dataManager.get(NAME);
	}

	public void setName(String name)
	{
		dataManager.set(NAME, name);
	}

	public String getHeadTexture()
	{
		return dataManager.get(HEAD_TEXTURE);
	}

	public void setHeadTexture(String texture)
	{
		dataManager.set(HEAD_TEXTURE, texture);
	}

	public String getClothesTexture()
	{
		return dataManager.get(CLOTHES_TEXTURE);
	}

	public void setClothesTexture(String texture)
	{
		dataManager.set(CLOTHES_TEXTURE, texture);
	}

	public void assignRandomSkin()
	{
		if (this.getGender() == EnumGender.UNASSIGNED)
		{
			Throwable t = new Throwable();
			MCA.getLog().error("Attempted to randomize skin on unassigned gender villager.");
			MCA.getLog().error(t);
		}

		else
		{
			EnumProfessionSkinGroup skinGroup = this.getProfessionSkinGroup();
			String skin = this.getGender() == EnumGender.MALE ? skinGroup.getRandomMaleSkin() : skinGroup.getRandomFemaleSkin();
			setHeadTexture(skin);
			setClothesTexture(skin);
		}
	}

	public void assignRandomScale()
	{

	}

	public EnumProfession getProfessionEnum()
	{
		return EnumProfession.getProfessionById(dataManager.get(PROFESSION));
	}

	public EnumProfessionSkinGroup getProfessionSkinGroup()
	{
		return EnumProfession.getProfessionById(dataManager.get(PROFESSION).intValue()).getSkinGroup();
	}

	public void setProfession(EnumProfession profession)
	{
		dataManager.set(PROFESSION, profession.getId());
		villager.setProfession(profession.getId());
	}

	public EnumPersonality getPersonality()
	{
		return EnumPersonality.getById(dataManager.get(PERSONALITY));
	}

	public void setPersonality(EnumPersonality personality)
	{
		dataManager.set(PERSONALITY, personality.getId());
	}

	public EnumGender getGender()
	{
		return EnumGender.byId(dataManager.get(GENDER));
	}

	public void setGender(EnumGender gender)
	{
		dataManager.set(GENDER, gender.getId());
	}

	public String getSpouseName()
	{
		return dataManager.get(SPOUSE_NAME);
	}

	public UUID getSpouseUUID()
	{
		return dataManager.get(SPOUSE_UUID).or(EMPTY_UUID);
	}

	public EnumGender getSpouseGender()
	{
		return EnumGender.byId(dataManager.get(SPOUSE_GENDER));
	}


	/* Performs an engagement between this villager and provided player. 
	 * DOES NOT handle nulls. To end an engagement, call setSpouse with null.
	 * */
	public void setFiancee(EntityPlayer player) 
	{
		if (player == null) throw new Error("Engagement player cannot be null");

		NBTPlayerData playerData = MCA.getPlayerData(player);

		dataManager.set(SPOUSE_NAME, player.getName());
		dataManager.set(SPOUSE_UUID, Optional.of(player.getUniqueID()));
		dataManager.set(SPOUSE_GENDER, playerData.getGender().getId());

		setMarriageState(EnumMarriageState.ENGAGED);

		playerData.setSpouseName(this.getName());
		playerData.setSpouseGender(this.getGender());
		playerData.setSpouseUUID(villager.getUniqueID());
		playerData.setMarriageState(EnumMarriageState.ENGAGED);

		//Prevent story progression when engaged to a player
		villager.getBehavior(ActionStoryProgression.class).setProgressionStep(EnumProgressionStep.FINISHED);
	}

	public EntityVillagerMCA getVillagerSpouseInstance()
	{
		for (Object obj : villager.world.loadedEntityList)
		{
			if (obj instanceof EntityVillagerMCA)
			{
				EntityVillagerMCA villager = (EntityVillagerMCA)obj;

				if (villager.getUniqueID().equals(getSpouseUUID()))
				{
					return villager;
				}
			}
		}

		return null;
	}

	public EntityPlayer getPlayerSpouseInstance()
	{
		for (Object obj : villager.world.playerEntities)
		{
			final EntityPlayer player = (EntityPlayer)obj;

			if (player.getUniqueID().equals(this.getSpouseUUID()))
			{
				return player;
			}
		}

		return null;
	}

	public String getMotherName()
	{
		return dataManager.get(MOTHER_NAME);
	}

	public UUID getMotherUUID()
	{
		return dataManager.get(MOTHER_UUID).or(EMPTY_UUID);
	}

	public EnumGender getMotherGender()
	{
		return EnumGender.byId(dataManager.get(MOTHER_GENDER));
	}

	public void setMotherName(String name)
	{
		if (name == null) {
			name = "N/A";
		}
		
		dataManager.set(MOTHER_NAME, name);
	}
	
	public void setMotherUUID(UUID uuid)
	{
		dataManager.set(MOTHER_UUID, Optional.of(uuid));
	}
	
	public void setMotherGender(EnumGender gender)
	{
		dataManager.set(MOTHER_GENDER, gender.getId());
	}
	
	public void setMother(Either<EntityVillagerMCA, EntityPlayer> either)
	{
		if (either == null)
		{
			dataManager.set(MOTHER_NAME, "");
			dataManager.set(MOTHER_UUID, EMPTY_UUID_OPT);
			dataManager.set(MOTHER_GENDER, EnumGender.UNASSIGNED.getId());
		}

		else if (either.getLeft() != null)
		{
			EntityVillagerMCA mother = either.getLeft();
			dataManager.set(MOTHER_NAME, mother.attributes.getName());
			dataManager.set(MOTHER_UUID, Optional.of(mother.getUniqueID()));
			dataManager.set(MOTHER_GENDER, mother.attributes.getGender().getId());
		}

		else if (either.getRight() != null)
		{
			EntityPlayer player = either.getRight();
			NBTPlayerData data = MCA.getPlayerData(player);

			dataManager.set(MOTHER_NAME, player.getName());
			dataManager.set(MOTHER_UUID, Optional.of(player.getUniqueID()));
			dataManager.set(MOTHER_GENDER, data.getGender().getId());
		}
	}

	public String getFatherName()
	{
		return dataManager.get(FATHER_NAME);
	}

	public UUID getFatherUUID()
	{
		return dataManager.get(FATHER_UUID).or(EMPTY_UUID);
	}

	public EnumGender getFatherGender()
	{
		return EnumGender.byId(dataManager.get(FATHER_GENDER));
	}

	public void setFatherName(String name)
	{
		if (name == null) {
			name = "N/A";
		}
		
		dataManager.set(FATHER_NAME, name);
	}
	
	public void setFatherUUID(UUID uuid)
	{
		dataManager.set(FATHER_UUID, Optional.of(uuid));
	}
	
	public void setFatherGender(EnumGender gender)
	{
		dataManager.set(FATHER_GENDER, gender.getId());
	}
	
	public void setFather(Either<EntityVillagerMCA, EntityPlayer> either)
	{
		if (either == null)
		{
			dataManager.set(FATHER_NAME, "");
			dataManager.set(FATHER_UUID, EMPTY_UUID_OPT);
			dataManager.set(FATHER_GENDER, EnumGender.UNASSIGNED.getId());
		}

		else if (either.getLeft() != null)
		{
			EntityVillagerMCA father = either.getLeft();
			dataManager.set(FATHER_NAME, father.attributes.getName());
			dataManager.set(FATHER_UUID, Optional.of(father.getUniqueID()));
			dataManager.set(FATHER_GENDER, father.attributes.getGender().getId());
		}

		else if (either.getRight() != null)
		{
			EntityPlayer player = either.getRight();
			NBTPlayerData data = MCA.getPlayerData(player);

			dataManager.set(FATHER_NAME, player.getName());
			dataManager.set(FATHER_UUID, Optional.of(player.getUniqueID()));
			dataManager.set(FATHER_GENDER, data.getGender().getId());
		}
	}

	public EnumBabyState getBabyState()
	{
		return EnumBabyState.fromId(dataManager.get(BABY_STATE));
	}

	public void setBabyState(EnumBabyState state)
	{
		dataManager.set(BABY_STATE, state.getId());
	}

	public EnumMovementState getMovementState()
	{
		return EnumMovementState.fromId(dataManager.get(MOVEMENT_STATE));
	}

	public void setMovementState(EnumMovementState state)
	{
		dataManager.set(MOVEMENT_STATE, state.getId());
	}

	public boolean getIsChild()
	{
		return dataManager.get(IS_CHILD);
	}

	public void setIsChild(boolean isChild)
	{
		dataManager.set(IS_CHILD, isChild);

		EnumDialogueType newDialogueType = isChild ? EnumDialogueType.CHILD : EnumDialogueType.ADULT;
		EnumDialogueType targetReplacementType = isChild ? EnumDialogueType.ADULT : EnumDialogueType.CHILD;

		for (PlayerMemory memory : playerMemories.values())
		{
			if (memory.getDialogueType() == targetReplacementType)
			{
				memory.setDialogueType(newDialogueType);
			}
		}
	}

	public boolean isChildOfAVillager() 
	{
		// If we can't find data for the mother and father, the child's parents
		// must be other villagers.
		NBTPlayerData motherData = MCA.getPlayerData(villager.world, getMotherUUID());
		NBTPlayerData fatherData = MCA.getPlayerData(villager.world, getFatherUUID());

		return motherData == null && fatherData == null;
	}

	public int getAge()
	{
		return dataManager.get(AGE).intValue();
	}

	public void setAge(int age)
	{
		dataManager.set(AGE, age);
	}

	public float getScaleHeight()
	{
		return dataManager.get(SCALE_HEIGHT);
	}

	public void setScaleHeight(float value)
	{
		dataManager.set(SCALE_HEIGHT, value);
	}

	public float getScaleWidth()
	{
		return dataManager.get(SCALE_WIDTH);
	}

	public void setScaleWidth(float value)
	{
		dataManager.set(SCALE_WIDTH, value);
	}

	public boolean getDoDisplay()
	{
		return dataManager.get(DO_DISPLAY);
	}

	public void setDoDisplay(boolean value)
	{
		dataManager.set(DO_DISPLAY, value);
	}

	public boolean getIsSwinging()
	{
		return dataManager.get(IS_SWINGING);
	}

	public void setIsSwinging(boolean value)
	{
		dataManager.set(IS_SWINGING, value);
	}

	@Deprecated
	public boolean getIsMale()
	{
		return getGender() == EnumGender.MALE;
	}

	@Deprecated
	public String getParentNames() 
	{
		return this.getMotherName() + "|" + this.getFatherName();
	}

	@Deprecated
	public boolean getIsMarried()
	{
		return getMarriageState() == EnumMarriageState.MARRIED_TO_VILLAGER || getMarriageState() == EnumMarriageState.MARRIED_TO_PLAYER;
	}

	public boolean getCanBeHired(EntityPlayer player) 
	{
		return getPlayerSpouseInstance() != player && (getProfessionSkinGroup() == EnumProfessionSkinGroup.Farmer || 
				getProfessionSkinGroup() == EnumProfessionSkinGroup.Miner || 
				getProfessionSkinGroup() == EnumProfessionSkinGroup.Warrior);
	}


	public boolean getDoOpenInventory()
	{
		return dataManager.get(DO_OPEN_INVENTORY);
	}

	public void setDoOpenInventory(boolean value)
	{
		dataManager.set(DO_OPEN_INVENTORY, value);
	}

	public EnumMarriageState getMarriageState()
	{
		return EnumMarriageState.byId(dataManager.get(MARRIAGE_STATE));
	}

	/*package-private*/ void setSpouseUUID(UUID uuid)
	{
		dataManager.set(SPOUSE_UUID, Optional.of(uuid));
	}

	/*package-private*/ void setSpouseName(String value)
	{
		dataManager.set(SPOUSE_NAME, value);
	}

	/*package-private*/ void setSpouseGender(EnumGender gender)
	{
		dataManager.set(SPOUSE_GENDER, gender.getId());
	}

	/*package-private*/ void setParentName(boolean mother, String value)
	{
		DataParameter field = mother ? MOTHER_NAME : FATHER_NAME;
		dataManager.set(field, value);
	}

	/*package-private*/ void setParentUUID(boolean mother, UUID uuid)
	{
		DataParameter field = mother ? MOTHER_UUID: FATHER_UUID;
		dataManager.set(field, Optional.of(uuid));
	}

	/*package-private*/ void setParentGender(boolean mother, EnumGender gender)
	{
		DataParameter field = mother ? MOTHER_GENDER : FATHER_GENDER;
		dataManager.set(field, gender.getId());
	}

	/*package-private*/ void setMarriageState(EnumMarriageState state)
	{
		dataManager.set(MARRIAGE_STATE, state.getId());
	}


	public int getHeldItemSlot()
	{
		return dataManager.get(HELD_ITEM_SLOT);
	}

	public void setHeldItemSlot(int value)
	{
		dataManager.set(HELD_ITEM_SLOT, value);
	}

	public boolean getIsInfected()
	{
		return dataManager.get(IS_INFECTED);
	}

	public void setIsInfected(boolean value)
	{
		dataManager.set(IS_INFECTED, value);
	}


	public double getBaseAttackDamage() 
	{
		switch (getPersonality())
		{
		case STRONG: return 2.0D;
		case CONFIDENT: return 1.0D;
		default: 
			if (getProfessionSkinGroup() == EnumProfessionSkinGroup.Guard)
			{
				return 5.0D;
			}

			else
			{
				return 0.5D;
			}
		}
	}

	public void assignRandomName()
	{
		if (getGender() == EnumGender.MALE)
		{
			setName(MCA.getLocalizer().getString("name.male"));
		}

		else
		{
			setName(MCA.getLocalizer().getString("name.female"));
		}
	}

	public void assignRandomGender()
	{
		setGender(villager.world.rand.nextBoolean() ? EnumGender.MALE : EnumGender.FEMALE);
	}

	public void assignRandomProfession()
	{
		setProfession(EnumProfession.getAtRandom());
	}

	public void assignRandomPersonality() 
	{
		setPersonality(EnumPersonality.getAtRandom());
	}

	public boolean isMarriedToAPlayer()
	{
		return getMarriageState() == EnumMarriageState.MARRIED_TO_PLAYER;
	}

	public boolean isMarriedToAVillager()
	{
		return getMarriageState() == EnumMarriageState.MARRIED_TO_VILLAGER;
	}

	public boolean getIsEngaged()
	{
		return getMarriageState() == EnumMarriageState.ENGAGED;
	}



	public boolean isPlayerAParent(EntityPlayer player)
	{
		final NBTPlayerData data = MCA.getPlayerData(player);

		if (data != null)
		{
			boolean result = getMotherUUID().equals(data.getUUID()) || getFatherUUID().equals(data.getUUID());
			return result;
		}

		else
		{
			return false;
		}
	}

	public boolean isPlayerAParent(UUID uuid)
	{
		final NBTPlayerData data = MCA.getPlayerData(villager.world, uuid);

		if (data != null)
		{
			return getMotherUUID() == data.getUUID() || getFatherUUID() == data.getUUID();
		}

		else
		{
			return false;
		}
	}

	public float getSpeed()
	{
		return getPersonality() == EnumPersonality.ATHLETIC ? Constants.SPEED_RUN : Constants.SPEED_WALK;
	}

	public boolean allowsHiring(EntityPlayer player) 
	{
		return getPlayerSpouseInstance() != player && (getProfessionSkinGroup() == EnumProfessionSkinGroup.Farmer || 
				getProfessionSkinGroup() == EnumProfessionSkinGroup.Miner || 
				getProfessionSkinGroup() == EnumProfessionSkinGroup.Warrior);
	}

	public boolean allowsWorkInteractions(EntityPlayer player)
	{
		final NBTPlayerData data = MCA.getPlayerData(player);
		final PlayerMemory memory = getPlayerMemory(player);

		if (data.getIsSuperUser())
		{
			return true;
		}

		else if (getIsInfected()) //Infected villagers can't use an inventory or do chores.
		{
			return false;
		}

		else if (memory.getIsHiredBy())
		{
			return true;
		}

		else if (isPlayerAParent(player))
		{
			return true;
		}

		return false;
	}

	public boolean allowsControllingInteractions(EntityPlayer player)
	{
		final NBTPlayerData data = MCA.getPlayerData(player);

		if (data.getIsSuperUser())
		{
			return true;
		}

		//Married to a player, and this player is not their spouse.
		else if (isMarriedToAPlayer() && !getSpouseUUID().equals(data.getUUID()))
		{
			return false;
		}

		else if (getIsChild())
		{
			if (isPlayerAParent(player))
			{
				return true;
			}

			else if (isChildOfAVillager())
			{
				return true;
			}

			else
			{
				return false;
			}
		}

		return true;
	}

	public boolean allowsIntimateInteractions(EntityPlayer player)
	{
		return !getIsChild() && !isPlayerAParent(player);
	}

	public int getTicksAlive()
	{
		return ticksAlive;
	}

	public void setTicksAlive(int value)
	{
		this.ticksAlive = value;
	}

	public int getLowHeartWarnings()
	{
		return timesWarnedForLowHearts;
	}

	public void incrementLowHeartWarnings()
	{
		timesWarnedForLowHearts++;
	}

	public void resetLowHeartWarnings()
	{
		timesWarnedForLowHearts = 0;
	}

	public void setPlayerMemory(EntityPlayer player, PlayerMemory memory)
	{
		playerMemories.put(player.getPersistentID(), memory);
	}

	public PlayerMemory getPlayerMemory(EntityPlayer player)
	{
		UUID playerUUID = player.getPersistentID();
		PlayerMemory returnMemory = playerMemories.get(playerUUID);

		if (returnMemory == null)
		{
			returnMemory = new PlayerMemory(villager, player);
			playerMemories.put(playerUUID, returnMemory);
		}

		return returnMemory;
	}

	public PlayerMemory getPlayerMemoryWithoutCreating(EntityPlayer player) 
	{
		return getPlayerMemoryWithoutCreating(player.getUniqueID());
	}

	public PlayerMemory getPlayerMemoryWithoutCreating(UUID playerUUID)
	{
		return playerMemories.get(playerUUID);
	}
	
	public Map<UUID, PlayerMemory> getPlayerMemories()
	{
		return playerMemories;
	}
	
	public boolean hasMemoryOfPlayer(EntityPlayer player)
	{
		return playerMemories.containsKey(player.getName());
	}

	public String getTitle(EntityPlayer player)
	{
		PlayerMemory memory = getPlayerMemory(player);

		if (memory.isRelatedToPlayer())
		{
			return MCA.getLocalizer().getString(getGender() == EnumGender.MALE ? "title.relative.male" : "title.relative.female", villager, player);
		}

		else
		{
			return MCA.getLocalizer().getString(getGender() == EnumGender.MALE ? "title.nonrelative.male" : "title.nonrelative.female", villager, player);
		}
	}

	public void writeToNBT(NBTTagCompound nbt)
	{
		//Auto save data manager values to NBT by reflection
		for (Field f : this.getClass().getDeclaredFields())
		{
			try
			{
				if (f.getType() == DataParameter.class)
				{
					Type genericType = f.getGenericType();
					String typeName = genericType.getTypeName();
					DataParameter param = (DataParameter) f.get(this);
					String paramName = f.getName();

					if (typeName.contains("Boolean"))
					{
						DataParameter<Boolean> bParam = (DataParameter<Boolean>)param;
						nbt.setBoolean(paramName, dataManager.get(bParam).booleanValue());
					}

					else if (typeName.contains("Integer"))
					{
						DataParameter<Integer> iParam = (DataParameter<Integer>)param;
						nbt.setInteger(paramName, dataManager.get(iParam).intValue());
					}

					else if (typeName.contains("String"))
					{
						DataParameter<String> sParam = (DataParameter<String>)param;
						nbt.setString(paramName, dataManager.get(sParam));
					}

					else if (typeName.contains("Float"))
					{
						DataParameter<Float> fParam = (DataParameter<Float>)param;
						nbt.setFloat(paramName, dataManager.get(fParam).floatValue());
					}

					else if (typeName.contains("Optional<java.util.UUID>"))
					{
						DataParameter<Optional<UUID>> uuParam = (DataParameter<Optional<UUID>>)param;
						nbt.setUniqueId(paramName, dataManager.get(uuParam).get());
					}

					else
					{
						throw new RuntimeException("Field type not handled while saving to NBT: " + f.getName());
					}
				}
			}

			catch (Exception e)
			{
				e.printStackTrace();
			}
		}

		nbt.setInteger("ticksAlive", ticksAlive);
		nbt.setInteger("timesWarnedForLowHearts", timesWarnedForLowHearts);
		nbt.setTag("inventory", inventory.writeInventoryToNBT());
		
		int counter = 0;
		for (Map.Entry<UUID, PlayerMemory> pair : playerMemories.entrySet())
		{
			nbt.setUniqueId("playerMemoryKey" + counter, pair.getKey());
			pair.getValue().writePlayerMemoryToNBT(nbt);
			counter++;
		}
	}

	public void readFromNBT(NBTTagCompound nbt)
	{
		//Auto read data manager values
		for (Field f : this.getClass().getDeclaredFields())
		{
			try
			{
				if (f.getType() == DataParameter.class)
				{
					Type genericType = f.getGenericType();
					String typeName = genericType.getTypeName();
					DataParameter param = (DataParameter) f.get(this);
					String paramName = f.getName();

					if (typeName.contains("Boolean"))
					{
						DataParameter<Boolean> bParam = (DataParameter<Boolean>)param;
						dataManager.set(bParam, nbt.getBoolean(paramName));
					}

					else if (typeName.contains("Integer"))
					{
						DataParameter<Integer> iParam = (DataParameter<Integer>)param;
						dataManager.set(iParam, nbt.getInteger(paramName));
					}

					else if (typeName.contains("String"))
					{
						DataParameter<String> sParam = (DataParameter<String>)param;
						dataManager.set(sParam, nbt.getString(paramName));
					}

					else if (typeName.contains("Float"))
					{
						DataParameter<Float> fParam = (DataParameter<Float>)param;
						dataManager.set(fParam, nbt.getFloat(paramName));
					}

					else if (typeName.contains("Optional<java.util.UUID>"))
					{
						DataParameter<Optional<UUID>> uuParam = (DataParameter<Optional<UUID>>)param;
						dataManager.set(uuParam, Optional.of(nbt.getUniqueId(paramName)));
					}

					else
					{
						throw new RuntimeException("Field type not handled while saving to NBT: " + f.getName());
					}
				}
			}

			catch (Exception e)
			{
				e.printStackTrace();
			}
		}

		ticksAlive = nbt.getInteger("ticksAlive");
		timesWarnedForLowHearts = nbt.getInteger("timesWarnedForLowHearts");
		inventory.readInventoryFromNBT(nbt.getTagList("inventory", 10));
		
		int counter = 0;
		
		while (true)
		{
			final UUID playerUUID = nbt.getUniqueId("playerMemoryKey" + counter);

			if (playerUUID == null || playerUUID.equals(Constants.EMPTY_UUID))
			{
				break;
			}

			else
			{
				final PlayerMemory playerMemory = new PlayerMemory(villager, playerUUID);
				playerMemory.readPlayerMemoryFromNBT(nbt);
				playerMemories.put(playerUUID, playerMemory);
				counter++;
			}
		}
	}

	public VillagerInventory getInventory() 
	{
		return inventory;
	}

	public void incrementTicksAlive() 
	{
		ticksAlive++;
	}

	public void writeSpawnData(ByteBuf buffer) 
	{
		RadixNettyIO.writeObject(buffer, playerMemories);
	}

	public void readSpawnData(ByteBuf buffer) 
	{
		Map<UUID, PlayerMemory> recvMemories = (Map<UUID, PlayerMemory>) RadixNettyIO.readObject(buffer);
		playerMemories = recvMemories;
		setDoDisplay(true);
	}

	public void setSize(float width, float height) 
	{
		villager.setHitboxSize(width, height);
		
		if (!villager.world.isRemote)
		{
			MCA.getPacketHandler().sendPacketToAllPlayers(new PacketSetSize(villager, width, height));
		}
	}

	public UUID getVillagerUUID() 
	{
		return villager.getPersistentID();
	}
}
