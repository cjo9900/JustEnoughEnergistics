package p455w0rd.jee.integration;

import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.common.collect.Table.Cell;

import appeng.container.implementations.ContainerPatternTerm;
import mezz.jei.api.*;
import mezz.jei.api.gui.IGuiIngredient;
import mezz.jei.api.gui.IRecipeLayout;
import mezz.jei.api.ingredients.IModIngredientRegistration;
import mezz.jei.api.recipe.IStackHelper;
import mezz.jei.api.recipe.VanillaRecipeCategoryUid;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.collect.Table;
import mezz.jei.config.Constants;
import mezz.jei.recipes.RecipeTransferRegistry;
import mezz.jei.startup.StackHelper;
import mezz.jei.transfer.RecipeTransferErrorInternal;
import mezz.jei.transfer.RecipeTransferErrorTooltip;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.translation.I18n;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import net.minecraftforge.oredict.OreDictionary;
import p455w0rd.jee.init.ModLogger;
import p455w0rd.jee.init.ModNetworking;
import p455w0rd.jee.packets.PacketJEIPatternRecipe;
import p455w0rd.jee.util.WrappedTable;

@SuppressWarnings({
		"rawtypes", "deprecation"
})
@JEIPlugin
public class JEI implements IModPlugin {

	private static final IRecipeTransferError NEEDED_MODE_CRAFTING = new IncorrectTerminalModeError(true);
	private static final IRecipeTransferError NEEDED_MODE_PROCESSING = new IncorrectTerminalModeError(false);
	private static StackHelper stackHelper = null;

	@Override
	public void register(@Nonnull IModRegistry registry) {
		IStackHelper ish = registry.getJeiHelpers().getStackHelper();
		if (ish instanceof StackHelper) {
			stackHelper = (StackHelper) registry.getJeiHelpers().getStackHelper();
		}
		Table<Class<?>, String, IRecipeTransferHandler> newRegistry = Table.hashBasedTable();
		boolean ae2found = false;
		for (Cell<Class, String, IRecipeTransferHandler> currentCell : ((RecipeTransferRegistry) registry.getRecipeTransferRegistry()).getRecipeTransferHandlers().cellSet()) {
			if (currentCell.getRowKey().equals(ContainerPatternTerm.class)) {
				ae2found = true;
				continue;
			}
			newRegistry.put(currentCell.getRowKey(), currentCell.getColumnKey(), currentCell.getValue());
		}
		newRegistry.put(ContainerPatternTerm.class, Constants.UNIVERSAL_RECIPE_TRANSFER_UID, new RecipeTransferHandler());
		if (ae2found) {
			ModLogger.info("AE2 RecipeTransferHandler Replaced Successfully (Registered prior)");
		}
		else {
			newRegistry = new WrappedTable<Class<?>, String, IRecipeTransferHandler>(newRegistry);
		}
		ReflectionHelper.setPrivateValue(RecipeTransferRegistry.class, ((RecipeTransferRegistry) registry.getRecipeTransferRegistry()), newRegistry, "recipeTransferHandlers");
	}

	@Override
	public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
	}

	@Override
	public void registerIngredients(IModIngredientRegistration registry) {
	}

	@Override
	public void registerItemSubtypes(ISubtypeRegistry subtypeRegistry) {
	}

	public static StackHelper getStackHelper() {
		return stackHelper;
	}

	public static class RecipeTransferHandler implements IRecipeTransferHandler<ContainerPatternTerm> {

		public static final String OUTPUTS_KEY = "Outputs";

		@Override
		public Class<ContainerPatternTerm> getContainerClass() {
			return ContainerPatternTerm.class;
		}

		@Override
		@Nullable
		public IRecipeTransferError transferRecipe(ContainerPatternTerm container, IRecipeLayout recipeLayout, EntityPlayer player, boolean maxTransfer, boolean doTransfer) {
			String recipeType = recipeLayout.getRecipeCategory().getUid();
			if (doTransfer) {
				Map<Integer, ? extends IGuiIngredient<ItemStack>> ingredients = recipeLayout.getItemStacks().getGuiIngredients();
				NBTTagCompound recipeInputs = new NBTTagCompound();
				NBTTagCompound recipeOutputs = null;
				NBTTagList outputList = new NBTTagList();
				int inputIndex = 0;
				int outputIndex = 0;
				for (Map.Entry<Integer, ? extends IGuiIngredient<ItemStack>> ingredientEntry : ingredients.entrySet()) {
					IGuiIngredient<ItemStack> guiIngredient = ingredientEntry.getValue();
					if (guiIngredient != null) {
						ItemStack ingredient = ItemStack.EMPTY;
						if (guiIngredient.getDisplayedIngredient() != null) {
							ingredient = guiIngredient.getDisplayedIngredient().copy();
						}
						if (guiIngredient.isInput()) {
							NBTTagList tags = new NBTTagList();
							String oreDict = getStackHelper().getOreDictEquivalent(guiIngredient.getAllIngredients());
							if (oreDict != null) { // Fix for https://github.com/p455w0rd/JustEnoughEnergistics/issues/4 (kind of a band-aid, we'll see)
								tags.appendTag(OreDictionary.getOres(oreDict).get(0).writeToNBT(new NBTTagCompound()));
							}
							else {
								for (ItemStack stack : guiIngredient.getAllIngredients()) {
									if (stack != null) { // How is this even a thing in 1.12?? Fix for https://github.com/p455w0rd/JustEnoughEnergistics/issues/3
										tags.appendTag(stack.writeToNBT(new NBTTagCompound()));
										break;
									}
								}
							}
							recipeInputs.setTag("#" + inputIndex, tags);
							inputIndex++;
						}
						else {
							if (outputIndex >= 3 || ingredient.isEmpty() || container.isCraftingMode()) {
								continue;
							}
							outputList.appendTag(ingredient.writeToNBT(new NBTTagCompound()));
							++outputIndex;
							continue;
						}
					}
				}
				if (!outputList.hasNoTags()) {
					recipeOutputs = new NBTTagCompound();
					recipeOutputs.setTag(OUTPUTS_KEY, outputList);
				}
				ModNetworking.getInstance().sendToServer(new PacketJEIPatternRecipe(recipeInputs, recipeOutputs));
			}
			if (!recipeType.equals(VanillaRecipeCategoryUid.INFORMATION) && !recipeType.equals(VanillaRecipeCategoryUid.FUEL)) {
				if (!container.isCraftingMode()) {
					if (recipeType.equals(VanillaRecipeCategoryUid.CRAFTING)) {
						return NEEDED_MODE_CRAFTING;
					}
				}
				else if (!recipeType.equals(VanillaRecipeCategoryUid.CRAFTING)) {
					return NEEDED_MODE_PROCESSING;
				}
			}
			else {
				return RecipeTransferErrorInternal.INSTANCE;
			}
			return null;
		}

	}

	private static class IncorrectTerminalModeError extends RecipeTransferErrorTooltip {

		private static final String CRAFTING = I18n.translateToLocalFormatted("tooltip.jee.crafting", new Object[0]);
		private static final String PROCESSING = I18n.translateToLocalFormatted("tooltip.jee.processing", new Object[0]);

		public IncorrectTerminalModeError(boolean needsCrafting) {
			super(I18n.translateToLocalFormatted("tooltip.jee.errormsg", TextFormatting.BOLD + (needsCrafting ? CRAFTING : PROCESSING) + TextFormatting.RESET + "" + TextFormatting.RED));
		}

	}

}
