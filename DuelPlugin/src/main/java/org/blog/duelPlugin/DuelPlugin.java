package org.blog.duelPlugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public final class DuelPlugin extends JavaPlugin implements Listener {

    // === 메뉴 타이틀 ===
    private static final String MENU_TITLE_SELECT_TARGET = "§a듀얼 상대 선택";
    private static final String MENU_TITLE_CLASS = "§a병과 선택";
    private static final String MENU_TITLE_CONFIRM = "§a듀얼 요청 (승인/거절)";

    // 병과 슬롯(27칸 인벤 중앙)
    private static final int SLOT_SWORD = 11;
    private static final int SLOT_BOW   = 13;
    private static final int SLOT_LANCE = 15;

    // 확인 메뉴 슬롯
    private static final int SLOT_ACCEPT = 11; // 승인(초록)
    private static final int SLOT_DECLINE = 15; // 거절(빨강)

    // 말/상태 추적
    private final Set<UUID> duelHorseIds = new HashSet<>();
    private final Set<UUID> sneakingPlayers = new HashSet<>();
    private final Map<UUID, UUID> pendingTarget = new HashMap<>(); // opener -> target
    private final Map<UUID, CombatClass> pendingClass = new HashMap<>(); // opener -> picked class
    private final Map<UUID, UUID> pendingRequestByTarget = new HashMap<>(); // target -> opener

    // 진행 중 듀얼: 플레이어 UUID -> Duel
    private final Map<UUID, Duel> activeDuels = new HashMap<>();
    // 사망자: 리스폰 시 처리
    private final Set<UUID> pendingRespawn = new HashSet<>();
    // 강제 동사 표식(상대 동사 유발시 드랍/중복 종료 방지)
    private final Set<UUID> forcedDeath = new HashSet<>();

    private enum CombatClass { SWORD, BOW, LANCE }

    private static final class Duel {
        final UUID p1, p2;
        final CombatClass picked;          // 동일 병과
        final Set<UUID> horses = new HashSet<>(); // 이 듀얼에서 소환된 말들
        int actionbarTaskId = -1;          // 검 병과 액션바 태스크
        boolean ending = false;            // 종료 스케줄 진행 중 여부

        Duel(UUID p1, UUID p2, CombatClass picked) {
            this.p1 = p1; this.p2 = p2; this.picked = picked;
        }
        boolean involves(UUID u) { return p1.equals(u) || p2.equals(u); }
        UUID other(UUID u) { return p1.equals(u) ? p2 : p1; }
    }

    // ─────────────────────────────────────
    // 말 힐(밀짚 더미 우클릭) 상태값
    private final Map<UUID, Long> horseHealCooldown = new HashMap<>(); // 플레이어별 쿨타임 종료 시각(ms)
    private final Map<UUID, Integer> horseHealTaskId = new HashMap<>(); // 진행중 힐 태스크

    private static final double HEAL_PER_WHEAT = 9.0;   // ★ 밀짚 1개당 9HP(하트 4.5칸)
    private static final double HEAL_STEP = 0.5;        // 0.5HP씩
    private static final long   HEAL_PERIOD_TICKS = 5L; // 0.25초 간격
    // ─────────────────────────────────────

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("DuelPlugin Enabled!");
    }

    public Map<UUID, Duel> getActiveDuels() { return activeDuels; }

    /* =========================
           입력 감지 (Shift+F)
       ========================= */

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();
        if (event.isSneaking()) sneakingPlayers.add(player.getUniqueId());
        else sneakingPlayers.remove(player.getUniqueId());
    }

    @EventHandler
    public void onSwapHand(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        if (sneakingPlayers.contains(player.getUniqueId())) {
            event.setCancelled(true);
            if (activeDuels.containsKey(player.getUniqueId())) {
                player.sendMessage("§c듀얼 진행 중에는 메뉴를 열 수 없습니다.");
                return;
            }
            openTargetMenu(player);
        }
    }

    /* =========================
               메뉴들
       ========================= */

    private void openTargetMenu(Player opener) {
        Inventory menu = Bukkit.createInventory(null, 54, MENU_TITLE_SELECT_TARGET);

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.equals(opener)) continue;

            ItemStack head = new ItemStack(Material.PLAYER_HEAD, 1);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(p);
            meta.setDisplayName("§a" + p.getName());
            head.setItemMeta(meta);

            menu.addItem(head);
        }
        opener.openInventory(menu);
    }

    private void openClassMenu(Player opener, Player target) {
        pendingTarget.put(opener.getUniqueId(), target.getUniqueId());

        Inventory inv = Bukkit.createInventory(null, 27, MENU_TITLE_CLASS);

        inv.setItem(SLOT_SWORD, icon(Material.DIAMOND_SWORD, "§a검",
                "§7근접전 특화 (말 탑승 중 데미지 +10%)", "§e클릭하여 선택"));
        inv.setItem(SLOT_BOW, icon(Material.BOW, "§a활",
                "§7원거리 특화 (무한 화살)", "§e클릭하여 선택"));

        ItemStack lance = new ItemStack(Material.IRON_AXE);
        ItemMeta lm = lance.getItemMeta();
        lm.setDisplayName("§a창");
        lm.setLore(Arrays.asList("§7리소스팩: 도끼→창", "§e클릭하여 선택"));
        lance.setItemMeta(lm);
        lance.addUnsafeEnchantment(Enchantment.DAMAGE_UNDEAD, 3);
        lance.addUnsafeEnchantment(Enchantment.DAMAGE_ALL, 3);
        inv.setItem(SLOT_LANCE, lance);

        opener.openInventory(inv);
    }

    private void openConfirmMenu(Player target, Player opener, CombatClass picked) {
        Inventory inv = Bukkit.createInventory(null, 27, MENU_TITLE_CONFIRM);

        String cls = switch (picked) { case SWORD -> "검"; case BOW -> "활"; case LANCE -> "창"; };

        ItemStack accept = icon(Material.LIME_CONCRETE, "§a듀얼 승인",
                "§7신청자: §a" + opener.getName(),
                "§7병과: §f" + cls,
                "§e클릭하여 듀얼을 시작합니다");
        ItemStack decline = icon(Material.RED_CONCRETE, "§c듀얼 거절",
                "§7신청자: §a" + opener.getName(),
                "§e클릭하여 거절합니다");

        inv.setItem(SLOT_ACCEPT, accept);
        inv.setItem(SLOT_DECLINE, decline);

        target.openInventory(inv);
    }

    private ItemStack icon(Material mat, String name, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(name);
        if (lore != null && lore.length > 0) m.setLore(Arrays.asList(lore));
        it.setItemMeta(m);
        return it;
    }

    /* =========================
            인벤 클릭 처리
       ========================= */

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        String title = event.getView().getTitle();
        if (title == null) return;

        if (title.equals(MENU_TITLE_SELECT_TARGET) || title.equals(MENU_TITLE_CLASS) || title.equals(MENU_TITLE_CONFIRM)) {
            event.setCancelled(true);
        } else return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        // 상대 선택 메뉴
        if (title.equals(MENU_TITLE_SELECT_TARGET)) {
            String targetName = clicked.getItemMeta().getDisplayName().replace("§a", "");
            Player target = Bukkit.getPlayerExact(targetName);
            if (target == null || !target.isOnline()) {
                player.sendMessage("§c플레이어를 찾을 수 없습니다.");
                return;
            }
            if (activeDuels.containsKey(player.getUniqueId()) || activeDuels.containsKey(target.getUniqueId())) {
                player.sendMessage("§c상대 또는 당신이 이미 듀얼 중입니다.");
                return;
            }
            openClassMenu(player, target);
            return;
        }

        // 병과 선택 메뉴
        if (title.equals(MENU_TITLE_CLASS)) {
            UUID targetId = pendingTarget.get(player.getUniqueId()); // 승인까지 유지
            if (targetId == null) {
                player.sendMessage("§c상대 정보를 찾을 수 없습니다.");
                player.closeInventory();
                return;
            }
            Player target = Bukkit.getPlayer(targetId);
            if (target == null || !target.isOnline()) {
                player.sendMessage("§c상대가 오프라인입니다.");
                player.closeInventory();
                return;
            }

            int slot = event.getRawSlot();
            CombatClass picked = switch (slot) {
                case SLOT_SWORD -> CombatClass.SWORD;
                case SLOT_BOW   -> CombatClass.BOW;
                case SLOT_LANCE -> CombatClass.LANCE;
                default -> null;
            };
            if (picked == null) return;

            // 요청 보관 & 상대에게 확인 창
            pendingClass.put(player.getUniqueId(), picked);
            pendingRequestByTarget.put(target.getUniqueId(), player.getUniqueId());

            player.closeInventory();
            openConfirmMenu(target, player, picked);
            player.sendMessage("§a" + target.getName() + "§7에게 듀얼 요청을 보냈습니다. (승인/거절 대기)");
            target.sendMessage("§a" + player.getName() + "§7로부터 듀얼 요청이 도착했습니다.");
            return;
        }

        // 승인/거절 메뉴
        if (title.equals(MENU_TITLE_CONFIRM)) {
            UUID targetId = player.getUniqueId(); // 이 메뉴를 연 사람 = 초대받은 대상
            UUID openerId = pendingRequestByTarget.get(targetId);
            if (openerId == null) {
                player.sendMessage("§c유효하지 않은 요청입니다.");
                player.closeInventory();
                return;
            }
            Player opener = Bukkit.getPlayer(openerId);
            if (opener == null || !opener.isOnline()) {
                player.sendMessage("§c신청자가 오프라인입니다. 요청이 취소되었습니다.");
                cleanupPending(openerId, targetId);
                player.closeInventory();
                return;
            }
            CombatClass picked = pendingClass.get(openerId);
            if (picked == null) {
                player.sendMessage("§c요청 정보가 손상되었습니다.");
                cleanupPending(openerId, targetId);
                player.closeInventory();
                return;
            }

            int slot = event.getRawSlot();
            if (slot == SLOT_ACCEPT) {
                if (activeDuels.containsKey(openerId) || activeDuels.containsKey(targetId)) {
                    player.sendMessage("§c이미 듀얼 중인 플레이어가 있습니다. 요청이 취소됩니다.");
                    if (opener.isOnline()) opener.sendMessage("§c요청이 취소되었습니다. (이미 듀얼 중)");
                    cleanupPending(openerId, targetId);
                    player.closeInventory();
                    return;
                }
                player.closeInventory();
                cleanupPending(openerId, targetId);
                startDuel(opener, player, picked);
            } else if (slot == SLOT_DECLINE) {
                player.closeInventory();
                cleanupPending(openerId, targetId);
                player.sendMessage("§c듀얼 요청을 거절했습니다.");
                if (opener.isOnline()) opener.sendMessage("§c상대가 듀얼 요청을 거절했습니다.");
            }
        }
    }

    private void cleanupPending(UUID openerId, UUID targetId) {
        pendingRequestByTarget.remove(targetId);
        pendingClass.remove(openerId);
        pendingTarget.remove(openerId);
    }

    /* =========================
              듀얼 로직
       ========================= */

    private void startDuel(Player p1, Player p2, CombatClass picked) {
        // 1. 위치 이동 (듀얼 경기장 좌표 예시)
        p1.teleport(new Location(p1.getWorld(), 132, -56, 57));
        p2.teleport(new Location(p2.getWorld(), 132, -54, 151));

        // 2. 모험 모드
        p1.setGameMode(GameMode.ADVENTURE);
        p2.setGameMode(GameMode.ADVENTURE);

        // 3. 병과 조건에 따라 말 스폰: 검/활만 말, 창은 말 없음
        Horse h1 = null, h2 = null;
        if (picked == CombatClass.SWORD || picked == CombatClass.BOW) {
            h1 = spawnDuelHorse(p1);
            h2 = spawnDuelHorse(p2);
        }

        // 4. 장비 지급
        setupGearCommon(p1);
        setupGearCommon(p2);
        giveClassLoadout(p1, picked);
        giveClassLoadout(p2, picked);

        // 5. 탑승
        if (h1 != null) h1.addPassenger(p1);
        if (h2 != null) h2.addPassenger(p2);

        // 6. 듀얼 등록
        Duel duel = new Duel(p1.getUniqueId(), p2.getUniqueId(), picked);
        if (h1 != null) { duel.horses.add(h1.getUniqueId()); duelHorseIds.add(h1.getUniqueId()); }
        if (h2 != null) { duel.horses.add(h2.getUniqueId()); duelHorseIds.add(h2.getUniqueId()); }
        activeDuels.put(p1.getUniqueId(), duel);
        activeDuels.put(p2.getUniqueId(), duel);

        // 7. 메시지
        String cls = switch (picked) { case SWORD -> "검"; case BOW -> "활"; case LANCE -> "창"; };
        p1.sendMessage("§a" + p2.getName() + "와 듀얼이 시작되었습니다! §7(병과: " + cls + ")");
        p2.sendMessage("§a" + p1.getName() + "와 듀얼이 시작되었습니다! §7(병과: " + cls + ")");

        // 8. 검 병과: 액션바(말 탑승 중에만) 주기 송출
        if (picked == CombatClass.SWORD) {
            int taskId = getServer().getScheduler().runTaskTimer(this, () -> {
                Player a = Bukkit.getPlayer(duel.p1);
                Player b = Bukkit.getPlayer(duel.p2);
                Component c = Component.text("검기마 데미지 10% 증가").color(NamedTextColor.AQUA);
                if (a != null && a.isOnline() && a.getVehicle() instanceof AbstractHorse h && !h.isDead()) a.sendActionBar(c);
                if (b != null && b.isOnline() && b.getVehicle() instanceof AbstractHorse h && !h.isDead()) b.sendActionBar(c);
            }, 0L, 40L).getTaskId(); // 2초마다
            duel.actionbarTaskId = taskId;
        }
    }

    // 공통 방어구
    private void setupGearCommon(Player p) {
        p.getInventory().clear();
        p.getInventory().setHelmet(ench(new ItemStack(Material.DIAMOND_HELMET), Enchantment.PROTECTION_ENVIRONMENTAL, 2));
        p.getInventory().setChestplate(ench(new ItemStack(Material.DIAMOND_CHESTPLATE), Enchantment.PROTECTION_ENVIRONMENTAL, 2));
        p.getInventory().setLeggings(ench(new ItemStack(Material.DIAMOND_LEGGINGS), Enchantment.PROTECTION_ENVIRONMENTAL, 2));
        p.getInventory().setBoots(ench(new ItemStack(Material.DIAMOND_BOOTS), Enchantment.PROTECTION_ENVIRONMENTAL, 2));
    }

    // 병과별 지급 (활 = Power III, 검/활에 밀짚 64 지급 + 검에 도끼/방패)
    private void giveClassLoadout(Player p, CombatClass picked) {
        p.getInventory().setItem(8, new ItemStack(Material.GOLDEN_APPLE, 8));
        p.getInventory().setItem(7, new ItemStack(Material.COOKED_BEEF, 32));

        switch (picked) {
            case SWORD -> {
                ItemStack sword = ench(new ItemStack(Material.DIAMOND_SWORD), Enchantment.DAMAGE_ALL, 2);
                p.getInventory().setItem(0, sword);
                // 강타 I 다이아 도끼 + 방패(오프핸드)
                p.getInventory().addItem(ench(new ItemStack(Material.DIAMOND_AXE), Enchantment.DAMAGE_UNDEAD, 1));
                p.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));
                // 밀짚
                p.getInventory().setItem(1, new ItemStack(Material.HAY_BLOCK, 64));
            }
            case BOW -> {
                ItemStack bow = ench(new ItemStack(Material.BOW), Enchantment.ARROW_DAMAGE, 3); // 힘 III
                bow.addEnchantment(Enchantment.ARROW_INFINITE, 1);
                p.getInventory().setItem(0, bow);
                // 화살은 인벤토리 (핫바 아님)
                p.getInventory().setItem(9, new ItemStack(Material.ARROW, 1));
                // 밀짚
                p.getInventory().setItem(1, new ItemStack(Material.HAY_BLOCK, 64));
            }
            case LANCE -> {
                ItemStack axe = new ItemStack(Material.IRON_AXE);
                ItemMeta m = axe.getItemMeta();
                m.setDisplayName("§a창");
                axe.setItemMeta(m);
                axe.addUnsafeEnchantment(Enchantment.DAMAGE_UNDEAD, 3);
                axe.addUnsafeEnchantment(Enchantment.DAMAGE_ALL, 3);
                p.getInventory().setItem(0, axe);
            }
        }
    }

    private Horse spawnDuelHorse(Player player) {
        Horse horse = (Horse) player.getWorld().spawnEntity(player.getLocation(), EntityType.HORSE);
        horse.setOwner(player);
        horse.setTamed(true);
        horse.setAdult();
        horse.setMaxHealth(30.0);
        horse.setHealth(30.0);

        AttributeInstance speed = horse.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED);
        if (speed != null) speed.setBaseValue(0.30);
        horse.setJumpStrength(1.0);

        ItemStack armor = new ItemStack(Material.DIAMOND_HORSE_ARMOR);
        ItemMeta meta = armor.getItemMeta();
        meta.addEnchant(Enchantment.PROTECTION_ENVIRONMENTAL, 1, true);
        armor.setItemMeta(meta);

        horse.getInventory().setArmor(armor);
        horse.getInventory().setSaddle(new ItemStack(Material.SADDLE));
        return horse;
    }

    /* =========================
          전투/종료 처리
       ========================= */

    // 검 병과 데미지 +10% — 말 탑승 중에만
    @EventHandler(ignoreCancelled = true)
    public void onDamage(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player attacker)) return;
        Duel duel = activeDuels.get(attacker.getUniqueId());
        if (duel == null || duel.picked != CombatClass.SWORD) return;
        if (!(e.getEntity() instanceof Player victim)) return;
        if (!duel.involves(victim.getUniqueId())) return;

        if (attacker.getVehicle() instanceof AbstractHorse h && !h.isDead()) {
            e.setDamage(e.getDamage() * 1.10);
        }
    }

    // 플레이어 사망 → 2초 뒤 상대도 사망 + 듀얼 종료
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent e) {
        Player dead = e.getEntity();

        // 강제 사망으로 유발된 두 번째 이벤트: 드랍만 제거 후 종료
        if (forcedDeath.remove(dead.getUniqueId())) {
            e.getDrops().clear();
            e.setDroppedExp(0);
            return;
        }

        Duel duel = activeDuels.get(dead.getUniqueId());
        if (duel == null) return;

        // 이미 종료 스케줄이 잡혀 있으면 중복 처리 방지
        if (duel.ending) {
            e.getDrops().clear();
            e.setDroppedExp(0);
            return;
        }
        duel.ending = true;

        // 원래 죽은 사람 드랍 제거 + 리스폰 마킹
        e.getDrops().clear();
        e.setDroppedExp(0);
        pendingRespawn.add(dead.getUniqueId());

        UUID otherId = duel.other(dead.getUniqueId());
        Player other = Bukkit.getPlayer(otherId);

        // 2초(40틱) 뒤 상대를 강제 사망시키고, 듀얼 종료
        getServer().getScheduler().runTaskLater(this, () -> {
            if (other != null && other.isOnline() && other.getHealth() > 0.0) {
                forcedDeath.add(other.getUniqueId());
                other.setHealth(0.0);
                pendingRespawn.add(other.getUniqueId());
            }
            endDuel(duel);
        }, 40L);
    }

    // 리스폰 시 지급 장비 제거
    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (!pendingRespawn.remove(p.getUniqueId())) return;
        Bukkit.getScheduler().runTask(this, () -> {
            p.getInventory().clear();
            p.getInventory().setArmorContents(null);
            p.getInventory().setItemInOffHand(null);
        });
    }

    // 말 사망 → 드랍 방지
    @EventHandler
    public void onHorseDeath(EntityDeathEvent e) {
        if (e.getEntity().getType() == EntityType.HORSE) {
            UUID id = e.getEntity().getUniqueId();
            if (duelHorseIds.remove(id)) {
                e.getDrops().clear();
                e.setDroppedExp(0);
            }
        }
    }

    // 듀얼 종료 처리
    private void endDuel(Duel duel) {
        if (duel.actionbarTaskId != -1) {
            getServer().getScheduler().cancelTask(duel.actionbarTaskId);
        }

        // 진행 중 힐 태스크/쿨타임 정리
        for (UUID uid : List.of(duel.p1, duel.p2)) {
            Integer tid = horseHealTaskId.remove(uid);
            if (tid != null) getServer().getScheduler().cancelTask(tid);
            horseHealCooldown.remove(uid);
        }

        for (UUID hid : duel.horses) {
            for (World w : Bukkit.getWorlds()) {
                var ent = w.getEntity(hid);
                if (ent instanceof Horse h && !h.isDead()) h.remove();
            }
            duelHorseIds.remove(hid);
        }

        Player p1 = Bukkit.getPlayer(duel.p1);
        Player p2 = Bukkit.getPlayer(duel.p2);
        Component msg = Component.text("듀얼이 종료되었습니다.").color(NamedTextColor.GRAY);

        if (p1 != null && p1.isOnline()) {
            p1.getInventory().clear(); p1.getInventory().setArmorContents(null);
            p1.getInventory().setItemInOffHand(null);
            p1.sendMessage(msg);
        }
        if (p2 != null && p2.isOnline()) {
            p2.getInventory().clear(); p2.getInventory().setArmorContents(null);
            p2.getInventory().setItemInOffHand(null);
            p2.sendMessage(msg);
        }

        activeDuels.remove(duel.p1);
        activeDuels.remove(duel.p2);
    }

    private ItemStack ench(ItemStack item, Enchantment ench, int level) {
        ItemMeta meta = item.getItemMeta();
        meta.addEnchant(ench, level, true);
        item.setItemMeta(meta);
        return item;
    }

    // ─────────────────────────────────────────────────────
    //           말 힐: 밀짚 더미 우클릭 (게이지만, 텍스트 없음)
    // ─────────────────────────────────────────────────────
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onWheatUseWhileRiding(PlayerInteractEvent e) {
        // 우클릭만 처리
        Action act = e.getAction();
        if (act != Action.RIGHT_CLICK_AIR && act != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();

        // 우클릭한 손의 아이템
        ItemStack used = (e.getHand() == EquipmentSlot.OFF_HAND)
                ? p.getInventory().getItemInOffHand()
                : p.getInventory().getItemInMainHand();

        // 밀짚 더미 체크
        if (used == null || used.getType() != Material.HAY_BLOCK) return;

        // 말 탑승 여부
        if (!(p.getVehicle() instanceof AbstractHorse horse)) return;

        // 창 병과는 힐 사용 불가 그대로 유지
        Duel duel = activeDuels.get(p.getUniqueId());
        if (duel != null && duel.picked == CombatClass.LANCE) return;

        // 풀피면 조용히 무시
        double max = horse.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
        double now = horse.getHealth();
        if (now >= max - 1e-9) {
            return;
        }

        // 병과별 쿨타임
        int cdSec = (duel == null) ? 10 : switch (duel.picked) {
            case SWORD -> 10;
            case BOW   -> 20;
            case LANCE -> 10;
        };

        long nowMs = System.currentTimeMillis();
        long until = horseHealCooldown.getOrDefault(p.getUniqueId(), 0L);
        if (nowMs < until) {
            // 쿨타임 중이면 조용히 무시 (게이지는 이미 돌아가고 있음)
            e.setCancelled(true);
            return;
        }

        // 기본 상호작용 막기
        e.setCancelled(true);

        // 밀짚 1개 소모
        if (e.getHand() == EquipmentSlot.OFF_HAND) {
            if (used.getAmount() <= 1) p.getInventory().setItemInOffHand(null);
            else used.setAmount(used.getAmount() - 1);
        } else {
            if (used.getAmount() <= 1) p.getInventory().setItemInMainHand(null);
            else used.setAmount(used.getAmount() - 1);
        }
        p.updateInventory();

        // 쿨타임 시작(게이지 표시)
        horseHealCooldown.put(p.getUniqueId(), nowMs + cdSec * 1000L);
        p.setCooldown(Material.HAY_BLOCK, cdSec * 20); // ★ 게이지만

        // 중복 사용 방지: 진행중 태스크 있으면 조용히 무시
        if (horseHealTaskId.containsKey(p.getUniqueId())) {
            return;
        }

        // 한 번에 채울 총량 — 1개당 9HP
        final double targetHeal = Math.min(HEAL_PER_WHEAT, max - now);

        // 서서히 회복
        BukkitRunnable task = new BukkitRunnable() {
            double healed = 0.0;

            @Override public void run() {
                if (!horse.isValid() || p.getVehicle() != horse) { stop(); return; }
                double step = Math.min(HEAL_STEP, targetHeal - healed);
                if (step <= 0.0) { stop(); return; }

                double cur = horse.getHealth();
                double next = Math.min(cur + step, max);
                if (next <= cur) { stop(); return; }

                horse.setHealth(next);
                healed += (next - cur);

                if (healed >= targetHeal - 1e-6) stop();
            }

            private void stop() {
                Integer tid = horseHealTaskId.remove(p.getUniqueId());
                if (tid != null) { /* nothing */ }
                cancel();
            }
        };
        int id = task.runTaskTimer(this, 0L, HEAL_PERIOD_TICKS).getTaskId();
        horseHealTaskId.put(p.getUniqueId(), id);
    }

}
