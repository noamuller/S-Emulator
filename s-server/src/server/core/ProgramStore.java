package server.core;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ProgramStore {

    public static final class ProgramEntry {
        private final String id;
        private final String name;
        private final String xml;        // phase 1: keep raw XML; phase 2: keep parsed Program
        private final Instant uploadedAt;

        ProgramEntry(String id, String name, String xml, Instant uploadedAt) {
            this.id = id; this.name = name; this.xml = xml; this.uploadedAt = uploadedAt;
        }
        public String getId() { return id; }
        public String getName() { return name; }
        public String getXml() { return xml; }
        public Instant getUploadedAt() { return uploadedAt; }
    }

    private static final ProgramStore INSTANCE = new ProgramStore();
    public static ProgramStore get() { return INSTANCE; }

    // userId -> list of programs
    private final Map<String, List<ProgramEntry>> byUser = new ConcurrentHashMap<>();

    private ProgramStore() {}

    public synchronized ProgramEntry add(String userId, String name, String xml) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId required");
        if (xml == null || xml.isBlank()) throw new IllegalArgumentException("file is empty");

        String id = UUID.randomUUID().toString();
        ProgramEntry e = new ProgramEntry(id, (name == null || name.isBlank() ? "program-" + id.substring(0, 6) : name),
                xml, Instant.now());
        byUser.computeIfAbsent(userId, k -> new ArrayList<>()).add(e);
        return e;
    }

    public List<ProgramEntry> list(String userId) {
        return byUser.getOrDefault(userId, Collections.emptyList());
    }
}
