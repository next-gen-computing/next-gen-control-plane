package com.nextgen.desktop.ui.server.dto;

import com.nextgen.desktop.ui.account.Account;

import java.util.List;

public record AccountListDto(List<AccountDto> accounts) {
    public static AccountListDto from(List<Account> accounts) {
        return new AccountListDto(accounts.stream().map(AccountDto::from).toList());
    }
}
