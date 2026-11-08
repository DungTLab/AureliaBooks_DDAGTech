package com.ddagtech.aureliabooks.runtimeconfig;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.Clock;
import java.util.*;
import static com.ddagtech.aureliabooks.runtimeconfig.ConfigPolicy.*;

/** Bounded, environment-bound snapshot protocol. No secret lookup, SSH or activation. */
public final class StagingConfigEnvelope {
    @FunctionalInterface public interface StageSink {
        void stage(String generation, String revision, Map<String, String> values, Collection<String> groups, String role);
    }
    private static final int MAGIC = 0x41524331, MAX_FRAME = 131072;
    private final String environment, generation, revision;
    private final long issued, expires;
    private final Map<String, String> values;
    private final Set<String> groups;

    public StagingConfigEnvelope(String generation, String revision, long issued, long expires,
                                 Map<String, String> values, Collection<String> groups) {
        this("staging", generation, revision, issued, expires, values, groups);
    }
    public StagingConfigEnvelope(String environment, String generation, String revision, long issued, long expires,
                                 Map<String, String> values, Collection<String> groups) {
        check(environment != null && Set.of("staging", "production").contains(environment), "INVALID_TARGET_ENVIRONMENT");
        ConfigPolicy.generation(generation, revision);
        check(issued >= 0 && expires > issued && expires - issued <= 300, "INVALID_ENVELOPE_LIFETIME");
        ConfigPolicy.render(values, groups);
        if (groups.contains("r2")) r2Bucket(environment, values.get("R2_BUCKET"));
        check(new HashSet<>(groups).size() == groups.size(), "DUPLICATE_PROVIDER_GROUP");
        this.environment = environment; this.generation = generation; this.revision = revision;
        this.issued = issued; this.expires = expires;
        this.values = Map.copyOf(values); this.groups = Set.copyOf(groups);
    }
    @Override public String toString() { return "StagingConfigEnvelope[REDACTED]"; }
    public String generation() { return generation; }

    private static void string(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length); out.write(bytes);
    }
    private static String string(DataInputStream input, int limit) throws IOException {
        int length = input.readInt();
        check(length >= 0 && length <= limit, "INVALID_ENVELOPE_STRING_LENGTH");
        byte[] bytes = input.readNBytes(length);
        check(bytes.length == length, "TRUNCATED_ENVELOPE");
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) { throw new Rejected("INVALID_ENVELOPE_UTF8"); }
    }
    /** Bytes must use a private pipe, never argv, logs or retained artifacts. */
    public byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC); string(out, environment); string(out, "stage");
            string(out, generation); string(out, revision); out.writeLong(issued); out.writeLong(expires);
            out.writeInt(groups.size());
            for (String group : new TreeSet<>(groups)) string(out, group);
            out.writeInt(values.size());
            for (var entry : new TreeMap<>(values).entrySet()) { string(out, entry.getKey()); string(out, entry.getValue()); }
            out.flush();
            byte[] frame = bytes.toByteArray();
            check(frame.length <= MAX_FRAME, "ENVELOPE_TOO_LARGE");
            return frame;
        } catch (IOException e) { throw new Rejected("ENVELOPE_ENCODING_FAILED"); }
    }
    private static StagingConfigEnvelope decode(byte[] frame) {
        check(frame.length <= MAX_FRAME, "ENVELOPE_TOO_LARGE");
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(frame));
            check(input.readInt() == MAGIC, "INVALID_ENVELOPE_VERSION");
            String environment = string(input, 16);
            check(Set.of("staging", "production").contains(environment), "ENVELOPE_ENVIRONMENT_DENIED");
            check(string(input, 16).equals("stage"), "ENVELOPE_ACTION_DENIED");
            String generation = string(input, 32), revision = string(input, 40);
            long issued = input.readLong(), expires = input.readLong();
            int count = input.readInt(); check(count >= 0 && count <= SUPPORTED_GROUPS.size(), "INVALID_ENVELOPE_GROUP_COUNT");
            List<String> groups = new ArrayList<>();
            for (int i = 0; i < count; i++) groups.add(string(input, 16));
            check(new ArrayList<>(new TreeSet<>(groups)).equals(groups), "NONCANONICAL_ENVELOPE_GROUPS");
            count = input.readInt(); check(count >= 0 && count <= FIELDS.size() + FLAGS.size(), "INVALID_ENVELOPE_FIELD_COUNT");
            Map<String, String> values = new TreeMap<>(); String previous = "";
            for (int i = 0; i < count; i++) {
                String key = string(input, 64);
                check(previous.compareTo(key) < 0, "DUPLICATE_OR_UNSORTED_ENVELOPE_KEY"); previous = key;
                values.put(key, string(input, 16384));
            }
            check(input.read() == -1, "TRAILING_ENVELOPE_BYTES");
            return new StagingConfigEnvelope(environment, generation, revision, issued, expires, values, groups);
        } catch (IOException e) { throw new Rejected("INVALID_OR_TRUNCATED_ENVELOPE"); }
    }
    /**
     * Offline receiver contract only. Input must already be fully bounded in memory:
     * actual stdin deadline/SSH identity/revision attestation are not implemented.
     * Stage only, never activate. The expected revision is trusted caller metadata.
     */
    public static StagingConfigEnvelope validate(byte[] frame, String expectedRevision, Clock clock, String trustedRole) {
        return validate(frame, "staging", expectedRevision, clock, trustedRole);
    }
    public static StagingConfigEnvelope validate(byte[] frame, String expectedEnvironment, String expectedRevision, Clock clock, String trustedRole) {
        check("receiver".equals(trustedRole), "ROLE_DENIED");
        check(frame != null && clock != null, "INVALID_ENVELOPE_INPUT");
        StagingConfigEnvelope envelope = decode(frame);
        check(envelope.environment.equals(expectedEnvironment), "ENVELOPE_ENVIRONMENT_CONFLICT");
        long now = clock.instant().getEpochSecond();
        check(now >= envelope.issued && now < envelope.expires, "ENVELOPE_EXPIRED_OR_FUTURE");
        check(envelope.revision.equals(expectedRevision), "ENVELOPE_REVISION_CONFLICT");
        return envelope;
    }
    public static String receive(byte[] frame, String expectedRevision, Clock clock,
                                 String trustedRole, StageSink store) {
        return receive(frame, "staging", expectedRevision, clock, trustedRole, store);
    }
    public static String receive(byte[] frame, String expectedEnvironment, String expectedRevision, Clock clock,
                                 String trustedRole, StageSink store) {
        check(store != null, "INVALID_ENVELOPE_INPUT");
        StagingConfigEnvelope envelope = validate(frame, expectedEnvironment, expectedRevision, clock, trustedRole);
        store.stage(envelope.generation, envelope.revision, envelope.values, envelope.groups, "receiver");
        return envelope.generation;
    }
}
