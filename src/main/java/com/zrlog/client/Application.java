package com.zrlog.client;

import com.zrlog.client.auth.CredentialStore;
import com.zrlog.client.auth.OAuthLogin;
import com.zrlog.client.auth.OAuthTokens;
import com.zrlog.client.content.ArticleSource;
import com.zrlog.client.content.ContentFiles;
import com.zrlog.client.content.ContentPolicy;
import com.zrlog.client.content.ContentService;
import com.zrlog.client.model.Article;
import com.zrlog.client.model.Category;
import com.zrlog.client.model.Theme;
import com.zrlog.client.update.UpdateService;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "zrlogctl", mixinStandardHelpOptions = true, versionProvider = BuildInfo.class,
        description = "Non-graphical ZrLog administration for automation and AI agents.",
        subcommands = {Application.ArticleGroup.class, Application.CategoryGroup.class,
                Application.MediaGroup.class, Application.ThemeGroup.class,
                Application.ContentGroup.class, Application.UpdateGroup.class,
                Application.Login.class, Application.Logout.class, Application.NotificationGroup.class})
public class Application implements Runnable {

    @Option(names = "--site", scope = CommandLine.ScopeType.INHERIT,
            description = "ZrLog base URL, including an optional context path")
    String site;

    @Option(names = "--token-file", scope = CommandLine.ScopeType.INHERIT,
            description = "Read a personal access token or legacy admin token from this file")
    Path tokenFile;

    @Option(names = "--token", scope = CommandLine.ScopeType.INHERIT,
            description = "Personal access token or legacy admin token (prefer login or --token-file)")
    String tokenValue;

    @Option(names = "--output", scope = CommandLine.ScopeType.INHERIT, defaultValue = "text",
            description = "Output format: ${COMPLETION-CANDIDATES}")
    Output output;

    @Option(names = "--timeout", scope = CommandLine.ScopeType.INHERIT, defaultValue = "30",
            description = "HTTP timeout in seconds")
    int timeout = 30;

    Map<String, String> environment = System.getenv();
    Path dotenvPath = Path.of(".env");
    private Map<String, String> dotenv;
    Path credentialDirectory;
    java.util.function.Consumer<java.net.URI> browser = OAuthLogin::openBrowser;

    enum Output { text, json }

    public static void main(String[] args) {
        int exitCode = commandLine(new Application()).execute(args);
        if (exitCode != 0) System.exit(exitCode);
    }

