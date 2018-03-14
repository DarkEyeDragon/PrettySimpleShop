package com.robomwm.prettysimpleshop.command;

import com.robomwm.prettysimpleshop.ConfigManager;
import com.robomwm.prettysimpleshop.LazyUtil;
import com.robomwm.prettysimpleshop.PrettySimpleShop;
import com.robomwm.prettysimpleshop.event.ShopBoughtEvent;
import com.robomwm.prettysimpleshop.shop.ShopAPI;
import com.robomwm.prettysimpleshop.shop.ShopInfo;
import com.robomwm.prettysimpleshop.shop.ShopListener;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import pw.valaria.bookutil.BookUtil;

import java.util.HashMap;
import java.util.Map;

/**
 * Created on 2/8/2018.
 *
 * @author RoboMWM
 */
public class BuyCommand implements CommandExecutor, Listener
{
    private ShopListener shopListener;
    private ConfigManager config;
    private ShopAPI shopAPI;
    private Economy economy;
    private Map<Player, UnconfirmedTransaction> unconfirmedTransactionMap = new HashMap<>();
    private BookUtil bookUtil;

    public BuyCommand(PrettySimpleShop plugin, ShopListener shopListener, Economy economy)
    {
        this.shopListener = shopListener;
        this.config = plugin.getConfigManager();
        this.shopAPI = plugin.getShopAPI();
        this.economy = economy;
        this.bookUtil = new BookUtil(plugin);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args)
    {
        if (!(sender instanceof Player) || args.length < 1)
            return false;
        Player player = (Player)sender;

        if (args.length == 4) //Selecting a shop via clicking
        {
            World world = player.getServer().getWorld(args[0]);
            if (world == null || player.getWorld() != world)
            {
                config.sendMessage(player, "tooFar");
                return false;
            }
            Location location;

            try
            {
                location = new Location(world, Double.parseDouble(args[1]), Double.parseDouble(args[2]), Double.parseDouble(args[3]));
            }
            catch (Throwable rock)
            {
                return false;
            }

            if (!shopListener.selectShop(player, location.getBlock()))
                config.sendMessage(player, "noShopThere");
            else
                player.sendMessage(LazyUtil.getClickableSuggestion(config.getString("promptBuy"),
                        "/" + label + " ", "/" + label));
            return true;
        }

        if (args[0].equalsIgnoreCase("cancel"))
        {
            unconfirmedTransactionMap.remove(player);
            config.sendMessage(player, "transactionCanceled");
            return true;
        }

        int quantity;

        try
        {
            quantity = Integer.valueOf(args[0]);
        }
        catch (Throwable rock)
        {
            return false;
        }

        buyCommand((Player)sender, quantity);
        return true;
    }

