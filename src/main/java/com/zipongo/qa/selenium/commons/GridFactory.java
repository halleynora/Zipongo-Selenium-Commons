package com.zipongo.qa.selenium.commons;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.openqa.selenium.NotFoundException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxProfile;
import org.openqa.selenium.ie.InternetExplorerDriver;
import org.openqa.selenium.remote.CapabilityType;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.remote.UnreachableBrowserException;
import org.openqa.selenium.safari.SafariOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.stream.Collectors;


/**
 * The type Grid factory.
 */
public class GridFactory {

    private final Logger logger = LoggerFactory.getLogger(GridFactory.class);
    private static final String LOCAL_HUB_URL = "http://localhost:4444/wd/hub";
    private static final Integer TIMEOUT_SECONDS = 30;
    private static List<URL> listOfHub;
    private static DesiredCapabilities defaultCapabilities = new DesiredCapabilities();
    private static String currentHub; // the current hub url

    /**
     * Constructor with the default primary and secondary hub
     */
    public GridFactory() {
        listOfHub = new ArrayList<>();
        listOfHub.add(getURL(LOCAL_HUB_URL)
                .orElseThrow(() -> new NotFoundException("Bad URL")));

        logger.info("GridFactory will be use with default hub list: [" + listOfHub + "].");

        // set default capabilities
        addDefaultCapabilities();
    }

    /**
     * Constructor with custom list of grid hub.
     *
     * @param pListOfHub (List<String>)
     */
    public GridFactory(List<String> pListOfHub) {

        // Validate the list of hub and remove bad instance of the list
        listOfHub = validateAndCleanHubList(pListOfHub);

        // if custom list is empty, set the default hub list
        if (listOfHub.isEmpty()) {
            logger.info("The custom hub list was empty, set local hub.");
            listOfHub.add(getURL(LOCAL_HUB_URL).get());
        }
        logger.info("GridFactory will use hub list: " + listOfHub);
        // set default capabilities
        addDefaultCapabilities();
    }

    /**
     * This method test each hub and remove the bad hub definition
     *
     * @param pListOfHub List of Strings of the Selenium hubs
     * @return List of Valid URLs
     */
    private List<URL> validateAndCleanHubList(List<String> pListOfHub) {
        return pListOfHub.stream()
                    .peek(hub->logger.info("Validating URL format of Hub: " + hub))
                    .map(this::getURL)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .collect(Collectors.toList());
    }

    /**
     * Add the common capability to custom capabilities
     */
    private void addDefaultCapabilities()
    {
        defaultCapabilities.setCapability("takeScreenshot", true);
        // get the Jenkins build tag
        String jenkinsBuildTag = System.getenv("BUILD_TAG");
        if (jenkinsBuildTag != null && !jenkinsBuildTag.isEmpty()) {
            defaultCapabilities.setCapability("jenkinsBuildTag", jenkinsBuildTag);
        }
        try {
            String hostName = InetAddress.getLocalHost().getHostName();
            defaultCapabilities.setCapability("hostName", hostName);
        } catch (Exception e) {
            logger.info("Unable to get the Hostname");
        }

    }

    /**
     * Private class to save the Grid URL to the Webdriver
     */
    private class WebDriverHubURL
    {
        private final WebDriver webDriver;
        private final URL url;

        public WebDriverHubURL(WebDriver webDriver, URL url)
        {
            this.webDriver=webDriver;
            this.url=url;
        }

        public WebDriver getWebDriver()
        {
            return webDriver;
        }

        public URL getUrl()
        {
            return url;
        }
    }

    /**
     * This method will try to provide a Webdriver from the list of grid hubs.
     *
     * @param capabilities (DesiredCapabilities)
     * @return (WebDriver) If problem return null.
     * @throws GridFactoryException
     */
    private WebDriver getBrowser(DesiredCapabilities capabilities) throws GridFactoryException {

        // add default capabilities
        capabilities.merge(defaultCapabilities);
        logger.info("Set capabilities: " + capabilities);

        WebDriverHubURL webDriverHubUrl =
                getWebDriverHubURL(listOfHub,capabilities,0)
                    .orElseThrow(()->new GridFactoryException("Could not get WebDriver after 3 attempts"));

        logger.info("Retrieved Remote Webdriver: " + webDriverHubUrl.getWebDriver().toString());

        currentHub = webDriverHubUrl.getUrl().toString();
        return webDriverHubUrl.getWebDriver();

    }

    private Optional<WebDriverHubURL> getWebDriverHubURL(List<URL> listOfHub, DesiredCapabilities cap, int count)
    {
        if (count > 3) return Optional.empty();

        ExecutorService executor = Executors.newCachedThreadPool();

        Optional<WebDriverHubURL> url =
            listOfHub.stream()
                .map(hubUrl -> executor.submit(getTask(hubUrl, cap)))
                .map(this::getDriverFromFuture)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findAny();

        return url.isPresent() ? url : getWebDriverHubURL(listOfHub,cap,count+1);
    }

    /**
     * Provide a Callable object to retrieve a RemoteWebDriver
     *
     * @param hubUrl String URL
     * @param cap Capabilities of the Browser
     * @return Callable object
     */
    private Callable<WebDriverHubURL> getTask(URL hubUrl, DesiredCapabilities cap)
    {
        logger.info("Creating Callable object for Hub URL: " + hubUrl);
        return ()->new WebDriverHubURL(new RemoteWebDriver(hubUrl, cap), hubUrl);
    }

