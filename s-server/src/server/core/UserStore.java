package server.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class UserStore {
    private static final UserStore INSTANCE = new UserStore();

    // username -> User
    private final Map<String, User> byName = new ConcurrentHashMap<>();
    // id -> User (ids are simple incrementing ints)
    private final Map<Integer, User> byId = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger(1);

    private UserStore() {}

    public static UserStore get() {
        return INSTANCE;
    }

    /** Create-or-get by username; assigns a simple sequential id on first creation. */
    public User getOrCreate(String username) {
        String key = username.trim();
        return byName.computeIfAbsent(key, n -> {
            int id = seq.getAndIncrement();
            User u = new User(id, n, 1000);
            byId.put(id, u);
            return u;
        });
    }

    /** Lookup by username; null if not found. */
    public User getByName(String username) {
        if (username == null) return null;
        return byName.get(username.trim());
    }

    /** Lookup by string id (EngineFacadeImpl passes String). */
    public User getById(String userId) {
        if (userId == null || userId.isBlank()) return null;
        try {
            int id = Integer.parseInt(userId.trim());
            return byId.get(id);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Add (or subtract if negative) credits to the user with the given id string.
     * Returns the updated credit balance; returns 0 if user not found.
     */
    public int charge(String userId, int amount) {
        User u = getById(userId);
        if (u == null) return 0;
        u.setCredits(u.getCredits() + amount);
        return u.getCredits();
    }
}
