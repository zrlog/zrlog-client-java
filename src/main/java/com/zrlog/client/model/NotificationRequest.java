package com.zrlog.client.model;

public record NotificationRequest(String title, String description, String taskKey, String source) { }
