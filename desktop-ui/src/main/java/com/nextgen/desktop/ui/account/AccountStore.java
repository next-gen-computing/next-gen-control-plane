package com.nextgen.desktop.ui.account;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextgen.controlplane.EnvConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Persists local device accounts to one plain JSON file, matching {@link
 * com.nextgen.desktop.ui.profile.DesktopProfileStore}'s idiom exactly — including the same "ignore
 * unknown fields, never let a schema change turn into 'lost all your accounts'" discipline. This is a
 * device-local account store, not a synced one: what's in this file is everything that exists of an
 * account created here. See {@link Account}'s own Javadoc for the full scope statement.
 */
public class AccountStore {
    private static final Logger LOG = LoggerFactory.getLogger(AccountStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private record StoreFile(List<Account> accounts, String currentAccountId) {
        static final StoreFile EMPTY = new StoreFile(List.of(), null);
    }

    private final Path file;

    public AccountStore() {
        this(defaultDataDir());
    }

    AccountStore(Path dataDir) {
        this.file = dataDir.resolve("accounts.json");
    }

    private static Path defaultDataDir() {
        return Paths.get(EnvConfig.stringValue("NEXTGEN_DESKTOP_DATA_DIR",
                Paths.get(System.getProperty("user.home", "."), ".nextgen", "desktop").toString()));
    }

    private StoreFile readFile() {
        if (!Files.exists(file)) {
            return StoreFile.EMPTY;
        }
        try {
            StoreFile loaded = MAPPER.readValue(file.toFile(), StoreFile.class);
            return loaded == null ? StoreFile.EMPTY : loaded;
        } catch (IOException e) {
            LOG.warn("Could not read accounts at {} — treating as no accounts: {}", file, e.getMessage());
            return StoreFile.EMPTY;
        }
    }

    private void writeFile(StoreFile store) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(store));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.warn("Could not save accounts to {}: {}", file, e.getMessage());
        }
    }

    public List<Account> listAccounts() {
        return readFile().accounts();
    }

    public Optional<Account> findById(String id) {
        return listAccounts().stream().filter(a -> a.id().equals(id)).findFirst();
    }

    public Optional<Account> findByEmail(String email) {
        String normalized = email.trim().toLowerCase();
        return listAccounts().stream()
                .filter(a -> a.email() != null && a.email().equalsIgnoreCase(normalized))
                .findFirst();
    }

    public Optional<Account> findByGitHubLogin(String githubLogin) {
        return listAccounts().stream()
                .filter(a -> githubLogin.equals(a.githubLogin()))
                .findFirst();
    }

    /** Upserts by {@code id} and, unless {@code makeCurrent} is false, marks this account as the one
     * signed in on this device. */
    public synchronized void save(Account account, boolean makeCurrent) {
        StoreFile current = readFile();
        List<Account> next = new ArrayList<>(current.accounts().stream()
                .filter(a -> !a.id().equals(account.id()))
                .toList());
        next.add(account);
        String currentId = makeCurrent ? account.id() : current.currentAccountId();
        writeFile(new StoreFile(next, currentId));
        LOG.info("Saved account {} ({})", account.id(),
                account.isGitHubAccount() ? "github:" + account.githubLogin() : account.email());
    }

    public Optional<Account> currentAccount() {
        StoreFile store = readFile();
        if (store.currentAccountId() == null) {
            return Optional.empty();
        }
        return store.accounts().stream().filter(a -> a.id().equals(store.currentAccountId())).findFirst();
    }

    /** Marks {@code accountId} as the one signed in on this device — used by "switch account" to
     * re-activate a previously-used local account without recreating it. */
    public synchronized void setCurrentAccount(String accountId) {
        StoreFile current = readFile();
        writeFile(new StoreFile(current.accounts(), accountId));
    }

    /** Signs out on this device only — the account itself, and every other account already saved
     * here, is untouched, so "switch account" has something to switch back to. */
    public synchronized void signOut() {
        StoreFile current = readFile();
        writeFile(new StoreFile(current.accounts(), null));
        LOG.info("Signed out on this device");
    }
}
