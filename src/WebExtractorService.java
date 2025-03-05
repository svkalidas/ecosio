import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.*;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class WebExtractorService {

    private static final Logger LOGGER = Logger.getLogger(WebExtractorService.class.getName());
    private static final Map<String, String> linkMap = new ConcurrentSkipListMap<>();
    //private static final Map<String, String> linkMap = Collections.synchronizedMap(new HashMap<>());

    private final ExecutorService executorService;
    private Set<String> visitedUrls = null;
    private String baseDomain = null;

    public static void main(String[] args) {
        String startUrl = args.length > 0 ? args[0] : "https://ecosio.com";

        WebExtractorService webExtractorService = new WebExtractorService();
        webExtractorService.extractDomains(startUrl);

        System.out.println("Collection of links for: " + startUrl);
        List<String> sortedLinks = linkMap.entrySet()
                .stream()
                .sorted(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        System.out.println(sortedLinks);

    }

    /**
     * Instantiate {@link WebExtractorService }
     * Initialise ExecutorService : {@link ForkJoinPool}.
     */
    private WebExtractorService() {
        this.executorService = Executors.newWorkStealingPool();
    }

    /**
     * @param startUrl is the URL of the website from which links will be extracted.
     */
    private void extractDomains(String startUrl) {

        try {
            this.visitedUrls = ConcurrentHashMap.newKeySet();
            this.baseDomain = new URI(startUrl).getHost();

            extractDomainRecursive(startUrl);

            executorService.shutdown();
            if (!executorService.awaitTermination(2, TimeUnit.MINUTES)) {
                executorService.shutdownNow();
                if (!executorService.awaitTermination(20, TimeUnit.SECONDS)) {
                    LOGGER.log(Level.WARNING, "Executor did not terminate properly.");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.log(Level.WARNING, "Execution interrupted.", e);
        } catch (URISyntaxException e) {
            LOGGER.log(Level.WARNING, "Invalid URL: "+ startUrl, e);
        }
    }

    /**
     * Performing a recursive operation to extract the links form all possible pages form
     * the baseurl/starturl
     * Enabling parallel processing with thread pooling (ForkJoinPool/WorkStealingPool)
     * @param url
     */
    private void extractDomainRecursive(String url) {
        if (visitedUrls.add(url)) { // Ensures the URL is added only once
            executorService.submit(() -> {
                try {
                    extractInternalLinks(url).forEach(this::extractDomainRecursive);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error crawling URL: " + url, e);
                }
            });
        }
    }

    /**
     * Using pattern matching, identify all possible domains/links in the given webpage
     * @param websiteUrl
     * @return set of links extracted from the given webpage.
     */
    private Set<String> extractInternalLinks(String websiteUrl) {
        Set<String> internalLinks = new HashSet<>();
        HttpURLConnection connection = null;

        try {
            URL url = new URL(websiteUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
            connection.setRequestMethod("GET");

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                String htmlContent = reader.lines().collect(Collectors.joining());

                Pattern linkPattern = Pattern.compile(
                        "<a\\s+(?:[^>]*?\\s+)?href=([\"'])(https?://[^\\s\"']+|/[^\\s\"']*)\\1(?:[^>]*?>)(.*?)</a>",
                        Pattern.CASE_INSENSITIVE);
                Matcher matcher = linkPattern.matcher(htmlContent);

                while (matcher.find()) {
                    String link = matcher.group(2);
                    String label = matcher.group(3).replaceAll("<[^>]*>", "").trim(); // Remove HTML tags from label

                    URI linkUri = new URI(link);
                    if (!linkUri.isAbsolute()) {
                        link = url.toURI().resolve(linkUri).toString();
                    }

                    URL absoluteLinkUrl = new URL(link);
                    if (absoluteLinkUrl.getHost().equals(baseDomain)) {
                        internalLinks.add(link);
                    }

                    linkMap.put(absoluteLinkUrl.getHost(), label);
                }
            }
        } catch (MalformedURLException | URISyntaxException e) {
            LOGGER.log(Level.INFO, "Invalid URL: " + websiteUrl);
        } catch (IOException e) {
            LOGGER.log(Level.INFO, "Connection error: " + websiteUrl);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return internalLinks;
    }
}
