package com.zrlog.client.auth;

/** Stored only in the owner's credential file; never emitted by CLI output. */
public record OAuthTokens(String issuer, String accessToken, String refreshToken, long expiresAt, String scope) { }
