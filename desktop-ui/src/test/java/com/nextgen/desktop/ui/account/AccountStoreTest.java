package com.nextgen.desktop.ui.account;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountStoreTest {

    private static Account passwordAccount(String id, String email) {
        return new Account(id, "Ada", email, "hash", "salt", "recoveryHash", "recoverySalt",
                null, null, null, 1000L, 1000L);
    }

    private static Account githubAccount(String id, String login) {
        return new Account(id, login, null, null, null, null, null,
                login, "https://avatars.example/" + login, "gho_token", 1000L, 1000L);
    }

    @Test
    void noSavedFileMeansNoAccountsAndNoCurrentAccount(@TempDir Path dir) {
        AccountStore store = new AccountStore(dir);

        assertTrue(store.listAccounts().isEmpty());
        assertTrue(store.currentAccount().isEmpty());
    }

    @Test
    void savingAnAccountMakesItFindableAndCurrentByDefault(@TempDir Path dir) {
        AccountStore store = new AccountStore(dir);
        Account account = passwordAccount("acc-1", "ada@example.com");

        store.save(account, true);

        assertEquals(Optional.of(account), store.findById("acc-1"));
        assertEquals(Optional.of(account), store.findByEmail("ADA@EXAMPLE.COM"));
        assertEquals(Optional.of(account), store.currentAccount());
    }

    @Test
    void savingWithMakeCurrentFalseDoesNotChangeWhoIsSignedIn(@TempDir Path dir) {
        AccountStore store = new AccountStore(dir);
        store.save(passwordAccount("acc-1", "ada@example.com"), true);
        store.save(passwordAccount("acc-2", "grace@example.com"), false);

        assertEquals("acc-1", store.currentAccount().orElseThrow().id());
        assertEquals(2, store.listAccounts().size());
    }

    @Test
    void findByGitHubLoginWorks(@TempDir Path dir) {
        AccountStore store = new AccountStore(dir);
        store.save(githubAccount("acc-1", "octocat"), true);

        assertTrue(store.findByGitHubLogin("octocat").isPresent());
        assertTrue(store.findByGitHubLogin("someone-else").isEmpty());
    }

    @Test
    void savingTwiceWithTheSameIdUpsertsRatherThanDuplicates(@TempDir Path dir) {
        AccountStore store = new AccountStore(dir);
        store.save(passwordAccount("acc-1", "ada@example.com"), true);
        store.save(passwordAccount("acc-1", "ada@example.com").withLastLogin(5000L), true);

        List<Account> all = store.listAccounts();
        assertEquals(1, all.size());
        assertEquals(5000L, all.get(0).lastLoginAtEpochMillis());
    }

    @Test
    void signOutClearsCurrentAccountButKeepsTheAccountData(@TempDir Path dir) {
        AccountStore store = new AccountStore(dir);
        store.save(passwordAccount("acc-1", "ada@example.com"), true);

        store.signOut();

        assertTrue(store.currentAccount().isEmpty());
        assertFalse(store.listAccounts().isEmpty());
    }

    @Test
    void setCurrentAccountSwitchesWithoutRecreatingTheAccount(@TempDir Path dir) {
        AccountStore store = new AccountStore(dir);
        store.save(passwordAccount("acc-1", "ada@example.com"), true);
        store.save(passwordAccount("acc-2", "grace@example.com"), false);

        store.setCurrentAccount("acc-2");

        assertEquals("acc-2", store.currentAccount().orElseThrow().id());
        assertEquals(2, store.listAccounts().size());
    }

    @Test
    void aNewStoreInstanceOverTheSameDirectoryReloadsEverythingFromDisk(@TempDir Path dir) {
        new AccountStore(dir).save(passwordAccount("acc-1", "ada@example.com"), true);

        AccountStore reopened = new AccountStore(dir);

        assertEquals("acc-1", reopened.currentAccount().orElseThrow().id());
    }

    @Test
    void aFileWithAFieldFromAnOlderSchemaStillLoadsRatherThanBeingTreatedAsCorrupt(@TempDir Path dir)
            throws Exception {
        Path file = dir.resolve("accounts.json");
        java.nio.file.Files.writeString(file, """
                {
                  "accounts": [
                    {"id":"acc-1","displayName":"Ada","email":"ada@example.com","passwordHash":"h",
                     "passwordSalt":"s","createdAtEpochMillis":1000,"lastLoginAtEpochMillis":1000,
                     "thisFieldNoLongerExists":"legacy value"}
                  ],
                  "currentAccountId": "acc-1"
                }
                """);

        AccountStore store = new AccountStore(dir);

        assertEquals("acc-1", store.currentAccount().orElseThrow().id());
    }
}
