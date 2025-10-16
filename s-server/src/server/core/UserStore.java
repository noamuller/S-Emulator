package server.core;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class UserStore {
    private static final UserStore INSTANCE = new UserStore();
    public static UserStore get() { return INSTANCE; }

    private final Map<String, User> byId = new ConcurrentHashMap<>();
    private final Map<String, String> idByName = new ConcurrentHashMap<>();

    private UserStore() {}

    public synchronized User loginOrCreate(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username is required");
        }
        String id = idByName.get(username);
        if (id != null) return byId.get(id);

        User u = new User(username.trim(), /*startingCredits*/ 1000);
        byId.put(u.getId(), u);
        idByName.put(u.getUsername(), u.getId());
        return u;
    }

    public User getById(String id) { return byId.get(id); }

    public synchronized int charge(String id, int amount) {
        if (amount <= 0) throw new IllegalArgumentException("amount must be > 0");
        User u = byId.get(id);
        if (u == null) throw new NoSuchElementException("user not found");
        u.addCredits(amount);
        return u.getCredits();
    }
}
