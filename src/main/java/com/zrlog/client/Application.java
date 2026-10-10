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
import com.zrlog.client.model.Navigation;
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
        subcommands = {Application.ArticleGroup.class, Application.CategoryGroup.class, Application.NavigationGroup.class,
                Application.MediaGroup.class, Application.ThemeGroup.class, Application.PluginGroup.class,
                Application.ContentGroup.class, Application.UpdateGroup.class, Application.ProxyGroup.class,
                Application.Login.class, Application.Logout.class, Application.NotificationGroup.class,
                OpenApiCommands.class})
public class Application implements Runnable {

    @Option(names = "--site", scope = CommandLine.ScopeType.INHERIT,
            description = "ZrLog base URL, including an optional context path (defaults to environment, .env, zrlog.json, or last login)")
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
    Path projectConfigPath = Path.of(ProjectConfig.FILE_NAME);
    private Map<String, String> dotenv;
    java.util.function.Consumer<java.net.URI> browser = OAuthLogin::openBrowser;

    enum Output { text, json }

    public static void main(String[] args) {
        int exitCode = commandLine(new Application()).execute(args);
        if (exitCode != 0) System.exit(exitCode);
    }

    static CommandLine commandLine(Application application) {
        CommandLine commandLine = new CommandLine(application);
        // @path is an API body file, never a picocli file containing more CLI arguments.
        commandLine.setExpandAtFiles(false);
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
            throw new ApiException("Run zrlogctl login --site <URL>, set site_url in zrlog.json, or set ZRLOG_SITE_URL (environment or .env)", 3, null, null);
        }
        if (timeout <= 0) throw new ApiException("--timeout must be greater than zero", 3, null, null);
        try {
            boolean bearer = accessTokenSource();
            if (token == null || token.isBlank()) {
                OAuthLogin login = oauthLogin(resolvedSite);
                OAuthTokens saved = credentialStore(login).update(current -> {
                    if (current == null) throw new ApiException("Run zrlogctl login --site <URL>, or supply a personal access token", 4, null, null);
                    return login.refresh(current);
                });
                token = saved.accessToken(); bearer = true;
            }
            ClientConfig detected = new ClientConfig(java.net.URI.create(resolvedSite), token.trim(), Duration.ofSeconds(timeout));
            ClientConfig config = new ClientConfig(detected.baseUri(), detected.token(), detected.timeout(), bearer || detected.bearer());
            return new ZrLogApi(new ZrLogOpenApiClient(config), this::publishProgress);
        } catch (IllegalArgumentException e) {
            throw new ApiException(e.getMessage(), 3, e);
        }
    }

    ContentService contentService() {
        ZrLogApi api = api();
        return new ContentService(api, api.http().config().baseUri());
    }

    java.net.URI openApiSite() {
        String value = resolvedSite();
        if (value == null || value.isBlank()) throw new ApiException("Set --site, site_url in zrlog.json, or ZRLOG_SITE_URL", 3, null, null);
        try { return ClientConfig.normalizeBaseUri(java.net.URI.create(value)); }
        catch (IllegalArgumentException e) { throw new ApiException(e.getMessage(), 3, e); }
    }

    void publishProgress(String event, com.google.gson.JsonObject data) {
        if (output == Output.json) {
            System.err.println(JsonSupport.GSON.toJson(Map.of("event", event, "data", data)));
            return;
        }
        String message = switch (event) {
            case "article" -> "Article saved; waiting for publication to complete";
            case "publish-start" -> "Publishing article";
            case "static-sync-start" -> "Starting static site sync";
            case "static-progress" -> "Static site sync: " + JsonSupport.number(data, "handled") + "/" + JsonSupport.number(data, "total");
            case "static-sync-complete" -> "Static site sync complete";
            case "static-sync-skipped" -> "Static site sync is disabled";
            case "publish-check-start" -> "Running publish check";
            case "publish-check-complete" -> "Publish check complete";
            case "publish-check-error" -> "Publish check warning: " + JsonSupport.string(data, "message", "check failed");
            case "publish-complete" -> "Publication complete; verifying remote article";
            case "response" -> "Server returned JSON; static site completion is unavailable. Verifying saved article";
            default -> event;
        };
        System.err.println(message);
    }

    private String resolvedSite() {
        if (site != null) return site;
        String configured = environment.get("ZRLOG_SITE_URL");
        if (configured != null) return configured;
        configured = dotenv().get("ZRLOG_SITE_URL");
        if (configured != null) return configured;
        configured = projectConfig().site();
        return configured != null ? configured : siteConfig().defaultSite();
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
    private OAuthLogin oauthLogin(String resolvedSite) {
        if (resolvedSite == null || resolvedSite.isBlank()) throw new ApiException("Set --site, site_url in zrlog.json, or ZRLOG_SITE_URL", 3, null, null);
        if (timeout <= 0) throw new ApiException("--timeout must be greater than zero", 3, null, null);
        try { return new OAuthLogin(java.net.URI.create(resolvedSite), Duration.ofSeconds(timeout)); }
        catch (IllegalArgumentException e) { throw new ApiException(e.getMessage(), 3, e); }
    }
    private CredentialStore credentialStore(OAuthLogin login) {
        return new CredentialStore(configDirectory().resolve("credentials"), login.issuer());
    }
    private Path configDirectory() {
        return ProxyConfig.directory(environment);
    }
    private SiteConfig siteConfig() {
        return new SiteConfig(configDirectory());
    }
    private ProjectConfig projectConfig() {
        return new ProjectConfig(projectConfigPath);
    }

    @Command(name = "proxy", mixinStandardHelpOptions = true,
            description = "Manage saved proxy settings (override environment and runtime proxies)",
            subcommands = {ProxySet.class, ProxyShow.class, ProxyUnset.class})
    static class ProxyGroup implements Runnable {
        @ParentCommand Application root;
        ProxyConfig config() { return new ProxyConfig(root.configDirectory()); }
        public void run() { new CommandLine(this).usage(System.out); }
    }

    @Command(name = "set", mixinStandardHelpOptions = true,
            description = "Save an HTTP proxy for all HTTP/HTTPS requests")
    static class ProxySet implements Callable<Integer> {
        @ParentCommand ProxyGroup group;
        @Parameters(index = "0", paramLabel = "URL", description = "http://[user:password@]host:port or host:port") String url;
        @Option(names = "--no-proxy", defaultValue = "", paramLabel = "HOSTS",
                description = "Comma-separated direct hosts; replaces environment NO_PROXY (default: none)") String noProxy;
        public Integer call() {
            var settings = new ProxyConfig.Settings(url, noProxy);
            ProxyConfig config = group.config();
            config.save(settings);
            group.root.emit(settings.display(), "Saved proxy " + settings.display().get("proxy") + " to " + config.path());
            return 0;
        }
    }

    @Command(name = "show", mixinStandardHelpOptions = true, description = "Show saved proxy settings without credentials")
    static class ProxyShow implements Callable<Integer> {
        @ParentCommand ProxyGroup group;
        public Integer call() {
            var settings = group.config().read();
            group.root.emit(settings == null ? Map.of("configured", false) : settings.display(),
                    settings == null ? "No saved proxy; using environment and runtime settings"
                            : "Proxy: " + settings.display().get("proxy") + "\nNo proxy: " + settings.noProxy()
                            + "\nCredentials: " + (settings.display().get("hasCredentials").equals(true) ? "configured" : "none"));
            return 0;
        }
    }

    @Command(name = "unset", mixinStandardHelpOptions = true, description = "Remove saved proxy settings and use environment/runtime defaults")
    static class ProxyUnset implements Callable<Integer> {
        @ParentCommand ProxyGroup group;
        public Integer call() {
            group.config().clear();
            group.root.emit(Map.of("configured", false), "Saved proxy removed; using environment and runtime settings");
            return 0;
        }
    }

    @Command(name = "login", description = "Authorize zrlogctl through your browser")
    static class Login implements Callable<Integer> {
        private static final List<String> DEFAULT_PERMISSIONS = List.of(
                "article.read", "article.create", "article.update", "article.publish",
                "taxonomy.read", "taxonomy.manage", "asset.upload", "site.configure", "plugin.manage", "notification.create");
        @ParentCommand Application root;
        @Option(names = "--no-browser", description = "Print the authorization URL without opening a browser") boolean noBrowser;
        @Option(names = "--permissions", split = ",", description = "Request only these account action IDs (defaults to article publishing, categories, navigation, uploads, themes, plugins, and notifications)") List<String> permissions;
        @Option(names = "--inherit-permissions", description = "Allow choosing inherited account permissions in the browser instead of the default CLI permissions") boolean inheritPermissions;
        @Option(names = "--wait", defaultValue = "300", description = "Seconds to wait for browser authorization") int wait;
        public Integer call() {
            if (wait < 1 || wait > 1800) throw new ApiException("--wait must be between 1 and 1800 seconds", 3, null, null);
            if (inheritPermissions && permissions != null)
                throw new ApiException("Use only one of --permissions and --inherit-permissions", 3, null, null);
            if (permissions != null && (permissions.isEmpty() || permissions.stream().anyMatch(p -> !p.matches("[a-z_]+(?:\\.[a-z_]+)+"))))
                throw new ApiException("--permissions must contain account action IDs", 3, null, null);
            ProjectConfig project = root.projectConfig();
            project.site();
            OAuthLogin login = root.oauthLogin(root.resolvedSite());
            String scope = (inheritPermissions ? "account:inherit"
                    : String.join(" ", permissions == null ? DEFAULT_PERMISSIONS : permissions)) + " offline_access";
            OAuthTokens tokens = login.login(scope, Duration.ofSeconds(wait), uri -> {
                System.err.println("Open this URL to authorize zrlogctl:\n" + uri);
                if (!noBrowser) root.browser.accept(uri);
            });
            root.credentialStore(login).update(previous -> tokens);
            project.saveSite(login.issuer());
            root.siteConfig().saveDefaultSite(login.issuer());
            root.emit(Map.of("site", login.issuer(), "scope", tokens.scope()), "Signed in to " + login.issuer());
            return 0;
        }
    }
    @Command(name = "logout", description = "Revoke and remove the saved login for this site")
    static class Logout implements Callable<Integer> {
        @ParentCommand Application root;
        public Integer call() {
            OAuthLogin login = root.oauthLogin(root.resolvedSite());
            root.credentialStore(login).update(current -> { if (current != null) login.revoke(current); return null; });
            root.siteConfig().clearDefaultSite(login.issuer());
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

    @Command(name = "article", mixinStandardHelpOptions = true, description = "Manage ZrLog articles", subcommands = {
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

    @Command(name = "list", mixinStandardHelpOptions = true, description = "List all admin-visible articles")
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

    @Command(name = "get", mixinStandardHelpOptions = true, description = "Get an article by numeric ID or alias")
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

    @Command(name = "draft", mixinStandardHelpOptions = true, description = "Create or safely update a draft")
    static class ArticleDraft extends ArticleFileCommand {
        @Option(names = "--revision-token") String token;
        public Integer call() {
            ContentService.Result result = root().contentService().saveDraft(source(), token);
            root().emit(result, result.action() + " draft " + result.article().alias());
            return 0;
        }
    }

    @Command(name = "publish", mixinStandardHelpOptions = true,
            description = "Publish an existing draft that matches the Markdown file, wait for completion, and verify it",
            footer = {"Create the matching draft first: zrlogctl article draft article.md",
                    "Publish it: zrlogctl article publish article.md --timeout 300",
                    "Required front matter: title, alias, category (an existing category alias).",
                    "Requires article.read, article.update, article.publish, and taxonomy.read; draft creation also requires article.create."})
    static class ArticlePublish extends ArticleFileCommand {
        public Integer call() {
            ContentService.Result result = root().contentService().publish(source());
            root().emit(result, "published " + result.article().alias());
            return 0;
        }
    }

    @Command(name = "verify", mixinStandardHelpOptions = true, description = "Verify managed fields against the remote article")
    static class ArticleVerify extends ArticleFileCommand {
        @Option(names = "--status", defaultValue = "published") String status;
        public Integer call() {
            ContentService.Result result = root().contentService().verify(source(), status);
            root().emit(result, "verified " + result.article().alias() + " (" + result.article().status() + ")");
            return 0;
        }
    }

    @Command(name = "revision-token", mixinStandardHelpOptions = true, description = "Create a token bound to the current remote snapshot")
    static class ArticleToken extends ArticleFileCommand {
        @Option(names = "--status", defaultValue = "published") String status;
        public Integer call() {
            String token = root().contentService().revisionToken(source().alias(), status);
            root().emit(Map.of("token", token, "alias", source().alias(), "status", status), token);
            return 0;
        }
    }

    @Command(name = "revise", mixinStandardHelpOptions = true, description = "Safely revise an existing published article")
    static class ArticleRevise extends ArticleFileCommand {
        @Option(names = "--revision-token", required = true) String token;
        public Integer call() {
            ContentService.Result result = root().contentService().revise(source(), token);
            root().emit(result, result.action() + " " + result.article().alias());
            return 0;
        }
    }

    @Command(name = "stage-revision", mixinStandardHelpOptions = true, description = "Move a published article to a managed draft revision")
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

    @Command(name = "nav", mixinStandardHelpOptions = true,
            description = "Manage blog navigation through OpenAPI (requires site.configure)",
            subcommands = {NavigationList.class, NavigationCreate.class, NavigationUpdate.class, NavigationDelete.class})
    static class NavigationGroup implements Runnable {
        @ParentCommand Application root;
        public void run() { new CommandLine(this).usage(System.out); }
    }

    @Command(name = "list", mixinStandardHelpOptions = true, description = "List all navigation entries ordered by sort")
    static class NavigationList implements Callable<Integer> {
        @ParentCommand NavigationGroup group;
        public Integer call() {
            List<Navigation> entries = group.root.api().listNavigation();
            if (group.root.output == Output.json) group.root.emit(entries, "");
            else entries.forEach(entry -> System.out.printf("%d\t%s\t%s\t%s\t%s%n", entry.id(),
                    entry.sort() == null ? "" : entry.sort(), entry.name(), entry.url(), entry.icon() == null ? "" : entry.icon()));
            return 0;
        }
    }

    @Command(name = "create", mixinStandardHelpOptions = true,
            description = "Create a navigation entry; use 'nav list' afterward to find its ID")
    static class NavigationCreate implements Callable<Integer> {
        @ParentCommand NavigationGroup group;
        @Option(names = "--name", required = true, description = "Navigation label") String name;
        @Option(names = "--url", required = true, description = "Navigation URL, e.g. /archive or https://example.com") String url;
        @Option(names = "--icon", defaultValue = "", description = "Icon text (default: empty)") String icon;
        @Option(names = "--sort", defaultValue = "0", description = "Sort value, ascending (default: ${DEFAULT-VALUE})") Long sort;
        public Integer call() {
            group.root.api().createNavigation(name, url, icon, sort);
            group.root.emit(Map.of("action", "created"), "created navigation; use 'nav list' to find its ID");
            return 0;
        }
    }

    @Command(name = "update", mixinStandardHelpOptions = true,
            description = "Update selected fields of an existing navigation entry; omitted fields are read from the server",
            footer = "At least one field is required. The server has no version check; concurrent edits can overwrite each other.")
    static class NavigationUpdate implements Callable<Integer> {
        @ParentCommand NavigationGroup group;
        @Parameters(index = "0", description = "Navigation ID from 'nav list'") long id;
        @Option(names = "--name", description = "New navigation label") String name;
        @Option(names = "--url", description = "New navigation URL") String url;
        @Option(names = "--icon", description = "New icon text; use --icon '' to clear") String icon;
        @Option(names = "--sort", description = "New sort value, ascending") Long sort;
        public Integer call() {
            group.root.api().updateNavigation(id, name, url, icon, sort);
            group.root.emit(Map.of("action", "updated", "id", id), "updated navigation " + id);
            return 0;
        }
    }

    @Command(name = "delete", mixinStandardHelpOptions = true,
            description = "Delete navigation entries by ID; all IDs must exist")
    static class NavigationDelete implements Callable<Integer> {
        @ParentCommand NavigationGroup group;
        @Parameters(index = "0..*", arity = "1..*", split = ",", description = "Navigation IDs, separated by spaces or commas") List<Long> ids;
        public Integer call() {
            group.root.api().deleteNavigation(ids);
            group.root.emit(Map.of("action", "deleted", "ids", ids), "deleted navigation " + ids);
            return 0;
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

    @Command(name = "plugin", description = "Manage ZrLog plugins", subcommands = PluginUpload.class)
    static class PluginGroup implements Runnable {
        @ParentCommand Application root;
        public void run() { new CommandLine(this).usage(System.out); }
    }

    @Command(name = "upload", description = "Upload and register a JAR or native plugin")
    static class PluginUpload implements Callable<Integer> {
        @ParentCommand PluginGroup group;
        @Parameters(index = "0", description = "Plugin file (.jar, .bin, or .exe) matching the server runtime") Path file;
        @Option(names = "--overwrite", description = "Replace and restart an existing plugin") boolean overwrite;
        public Integer call() {
            var plugin = group.root.api().uploadPlugin(file, overwrite);
            group.root.emit(plugin, "uploaded plugin " + plugin.shortName()
                    + (plugin.overwritten() ? " (overwritten)" : ""));
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