    public boolean buyCommand(Player player, int amount)
    {
        ShopInfo shopInfo = shopListener.getSelectedShop(player);
        if (shopInfo == null)
        {
            config.sendMessage(player, "noShopSelected");
            return false;
        }

        if (shopInfo.getPrice() < 0)
        {
            config.sendMessage(player, "noPrice");
            return false;
        }

        if (economy.getBalance(player) < amount * shopInfo.getPrice())
        {
            config.sendMessage(player, "noMoney");
            return false;
        }

        shopInfo.getItem().setAmount(amount);

        if (!hasInventorySpace(player, shopInfo.getItem()))
        {
            config.sendMessage(player, "noSpace");
        }

        if (config.getBoolean("confirmTransactions"))
        {
            if (!unconfirmedTransactionMap.containsKey(player) || !unconfirmedTransactionMap.remove(player).matches(shopInfo, amount))
            {
                unconfirmedTransactionMap.put(player, new UnconfirmedTransaction(player, shopInfo, amount, config, economy, bookUtil));
                return true;
            }
        }

        ItemStack itemStack = shopAPI.performTransaction(shopAPI.getChest(shopInfo.getLocation()), shopInfo.getItem(), shopInfo.getPrice());
        if (itemStack == null)
        {
            config.sendMessage(player, "shopModified");
            return false;
        }

        economy.withdrawPlayer(player, itemStack.getAmount() * shopInfo.getPrice());

        ShopInventoryHolder shopInventoryHolder = new ShopInventoryHolder();
        Inventory inventory = player.getServer().createInventory(shopInventoryHolder,
                ((itemStack.getAmount() * itemStack.getMaxStackSize()) / 9) + 1,
                config.getString("transactionCompleted", Integer.toString(itemStack.getAmount()), "", economy.format(itemStack.getAmount() * shopInfo.getPrice())));
        inventory.addItem(itemStack);
        shopInventoryHolder.setInventory(inventory);
        player.openInventory(inventory);

        //Map<Integer, ItemStack> leftovers = player.getInventory().addItem(itemStack);

        config.sendMessage(player, "transactionCompleted", Integer.toString(itemStack.getAmount()), PrettySimpleShop.getItemName(itemStack), economy.format(itemStack.getAmount() * shopInfo.getPrice()));

        player.getServer().getPluginManager().callEvent(new ShopBoughtEvent(player, shopInfo));

//        if (!leftovers.isEmpty())
//        {
//            player.sendMessage("Somehow you bought more than you can hold and we didn't detect this. Please report this issue with the following debug info:");
//            for (ItemStack itemStack1 : leftovers.values())
//                player.sendMessage(itemStack1.toString());
//            return true;
//        }
        return true;
    }

    //Drop items not taken from the "collection window"
    @EventHandler
    private void onCloseShopInventory(InventoryCloseEvent event)
    {
        if (!(event.getInventory().getHolder() instanceof ShopInventoryHolder))
            return;
        for (ItemStack itemStack : event.getInventory())
        {
            if (itemStack == null)
                continue;
            event.getPlayer().getLocation().getWorld().dropItemNaturally(event.getPlayer().getLocation(), itemStack);
        }
    }

    //https://www.spigotmc.org/threads/detecting-when-a-players-inventory-is-almost-full.132061/#post-1401285
    private boolean hasInventorySpace(Player p, ItemStack item) {
        int free = 0;
        for (ItemStack i : p.getInventory()) {
            if (i == null) {
                free += item.getMaxStackSize();
            } else if (i.isSimilar(item)) {
                free += item.getMaxStackSize() - i.getAmount();
            }
        }
        return free >= item.getAmount();
    }
}

class UnconfirmedTransaction
{
    private ShopInfo shopInfo;
    private int amount;

    UnconfirmedTransaction(Player player, ShopInfo shopInfo, int amount, ConfigManager config, Economy economy, BookUtil bookUtil)
    {
        this.shopInfo = shopInfo;
        this.amount = amount;
        shopInfo.getHoverableText().setText(PrettySimpleShop.getItemName(shopInfo.getItem()));
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta bookMeta = (BookMeta)book.getItemMeta();
        bookMeta.spigot().addPage(LazyUtil.buildPage(config.getString("BuyingQuantity", Integer.toString(amount)),
                "\n", shopInfo.getHoverableText(),
                "\n", config.getString("TotalCost", economy.format(amount * shopInfo.getPrice())),
                "\n", config.getString("currentBalanceAndCost", economy.format(economy.getBalance(player)), economy.format( - economy.getBalance(player) - amount * shopInfo.getPrice())),
                "\n", LazyUtil.getClickableCommand(config.getString("Confirm"), "/buy " + amount),
                " ", LazyUtil.getClickableCommand(config.getString("Cancel"), "/buy cancel")));

        book.setItemMeta(bookMeta);
        bookUtil.openBook(player, book);
    }

    public boolean matches(ShopInfo shopInfo, int amount)
    {
        return this.shopInfo.equals(shopInfo) && this.amount == amount;
    }
}

class ShopInventoryHolder implements InventoryHolder
{
    private Inventory inventory;

    public void setInventory(Inventory inventory)
    {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory()
    {
        return inventory;
    }
}
