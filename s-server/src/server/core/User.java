package server.core;

public class User {
    private final int id;
    private final String username;
    private int credits;

    public User(int id, String username, int initialCredits) {
        this.id = id;
        this.username = username;
        this.credits = initialCredits;
    }

    public int getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public int getCredits() {
        return credits;
    }

    public void setCredits(int credits) {
        this.credits = credits;
    }
}
