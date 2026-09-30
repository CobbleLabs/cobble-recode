/*
 * Every Minecraft symbol Cobble Recode touches, written against Lunar 1.8.9.
 *
 * Lunar ships the 1.8.9 jar deobfuscated to MCP stable-22 names, so the
 * runtime namespace IS this namespace: genmappings.mjs is a pass-through and
 * the payload resolves the names directly through JNI - no Fabric resolver,
 * no tiny-mappings file.
 *
 * The `id` of every entry (and the order) is contract: the C++ side of the
 * bridge refers to MappedClass/MappedMethod/MappedField by these labels, so
 * entries are never removed or reordered. A symbol 1.8.9 does not have keeps
 * its id, keeps the old label as its name, and is marked optional - it
 * resolves to null and the feature that wanted it degrades. The tail of each
 * table carries NEW ids for things 1.8.9 spells differently (posX fields,
 * Timer.renderPartialTicks, ActiveRenderInfo matrices, Display size), which
 * is where the C++ reads them instead of the dead ids.
 *
 * `owner` is the DECLARING class. JNI resolves inherited members through the
 * subclass automatically, so `Entity.posX` is fine to read on a player. Where
 * the id's label names a subclass but 1.8.9 declares the member elsewhere
 * (isBlocking lives on EntityPlayer, not EntityLivingBase), the entry points
 * at the right owner and keeps the label.
 */

export const classes = {
  MinecraftClient: 'net/minecraft/client/Minecraft',
  /* 1.8.9 has no Window object; the scaled sizes come from a fresh
   * ScaledResolution each frame and the framebuffer from LWJGL Display. */
  Window: 'net/minecraft/client/gui/ScaledResolution',
  GameOptions: 'net/minecraft/client/settings/GameSettings',
  /* SimpleOption is a 1.14+ concept; the id survives for enum stability. */
  SimpleOption: 'net/minecraft/client/settings/GameSettings',
  GameRenderer: 'net/minecraft/client/renderer/EntityRenderer',
  /* The camera object is 1.9+. On 1.8.9 the frame's matrices live in
   * ActiveRenderInfo's static float arrays, so this id resolves there. */
  Camera: 'net/minecraft/client/renderer/ActiveRenderInfo',
  RenderTickCounter: 'net/minecraft/util/Timer',

  ClientPlayerEntity: 'net/minecraft/client/entity/EntityPlayerSP',
  AbstractClientPlayerEntity: 'net/minecraft/client/entity/AbstractClientPlayer',
  ClientPlayerLikeEntity: 'net/minecraft/client/entity/EntityPlayerSP',
  ClientPlayerLikeState: 'net/minecraft/client/entity/EntityPlayerSP',
  ClientPlayerInteractionManager: 'net/minecraft/client/multiplayer/PlayerControllerMP',
  ClientCommonNetworkHandler: 'net/minecraft/network/NetworkManager',
  ClientPlayNetworkHandler: 'net/minecraft/client/network/NetHandlerPlayClient',
  PlayerListEntry: 'net/minecraft/client/network/NetworkPlayerInfo',
  ClientWorld: 'net/minecraft/client/multiplayer/WorldClient',
  World: 'net/minecraft/world/World',
  BlockView: 'net/minecraft/world/IBlockAccess',
  EntityView: 'net/minecraft/world/World',
  WorldAccess: 'net/minecraft/world/World',
  Inventory: 'net/minecraft/entity/player/InventoryPlayer',
  /* 1.8.9 grabs the cursor through LWJGL Mouse; the client instead shows a
   * dummy GuiScreen, so these only back the rare direct path. */
  Mouse: 'org/lwjgl/input/Mouse',
  Screen: 'net/minecraft/client/gui/GuiScreen',
  InGameHud: 'net/minecraft/client/gui/GuiIngame',
  ChatHud: 'net/minecraft/client/gui/GuiNewChat',
  InventoryScreen: 'net/minecraft/client/gui/inventory/GuiInventory',
  KeyBinding: 'net/minecraft/client/settings/KeyBinding',
  InputUtilKey: 'net/minecraft/client/settings/KeyBinding',
  Input: 'net/minecraft/client/settings/KeyBinding',

  Entity: 'net/minecraft/entity/Entity',
  LivingEntity: 'net/minecraft/entity/EntityLivingBase',
  Attackable: 'net/minecraft/entity/EntityLivingBase',
  LimbAnimator: 'net/minecraft/entity/EntityLivingBase',
  PlayerEntity: 'net/minecraft/entity/player/EntityPlayer',
  PlayerInventory: 'net/minecraft/entity/player/InventoryPlayer',
  EntityType: 'net/minecraft/entity/Entity',

  ItemStack: 'net/minecraft/item/ItemStack',
  Item: 'net/minecraft/item/Item',
  BlockItem: 'net/minecraft/item/ItemBlock',
  AxeItem: 'net/minecraft/item/ItemAxe',
  /* 1.8.9 has no end crystals; the id survives so the enum does. */
  EndCrystalEntity: 'net/minecraft/item/ItemAxe',
  Items: 'net/minecraft/item/Item',
  ItemTags: 'net/minecraft/item/Item',
  TagKey: 'net/minecraft/item/Item',
  RegistryEntry: 'net/minecraft/item/Item',
  StatusEffects: 'net/minecraft/potion/Potion',
  StatusEffectInstance: 'net/minecraft/potion/PotionEffect',
  Blocks: 'net/minecraft/init/Blocks',
  BlockState: 'net/minecraft/block/state/IBlockState',
  AbstractBlockState: 'net/minecraft/block/state/IBlockState',
  Block: 'net/minecraft/block/Block',
  RespawnAnchorBlock: 'net/minecraft/block/Block',
  Property: 'net/minecraft/block/state/IBlockState',
  State: 'net/minecraft/block/state/IBlockState',

  Box: 'net/minecraft/util/AxisAlignedBB',
  Vec3d: 'net/minecraft/util/Vec3',
  /* BlockPos extends Vec3i in 1.8.9, so the coordinate getters come along. */
  Vec3i: 'net/minecraft/util/BlockPos',
  BlockPos: 'net/minecraft/util/BlockPos',
  Direction: 'net/minecraft/util/EnumFacing',
  HitResult: 'net/minecraft/util/MovingObjectPosition',
  HitResultType: 'net/minecraft/util/MovingObjectPosition$MovingObjectType',
  BlockHitResult: 'net/minecraft/util/MovingObjectPosition',
  EntityHitResult: 'net/minecraft/util/MovingObjectPosition',
  /* 1.8.9 has no offhand and no EnumHand; swingItem() carries the swing. */
  Hand: 'net/minecraft/util/EnumFacing',
  /* 1.8.9 has no result enums: interactions return boolean, clicks are int
   * modes. The ids stay for the C++ surface and point at real classes so the
   * resolver has something to bind; every member on them is optional/dead. */
  ActionResult: 'net/minecraft/entity/player/EntityPlayer',
  SlimeEntity: 'net/minecraft/entity/monster/EntitySlime',
  ZombieEntity: 'net/minecraft/entity/monster/EntityZombie',
  Text: 'net/minecraft/util/ChatComponentText',
  Nameable: 'net/minecraft/entity/Entity',
  Identifier: 'net/minecraft/util/ResourceLocation',
  MutableText: 'net/minecraft/util/ChatComponentText',
  Registries: 'net/minecraft/item/Item',
  Registry: 'net/minecraft/util/RegistryNamespaced',
  /* 1.8.9 plays sounds by registry path string; the SoundEvent class is 1.9+. */
  SoundEvent: 'net/minecraft/util/ResourceLocation',
  ServerInfo: 'net/minecraft/client/multiplayer/ServerData',
  ScreenHandler: 'net/minecraft/inventory/Container',
  /* 1.8.9 windowClick takes the mode as an int; ClickType is 1.9+. */
  SlotActionType: 'net/minecraft/nbt/NBTTagCompound',

  /* ---- what the player radar reads. The 1.21 skin/asset machinery collapses
   * onto what 1.8.9 actually has: NetworkPlayerInfo, a ResourceLocation and
   * the stitched TextureMap. Dead ids point at ResourceLocation to resolve
   * harmlessly. ---- */
  EquipmentSlot: 'net/minecraft/util/ResourceLocation',
  ItemEnchantmentsComponent: 'net/minecraft/nbt/NBTTagCompound',
  SkinTextures: 'net/minecraft/util/ResourceLocation',
  PlayerSkinType: 'net/minecraft/util/ResourceLocation',
  TextureAsset: 'net/minecraft/util/ResourceLocation',
  TextureManager: 'net/minecraft/client/renderer/texture/TextureManager',
  /* 1.8.9 textures are ITextureObject; getGlTextureId is declared on it. */
  AbstractTexture: 'net/minecraft/client/renderer/texture/ITextureObject',
  SpriteAtlasTexture: 'net/minecraft/util/ResourceLocation',
  AtlasManager: 'net/minecraft/util/ResourceLocation',
  Sprite: 'net/minecraft/client/renderer/texture/TextureAtlasSprite',
  BakedModelManager: 'net/minecraft/client/renderer/texture/TextureMap',
  Atlases: 'net/minecraft/util/ResourceLocation',

  /* ---- appended for the 1.8.9 port; ids are new, appended at the end ---- */
  Display: 'org/lwjgl/opengl/Display',
  NBTTagList: 'net/minecraft/nbt/NBTTagList',
  RegistrySimple: 'net/minecraft/util/RegistrySimple',
};

