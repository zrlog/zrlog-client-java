package com.zrlog.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Dotenv {

    private Dotenv() { }

    static Map<String, String> load(Path path) {
        if (!Files.exists(path)) return Map.of();
        if (!Files.isRegularFile(path)) {
            throw new ApiException(".env must be a regular file: " + path, 3, null, null);
        }
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            Map<String, String> values = new LinkedHashMap<>();
            for (int index = 0; index < lines.size(); index++) {
                parseLine(path, index + 1, lines.get(index), values);
            }
            return Map.copyOf(values);
        } catch (IOException e) {
            throw new ApiException("Unable to read .env: " + e.getMessage(), 3, e);
        }
    }

    private static void parseLine(Path path, int lineNumber, String rawLine, Map<String, String> values) {
        String line = rawLine.trim();
        if (line.isEmpty() || line.startsWith("#")) return;
        if (line.startsWith("export ")) line = line.substring("export ".length()).trim();

        int separator = line.indexOf('=');
        if (separator < 1) throw invalid(path, lineNumber);
        String key = line.substring(0, separator).trim();
        if (!key.matches("[A-Za-z_][A-Za-z0-9_]*")) throw invalid(path, lineNumber);
        values.put(key, parseValue(path, lineNumber, line.substring(separator + 1).trim()));
    }

    private static String parseValue(Path path, int lineNumber, String value) {
        if (value.isEmpty()) return "";
        char quote = value.charAt(0);
        if (quote == '\'' || quote == '"') {
            if (value.length() < 2 || value.charAt(value.length() - 1) != quote) {
                throw invalid(path, lineNumber);
            }
            String inner = value.substring(1, value.length() - 1);
            return quote == '\'' ? inner : unescape(path, lineNumber, inner);
        }
        for (int index = 1; index < value.length(); index++) {
            if (value.charAt(index) == '#' && Character.isWhitespace(value.charAt(index - 1))) {
                return value.substring(0, index).trim();
            }
        }
        return value;
    }

    private static String unescape(Path path, int lineNumber, String value) {
        StringBuilder result = new StringBuilder(value.length());
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (!escaped && current == '\\') {
                escaped = true;
                continue;
            }
            if (escaped) {
                current = switch (current) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case '\\' -> '\\';
                    case '"' -> '"';
                    default -> throw invalid(path, lineNumber);
                };
                escaped = false;
            }
            result.append(current);
        }
        if (escaped) throw invalid(path, lineNumber);
        return result.toString();
    }

    private static ApiException invalid(Path path, int lineNumber) {
        return new ApiException("Invalid .env entry at " + path + ":" + lineNumber, 3, null, null);
    }
}
