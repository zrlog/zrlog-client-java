package com.zrlog.client.content;

import com.zrlog.client.ApiException;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ContentPolicy {

    private static final Set<String> FIELD_TYPES = Set.of("string", "boolean", "list");

    private final Path contentRoot;
    private final Set<String> categories;
    private final Set<String> allowedFields;
    private final Map<String, String> requiredFields;
    private final Map<String, Set<String>> styleProfiles;
    private final List<String> forbiddenPhrases;
    private final boolean forbidLevelOneHeading;
    private final Publication publication;

    private ContentPolicy(Path contentRoot, Set<String> categories, Set<String> allowedFields,
                          Map<String, String> requiredFields, Map<String, Set<String>> styleProfiles,
                          List<String> forbiddenPhrases, boolean forbidLevelOneHeading, Publication publication) {
        this.contentRoot = contentRoot;
        this.categories = categories;
        this.allowedFields = allowedFields;
        this.requiredFields = requiredFields;
        this.styleProfiles = styleProfiles;
        this.forbiddenPhrases = forbiddenPhrases;
        this.forbidLevelOneHeading = forbidLevelOneHeading;
        this.publication = publication;
    }

    public static ContentPolicy load(Path path) {
        Path absolutePolicy = path.toAbsolutePath().normalize();
        Map<?, ?> policy = object(ContentFiles.loadYaml(absolutePolicy), absolutePolicy, "policy");
        if (integer(policy.get("schemaVersion"), absolutePolicy, "schemaVersion") != 1) {
            throw invalid(absolutePolicy, "schemaVersion must be 1");
        }
        Path policyDirectory = absolutePolicy.getParent();
        Path contentRoot = policyDirectory.resolve(string(policy.get("contentRoot"), absolutePolicy, "contentRoot"))
                .normalize();
        if (!Files.isDirectory(contentRoot)) throw invalid(absolutePolicy, "contentRoot must be an existing directory");
        Path categoriesFile = policyDirectory
                .resolve(string(policy.get("categoriesFile"), absolutePolicy, "categoriesFile")).normalize();
        Set<String> categories = new LinkedHashSet<>();
        for (Map<String, String> category : ContentFiles.loadCategories(categoriesFile)) {
            categories.add(category.get("alias"));
        }

        Set<String> allowedFields = strings(policy.get("allowedFields"), absolutePolicy, "allowedFields", false);
        if (!allowedFields.containsAll(Set.of("title", "alias", "category"))) {
            throw invalid(absolutePolicy, "allowedFields must include title, alias, and category");
        }
        Map<?, ?> rawRequired = object(policy.get("requiredFields"), absolutePolicy, "requiredFields");
        Map<String, String> requiredFields = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawRequired.entrySet()) {
            String field = mapKey(entry.getKey(), absolutePolicy, "requiredFields");
            String type = string(entry.getValue(), absolutePolicy, "requiredFields." + field);
            if (!FIELD_TYPES.contains(type)) throw invalid(absolutePolicy, "unsupported field type " + type);
            requiredFields.put(field, type);
        }
        if (!allowedFields.containsAll(requiredFields.keySet())) {
            throw invalid(absolutePolicy, "requiredFields must also be present in allowedFields");
        }

        Map<?, ?> rawProfiles = object(policy.get("styleProfiles"), absolutePolicy, "styleProfiles");
        Map<String, Set<String>> styleProfiles = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawProfiles.entrySet()) {
            String profile = mapKey(entry.getKey(), absolutePolicy, "styleProfiles");
            Set<String> profileCategories = strings(entry.getValue(), absolutePolicy,
                    "styleProfiles." + profile, true);
            if (!categories.containsAll(profileCategories)) {
                throw invalid(absolutePolicy, "style profile " + profile + " references an unknown category");
            }
            styleProfiles.put(profile, profileCategories);
        }

        List<String> forbiddenPhrases = new ArrayList<>(strings(policy.get("forbiddenPhrases"), absolutePolicy,
                "forbiddenPhrases", true));
        boolean forbidHeading = bool(policy.get("forbidLevelOneHeading"), absolutePolicy,
                "forbidLevelOneHeading");
        Map<?, ?> rawPublication = object(policy.get("publication"), absolutePolicy, "publication");
        Publication publication = new Publication(
                string(rawPublication.get("readyField"), absolutePolicy, "publication.readyField"),
                string(rawPublication.get("reviewedField"), absolutePolicy, "publication.reviewedField"),
                string(rawPublication.get("verifiedAtField"), absolutePolicy, "publication.verifiedAtField"),
                string(rawPublication.get("sourcesField"), absolutePolicy, "publication.sourcesField"),
                string(rawPublication.get("privacyField"), absolutePolicy, "publication.privacyField"));
        validatePolicyFields(absolutePolicy, allowedFields, requiredFields, publication);
        return new ContentPolicy(contentRoot, Set.copyOf(categories), Set.copyOf(allowedFields),
                Map.copyOf(requiredFields), Map.copyOf(styleProfiles), List.copyOf(forbiddenPhrases),
                forbidHeading, publication);
    }

    private static void validatePolicyFields(Path path, Set<String> allowedFields,
                                             Map<String, String> requiredFields, Publication publication) {
        Map<String, String> publicationTypes = new LinkedHashMap<>();
        publicationTypes.put(publication.readyField(), "boolean");
        publicationTypes.put(publication.reviewedField(), "boolean");
        publicationTypes.put(publication.verifiedAtField(), "string");
        publicationTypes.put(publication.sourcesField(), "list");
        if (publicationTypes.size() != 4) throw invalid(path, "publication field names must be distinct");
        for (Map.Entry<String, String> field : publicationTypes.entrySet()) {
            if (!allowedFields.contains(field.getKey()) || !field.getValue().equals(requiredFields.get(field.getKey()))) {
                throw invalid(path, "publication field " + field.getKey()
                        + " must be allowed and required as " + field.getValue());
            }
        }
        if (!allowedFields.contains(publication.privacyField())) {
            throw invalid(path, "publication privacyField must be present in allowedFields");
        }
        if (!"string".equals(requiredFields.get("styleProfile"))) {
            throw invalid(path, "styleProfile must be required as string");
        }
    }

    public void validate(Path file, ContentFiles.ArticleDocument document) {
        Path absoluteFile = file.toAbsolutePath().normalize();
        Map<String, Object> metadata = document.metadata();
        for (String field : metadata.keySet()) {
            if (!allowedFields.contains(field)) throw invalid(file, "unsupported front matter field " + field);
        }
        for (Map.Entry<String, String> required : requiredFields.entrySet()) {
            if (!metadata.containsKey(required.getKey())) {
                throw invalid(file, "front matter field " + required.getKey() + " is required by policy");
            }
            if (!matchesType(metadata.get(required.getKey()), required.getValue())) {
                throw invalid(file, "front matter field " + required.getKey() + " must be a " + required.getValue());
            }
        }

        if (!absoluteFile.startsWith(contentRoot)) throw invalid(file, "must be inside " + contentRoot);
        Path relative = contentRoot.relativize(absoluteFile);
        if (relative.getNameCount() != 2) {
            throw invalid(file, "must use <category>/<alias>.md below the configured content root");
        }
        String category = document.article().category();
        String alias = document.article().alias();
        if (!relative.getName(0).toString().equals(category)
                || !relative.getFileName().toString().equals(alias + ".md")) {
            throw invalid(file, "path must match front matter category and alias");
        }
        if (!categories.contains(category)) throw invalid(file, "unknown category " + category);

        Object rawProfile = metadata.get("styleProfile");
        if (!(rawProfile instanceof String profile) || !styleProfiles.containsKey(profile)) {
            throw invalid(file, "styleProfile is not allowed by policy");
        }
        Set<String> profileCategories = styleProfiles.get(profile);
        if (!profileCategories.isEmpty() && !profileCategories.contains(category)) {
            throw invalid(file, "styleProfile " + profile + " is not allowed in category " + category);
        }
        for (String phrase : forbiddenPhrases) {
            if (document.article().markdown().contains(phrase)) {
                throw invalid(file, "forbidden phrase found: " + phrase);
            }
        }
        if (forbidLevelOneHeading && document.article().markdown().matches("(?s).*(?m:^#\\s+).*$")) {
            throw invalid(file, "body must not repeat the title as a level-one heading");
        }
        validatePublication(file, metadata);
    }

    private void validatePublication(Path file, Map<String, Object> metadata) {
        if (!Boolean.TRUE.equals(metadata.get(publication.readyField()))) return;
        if (!Boolean.TRUE.equals(metadata.get(publication.reviewedField()))) {
            throw invalid(file, "publish-ready article requires " + publication.reviewedField() + "=true");
        }
        if (Boolean.TRUE.equals(metadata.get(publication.privacyField()))) {
            throw invalid(file, "publish-ready article must not be private");
        }
        Object rawDate = metadata.get(publication.verifiedAtField());
        if (!(rawDate instanceof String date) || date.isBlank()) {
            throw invalid(file, "publish-ready article requires " + publication.verifiedAtField());
        }
        try {
            LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            throw invalid(file, publication.verifiedAtField() + " must use a valid YYYY-MM-DD date");
        }
        Object rawSources = metadata.get(publication.sourcesField());
        if (!(rawSources instanceof List<?> sources) || sources.isEmpty()) {
            throw invalid(file, "publish-ready article requires at least one source");
        }
        for (int index = 0; index < sources.size(); index++) validateSource(file, sources.get(index), index + 1);
    }

    private static void validateSource(Path file, Object value, int index) {
        if (!(value instanceof Map<?, ?> source)) throw invalid(file, "source " + index + " must be an object");
        Object rawName = source.get("name");
        Object rawUrl = source.get("url");
        if (!(rawName instanceof String name) || name.isBlank()
                || !(rawUrl instanceof String url) || url.isBlank()) {
            throw invalid(file, "source " + index + " requires name and url");
        }
        try {
            URI uri = URI.create(url);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
                throw invalid(file, "source " + index + " URL must be HTTPS without credentials");
            }
        } catch (IllegalArgumentException e) {
            throw invalid(file, "source " + index + " URL must be absolute HTTPS");
        }
    }

    private static boolean matchesType(Object value, String type) {
        return switch (type) {
            case "string" -> value instanceof String;
            case "boolean" -> value instanceof Boolean;
            case "list" -> value instanceof List<?>;
            default -> false;
        };
    }

    private static Map<?, ?> object(Object value, Path path, String field) {
        if (!(value instanceof Map<?, ?> result)) throw invalid(path, field + " must be an object");
        return result;
    }

    private static Set<String> strings(Object value, Path path, String field, boolean allowEmpty) {
        if (!(value instanceof List<?> list) || (!allowEmpty && list.isEmpty())) {
            throw invalid(path, field + " must be " + (allowEmpty ? "a list" : "a non-empty list"));
        }
        Set<String> result = new LinkedHashSet<>();
        for (Object item : list) {
            String text = string(item, path, field);
            if (!result.add(text)) throw invalid(path, field + " contains duplicate value " + text);
        }
        return result;
    }

    private static String mapKey(Object value, Path path, String field) {
        if (!(value instanceof String key) || key.isBlank()) throw invalid(path, field + " keys must be strings");
        return key;
    }

    private static String string(Object value, Path path, String field) {
        if (!(value instanceof String text) || text.isBlank()) throw invalid(path, field + " must be a string");
        return text;
    }

    private static int integer(Object value, Path path, String field) {
        if (!(value instanceof Number number)) throw invalid(path, field + " must be an integer");
        return number.intValue();
    }

    private static boolean bool(Object value, Path path, String field) {
        if (!(value instanceof Boolean result)) throw invalid(path, field + " must be a boolean");
        return result;
    }

    private static ApiException invalid(Path path, String message) {
        return new ApiException(path + ": " + message, 3, null, null);
    }

    private record Publication(String readyField, String reviewedField, String verifiedAtField,
                               String sourcesField, String privacyField) { }
}
