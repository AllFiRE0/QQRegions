package dev.qqregions.raid;

import dev.qqregions.QQRegions;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Обёртка над JustTeams (мягкая зависимость, рефлексия — в pom.xml
 * зависимость не добавляли, чтобы не зависеть от jitpack). Если плагин
 * не установлен/не отвечает — {@link #enabled()} возвращает false и рейд
 * недоступен. Списание с банка клана сохраняется через
 * IDataStorage.updateTeamBalance + markTeamModified + publishCrossServerUpdate
 * (тот же путь, что использует сам JustTeams в TeamManager.deposit/withdraw).
 */
public final class JustTeamsHook {

    private final QQRegions plugin;

    private Object justTeams;       // eu.kotori.justTeams.JustTeams
    private Object teamManager;     // eu.kotori.justTeams.team.TeamManager
    private Object storage;         // eu.kotori.justTeams.storage.IDataStorage

    private Method mGetInstance;
    private Method mGetTeamManager;
    private Method mGetStorageManager;
    private Method mGetStorage;
    private Method mGetPlayerTeamCached;      // Team getPlayerTeamCached(UUID)
    private Method mGetPlayerTeam;            // Team getPlayerTeam(UUID)
    private Method mMarkTeamModified;         // void markTeamModified(int)
    private Method mPublishCrossServerUpdate; // void publishCrossServerUpdate(int,String,String,String)
    private Method mUpdateTeamBalance;        // void updateTeamBalance(int,double)

    private Class<?> teamClass;
    private Method mTeamGetId;
    private Method mTeamGetName;
    private Method mTeamGetOwnerUuid;
    private Method mTeamGetBalance;
    private Method mTeamRemoveBalance;
    private Method mTeamIsOwner;
    private Method mTeamIsMember;
    private Method mTeamGetMembers;

    private Class<?> teamPlayerClass;
    private Method mTpGetPlayerUuid;
    private Method mTpIsOnline;

    public JustTeamsHook(QQRegions plugin) {
        this.plugin = plugin;
        reload();
    }

    /** Переподключение после /region reload (или включения плагина). */
    public void reload() {
        justTeams = null;
        teamManager = null;
        storage = null;
        mGetInstance = mGetTeamManager = mGetStorageManager = mGetStorage = null;
        mGetPlayerTeamCached = mGetPlayerTeam = null;
        mMarkTeamModified = mPublishCrossServerUpdate = mUpdateTeamBalance = null;
        teamClass = teamPlayerClass = null;
        try {
            Class<?> clazz = Class.forName("eu.kotori.justTeams.JustTeams");
            mGetInstance = clazz.getMethod("getInstance");
            Object inst = mGetInstance.invoke(null);
            if (inst == null) {
                return;
            }
            justTeams = inst;
            mGetTeamManager = clazz.getMethod("getTeamManager");
            teamManager = mGetTeamManager.invoke(inst);
            if (teamManager == null) {
                return;
            }

            mGetStorageManager = clazz.getMethod("getStorageManager");
            Object sm = mGetStorageManager.invoke(justTeams);
            mGetStorage = sm == null ? null : fieldlessMethod(sm.getClass(), "getStorage");
            if (sm != null && mGetStorage != null) {
                storage = mGetStorage.invoke(sm);
            }

            mGetPlayerTeamCached = teamMethod("getPlayerTeamCached");
            mGetPlayerTeam = teamMethod("getPlayerTeam");
            mMarkTeamModified = teamMethod("markTeamModified");
            mPublishCrossServerUpdate = teamMethod("publishCrossServerUpdate");
            if (storage != null) {
                mUpdateTeamBalance = fieldlessMethod(storage.getClass(), "updateTeamBalance");
            }

            if (mGetPlayerTeam != null && mGetPlayerTeam.getReturnType() != null) {
                teamClass = mGetPlayerTeam.getReturnType();
                mTeamGetId = fieldlessMethod(teamClass, "getId");
                mTeamGetName = fieldlessMethod(teamClass, "getName");
                mTeamGetOwnerUuid = fieldlessMethod(teamClass, "getOwnerUuid");
                mTeamGetBalance = fieldlessMethod(teamClass, "getBalance");
                mTeamRemoveBalance = fieldlessMethod(teamClass, "removeBalance");
                mTeamIsOwner = fieldlessMethod(teamClass, "isOwner");
                mTeamIsMember = fieldlessMethod(teamClass, "isMember");

                mTeamGetMembers = fieldlessMethod(teamClass, "getMembers");
                if (mTeamGetMembers != null && mTeamGetMembers.getReturnType() != null) {
                    // Team.getMembers() : List<TeamPlayer>
                    teamPlayerClass = genericFirstParam(mTeamGetMembers);
                    if (teamPlayerClass != null) {
                        mTpGetPlayerUuid = fieldlessMethod(teamPlayerClass, "getPlayerUuid");
                        mTpIsOnline = fieldlessMethod(teamPlayerClass, "isOnline");
                    }
                }
            }
        } catch (Throwable t) {
            plugin.dbg("JustTeamsHook: " + t.getMessage());
        }
    }

    public boolean enabled() {
        return justTeams != null && teamManager != null && mGetPlayerTeamCached != null;
    }

    /** Клан игрока (сначала кэш, при пустом кэше — полный запрос). */
    public TeamRef team(UUID playerId) {
        if (!enabled() || playerId == null) {
            return null;
        }
        Object cached = invoke(mGetPlayerTeamCached, teamManager, playerId);
        Object team = cached != null ? cached : invoke(mGetPlayerTeam, teamManager, playerId);
        return team == null ? null : new TeamRef(team);
    }

    /** Клан игрока по нику (для контекста меню). */
    public TeamRef team(OfflinePlayer player) {
        return player == null ? null : team(player.getUniqueId());
    }

    /**
     * Списать amount с баланса клана и сохранить: removeBalance +
     * storage.updateTeamBalance + markTeamModified + publishCrossServerUpdate.
     * Возвращает true при успехе.
     */
    public boolean charge(TeamRef team, double amount) {
        if (team == null || !enabled() || amount <= 0) {
            return false;
        }
        try {
            Double bal = (Double) mTeamGetBalance.invoke(team.raw);
            if (bal == null || bal < amount) {
                return false;
            }
            mTeamRemoveBalance.invoke(team.raw, amount);
            double newBalance = bal - amount;
            if (storage != null && mUpdateTeamBalance != null) {
                mUpdateTeamBalance.invoke(storage, team.id, newBalance);
            }
            if (mMarkTeamModified != null) {
                mMarkTeamModified.invoke(teamManager, team.id);
            }
            if (mPublishCrossServerUpdate != null) {
                mPublishCrossServerUpdate.invoke(teamManager, team.id,
                        "BANK_UPDATE", null, String.valueOf(newBalance));
            }
            return true;
        } catch (Throwable t) {
            plugin.dbg("JustTeamsHook.charge: " + t.getMessage());
            return false;
        }
    }

    /** Баланс банка клана. */
    public double balance(TeamRef team) {
        if (team == null || !enabled() || mTeamGetBalance == null) {
            return 0;
        }
        try {
            Double v = (Double) mTeamGetBalance.invoke(team.raw);
            return v == null ? 0 : v;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Баланс банка клана (не требует проверок enabled — для расчёта процента). */
    public double balanceRaw(TeamRef team) {
        if (team == null || mTeamGetBalance == null) {
            return 0;
        }
        return balance(team);
    }

    /** Онлайн-игроки клана (по членам клана, с подстраховкой через Bukkit). */
    public List<UUID> onlineMembers(TeamRef team) {
        List<UUID> out = new ArrayList<>();
        if (team == null || !enabled() || mTeamGetMembers == null) {
            return out;
        }
        try {
            Object members = mTeamGetMembers.invoke(team.raw);
            if (!(members instanceof Iterable<?> it)) {
                return out;
            }
            for (Object m : it) {
                UUID uuid = teamPlayerUuid(m);
                if (uuid == null) {
                    continue;
                }
                boolean online = teamPlayerOnline(m);
                Player p = Bukkit.getPlayer(uuid);
                if (online && p != null) {
                    out.add(uuid);
                }
            }
        } catch (Throwable t) {
            plugin.dbg("JustTeamsHook.onlineMembers: " + t.getMessage());
        }
        return out;
    }

    private UUID teamPlayerUuid(Object tp) {
        if (tp == null || mTpGetPlayerUuid == null) {
            return null;
        }
        try {
            return (UUID) mTpGetPlayerUuid.invoke(tp);
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean teamPlayerOnline(Object tp) {
        if (tp == null || mTpIsOnline == null) {
            return false;
        }
        try {
            Object v = mTpIsOnline.invoke(tp);
            return Boolean.TRUE.equals(v);
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------- рефлексия ----------

    private Method teamMethod(String name) {
        return fieldlessMethod(teamManager.getClass(), name);
    }

    private Method fieldlessMethod(Class<?> c, String name) {
        if (c == null) {
            return null;
        }
        try {
            return c.getMethod(name);
        } catch (NoSuchMethodException e) {
            for (Method m : c.getMethods()) {
                if (m.getName().equals(name)) {
                    return m;
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Class<?> genericFirstParam(Method m) {
        try {
            java.lang.reflect.ParameterizedType pt = (java.lang.reflect.ParameterizedType) m.getGenericReturnType();
            java.lang.reflect.Type arg = pt.getActualTypeArguments()[0];
            return arg instanceof Class<?> c ? c : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object invoke(Method m, Object target, Object... args) {
        try {
            return m.invoke(target, args);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Лёгкая ссылка на клан: id, имя, владелец, сырой объект Team. */
    public static final class TeamRef {
        final Object raw;
        final int id;
        final String name;
        final UUID ownerUuid;

        TeamRef(Object raw) {
            this.raw = raw;
            this.id = toId(raw);
            this.name = toName(raw);
            this.ownerUuid = toOwner(raw);
        }

        public int id() {
            return id;
        }

        public String name() {
            return name;
        }

        public UUID ownerUuid() {
            return ownerUuid;
        }

        private static int toId(Object raw) {
            try {
                Method m = raw.getClass().getMethod("getId");
                Object v = m.invoke(raw);
                return v instanceof Number n ? n.intValue() : 0;
            } catch (Throwable t) {
                return 0;
            }
        }

        private static String toName(Object raw) {
            try {
                Method m = raw.getClass().getMethod("getName");
                Object v = m.invoke(raw);
                return v == null ? "?" : v.toString();
            } catch (Throwable t) {
                return "?";
            }
        }

        private static UUID toOwner(Object raw) {
            try {
                Method m = raw.getClass().getMethod("getOwnerUuid");
                Object v = m.invoke(raw);
                return v instanceof UUID u ? u : null;
            } catch (Throwable t) {
                return null;
            }
        }
    }
}