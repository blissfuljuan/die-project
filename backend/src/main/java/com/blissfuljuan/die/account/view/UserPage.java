package com.blissfuljuan.die.account.view;

import java.util.List;

public record UserPage(List<UserView> items, int page, int size, long total) {}
