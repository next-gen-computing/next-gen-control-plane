package com.nextgen.security;

import com.nextgen.controlplane.EnvConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * On-disk layout for key material, and the best-effort file permission hardening that goes with it.
 *
 * <p>Layout under {@code ${NEXTGEN_PKI_DIR}} (default {@code ~/.nextgen/pki}):
 * <pre>
 *   ca.crt        CA certificate  (public)
 *   ca.key        CA private key  (SECRET — the only true secret on the control plane)
 *   server.crt    server leaf certificate
 *   server.key    server private key (SECRET)
 *   serial.txt    monotonic serial counter
 *   index.txt     append-only ledger of issued certificates
 *   revoked.txt   serial denylist
 * </pre>
 *
 * <p>None of these files is ever logged, and the directory is gitignored.
 */
public final class PkiPaths {
    private static final Logger LOG = LoggerFactory.getLogger(PkiPaths.class);

    /**
     * 1 when owner-only permissions were successfully applied, 0 when the filesystem could not
     * express them. Exported as a gauge so the degradation is observable rather than invisible.
     */
    static final AtomicInteger PERMISSIONS_ENFORCED = new AtomicInteger(-1);

    private final Path directory;

    public PkiPaths(Path directory) {
        this.directory = directory;
    }

    /** Resolves the PKI directory from {@code NEXTGEN_PKI_DIR}, defaulting under the user's home. */
    public static PkiPaths fromEnvironment() {
        String configured = EnvConfig.stringValue("NEXTGEN_PKI_DIR",
                Paths.get(System.getProperty("user.home", "."), ".nextgen", "pki").toString());
        return new PkiPaths(Paths.get(configured));
    }

    public Path directory()      { return directory; }
    public Path caCertificate()  { return directory.resolve("ca.crt"); }
    public Path caKey()          { return directory.resolve("ca.key"); }
    public Path serverCertificate() { return directory.resolve("server.crt"); }
    public Path serverKey()      { return directory.resolve("server.key"); }
    public Path serialFile()     { return directory.resolve("serial.txt"); }
    public Path indexFile()      { return directory.resolve("index.txt"); }
    public Path revokedFile()    { return directory.resolve("revoked.txt"); }

    /**
     * Creates the directory if absent and restricts it to the owner.
     *
     * <p>The directory is hardened <b>before</b> anything is written into it. On Windows the
     * create-then-ACL sequence has an unavoidable race for individual files (Java offers no atomic
     * create-with-ACL), so tightening the parent first is the meaningful mitigation.
     */
    public void ensureDirectory() throws IOException {
        if (!Files.exists(directory)) {
            Files.createDirectories(directory);
        }
        restrictToOwner(directory);
    }

    /**
     * Writes a secret file with owner-only permissions.
     *
     * <p>On POSIX the file is created with the correct mode atomically. Creating it and then
     * chmod-ing would leave a window in which the private key is world-readable.
     */
    public static void writeSecret(Path file, byte[] content) throws IOException {
        Files.deleteIfExists(file);
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-------")));
            Files.write(file, content);
        } else {
            Files.write(file, content);
            restrictToOwner(file);
        }
    }

    /**
     * Restricts a path to its owner, as far as the filesystem allows.
     *
     * <p><b>Best effort, never fatal.</b> {@code Files.setPosixFilePermissions} throws
     * {@link UnsupportedOperationException} on Windows, and a directory on exFAT supports neither
     * POSIX nor ACL views. Refusing to start in those cases would make Windows development
     * impossible, so this logs a warning and records the failure in
     * {@code controlplane_pki_permissions_enforced} instead.
     */
    public static void restrictToOwner(Path path) {
        Set<String> views = path.getFileSystem().supportedFileAttributeViews();
        try {
            if (views.contains("posix")) {
                Files.setPosixFilePermissions(path, Files.isDirectory(path)
                        ? PosixFilePermissions.fromString("rwx------")
                        : PosixFilePermissions.fromString("rw-------"));
                PERMISSIONS_ENFORCED.set(1);
                return;
            }
            if (views.contains("acl")) {
                AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
                if (acl != null) {
                    UserPrincipal owner = Files.getOwner(path);
                    acl.setAcl(List.of(AclEntry.newBuilder()
                            .setType(AclEntryType.ALLOW)
                            .setPrincipal(owner)
                            .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                            .setFlags(Files.isDirectory(path)
                                    ? EnumSet.of(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                                    : EnumSet.noneOf(AclEntryFlag.class))
                            .build()));
                    PERMISSIONS_ENFORCED.set(1);
                    return;
                }
            }
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            LOG.warn("SECURITY: could not restrict permissions on {} ({}); key material is readable "
                    + "by other users of this machine", path, e.toString());
            PERMISSIONS_ENFORCED.set(0);
            return;
        }
        LOG.warn("SECURITY: filesystem at {} supports neither POSIX nor ACL permissions; "
                + "key material is readable by other users of this machine", path);
        PERMISSIONS_ENFORCED.set(0);
    }

    /** -1 when nothing has been hardened yet, 0 when hardening failed, 1 when it succeeded. */
    public static int permissionsEnforced() {
        return PERMISSIONS_ENFORCED.get();
    }
}
