package server.core;

import java.util.UUID;

public class User {
    private final String id;
    private final String username;
    private int credits;

    public User(String username, int startingCredits) {
        this.id = UUID.randomUUID().toString();
        this.username = username;
        this.credits = startingCredits;
    }

    public String getId() { return id; }
    public String getUsername() { return username; }
    public int getCredits() { return credits; }
    public void addCredits(int amount) { this.credits += amount; }
    public void setCredits(int credits) { this.credits = credits; }
}
