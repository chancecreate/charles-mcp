package io.github.charlesmcp.agent.bridge;

import io.github.charlesmcp.agent.json.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Writes the per-process discovery file ({@code host}, {@code port}, {@code token}) that the sidecar
 * reads. The token is the only credential for the bridge, so the file is restricted to the current
 * user where the filesystem allows it (owner-only ACL on Windows, 0600 on POSIX).
 */
public final class Discovery {
    private static final Logger LOG = Logger.getLogger("charles-mcp");

    private Discovery() {
    }

    public static void write(Path file, Map<String, Object> content) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "bridge-", ".tmp");
        try {
            restrictToOwner(tmp);
            Files.write(tmp, Json.write(content).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictToOwner(file);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Deletes the file only if it still belongs to this process (matching token). */
    public static void deleteIfOwned(Path file, String token) {
        try {
            if (!Files.exists(file)) {
                return;
            }
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            Object parsed = Json.parse(text);
            if (parsed instanceof Map && token.equals(((Map<?, ?>) parsed).get("token"))) {
                Files.deleteIfExists(file);
            }
        } catch (IOException | RuntimeException e) {
            // best effort on shutdown
        }
    }

    static void restrictToOwner(Path p) {
        try {
            AclFileAttributeView acl = Files.getFileAttributeView(p, AclFileAttributeView.class);
            if (acl != null) {
                UserPrincipal owner = acl.getOwner();
                AclEntry entry = AclEntry.newBuilder()
                        .setType(AclEntryType.ALLOW)
                        .setPrincipal(owner)
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                        .build();
                acl.setAcl(Collections.singletonList(entry));
                return;
            }
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"));
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            LOG.warning("charles-mcp: could not restrict discovery file permissions: " + e.getClass().getSimpleName());
        }
    }
}