export const methods = [
  /* ---- client root ---- */
  /* 1.8.9 exposes the client as the static field `theMinecraft`; this method
   * never resolves and the bridge reads the field instead. */
  { id: 'MinecraftClient_getInstance', owner: 'MinecraftClient', name: 'getInstance', desc: '()Lnet/minecraft/client/Minecraft;', static: true, optional: true },
  /* FPS is the static Minecraft.debugFPS counter, read as a field. */
  { id: 'MinecraftClient_getCurrentFps', owner: 'MinecraftClient', name: 'getCurrentFps', desc: '()I', static: true, optional: true },
  { id: 'MinecraftClient_getNetworkHandler', owner: 'ClientPlayerEntity', name: 'sendQueue', desc: '()Lnet/minecraft/client/network/NetHandlerPlayClient;', optional: true },
  { id: 'MinecraftClient_getWindow', owner: 'MinecraftClient', name: 'getWindow', desc: '()Lnet/minecraft/client/util/Window;', optional: true },
  { id: 'MinecraftClient_getCameraEntity', owner: 'MinecraftClient', name: 'getRenderViewEntity', desc: '()Lnet/minecraft/entity/Entity;' },
  { id: 'MinecraftClient_getRenderTickCounter', owner: 'MinecraftClient', name: 'getRenderTickCounter', desc: '()Lnet/minecraft/client/render/RenderTickCounter;', optional: true },

  /* ---- window. ScaledResolution is constructed per frame. ---- */
  { id: 'Window_getHandle', owner: 'Window', name: 'getHandle', desc: '()J', optional: true },
  { id: 'Window_getWidth', owner: 'Window', name: 'getWidth', desc: '()I', optional: true },
  { id: 'Window_getHeight', owner: 'Window', name: 'getHeight', desc: '()I', optional: true },
  { id: 'Window_getScaledWidth', owner: 'Window', name: 'getScaledWidth', desc: '()I' },
  { id: 'Window_getScaledHeight', owner: 'Window', name: 'getScaledHeight', desc: '()I' },
  { id: 'Window_getScaleFactor', owner: 'Window', name: 'getScaleFactor', desc: '()I', optional: true },
  { id: 'Window_getFramebufferWidth', owner: 'Window', name: 'getFramebufferWidth', desc: '()I', optional: true },
  { id: 'Window_getFramebufferHeight', owner: 'Window', name: 'getFramebufferHeight', desc: '()I', optional: true },

  /* ---- options. 1.8.9 keeps plain fields; the option getters never resolve. ---- */
  { id: 'GameOptions_getMouseSensitivity', owner: 'GameOptions', name: 'getMouseSensitivity', desc: '()Lnet/minecraft/client/option/SimpleOption;', optional: true },
  { id: 'GameOptions_getFov', owner: 'GameOptions', name: 'getFov', desc: '()Lnet/minecraft/client/option/SimpleOption;', optional: true },
  { id: 'GameOptions_getBobView', owner: 'GameOptions', name: 'getBobView', desc: '()Lnet/minecraft/client/option/SimpleOption;', optional: true },
  { id: 'GameOptions_getDamageTiltStrength', owner: 'GameOptions', name: 'getDamageTiltStrength', desc: '()Lnet/minecraft/client/option/SimpleOption;', optional: true },
  { id: 'SimpleOption_getValue', owner: 'SimpleOption', name: 'getValue', desc: '()Ljava/lang/Object;', optional: true },

  /* ---- render state. The projection and view matrices are read straight
   * out of ActiveRenderInfo after the world pass has written them, so none
   * of the 1.21 accessors resolve and nothing needs reproducing: view bob
   * and damage tilt are already folded into what the frame drew. ---- */
  { id: 'GameRenderer_getFov', owner: 'GameRenderer', name: 'getFOVModifier', desc: '(FZ)F' },
  { id: 'GameRenderer_getCamera', owner: 'GameRenderer', name: 'getCamera', desc: '()Lnet/minecraft/client/render/Camera;', optional: true },
  { id: 'GameRenderer_getProjectionMatrix', owner: 'GameRenderer', name: 'getProjectionMatrix', desc: '(F)Lorg/joml/Matrix4f;', optional: true },
  { id: 'GameRenderer_getBasicProjectionMatrix', owner: 'GameRenderer', name: 'getBasicProjectionMatrix', desc: '(F)Lorg/joml/Matrix4f;', optional: true },
  { id: 'Camera_getLastTickProgress', owner: 'Camera', name: 'getLastTickProgress', desc: '()F', optional: true },
  { id: 'RenderTickCounter_getTickProgress', owner: 'RenderTickCounter', name: 'getTickProgress', desc: '(Z)F', optional: true },
  { id: 'ClientPlayerLikeEntity_getState', owner: 'ClientPlayerLikeEntity', name: 'getState', desc: '()Lnet/minecraft/client/network/ClientPlayerLikeState;', optional: true },
  { id: 'ClientPlayerLikeState_getReverseLerpedDistanceMoved', owner: 'ClientPlayerLikeState', name: 'getReverseLerpedDistanceMoved', desc: '(F)F', optional: true },
  { id: 'ClientPlayerLikeState_lerpMovement', owner: 'ClientPlayerLikeState', name: 'lerpMovement', desc: '(F)F', optional: true },

  /* ---- entity ---- */
  { id: 'Entity_getX', owner: 'Entity', name: 'posX', desc: '()D', optional: true },
  { id: 'Entity_getY', owner: 'Entity', name: 'posY', desc: '()D', optional: true },
  { id: 'Entity_getZ', owner: 'Entity', name: 'posZ', desc: '()D', optional: true },
  /* 1.8.9's eye height accessor returns a float, not a double. */
  { id: 'Entity_getEyeY', owner: 'Entity', name: 'getEyeHeight', desc: '()F', optional: true },
  { id: 'Entity_getYaw', owner: 'Entity', name: 'getYaw', desc: '()F', optional: true },
  { id: 'Entity_getPitch', owner: 'Entity', name: 'getPitch', desc: '()F', optional: true },
  { id: 'Entity_setYaw', owner: 'Entity', name: 'setYaw', desc: '(F)V', optional: true },
  { id: 'Entity_setPitch', owner: 'Entity', name: 'setPitch', desc: '(F)V', optional: true },
  { id: 'Entity_getWidth', owner: 'Entity', name: 'getWidth', desc: '()F', optional: true },
  { id: 'Entity_getHeight', owner: 'Entity', name: 'getHeight', desc: '()F', optional: true },
  { id: 'Entity_isAlive', owner: 'Entity', name: 'isEntityAlive', desc: '()Z' },
  { id: 'Entity_isInvisible', owner: 'Entity', name: 'isInvisible', desc: '()Z' },
  { id: 'Entity_isSneaking', owner: 'Entity', name: 'isSneaking', desc: '()Z' },
  { id: 'Entity_isSprinting', owner: 'Entity', name: 'isSprinting', desc: '()Z' },
  { id: 'Entity_setSprinting', owner: 'Entity', name: 'setSprinting', desc: '(Z)V' },
  { id: 'Entity_isOnGround', owner: 'Entity', name: 'isOnGround', desc: '()Z', optional: true },
  { id: 'Entity_isSpectator', owner: 'Entity', name: 'isSpectator', desc: '()Z', optional: true },
  { id: 'Entity_hasVehicle', owner: 'Entity', name: 'hasVehicle', desc: '()Z', optional: true },
  /* 1.8.9 has no lerped accessor; the capture interpolates prevPos -> pos. */
  { id: 'Entity_getLerpedPos', owner: 'Entity', name: 'getLerpedPos', desc: '(F)Lnet/minecraft/util/math/Vec3d;', optional: true },
  { id: 'Entity_getRotationVec', owner: 'Entity', name: 'getLook', desc: '(F)Lnet/minecraft/util/Vec3;' },
  { id: 'Entity_getVelocity', owner: 'Entity', name: 'getVelocity', desc: '()Lnet/minecraft/util/math/Vec3d;', optional: true },
  { id: 'Entity_setVelocity', owner: 'Entity', name: 'setVelocity', desc: '(DDD)V' },
  { id: 'Entity_distanceTo', owner: 'Entity', name: 'getDistanceToEntity', desc: '(Lnet/minecraft/entity/Entity;)F' },
  { id: 'Entity_squaredDistanceTo', owner: 'Entity', name: 'getDistanceSq', desc: '(Lnet/minecraft/util/math/Vec3d;)D', optional: true },
  { id: 'Entity_getType', owner: 'Entity', name: 'getType', desc: '()Lnet/minecraft/entity/EntityType;', optional: true },
  { id: 'Entity_getUuidAsString', owner: 'Entity', name: 'getUniqueID', desc: '()Ljava/util/UUID;' },

  /* ---- living ---- */
  { id: 'LivingEntity_getHealth', owner: 'LivingEntity', name: 'getHealth', desc: '()F' },
  { id: 'LivingEntity_getMaxHealth', owner: 'LivingEntity', name: 'getMaxHealth', desc: '()F' },
  { id: 'LivingEntity_getAbsorptionAmount', owner: 'LivingEntity', name: 'getAbsorptionAmount', desc: '()F' },
  { id: 'LivingEntity_getArmor', owner: 'LivingEntity', name: 'getTotalArmorValue', desc: '()I' },
  /* isBlocking is declared on EntityPlayer, not EntityLivingBase. */
  { id: 'LivingEntity_isBlocking', owner: 'PlayerEntity', name: 'isBlocking', desc: '()Z' },
  { id: 'LivingEntity_isDead', owner: 'LivingEntity', name: 'isDead', desc: '()Z', optional: true },
  /* 1.8.9: EntityPlayer declares the use-item state, not EntityLivingBase. */
  { id: 'LivingEntity_isUsingItem', owner: 'PlayerEntity', name: 'isUsingItem', desc: '()Z' },
  { id: 'LivingEntity_getItemUseTime', owner: 'PlayerEntity', name: 'getItemInUseDuration', desc: '()I' },
  /* 1.8.9 has no hand argument; swingItem() swings the main hand. */
  { id: 'LivingEntity_swingHand', owner: 'LivingEntity', name: 'swingItem', desc: '()V' },
  { id: 'LivingEntity_getMainHandStack', owner: 'LivingEntity', name: 'getHeldItem', desc: '()Lnet/minecraft/item/ItemStack;' },
  { id: 'LivingEntity_getDamageTiltYaw', owner: 'LivingEntity', name: 'getDamageTiltYaw', desc: '()F', optional: true },
  { id: 'LivingEntity_jump', owner: 'LivingEntity', name: 'jump', desc: '()V' },

  /* ---- player ---- */
  /* 1.8.9 has no attack cooldown; the bridge always reports a full meter. */
  { id: 'PlayerEntity_getAttackCooldownProgress', owner: 'PlayerEntity', name: 'getAttackCooldownProgress', desc: '(F)F', optional: true },
  { id: 'PlayerEntity_getInventory', owner: 'PlayerEntity', name: 'inventory', desc: '()Lnet/minecraft/entity/player/InventoryPlayer;', optional: true },
  { id: 'Inventory_getStack', owner: 'Inventory', name: 'getStackInSlot', desc: '(I)Lnet/minecraft/item/ItemStack;' },
  { id: 'PlayerInventory_getSelectedStack', owner: 'PlayerInventory', name: 'getCurrentItem', desc: '()Lnet/minecraft/item/ItemStack;' },

  /* ---- items and blocks ---- */
  { id: 'ItemStack_isEmpty', owner: 'ItemStack', name: 'isEmpty', desc: '()Z', optional: true },
  { id: 'ItemStack_getCount', owner: 'ItemStack', name: 'getCount', desc: '()I', optional: true },
  { id: 'ItemStack_getItem', owner: 'ItemStack', name: 'getItem', desc: '()Lnet/minecraft/item/Item;' },
  /* 1.8.9 has no offhand; this never resolves and callers treat it as empty. */
  { id: 'LivingEntity_getOffHandStack', owner: 'LivingEntity', name: 'getOffHandStack', desc: '()Lnet/minecraft/item/ItemStack;', optional: true },
  { id: 'PlayerInventory_setSelectedSlot', owner: 'PlayerInventory', name: 'setSelectedSlot', desc: '(I)V', optional: true },
  { id: 'AbstractBlockState_getBlock', owner: 'AbstractBlockState', name: 'getBlock', desc: '()Lnet/minecraft/block/Block;' },
  { id: 'BlockView_getBlockState', owner: 'BlockView', name: 'getBlockState', desc: '(Lnet/minecraft/util/BlockPos;)Lnet/minecraft/block/state/IBlockState;' },
  /* "Air" on 1.8.9 is block identity against Blocks.air. */
  { id: 'AbstractBlockState_isAir', owner: 'AbstractBlockState', name: 'isAir', desc: '()Z', optional: true },
  { id: 'AbstractBlockState_isReplaceable', owner: 'AbstractBlockState', name: 'isReplaceable', desc: '()Z', optional: true },

  /* ---- world ---- */
  /* Entity iteration walks loadedEntityList; this accessor never resolves. */
  { id: 'ClientWorld_getEntities', owner: 'ClientWorld', name: 'getEntities', desc: '()Ljava/lang/Iterable;', optional: true },
  { id: 'EntityView_getPlayers', owner: 'EntityView', name: 'getPlayers', desc: '()Ljava/util/List;', optional: true },
  { id: 'EntityView_getOtherEntities', owner: 'EntityView', name: 'getOtherEntities', desc: '(Lnet/minecraft/entity/Entity;Lnet/minecraft/util/math/Box;)Ljava/util/List;', optional: true },
  { id: 'WorldAccess_getTime', owner: 'WorldAccess', name: 'getTotalWorldTime', desc: '()J' },

  /* ---- interaction and networking ---- */
  { id: 'ClientPlayerInteractionManager_attackEntity', owner: 'ClientPlayerInteractionManager', name: 'attackEntity', desc: '(Lnet/minecraft/entity/player/EntityPlayer;Lnet/minecraft/entity/Entity;)V' },
  { id: 'ClientPlayerInteractionManager_interactBlock', owner: 'ClientPlayerInteractionManager', name: 'onPlayerRightClick', desc: '(Lnet/minecraft/client/entity/EntityPlayerSP;Lnet/minecraft/client/multiplayer/WorldClient;Lnet/minecraft/item/ItemStack;Lnet/minecraft/util/BlockPos;Lnet/minecraft/util/EnumFacing;Lnet/minecraft/util/Vec3;)Z' },
  { id: 'ClientPlayerInteractionManager_hasLimitedAttackSpeed', owner: 'ClientPlayerInteractionManager', name: 'hasLimitedAttackSpeed', desc: '()Z', optional: true },
  /* 1.8.9 windowClick takes the mode as an int: 2 is the swap. */
  { id: 'ClientPlayerInteractionManager_clickSlot', owner: 'ClientPlayerInteractionManager', name: 'windowClick', desc: '(IIIILnet/minecraft/entity/player/EntityPlayer;)Lnet/minecraft/item/ItemStack;' },
  /* Lunar's NetworkManager keeps the Mojang name for this one. */
  { id: 'ClientCommonNetworkHandler_sendPacket', owner: 'ClientCommonNetworkHandler', name: 'sendPacket', desc: '(Lnet/minecraft/network/Packet;)V' },
  { id: 'ClientPlayNetworkHandler_getPlayerListEntry', owner: 'ClientPlayNetworkHandler', name: 'getPlayerInfo', desc: '(Ljava/util/UUID;)Lnet/minecraft/client/network/NetworkPlayerInfo;' },
  { id: 'ClientPlayerEntity_requestRespawn', owner: 'ClientPlayerEntity', name: 'respawnPlayer', desc: '()V' },
  /* The cursor lives behind a dummy GuiScreen on 1.8.9; the LWJGL calls stay
   * for the rare direct path and are all optional. */
  { id: 'Mouse_lockCursor', owner: 'Mouse', name: 'grabMouseCursor', desc: '()V', static: true, optional: true },
  { id: 'Mouse_unlockCursor', owner: 'Mouse', name: 'ungrabMouseCursor', desc: '()V', static: true, optional: true },
  { id: 'Mouse_isCursorLocked', owner: 'Mouse', name: 'isGrabbed', desc: '()Z', static: true, optional: true },
  { id: 'MinecraftClient_setScreen', owner: 'MinecraftClient', name: 'displayGuiScreen', desc: '(Lnet/minecraft/client/gui/GuiScreen;)V' },
  /* 1.8.9 sends chat from the player: EntityPlayerSP.sendChatMessage routes
   * slash-prefixed lines to the command path itself. */
  { id: 'ClientPlayNetworkHandler_sendChatCommand', owner: 'ClientPlayNetworkHandler', name: 'sendChatCommand', desc: '(Ljava/lang/String;)V', optional: true },
  { id: 'ClientPlayNetworkHandler_sendChatMessage', owner: 'ClientPlayerEntity', name: 'sendChatMessage', desc: '(Ljava/lang/String;)V' },

  /* ---- what a script needs to say something and to label what it sees ---- */
  { id: 'InGameHud_getChatHud', owner: 'InGameHud', name: 'getChatGUI', desc: '()Lnet/minecraft/client/gui/GuiNewChat;' },
  { id: 'ChatHud_addMessage', owner: 'ChatHud', name: 'printChatMessage', desc: '(Lnet/minecraft/util/IChatComponent;)V' },
  { id: 'Text_literal', owner: 'Text', name: 'literal', desc: '(Ljava/lang/String;)Lnet/minecraft/text/MutableText;', static: true, optional: true },
  { id: 'Nameable_getStringifiedName', owner: 'Nameable', name: 'getStringifiedName', desc: '()Ljava/lang/String;', optional: true },
  { id: 'MutableText_withColor', owner: 'MutableText', name: 'withColor', desc: '(I)Lnet/minecraft/text/MutableText;', optional: true },
  /* The action-bar line becomes an action-bar title on 1.8.9; resolved by the
   * field path in the bridge, so the accessor never resolves. */
  { id: 'InGameHud_setOverlayMessage', owner: 'InGameHud', name: 'setOverlayMessage', desc: '(Lnet/minecraft/text/Text;Z)V', optional: true },
  { id: 'MinecraftClient_getCurrentServerEntry', owner: 'MinecraftClient', name: 'getCurrentServerData', desc: '()Lnet/minecraft/client/multiplayer/ServerData;', static: true, optional: true },

  /* ---- registries: the identity of an item ---- */
  /* getNameForObject is declared on RegistrySimple; RegistryNamespaced only
   * inherits it. The live dump of RegistryNamespaced shows none of its own. */
  { id: 'Registry_getId', owner: 'RegistrySimple', name: 'getNameForObject', desc: '(Ljava/lang/Object;)Lnet/minecraft/util/ResourceLocation;', optional: true },
  { id: 'Registry_get', owner: 'Registry', name: 'getObject', desc: '(Lnet/minecraft/util/ResourceLocation;)Ljava/lang/Object;', optional: true },
  { id: 'Identifier_of', owner: 'Identifier', name: 'of', desc: '(Ljava/lang/String;)Lnet/minecraft/util/Identifier;', static: true, optional: true },
  { id: 'Identifier_getPath', owner: 'Identifier', name: 'getResourcePath', desc: '()Ljava/lang/String;' },
  { id: 'Identifier_getNamespace', owner: 'Identifier', name: 'getResourceDomain', desc: '()Ljava/lang/String;' },

  /* ---- what a script reads off a stack and off itself ---- */
  { id: 'ItemStack_getDamage', owner: 'ItemStack', name: 'getItemDamage', desc: '()I' },
  { id: 'ItemStack_getMaxDamage', owner: 'ItemStack', name: 'getMaxDamage', desc: '()I' },
  { id: 'ItemStack_isDamageable', owner: 'ItemStack', name: 'isItemStackDamageable', desc: '()Z' },
  { id: 'LivingEntity_getStatusEffects', owner: 'LivingEntity', name: 'getActivePotionEffects', desc: '()Ljava/util/Collection;' },
  /* 1.8.9 identifies a potion by integer id, not by registry entry. */
  { id: 'StatusEffectInstance_getEffectType', owner: 'StatusEffectInstance', name: 'getEffectType', desc: '()Lnet/minecraft/registry/entry/RegistryEntry;', optional: true },
  { id: 'StatusEffectInstance_getDuration', owner: 'StatusEffectInstance', name: 'getDuration', desc: '()I' },
  { id: 'RegistryEntry_value', owner: 'RegistryEntry', name: 'value', desc: '()Ljava/lang/Object;', optional: true },
  /* 1.8.9 EntityPlayer.playSound takes the sound by registry path. */
  { id: 'Entity_playSound', owner: 'PlayerEntity', name: 'playSound', desc: '(Ljava/lang/String;FF)V' },
  { id: 'PlayerListEntry_getLatency', owner: 'PlayerListEntry', name: 'getResponseTime', desc: '()I' },

  /* ---- math value types ---- */
  { id: 'BlockPos_ctor', owner: 'BlockPos', name: '<init>', desc: '(III)V' },
  { id: 'Vec3d_ctor', owner: 'Vec3d', name: '<init>', desc: '(DDD)V' },
  { id: 'Box_ctor', owner: 'Box', name: '<init>', desc: '(DDDDDD)V' },
  /* 1.8.9's block MovingObjectPosition ctor: hitVec, facing, blockPos. The
   * facing ctor also sets typeOfHit to BLOCK, which is what crosshair() reads. */
  { id: 'BlockHitResult_ctor', owner: 'BlockHitResult', name: '<init>', desc: '(Lnet/minecraft/util/Vec3;Lnet/minecraft/util/EnumFacing;Lnet/minecraft/util/BlockPos;)V' },
  { id: 'HitResult_getType', owner: 'HitResult', name: 'getType', desc: '()Lnet/minecraft/util/hit/HitResult$Type;', optional: true },
  { id: 'ActionResult_isAccepted', owner: 'ActionResult', name: 'isAccepted', desc: '()Z', optional: true },
  { id: 'HitResult_getPos', owner: 'HitResult', name: 'getPos', desc: '()Lnet/minecraft/util/math/Vec3d;', optional: true },
  { id: 'EntityHitResult_getEntity', owner: 'EntityHitResult', name: 'getEntity', desc: '()Lnet/minecraft/entity/Entity;', optional: true },
  { id: 'BlockHitResult_getBlockPos', owner: 'BlockHitResult', name: 'getBlockPos', desc: '()Lnet/minecraft/util/BlockPos;' },
  { id: 'BlockHitResult_getSide', owner: 'BlockHitResult', name: 'getSide', desc: '()Lnet/minecraft/util/math/Direction;', optional: true },
  { id: 'Vec3i_getX', owner: 'Vec3i', name: 'getX', desc: '()I' },
  { id: 'Vec3i_getY', owner: 'Vec3i', name: 'getY', desc: '()I' },
  { id: 'Vec3i_getZ', owner: 'Vec3i', name: 'getZ', desc: '()I' },

  /* ---- the crosshair macros ---- */
  { id: 'State_get', owner: 'State', name: 'get', desc: '(Lnet/minecraft/state/property/Property;)Ljava/lang/Comparable;', optional: true },
  { id: 'ItemStack_isIn', owner: 'ItemStack', name: 'isIn', desc: '(Lnet/minecraft/registry/tag/TagKey;)Z', optional: true },
  { id: 'LivingEntity_getStatusEffect', owner: 'LivingEntity', name: 'getActivePotionEffect', desc: '(Lnet/minecraft/potion/Potion;)Lnet/minecraft/potion/PotionEffect;' },
  { id: 'StatusEffectInstance_getAmplifier', owner: 'StatusEffectInstance', name: 'getAmplifier', desc: '()I' },
  { id: 'Attackable_getLastAttacker', owner: 'Attackable', name: 'getLastAttacker', desc: '()Lnet/minecraft/entity/EntityLivingBase;' },
  { id: 'LivingEntity_getAttacking', owner: 'LivingEntity', name: 'getAttacking', desc: '()Lnet/minecraft/entity/EntityLivingBase;', optional: true },
  /* Starting a break is clickBlock; continuing is onPlayerDamageBlock. */
  /* clickBlock starts a break; 1.8.9's returns whether the block changed
   * (onPlayerDamageBlock, the progress path below, is boolean too). */
  { id: 'ClientPlayerInteractionManager_attackBlock', owner: 'ClientPlayerInteractionManager', name: 'clickBlock', desc: '(Lnet/minecraft/util/BlockPos;Lnet/minecraft/util/EnumFacing;)Z' },
  { id: 'ClientPlayerInteractionManager_updateBlockBreakingProgress', owner: 'ClientPlayerInteractionManager', name: 'onPlayerDamageBlock', desc: '(Lnet/minecraft/util/BlockPos;Lnet/minecraft/util/EnumFacing;)Z' },
  { id: 'ClientWorld_spawnBlockBreakingParticle', owner: 'ClientWorld', name: 'spawnBlockBreakingParticle', desc: '(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/Direction;)V', optional: true },
  { id: 'KeyBinding_setPressed', owner: 'KeyBinding', name: 'setKeyBindState', desc: '(IZ)V', static: true },
  { id: 'InputUtilKey_getCode', owner: 'InputUtilKey', name: 'getKeyCode', desc: '()I', optional: true },
  { id: 'InventoryScreen_ctor', owner: 'InventoryScreen', name: '<init>', desc: '(Lnet/minecraft/entity/player/EntityPlayer;)V' },
  /* GuiScreen has no close(); the bridge calls displayGuiScreen(null). */
  { id: 'Screen_close', owner: 'Screen', name: 'close', desc: '()V', optional: true },

  /* ---- the player radar ----
   *
   * Equipment on another player is whatever the server sent in
   * SPacketEntityEquipment, which is exactly what getCurrentArmor and
   * getHeldItem return. Enchantments come off the stack's own NBT. The skin
   * is a ResourceLocation the client already resolved and uploaded; the radar
   * borrows the GL name rather than fetching anything of its own. */
  { id: 'LivingEntity_getEquippedStack', owner: 'PlayerEntity', name: 'getCurrentArmor', desc: '(I)Lnet/minecraft/item/ItemStack;' },
  { id: 'ItemStack_getName', owner: 'ItemStack', name: 'getDisplayName', desc: '()Ljava/lang/String;' },
  { id: 'Text_asTruncatedString', owner: 'Text', name: 'asTruncatedString', desc: '(I)Ljava/lang/String;', optional: true },
  /* Enchantments live in the stack's NBT under "ench". */
  /* 1.8.9 ItemStack keeps enchantments in its NBT; getEnchantmentTagList
   * returns the NBTTagList itself (the SDK's inventory module matches it so). */
  { id: 'ItemStack_getEnchantments', owner: 'ItemStack', name: 'getEnchantmentTagList', desc: '()Lnet/minecraft/nbt/NBTTagList;', optional: true },
  { id: 'ItemStack_getTagCompound', owner: 'ItemStack', name: 'getTagCompound', desc: '()Lnet/minecraft/nbt/NBTTagCompound;' },
  { id: 'ItemEnchantmentsComponent_getEnchantments', owner: 'ItemEnchantmentsComponent', name: 'getTagList', desc: '(Ljava/lang/String;I)Lnet/minecraft/nbt/NBTTagList;' },
  { id: 'NBTTagCompound_getShort', owner: 'ItemEnchantmentsComponent', name: 'getShort', desc: '(Ljava/lang/String;)S' },
  { id: 'RegistryEntry_getIdAsString', owner: 'RegistryEntry', name: 'getIdAsString', desc: '()Ljava/lang/String;', optional: true },
  { id: 'AbstractClientPlayerEntity_getPlayerListEntry', owner: 'AbstractClientPlayerEntity', name: 'getPlayerInfo', desc: '()Lnet/minecraft/client/network/NetworkPlayerInfo;' },
  { id: 'PlayerListEntry_getSkinTextures', owner: 'PlayerListEntry', name: 'getSkinTextures', desc: '()Lnet/minecraft/entity/player/SkinTextures;', optional: true },
  { id: 'SkinTextures_body', owner: 'SkinTextures', name: 'body', desc: '()Lnet/minecraft/util/AssetInfo$TextureAsset;', optional: true },
  { id: 'SkinTextures_model', owner: 'SkinTextures', name: 'model', desc: '()Lnet/minecraft/entity/player/PlayerSkinType;', optional: true },
  { id: 'TextureAsset_texturePath', owner: 'TextureAsset', name: 'texturePath', desc: '()Lnet/minecraft/util/Identifier;', optional: true },
  { id: 'MinecraftClient_getTextureManager', owner: 'MinecraftClient', name: 'getTextureManager', desc: '()Lnet/minecraft/client/renderer/texture/TextureManager;', optional: true },
  { id: 'TextureManager_getTexture', owner: 'TextureManager', name: 'getTexture', desc: '(Lnet/minecraft/util/ResourceLocation;)Lnet/minecraft/client/renderer/texture/ITextureObject;' },
  { id: 'AbstractTexture_getGlTexture', owner: 'AbstractTexture', name: 'getGlTextureId', desc: '()I' },
  { id: 'MinecraftClient_getBakedModelManager', owner: 'MinecraftClient', name: 'getBakedModelManager', desc: '()Lnet/minecraft/client/render/model/BakedModelManager;', optional: true },
  { id: 'AtlasManager_getAtlasTexture', owner: 'AtlasManager', name: 'getAtlasTexture', desc: '(Lnet/minecraft/util/Identifier;)Lnet/minecraft/client/texture/SpriteAtlasTexture;', optional: true },
  /* Item icons come out of the stitched block atlas by icon name. */
  { id: 'SpriteAtlasTexture_getSprite', owner: 'BakedModelManager', name: 'getAtlasSprite', desc: '(Ljava/lang/String;)Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;' },
  { id: 'Sprite_getMinU', owner: 'Sprite', name: 'getMinU', desc: '()F' },
  { id: 'Sprite_getMaxU', owner: 'Sprite', name: 'getMaxU', desc: '()F' },
  { id: 'Sprite_getMinV', owner: 'Sprite', name: 'getMinV', desc: '()F' },
  { id: 'Sprite_getMaxV', owner: 'Sprite', name: 'getMaxV', desc: '()F' },

  /* ---- appended for the 1.8.9 port; ids are new, appended at the end ---- */
  { id: 'Window_ctor', owner: 'Window', name: '<init>', desc: '(Lnet/minecraft/client/Minecraft;)V' },
  { id: 'Display_getWidth', owner: 'Display', name: 'getWidth', desc: '()I', static: true },
  { id: 'Display_getHeight', owner: 'Display', name: 'getHeight', desc: '()I', static: true },
  { id: 'Text_ctor', owner: 'Text', name: '<init>', desc: '(Ljava/lang/String;)V' },
  { id: 'Entity_getName', owner: 'Entity', name: 'getName', desc: '()Ljava/lang/String;' },
  { id: 'PlayerEntity_getCurrentArmor', owner: 'PlayerEntity', name: 'getCurrentArmor', desc: '(I)Lnet/minecraft/item/ItemStack;' },
  { id: 'PlayerListEntry_getLocationSkin', owner: 'PlayerListEntry', name: 'getLocationSkin', desc: '()Lnet/minecraft/util/ResourceLocation;' },
  { id: 'PlayerListEntry_getSkinType', owner: 'PlayerListEntry', name: 'getSkinType', desc: '()Ljava/lang/String;' },
  { id: 'NBTTagList_getCompoundTagAt', owner: 'NBTTagList', name: 'getCompoundTagAt', desc: '(I)Lnet/minecraft/nbt/NBTTagCompound;' },
  { id: 'NBTTagList_tagCount', owner: 'NBTTagList', name: 'tagCount', desc: '()I' },
  { id: 'StatusEffectInstance_getPotionID', owner: 'StatusEffectInstance', name: 'getPotionID', desc: '()I' },
  { id: 'Block_getUnlocalizedName', owner: 'Block', name: 'getUnlocalizedName', desc: '()Ljava/lang/String;', optional: true },
  { id: 'Item_getUnlocalizedName', owner: 'Item', name: 'getUnlocalizedName', desc: '()Ljava/lang/String;', optional: true },
];