    static CommandLine commandLine(Application application) {
        CommandLine commandLine = new CommandLine(application);
        commandLine.setParameterExceptionHandler((error, args) -> {
            boolean json = application.output == Output.json || requestsJson(args);
            if (json) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("ok", false);
                details.put("message", error.getMessage());
                details.put("exitCode", 2);
                error.getCommandLine().getErr().println(JsonSupport.GSON.toJson(details));
            } else {
                error.getCommandLine().getErr().println(error.getMessage());
                error.getCommandLine().getErr().println("Use 'zrlogctl --help' for usage.");
            }
            return 2;
        });
        commandLine.setExecutionExceptionHandler((error, command, parseResult) -> {
            Throwable cause = relevantCause(error);
            String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            Application root = (Application) command.getCommandSpec().root().userObject();
            if (root.output == Output.json) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("ok", false);
                details.put("message", message);
                details.put("exitCode", cause instanceof ApiException api ? api.exitCode() : 1);
                if (cause instanceof ApiException api && api.httpStatus() != null) details.put("httpStatus", api.httpStatus());
                if (cause instanceof ApiException api && api.apiError() != null) details.put("apiError", api.apiError());
                command.getErr().println(JsonSupport.GSON.toJson(details));
            } else command.getErr().println(message);
            return cause instanceof ApiException api ? api.exitCode() : 1;
        });
        return commandLine;
    }

    private static boolean requestsJson(String[] args) {
        for (int index = 0; index < args.length; index++) {
            if ("--output=json".equals(args[index])) return true;
            if ("--output".equals(args[index]) && index + 1 < args.length && "json".equals(args[index + 1])) return true;
        }
        return false;
    }

    @Override
    public void run() { new CommandLine(this).usage(System.out); }

    ZrLogApi api() {
        String resolvedSite = resolvedSite();
        String token = resolvedToken();
        if (resolvedSite == null || resolvedSite.isBlank()) {
            throw new ApiException("Set --site or ZRLOG_SITE_URL (environment or .env)", 3, null, null);
        }
        if (timeout <= 0) throw new ApiException("--timeout must be greater than zero", 3, null, null);
        try {
            boolean bearer = accessTokenSource();
            if (token == null || token.isBlank()) {
                OAuthLogin login = oauthLogin();
                OAuthTokens saved = credentialStore(login).update(current -> {
                    if (current == null) throw new ApiException("Run zrlogctl login --site <URL>, or supply a personal access token", 4, null, null);
                    return login.refresh(current);
                });
                token = saved.accessToken(); bearer = true;
            }
            ClientConfig detected = new ClientConfig(java.net.URI.create(resolvedSite), token.trim(), Duration.ofSeconds(timeout));
            ClientConfig config = new ClientConfig(detected.baseUri(), detected.token(), detected.timeout(), bearer || detected.bearer());
            return new ZrLogApi(new ZrLogHttpClient(config));
        } catch (IllegalArgumentException e) {
            throw new ApiException(e.getMessage(), 3, e);
        }
    }

    ContentService contentService() {
        ZrLogApi api = api();
        return new ContentService(api, apiSite());
    }

    private java.net.URI apiSite() {
        String resolvedSite = resolvedSite();
        return java.net.URI.create(resolvedSite.endsWith("/") ? resolvedSite.substring(0, resolvedSite.length() - 1) : resolvedSite);
    }

    private String resolvedSite() {
        return first(site, first(environment.get("ZRLOG_SITE_URL"), dotenv().get("ZRLOG_SITE_URL")));
    }

    private String resolvedToken() {
        if (tokenValue != null && tokenFile != null) {
            throw new ApiException("Use only one of --token and --token-file", 4, null, null);
        }
        if (tokenValue != null) return tokenValue;
        if (tokenFile != null) return readToken(tokenFile);
        return first(first(environment.get("ZRLOG_ACCESS_TOKEN"), environment.get("ZRLOG_ADMIN_TOKEN")),
                first(dotenv().get("ZRLOG_ACCESS_TOKEN"), dotenv().get("ZRLOG_ADMIN_TOKEN")));
    }

    private boolean accessTokenSource() {
        if (tokenValue != null || tokenFile != null) return false;
        if (environment.containsKey("ZRLOG_ACCESS_TOKEN")) return true;
        return !environment.containsKey("ZRLOG_ADMIN_TOKEN") && dotenv().containsKey("ZRLOG_ACCESS_TOKEN");
    }
    private OAuthLogin oauthLogin() {
        if (resolvedSite() == null || resolvedSite().isBlank()) throw new ApiException("Set --site or ZRLOG_SITE_URL", 3, null, null);
        if (timeout <= 0) throw new ApiException("--timeout must be greater than zero", 3, null, null);
        try { return new OAuthLogin(java.net.URI.create(resolvedSite()), Duration.ofSeconds(timeout)); }
        catch (IllegalArgumentException e) { throw new ApiException(e.getMessage(), 3, e); }
    }
    private CredentialStore credentialStore(OAuthLogin login) {
        Path directory = credentialDirectory != null ? credentialDirectory : Path.of(
                environment.getOrDefault("XDG_CONFIG_HOME", Path.of(System.getProperty("user.home"), ".config").toString()), "zrlog", "credentials");
        return new CredentialStore(directory, login.issuer());
    }

    @Command(name = "login", description = "Authorize zrlogctl through your browser")
    static class Login implements Callable<Integer> {
        @ParentCommand Application root;
        @Option(names = "--no-browser", description = "Print the authorization URL without opening a browser") boolean noBrowser;
        @Option(names = "--permissions", split = ",", description = "Request specific account action IDs; otherwise choose permissions in the browser") List<String> permissions;
        @Option(names = "--wait", defaultValue = "300", description = "Seconds to wait for browser authorization") int wait;
        public Integer call() {
            if (wait < 1 || wait > 1800) throw new ApiException("--wait must be between 1 and 1800 seconds", 3, null, null);
            if (permissions != null && (permissions.isEmpty() || permissions.stream().anyMatch(p -> !p.matches("[a-z_]+(?:\\.[a-z_]+)+"))))
                throw new ApiException("--permissions must contain account action IDs", 3, null, null);
            OAuthLogin login = root.oauthLogin();
            String scope = (permissions == null ? "account:inherit" : String.join(" ", permissions)) + " offline_access";
            OAuthTokens tokens = login.login(scope, Duration.ofSeconds(wait), uri -> {
                System.err.println("Open this URL to authorize zrlogctl:\n" + uri);
                if (!noBrowser) root.browser.accept(uri);
            });
            root.credentialStore(login).update(previous -> tokens);
            root.emit(Map.of("site", login.issuer(), "scope", tokens.scope()), "Signed in to " + login.issuer());
            return 0;
        }
    }
    @Command(name = "logout", description = "Revoke and remove the saved login for this site")
    static class Logout implements Callable<Integer> {
        @ParentCommand Application root;
        public Integer call() {
            OAuthLogin login = root.oauthLogin();
            root.credentialStore(login).update(current -> { if (current != null) login.revoke(current); return null; });
            root.emit(Map.of("site", login.issuer(), "signedOut", true), "Signed out of " + login.issuer());
            return 0;
        }
    }

    private Map<String, String> dotenv() {
        if (dotenv == null) dotenv = Dotenv.load(dotenvPath);
        return dotenv;
    }

    void emit(Object value, String text) {
        System.out.println(output == Output.json ? JsonSupport.PRETTY_GSON.toJson(value) : text);
    }

    private static String readToken(Path path) {
        try {
            try {
                var permissions = Files.getPosixFilePermissions(path);
                if (permissions.stream().anyMatch(permission -> permission.name().startsWith("GROUP_")
                        || permission.name().startsWith("OTHERS_"))) {
                    throw new ApiException("Token file must not be accessible by group or other users", 4, null, null);
                }
            } catch (UnsupportedOperationException ignored) {
                // Linux is the supported release target; this keeps JVM development portable.
            }
            return Files.readString(path, StandardCharsets.UTF_8).trim();
        }
        catch (IOException e) { throw new ApiException("Unable to read token file: " + e.getMessage(), 4, e); }
    }

    private static String first(String first, String second) { return first != null ? first : second; }

    private static Throwable relevantCause(Throwable error) {
        Throwable value = error;
        while (value.getCause() != null && value.getCause() != value) {
            if (value instanceof ApiException) return value;
            value = value.getCause();
        }
        return value;
    }

    @Command(name = "notification", description = "Send site notifications", subcommands = NotificationSend.class)
    static class NotificationGroup implements Runnable {
        @ParentCommand Application root;
        public void run() { new CommandLine(this).usage(System.out); }
    }
    @Command(name = "send", description = "Send a message-center notification")
    static class NotificationSend implements Callable<Integer> {
        @ParentCommand NotificationGroup group;
        @Option(names = "--title", required = true) String title;
        @Option(names = "--description", defaultValue = "") String description;
        @Option(names = "--key", description = "Stable key to replace an earlier notice") String key;
        @Option(names = "--source", defaultValue = "zrlogctl") String source;
        public Integer call() {
            if (title.isBlank() || title.length() > 120 || description.length() > 2000) throw new ApiException("Invalid notification title or description", 3, null, null);
            var result = group.root.api().sendNotification(new com.zrlog.client.model.NotificationRequest(title, description, key, source));
            group.root.emit(result, "Sent notification " + result.taskKey());
            return 0;
        }
    }

    @Command(name = "article", description = "Manage ZrLog articles", subcommands = {
            ArticleList.class, ArticleGet.class, ArticleDraft.class, ArticlePublish.class,
            ArticleVerify.class, ArticleToken.class, ArticleRevise.class, ArticleStage.class})
    static class ArticleGroup implements Runnable {
        @ParentCommand Application root;
        public void run() { new CommandLine(this).usage(System.out); }
    }

    abstract static class ArticleFileCommand implements Callable<Integer> {
        @ParentCommand ArticleGroup group;
        @Parameters(index = "0", description = "Markdown file with YAML front matter") Path file;
        Application root() { return group.root; }
        ArticleSource source() { return ContentFiles.loadArticle(file); }
    }

    @Command(name = "list", description = "List all admin-visible articles")
    static class ArticleList implements Callable<Integer> {
        @ParentCommand ArticleGroup group;
        public Integer call() {
            List<Article> articles = group.root.api().listArticles();
            if (group.root.output == Output.json) group.root.emit(articles, "");
            else articles.forEach(article -> System.out.printf("%d\t%s\t%s\t%s\t%s%n", article.id(),
                    article.status(), article.typeAlias().isBlank() ? "-" : article.typeAlias(),
                    article.alias(), article.title()));
            return 0;
        }
    }

    @Command(name = "get", description = "Get an article by numeric ID or alias")
    static class ArticleGet implements Callable<Integer> {
        @ParentCommand ArticleGroup group;
        @Parameters(index = "0") String idOrAlias;
        public Integer call() {
            ZrLogApi api = group.root.api();
            Article article;
            try { article = api.getArticle(Long.parseLong(idOrAlias)); }
            catch (NumberFormatException e) { article = api.findUniqueByAlias(idOrAlias); }
            group.root.emit(article, article.markdown());
            return 0;
        }
    }

    @Command(name = "draft", description = "Create or safely update a draft")
    static class ArticleDraft extends ArticleFileCommand {
        @Option(names = "--revision-token") String token;
        public Integer call() {
            ContentService.Result result = root().contentService().saveDraft(source(), token);
            root().emit(result, result.action() + " draft " + result.article().alias());
            return 0;
        }
    }

    @Command(name = "publish", description = "Publish a byte-equivalent managed draft")
    static class ArticlePublish extends ArticleFileCommand {
        public Integer call() {
            ContentService.Result result = root().contentService().publish(source());
            root().emit(result, "published " + result.article().alias());
            return 0;
        }
    }

    @Command(name = "verify", description = "Verify managed fields against the remote article")
    static class ArticleVerify extends ArticleFileCommand {
        @Option(names = "--status", defaultValue = "published") String status;
        public Integer call() {
            ContentService.Result result = root().contentService().verify(source(), status);
            root().emit(result, "verified " + result.article().alias() + " (" + result.article().status() + ")");
            return 0;
        }
    }

    @Command(name = "revision-token", description = "Create a token bound to the current remote snapshot")
    static class ArticleToken extends ArticleFileCommand {
        @Option(names = "--status", defaultValue = "published") String status;
        public Integer call() {
            String token = root().contentService().revisionToken(source().alias(), status);
            root().emit(Map.of("token", token, "alias", source().alias(), "status", status), token);
            return 0;
        }
    }

    @Command(name = "revise", description = "Safely revise an existing published article")
    static class ArticleRevise extends ArticleFileCommand {
        @Option(names = "--revision-token", required = true) String token;
        public Integer call() {
            ContentService.Result result = root().contentService().revise(source(), token);
            root().emit(result, result.action() + " " + result.article().alias());
            return 0;
        }
    }

    @Command(name = "stage-revision", description = "Move a published article to a managed draft revision")
    static class ArticleStage extends ArticleFileCommand {
        @Option(names = "--revision-token", required = true) String token;
        public Integer call() {
            ContentService.Result result = root().contentService().stageRevision(source(), token);
            root().emit(result, "staged draft revision " + result.article().alias());
            return 0;
        }
    }

    @Command(name = "category", description = "Manage article categories", subcommands = {CategoryList.class, CategorySync.class})
    static class CategoryGroup implements Runnable {
        @ParentCommand Application root;
        public void run() { new CommandLine(this).usage(System.out); }
    }

    @Command(name = "list", description = "List categories")
    static class CategoryList implements Callable<Integer> {
        @ParentCommand CategoryGroup group;
        public Integer call() {
            List<Category> categories = group.root.api().listCategories();
            if (group.root.output == Output.json) group.root.emit(categories, "");
            else categories.forEach(category -> System.out.printf("%d\t%s\t%s%n", category.id(), category.alias(), category.name()));
            return 0;
        }
    }

    @Command(name = "sync", description = "Create or update categories from a YAML file")
    static class CategorySync implements Callable<Integer> {
        @ParentCommand CategoryGroup group;
        @Parameters(index = "0") Path file;
        public Integer call() {
            ZrLogApi api = group.root.api();
            List<Category> current = api.listCategories();
            List<Map<String, String>> desiredCategories = ContentFiles.loadCategories(file);
            List<Map<String, Object>> actions = new java.util.ArrayList<>();
            for (Map<String, String> desired : desiredCategories) {
                Category existing = current.stream().filter(item -> item.alias().equals(desired.get("alias"))).findFirst().orElse(null);
                if (existing == null) {
                    api.createCategory(desired);
                    actions.add(action("created", desired.get("alias")));
                } else if (!existing.name().equals(desired.get("name")) || !existing.remark().equals(desired.get("remark"))) {
                    api.updateCategory(existing.id(), desired);
                    actions.add(action("updated", desired.get("alias")));
                } else actions.add(action("kept", desired.get("alias")));
            }
            List<Category> saved = api.listCategories();
            for (Map<String, String> desired : desiredCategories) {
                List<Category> matches = saved.stream()
                        .filter(item -> item.alias().equals(desired.get("alias"))).toList();
                if (matches.size() != 1 || !matches.getFirst().name().equals(desired.get("name"))
                        || !matches.getFirst().remark().equals(desired.get("remark"))) {
                    throw new ApiException("Category " + desired.get("alias")
                            + " differs after synchronization", 6, null, null);
                }
            }
            group.root.emit(actions, actions.size() + " categories synchronized");
            return 0;
        }

        private static Map<String, Object> action(String action, String alias) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("action", action);
            result.put("alias", alias);
            return result;
        }
    }

    @Command(name = "media", description = "Manage media", subcommands = MediaUpload.class)
    static class MediaGroup implements Runnable {
        @ParentCommand Application root;
        public void run() { new CommandLine(this).usage(System.out); }
    }

    @Command(name = "upload", description = "Upload an image")
    static class MediaUpload implements Callable<Integer> {
        @ParentCommand MediaGroup group;
        @Parameters(index = "0") Path file;
        @Option(names = "--dir", defaultValue = "image") String directory;
        public Integer call() {
            String url = group.root.api().upload(file, directory);
            group.root.emit(Map.of("url", url), url);
            return 0;
        }
    }

    @Command(name = "theme", description = "Manage ZrLog themes", subcommands = ThemeUpload.class)
    static class ThemeGroup implements Runnable {
        @ParentCommand Application root;
        public void run() { new CommandLine(this).usage(System.out); }
    }

    @Command(name = "upload", description = "Upload or replace a ZIP package or theme directory")
    static class ThemeUpload implements Callable<Integer> {
        @ParentCommand ThemeGroup group;
        @Parameters(index = "0", description = "ZIP theme package or theme directory") Path file;
        @Option(names = "--overwrite", description = "Replace an existing non-built-in theme") boolean overwrite;
        public Integer call() {
            Theme theme = group.root.api().uploadTheme(file, overwrite);
            group.root.emit(theme, "uploaded theme " + theme.shortTemplate()
                    + (theme.overwritten() ? " (overwritten)" : ""));
            return 0;
        }
    }

    @Command(name = "content", description = "Validate managed content files", subcommands = ContentCheck.class)
    static class ContentGroup implements Runnable {
        @ParentCommand Application root;
        public void run() { new CommandLine(this).usage(System.out); }
    }

    @Command(name = "check", description = "Validate Markdown front matter without connecting to ZrLog")
    static class ContentCheck implements Callable<Integer> {
        @ParentCommand ContentGroup group;
        @Option(names = "--policy", description = "Repository content policy YAML") Path policyFile;
        @Parameters(arity = "1..*") List<Path> files;
        public Integer call() {
            ContentPolicy policy = policyFile == null ? null : ContentPolicy.load(policyFile);
            java.util.Set<String> aliases = new java.util.HashSet<>();
            List<Map<String, Object>> results = files.stream().map(path -> {
                ContentFiles.ArticleDocument document = ContentFiles.loadArticleDocument(path);
                ArticleSource source = document.article();
                if (policy != null) policy.validate(path, document);
                if (!aliases.add(source.alias())) {
                    throw new ApiException("Duplicate article alias " + source.alias(), 3, null, null);
                }
                return Map.<String, Object>of("file", path.toString(), "alias", source.alias(), "valid", true);
            }).toList();
            group.root.emit(results, results.size() + " content files valid");
            return 0;
        }
    }

    @Command(name = "update", description = "Check or apply zrlogctl updates", subcommands = {UpdateCheck.class, UpdateApply.class})
    static class UpdateGroup implements Runnable {
        @ParentCommand Application root;
        public void run() { new CommandLine(this).usage(System.out); }
    }

    @Command(name = "check", description = "Check dl.zrlog.com for an update")
    static class UpdateCheck implements Callable<Integer> {
        @ParentCommand UpdateGroup group;
        public Integer call() {
            UpdateService.Manifest manifest = new UpdateService().check(BuildInfo.VERSION);
            group.root.emit(manifest, manifest.updateAvailable()
                    ? "update available: " + manifest.version() : "zrlogctl is up to date");
            return 0;
        }
    }

    @Command(name = "apply", description = "Download, verify, and atomically replace zrlogctl")
    static class UpdateApply implements Callable<Integer> {
        @ParentCommand UpdateGroup group;
        public Integer call() {
            Path updated = new UpdateService().apply(BuildInfo.VERSION);
            group.root.emit(Map.of("updated", updated != null, "path", updated == null ? "" : updated.toString()),
                    updated == null ? "zrlogctl is up to date" : "updated " + updated);
            return 0;
        }
    }
}