    /**
     * Retrieve a WebDriver object from a Future object
     * with a timeout of GridFactory.TIMEOUT_SECONDS
     *
     * @param f Future object that could return a RemoteWebDriver
     * @return Optional Webdriver object
     */
    private Optional<WebDriverHubURL> getDriverFromFuture(Future<WebDriverHubURL> f)
    {
        try
        {
            return Optional.of(f.get(GridFactory.TIMEOUT_SECONDS, TimeUnit.SECONDS));
        }
        catch (UnreachableBrowserException | TimeoutException|InterruptedException|ExecutionException e)
        {
            logger.error("Was not able to get WebDriver.  Trying again. : " + e.toString());
            return Optional.empty();
        }
    }

    /**
     * Get a Safari instance
     *
     * @return (WebDriver) safari instance
     * @throws GridFactoryException the grid factory exception
     */
    public WebDriver getSafariInstance() throws GridFactoryException {

        SafariOptions options = new SafariOptions();
        options.setUseCleanSession(true);

        DesiredCapabilities capability = DesiredCapabilities.safari();

        capability.setCapability(CapabilityType.ACCEPT_SSL_CERTS, true);
        capability.setCapability(SafariOptions.CAPABILITY, options);
        return getBrowser(capability);
    }

    /**
     * Gets internet explorer instance.
     *
     * @return the internet explorer instance
     * @throws GridFactoryException the grid factory exception
     */
    public WebDriver getInternetExplorerInstance() throws GridFactoryException {

        DesiredCapabilities capability = DesiredCapabilities.internetExplorer();
        capability.setCapability(InternetExplorerDriver.INTRODUCE_FLAKINESS_BY_IGNORING_SECURITY_DOMAINS, true);
        return getBrowser(capability);
    }


    /**
     * Return a WebDriver for FireFox with pProfile If the pProfile is null set the capability with a new empty Profile
     *
     * @param pProfile (FirefoxProfile)
     * @return (WebDriver) firefox instance
     * @throws GridFactoryException the grid factory exception
     * @author Danny.Paradis
     */
    public WebDriver getFirefoxInstance(FirefoxProfile pProfile) throws GridFactoryException {
        FirefoxProfile profile = new FirefoxProfile();
        if (pProfile != null) {
            profile = pProfile;
        }
        DesiredCapabilities capability = DesiredCapabilities.firefox();
        capability.setCapability(CapabilityType.ACCEPT_SSL_CERTS, true); // TODO DPA move to default capability
        capability.setCapability(FirefoxDriver.PROFILE, profile);
        capability.setCapability("acceptInsecureCerts", true);
        capability.setCapability("firefox_profile", profile);
        return getBrowser(capability);

    }


    /**
     * Return a WebDriver for Chrome with pOption If pOtions is null set the capability with a new empty Options
     *
     * @param pOtions (ChromeOptions)
     * @return (WebDriver) chrome instance
     * @throws GridFactoryException the grid factory exception
     */
    public WebDriver getChromeInstance(ChromeOptions pOtions) throws GridFactoryException {
        DesiredCapabilities capability = DesiredCapabilities.chrome();
        ChromeOptions options = new ChromeOptions();
        if (pOtions != null) {
            options = pOtions;
        }

        options.addArguments("--start-maximized");

        capability.setCapability(ChromeOptions.CAPABILITY, options);
        return getBrowser(capability);
    }

    /**
     * Gets phantom js instance.
     *
     * @return the phantom js instance
     * @throws GridFactoryException the grid factory exception
     */
    public WebDriver getPhantomJSInstance() throws GridFactoryException {
        DesiredCapabilities capability = DesiredCapabilities.phantomjs();
        return getBrowser(capability);
    }


    /**
     * Gets current hub.
     *
     * @return the current hub
     */
    public String getCurrentHub() {
        return currentHub;
    }

    /**
     * Simple http get
     *
     * @param pUrl (String)
     * @return (String) response
     */
    private String httpGet(String pUrl) {
        CloseableHttpClient httpclient = HttpClients.createDefault();
        try {
            HttpGet httpget = new HttpGet(pUrl);

            logger.debug("Executing request " + httpget.getRequestLine());

            // Create a custom response handler
            ResponseHandler<String> responseHandler = new ResponseHandler<String>() {

                @Override
                public String handleResponse(final HttpResponse response) throws ClientProtocolException, IOException {
                    int status = response.getStatusLine().getStatusCode();
                    if (status >= 200 && status < 300) {
                        HttpEntity entity = response.getEntity();
                        return entity != null ? EntityUtils.toString(entity) : null;
                    } else {
                        throw new ClientProtocolException("Unexpected response status: " + status);
                    }
                }

            };
            String responseBody = httpclient.execute(httpget, responseHandler);
            logger.debug(responseBody);
            return responseBody;
        } catch (IOException e) {
            logger.error("httpGet error", e);
            return "";
        } finally {
            try {
                httpclient.close();
            } catch (IOException e) {

                logger.error("httpGet error", e);
            }
        }
    }


    /**
     * Provide the URL object given a String url.
     * Return Optional empty if URL is Malformed
     *
     * @param url String representation of the URL
     * @return URL Object
     */
    private Optional<URL> getURL(String url)
    {
        try
        {
            return Optional.of(new URL(url));
        }
        catch (MalformedURLException e)
        {
            logger.warn(url + " was rejected because URL is invalid.");
            return Optional.empty();
        }
    }
}