export const fields = [
  /* ---- client root ---- */
  { id: 'MinecraftClient_player', owner: 'MinecraftClient', name: 'thePlayer', desc: 'Lnet/minecraft/client/entity/EntityPlayerSP;' },
  { id: 'MinecraftClient_world', owner: 'MinecraftClient', name: 'theWorld', desc: 'Lnet/minecraft/client/multiplayer/WorldClient;' },
  { id: 'MinecraftClient_interactionManager', owner: 'MinecraftClient', name: 'playerController', desc: 'Lnet/minecraft/client/multiplayer/PlayerControllerMP;' },
  { id: 'MinecraftClient_currentScreen', owner: 'MinecraftClient', name: 'currentScreen', desc: 'Lnet/minecraft/client/gui/GuiScreen;' },
  { id: 'MinecraftClient_options', owner: 'MinecraftClient', name: 'gameSettings', desc: 'Lnet/minecraft/client/settings/GameSettings;' },
  { id: 'MinecraftClient_window', owner: 'MinecraftClient', name: 'window', desc: 'Lnet/minecraft/client/util/Window;', optional: true },
  { id: 'MinecraftClient_crosshairTarget', owner: 'MinecraftClient', name: 'objectMouseOver', desc: 'Lnet/minecraft/util/MovingObjectPosition;' },
  { id: 'MinecraftClient_targetedEntity', owner: 'MinecraftClient', name: 'pointedEntity', desc: 'Lnet/minecraft/entity/Entity;', optional: true },
  { id: 'MinecraftClient_attackCooldown', owner: 'MinecraftClient', name: 'leftClickCounter', desc: 'I' },
  { id: 'MinecraftClient_itemUseCooldown', owner: 'MinecraftClient', name: 'rightClickDelayTimer', desc: 'I' },
  { id: 'ClientPlayerInteractionManager_blockBreakingCooldown', owner: 'ClientPlayerInteractionManager', name: 'blockHitDelay', desc: 'I' },
  { id: 'ClientPlayerInteractionManager_breakingBlock', owner: 'ClientPlayerInteractionManager', name: 'isHittingBlock', desc: 'Z' },
  { id: 'MinecraftClient_gameRenderer', owner: 'MinecraftClient', name: 'entityRenderer', desc: 'Lnet/minecraft/client/renderer/EntityRenderer;' },
  { id: 'MinecraftClient_mouse', owner: 'MinecraftClient', name: 'mouse', desc: 'Lnet/minecraft/client/Mouse;', optional: true },
  { id: 'MinecraftClient_inGameHud', owner: 'MinecraftClient', name: 'ingameGUI', desc: 'Lnet/minecraft/client/gui/GuiIngame;' },
  /* MCP 1.8.9 spells the run directory mcDataDir (Yarn's gameDir). */
  { id: 'MinecraftClient_runDirectory', owner: 'MinecraftClient', name: 'mcDataDir', desc: 'Ljava/io/File;', optional: true },
  { id: 'ServerInfo_address', owner: 'ServerInfo', name: 'serverIP', desc: 'Ljava/lang/String;' },
  { id: 'Mouse_x', owner: 'Mouse', name: 'x', desc: 'D', optional: true },
  { id: 'Mouse_y', owner: 'Mouse', name: 'y', desc: 'D', optional: true },
  { id: 'Mouse_cursorDeltaX', owner: 'Mouse', name: 'dx', desc: 'D', optional: true },
  { id: 'Mouse_cursorDeltaY', owner: 'Mouse', name: 'dy', desc: 'D', optional: true },
  { id: 'Mouse_cursorLocked', owner: 'Mouse', name: 'grabbed', desc: 'Z', optional: true },

  /* ---- render state. PROJECTION and MODELVIEW are the float[16] arrays the
   * world pass just used, column-major; renderPartialTicks is how far through
   * the current tick the presented frame is. ---- */
  { id: 'Camera_pos', owner: 'Camera', name: 'pos', desc: 'Lnet/minecraft/util/math/Vec3d;', optional: true },
  { id: 'Camera_yaw', owner: 'Camera', name: 'yaw', desc: 'F', optional: true },
  { id: 'Camera_pitch', owner: 'Camera', name: 'pitch', desc: 'F', optional: true },
  { id: 'Camera_rotation', owner: 'Camera', name: 'rotation', desc: 'Lorg/joml/Quaternionf;', optional: true },
  { id: 'Camera_focusedEntity', owner: 'Camera', name: 'focusedEntity', desc: 'Lnet/minecraft/entity/Entity;', optional: true },
  { id: 'Camera_thirdPerson', owner: 'Camera', name: 'thirdPerson', desc: 'Z', optional: true },
  { id: 'GameRenderer_camera', owner: 'GameRenderer', name: 'camera', desc: 'Lnet/minecraft/client/render/Camera;', optional: true },

  /* ---- entity ---- */
  { id: 'Entity_id', owner: 'Entity', name: 'entityId', desc: 'I' },
  { id: 'Entity_uuid', owner: 'Entity', name: 'entityUniqueID', desc: 'Ljava/util/UUID;' },
  { id: 'Entity_pos', owner: 'Entity', name: 'pos', desc: 'Lnet/minecraft/util/math/Vec3d;', optional: true },
  { id: 'Entity_boundingBox', owner: 'Entity', name: 'boundingBox', desc: 'Lnet/minecraft/util/AxisAlignedBB;' },
  { id: 'Entity_yaw', owner: 'Entity', name: 'rotationYaw', desc: 'F' },
  { id: 'Entity_pitch', owner: 'Entity', name: 'rotationPitch', desc: 'F' },
  { id: 'Entity_lastYaw', owner: 'Entity', name: 'prevRotationYaw', desc: 'F' },
  { id: 'Entity_lastPitch', owner: 'Entity', name: 'prevRotationPitch', desc: 'F' },
  /* 1.8.9 counts fall distance in floats. */
  { id: 'Entity_fallDistance', owner: 'Entity', name: 'fallDistance', desc: 'F' },
  { id: 'Entity_age', owner: 'Entity', name: 'ticksExisted', desc: 'I' },
  { id: 'Entity_onGround', owner: 'Entity', name: 'onGround', desc: 'Z' },
  { id: 'Entity_velocity', owner: 'Entity', name: 'motion', desc: 'Lnet/minecraft/util/math/Vec3d;', optional: true },
  /* 1.8.9 Entity tracks walked distance as doubles named distanceWalked*;
   * the float distanceTraveled is 1.21-only, so this id idles. */
  { id: 'Entity_distanceTraveled', owner: 'Entity', name: 'distanceWalkedModified', desc: 'D', optional: true },

  /* ---- living ---- */
  { id: 'LivingEntity_bodyYaw', owner: 'LivingEntity', name: 'renderYawOffset', desc: 'F' },
  { id: 'LivingEntity_lastBodyYaw', owner: 'LivingEntity', name: 'prevRenderYawOffset', desc: 'F', optional: true },
  { id: 'LivingEntity_headYaw', owner: 'LivingEntity', name: 'rotationYawHead', desc: 'F', optional: true },
  { id: 'LivingEntity_hurtTime', owner: 'LivingEntity', name: 'hurtTime', desc: 'I' },
  { id: 'LivingEntity_maxHurtTime', owner: 'LivingEntity', name: 'maxHurtTime', desc: 'I' },
  { id: 'LivingEntity_jumping', owner: 'LivingEntity', name: 'jumping', desc: 'Z', optional: true },
  { id: 'LivingEntity_deathTime', owner: 'LivingEntity', name: 'deathTime', desc: 'I' },
  { id: 'LivingEntity_handSwinging', owner: 'LivingEntity', name: 'handSwinging', desc: 'Z', optional: true },
  { id: 'LivingEntity_limbAnimator', owner: 'LivingEntity', name: 'limbAnimator', desc: 'Lnet/minecraft/entity/LimbAnimator;', optional: true },

  { id: 'ClientPlayerEntity_input', owner: 'ClientPlayerEntity', name: 'movementInput', desc: 'Lnet/minecraft/util/MovementInput;', optional: true },
  { id: 'ClientPlayerEntity_networkHandler', owner: 'ClientPlayerEntity', name: 'sendQueue', desc: 'Lnet/minecraft/client/network/NetHandlerPlayClient;', optional: true },

  { id: 'PlayerInventory_selectedSlot', owner: 'PlayerInventory', name: 'currentItem', desc: 'I' },

  /* ---- math value types ---- */
  { id: 'Box_minX', owner: 'Box', name: 'minX', desc: 'D' },
  { id: 'Box_minY', owner: 'Box', name: 'minY', desc: 'D' },
  { id: 'Box_minZ', owner: 'Box', name: 'minZ', desc: 'D' },
  { id: 'Box_maxX', owner: 'Box', name: 'maxX', desc: 'D' },
  { id: 'Box_maxY', owner: 'Box', name: 'maxY', desc: 'D' },
  { id: 'Box_maxZ', owner: 'Box', name: 'maxZ', desc: 'D' },

  { id: 'Vec3d_x', owner: 'Vec3d', name: 'xCoord', desc: 'D' },
  { id: 'Vec3d_y', owner: 'Vec3d', name: 'yCoord', desc: 'D' },
  { id: 'Vec3d_z', owner: 'Vec3d', name: 'zCoord', desc: 'D' },

  /* ---- containers ---- */
  { id: 'PlayerEntity_currentScreenHandler', owner: 'PlayerEntity', name: 'openContainer', desc: 'Lnet/minecraft/inventory/Container;' },
  { id: 'ScreenHandler_syncId', owner: 'ScreenHandler', name: 'windowId', desc: 'I' },
  /* 1.8.9 ClickType.SWAP: number key pressed is the hotbar slot, exactly the
   * trick the 1.21 SWAP action used. */
  { id: 'SlotActionType_SWAP', owner: 'SlotActionType', name: 'SWAP', desc: 'Ljava/lang/Object;', static: true, optional: true },

  /* 1.8.9 has no offhand; MAIN_HAND resolves to DOWN as a harmless sentinel
   * and OFF_HAND stays null, which every caller treats as the main hand. */
  { id: 'Hand_MAIN_HAND', owner: 'Hand', name: 'DOWN', desc: 'Lnet/minecraft/util/EnumFacing;', static: true, optional: true },
  { id: 'Hand_OFF_HAND', owner: 'Hand', name: 'UP', desc: 'Lnet/minecraft/util/EnumFacing;', static: true, optional: true },

  /* Post-1.8 items; all optional, all dead on 1.8.9. */
  { id: 'Items_TOTEM_OF_UNDYING', owner: 'Items', name: 'TOTEM_OF_UNDYING', desc: 'Lnet/minecraft/item/Item;', static: true, optional: true },
  { id: 'Items_END_CRYSTAL', owner: 'Items', name: 'END_CRYSTAL', desc: 'Lnet/minecraft/item/Item;', static: true, optional: true },
  { id: 'Items_RESPAWN_ANCHOR', owner: 'Items', name: 'RESPAWN_ANCHOR', desc: 'Lnet/minecraft/item/Item;', static: true, optional: true },
  { id: 'Items_GLOWSTONE', owner: 'Items', name: 'GLOWSTONE', desc: 'Lnet/minecraft/item/Item;', static: true, optional: true },
  { id: 'Items_MACE', owner: 'Items', name: 'MACE', desc: 'Lnet/minecraft/item/Item;', static: true, optional: true },
  { id: 'Items_OBSIDIAN', owner: 'Items', name: 'OBSIDIAN', desc: 'Lnet/minecraft/item/Item;', static: true, optional: true },
  { id: 'Items_SHIELD', owner: 'Items', name: 'SHIELD', desc: 'Lnet/minecraft/item/Item;', static: true, optional: true },
  { id: 'Blocks_OBSIDIAN', owner: 'Blocks', name: 'obsidian', desc: 'Lnet/minecraft/block/Block;', static: true },
  { id: 'Blocks_BEDROCK', owner: 'Blocks', name: 'bedrock', desc: 'Lnet/minecraft/block/Block;', static: true },
  { id: 'Blocks_RESPAWN_ANCHOR', owner: 'Blocks', name: 'respawn_anchor', desc: 'Lnet/minecraft/block/Block;', static: true, optional: true },
  { id: 'Direction_DOWN', owner: 'Direction', name: 'DOWN', desc: 'Lnet/minecraft/util/EnumFacing;', static: true },
  { id: 'Direction_UP', owner: 'Direction', name: 'UP', desc: 'Lnet/minecraft/util/EnumFacing;', static: true },
  { id: 'Direction_NORTH', owner: 'Direction', name: 'NORTH', desc: 'Lnet/minecraft/util/EnumFacing;', static: true },
  { id: 'Direction_SOUTH', owner: 'Direction', name: 'SOUTH', desc: 'Lnet/minecraft/util/EnumFacing;', static: true },
  { id: 'Direction_WEST', owner: 'Direction', name: 'WEST', desc: 'Lnet/minecraft/util/EnumFacing;', static: true },
  { id: 'Direction_EAST', owner: 'Direction', name: 'EAST', desc: 'Lnet/minecraft/util/EnumFacing;', static: true },

  { id: 'HitResultType_MISS', owner: 'HitResultType', name: 'MISS', desc: 'Lnet/minecraft/util/MovingObjectPosition$MovingObjectType;', static: true },
  { id: 'HitResultType_BLOCK', owner: 'HitResultType', name: 'BLOCK', desc: 'Lnet/minecraft/util/MovingObjectPosition$MovingObjectType;', static: true },
  { id: 'HitResultType_ENTITY', owner: 'HitResultType', name: 'ENTITY', desc: 'Lnet/minecraft/util/MovingObjectPosition$MovingObjectType;', static: true },
  { id: 'RespawnAnchorBlock_CHARGES', owner: 'RespawnAnchorBlock', name: 'CHARGES', desc: 'Lnet/minecraft/state/property/IntProperty;', static: true, optional: true },
  { id: 'StatusEffects_WEAKNESS', owner: 'StatusEffects', name: 'weakness', desc: 'Lnet/minecraft/potion/Potion;', static: true },
  { id: 'StatusEffects_STRENGTH', owner: 'StatusEffects', name: 'damageBoost', desc: 'Lnet/minecraft/potion/Potion;', static: true },
  { id: 'ItemTags_SWORDS', owner: 'ItemTags', name: 'SWORDS', desc: 'Lnet/minecraft/registry/tag/TagKey;', static: true, optional: true },
  { id: 'ItemTags_AXES', owner: 'ItemTags', name: 'AXES', desc: 'Lnet/minecraft/registry/tag/TagKey;', static: true, optional: true },
  { id: 'MinecraftClient_particleManager', owner: 'MinecraftClient', name: 'effectRenderer', desc: 'Lnet/minecraft/client/particle/EffectRenderer;', optional: true },
  { id: 'GameOptions_useKey', owner: 'GameOptions', name: 'keyBindUseItem', desc: 'Lnet/minecraft/client/settings/KeyBinding;' },
  { id: 'GameOptions_attackKey', owner: 'GameOptions', name: 'keyBindAttack', desc: 'Lnet/minecraft/client/settings/KeyBinding;' },
  { id: 'GameOptions_forwardKey', owner: 'GameOptions', name: 'keyBindForward', desc: 'Lnet/minecraft/client/settings/KeyBinding;' },
  { id: 'GameOptions_backKey', owner: 'GameOptions', name: 'keyBindBack', desc: 'Lnet/minecraft/client/settings/KeyBinding;' },
  { id: 'GameOptions_leftKey', owner: 'GameOptions', name: 'keyBindLeft', desc: 'Lnet/minecraft/client/settings/KeyBinding;' },
  { id: 'GameOptions_rightKey', owner: 'GameOptions', name: 'keyBindRight', desc: 'Lnet/minecraft/client/settings/KeyBinding;' },
  { id: 'GameOptions_jumpKey', owner: 'GameOptions', name: 'keyBindJump', desc: 'Lnet/minecraft/client/settings/KeyBinding;' },
  { id: 'GameOptions_sneakKey', owner: 'GameOptions', name: 'keyBindSneak', desc: 'Lnet/minecraft/client/settings/KeyBinding;' },
  { id: 'GameOptions_sprintKey', owner: 'GameOptions', name: 'keyBindSprint', desc: 'Lnet/minecraft/client/settings/KeyBinding;' },
  /* 1.8.9 registries are static fields on RegistryNamespaced instances. */
  /* 1.8.9 registry singletons live on Item and Block themselves. */
  { id: 'Registries_ITEM', owner: 'Registries', name: 'itemRegistry', desc: 'Lnet/minecraft/util/RegistryNamespaced;', static: true },
  { id: 'Registries_BLOCK', owner: 'Block', name: 'blockRegistry', desc: 'Lnet/minecraft/util/RegistryNamespaced;', static: true, optional: true },
  { id: 'Registries_STATUS_EFFECT', owner: 'Registries', name: 'STATUS_EFFECT', desc: 'Lnet/minecraft/util/Registry;', static: true, optional: true },
  { id: 'Registries_SOUND_EVENT', owner: 'Registries', name: 'SOUND_EVENT', desc: 'Lnet/minecraft/util/Registry;', static: true, optional: true },
  { id: 'KeyBinding_timesPressed', owner: 'KeyBinding', name: 'pressTime', desc: 'I' },
  { id: 'KeyBinding_pressed', owner: 'KeyBinding', name: 'pressed', desc: 'Z' },
  { id: 'KeyBinding_boundKey', owner: 'KeyBinding', name: 'keyCode', desc: 'I', optional: true },

  /* ---- the player radar. The 1.21 equipment-slot enum and atlas machinery
   * collapse; the ids stay for enum stability and resolve to nulls. ---- */
  { id: 'EquipmentSlot_HEAD', owner: 'EquipmentSlot', name: 'HEAD', desc: 'Lnet/minecraft/entity/EquipmentSlot;', static: true, optional: true },
  { id: 'EquipmentSlot_CHEST', owner: 'EquipmentSlot', name: 'CHEST', desc: 'Lnet/minecraft/entity/EquipmentSlot;', static: true, optional: true },
  { id: 'EquipmentSlot_LEGS', owner: 'EquipmentSlot', name: 'LEGS', desc: 'Lnet/minecraft/entity/EquipmentSlot;', static: true, optional: true },
  { id: 'EquipmentSlot_FEET', owner: 'EquipmentSlot', name: 'FEET', desc: 'Lnet/minecraft/entity/EquipmentSlot;', static: true, optional: true },
  { id: 'EquipmentSlot_MAINHAND', owner: 'EquipmentSlot', name: 'MAINHAND', desc: 'Lnet/minecraft/entity/EquipmentSlot;', static: true, optional: true },
  { id: 'EquipmentSlot_OFFHAND', owner: 'EquipmentSlot', name: 'OFFHAND', desc: 'Lnet/minecraft/entity/EquipmentSlot;', static: true, optional: true },
  { id: 'PlayerSkinType_SLIM', owner: 'PlayerSkinType', name: 'SLIM', desc: 'Lnet/minecraft/entity/player/PlayerSkinType;', static: true, optional: true },
  { id: 'Atlases_ITEMS', owner: 'Atlases', name: 'ITEMS', desc: 'Lnet/minecraft/util/Identifier;', static: true, optional: true },
  { id: 'Atlases_BLOCKS', owner: 'Atlases', name: 'BLOCKS', desc: 'Lnet/minecraft/util/Identifier;', static: true, optional: true },
  { id: 'BakedModelManager_atlasManager', owner: 'BakedModelManager', name: 'atlasManager', desc: 'Lnet/minecraft/client/texture/AtlasManager;', optional: true },

  /* ---- appended for the 1.8.9 port; ids are new, appended at the end ---- */
  { id: 'MinecraftClient_theMinecraft', owner: 'MinecraftClient', name: 'theMinecraft', desc: 'Lnet/minecraft/client/Minecraft;', static: true },
  { id: 'MinecraftClient_debugFPS', owner: 'MinecraftClient', name: 'debugFPS', desc: 'I', static: true },
  { id: 'MinecraftClient_textureManager', owner: 'MinecraftClient', name: 'renderEngine', desc: 'Lnet/minecraft/client/renderer/texture/TextureManager;' },
  { id: 'MinecraftClient_textureMapBlocks', owner: 'MinecraftClient', name: 'textureMapBlocks', desc: 'Lnet/minecraft/client/renderer/texture/TextureMap;' },
  { id: 'GameOptions_mouseSensitivity', owner: 'GameOptions', name: 'mouseSensitivity', desc: 'F' },
  { id: 'GameOptions_viewBobbing', owner: 'GameOptions', name: 'viewBobbing', desc: 'Z' },
  { id: 'Camera_MODELVIEW', owner: 'Camera', name: 'MODELVIEW', desc: 'Ljava/nio/FloatBuffer;', static: true },
  { id: 'Camera_PROJECTION', owner: 'Camera', name: 'PROJECTION', desc: 'Ljava/nio/FloatBuffer;', static: true },
  { id: 'RenderTickCounter_renderPartialTicks', owner: 'RenderTickCounter', name: 'renderPartialTicks', desc: 'F' },
  { id: 'Entity_posX', owner: 'Entity', name: 'posX', desc: 'D' },
  { id: 'Entity_posY', owner: 'Entity', name: 'posY', desc: 'D' },
  { id: 'Entity_posZ', owner: 'Entity', name: 'posZ', desc: 'D' },
  { id: 'Entity_prevPosX', owner: 'Entity', name: 'prevPosX', desc: 'D' },
  { id: 'Entity_prevPosY', owner: 'Entity', name: 'prevPosY', desc: 'D' },
  { id: 'Entity_prevPosZ', owner: 'Entity', name: 'prevPosZ', desc: 'D' },
  { id: 'Entity_lastTickPosX', owner: 'Entity', name: 'lastTickPosX', desc: 'D' },
  { id: 'Entity_lastTickPosY', owner: 'Entity', name: 'lastTickPosY', desc: 'D' },
  { id: 'Entity_lastTickPosZ', owner: 'Entity', name: 'lastTickPosZ', desc: 'D' },
  { id: 'Entity_motionX', owner: 'Entity', name: 'motionX', desc: 'D' },
  { id: 'Entity_motionY', owner: 'Entity', name: 'motionY', desc: 'D' },
  { id: 'Entity_motionZ', owner: 'Entity', name: 'motionZ', desc: 'D' },
  { id: 'Entity_width', owner: 'Entity', name: 'width', desc: 'F' },
  { id: 'Entity_height', owner: 'Entity', name: 'height', desc: 'F' },
  { id: 'World_loadedEntityList', owner: 'World', name: 'loadedEntityList', desc: 'Ljava/util/List;' },
  { id: 'HitResult_blockPos', owner: 'HitResult', name: 'blockPos', desc: 'Lnet/minecraft/util/BlockPos;' },
  { id: 'EnumActionResult_SUCCESS', owner: 'ActionResult', name: 'SUCCESS', desc: 'Lnet/minecraft/util/EnumActionResult;', static: true, optional: true },
  { id: 'EnumActionResult_PASS', owner: 'ActionResult', name: 'PASS', desc: 'Lnet/minecraft/util/EnumActionResult;', static: true, optional: true },
  { id: 'World_playerEntities', owner: 'World', name: 'playerEntities', desc: 'Ljava/util/List;' },
  { id: 'Blocks_air', owner: 'Blocks', name: 'air', desc: 'Lnet/minecraft/block/Block;', static: true },
  { id: 'ItemStack_stackSize', owner: 'ItemStack', name: 'stackSize', desc: 'I' },
  { id: 'HitResult_typeOfHit', owner: 'HitResult', name: 'typeOfHit', desc: 'Lnet/minecraft/util/MovingObjectPosition$MovingObjectType;' },
  { id: 'HitResult_hitVec', owner: 'HitResult', name: 'hitVec', desc: 'Lnet/minecraft/util/Vec3;' },
  { id: 'BlockHitResult_sideHit', owner: 'BlockHitResult', name: 'sideHit', desc: 'Lnet/minecraft/util/EnumFacing;' },
  { id: 'EntityHitResult_entityHit', owner: 'EntityHitResult', name: 'entityHit', desc: 'Lnet/minecraft/entity/Entity;' },
  { id: 'ItemEnchantmentsComponent_id', owner: 'ItemEnchantmentsComponent', name: 'id', desc: 'S', optional: true },
  { id: 'ItemEnchantmentsComponent_lvl', owner: 'ItemEnchantmentsComponent', name: 'lvl', desc: 'S', optional: true },
  { id: 'MinecraftClient_timer', owner: 'MinecraftClient', name: 'timer', desc: 'Lnet/minecraft/util/Timer;' },
];
