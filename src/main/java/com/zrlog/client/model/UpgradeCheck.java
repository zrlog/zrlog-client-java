package com.zrlog.client.model;

import com.google.gson.JsonObject;

/** Site upgrade readiness; version and backup evidence retain the server's fields. */
public record UpgradeCheck(boolean upgrade, boolean onlineUpgradable, JsonObject version,
                           String disableUpgradeReason, JsonObject backupProtection,
                           Boolean dockerMode, Boolean systemServiceMode, Boolean faasMode,
                           Boolean warMode, Boolean nativeImageMode) { }
