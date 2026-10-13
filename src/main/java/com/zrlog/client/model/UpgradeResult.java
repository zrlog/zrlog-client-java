package com.zrlog.client.model;

/** The server's upgrade processing result, before separate restart verification. */
public record UpgradeResult(boolean finish, String message) { }
